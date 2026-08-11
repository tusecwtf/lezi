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
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.core.database.causal.ConflictSummaryEntity
import com.lezi.babylog.core.database.causal.CommitFirstSettlementEpoch
import com.lezi.babylog.core.database.causal.WakeObservationDao
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.core.database.causal.frozenMediaSpoolCacheKey
import com.lezi.babylog.core.model.isNextFeedPlanNote
import com.lezi.babylog.sync.backend.AuthorityProofException
import com.lezi.babylog.sync.backend.CausalBatchResult
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.backend.CausalMutationUnit
import com.lezi.babylog.sync.backend.CausalProofBatch
import com.lezi.babylog.sync.backend.CausalReconcileStatus
import com.lezi.babylog.sync.backend.CausalProofUnit
import com.lezi.babylog.sync.backend.CausalUnitResult
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.media.ImmutableMediaSpool
import com.lezi.babylog.sync.media.ImmutableMediaSpoolGroup
import com.lezi.babylog.sync.media.ImmutableMediaSpoolItem
import com.lezi.babylog.sync.media.ImmutableMediaSpoolRecovery
import com.lezi.babylog.sync.media.ImmutableMediaSpoolSource
import com.lezi.babylog.sync.media.CausalMediaPolicy
import com.lezi.babylog.sync.media.encodeImmutableMediaSpoolGroup
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

private val MEDIA_COMMIT_FIRST_ROOT_TYPES = setOf("baby", "record", "care_plan")

/** Reconcile-only generation evidence; contracted commit units carry none. */
private fun CausalProofUnit.authorityGeneration() =
    (this as? CausalUnitResult)?.generation.orEmpty()

/**
 * One frozen causal atomic unit. Migrated roots restore one immutable Room-backed
 * envelope; roots not yet migrated retain the source reconcile path.
 */
private data class FrozenCausalUnit(
    val mutation: CausalMutationUnit,
    val contentEpoch: Long,
    /** Deterministic hash of the frozen mutation envelope (local proof identity). */
    val contentHash: String,
    /** Root + attached media candidates captured with the freeze. */
    val candidates: List<PublishCandidate>,
    /** Active attachment revisions materialized into [mutation]. */
    val mediaSnapshot: List<CausalMediaRevision>,
    /** True only when [mutation] is owned by the durable commit-first envelope. */
    val durableCommitFirst: Boolean = false,
    /** True only after every media receipt and the commit attempt are durable. */
    val durableMediaCommitUnknown: Boolean = false,
)

private data class CausalMediaRevision(
    val id: Long,
    val clientUuid: String,
    val localUri: String,
    val recordId: Long?,
    val carePlanId: Long?,
    val wakeObservationId: Long?,
    val babyId: Long?,
    val kind: String,
    val updatedAt: Long,
)

private fun ImmutableMediaSpoolItem.toCausalMediaItem() = CausalMediaItem(
    mediaUuid = mediaUuid,
    role = role.wireName,
    sha256 = sha256,
    byteSize = byteSize,
    mime = mime,
    width = width,
    height = height,
)

/** Local immutable-envelope proof failure: never trigger authority recovery or pull. */
private class FrozenCommitProofException(message: String) : IllegalStateException(message)

