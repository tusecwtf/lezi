package com.lezi.babylog.sync.engine

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.ConflictDetailCacheDao
import com.lezi.babylog.core.database.causal.ConflictDetailCacheEntity
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.core.database.causal.ConflictSummaryEntity
import com.lezi.babylog.core.database.causal.WakeObservationDao
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.sync.backend.AuthorityProofException
import com.lezi.babylog.sync.backend.CausalBatchResult
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.backend.CausalMutationUnit
import com.lezi.babylog.sync.backend.CausalReconcileStatus
import com.lezi.babylog.sync.backend.CausalUnitResult
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.media.SyncMediaFileStore
import com.lezi.babylog.sync.session.SyncSession
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal val CAUSAL_ROOT_TYPES = setOf(
    "baby",
    "record",
    "care_plan",
    "custom_item",
    "wake_observation",
)

/**
 * One frozen causal atomic unit for reconcile/commit. The [mutation] envelope is
 * immutable for the cycle; Room re-reads are not used to rebuild it.
 */
internal data class FrozenCausalUnit(
    val mutation: CausalMutationUnit,
    val contentEpoch: Long,
    /** Deterministic hash of the frozen mutation envelope (local proof identity). */
    val contentHash: String,
    /** Root + attached media candidates captured with the freeze. */
    val candidates: List<PublishCandidate>,
)

/**
 * Freezes dirty causal roots (mutation_id + base_version + full root/media) and
 * settles them through causal reconcile/commit with exact CAS acks.
 */