/**
 * Freezes dirty causal roots (mutation_id + base_version + full root/media) and
 * settles them through the per-root migration path with exact CAS acks.
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
    private val conflictSnapshotCacheDao: ConflictSnapshotCacheDao? = null,
    private val immutableMediaSpool: ImmutableMediaSpool,
    private val transactionRunner: DatabaseTransactionRunner,
    private val requireRemoteAllowed: suspend (SyncSession) -> Unit,
    private val protectDirtyCausalRoots: Boolean = true,
) {
    private val mediaSettlementJournal = conflictSnapshotCacheDao?.let { cache ->
        CausalMediaSettlementJournalOwner(cache, immutableMediaSpool, transactionRunner)
    }

    /**
     * Cold-start gate: sidecars for pending/Room-referenced mutations are recovered before the
     * mutable source repair path runs; everything else is an unreferenced local orphan.
     */
    suspend fun recoverImmutableMediaSpool(): Set<String> {
        val cache = conflictSnapshotCacheDao ?: return emptySet()
        mediaSettlementJournal?.finishPendingCleanups()
        val roomGroups = cache.listFrozenMediaSpoolManifests().map { row ->
            val group = decodeFrozenMediaSpoolManifest(row.snapshotJson)
            require(row.conflictId == frozenMediaSpoolCacheKey(group.mutationId)) {
                "Room media spool key does not bind its payload mutation"
            }
            group.mutationId to group
        }
        require(roomGroups.map { it.first }.distinct().size == roomGroups.size) {
            "Room media spool contains duplicate payload mutations"
        }
        val roomGroupsByMutation = roomGroups.toMap()
        val pendingMutationIds = buildSet {
            babyDao.listPendingSync().mapNotNullTo(this) { it.mutationId }
            recordDao.listPendingSync().mapNotNullTo(this) { it.mutationId }
            carePlanDao.listPendingSync().mapNotNullTo(this) { it.mutationId }
            customItemDao.listPendingSync().mapNotNullTo(this) { it.mutationId }
            wakeObservationDao.listPendingSync().mapNotNullTo(this) { it.mutationId }
        }
        val recovered = immutableMediaSpool.recoverAndSweep(
            roomGroupsByMutation.keys + pendingMutationIds,
        )
        roomGroupsByMutation.forEach { (mutationId, manifest) ->
            require((recovered[mutationId] as? ImmutableMediaSpoolRecovery.Complete)?.group == manifest) {
                "Room media spool manifest lost its immutable sidecar evidence"
            }
        }
        return recovered.values.flatMapTo(linkedSetOf()) { state ->
            state.group.items.map(ImmutableMediaSpoolItem::mediaUuid)
        }
    }

    suspend fun settle(
        session: SyncSession,
        candidates: List<PublishCandidate>,
    ) {
        val causalCandidates = candidates.filter { it.entityType in CAUSAL_ROOT_TYPES || it.entityType == "media" }
        if (causalCandidates.isEmpty()) return
        val frozen = freezeCausalUnits(session, causalCandidates)
        if (frozen.isEmpty()) return
        frozen.chunked(MAX_CAUSAL_SETTLEMENT_UNITS).forEach { batch ->
            settleBatch(session, batch)
        }
    }

    /** Keeps both direct commit and source reconcile batches within the 64-root bound. */
    private suspend fun settleBatch(
        session: SyncSession,
        frozen: List<FrozenCausalUnit>,
    ) {
        val directCommit = frozen.filter(FrozenCausalUnit::durableCommitFirst)
        if (directCommit.isNotEmpty()) {
            commitFirst(session, directCommit)
        }
        val mediaCommitUnknown = frozen.filter(FrozenCausalUnit::durableMediaCommitUnknown)
        if (mediaCommitUnknown.isNotEmpty()) {
            replayMediaCommitUnknown(session, mediaCommitUnknown)
        }
        val sourceReconcile = frozen - directCommit.toSet() - mediaCommitUnknown.toSet()
        if (sourceReconcile.isEmpty()) return
        require(sourceReconcile.none { it.mutation.entityType in MEDIA_COMMIT_FIRST_ROOT_TYPES }) {
            "Migrated media roots must not use source reconcile"
        }
        reconcileFirst(session, sourceReconcile)
    }

    /** Lost-response recovery: replay the exact durable envelope before a superseding local edit. */
    private suspend fun replayMediaCommitUnknown(
        session: SyncSession,
        frozen: List<FrozenCausalUnit>,
    ) {
        require(frozen.all { it.durableMediaCommitUnknown && it.mutation.media.isNotEmpty() })
        requireRemoteAllowed(session)
        val commit = backend.causalCommit(session, frozen.map(FrozenCausalUnit::mutation))
        validateCausalProof(
            session = session,
            frozen = frozen,
            batch = commit,
            forCommit = true,
            localFrozenProof = true,
        )
        val byMutation = commit.results.associateBy(CausalProofUnit::mutationId)
        transactionRunner.run {
            frozen.forEach { unit ->
                applyCommitFirstResult(unit, byMutation.getValue(unit.mutation.mutationId))
            }
        }
        frozen.forEach { unit ->
            val result = byMutation.getValue(unit.mutation.mutationId)
            if (result.status == CausalCommitStatus.ACCEPTED ||
                result.status == CausalCommitStatus.MERGED
            ) {
                mediaSettlementJournal?.finishCleanup(unit.mutation.mutationId)
            }
        }
    }

    private suspend fun commitFirst(
        session: SyncSession,
        frozen: List<FrozenCausalUnit>,
    ) = commitPreparedCausalUnits(
        session = session,
        frozen = frozen,
        localFrozenProof = true,
    ) { unit, result ->
        applyCommitFirstResult(unit, result)
    }

    /**
     * One ordering owner for receipt completion, commit-unknown durability, commit proof,
     * transactional settlement, and terminal spool cleanup.
     */
    private suspend fun commitPreparedCausalUnits(
        session: SyncSession,
        frozen: List<FrozenCausalUnit>,
        localFrozenProof: Boolean,
        settleResult: suspend (FrozenCausalUnit, CausalProofUnit) -> Unit,
    ) {
        for (unit in frozen) {
            stageCausalMediaPreimages(session, unit)
        }
        frozen.filter { it.mutation.media.isNotEmpty() }.forEach { unit ->
            requireNotNull(mediaSettlementJournal) {
                "causal media commit requires durable settlement journal"
            }.markCommitUnknown(unit.mutation.mutationId)
        }
        requireRemoteAllowed(session)
        val commit = backend.causalCommit(session, frozen.map(FrozenCausalUnit::mutation))
        validateCausalProof(
            session = session,
            frozen = frozen,
            batch = commit,
            forCommit = true,
            localFrozenProof = localFrozenProof,
        )
        val commitByMutation = commit.results.associateBy(CausalProofUnit::mutationId)
        transactionRunner.run {
            for (unit in frozen) {
                settleResult(unit, commitByMutation.getValue(unit.mutation.mutationId))
            }
        }
        frozen.forEach { unit ->
            val result = commitByMutation.getValue(unit.mutation.mutationId)
            if (unit.mutation.media.isNotEmpty() &&
                (result.status == CausalCommitStatus.ACCEPTED ||
                    result.status == CausalCommitStatus.MERGED)
            ) {
                mediaSettlementJournal?.finishCleanup(unit.mutation.mutationId)
            }
        }
    }

    private suspend fun reconcileFirst(
        session: SyncSession,
        frozen: List<FrozenCausalUnit>,
    ) {
        requireRemoteAllowed(session)
        val reconcile = backend.causalReconcile(session, frozen.map(FrozenCausalUnit::mutation))
        validateCausalProof(session, frozen, reconcile, forCommit = false)
        val stillCurrent = frozen.filter { unitStillCurrent(it) }
        val byMutation = reconcile.results.associateBy(CausalProofUnit::mutationId)
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
                                result.authorityGeneration(),
                                IllegalArgumentException(
                                    "因果 reconcile 拒绝: ${result.code ?: result.reason}",
                                ),
                            )
                        }
                    }
                    else -> throw AuthorityProofException(
                        result.authorityGeneration(),
                        IllegalArgumentException("未知因果 reconcile status: ${result.status}"),
                    )
                }
            }
        }
        if (publishable.isEmpty()) return
        commitPreparedCausalUnits(session, publishable, localFrozenProof = false) { unit, result ->
            if (unitStillCurrent(unit)) {
                applyCommitResult(unit, result)
            }
        }
    }

    private suspend fun applyCommitResult(
        unit: FrozenCausalUnit,
        result: CausalProofUnit,
    ) {
        when (result.status) {
            CausalCommitStatus.ACCEPTED,
            CausalCommitStatus.MERGED,
            -> applyAcceptedOrMerged(unit, result)
            CausalCommitStatus.BRANCHED -> applyBranched(unit, result)
            else -> throw AuthorityProofException(
                result.authorityGeneration(),
                IllegalArgumentException("未知因果 commit status: ${result.status}"),
            )
        }
    }

    private suspend fun applyCommitFirstResult(
        unit: FrozenCausalUnit,
        result: CausalProofUnit,
    ) {
        require(
            (unit.durableCommitFirst || unit.durableMediaCommitUnknown) &&
                unit.mutation.entityType in COMMIT_FIRST_ROOT_TYPES,
        ) {
            "commit-first settlement only owns migrated roots"
        }
        val cache = requireNotNull(conflictSnapshotCacheDao) {
            "commit-first settlement requires durable envelope storage"
        }
        val stableVersion = result.stableVersionId?.takeIf { it.isNotBlank() }
        when (result.status) {
            CausalCommitStatus.ACCEPTED,
            CausalCommitStatus.MERGED,
            -> {
                val version = stableVersion ?: throw AuthorityProofException(
                    result.authorityGeneration(),
                    IllegalArgumentException("accepted/merged 缺少 stable_version_id"),
                )
                val settled = settleCommitFirstAcceptedOrMerged(unit, version) ?: return
                if (settled == CommitFirstSettlementEpoch.CurrentEpoch) {
                    applyStableProjectionAfterAck(unit, result)
                    settleMigratedMediaCandidates(unit)
                }
                settleCommitFirstEvidence(unit, result, cache)
            }
            CausalCommitStatus.BRANCHED -> {
                val conflictId = result.conflictId?.takeIf { it.isNotBlank() }
                    ?: throw AuthorityProofException(
                        result.authorityGeneration(),
                        IllegalArgumentException("branched 缺少 conflict_id"),
                    )
                val branchVersionId = result.branchVersionId?.takeIf { it.isNotBlank() }
                    ?: throw AuthorityProofException(
                        result.authorityGeneration(),
                        IllegalArgumentException("branched 缺少 branch_version_id"),
                    )
                val version = stableVersion ?: throw AuthorityProofException(
                    result.authorityGeneration(),
                    IllegalArgumentException("branched 缺少 stable_version_id"),
                )
                val settled = settleCommitFirstBranched(
                    unit = unit,
                    conflictId = conflictId,
                    branchVersionId = branchVersionId,
                    stableBaseVersion = version,
                ) ?: return
                if (settled == CommitFirstSettlementEpoch.CurrentEpoch) {
                    applyStableProjectionAfterAck(unit, result)
                    settleMigratedMediaCandidates(unit)
                }
                conflictSummaryDao.upsert(
                    ConflictSummaryEntity(
                        conflictId = conflictId,
                        entityType = unit.mutation.entityType,
                        clientUuid = unit.mutation.clientUuid,
                        baseVersionId = unit.mutation.baseVersion,
                        stableVersionId = version,
                        status = "open",
                        kind = "concurrent",
                        branchVersionIdsJson = encodeBranchVersionIdsJson(listOf(branchVersionId)),
                        updatedAt = unit.contentEpoch,
                    ),
                )
                settleCommitFirstEvidence(unit, result, cache)
            }
            else -> applyCommitResult(unit, result)
        }
    }

    /** One terminal owner for empty roots and roots with a durable media journal. */
    private suspend fun settleCommitFirstEvidence(
        unit: FrozenCausalUnit,
        result: CausalProofUnit,
        cache: ConflictSnapshotCacheDao,
    ) {
        if (unit.mutation.media.isEmpty()) {
            cache.deleteFrozenMutation(unit.mutation.entityType, unit.mutation.clientUuid)
        } else {
            requireNotNull(mediaSettlementJournal).markTerminal(unit.mutation.mutationId, result)
        }
    }

    /** H21-H23 share settlement for Record, Baby, and CarePlan media; Wake stays pending. */
    private suspend fun settleMigratedMediaCandidates(unit: FrozenCausalUnit) {
        if (unit.mutation.entityType !in MEDIA_COMMIT_FIRST_ROOT_TYPES) return
        unit.candidates.filter { it.entityType == "media" }.forEach { media ->
            mediaDao.markSynced(media.clientUuid, media.updatedAt)
        }
    }

    private suspend fun settleCommitFirstAcceptedOrMerged(
        unit: FrozenCausalUnit,
        stableBaseVersion: String,
    ): CommitFirstSettlementEpoch? = when (unit.mutation.entityType) {
        "baby" -> babyDao.settleCommitFirstAcceptedOrMerged(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            stableBaseVersion,
        )
        "record" -> recordDao.settleCommitFirstAcceptedOrMerged(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            stableBaseVersion,
        )
        "care_plan" -> carePlanDao.settleCommitFirstAcceptedOrMerged(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            stableBaseVersion,
        )
        "custom_item" -> customItemDao.settleCommitFirstAcceptedOrMerged(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            stableBaseVersion,
        )
        "wake_observation" -> wakeObservationDao.settleCommitFirstAcceptedOrMerged(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            stableBaseVersion,
        )
        else -> null
    }

    private suspend fun settleCommitFirstBranched(
        unit: FrozenCausalUnit,
        conflictId: String,
        branchVersionId: String,
        stableBaseVersion: String,
    ): CommitFirstSettlementEpoch? = when (unit.mutation.entityType) {
        "baby" -> babyDao.settleCommitFirstBranched(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            conflictId,
            branchVersionId,
            stableBaseVersion,
        )
        "record" -> recordDao.settleCommitFirstBranched(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            conflictId,
            branchVersionId,
            stableBaseVersion,
        )
        "care_plan" -> carePlanDao.settleCommitFirstBranched(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            conflictId,
            branchVersionId,
            stableBaseVersion,
        )
        "custom_item" -> customItemDao.settleCommitFirstBranched(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            conflictId,
            branchVersionId,
            stableBaseVersion,
        )
        "wake_observation" -> wakeObservationDao.settleCommitFirstBranched(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            conflictId,
            branchVersionId,
            stableBaseVersion,
        )
        else -> null
    }

    /**
     * Pull apply gate for causal roots.
     * - forceAuthority: always apply
     * - dirty unfinished mutation: never content-overwrite
     * - migrated pre-causal dirty root: capture the pulled stable version as its
     *   causal baseline without changing local content or clearing pending
     * - open conflict: only advance when remote stable version_id changes
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
        if (local.syncDirty && protectDirtyCausalRoots) {
            if (local.baseVersion == null && remoteVersionId != null) {
                establishMigratedDirtyBaseline(
                    entityType = entityType,
                    clientUuid = clientUuid,
                    expectedContentEpoch = local.contentEpoch,
                    remoteVersionId = remoteVersionId,
                )
            }
            return false
        }
        val causalAware = local.baseVersion != null || local.mutationId != null ||
            local.openConflictId != null
        if (!causalAware) return true
        if (local.openConflictId != null) {
            return remoteVersionId != null && remoteVersionId != local.baseVersion
        }
        return true
    }

    /**
     * Room 26 dirty rows have no causal identity. The first causal full pull is the
     * only authoritative place to learn the stable parent they were edited from.
     * Persist only that parent; the local revision, tombstone, media references and
     * dirty intent remain untouched and are reconciled immediately after the pull.
     *
     * A previously frozen null-base mutation cannot be reused after the envelope
     * gains a base_version, so clear only its mutation identity. Freeze will mint a
     * new stable id for the now-baselined envelope.
     */
    private suspend fun establishMigratedDirtyBaseline(
        entityType: String,
        clientUuid: String,
        expectedContentEpoch: Long,
        remoteVersionId: String,
    ) {
        when (entityType) {
            "baby" -> babyDao.getByClientUuid(clientUuid)?.let { current ->
                if (current.syncDirty && current.updatedAt == expectedContentEpoch &&
                    current.baseVersion == null
                ) {
                    babyDao.update(current.copy(baseVersion = remoteVersionId, mutationId = null))
                }
            }
            "record" -> recordDao.getByClientUuid(clientUuid)?.let { current ->
                if (current.syncDirty && current.updatedAt == expectedContentEpoch &&
                    current.baseVersion == null
                ) {
                    recordDao.update(current.copy(baseVersion = remoteVersionId, mutationId = null))
                }
            }
            "care_plan" -> carePlanDao.getByClientUuid(clientUuid)?.let { current ->
                if (current.syncDirty && current.updatedAt == expectedContentEpoch &&
                    current.baseVersion == null
                ) {
                    carePlanDao.update(current.copy(baseVersion = remoteVersionId, mutationId = null))
                }
            }
            "custom_item" -> customItemDao.getByClientUuid(clientUuid)?.let { current ->
                if (current.syncDirty && current.updatedAt == expectedContentEpoch &&
                    current.baseVersion == null
                ) {
                    customItemDao.update(current.copy(baseVersion = remoteVersionId, mutationId = null))
                }
            }
            "wake_observation" -> wakeObservationDao.getByClientUuid(clientUuid)?.let { current ->
                if (current.syncDirty && current.updatedAt == expectedContentEpoch &&
                    current.baseVersion == null
                ) {
                    wakeObservationDao.update(
                        current.copy(baseVersion = remoteVersionId, mutationId = null),
                    )
                }
            }
        }
    }

    suspend fun applyPullConflictSummary(
        entityType: String,
        clientUuid: String,
        summary: com.lezi.babylog.sync.backend.PullConflictSummary?,
        updatedAt: Long,
    ) {
        val prior = conflictSummaryDao.listForRoot(entityType, clientUuid)
        if (summary == null) {
            prior.forEach { stale ->
                conflictSnapshotCacheDao?.deleteConflictState(stale.conflictId)
                conflictSummaryDao.delete(stale.conflictId)
            }
            return
        }
        prior.filter { it.conflictId != summary.conflictId }.forEach { stale ->
            conflictSnapshotCacheDao?.deleteConflictState(stale.conflictId)
            conflictSummaryDao.delete(stale.conflictId)
        }
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
        return units.sortedBy { unit ->
            ROOT_DEPENDENCY_PRIORITY.getValue(unit.mutation.entityType)
        }
    }

    private suspend fun freezeRoot(
        entityType: String,
        clientUuid: String,
        contentEpoch: Long,
        candidates: List<PublishCandidate>,
    ): FrozenCausalUnit? {
        mediaSettlementJournal?.restoreUnsettled(entityType, clientUuid)?.let { journal ->
            val current = loadCommitFirstLocal(entityType, clientUuid)
                ?: error("durable media commit lost its product fact")
            require(
                current.syncDirty && current.contentEpoch >= journal.binding.contentEpoch,
            ) { "durable media commit no longer owns a pending fact" }
            val sameFactEpoch = current.contentEpoch == journal.binding.contentEpoch
            if (journal.phase == CausalMediaSettlementPhase.Pending && !sameFactEpoch) {
                return null
            }
            return FrozenCausalUnit(
                mutation = journal.mutation,
                contentEpoch = journal.binding.contentEpoch,
                contentHash = journal.binding.requestHash,
                candidates = if (sameFactEpoch) candidates else emptyList(),
                mediaSnapshot = if (sameFactEpoch) {
                    loadActiveCausalMedia(entityType, clientUuid).toCausalMediaRevisions()
                } else {
                    emptyList()
                },
                durableMediaCommitUnknown =
                    journal.phase == CausalMediaSettlementPhase.CommitUnknown,
                durableCommitFirst =
                    journal.mutation.entityType in MEDIA_COMMIT_FIRST_ROOT_TYPES &&
                        journal.phase == CausalMediaSettlementPhase.Pending,
            )
        }
        if (entityType == "wake_observation") {
            freezeEmptyMediaCommitEnvelope(
                entityType = entityType,
                clientUuid = clientUuid,
                contentEpoch = contentEpoch,
                candidates = candidates,
            )?.let { return it }
            // Wake has no reconcile-first fallback. Media-bearing observations
            // wait for the immutable spool/receipt owner (H18/H20).
            return null
        } else if (entityType == "care_plan") {
            freezeEmptyMediaCommitEnvelope(
                entityType = entityType,
                clientUuid = clientUuid,
                contentEpoch = contentEpoch,
                candidates = candidates,
            )?.let { return it }
            // A complete empty manifest that cannot yet satisfy its dependency
            // order must wait; falling through would resurrect reconcile-first.
            if (!hasCarePlanMediaPublishOrRepairEvidence(clientUuid, candidates)) return null
            val cache = conflictSnapshotCacheDao ?: return null
            if (!carePlanDependenciesReady(clientUuid, cache)) return null
        } else if (entityType in COMMIT_FIRST_ROOT_TYPES) {
            freezeEmptyMediaCommitEnvelope(
                entityType = entityType,
                clientUuid = clientUuid,
                contentEpoch = contentEpoch,
                candidates = candidates,
            )?.let { return it }
        }
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
        val frozenMedia = freezeCausalMedia(
            entityType = entityType,
            clientUuid = clientUuid,
            mutationId = effectiveMutationId,
            contentEpoch = contentEpoch,
        ) ?: return null
        val mutation = CausalMutationUnit(
            mutationId = effectiveMutationId,
            baseVersion = state.baseVersion,
            entityType = entityType,
            clientUuid = clientUuid,
            rootJson = rootJson,
            media = frozenMedia.items,
            deleted = state.deleted,
        )
        val contentHash = causalMutationContentHash(mutation)
        frozenMedia.group?.let { group ->
            requireNotNull(mediaSettlementJournal) {
                "causal media freeze requires durable settlement journal"
            }.bind(
                mutation = mutation,
                contentEpoch = contentEpoch,
                requestHash = contentHash,
                manifest = group,
            )
        }
        return FrozenCausalUnit(
            mutation = mutation,
            contentEpoch = contentEpoch,
            contentHash = contentHash,
            candidates = candidates,
            mediaSnapshot = frozenMedia.revisions,
            durableCommitFirst = entityType in MEDIA_COMMIT_FIRST_ROOT_TYPES,
        )
    }

    /**
     * Freezes or restores one migrated empty-media root in one Room transaction.
     * An existing envelope always wins over the mutable fact so process death and
     * response loss replay byte-identical intent before a later edit is frozen.
     */
    private suspend fun freezeEmptyMediaCommitEnvelope(
        entityType: String,
        clientUuid: String,
        contentEpoch: Long,
        candidates: List<PublishCandidate>,
    ): FrozenCausalUnit? {
        val cache = conflictSnapshotCacheDao ?: return null
        return transactionRunner.run {
            cache.getFrozenMutation(entityType, clientUuid)?.let { stored ->
                val restored = decodeFrozenCommitEnvelope(stored.snapshotJson)
                require(restored.contentEpoch == stored.cachedAt) {
                    "frozen commit envelope epoch metadata drift"
                }
                require(
                    restored.mutation.entityType == entityType &&
                        restored.mutation.clientUuid == clientUuid &&
                        restored.mutation.media.isEmpty(),
                ) {
                    "frozen commit envelope storage identity drift"
                }
                val current = loadCommitFirstLocal(entityType, clientUuid)
                    ?: error("frozen commit envelope lost its product fact")
                if (entityType == "wake_observation") {
                    require(
                        wakeMutationReferencesReady(
                            clientUuid = clientUuid,
                            rootJson = restored.mutation.rootJson,
                            cache = cache,
                        ),
                    ) {
                        "frozen Wake envelope lost its exact Sleep source dependency"
                    }
                }
                val identityStillOwned = current.mutationId == restored.mutation.mutationId ||
                    (current.contentEpoch > restored.contentEpoch && current.mutationId == null)
                require(
                    current.syncDirty &&
                        current.contentEpoch >= restored.contentEpoch &&
                        identityStillOwned,
                ) {
                    "frozen commit envelope no longer owns the pending fact"
                }
                return@run FrozenCausalUnit(
                    mutation = restored.mutation,
                    contentEpoch = restored.contentEpoch,
                    contentHash = restored.requestHash,
                    candidates = candidates,
                    mediaSnapshot = emptyList(),
                    durableCommitFirst = true,
                )
            }

            val current = loadCommitFirstLocal(entityType, clientUuid) ?: return@run null
            if (!current.syncDirty || current.contentEpoch != contentEpoch) return@run null
            if (entityType == "baby" && babyDao.getByClientUuid(clientUuid)?.familyAuthority != true) {
                return@run null
            }
            if (loadActiveCausalMedia(entityType, clientUuid).isNotEmpty()) return@run null
            if (entityType == "record" && !recordProvidersReady(clientUuid, cache)) return@run null
            if (entityType == "care_plan" && !carePlanDependenciesReady(clientUuid, cache)) {
                return@run null
            }
            if (entityType == "wake_observation" &&
                !wakeObservationDependenciesReady(clientUuid, cache)
            ) {
                return@run null
            }
            val mutationId = UUID.randomUUID().toString()
            val frozen = freezeCommitFirstIdentity(
                entityType = entityType,
                clientUuid = clientUuid,
                contentEpoch = contentEpoch,
                mutationId = mutationId,
            ) ?: return@run null
            val rootJson = buildCausalRootJson(entityType, clientUuid) ?: return@run null
            val mutation = CausalMutationUnit(
                mutationId = requireNotNull(frozen.mutationId),
                baseVersion = frozen.baseVersion,
                entityType = entityType,
                clientUuid = clientUuid,
                rootJson = rootJson,
                media = emptyList(),
                deleted = frozen.deleted,
            )
            val requestHash = causalMutationContentHash(mutation)
            cache.putFrozenMutation(
                entityType = entityType,
                clientUuid = clientUuid,
                canonicalEnvelopeJson = encodeFrozenCommitEnvelope(
                    mutation = mutation,
                    contentEpoch = contentEpoch,
                    requestHash = requestHash,
                ),
                contentEpoch = contentEpoch,
            )
            FrozenCausalUnit(
                mutation = mutation,
                contentEpoch = contentEpoch,
                contentHash = requestHash,
                candidates = candidates,
                mediaSnapshot = emptyList(),
                durableCommitFirst = true,
            )
        }
    }

    private data class CausalLocal(
        val baseVersion: String?,
        val mutationId: String?,
        val contentEpoch: Long,
        val syncDirty: Boolean,
        val openConflictId: String?,
        val deleted: Boolean,
    )

    private suspend fun loadCommitFirstLocal(
        entityType: String,
        clientUuid: String,
    ): CausalLocal? = when (entityType) {
        "baby" -> babyDao.getByClientUuid(clientUuid)?.let {
            CausalLocal(
                it.baseVersion,
                it.mutationId,
                it.updatedAt,
                it.syncDirty,
                it.openConflictId,
                it.deletedAt != null,
            )
        }
        "record" -> recordDao.getByClientUuid(clientUuid)?.let {
            CausalLocal(
                it.baseVersion,
                it.mutationId,
                it.updatedAt,
                it.syncDirty,
                it.openConflictId,
                it.deletedAt != null,
            )
        }
        "care_plan" -> carePlanDao.getByClientUuid(clientUuid)?.let {
            CausalLocal(
                it.baseVersion,
                it.mutationId,
                it.updatedAt,
                it.syncDirty,
                it.openConflictId,
                it.deletedAt != null,
            )
        }
        "custom_item" -> customItemDao.getByClientUuid(clientUuid)?.let {
            CausalLocal(
                it.baseVersion,
                it.mutationId,
                it.updatedAt,
                it.syncDirty,
                it.openConflictId,
                it.deletedAt != null,
            )
        }
        "wake_observation" -> wakeObservationDao.getByClientUuid(clientUuid)?.let {
            CausalLocal(
                it.baseVersion,
                it.mutationId,
                it.updatedAt,
                it.syncDirty,
                it.openConflictId,
                it.deletedAt != null,
            )
        }
        else -> null
    }

    private suspend fun freezeCommitFirstIdentity(
        entityType: String,
        clientUuid: String,
        contentEpoch: Long,
        mutationId: String,
    ): CausalLocal? = when (entityType) {
        "baby" -> babyDao.freezeCommitFirstEpoch(clientUuid, contentEpoch, mutationId)?.let {
            CausalLocal(
                it.baseVersion,
                it.mutationId,
                it.updatedAt,
                it.syncDirty,
                it.openConflictId,
                it.deletedAt != null,
            )
        }
        "record" -> recordDao.freezeCommitFirstEpoch(clientUuid, contentEpoch, mutationId)?.let {
            CausalLocal(
                it.baseVersion,
                it.mutationId,
                it.updatedAt,
                it.syncDirty,
                it.openConflictId,
                it.deletedAt != null,
            )
        }
        "care_plan" -> carePlanDao.freezeCommitFirstEpoch(clientUuid, contentEpoch, mutationId)?.let {
            CausalLocal(
                it.baseVersion,
                it.mutationId,
                it.updatedAt,
                it.syncDirty,
                it.openConflictId,
                it.deletedAt != null,
            )
        }
        "custom_item" ->
            customItemDao.freezeCommitFirstEpoch(clientUuid, contentEpoch, mutationId)?.let {
                CausalLocal(
                    it.baseVersion,
                    it.mutationId,
                    it.updatedAt,
                    it.syncDirty,
                    it.openConflictId,
                    it.deletedAt != null,
                )
            }
        "wake_observation" ->
            wakeObservationDao.freezeCommitFirstEpoch(clientUuid, contentEpoch, mutationId)?.let {
                CausalLocal(
                    it.baseVersion,
                    it.mutationId,
                    it.updatedAt,
                    it.syncDirty,
                    it.openConflictId,
                    it.deletedAt != null,
                )
            }
        else -> null
    }

    private suspend fun recordProvidersReady(
        recordClientUuid: String,
        cache: ConflictSnapshotCacheDao,
    ): Boolean {
        val record = recordDao.getByClientUuid(recordClientUuid) ?: return false
        val baby = babyDao.getIncludingDeleted(record.babyId) ?: return false
        if (!baby.familyAuthority) return false
        if (baby.syncDirty && !cache.hasCurrentFrozenProvider("baby", baby.clientUuid, baby.mutationId)) {
            return false
        }
        val customItemUuid = resolveRecordCustomItemClientUuid(record, customItemDao) ?: return true
        val customItem = customItemDao.getByClientUuid(customItemUuid) ?: return false
        return !customItem.syncDirty || cache.hasCurrentFrozenProvider(
            "custom_item",
            customItem.clientUuid,
            customItem.mutationId,
        )
    }

    private suspend fun carePlanDependenciesReady(
        carePlanClientUuid: String,
        cache: ConflictSnapshotCacheDao,
    ): Boolean {
        val root = buildCausalRootJson("care_plan", carePlanClientUuid) ?: return false
        val wire = runCatching {
            decodeCarePlanWire(
                Json.parseToJsonElement(root).jsonObject,
                RootUpdatedAtLocation.InlineStableRoot,
                requireCanonicalIds = true,
            )
        }.getOrElse { return false }
        val references = resolveCarePlanReferences(
            wire = wire,
            babyDao = babyDao,
            customItemDao = customItemDao,
            recordDao = recordDao,
        ) ?: return false
        val baby = references.baby
        if (!baby.familyAuthority) return false
        if (baby.syncDirty && !cache.hasCurrentFrozenProvider("baby", baby.clientUuid, baby.mutationId)) {
            return false
        }
        val customItem = references.customItem
        if (customItem?.syncDirty == true &&
            !cache.hasCurrentFrozenProvider("custom_item", customItem.clientUuid, customItem.mutationId)
        ) {
            return false
        }
        val record = references.fulfilledRecord ?: return true
        return !record.syncDirty || cache.hasCurrentFrozenProvider(
            "record",
            record.clientUuid,
            record.mutationId,
        )
    }

    /** Wake keeps the exact Sleep Record source UUID and never commits a dangling reference. */
    private suspend fun wakeObservationDependenciesReady(
        wakeClientUuid: String,
        cache: ConflictSnapshotCacheDao,
    ): Boolean {
        val rootJson = buildCausalRootJson("wake_observation", wakeClientUuid) ?: return false
        return wakeMutationReferencesReady(
            clientUuid = wakeClientUuid,
            rootJson = rootJson,
            cache = cache,
        )
    }

    private suspend fun wakeMutationReferencesReady(
        clientUuid: String,
        rootJson: String,
        cache: ConflictSnapshotCacheDao,
    ): Boolean {
        val wake = wakeObservationDao.getByClientUuid(clientUuid) ?: return false
        val wire = runCatching {
            decodeWakeRootWire(
                Json.parseToJsonElement(rootJson).jsonObject,
                WakeRootWireShape.LocalMutation,
            )
        }.getOrElse { return false }
        val sleep = resolveWakeReference(
            wire = wire,
            recordDao = recordDao,
            expectedSleepClientUuid = wake.sleepRecordClientUuid,
        ) ?: return false
        return !sleep.syncDirty || cache.hasCurrentFrozenProvider(
            "record",
            sleep.clientUuid,
            sleep.mutationId,
        )
    }

    private suspend fun ConflictSnapshotCacheDao.hasCurrentFrozenProvider(
        entityType: String,
        clientUuid: String,
        currentMutationId: String?,
    ): Boolean {
        val stored = getFrozenMutation(entityType, clientUuid) ?: return false
        val frozen = runCatching { decodeFrozenCommitEnvelope(stored.snapshotJson) }.getOrNull()
            ?: return false
        return frozen.mutation.entityType == entityType &&
            frozen.mutation.clientUuid == clientUuid &&
            frozen.mutation.mutationId == currentMutationId &&
            frozen.mutation.media.isEmpty()
    }

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
                val customUuid = resolveRecordCustomItemClientUuid(record, customItemDao)
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
                encodeWakeMutationRoot(wake)
            }
            else -> null
        }
    }

    /**
     * @return null when attached live media cannot be hashed (fail closed freeze).
     */
    private data class FrozenCausalMedia(
        val items: List<CausalMediaItem>,
        val revisions: List<CausalMediaRevision>,
        val group: ImmutableMediaSpoolGroup?,
    )

    private suspend fun freezeCausalMedia(
        entityType: String,
        clientUuid: String,
        mutationId: String,
        contentEpoch: Long,
    ): FrozenCausalMedia? {
        val assets = loadActiveCausalMedia(entityType, clientUuid)
        val revisions = assets.toCausalMediaRevisions()
        if (assets.isEmpty()) return FrozenCausalMedia(emptyList(), revisions, null)
        val cache = conflictSnapshotCacheDao ?: return null
        val expectedSources = assets.sortedBy(MediaAssetEntity::clientUuid).map { asset ->
            ImmutableMediaSpoolSource(
                mediaUuid = asset.clientUuid,
                role = CausalMediaPolicy.roleForEntityType(entityType) ?: return null,
                localUri = asset.localUri,
            )
        }
        val stored = cache.getFrozenMediaSpoolManifest(mutationId)?.let { row ->
            decodeFrozenMediaSpoolManifest(row.snapshotJson).also { group ->
                require(group.mutationId == mutationId) { "Room media spool manifest identity drift" }
                require(row.cachedAt == contentEpoch) { "Room media spool manifest epoch drift" }
            }
        }
        val sidecarOnly = if (stored == null) {
            immutableMediaSpool.recoverGroup(mutationId)?.group
        } else {
            null
        }
        if (sidecarOnly != null && !sidecarOnly.items.all { item ->
                expectedSources.any { source ->
                    source.mediaUuid == item.mediaUuid && source.role == item.role
                }
            }
        ) {
            return null
        }
        val group = if (stored != null) {
            require(
                (immutableMediaSpool.recoverGroup(mutationId) as?
                    ImmutableMediaSpoolRecovery.Complete)?.group == stored,
            ) {
                "Room media spool manifest does not match immutable sidecars"
            }
            stored
        } else {
            immutableMediaSpool.freezeGroup(mutationId, expectedSources).also { frozen ->
                if (loadActiveCausalMedia(entityType, clientUuid).toCausalMediaRevisions() != revisions) {
                    return null
                }
                transactionRunner.run {
                    val current = requireNotNull(loadCommitFirstLocal(entityType, clientUuid)) {
                        "media spool manifest lost its product fact"
                    }
                    require(current.mutationId == mutationId && current.contentEpoch == contentEpoch) {
                        "media spool manifest lost its pending mutation owner"
                    }
                    cache.putFrozenMediaSpoolManifest(
                        mutationId = mutationId,
                        canonicalManifestJson = encodeImmutableMediaSpoolGroup(frozen),
                        contentEpoch = contentEpoch,
                    )
                }
            }
        }
        if (group.items.map { it.mediaUuid to it.role } !=
            expectedSources.map { it.mediaUuid to it.role }
        ) return null
        if (loadActiveCausalMedia(entityType, clientUuid).toCausalMediaRevisions() != revisions) {
            return null
        }
        return FrozenCausalMedia(
            items = group.items.map(ImmutableMediaSpoolItem::toCausalMediaItem),
            revisions = revisions,
            group = group,
        )
    }

    private suspend fun loadActiveCausalMedia(
        entityType: String,
        clientUuid: String,
    ): List<MediaAssetEntity> = when (entityType) {
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

    /** Distinguishes a pending attachment/tombstone group from a dependency-blocked empty plan. */
    private suspend fun hasCarePlanMediaPublishOrRepairEvidence(
        clientUuid: String,
        candidates: List<PublishCandidate>,
    ): Boolean {
        if (candidates.any { it.entityType == "media" }) return true
        val plan = carePlanDao.getByClientUuid(clientUuid) ?: return false
        return mediaDao.listForCarePlan(plan.id).any { media ->
            media.syncDirty || media.deletedAt == null
        }
    }

    private fun List<MediaAssetEntity>.toCausalMediaRevisions(): List<CausalMediaRevision> =
        map { asset ->
            CausalMediaRevision(
                id = asset.id,
                clientUuid = asset.clientUuid,
                localUri = asset.localUri,
                recordId = asset.recordId,
                carePlanId = asset.carePlanId,
                wakeObservationId = asset.wakeObservationId,
                babyId = asset.babyId,
                kind = asset.kind,
                updatedAt = asset.updatedAt,
            )
        }.sortedWith(compareBy(CausalMediaRevision::clientUuid).thenBy(CausalMediaRevision::id))

    private suspend fun unitStillCurrent(unit: FrozenCausalUnit): Boolean =
        unit.candidates.all { candidateStillCurrent(it) } &&
            loadActiveCausalMedia(
                unit.mutation.entityType,
                unit.mutation.clientUuid,
            ).toCausalMediaRevisions() == unit.mediaSnapshot

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
        val cache = requireNotNull(conflictSnapshotCacheDao) {
            "causal media upload requires durable Room manifest storage"
        }
        val group = cache.getFrozenMediaSpoolManifest(unit.mutation.mutationId)?.let { row ->
            decodeFrozenMediaSpoolManifest(row.snapshotJson)
        } ?: throw AuthorityProofException(
            session.pullGeneration,
            IllegalArgumentException("因果媒体缺少不可变 spool manifest"),
        )
        require(group.items.map(ImmutableMediaSpoolItem::toCausalMediaItem) == unit.mutation.media) {
            "因果媒体 spool manifest 与冻结 mutation 不一致"
        }
        for (item in unit.mutation.media) {
            val spoolItem = group.items.single { it.mediaUuid == item.mediaUuid }
            val existingReceipt = requireNotNull(mediaSettlementJournal) {
                "causal media upload requires settlement journal"
            }.preparedReceipt(unit.mutation.mutationId, spoolItem)
            if (existingReceipt != null) continue
            val source = immutableMediaSpool.open(unit.mutation.mutationId, spoolItem)
            val receipt = backend.putCausalMediaPreimage(
                session = session,
                mediaUuid = item.mediaUuid,
                source = source,
                sha256 = item.sha256,
            )
            mediaSettlementJournal.recordPrepared(unit.mutation.mutationId, receipt)
        }
    }

    private suspend fun applyConfirmed(unit: FrozenCausalUnit, result: CausalProofUnit) {
        val stableVersion = result.stableVersionId?.takeIf { it.isNotBlank() }
            ?: throw AuthorityProofException(
                result.authorityGeneration(),
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

    private suspend fun applyAcceptedOrMerged(unit: FrozenCausalUnit, result: CausalProofUnit) {
        val stableVersion = result.stableVersionId?.takeIf { it.isNotBlank() }
            ?: throw AuthorityProofException(
                result.authorityGeneration(),
                IllegalArgumentException("accepted/merged 缺少 stable_version_id"),
            )
        // CAS clears mutation on frozen contentEpoch, then project stable business fields.
        val acked = acknowledgeAccepted(unit, stableVersion)
        if (!acked) return
        applyStableProjectionAfterAck(unit, result)
        unit.candidates.filter { it.entityType == "media" }.forEach { media ->
            mediaDao.markSynced(media.clientUuid, media.updatedAt)
        }
        if (unit.mutation.media.isNotEmpty()) {
            requireNotNull(mediaSettlementJournal).markTerminal(unit.mutation.mutationId, result)
        }
    }

    private suspend fun applyBranched(unit: FrozenCausalUnit, result: CausalProofUnit) {
        val conflictId = result.conflictId?.takeIf { it.isNotBlank() }
            ?: throw AuthorityProofException(
                result.authorityGeneration(),
                IllegalArgumentException("branched 缺少 conflict_id"),
            )
        val branchVersionId = result.branchVersionId?.takeIf { it.isNotBlank() }
            ?: throw AuthorityProofException(
                result.authorityGeneration(),
                IllegalArgumentException("branched 缺少 branch_version_id"),
            )
        val stableVersion = result.stableVersionId?.takeIf { it.isNotBlank() }
            ?: throw AuthorityProofException(
                result.authorityGeneration(),
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
        if (unit.mutation.media.isNotEmpty()) {
            requireNotNull(mediaSettlementJournal).markTerminal(unit.mutation.mutationId, result)
        }
        // A commit response is not a complete ConflictSnapshot receipt. Persist
        // only the pull/list summary; detail is fetched and projected losslessly.
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
        result: CausalProofUnit,
    ) {
        val stableVersion = result.stableVersionId?.takeIf { it.isNotBlank() } ?: return
        val root = runCatching {
            Json.parseToJsonElement(result.stableRootJson).jsonObject
        }.getOrNull() ?: return
        when (unit.mutation.entityType) {
            "record" -> applyStableRecord(
                unit.mutation.clientUuid,
                root,
                stableVersion,
                result.stableDeletedAt,
            )
            "baby" -> applyStableBaby(
                unit.mutation.clientUuid,
                root,
                stableVersion,
                result.stableDeletedAt,
            )
            "care_plan" -> applyStableCarePlan(
                unit.mutation.clientUuid,
                root,
                stableVersion,
                deleted = result.stableDeleted,
                deletedAt = result.stableDeletedAt,
            )
            "custom_item" -> applyStableCustomItem(
                unit.mutation.clientUuid,
                root,
                stableVersion,
                result.stableDeletedAt,
            )
            "wake_observation" -> applyStableWake(
                unit.mutation.clientUuid,
                root,
                stableVersion,
                result.stableDeletedAt,
            )
        }
    }

    private suspend fun applyStableRecord(
        clientUuid: String,
        root: JsonObject,
        stableVersion: String,
        deletedAt: Long?,
    ) {
        val existing = recordDao.getByClientUuid(clientUuid) ?: return
        val note = root.stringOrNull("note")
        val timestamp = root["timestamp"]?.jsonPrimitive?.longOrNull ?: existing.timestamp
        val endTimestamp = if ("end_timestamp" in root) {
            root["end_timestamp"]?.let { value ->
                if (value is JsonNull) null else value.jsonPrimitive.longOrNull
            }
        } else {
            existing.endTimestamp
        }
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
                deletedAt = deletedAt,
                baseVersion = stableVersion,
                // Ack already cleared mutation/dirty/conflict; keep those columns.
                mutationId = existing.mutationId,
                syncDirty = existing.syncDirty,
                openConflictId = existing.openConflictId,
                localBranchVersionId = existing.localBranchVersionId,
                effectiveWakeObservationClientUuid =
                    if ("effective_wake_observation_client_uuid" in root) {
                        root.stringOrNull("effective_wake_observation_client_uuid")
                    } else {
                        existing.effectiveWakeObservationClientUuid
                    },
            ),
        )
    }

    private suspend fun applyStableBaby(
        clientUuid: String,
        root: JsonObject,
        stableVersion: String,
        deletedAt: Long?,
    ) {
        val existing = babyDao.getByClientUuid(clientUuid) ?: return
        val wire = decodeBabyWire(root, RootUpdatedAtLocation.InlineStableRoot)
        babyDao.update(
            existing.copy(
                nickname = wire.nickname,
                sex = wire.sex,
                birthdayEpochDay = wire.birthdayEpochDay,
                birthWeightGrams = wire.birthWeightGrams,
                avatarMediaUuid = wire.avatarMediaUuid,
                updatedAt = requireNotNull(wire.inlineUpdatedAt),
                deletedAt = deletedAt,
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
        deleted: Boolean,
        deletedAt: Long?,
    ) {
        val existing = carePlanDao.getByClientUuid(clientUuid) ?: return
        val wire = decodeCarePlanWire(
            root,
            RootUpdatedAtLocation.InlineStableRoot,
            requireCanonicalIds = true,
        )
        val references = requireNotNull(
            resolveCarePlanReferences(
                wire = wire,
                babyDao = babyDao,
                customItemDao = customItemDao,
                recordDao = recordDao,
            ),
        ) { "care_plan stable_root 引用无效或跨 Baby" }
        val customItemId = references.customItem?.id
        val localPayload = SyncWireMapper.localPayloadFromWire(
            type = wire.type,
            payload = wire.payload,
            customItemId = customItemId,
            allowIntentOnlyFeed = isNextFeedPlanNote(wire.note),
        )
        val calendarDisposition = carePlanCalendarDisposition(
            existing = existing,
            babyId = references.baby.id,
            type = wire.type.key,
            customItemId = customItemId,
            scheduledAt = wire.scheduledAt,
            scheduledZoneId = wire.scheduledZoneId,
            note = wire.note,
            payloadJson = localPayload,
            schemaVersion = wire.schemaVersion,
            status = wire.status,
            deleted = deleted,
        )
        carePlanDao.update(
            existing.copy(
                babyId = references.baby.id,
                type = wire.type.key,
                customItemId = customItemId,
                scheduledAt = wire.scheduledAt,
                scheduledZoneId = wire.scheduledZoneId,
                note = wire.note,
                payloadJson = localPayload,
                schemaVersion = wire.schemaVersion,
                status = wire.status,
                createdByMembershipId = wire.createdByMembershipId,
                fulfilledRecordClientUuid = wire.fulfilledRecordClientUuid,
                fulfilledAt = wire.fulfilledAt,
                updatedAt = requireNotNull(wire.inlineUpdatedAt),
                deletedAt = deletedAt,
                baseVersion = stableVersion,
                // Source provenance and calendar projection columns are device-local.
                systemCalendarReminderReady = calendarDisposition.reminderReady,
                systemCalendarProjectionPending = calendarDisposition.projectionPending,
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
        deletedAt: Long?,
    ) {
        val existing = customItemDao.getByClientUuid(clientUuid) ?: return
        val wire = decodeCustomItemWire(root, RootUpdatedAtLocation.InlineStableRoot)
        customItemDao.update(
            existing.copy(
                name = wire.name,
                iconSlot = wire.iconSlot,
                createdByMembershipId = wire.createdByMembershipId,
                updatedAt = requireNotNull(wire.inlineUpdatedAt),
                deletedAt = deletedAt,
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
        deletedAt: Long?,
    ) {
        val existing = wakeObservationDao.getByClientUuid(clientUuid) ?: return
        val wire = decodeWakeRootWire(root, WakeRootWireShape.StableRoot)
        wakeObservationDao.update(
            existing.copy(
                sleepRecordClientUuid = wire.sleepRecordClientUuid,
                wakeTimestamp = wire.wakeTimestamp,
                observerMembershipId = requireNotNull(wire.observerMembershipId),
                note = wire.note,
                withdrawn = wire.withdrawn,
                updatedAt = requireNotNull(wire.inlineUpdatedAt),
                deletedAt = deletedAt,
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

    private suspend fun resolveWakeById(id: Long): WakeObservationEntity? =
        wakeObservationDao.get(id)

    private suspend fun validateCausalProof(
        session: SyncSession,
        frozen: List<FrozenCausalUnit>,
        batch: CausalProofBatch,
        forCommit: Boolean,
        localFrozenProof: Boolean = false,
    ) {
        fun fail(message: String, generationDrift: Boolean = false): Nothing {
            if (localFrozenProof && !generationDrift) {
                throw FrozenCommitProofException(
                    "frozen commit proof invalid; exact envelope retained: $message",
                )
            }
            throw AuthorityProofException(
                batch.generation.ifBlank { session.pullGeneration },
                IllegalArgumentException(message),
            )
        }
        if (batch.generation != session.pullGeneration) {
            fail("家庭服务器在因果同步期间变更了同步代际", generationDrift = true)
        }
        val expectedMutationOrder = frozen.map { it.mutation.mutationId }
        val expectedMutations = expectedMutationOrder.toSet()
        val byMutation = batch.results.groupBy(CausalProofUnit::mutationId)
        if (byMutation.keys != expectedMutations || byMutation.values.any { it.size != 1 }) {
            fail("家庭服务器因果响应 mutation_id 不完整、重复或包含多余 key")
        }
        if (batch.results.map(CausalProofUnit::mutationId) != expectedMutationOrder) {
            fail("家庭服务器因果响应顺序与请求不一致")
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
            val legacyGeneration = (result as? CausalUnitResult)?.generation
            if (!legacyGeneration.isNullOrBlank() && legacyGeneration != session.pullGeneration) {
                fail("家庭服务器因果 unit generation 漂移", generationDrift = true)
            }
            val known = if (forCommit) {
                setOf(
                    CausalCommitStatus.ACCEPTED,
                    CausalCommitStatus.MERGED,
                    CausalCommitStatus.BRANCHED,
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
            if (!result.requestHash.matches(LOWERCASE_SHA256) ||
                result.requestHash != unit.contentHash
            ) {
                fail("因果 request_hash 不是冻结内容的 canonical SHA-256")
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
                validateStableProjection(unit, result, ::fail)
            }
        }
    }

    private suspend fun validateStableProjection(
        unit: FrozenCausalUnit,
        result: CausalProofUnit,
        fail: (String) -> Nothing,
    ) {
        if (!result.stableRootPresent || !result.stableMediaPresent) {
            fail("因果 ${result.status} 缺少完整 stable_root/stable_media")
        }
        val expectedRoot = runCatching {
            Json.parseToJsonElement(unit.mutation.rootJson).jsonObject
        }.getOrElse { fail("本机冻结 causal root 无效") }
        val stableRoot = runCatching {
            Json.parseToJsonElement(result.stableRootJson).jsonObject
        }.getOrElse { fail("因果 ${result.status} stable_root 无效") }
        if (unit.mutation.entityType == "wake_observation") {
            runCatching {
                decodeWakeRootWire(expectedRoot, WakeRootWireShape.LocalMutation)
            }.getOrElse { fail("本机冻结 Wake causal root 类型或 domain 无效") }
        }
        val providerAvatarUuid = when (unit.mutation.entityType) {
            "baby" -> runCatching {
                decodeBabyWire(stableRoot, RootUpdatedAtLocation.InlineStableRoot)
            }.getOrElse { fail("因果 ${result.status} baby stable_root 类型或 domain 无效") }
                .avatarMediaUuid
            "custom_item" -> {
                runCatching {
                    decodeCustomItemWire(stableRoot, RootUpdatedAtLocation.InlineStableRoot)
                }.getOrElse {
                    fail("因果 ${result.status} custom_item stable_root 类型或 domain 无效")
                }
                null
            }
            "care_plan" -> {
                runCatching {
                    decodeCarePlanWire(
                        stableRoot,
                        RootUpdatedAtLocation.InlineStableRoot,
                        requireCanonicalIds = true,
                    )
                }.getOrElse {
                    fail("因果 ${result.status} care_plan stable_root 类型或 domain 无效")
                }
                null
            }
            "wake_observation" -> {
                val wire = runCatching {
                    decodeWakeRootWire(stableRoot, WakeRootWireShape.StableRoot)
                }.getOrElse {
                    fail("因果 ${result.status} wake stable_root 类型或 domain 无效")
                }
                val existing = wakeObservationDao.getByClientUuid(unit.mutation.clientUuid)
                    ?: fail("因果 ${result.status} wake 本机事实缺失")
                if (resolveWakeReference(
                        wire = wire,
                        recordDao = recordDao,
                        expectedSleepClientUuid = existing.sleepRecordClientUuid,
                    ) == null
                ) {
                    fail("因果 ${result.status} wake stable_root Sleep source 无效或漂移")
                }
                null
            }
            else -> null
        }
        val serverStampKeys = when (unit.mutation.entityType) {
            "wake_observation" -> setOf("observer_membership_id")
            else -> setOf("created_by_membership_id")
        }
        if (!stableRoot.keys.containsAll(expectedRoot.keys) ||
            stableRoot.keys.any { it !in expectedRoot.keys && it !in serverStampKeys }
        ) {
            fail("因果 ${result.status} stable_root 不符合冻结 closed schema")
        }

        val media = result.stableMedia
        if (media.map(CausalMediaItem::mediaUuid) != media.map(CausalMediaItem::mediaUuid).sorted() ||
            media.map(CausalMediaItem::mediaUuid).toSet().size != media.size
        ) {
            fail("因果 ${result.status} stable_media 必须唯一且 canonical 排序")
        }
        val expectedRole = CausalMediaPolicy.roleForEntityType(unit.mutation.entityType)?.wireName
        val maxMedia = CausalMediaPolicy.maxItemsForEntityType(unit.mutation.entityType)
        if (media.size > maxMedia || media.any {
                it.role != expectedRole ||
                    !it.sha256.matches(LOWERCASE_SHA256) ||
                    it.byteSize <= 0 ||
                    it.mime.isBlank()
            }
        ) {
            fail("因果 ${result.status} stable_media 不符合 closed manifest")
        }
        if (unit.mutation.entityType == "baby") {
            if (providerAvatarUuid != null && media.none { it.mediaUuid == providerAvatarUuid }) {
                fail("baby stable_root 引用了 stable_media 之外的头像")
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
        const val MAX_CAUSAL_SETTLEMENT_UNITS = 64

        val COMMIT_FIRST_ROOT_TYPES = setOf(
            "baby",
            "custom_item",
            "record",
            "wake_observation",
            "care_plan",
        )

        val ROOT_DEPENDENCY_PRIORITY = mapOf(
            "baby" to 0,
            "custom_item" to 1,
            "record" to 2,
            "wake_observation" to 3,
            "care_plan" to 4,
        )

        val LOWERCASE_SHA256 = Regex("^[0-9a-f]{64}$")

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