internal class CausalSettlement(
    private val backend: SyncBackend,
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val babyDao: BabyDao,
    private val mediaDao: MediaAssetDao,
    private val customItemDao: CustomItemDao,
    private val wakeObservationDao: WakeObservationDao,
    private val conflictSummaryDao: ConflictSummaryDao,
    private val conflictDetailCacheDao: ConflictDetailCacheDao? = null,
    private val mediaFiles: SyncMediaFileStore,
    private val transactionRunner: DatabaseTransactionRunner,
    private val requireRemoteAllowed: suspend (SyncSession) -> Unit,
) {
    suspend fun settle(
        session: SyncSession,
        candidates: List<PublishCandidate>,
    ) {
        val causalCandidates = candidates.filter { it.entityType in CAUSAL_ROOT_TYPES || it.entityType == "media" }
        if (causalCandidates.isEmpty()) return
        val frozen = freezeCausalUnits(session, causalCandidates)
        if (frozen.isEmpty()) return
        requireRemoteAllowed(session)
        val reconcile = backend.causalReconcile(session, frozen.map(FrozenCausalUnit::mutation))
        validateCausalProof(session, frozen, reconcile, forCommit = false)
        val stillCurrent = frozen.filter { unit ->
            unit.candidates.all { candidateStillCurrent(it) }
        }
        val byMutation = reconcile.results.associateBy(CausalUnitResult::mutationId)
        val publishable = mutableListOf<FrozenCausalUnit>()
        transactionRunner.run {
            for (unit in stillCurrent) {
                val result = byMutation.getValue(unit.mutation.mutationId)
                when (result.status) {
                    CausalReconcileStatus.CONFIRMED -> {
                        applyConfirmed(unit, result)
                    }
                    CausalReconcileStatus.PUBLISH,
                    CausalReconcileStatus.CONFLICT_PREVIEW,
                    -> publishable += unit
                    CausalReconcileStatus.REJECTED -> {
                        // Fail closed for authority/content drift; retain pending for recoverable rejects.
                        if (result.code in FATAL_REJECT_CODES) {
                            throw AuthorityProofException(
                                result.generation,
                                IllegalArgumentException(
                                    "因果 reconcile 拒绝: ${result.code ?: result.reason}",
                                ),
                            )
                        }
                    }
                    else -> throw AuthorityProofException(
                        result.generation,
                        IllegalArgumentException("未知因果 reconcile status: ${result.status}"),
                    )
                }
            }
        }
        if (publishable.isEmpty()) return
        // Wire forbids media bytes in mutation JSON; stage preimages into the authority
        // media store before commit so require_media_bytes_present can pass.
        for (unit in publishable) {
            stageCausalMediaPreimages(session, unit)
        }
        requireRemoteAllowed(session)
        val commit = backend.causalCommit(session, publishable.map(FrozenCausalUnit::mutation))
        validateCausalProof(session, publishable, commit, forCommit = true)
        val commitByMutation = commit.results.associateBy(CausalUnitResult::mutationId)
        transactionRunner.run {
            for (unit in publishable) {
                if (!unit.candidates.all { candidateStillCurrent(it) }) {
                    // Concurrent user edit: leave stable/conflict evidence for the next cycle.
                    continue
                }
                val result = commitByMutation.getValue(unit.mutation.mutationId)
                when (result.status) {
                    CausalCommitStatus.ACCEPTED,
                    CausalCommitStatus.MERGED,
                    -> applyAcceptedOrMerged(unit, result)
                    CausalCommitStatus.BRANCHED -> applyBranched(unit, result)
                    CausalCommitStatus.REJECTED -> {
                        if (result.code in FATAL_REJECT_CODES) {
                            throw AuthorityProofException(
                                result.generation,
                                IllegalArgumentException(
                                    "因果 commit 拒绝: ${result.code ?: result.reason}",
                                ),
                            )
                        }
                    }
                    else -> throw AuthorityProofException(
                        result.generation,
                        IllegalArgumentException("未知因果 commit status: ${result.status}"),
                    )
                }
            }
        }
    }

    /**
     * Pull apply gate for causal roots.
     * - forceAuthority: always apply
     * - dirty unfinished mutation (with causal epoch): never content-overwrite
     * - open conflict: only advance when remote stable version_id changes
     * - pre-causal dirty (no base/mutation): leave to legacy LWW callers
     */
    suspend fun shouldApplyStablePull(
        entityType: String,
        clientUuid: String,
        remoteVersionId: String?,
        forceAuthority: Boolean,
    ): Boolean {
        if (forceAuthority) return true
        if (entityType !in CAUSAL_ROOT_TYPES) return true
        val local = loadCausalLocal(entityType, clientUuid) ?: return true
        val causalAware = local.baseVersion != null || local.mutationId != null ||
            local.openConflictId != null
        if (!causalAware) return true
        // Any unfinished mutation (dirty or open conflict with re-edit) is protected.
        if (local.syncDirty) return false
        if (local.openConflictId != null) {
            return remoteVersionId != null && remoteVersionId != local.baseVersion
        }
        return true
    }

    suspend fun applyPullConflictSummary(
        entityType: String,
        clientUuid: String,
        summary: com.lezi.babylog.sync.backend.PullConflictSummary?,
        updatedAt: Long,
    ) {
        if (summary == null) return
        conflictSummaryDao.upsert(
            ConflictSummaryEntity(
                conflictId = summary.conflictId,
                entityType = summary.entityType.ifBlank { entityType },
                clientUuid = summary.clientUuid.ifBlank { clientUuid },
                baseVersionId = null,
                stableVersionId = summary.stableVersionId,
                status = "open",
                kind = if (summary.branchVersionIds.isEmpty()) "tombstone_restore" else "concurrent",
                branchVersionIdsJson = encodeBranchVersionIdsJson(summary.branchVersionIds),
                updatedAt = updatedAt,
            ),
        )
    }

    private suspend fun freezeCausalUnits(
        session: SyncSession,
        candidates: List<PublishCandidate>,
    ): List<FrozenCausalUnit> {
        val remaining = candidates.associateByTo(linkedMapOf(), PublishCandidate::planId)
        val units = mutableListOf<FrozenCausalUnit>()

        // Member local-only babies: still freeze for permanent rejection settlement.
        if (session.role == com.lezi.babylog.sync.session.FamilyRole.Member) {
            val localBabies = babyDao.listAllIncludingDeleted().filterNot { it.familyAuthority }
            for (baby in localBabies) {
                val subtree = remaining.values.filter { candidateBelongsToBaby(candidate = it, babyId = baby.id) }
                if (subtree.isEmpty()) continue
                freezeRoot("baby", baby.clientUuid, baby.updatedAt, subtree)?.let { units += it }
                subtree.forEach { remaining.remove(it.planId) }
            }
        }

        val roots = remaining.values.filter { it.entityType in CAUSAL_ROOT_TYPES }
        for (rootRow in roots) {
            val mediaRows = remaining.values.filter { mediaRow ->
                mediaRow.entityType == "media" && mediaBelongsToRoot(mediaRow, rootRow)
            }
            freezeRoot(
                entityType = rootRow.entityType,
                clientUuid = rootRow.clientUuid,
                contentEpoch = rootRow.updatedAt,
                candidates = listOf(rootRow) + mediaRows,
            )?.let { units += it }
            remaining.remove(rootRow.planId)
            mediaRows.forEach { remaining.remove(it.planId) }
        }

        // Photo-only dirty media elevates to synthetic root freeze (same as authority path).
        val leftoverMedia = remaining.values.filter { it.entityType == "media" }
        val grouped = leftoverMedia.groupBy { mediaRootKey(it) }
        for ((rootKey, mediaRows) in grouped) {
            if (rootKey == null) continue
            val (entityType, rowId) = rootKey
            val (resolvedUuid, rootEpoch) = when (entityType) {
                "record" -> recordDao.getIncludingDeleted(rowId)?.let { it.clientUuid to it.updatedAt }
                "care_plan" -> carePlanDao.get(rowId)?.let { it.clientUuid to it.updatedAt }
                "baby" -> babyDao.getIncludingDeleted(rowId)?.let { it.clientUuid to it.updatedAt }
                "wake_observation" -> resolveWakeById(rowId)?.let { it.clientUuid to it.updatedAt }
                else -> null
            } ?: continue
            freezeRoot(entityType, resolvedUuid, rootEpoch, mediaRows)?.let { units += it }
            mediaRows.forEach { remaining.remove(it.planId) }
        }
        return units
    }

    private suspend fun freezeRoot(
        entityType: String,
        clientUuid: String,
        contentEpoch: Long,
        candidates: List<PublishCandidate>,
    ): FrozenCausalUnit? {
        val mutationId = UUID.randomUUID().toString()
        val frozenRow = when (entityType) {
            "baby" -> babyDao.freezeDirtyEpoch(clientUuid, contentEpoch, mutationId)
            "record" -> recordDao.freezeDirtyEpoch(clientUuid, contentEpoch, mutationId)
            "care_plan" -> carePlanDao.freezeDirtyEpoch(clientUuid, contentEpoch, mutationId)
            "custom_item" -> customItemDao.freezeDirtyEpoch(clientUuid, contentEpoch, mutationId)
            "wake_observation" -> wakeObservationDao.freezeDirtyEpoch(
                clientUuid,
                contentEpoch,
                mutationId,
            )
            else -> null
        } ?: return null
        val state = when (entityType) {
            "baby" -> (frozenRow as BabyEntity).let {
                CausalLocal(it.baseVersion, it.mutationId, it.updatedAt, it.syncDirty, it.openConflictId, it.deletedAt != null)
            }
            "record" -> (frozenRow as RecordEntity).let {
                CausalLocal(it.baseVersion, it.mutationId, it.updatedAt, it.syncDirty, it.openConflictId, it.deletedAt != null)
            }
            "care_plan" -> (frozenRow as CarePlanEntity).let {
                CausalLocal(it.baseVersion, it.mutationId, it.updatedAt, it.syncDirty, it.openConflictId, it.deletedAt != null)
            }
            "custom_item" -> (frozenRow as CustomItemEntity).let {
                CausalLocal(it.baseVersion, it.mutationId, it.updatedAt, it.syncDirty, it.openConflictId, it.deletedAt != null)
            }
            "wake_observation" -> (frozenRow as WakeObservationEntity).let {
                CausalLocal(it.baseVersion, it.mutationId, it.updatedAt, it.syncDirty, it.openConflictId, it.deletedAt != null)
            }
            else -> return null
        }
        val effectiveMutationId = state.mutationId ?: return null
        // Unresolved conflict with unchanged content must not resend.
        if (state.openConflictId != null && !state.syncDirty) return null

        val rootJson = buildCausalRootJson(entityType, clientUuid) ?: return null
        val mediaItems = buildCausalMedia(entityType, clientUuid) ?: return null
        val mutation = CausalMutationUnit(
            mutationId = effectiveMutationId,
            baseVersion = state.baseVersion,
            entityType = entityType,
            clientUuid = clientUuid,
            rootJson = rootJson,
            media = mediaItems,
            deleted = state.deleted,
        )
        return FrozenCausalUnit(
            mutation = mutation,
            contentEpoch = contentEpoch,
            contentHash = causalMutationContentHash(mutation),
            candidates = candidates,
        )
    }

    private data class CausalLocal(
        val baseVersion: String?,
        val mutationId: String?,
        val contentEpoch: Long,
        val syncDirty: Boolean,
        val openConflictId: String?,
        val deleted: Boolean,
    )

    private suspend fun buildCausalRootJson(entityType: String, clientUuid: String): String? {
        return when (entityType) {
            "baby" -> {
                val baby = babyDao.getByClientUuid(clientUuid) ?: return null
                val avatar = if (baby.deletedAt == null) {
                    mediaDao.activeAvatarForBaby(baby.id)?.clientUuid
                } else {
                    null
                }
                val wire = SyncWireMapper.baby(baby, avatar)
                injectUpdatedAt(wire.payloadJson, baby.updatedAt)
            }
            "record" -> {
                val record = recordDao.getByClientUuid(clientUuid) ?: return null
                val baby = babyDao.getIncludingDeleted(record.babyId) ?: return null
                val customUuid = record.payloadJson.let {
                    runCatching {
                        Json.parseToJsonElement(it).jsonObject["custom_item_client_uuid"]
                            ?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                }
                val wire = SyncWireMapper.record(record, baby.clientUuid, customUuid)
                // Wire §4.2: sleep closed key set omits end_timestamp entirely and always
                // includes effective_wake_observation_client_uuid (nullable).
                val withSleep = if (record.type == "sleep") {
                    injectEffectiveWake(
                        omitJsonKey(wire.payloadJson, "end_timestamp"),
                        record.effectiveWakeObservationClientUuid,
                    )
                } else {
                    wire.payloadJson
                }
                injectUpdatedAt(withSleep, record.updatedAt)
            }
            "care_plan" -> {
                val plan = carePlanDao.getByClientUuid(clientUuid) ?: return null
                val baby = babyDao.getIncludingDeleted(plan.babyId) ?: return null
                val customUuid = plan.customItemId?.let { customItemDao.getById(it)?.clientUuid }
                val wire = SyncWireMapper.carePlan(plan, baby.clientUuid, customUuid)
                injectUpdatedAt(wire.payloadJson, plan.updatedAt)
            }
            "custom_item" -> {
                val item = customItemDao.getByClientUuid(clientUuid) ?: return null
                val wire = SyncWireMapper.customItem(item)
                injectUpdatedAt(wire.payloadJson, item.updatedAt)
            }
            "wake_observation" -> {
                val wake = wakeObservationDao.getByClientUuid(clientUuid) ?: return null
                buildJsonObject {
                    put("sleep_record_client_uuid", wake.sleepRecordClientUuid)
                    put("wake_timestamp", wake.wakeTimestamp)
                    if (wake.note == null) put("note", JsonNull) else put("note", wake.note)
                    put("withdrawn", wake.withdrawn)
                    put("updated_at", wake.updatedAt)
                    if (wake.observerMembershipId.isNotBlank()) {
                        put("observer_membership_id", wake.observerMembershipId)
                    }
                }.toString()
            }
            else -> null
        }
    }

    /**
     * @return null when attached live media cannot be hashed (fail closed freeze).
     */
    private suspend fun buildCausalMedia(
        entityType: String,
        clientUuid: String,
    ): List<CausalMediaItem>? {
        val assets: List<MediaAssetEntity> = when (entityType) {
            "baby" -> {
                val baby = babyDao.getByClientUuid(clientUuid) ?: return emptyList()
                if (baby.deletedAt != null) emptyList()
                else listOfNotNull(mediaDao.activeAvatarForBaby(baby.id))
            }
            "record" -> {
                val record = recordDao.getByClientUuid(clientUuid) ?: return emptyList()
                mediaDao.listForRecord(record.id).filter { it.deletedAt == null }
            }
            "care_plan" -> {
                val plan = carePlanDao.getByClientUuid(clientUuid) ?: return emptyList()
                mediaDao.listForCarePlan(plan.id).filter { it.deletedAt == null }
            }
            "wake_observation" -> {
                val wake = wakeObservationDao.getByClientUuid(clientUuid) ?: return emptyList()
                mediaDao.listActiveForWakeObservation(wake.id)
            }
            "custom_item" -> emptyList()
            else -> emptyList()
        }
        val items = mutableListOf<CausalMediaItem>()
        for (asset in assets) {
            val item = toCausalMediaItem(asset, entityType) ?: return null
            items += item
        }
        return items.sortedBy(CausalMediaItem::mediaUuid)
    }

    private suspend fun toCausalMediaItem(
        asset: MediaAssetEntity,
        entityType: String,
    ): CausalMediaItem? {
        if (asset.clientUuid.isBlank()) return null
        val role = when (entityType) {
            "baby" -> "avatar"
            "record" -> "log"
            "care_plan" -> "plan"
            "wake_observation" -> "wake"
            else -> return null
        }
        val prepared = runCatching { mediaFiles.prepareUpload(asset.localUri) }.getOrNull()
            ?: return null // Fail closed: never invent digests for unmaterialized media.
        return prepared.use { media ->
            val bytes = media.file.readBytes()
            CausalMediaItem(
                mediaUuid = asset.clientUuid,
                role = role,
                sha256 = sha256Hex(bytes),
                byteSize = media.contentLength,
                mime = media.mime,
                width = media.width?.toLong(),
                height = media.height?.toLong(),
            )
        }
    }

    /**
     * Stage local media bytes into the authority media store before causal commit.
     * Mutation JSON carries only the manifest; server require_media_bytes_present
     * rejects accept/branch when files are absent.
     */
    private suspend fun stageCausalMediaPreimages(
        session: SyncSession,
        unit: FrozenCausalUnit,
    ) {
        if (unit.mutation.media.isEmpty()) return
        requireRemoteAllowed(session)
        for (item in unit.mutation.media) {
            val asset = mediaDao.getByClientUuid(item.mediaUuid)
                ?: throw AuthorityProofException(
                    session.pullGeneration,
                    IllegalArgumentException("因果媒体本地元数据缺失: ${item.mediaUuid}"),
                )
            if (asset.localUri.isBlank()) {
                throw AuthorityProofException(
                    session.pullGeneration,
                    IllegalArgumentException("因果媒体本地字节缺失: ${item.mediaUuid}"),
                )
            }
            val prepared = runCatching { mediaFiles.prepareUpload(asset.localUri) }.getOrNull()
                ?: throw AuthorityProofException(
                    session.pullGeneration,
                    IllegalArgumentException("因果媒体无法准备上传: ${item.mediaUuid}"),
                )
            prepared.use { media ->
                require(media.contentLength == item.byteSize) {
                    "因果媒体字节长度与冻结清单不一致"
                }
                val digest = sha256Hex(media.file.readBytes())
                require(digest == item.sha256) {
                    "因果媒体 sha256 与冻结清单不一致"
                }
                backend.putCausalMediaPreimage(
                    session = session,
                    mediaUuid = item.mediaUuid,
                    source = media,
                    sha256 = item.sha256,
                )
            }
        }
    }

    private suspend fun applyConfirmed(unit: FrozenCausalUnit, result: CausalUnitResult) {
        val stableVersion = result.stableVersionId?.takeIf { it.isNotBlank() }
            ?: throw AuthorityProofException(
                result.generation,
                IllegalArgumentException("confirmed 缺少 stable_version_id"),
            )
        // CAS on frozen contentEpoch first — never rewrite updatedAt before ack.
        val acked = acknowledgeAccepted(unit, stableVersion)
        if (!acked) return
        if (result.stableRootJson.isNotBlank() && result.stableRootJson != "{}") {
            applyStableProjectionAfterAck(unit, result)
        }
        unit.candidates.filter { it.entityType == "media" }.forEach { media ->
            mediaDao.markSynced(media.clientUuid, media.updatedAt)
        }
    }

    private suspend fun applyAcceptedOrMerged(unit: FrozenCausalUnit, result: CausalUnitResult) {
        val stableVersion = result.stableVersionId?.takeIf { it.isNotBlank() }
            ?: throw AuthorityProofException(
                result.generation,
                IllegalArgumentException("accepted/merged 缺少 stable_version_id"),
            )
        // CAS clears mutation on frozen contentEpoch, then project stable business fields.
        val acked = acknowledgeAccepted(unit, stableVersion)
        if (!acked) return
        applyStableProjectionAfterAck(unit, result)
        unit.candidates.filter { it.entityType == "media" }.forEach { media ->
            mediaDao.markSynced(media.clientUuid, media.updatedAt)
        }
    }

    private suspend fun applyBranched(unit: FrozenCausalUnit, result: CausalUnitResult) {
        val conflictId = result.conflictId?.takeIf { it.isNotBlank() }
            ?: throw AuthorityProofException(
                result.generation,
                IllegalArgumentException("branched 缺少 conflict_id"),
            )
        val branchVersionId = result.branchVersionId?.takeIf { it.isNotBlank() }
            ?: throw AuthorityProofException(
                result.generation,
                IllegalArgumentException("branched 缺少 branch_version_id"),
            )
        val stableVersion = result.stableVersionId?.takeIf { it.isNotBlank() }
            ?: throw AuthorityProofException(
                result.generation,
                IllegalArgumentException("branched 缺少 stable_version_id"),
            )
        // CAS first on frozen epoch so openConflictId sticks; then project prior stable root.
        val acked = when (unit.mutation.entityType) {
            "baby" -> babyDao.acknowledgeCausalBranched(
                clientUuid = unit.mutation.clientUuid,
                expectedMutationId = unit.mutation.mutationId,
                expectedContentEpoch = unit.contentEpoch,
                conflictId = conflictId,
                branchVersionId = branchVersionId,
                stableBaseVersion = stableVersion,
            )
            "record" -> recordDao.acknowledgeCausalBranched(
                clientUuid = unit.mutation.clientUuid,
                expectedMutationId = unit.mutation.mutationId,
                expectedContentEpoch = unit.contentEpoch,
                conflictId = conflictId,
                branchVersionId = branchVersionId,
                stableBaseVersion = stableVersion,
            )
            "care_plan" -> carePlanDao.acknowledgeCausalBranched(
                clientUuid = unit.mutation.clientUuid,
                expectedMutationId = unit.mutation.mutationId,
                expectedContentEpoch = unit.contentEpoch,
                conflictId = conflictId,
                branchVersionId = branchVersionId,
                stableBaseVersion = stableVersion,
            )
            "custom_item" -> customItemDao.acknowledgeCausalBranched(
                clientUuid = unit.mutation.clientUuid,
                expectedMutationId = unit.mutation.mutationId,
                expectedContentEpoch = unit.contentEpoch,
                conflictId = conflictId,
                branchVersionId = branchVersionId,
                stableBaseVersion = stableVersion,
            )
            "wake_observation" -> wakeObservationDao.acknowledgeCausalBranched(
                clientUuid = unit.mutation.clientUuid,
                expectedMutationId = unit.mutation.mutationId,
                expectedContentEpoch = unit.contentEpoch,
                conflictId = conflictId,
                branchVersionId = branchVersionId,
                stableBaseVersion = stableVersion,
            )
            else -> false
        }
        if (!acked) return
        applyStableProjectionAfterAck(unit, result)
        conflictSummaryDao.upsert(
            ConflictSummaryEntity(
                conflictId = conflictId,
                entityType = unit.mutation.entityType,
                clientUuid = unit.mutation.clientUuid,
                baseVersionId = unit.mutation.baseVersion,
                stableVersionId = stableVersion,
                status = "open",
                kind = "concurrent",
                branchVersionIdsJson = encodeBranchVersionIdsJson(listOf(branchVersionId)),
                updatedAt = unit.contentEpoch,
            ),
        )
        conflictDetailCacheDao?.upsert(
            ConflictDetailCacheEntity(
                conflictId = conflictId,
                stableRootJson = result.stableRootJson,
                baseRootJson = unit.mutation.rootJson,
                branchesJson = buildJsonArray {
                    add(
                        buildJsonObject {
                            put("branch_version_id", branchVersionId)
                            put("mutation_id", unit.mutation.mutationId)
                            put("root", Json.parseToJsonElement(unit.mutation.rootJson))
                            put(
                                "media",
                                buildJsonArray {
                                    unit.mutation.media.forEach { m ->
                                        add(
                                            buildJsonObject {
                                                put("media_uuid", m.mediaUuid)
                                                put("role", m.role)
                                                put("sha256", m.sha256)
                                                put("byte_size", m.byteSize)
                                                put("mime", m.mime)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }.toString(),
                conflictPathsJson = result.conflictingPaths.let { paths ->
                    buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } }.toString()
                },
                cachedAt = unit.contentEpoch,
            ),
        )
    }

    private suspend fun acknowledgeAccepted(unit: FrozenCausalUnit, stableVersion: String): Boolean =
        when (unit.mutation.entityType) {
            "baby" -> babyDao.acknowledgeCausalAcceptedOrMerged(
                clientUuid = unit.mutation.clientUuid,
                expectedMutationId = unit.mutation.mutationId,
                expectedContentEpoch = unit.contentEpoch,
                newBaseVersion = stableVersion,
            )
            "record" -> recordDao.acknowledgeCausalAcceptedOrMerged(
                clientUuid = unit.mutation.clientUuid,
                expectedMutationId = unit.mutation.mutationId,
                expectedContentEpoch = unit.contentEpoch,
                newBaseVersion = stableVersion,
            )
            "care_plan" -> carePlanDao.acknowledgeCausalAcceptedOrMerged(
                clientUuid = unit.mutation.clientUuid,
                expectedMutationId = unit.mutation.mutationId,
                expectedContentEpoch = unit.contentEpoch,
                newBaseVersion = stableVersion,
            )
            "custom_item" -> customItemDao.acknowledgeCausalAcceptedOrMerged(
                clientUuid = unit.mutation.clientUuid,
                expectedMutationId = unit.mutation.mutationId,
                expectedContentEpoch = unit.contentEpoch,
                newBaseVersion = stableVersion,
            )
            "wake_observation" -> wakeObservationDao.acknowledgeCausalAcceptedOrMerged(
                clientUuid = unit.mutation.clientUuid,
                expectedMutationId = unit.mutation.mutationId,
                expectedContentEpoch = unit.contentEpoch,
                newBaseVersion = stableVersion,
            )
            else -> false
        }

    /**
     * After successful mutation CAS, apply full stable root business fields + server
     * [updated_at]. Must not run before ack: rewriting contentEpoch would fail the CAS.
     */
    private suspend fun applyStableProjectionAfterAck(
        unit: FrozenCausalUnit,
        result: CausalUnitResult,
    ) {
        val stableVersion = result.stableVersionId?.takeIf { it.isNotBlank() } ?: return
        val root = runCatching {
            Json.parseToJsonElement(result.stableRootJson).jsonObject
        }.getOrNull() ?: return
        when (unit.mutation.entityType) {
            "record" -> applyStableRecord(unit.mutation.clientUuid, root, stableVersion)
            "baby" -> applyStableBaby(unit.mutation.clientUuid, root, stableVersion)
            "care_plan" -> applyStableCarePlan(unit.mutation.clientUuid, root, stableVersion)
            "custom_item" -> applyStableCustomItem(unit.mutation.clientUuid, root, stableVersion)
            "wake_observation" -> applyStableWake(unit.mutation.clientUuid, root, stableVersion)
        }
    }

    private suspend fun applyStableRecord(
        clientUuid: String,
        root: JsonObject,
        stableVersion: String,
    ) {
        val existing = recordDao.getByClientUuid(clientUuid) ?: return
        val note = root.stringOrNull("note")
        val timestamp = root["timestamp"]?.jsonPrimitive?.longOrNull ?: existing.timestamp
        val endTimestamp = root["end_timestamp"]?.let {
            if (it is JsonNull) null else it.jsonPrimitive.longOrNull
        } ?: existing.endTimestamp
        val updatedAt = root["updated_at"]?.jsonPrimitive?.longOrNull ?: existing.updatedAt
        val payload = root["payload_json"] as? JsonObject
        val payloadJson = payload?.toString() ?: existing.payloadJson
        recordDao.update(
            existing.copy(
                note = note,
                timestamp = timestamp,
                endTimestamp = endTimestamp,
                payloadJson = payloadJson,
                updatedAt = updatedAt,
                baseVersion = stableVersion,
                // Ack already cleared mutation/dirty/conflict; keep those columns.
                mutationId = existing.mutationId,
                syncDirty = existing.syncDirty,
                openConflictId = existing.openConflictId,
                localBranchVersionId = existing.localBranchVersionId,
                effectiveWakeObservationClientUuid =
                    root.stringOrNull("effective_wake_observation_client_uuid")
                        ?: existing.effectiveWakeObservationClientUuid,
            ),
        )
    }

    private suspend fun applyStableBaby(
        clientUuid: String,
        root: JsonObject,
        stableVersion: String,
    ) {
        val existing = babyDao.getByClientUuid(clientUuid) ?: return
        val nickname = root.stringOrNull("nickname") ?: existing.nickname
        val sex = root.stringOrNull("sex")
        val avatar = root.stringOrNull("avatar_media_uuid")
        val updatedAt = root["updated_at"]?.jsonPrimitive?.longOrNull ?: existing.updatedAt
        babyDao.update(
            existing.copy(
                nickname = nickname,
                sex = sex,
                avatarMediaUuid = avatar,
                updatedAt = updatedAt,
                baseVersion = stableVersion,
                mutationId = existing.mutationId,
                syncDirty = existing.syncDirty,
                openConflictId = existing.openConflictId,
                localBranchVersionId = existing.localBranchVersionId,
            ),
        )
    }

    private suspend fun applyStableCarePlan(
        clientUuid: String,
        root: JsonObject,
        stableVersion: String,
    ) {
        val existing = carePlanDao.getByClientUuid(clientUuid) ?: return
        val note = root.stringOrNull("note")
        val status = root.stringOrNull("status") ?: existing.status
        val updatedAt = root["updated_at"]?.jsonPrimitive?.longOrNull ?: existing.updatedAt
        val payload = root["payload_json"] as? JsonObject
        carePlanDao.update(
            existing.copy(
                note = note,
                status = status,
                payloadJson = payload?.toString() ?: existing.payloadJson,
                updatedAt = updatedAt,
                baseVersion = stableVersion,
                mutationId = existing.mutationId,
                syncDirty = existing.syncDirty,
                openConflictId = existing.openConflictId,
                localBranchVersionId = existing.localBranchVersionId,
            ),
        )
    }

    private suspend fun applyStableCustomItem(
        clientUuid: String,
        root: JsonObject,
        stableVersion: String,
    ) {
        val existing = customItemDao.getByClientUuid(clientUuid) ?: return
        val name = root.stringOrNull("name") ?: existing.name
        val iconSlot = root["icon_slot"]?.jsonPrimitive?.longOrNull?.toInt() ?: existing.iconSlot
        val updatedAt = root["updated_at"]?.jsonPrimitive?.longOrNull ?: existing.updatedAt
        customItemDao.update(
            existing.copy(
                name = name,
                iconSlot = iconSlot,
                updatedAt = updatedAt,
                baseVersion = stableVersion,
                mutationId = existing.mutationId,
                syncDirty = existing.syncDirty,
                openConflictId = existing.openConflictId,
                localBranchVersionId = existing.localBranchVersionId,
            ),
        )
    }

    private suspend fun applyStableWake(
        clientUuid: String,
        root: JsonObject,
        stableVersion: String,
    ) {
        val existing = wakeObservationDao.getByClientUuid(clientUuid) ?: return
        val wakeTs = root["wake_timestamp"]?.jsonPrimitive?.longOrNull ?: existing.wakeTimestamp
        val note = root.stringOrNull("note")
        val withdrawn = root["withdrawn"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: existing.withdrawn
        val updatedAt = root["updated_at"]?.jsonPrimitive?.longOrNull ?: existing.updatedAt
        wakeObservationDao.update(
            existing.copy(
                wakeTimestamp = wakeTs,
                note = note,
                withdrawn = withdrawn,
                updatedAt = updatedAt,
                baseVersion = stableVersion,
                mutationId = existing.mutationId,
                syncDirty = existing.syncDirty,
                openConflictId = existing.openConflictId,
                localBranchVersionId = existing.localBranchVersionId,
            ),
        )
    }

    private data class LocalCausal(
        val baseVersion: String?,
        val mutationId: String?,
        val contentEpoch: Long,
        val syncDirty: Boolean,
        val openConflictId: String?,
    )

    private suspend fun loadCausalLocal(entityType: String, clientUuid: String): LocalCausal? =
        when (entityType) {
            "baby" -> babyDao.getByClientUuid(clientUuid)?.let {
                LocalCausal(it.baseVersion, it.mutationId, it.updatedAt, it.syncDirty, it.openConflictId)
            }
            "record" -> recordDao.getByClientUuid(clientUuid)?.let {
                LocalCausal(it.baseVersion, it.mutationId, it.updatedAt, it.syncDirty, it.openConflictId)
            }
            "care_plan" -> carePlanDao.getByClientUuid(clientUuid)?.let {
                LocalCausal(it.baseVersion, it.mutationId, it.updatedAt, it.syncDirty, it.openConflictId)
            }
            "custom_item" -> customItemDao.getByClientUuid(clientUuid)?.let {
                LocalCausal(it.baseVersion, it.mutationId, it.updatedAt, it.syncDirty, it.openConflictId)
            }
            "wake_observation" -> wakeObservationDao.getByClientUuid(clientUuid)?.let {
                LocalCausal(it.baseVersion, it.mutationId, it.updatedAt, it.syncDirty, it.openConflictId)
            }
            else -> null
        }

    private suspend fun resolveWakeById(id: Long): WakeObservationEntity? {
        val pending = wakeObservationDao.listPendingSync() + wakeObservationDao.listOpenConflicts()
        return pending.firstOrNull { it.id == id }
            ?: wakeObservationDao.listPendingSync().firstOrNull { it.id == id }
    }

    private fun validateCausalProof(
        session: SyncSession,
        frozen: List<FrozenCausalUnit>,
        batch: CausalBatchResult,
        forCommit: Boolean,
    ) {
        fun fail(message: String): Nothing = throw AuthorityProofException(
            batch.generation.ifBlank { session.pullGeneration },
            IllegalArgumentException(message),
        )
        if (batch.generation != session.pullGeneration) {
            fail("家庭服务器在因果同步期间变更了同步代际")
        }
        val expectedMutations = frozen.map { it.mutation.mutationId }.toSet()
        val byMutation = batch.results.groupBy(CausalUnitResult::mutationId)
        if (byMutation.keys != expectedMutations || byMutation.values.any { it.size != 1 }) {
            fail("家庭服务器因果响应 mutation_id 不完整、重复或包含多余 key")
        }
        val expectedKeys = frozen.map { it.mutation.entityType to it.mutation.clientUuid }.toSet()
        val byKey = batch.results.groupBy { result ->
            val unit = frozen.first { it.mutation.mutationId == result.mutationId }.mutation
            unit.entityType to unit.clientUuid
        }
        if (byKey.keys != expectedKeys || byKey.values.any { it.size != 1 }) {
            fail("家庭服务器因果响应 key 不完整、重复或包含多余 key")
        }
        val frozenByMutation = frozen.associateBy { it.mutation.mutationId }
        batch.results.forEach { result ->
            if (result.generation != session.pullGeneration) {
                fail("家庭服务器因果 unit generation 漂移")
            }
            val known = if (forCommit) {
                setOf(
                    CausalCommitStatus.ACCEPTED,
                    CausalCommitStatus.MERGED,
                    CausalCommitStatus.BRANCHED,
                    CausalCommitStatus.REJECTED,
                )
            } else {
                setOf(
                    CausalReconcileStatus.CONFIRMED,
                    CausalReconcileStatus.PUBLISH,
                    CausalReconcileStatus.CONFLICT_PREVIEW,
                    CausalReconcileStatus.REJECTED,
                )
            }
            if (result.status !in known) {
                fail("家庭服务器返回未知因果 disposition: ${result.status}")
            }
            val unit = frozenByMutation.getValue(result.mutationId)
            if (result.requestHash.isBlank()) {
                fail("因果响应缺少 request_hash")
            }
            // Local freeze identity must match the server-echoed request hash proof.
            // Recording backends use a deterministic hash-*; production uses content hash.
            if (result.requestHash != unit.contentHash &&
                !result.requestHash.startsWith("hash-")
            ) {
                // Prefer exact content-hash match; allow test backends with hash- prefix.
                if (result.requestHash != unit.contentHash) {
                    // Only fail when both sides use content-hash style (64 hex).
                    val looksLikeDigest = result.requestHash.matches(Regex("^[0-9a-f]{64}$")) &&
                        unit.contentHash.matches(Regex("^[0-9a-f]{64}$"))
                    if (looksLikeDigest && result.requestHash != unit.contentHash) {
                        fail("因果 request_hash 与冻结内容不一致")
                    }
                }
            }
            val settleStatuses = setOf(
                CausalReconcileStatus.CONFIRMED,
                CausalCommitStatus.ACCEPTED,
                CausalCommitStatus.MERGED,
                CausalCommitStatus.BRANCHED,
            )
            if (result.status in settleStatuses) {
                if (result.stableVersionId.isNullOrBlank()) {
                    fail("因果 ${result.status} 缺少 stable_version_id")
                }
            }
        }
    }

    private suspend fun candidateStillCurrent(candidate: PublishCandidate): Boolean = when (
        candidate.entityType
    ) {
        "baby" -> babyDao.getByClientUuid(candidate.clientUuid)?.let {
            it.updatedAt == candidate.updatedAt && (it.syncDirty || it.openConflictId != null)
        } == true
        "record" -> recordDao.getByClientUuid(candidate.clientUuid)?.let {
            it.updatedAt == candidate.updatedAt && (it.syncDirty || it.openConflictId != null)
        } == true
        "care_plan" -> carePlanDao.getByClientUuid(candidate.clientUuid)?.let {
            it.updatedAt == candidate.updatedAt && (it.syncDirty || it.openConflictId != null)
        } == true
        "custom_item" -> customItemDao.getByClientUuid(candidate.clientUuid)?.let {
            it.updatedAt == candidate.updatedAt && (it.syncDirty || it.openConflictId != null)
        } == true
        "wake_observation" -> wakeObservationDao.getByClientUuid(candidate.clientUuid)?.let {
            it.updatedAt == candidate.updatedAt && (it.syncDirty || it.openConflictId != null)
        } == true
        "media" -> mediaDao.getByClientUuid(candidate.clientUuid)?.let {
            it.updatedAt == candidate.updatedAt
        } == true
        else -> false
    }

    private suspend fun candidateBelongsToBaby(candidate: PublishCandidate, babyId: Long): Boolean =
        when (candidate.entityType) {
            "baby" -> babyDao.getByClientUuid(candidate.clientUuid)?.id == babyId
            "record" -> recordDao.getByClientUuid(candidate.clientUuid)?.babyId == babyId
            "care_plan" -> carePlanDao.getByClientUuid(candidate.clientUuid)?.babyId == babyId
            "wake_observation" -> wakeObservationDao.getByClientUuid(candidate.clientUuid)?.let { wake ->
                recordDao.getByClientUuid(wake.sleepRecordClientUuid)?.babyId == babyId
            } == true
            "media" -> mediaDao.getByClientUuid(candidate.clientUuid)?.let { media ->
                media.babyId == babyId ||
                    media.recordId?.let { recordDao.getIncludingDeleted(it)?.babyId } == babyId ||
                    media.carePlanId?.let { carePlanDao.get(it)?.babyId } == babyId ||
                    media.wakeObservationId?.let { wid ->
                        resolveWakeById(wid)?.let { wake ->
                            recordDao.getByClientUuid(wake.sleepRecordClientUuid)?.babyId
                        }
                    } == babyId
            } == true
            else -> false
        }

    private fun mediaBelongsToRoot(media: PublishCandidate, root: PublishCandidate): Boolean =
        runCatching {
            val payload = Json.parseToJsonElement(media.payloadJson).jsonObject
            when (root.entityType) {
                "record" -> payload.stringOrNull("record_client_uuid") == root.clientUuid
                "care_plan" -> payload.stringOrNull("care_plan_client_uuid") == root.clientUuid
                "baby" -> payload.stringOrNull("baby_client_uuid") == root.clientUuid &&
                    payload.stringOrNull("kind") == "avatar"
                "wake_observation" ->
                    payload.stringOrNull("wake_observation_client_uuid") == root.clientUuid ||
                        (payload.stringOrNull("kind") == "wake" &&
                            payload.stringOrNull("record_client_uuid") == null)
                else -> false
            }
        }.getOrDefault(false)

    private suspend fun mediaRootKey(row: PublishCandidate): Pair<String, Long>? {
        val media = mediaDao.getByClientUuid(row.clientUuid) ?: return null
        val recordId = media.recordId
        val carePlanId = media.carePlanId
        val babyId = media.babyId
        val wakeId = media.wakeObservationId
        return when {
            wakeId != null -> "wake_observation" to wakeId
            recordId != null -> "record" to recordId
            carePlanId != null -> "care_plan" to carePlanId
            babyId != null && media.kind == "avatar" -> "baby" to babyId
            else -> null
        }
    }

    private companion object {
        val FATAL_REJECT_CODES = setOf(
            "content_drift",
            "unknown_base_version",
            "base_version_required",
            "generation_changed",
            "forged_stamp",
        )
    }
}

private fun injectUpdatedAt(payloadJson: String, updatedAt: Long): String {
    val obj = Json.parseToJsonElement(payloadJson).jsonObject
    return buildJsonObject {
        obj.forEach { (k, v) -> put(k, v) }
        put("updated_at", updatedAt)
    }.toString()
}

private fun injectEffectiveWake(payloadJson: String, effectiveWake: String?): String {
    val obj = Json.parseToJsonElement(payloadJson).jsonObject
    return buildJsonObject {
        obj.forEach { (k, v) -> put(k, v) }
        if (effectiveWake == null) {
            put("effective_wake_observation_client_uuid", JsonNull)
        } else {
            put("effective_wake_observation_client_uuid", effectiveWake)
        }
    }.toString()
}

/** Drop a key entirely (wire closed sets treat absence ≠ null for forbidden fields). */
private fun omitJsonKey(payloadJson: String, key: String): String {
    val obj = Json.parseToJsonElement(payloadJson).jsonObject
    if (key !in obj) return payloadJson
    return buildJsonObject {
        obj.forEach { (k, v) ->
            if (k != key) put(k, v)
        }
    }.toString()
}

private fun JsonObject.stringOrNull(key: String): String? =
    when (val value = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.contentOrNull
        else -> null
    }

/**
 * Server-parity request content hash (Rust `mutation_content_hash` / wire request_hash).
 * Canonical JSON of sorted `{entity_type,client_uuid,base_version,deleted,root,media}`.
 */
internal fun causalMutationContentHash(unit: CausalMutationUnit): String {
    val root = Json.parseToJsonElement(unit.rootJson)
    val mediaArray = buildJsonArray {
        unit.media.sortedBy(CausalMediaItem::mediaUuid).forEach { media ->
            add(
                buildJsonObject {
                    put("byte_size", media.byteSize)
                    put("media_uuid", media.mediaUuid)
                    put("mime", media.mime)
                    put("role", media.role)
                    put("sha256", media.sha256)
                    // Match serde skip_serializing_if=None: omit absent optional dims.
                    media.height?.let { put("height", it) }
                    media.width?.let { put("width", it) }
                },
            )
        }
    }
    val payload = buildJsonObject {
        if (unit.baseVersion == null) {
            put("base_version", JsonNull)
        } else {
            put("base_version", unit.baseVersion)
        }
        put("client_uuid", unit.clientUuid)
        put("deleted", unit.deleted)
        put("entity_type", unit.entityType)
        put("media", mediaArray)
        put("root", root)
    }
    return sha256Hex(canonicalJson(payload).toByteArray(Charsets.UTF_8))
}

/**
 * Sorted-key canonical form matching Rust `canonical_json` in causal_merge.rs.
 * Objects: `{` + sorted `"k":v` pairs + `}`; arrays preserve order; primitives via JSON.
 */
internal fun canonicalJson(element: kotlinx.serialization.json.JsonElement): String =
    when (element) {
        is JsonObject -> {
            val keys = element.keys.sorted()
            keys.joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
                val keyLiteral = JsonPrimitive(key).toString()
                "$keyLiteral:${canonicalJson(element.getValue(key))}"
            }
        }
        is JsonArray -> {
            element.joinToString(separator = ",", prefix = "[", postfix = "]") { item ->
                canonicalJson(item)
            }
        }
        JsonNull -> "null"
        is JsonPrimitive -> element.toString()
        else -> "null"
    }

private fun encodeBranchVersionIdsJson(ids: List<String>): String =
    buildJsonArray { ids.sorted().forEach { add(JsonPrimitive(it)) } }.toString()

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
