package com.lezi.babylog.sync.engine

import com.lezi.babylog.sync.media.parseCanonicalMediaMime
import com.lezi.babylog.sync.media.requireCanonicalMediaMime
import com.lezi.babylog.core.common.MediaContentDigest
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
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
import com.lezi.babylog.core.model.FulfillmentAuthority
import com.lezi.babylog.core.model.FulfillmentCandidateEvidence
import com.lezi.babylog.core.model.carePlanAllowsIntentOnlyFeed
import com.lezi.babylog.core.database.causal.TerminalRejectionReceipt
import com.lezi.babylog.sync.backend.AuthorityProofException
import com.lezi.babylog.sync.backend.CausalCommitBatchResult
import com.lezi.babylog.sync.backend.CausalCommitRejectedException
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.backend.CausalMutationUnit
import com.lezi.babylog.sync.backend.CausalCommitUnitResult
import com.lezi.babylog.sync.backend.CausalMediaPreimageReceipt
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.takeIfOpenBranches
import com.lezi.babylog.sync.media.EmptyCausalMediaBindSource
import com.lezi.babylog.sync.media.ImmutableMediaSpool
import com.lezi.babylog.sync.media.ImmutableMediaSpoolGroup
import com.lezi.babylog.sync.media.ImmutableMediaSpoolItem
import com.lezi.babylog.sync.media.ImmutableMediaSpoolRecovery
import com.lezi.babylog.sync.media.ImmutableMediaSpoolSource
import com.lezi.babylog.sync.media.CausalMediaPolicy
import com.lezi.babylog.sync.media.PublishedMediaIdentity
import com.lezi.babylog.sync.media.encodeImmutableMediaSpoolGroup
import com.lezi.babylog.sync.session.receiptFor
import com.lezi.babylog.sync.session.SyncSession
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.put

/** Bounded concurrent preimage PUTs; matches the download side's media-GET parallelism. */
private const val CAUSAL_PREIMAGE_UPLOAD_PARALLELISM = 2

/** A legacy receipt is not evidence that its local import file is canonical. */
internal class PublishedMediaAuthorityRequiredException : IllegalStateException(
    "已发布照片需要从家庭服务器校验原始内容，请刷新同步后重试",
)

internal val CAUSAL_ROOT_TYPES = setOf(
    "baby",
    "record",
    "care_plan",
    "custom_item",
    "wake_observation",
)

private val MEDIA_COMMIT_FIRST_ROOT_TYPES = setOf(
    "baby",
    "record",
    "care_plan",
    "wake_observation",
)

/** Contracted commit units carry generation only at batch level. */
/**
 * One frozen causal atomic unit. Migrated roots restore one immutable Room-backed
 * envelope; every publishable root uses commit-first.
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
    val sha256: String?,
    val byteSize: Long,
    val mime: String?,
    val width: Int?,
    val height: Int?,
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
    private val fulfillmentCandidateDao: FulfillmentCandidateDao? = null,
    private val babyDao: BabyDao,
    private val mediaDao: MediaAssetDao,
    private val customItemDao: CustomItemDao,
    private val wakeObservationDao: WakeObservationDao,
    private val conflictSummaryDao: ConflictSummaryDao,
    private val conflictSnapshotCacheDao: ConflictSnapshotCacheDao? = null,
    private val immutableMediaSpool: ImmutableMediaSpool,
    private val mediaFiles: com.lezi.babylog.sync.media.SyncMediaFileStore,
    private val transactionRunner: DatabaseTransactionRunner,
    private val cleanupUnownedMediaPaths: suspend (Set<String>) -> Unit,
    private val requireRemoteAllowed: suspend (SyncSession) -> Unit,
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
        recoverMaterializedMediaPaths()
        mediaSettlementJournal?.finishPendingCleanups()
        val roomGroups = cache.listFrozenMediaSpoolManifests().map { row ->
            val group = decodeFrozenMediaSpoolManifest(row.payloadJson)
            require(row.journalKey == frozenMediaSpoolCacheKey(group.mutationId)) {
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
        val terminalSeals = com.lezi.babylog.sync.disasterrecovery.terminalSealsForSpoolRecovery(cache)
        val deletingIds = terminalSeals.filter { it.deleting }.mapTo(hashSetOf()) { it.group.mutationId }
        val retainedIds = roomGroupsByMutation.keys + pendingMutationIds + terminalSeals.map { it.group.mutationId }
        val recovered = if (deletingIds.isEmpty()) immutableMediaSpool.recoverAndSweep(retainedIds) else {
            requireNotNull(immutableMediaSpool as? com.lezi.babylog.sync.media.TerminalRetirementSpool) {
                "terminal spool deleting owner is unavailable"
            }.recoverAndSweepRetainingOpaque(retainedIds, terminalSeals.filter { it.deleting }.map { it.group })
        }
        val quarantineIds = cache.getTransportJournal(
            com.lezi.babylog.sync.disasterrecovery.RestoreArtifactRetirement.KEY,
        )?.payloadJson?.let { (Json.parseToJsonElement(it).jsonObject["groups"] as? JsonArray)
            ?.map { value -> value.jsonPrimitive.content }?.toSet() }.orEmpty()
        roomGroupsByMutation.forEach { (mutationId, manifest) ->
            if (mutationId in deletingIds) return@forEach
            val recoveredManifest = if (mutationId in quarantineIds) recovered[mutationId]?.group else
                (recovered[mutationId] as? ImmutableMediaSpoolRecovery.Complete)?.group
            require(recoveredManifest == manifest) {
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
        healInvalidEffectiveWakes(session)
        abandonUnpublishableDependents()
        val causalCandidates = candidates.filter { it.entityType in CAUSAL_ROOT_TYPES || it.entityType == "media" }
        if (causalCandidates.isEmpty()) return
        val frozen = freezeCausalUnits(session, causalCandidates)
        if (frozen.isEmpty()) return
        val (publishFirst, deferredTombstones) = partitionPoisoningParentTombstones(frozen)
        publishFirst.chunked(MAX_CAUSAL_SETTLEMENT_UNITS).forEach { batch ->
            settleBatch(session, batch)
        }
        deferredTombstones.chunked(MAX_CAUSAL_SETTLEMENT_UNITS).forEach { batch ->
            settleBatch(session, batch)
        }
    }

    /** Keeps direct commit batches within the 64-root bound. */
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
        require((directCommit + mediaCommitUnknown).toSet() == frozen.toSet()) {
            "Every causal root must have one durable commit-first envelope"
        }
    }

    /** Lost-response recovery: replay the exact durable envelope before a superseding local edit. */
    private suspend fun replayMediaCommitUnknown(
        session: SyncSession,
        frozen: List<FrozenCausalUnit>,
    ) {
        require(frozen.all { it.durableMediaCommitUnknown && it.mutation.media.isNotEmpty() })
        requireRemoteAllowed(session)
        val commit = try {
            commitWithMissingPreimageRecovery(session, frozen)
        } catch (failure: Throwable) {
            val rejected = failure as? CausalCommitRejectedException
                ?: (failure.cause as? CausalCommitRejectedException)
            if (rejected != null && isTerminalRejectionCode(rejected.code)) {
                recordTerminalRejection(frozen, rejected)
            }
            throw failure
        }
        validateCausalProof(
            session = session,
            frozen = frozen,
            batch = commit,
            localFrozenProof = true,
        )
        val byMutation = commit.results.associateBy(CausalCommitUnitResult::mutationId)
        transactionRunner.run {
            frozen.forEach { unit ->
                applyCommitFirstResult(
                    unit,
                    byMutation.getValue(unit.mutation.mutationId),
                    commit.generation,
                    session,
                )
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

    /**
     * A receipt proves a past PUT, not permanent staging retention. Always ask the
     * idempotent commit first: a lost successful response must never trigger a new
     * mutation or a source read. Only an explicit missing/expired preimage verdict
     * permits restoring those exact immutable bytes and one bounded commit replay.
     * No journal state or historical receipt is rewritten during this recovery.
     */
    private suspend fun commitWithMissingPreimageRecovery(
        session: SyncSession,
        frozen: List<FrozenCausalUnit>,
    ): CausalCommitBatchResult {
        val mutations = frozen.map(FrozenCausalUnit::mutation)
        try {
            return backend.causalCommit(session, mutations)
        } catch (failure: Throwable) {
            val rejected = failure as? CausalCommitRejectedException
                ?: (failure.cause as? CausalCommitRejectedException)
                ?: throw failure
            if (rejected.code !in setOf("missing_media_bytes", "media_preimage_expired")) {
                throw failure
            }
            val repair = frozen.filter { unit ->
                unit.mutation.media.isNotEmpty() &&
                    (rejected.mutationId == null || unit.mutation.mutationId == rejected.mutationId)
            }
            if (repair.isEmpty()) throw failure
            val cache = requireNotNull(conflictSnapshotCacheDao)
            for (unit in repair) {
                val group = decodeFrozenMediaSpoolManifest(
                    requireNotNull(cache.getFrozenMediaSpoolManifest(unit.mutation.mutationId)).payloadJson,
                )
                require(group.items.map(ImmutableMediaSpoolItem::toCausalMediaItem) == unit.mutation.media) {
                    "media recovery cannot change the frozen manifest"
                }
                for (item in group.items) {
                    requireRemoteAllowed(session)
                    val receipt = backend.putCausalMediaPreimage(
                        session = session,
                        mediaUuid = item.mediaUuid,
                        source = immutableMediaSpool.open(unit.mutation.mutationId, item),
                        sha256 = item.sha256,
                    )
                    require(receipt.mediaUuid == item.mediaUuid &&
                        receipt.sha256 == item.sha256 && receipt.byteSize == item.byteSize &&
                        receipt.status in setOf("staged", "consumed") && receipt.expiresAtEpochSeconds > 0L
                    ) { "restaged receipt does not bind the immutable media identity" }
                }
            }
        }
        requireRemoteAllowed(session)
        return backend.causalCommit(session, mutations)
    }

    private suspend fun commitFirst(
        session: SyncSession,
        frozen: List<FrozenCausalUnit>,
    ) = commitPreparedCausalUnits(
        session = session,
        frozen = frozen,
        localFrozenProof = true,
    )

    /**
     * One ordering owner for receipt completion, commit-unknown durability, commit proof,
     * transactional settlement, and terminal spool cleanup.
     */
    private suspend fun commitPreparedCausalUnits(
        session: SyncSession,
        frozen: List<FrozenCausalUnit>,
        localFrozenProof: Boolean,
    ) {
        stageCausalMediaPreimages(session, frozen)
        frozen.filter { it.mutation.media.isNotEmpty() }.forEach { unit ->
            requireNotNull(mediaSettlementJournal) {
                "causal media commit requires durable settlement journal"
            }.markCommitUnknown(unit.mutation.mutationId)
        }
        requireRemoteAllowed(session)
        val commit = try {
            commitWithMissingPreimageRecovery(session, frozen)
        } catch (failure: Throwable) {
            val rejected = failure as? CausalCommitRejectedException
                ?: (failure.cause as? CausalCommitRejectedException)
            if (rejected != null && isTerminalRejectionCode(rejected.code)) {
                recordTerminalRejection(frozen, rejected)
            }
            throw failure
        }
        validateCausalProof(
            session = session,
            frozen = frozen,
            batch = commit,
            localFrozenProof = localFrozenProof,
        )
        val commitByMutation = commit.results.associateBy(CausalCommitUnitResult::mutationId)
        transactionRunner.run {
            for (unit in frozen) {
                applyCommitFirstResult(
                    unit,
                    commitByMutation.getValue(unit.mutation.mutationId),
                    commit.generation,
                    session,
                )
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

    private suspend fun applyCommitFirstResult(
        unit: FrozenCausalUnit,
        result: CausalCommitUnitResult,
        authorityGeneration: String,
        session: SyncSession,
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
        val matchesBoundContent = boundFactsStillCurrent(unit, session)
        when (result.status) {
            CausalCommitStatus.ACCEPTED,
            CausalCommitStatus.MERGED,
            -> {
                val version = stableVersion ?: throw AuthorityProofException(
                    authorityGeneration,
                    IllegalArgumentException("accepted/merged 缺少 stable_version_id"),
                )
                val settled = settleCommitFirstAcceptedOrMerged(unit, version, matchesBoundContent) ?: return
                applyValidatedStableIdentityRedaction(unit, result)
                if (settled == CommitFirstSettlementEpoch.CurrentEpoch) {
                    applyStableProjectionAfterAck(unit, result)
                    settleMigratedMediaCandidates(unit)
                    for (accepted in result.stableMedia) {
                        val currentMedia = mediaDao.getByClientUuid(accepted.mediaUuid) ?: continue
                        if (currentMedia.deletedAt == null && currentMedia.sha256 == accepted.sha256 &&
                            currentMedia.byteSize == accepted.byteSize && currentMedia.mime == accepted.mime &&
                            currentMedia.width?.toLong() == accepted.width && currentMedia.height?.toLong() == accepted.height) {
                            mediaDao.update(currentMedia.copy(remoteUri = session.receiptFor(accepted.mediaUuid)))
                        }
                    }
                }
                settleCommitFirstEvidence(unit, result, cache)
            }
            CausalCommitStatus.BRANCHED -> {
                val conflictId = result.conflictId?.takeIf { it.isNotBlank() }
                    ?: throw AuthorityProofException(
                        authorityGeneration,
                        IllegalArgumentException("branched 缺少 conflict_id"),
                    )
                val branchVersionId = result.branchVersionId?.takeIf { it.isNotBlank() }
                    ?: throw AuthorityProofException(
                        authorityGeneration,
                        IllegalArgumentException("branched 缺少 branch_version_id"),
                    )
                val version = stableVersion ?: throw AuthorityProofException(
                    authorityGeneration,
                    IllegalArgumentException("branched 缺少 stable_version_id"),
                )
                val settled = settleCommitFirstBranched(
                    unit = unit,
                    conflictId = conflictId,
                    branchVersionId = branchVersionId,
                    stableBaseVersion = version,
                    matchesBoundContent = matchesBoundContent,
                ) ?: return
                applyValidatedStableIdentityRedaction(unit, result)
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
            else -> error("unknown causal commit status: ${result.status}")
        }
    }

    /** Full proof and the root/mutation CAS already passed; care facts may belong to a newer edit. */
    private suspend fun applyValidatedStableIdentityRedaction(
        unit: FrozenCausalUnit,
        result: CausalCommitUnitResult,
    ) {
        val type = unit.mutation.entityType
        val uuid = unit.mutation.clientUuid
        val root = Json.parseToJsonElement(result.stableRootJson).jsonObject
        val key = if (type == "wake_observation") "observer_membership_id" else "created_by_membership_id"
        if (root[key] !== JsonNull) return
        when (type) {
            "record" -> recordDao.getByClientUuid(uuid)?.let { row ->
                if (row.createdByMembershipId.isNotEmpty()) recordDao.update(row.copy(createdByMembershipId = ""))
            }
            "care_plan" -> carePlanDao.getByClientUuid(uuid)?.let { row ->
                if (row.createdByMembershipId.isNotEmpty()) carePlanDao.update(row.copy(createdByMembershipId = ""))
            }
            "custom_item" -> customItemDao.getByClientUuid(uuid)?.let { row ->
                if (row.createdByMembershipId.isNotEmpty()) customItemDao.update(row.copy(createdByMembershipId = ""))
            }
            "wake_observation" -> wakeObservationDao.getByClientUuid(uuid)?.let { row ->
                if (row.observerMembershipId.isNotEmpty()) wakeObservationDao.update(row.copy(observerMembershipId = ""))
            }
        }
        // Evidence invalidation is independent of stable care-version IDs and content epoch.
        val conflictIds = conflictSummaryDao.listForRoot(type, uuid).mapTo(mutableSetOf()) { it.conflictId }
        if (result.status == CausalCommitStatus.BRANCHED) {
            result.conflictId?.let(conflictIds::add)
        }
        conflictIds.forEach { conflictSnapshotCacheDao?.deleteConflictState(it) }
    }

    /** One terminal owner for empty roots and roots with a durable media journal. */
    private suspend fun settleCommitFirstEvidence(
        unit: FrozenCausalUnit,
        result: CausalCommitUnitResult,
        cache: ConflictSnapshotCacheDao,
    ) {
        if (unit.mutation.media.isEmpty()) {
            cache.deleteFrozenMutation(unit.mutation.entityType, unit.mutation.clientUuid)
        } else {
            requireNotNull(mediaSettlementJournal).markTerminal(unit.mutation.mutationId, result)
        }
    }

    /** H21-H23 share settlement for Record, Baby, CarePlan, and Wake media. */
    /** Caller holds the terminal Room transaction; no mutable snapshot can stand in for bound evidence. */
    private suspend fun boundFactsStillCurrent(unit: FrozenCausalUnit, session: SyncSession): Boolean {
        val local = loadCommitFirstLocal(unit.mutation.entityType, unit.mutation.clientUuid) ?: return false
        if (local.deleted != unit.mutation.deleted) return false
        val media = loadActiveCausalMedia(unit.mutation.entityType, unit.mutation.clientUuid)
        if (unit.mutation.media.isEmpty()) {
            return media.isEmpty() && buildCausalRootJson(unit.mutation.entityType, unit.mutation.clientUuid)
                ?.let { Json.parseToJsonElement(it) } == Json.parseToJsonElement(unit.mutation.rootJson)
        }
        val row = conflictSnapshotCacheDao?.getTransportJournal("media-freeze-capture-v1:${unit.mutation.mutationId}")
            ?: return false // Missing original local revision is conservative: advance base, retain local intent.
        val capture = Json.parseToJsonElement(row.payloadJson).jsonObject
        require(capture.keys == setOf("format", "source", "current") && capture["format"]?.jsonPrimitive?.content == "1")
        val recorded = requireNotNull(capture["current"] as? JsonObject)
        require(recorded["type"]?.jsonPrimitive?.content == unit.mutation.entityType &&
            recorded["uuid"]?.jsonPrimitive?.content == unit.mutation.clientUuid &&
            recorded["root"] == Json.parseToJsonElement(unit.mutation.rootJson) &&
            recorded["base"] == (unit.mutation.baseVersion?.let(::JsonPrimitive) ?: JsonNull)) {
            "bound media capture does not identify its immutable request"
        }
        val boundMedia = (recorded["media"] as? JsonArray)?.map { value -> value.jsonObject.let { item ->
            CausalMediaItem(
                mediaUuid = item.getValue("uuid").jsonPrimitive.content,
                role = requireNotNull(CausalMediaPolicy.roleForEntityType(unit.mutation.entityType)).wireName,
                sha256 = item.getValue("sha").jsonPrimitive.content,
                byteSize = requireNotNull(item.getValue("size").jsonPrimitive.longOrNull),
                mime = parseCanonicalMediaMime(item["mime"]),
                width = item["width"]?.jsonPrimitive?.longOrNull,
                height = item["height"]?.jsonPrimitive?.longOrNull,
            )
        } } ?: error("bound media capture lacks revisions")
        require(boundMedia == unit.mutation.media) { "bound media capture differs from immutable manifest" }
        // f5 source evidence predates this redundant deleted flag; the immutable wire envelope
        // already owns it. This compatibility read never infers newer local deletion state.
        val expected = if (recorded["deleted"] == null) JsonObject(recorded + ("deleted" to JsonPrimitive(unit.mutation.deleted))) else recorded
        return expected == mediaCaptureEvidence(session.mediaAuthorityKey(), unit.mutation.entityType,
            unit.mutation.clientUuid, media)
    }

    private suspend fun settleMigratedMediaCandidates(unit: FrozenCausalUnit) {
        if (unit.mutation.entityType !in MEDIA_COMMIT_FIRST_ROOT_TYPES) return
        unit.candidates.filter { it.entityType == "media" }.forEach { media ->
            mediaDao.markSynced(media.clientUuid, media.updatedAt)
        }
        // This is called only after the terminal transaction proves the originally bound
        // complete fact still owns the current rows. Replays never sample a new media snapshot.
        unit.mutation.media.forEach { media ->
            mediaDao.getByClientUuid(media.mediaUuid)?.let { current ->
                mediaDao.markSynced(current.clientUuid, current.updatedAt)
            }
        }
    }

    private suspend fun settleCommitFirstAcceptedOrMerged(
        unit: FrozenCausalUnit,
        stableBaseVersion: String,
        matchesBoundContent: Boolean,
    ): CommitFirstSettlementEpoch? = when (unit.mutation.entityType) {
        "baby" -> babyDao.settleCommitFirstAcceptedOrMerged(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            stableBaseVersion,
            matchesBoundContent,
        )
        "record" -> recordDao.settleCommitFirstAcceptedOrMerged(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            stableBaseVersion,
            matchesBoundContent,
        )
        "care_plan" -> carePlanDao.settleCommitFirstAcceptedOrMerged(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            stableBaseVersion,
            matchesBoundContent,
        )
        "custom_item" -> customItemDao.settleCommitFirstAcceptedOrMerged(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            stableBaseVersion,
            matchesBoundContent,
        )
        "wake_observation" -> wakeObservationDao.settleCommitFirstAcceptedOrMerged(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            stableBaseVersion,
            matchesBoundContent,
        )
        else -> null
    }

    private suspend fun settleCommitFirstBranched(
        unit: FrozenCausalUnit,
        conflictId: String,
        branchVersionId: String,
        stableBaseVersion: String,
        matchesBoundContent: Boolean,
    ): CommitFirstSettlementEpoch? = when (unit.mutation.entityType) {
        "baby" -> babyDao.settleCommitFirstBranched(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            conflictId,
            branchVersionId,
            stableBaseVersion,
            matchesBoundContent,
        )
        "record" -> recordDao.settleCommitFirstBranched(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            conflictId,
            branchVersionId,
            stableBaseVersion,
            matchesBoundContent,
        )
        "care_plan" -> carePlanDao.settleCommitFirstBranched(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            conflictId,
            branchVersionId,
            stableBaseVersion,
            matchesBoundContent,
        )
        "custom_item" -> customItemDao.settleCommitFirstBranched(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            conflictId,
            branchVersionId,
            stableBaseVersion,
            matchesBoundContent,
        )
        "wake_observation" -> wakeObservationDao.settleCommitFirstBranched(
            unit.mutation.clientUuid,
            unit.mutation.mutationId,
            unit.contentEpoch,
            conflictId,
            branchVersionId,
            stableBaseVersion,
            matchesBoundContent,
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
        return shouldApplyStableLocal(
            entityType,
            clientUuid,
            remoteVersionId,
            loadCausalLocal(entityType, clientUuid),
        )
    }

    /**
     * Apply-path variant: the caller already loaded the row inside this same
     * page transaction, so the causal fields are passed in ([local] = null
     * when the row is absent) instead of paying one extra SELECT per applied
     * root per page.
     */
    suspend fun shouldApplyStablePull(
        entityType: String,
        clientUuid: String,
        remoteVersionId: String?,
        forceAuthority: Boolean,
        local: LocalCausal?,
    ): Boolean {
        if (forceAuthority) return true
        if (entityType !in CAUSAL_ROOT_TYPES) return true
        return shouldApplyStableLocal(entityType, clientUuid, remoteVersionId, local)
    }

    private suspend fun shouldApplyStableLocal(
        entityType: String,
        clientUuid: String,
        remoteVersionId: String?,
        local: LocalCausal?,
    ): Boolean {
        val causal = local ?: return true
        if (causal.syncDirty) {
            if (causal.baseVersion == null && remoteVersionId != null) {
                establishMigratedDirtyBaseline(
                    entityType = entityType,
                    clientUuid = clientUuid,
                    expectedContentEpoch = causal.contentEpoch,
                    remoteVersionId = remoteVersionId,
                )
            }
            return false
        }
        val causalAware = causal.baseVersion != null || causal.mutationId != null ||
            causal.openConflictId != null
        if (!causalAware) return true
        if (causal.openConflictId != null) {
            return remoteVersionId != null && remoteVersionId != causal.baseVersion
        }
        return true
    }

    /**
     * A recovery full pull may replace reset-derived replicas, but a row that was already dirty
     * before reset owns local intent. Rebase that intent onto the observed stable version without
     * replacing its content, tombstone, or pending state. A new mutation id is minted on freeze.
     */
    suspend fun rebaseRecoveryPendingIntent(
        entityType: String,
        clientUuid: String,
        remoteVersionId: String?,
    ) {
        if (remoteVersionId == null || entityType !in CAUSAL_ROOT_TYPES) return
        val local = loadCausalLocal(entityType, clientUuid) ?: return
        if (!local.syncDirty || local.baseVersion == remoteVersionId) return
        updateDirtyBaseline(
            entityType = entityType,
            clientUuid = clientUuid,
            expectedContentEpoch = local.contentEpoch,
            remoteVersionId = remoteVersionId,
            requireMissingBaseline = false,
        )
    }

    suspend fun shouldPreserveRecoveryIntent(
        resetRoot: com.lezi.babylog.sync.session.FamilySessionReplica.ResetRoot,
        crossingFamilyBoundary: Boolean,
    ): Boolean {
        val local = loadCausalLocal(resetRoot.entityType, resetRoot.clientUuid) ?: return false
        return local.syncDirty &&
            (local.contentEpoch != resetRoot.contentEpoch ||
                (!crossingFamilyBoundary && resetRoot.wasPending))
    }

    /**
     * Room 26 dirty rows have no causal identity. The first causal full pull is the
     * only authoritative place to learn the stable parent they were edited from.
     * Persist only that parent; the local revision, tombstone, media references and
     * dirty intent remains untouched and is committed after the pull.
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
    ) = updateDirtyBaseline(
        entityType = entityType,
        clientUuid = clientUuid,
        expectedContentEpoch = expectedContentEpoch,
        remoteVersionId = remoteVersionId,
        requireMissingBaseline = true,
    )

    private suspend fun updateDirtyBaseline(
        entityType: String,
        clientUuid: String,
        expectedContentEpoch: Long,
        remoteVersionId: String,
        requireMissingBaseline: Boolean,
    ) {
        when (entityType) {
            "baby" -> babyDao.getByClientUuid(clientUuid)?.let { current ->
                if (current.syncDirty && current.updatedAt == expectedContentEpoch &&
                    (!requireMissingBaseline || current.baseVersion == null)
                ) {
                    babyDao.update(current.copy(baseVersion = remoteVersionId, mutationId = null))
                }
            }
            "record" -> recordDao.getByClientUuid(clientUuid)?.let { current ->
                if (current.syncDirty && current.updatedAt == expectedContentEpoch &&
                    (!requireMissingBaseline || current.baseVersion == null)
                ) {
                    recordDao.update(current.copy(baseVersion = remoteVersionId, mutationId = null))
                }
            }
            "care_plan" -> carePlanDao.getByClientUuid(clientUuid)?.let { current ->
                if (current.syncDirty && current.updatedAt == expectedContentEpoch &&
                    (!requireMissingBaseline || current.baseVersion == null)
                ) {
                    carePlanDao.update(current.copy(baseVersion = remoteVersionId, mutationId = null))
                }
            }
            "custom_item" -> customItemDao.getByClientUuid(clientUuid)?.let { current ->
                if (current.syncDirty && current.updatedAt == expectedContentEpoch &&
                    (!requireMissingBaseline || current.baseVersion == null)
                ) {
                    customItemDao.update(current.copy(baseVersion = remoteVersionId, mutationId = null))
                }
            }
            "wake_observation" -> wakeObservationDao.getByClientUuid(clientUuid)?.let { current ->
                if (current.syncDirty && current.updatedAt == expectedContentEpoch &&
                    (!requireMissingBaseline || current.baseVersion == null)
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
    ): Set<String> {
        val prior = conflictSummaryDao.listForRoot(entityType, clientUuid)
        val open = summary?.takeIfOpenBranches()
        val retired = mediaSettlementJournal?.retireResolvedBranches(
            entityType, clientUuid, open?.conflictId, open?.branchVersionIds.orEmpty().toSet(),
        ).orEmpty()
        if (open == null) {
            prior.forEach { stale ->
                conflictSnapshotCacheDao?.deleteConflictState(stale.conflictId)
                conflictSummaryDao.delete(stale.conflictId)
            }
            applyOpenConflictId(entityType, clientUuid, openConflictId = null)
            return retired
        }
        prior.filter { it.conflictId != open.conflictId }.forEach { stale ->
            conflictSnapshotCacheDao?.deleteConflictState(stale.conflictId)
            conflictSummaryDao.delete(stale.conflictId)
        }
        conflictSummaryDao.upsert(
            ConflictSummaryEntity(
                conflictId = open.conflictId,
                entityType = open.entityType.ifBlank { entityType },
                clientUuid = open.clientUuid.ifBlank { clientUuid },
                baseVersionId = null,
                stableVersionId = open.stableVersionId,
                status = "open",
                kind = "concurrent",
                branchVersionIdsJson = encodeBranchVersionIdsJson(open.branchVersionIds),
                updatedAt = updatedAt,
            ),
        )
        applyOpenConflictId(entityType, clientUuid, open.conflictId)
        return retired
    }

    suspend fun sweepRetiredMedia(retiredMutationIds: Set<String>) {
        mediaSettlementJournal?.sweepRetired(retiredMutationIds)
    }

    private suspend fun applyOpenConflictId(
        entityType: String,
        clientUuid: String,
        openConflictId: String?,
    ) {
        when (entityType) {
            "record" -> recordDao.getByClientUuid(clientUuid)?.let { current ->
                if (current.openConflictId == openConflictId &&
                    (openConflictId != null || current.localBranchVersionId == null)
                ) {
                    return
                }
                recordDao.update(
                    current.copy(
                        openConflictId = openConflictId,
                        localBranchVersionId = openConflictId?.let { current.localBranchVersionId },
                    ),
                )
            }
            "care_plan" -> carePlanDao.getByClientUuid(clientUuid)?.let { current ->
                if (current.openConflictId == openConflictId &&
                    (openConflictId != null || current.localBranchVersionId == null)
                ) {
                    return
                }
                carePlanDao.update(
                    current.copy(
                        openConflictId = openConflictId,
                        localBranchVersionId = openConflictId?.let { current.localBranchVersionId },
                    ),
                )
            }
            "baby" -> babyDao.getByClientUuid(clientUuid)?.let { current ->
                if (current.openConflictId == openConflictId &&
                    (openConflictId != null || current.localBranchVersionId == null)
                ) {
                    return
                }
                babyDao.update(
                    current.copy(
                        openConflictId = openConflictId,
                        localBranchVersionId = openConflictId?.let { current.localBranchVersionId },
                    ),
                )
            }
            "custom_item" -> customItemDao.getByClientUuid(clientUuid)?.let { current ->
                if (current.openConflictId == openConflictId &&
                    (openConflictId != null || current.localBranchVersionId == null)
                ) {
                    return
                }
                customItemDao.update(
                    current.copy(
                        openConflictId = openConflictId,
                        localBranchVersionId = openConflictId?.let { current.localBranchVersionId },
                    ),
                )
            }
            "wake_observation" -> wakeObservationDao.getByClientUuid(clientUuid)?.let { current ->
                if (current.openConflictId == openConflictId &&
                    (openConflictId != null || current.localBranchVersionId == null)
                ) {
                    return
                }
                wakeObservationDao.update(
                    current.copy(
                        openConflictId = openConflictId,
                        localBranchVersionId = openConflictId?.let { current.localBranchVersionId },
                    ),
                )
            }
        }
    }

    private suspend fun freezeCausalUnits(
        session: SyncSession,
        candidates: List<PublishCandidate>,
    ): List<FrozenCausalUnit> {
        val remaining = candidates.associateByTo(linkedMapOf(), PublishCandidate::planId)
        val units = mutableListOf<FrozenCausalUnit>()

        // Member local-only babies: still freeze for permanent rejection settlement.
        if (session.role == com.lezi.babylog.sync.session.FamilyRole.Member) {
            val localBabies = babyDao.listLocalOnlyIncludingDeleted()
            for (baby in localBabies) {
                val subtree = remaining.values.filter { candidateBelongsToBaby(candidate = it, babyId = baby.id) }
                if (subtree.isEmpty()) continue
                freezeRoot("baby", baby.clientUuid, baby.updatedAt, subtree, session.role, session.mediaAuthorityKey())?.let { units += it }
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
                role = session.role,
                authorityKey = session.mediaAuthorityKey(),
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
            freezeRoot(entityType, resolvedUuid, rootEpoch, mediaRows, session.role, session.mediaAuthorityKey())?.let { units += it }
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
        role: FamilyRole = FamilyRole.Member,
        authorityKey: String,
    ): FrozenCausalUnit? {
        mediaSettlementJournal?.restoreUnsettled(entityType, clientUuid)?.let { journal ->
            val terminalReceipt = conflictSnapshotCacheDao?.getTerminalReceipt(entityType, clientUuid)
            val current = loadCommitFirstLocal(entityType, clientUuid)
                ?: error("durable media commit lost its product fact")
            if (terminalReceipt != null) {
                if (current.contentEpoch == terminalReceipt.contentEpoch) {
                    return null
                } else if (current.contentEpoch > terminalReceipt.contentEpoch) {
                    transactionRunner.run {
                        conflictSnapshotCacheDao?.deleteTerminalReceipt(entityType, clientUuid)
                        mediaSettlementJournal.clearBinding(entityType, clientUuid)
                    }
                    // The rejected envelope is retired. Freeze the newer Room fact below;
                    // never return a unit whose durable media manifest was just removed.
                    return@let
                }
            }
            require(
                current.syncDirty && current.contentEpoch >= journal.binding.contentEpoch,
            ) { "durable media commit no longer owns a pending fact" }
            // A partial prepare already owns an immutable transport envelope. Finish that
            // intent before publishing a later Room epoch, just as unknown commits replay.
            // Existing settlement CAS advances only the base of the superseding fact.
            return FrozenCausalUnit(
                mutation = journal.mutation,
                contentEpoch = journal.binding.contentEpoch,
                contentHash = journal.binding.requestHash,
                candidates = emptyList(),
                mediaSnapshot = emptyList(),
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
                role = role,
            )?.let { return it }
            val cache = conflictSnapshotCacheDao ?: return null
            if (!wakeObservationDependenciesReady(clientUuid, cache)) return null
            if (loadActiveCausalMedia(entityType, clientUuid).isEmpty()) return null
        } else if (entityType == "care_plan") {
            freezeEmptyMediaCommitEnvelope(
                entityType = entityType,
                clientUuid = clientUuid,
                contentEpoch = contentEpoch,
                candidates = candidates,
                role = role,
            )?.let { return it }
            if (loadActiveCausalMedia(entityType, clientUuid).isEmpty()) return null
            if (!hasCarePlanMediaPublishOrRepairEvidence(clientUuid, candidates)) return null
            val cache = conflictSnapshotCacheDao ?: return null
            if (!carePlanDependenciesReady(clientUuid, cache)) return null
        } else if (entityType in COMMIT_FIRST_ROOT_TYPES) {
            freezeEmptyMediaCommitEnvelope(
                entityType = entityType,
                clientUuid = clientUuid,
                contentEpoch = contentEpoch,
                candidates = candidates,
                role = role,
            )?.let { return it }
            if (loadActiveCausalMedia(entityType, clientUuid).isEmpty()) return null
        }
        val terminalReceipt = conflictSnapshotCacheDao?.getTerminalReceipt(entityType, clientUuid)
        val currentFact = loadCommitFirstLocal(entityType, clientUuid)
        if (terminalReceipt != null && currentFact != null) {
            if (currentFact.contentEpoch == terminalReceipt.contentEpoch) {
                return null
            } else if (currentFact.contentEpoch > terminalReceipt.contentEpoch) {
                conflictSnapshotCacheDao?.deleteTerminalReceipt(entityType, clientUuid)
                conflictSnapshotCacheDao?.deleteFrozenMutation(entityType, clientUuid)
                mediaSettlementJournal?.clearBinding(entityType, clientUuid)
            }
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
            authorityKey = authorityKey,
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
        val bindingStillCurrent = transactionRunner.run {
            if (buildCausalRootJson(entityType, clientUuid) != rootJson ||
                loadActiveCausalMedia(entityType, clientUuid).toCausalMediaRevisions() != frozenMedia.revisions
            ) return@run false
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
            true
        }
        if (!bindingStillCurrent) return null
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
        role: FamilyRole = FamilyRole.Member,
    ): FrozenCausalUnit? {
        val cache = conflictSnapshotCacheDao ?: return null
        return transactionRunner.run {
            val current = loadCommitFirstLocal(entityType, clientUuid) ?: return@run null
            val terminalReceipt = cache.getTerminalReceipt(entityType, clientUuid)
            if (terminalReceipt != null) {
                if (current.contentEpoch == terminalReceipt.contentEpoch) {
                    return@run null
                } else if (current.contentEpoch > terminalReceipt.contentEpoch) {
                    cache.deleteTerminalReceipt(entityType, clientUuid)
                    cache.deleteFrozenMutation(entityType, clientUuid)
                    mediaSettlementJournal?.clearBinding(entityType, clientUuid)
                }
            }

            cache.getFrozenMutation(entityType, clientUuid)?.let { stored ->
                val restored = decodeFrozenCommitEnvelope(stored.payloadJson)
                require(restored.contentEpoch == stored.contentEpoch) {
                    "frozen commit envelope epoch metadata drift"
                }
                require(
                    restored.mutation.entityType == entityType &&
                        restored.mutation.clientUuid == clientUuid &&
                        restored.mutation.media.isEmpty(),
                ) {
                    "frozen commit envelope storage identity drift"
                }
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

            if (!current.syncDirty || current.contentEpoch != contentEpoch) return@run null
            if (entityType == "baby" && role != FamilyRole.Owner && babyDao.getByClientUuid(clientUuid)?.familyAuthority != true) {
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
    private fun isTerminalRejectionCode(code: String): Boolean = when (code) {
        "invalid_domain",
        "missing_field",
        "unknown_field",
        "wrong_type",
        "non_canonical_value",
        "content_drift",
        "permission_denied",
        "forbidden",
        "not_admin",
        "invalid_media",
        "media_not_found",
        "media_checksum_mismatch",
        "media_expired",
        "media_role_invalid",
        "media_too_large",
        "media_count_exceeded",
        "duplicate_member",
        "too_many_members",
        "unsupported_record_type",
        "same_author_only",
        "wrong_baby_or_type",
        "outside_time_window",
        "disconnected_group",
        "incomplete_group",
        "candidate_limit_exceeded",
        "already_related",
        "media_uuid_conflict",
        "media_sha256_mismatch",
        "media_byte_size_mismatch",
        "media_membership_mismatch",
        -> true
        else -> false
    }
    private suspend fun recordTerminalRejection(
        frozen: List<FrozenCausalUnit>,
        rejected: CausalCommitRejectedException,
    ) {
        val cache = conflictSnapshotCacheDao ?: return
        val targetUnits = if (rejected.mutationId != null) {
            frozen.filter { it.mutation.mutationId == rejected.mutationId }.ifEmpty { frozen }
        } else {
            frozen
        }
        val now = System.currentTimeMillis()
        transactionRunner.run {
            targetUnits.forEach { unit ->
                val receipt = TerminalRejectionReceipt(
                    entityType = unit.mutation.entityType,
                    clientUuid = unit.mutation.clientUuid,
                    mutationId = unit.mutation.mutationId,
                    code = rejected.code,
                    contentEpoch = unit.contentEpoch,
                    recordedAt = now,
                    abandoned = false,
                )
                cache.putTerminalReceipt(receipt)
                if (unit.mutation.media.isNotEmpty()) {
                    mediaSettlementJournal?.clearCommitUnknown(unit.mutation.mutationId)
                }
                abandonDirtyFulfillmentCandidatesReferencing(
                    cache = cache,
                    entityType = unit.mutation.entityType,
                    clientUuid = unit.mutation.clientUuid,
                    recordedAt = now,
                )
            }
        }
    }

    /**
     * SleepStart may keep a stale effective pointer after a withdraw/delete/abandon.
     * A live sleep mutation with an invalid pointer is `invalid_domain`; heal it
     * locally so the sleep (or its tombstone) can still settle.
     */
    private suspend fun healInvalidEffectiveWakes(session: SyncSession) {
        // The pending list is only an identity census. Never write one of its stale
        // whole-row snapshots after suspending in another DAO: care edits use a
        // different mutex. The fresh root and wake share one short Room write lease.
        recordDao.listPendingSync().map(RecordEntity::clientUuid).forEach { clientUuid ->
            transactionRunner.run {
                val current = recordDao.getByClientUuid(clientUuid) ?: return@run
                if (!current.syncDirty || current.type != "sleep" || current.deletedAt != null) {
                    return@run
                }
                if (session.role != FamilyRole.Owner &&
                    current.createdByMembershipId != session.membershipId
                ) return@run
                val selected = current.effectiveWakeObservationClientUuid ?: return@run
                // Absence during paginated pull is not proof of withdrawal.
                val wake = wakeObservationDao.getByClientUuid(selected) ?: return@run
                if (wake.deletedAt == null && !wake.withdrawn &&
                    wake.sleepRecordClientUuid == current.clientUuid
                ) return@run
                check(current.updatedAt < Long.MAX_VALUE) { "护理记录修订已达到上限" }
                recordDao.update(
                    current.copy(
                        effectiveWakeObservationClientUuid = null,
                        updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1),
                        mutationId = null,
                    ),
                )
            }
        }
    }

    private suspend fun isValidEffectiveWake(
        wakeClientUuid: String,
        sleepClientUuid: String,
    ): Boolean {
        val wake = wakeObservationDao.getByClientUuid(wakeClientUuid) ?: return false
        return wake.deletedAt == null && !wake.withdrawn &&
            wake.sleepRecordClientUuid == sleepClientUuid
    }

    /**
     * First-seen children that require a *live* parent cannot ingress once that
     * parent is provably dead on NAS (durable local tombstone). Leaving them
     * dirty parks 「家里没收下」 and can fail-close the parent tombstone in the
     * same commit (`invalid_domain` / `UnresolvedReference`). A parent that is
     * merely absent locally (or a tombstone NAS never acknowledged) is not
     * proof of death: the child stays dirty, waits on the freeze gates, and a
     * later pull or a server verdict resolves it.
     *
     * Accepted children whose parent is still live on NAS (local dirty tombstone)
     * are kept and published in an earlier batch — see
     * [partitionPoisoningParentTombstones].
     */
    private suspend fun abandonUnpublishableDependents() {
        wakeObservationDao.listPendingSync().forEach { wake ->
            val sleep = recordDao.getByClientUuid(wake.sleepRecordClientUuid)
            if (!shouldAbandonDependent(
                    childBaseVersion = wake.baseVersion,
                    parent = sleep?.toPublishState(),
                    abandonFirstSeenAgainstLocalTombstone = true,
                )
            ) {
                return@forEach
            }
            abandonMutation("wake_observation", wake.clientUuid)
        }
        recordDao.listPendingSync().forEach { record ->
            if (record.type != "custom") return@forEach
            val item = referencedCustomItem(record)
            if (!shouldAbandonDependent(
                    childBaseVersion = record.baseVersion,
                    parent = item?.toPublishState(),
                    abandonFirstSeenAgainstLocalTombstone = false,
                )
            ) {
                return@forEach
            }
            abandonMutation("record", record.clientUuid)
        }
        carePlanDao.listPendingSync().forEach { plan ->
            if (plan.type != "custom") return@forEach
            val item = plan.customItemId?.let { customItemDao.getById(it) }
            if (!shouldAbandonDependent(
                    childBaseVersion = plan.baseVersion,
                    parent = item?.toPublishState(),
                    abandonFirstSeenAgainstLocalTombstone = false,
                )
            ) {
                return@forEach
            }
            abandonMutation("care_plan", plan.clientUuid)
        }
    }

    /**
     * Wakes must not be first-seen against a tombstone (server: live sleep).
     * Custom records/plans are historical facts: if the catalog item is still
     * live on NAS, publish the child first instead of abandoning.
     */
    private fun shouldAbandonDependent(
        childBaseVersion: String?,
        parent: ParentPublishState?,
        abandonFirstSeenAgainstLocalTombstone: Boolean,
    ): Boolean {
        // A parent that is merely absent locally is a transient pull gap: the
        // freeze gates already refuse to commit dangling references, and a
        // later pull can still deliver the parent. Absence alone never
        // settles a child (that would silently drop a recoverable fact).
        if (parent == null) return false
        if (parent.deletedAt == null) return false
        val nasParentStillLive = parent.baseVersion != null && parent.syncDirty
        if (abandonFirstSeenAgainstLocalTombstone) {
            // Wakes: a live wake can never ingress against a dead Sleep
            // (server: live sleep required), and a first-seen wake on a sleep
            // the user deleted is moot intent — abandon it. An accepted wake
            // edit publishes first while NAS still has the sleep live
            // ([partitionPoisoningParentTombstones]); once the tombstone has
            // settled there, the edit can never ingress and is abandoned.
            if (childBaseVersion == null) return true
            return !nasParentStillLive
        }
        // Records/plans: historical facts stay writable and deletable while
        // their persisted reference matches (server historical_entity rule),
        // so an accepted child always publishes. A first-seen child is only
        // pre-emptively settled when the parent tombstone is durably
        // NAS-known and settled — a tombstone NAS never acknowledged
        // (baseVersion == null) may face a live item on NAS: attempt the
        // publish and let a terminal rejection keep the fact dirty and
        // visible instead.
        if (childBaseVersion == null) {
            if (parent.baseVersion == null) return false
            return !nasParentStillLive
        }
        return false
    }

    private fun RecordEntity.toPublishState() = ParentPublishState(deletedAt, baseVersion, syncDirty)

    private fun CustomItemEntity.toPublishState() = ParentPublishState(deletedAt, baseVersion, syncDirty)

    private data class ParentPublishState(
        val deletedAt: Long?,
        val baseVersion: String?,
        val syncDirty: Boolean,
    )

    private suspend fun referencedCustomItem(record: RecordEntity): CustomItemEntity? {
        val uuid = runCatching {
            resolveRecordCustomItemClientUuid(record, customItemDao)
        }.getOrNull() ?: return null
        return customItemDao.getByClientUuid(uuid)
    }

    /**
     * Record tombstones sort before wakes, and custom_item tombstones sort before
     * new records/plans. Server validate_push then sees a live first-seen child
     * against a just-tombstoned parent. Publish the child first while NAS still
     * has a live parent; send the parent tombstone in the next commit.
     */
    private suspend fun partitionPoisoningParentTombstones(
        frozen: List<FrozenCausalUnit>,
    ): Pair<List<FrozenCausalUnit>, List<FrozenCausalUnit>> {
        val defer = mutableSetOf<String>()
        for (unit in frozen) {
            if (!unit.mutation.deleted) continue
            when (unit.mutation.entityType) {
                "record" -> {
                    val sleep = recordDao.getByClientUuid(unit.mutation.clientUuid) ?: continue
                    if (sleep.type != "sleep") continue
                    val hasLiveWake = frozen.any { child ->
                        child.mutation.entityType == "wake_observation" &&
                            !child.mutation.deleted &&
                            wakeObservationDao.getByClientUuid(child.mutation.clientUuid)
                                ?.sleepRecordClientUuid == sleep.clientUuid
                    }
                    if (hasLiveWake) defer += unit.mutation.mutationId
                }
                "custom_item" -> {
                    val hasFirstSeenConsumer = frozen.any { child ->
                        child.mutation.baseVersion == null &&
                            childReferencesCustomItem(child, unit.mutation.clientUuid)
                    }
                    if (hasFirstSeenConsumer) defer += unit.mutation.mutationId
                }
            }
        }
        if (defer.isEmpty()) return frozen to emptyList()
        return frozen.filter { it.mutation.mutationId !in defer } to
            frozen.filter { it.mutation.mutationId in defer }
    }

    private suspend fun childReferencesCustomItem(
        child: FrozenCausalUnit,
        customItemClientUuid: String,
    ): Boolean = when (child.mutation.entityType) {
        "record" -> {
            val record = recordDao.getByClientUuid(child.mutation.clientUuid) ?: return false
            referencedCustomItem(record)?.clientUuid == customItemClientUuid
        }
        "care_plan" -> {
            val plan = carePlanDao.getByClientUuid(child.mutation.clientUuid) ?: return false
            plan.customItemId
                ?.let { customItemDao.getById(it) }
                ?.clientUuid == customItemClientUuid
        }
        else -> false
    }

    suspend fun abandonMutation(entityType: String, clientUuid: String) {
        val cache = conflictSnapshotCacheDao ?: return
        transactionRunner.run {
            val unsettled = mediaSettlementJournal?.restoreUnsettled(entityType, clientUuid)
            check(unsettled?.phase != CausalMediaSettlementPhase.CommitUnknown) {
                "媒体提交结果尚未确认，需先重试同步以保留完整恢复证据"
            }
            val current = loadCommitFirstLocal(entityType, clientUuid) ?: return@run
            when (entityType) {
                "baby" -> {
                    val b = babyDao.getByClientUuid(clientUuid) ?: return@run
                    babyDao.update(b.copy(syncDirty = false, mutationId = null))
                    mediaDao.activeAvatarForBaby(b.id)?.let { media ->
                        if (media.syncDirty) mediaDao.update(media.copy(syncDirty = false))
                    }
                }
                "record" -> {
                    val r = recordDao.getByClientUuid(clientUuid) ?: return@run
                    recordDao.update(r.copy(syncDirty = false, mutationId = null))
                    mediaDao.listForRecord(r.id).forEach { media ->
                        if (media.syncDirty) mediaDao.update(media.copy(syncDirty = false))
                    }
                }
                "care_plan" -> {
                    val p = carePlanDao.getByClientUuid(clientUuid) ?: return@run
                    carePlanDao.update(p.copy(syncDirty = false, mutationId = null))
                    mediaDao.listForCarePlan(p.id).forEach { media ->
                        if (media.syncDirty) mediaDao.update(media.copy(syncDirty = false))
                    }
                }
                "custom_item" -> {
                    val c = customItemDao.getByClientUuid(clientUuid) ?: return@run
                    customItemDao.update(c.copy(syncDirty = false, mutationId = null))
                }
                "wake_observation" -> {
                    val w = wakeObservationDao.getByClientUuid(clientUuid) ?: return@run
                    wakeObservationDao.update(w.copy(syncDirty = false, mutationId = null))
                    mediaDao.listActiveForWakeObservation(w.id).forEach { media ->
                        if (media.syncDirty) mediaDao.update(media.copy(syncDirty = false))
                    }
                }
            }
            cache.deleteFrozenMutation(entityType, clientUuid)
            mediaSettlementJournal?.clearBinding(entityType, clientUuid)
            val existingReceipt = cache.getTerminalReceipt(entityType, clientUuid)
            val recordedAt = existingReceipt?.recordedAt ?: System.currentTimeMillis()
            val receipt = TerminalRejectionReceipt(
                entityType = entityType,
                clientUuid = clientUuid,
                mutationId = existingReceipt?.mutationId.orEmpty(),
                code = existingReceipt?.code ?: "abandoned",
                contentEpoch = current.contentEpoch,
                recordedAt = recordedAt,
                abandoned = true,
            )
            cache.putTerminalReceipt(receipt)
            abandonDirtyFulfillmentCandidatesReferencing(
                cache = cache,
                entityType = entityType,
                clientUuid = clientUuid,
                recordedAt = recordedAt,
            )
        }
    }

    private suspend fun abandonDirtyFulfillmentCandidatesReferencing(
        cache: ConflictSnapshotCacheDao,
        entityType: String,
        clientUuid: String,
        recordedAt: Long,
    ) {
        val dao = fulfillmentCandidateDao ?: return
        val candidates = when (entityType) {
            "record" -> dao.listForRecord(clientUuid)
            "care_plan" -> dao.listForCarePlan(clientUuid)
            else -> return
        }
        for (candidate in candidates) {
            if (!candidate.syncDirty) continue
            val existing = cache.getTerminalReceipt(
                "fulfillment_candidate",
                candidate.clientUuid,
            )
            cache.putTerminalReceipt(
                TerminalRejectionReceipt(
                    entityType = "fulfillment_candidate",
                    clientUuid = candidate.clientUuid,
                    mutationId = existing?.mutationId.orEmpty(),
                    code = "abandoned",
                    contentEpoch = candidate.updatedAt,
                    recordedAt = recordedAt,
                    abandoned = true,
                ),
            )
        }
    }

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
        if (!baby.familyAuthority && baby.baseVersion == null && baby.deletedAt == null && !baby.syncDirty) return false
        if (baby.syncDirty && !cache.hasCurrentFrozenProvider("baby", baby.clientUuid, baby.mutationId)) {
            return false
        }
        val customItemUuid = resolveRecordCustomItemClientUuid(record, customItemDao) ?: return true
        val customItem = customItemDao.getByClientUuid(customItemUuid) ?: return false
        // First-seen custom facts must not wait for a catalog tombstone or they
        // enter the same commit and fail `custom_item_client_uuid is deleted`.
        if (customItem.deletedAt != null && record.baseVersion == null) return true
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
                CarePlanRootShape.LocalMutation,
                requireCanonicalIds = true,
            )
        }.getOrElse { return false }
        val references = resolveCarePlanReferences(
            wire = wire,
            babyDao = babyDao,
            customItemDao = customItemDao,
            recordDao = recordDao,
        ).referencesOrNull() ?: return false
        val baby = references.baby
        if (!baby.familyAuthority && baby.baseVersion == null && baby.deletedAt == null && !baby.syncDirty) return false
        if (baby.syncDirty && !cache.hasCurrentFrozenProvider("baby", baby.clientUuid, baby.mutationId)) {
            return false
        }
        val customItem = references.customItem
        val plan = carePlanDao.getByClientUuid(carePlanClientUuid)
        val waitForCustomItem = customItem?.deletedAt == null || plan?.baseVersion != null
        if (waitForCustomItem &&
            customItem?.syncDirty == true &&
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
        ).sleepOrNull() ?: return false
        // Accepted wake edits must not wait for a Sleep tombstone or they share
        // the commit and fail `wake_observation must reference a live sleep`.
        if (sleep.deletedAt != null) return true
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
        val frozen = runCatching { decodeFrozenCommitEnvelope(stored.payloadJson) }.getOrNull()
            ?: return false
        return frozen.mutation.entityType == entityType &&
            frozen.mutation.clientUuid == clientUuid &&
            frozen.mutation.mutationId == currentMutationId &&
            frozen.mutation.media.isEmpty()
    }

    private suspend fun carePlanPublishView(plan: CarePlanEntity): CarePlanEntity {
        val live = fulfillmentCandidateDao
            ?.listForCarePlan(plan.clientUuid)
            .orEmpty()
            .filter { it.deletedAt == null }
        val pair = FulfillmentAuthority.publishFulfillmentPair(
            statusStorageKey = SyncWireMapper.normalizeCarePlanStatusForWire(plan.status),
            displayRecordClientUuid = plan.fulfilledRecordClientUuid,
            displayFulfilledAt = plan.fulfilledAt,
            liveCandidates = live.map { candidate ->
                FulfillmentCandidateEvidence(
                    clientUuid = candidate.clientUuid,
                    recordClientUuid = candidate.recordClientUuid,
                    confirmedAt = candidate.confirmedAt,
                    submitterRole = candidate.submitterRole,
                )
            },
        )
        return plan.copy(
            fulfilledRecordClientUuid = pair.recordClientUuid,
            fulfilledAt = pair.fulfilledAt,
        )
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
                    val effective = record.effectiveWakeObservationClientUuid?.takeIf { uuid ->
                        isValidEffectiveWake(uuid, record.clientUuid)
                    }
                    injectEffectiveWake(
                        omitJsonKey(wire.payloadJson, "end_timestamp"),
                        effective,
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
                val wire = SyncWireMapper.carePlan(
                    carePlanPublishView(plan),
                    baby.clientUuid,
                    customUuid,
                )
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
        authorityKey: String,
    ): FrozenCausalMedia? {
        val cache = conflictSnapshotCacheDao ?: return null
        val assets = transactionRunner.run { loadActiveCausalMedia(entityType, clientUuid) }
        val revisions = assets.toCausalMediaRevisions()
        if (assets.isEmpty()) return FrozenCausalMedia(emptyList(), revisions, null)
        val evidenceKey = "media-freeze-capture-v1:$mutationId"
        val evidence = mediaCaptureEvidence(authorityKey, entityType, clientUuid, assets)
        // One raw-source digest per present input in this preparation attempt. Reuse this
        // result for donor comparison and the next durable binding; never decode twice.
        val sourceBytes = assets.associate { asset -> asset.clientUuid to mediaFiles.readableFile(asset.localUri)?.let {
            requireNotNull(MediaContentDigest.ofReadableFile(it)) {
                "present media source could not be hashed; retain preparation evidence for retry"
            } to it.length()
        } }
        val storedRow = cache.getFrozenMediaSpoolManifest(mutationId)
        val sidecar = immutableMediaSpool.recoverGroup(mutationId)?.group
        val previous = cache.getTransportJournal(evidenceKey)?.payloadJson?.let {
            Json.parseToJsonElement(it).jsonObject.also { value ->
                require(value.keys == setOf("format", "source", "current") && value["format"]?.jsonPrimitive?.content == "1") {
                    "unsupported media preparation evidence"
                }
            }
        }
        // An unbound sidecar is preparation evidence, not permission to stamp today's facts onto it.
        // Bound Pending/CommitUnknown/Branched envelopes are replayed by restoreUnsettled before here.
        if ((previous != null && previous["current"] != evidence) ||
            (previous == null && (storedRow != null || sidecar != null))) {
            quarantineUnboundPreparation(entityType, clientUuid, mutationId, evidenceKey, sidecar)
            return null
        }
        if (storedRow != null || sidecar != null) {
            val originalSources = cache.getTransportJournal("media-preparation-sources-v1:$mutationId")?.payloadJson
                ?.let { Json.parseToJsonElement(it).jsonObject }
            val entries = originalSources?.get("sources") as? JsonArray
            val currentEntries = evidence.getValue("media") as JsonArray
            val matches = originalSources?.keys == setOf("format", "authority", "type", "uuid", "sources") &&
                originalSources?.get("format")?.jsonPrimitive?.content == "1" &&
                originalSources?.get("authority")?.jsonPrimitive?.content == authorityKey &&
                originalSources?.get("type")?.jsonPrimitive?.content == entityType &&
                originalSources?.get("uuid")?.jsonPrimitive?.content == clientUuid &&
                entries?.size == assets.size && assets.all { asset ->
                    val captured = entries?.singleOrNull {
                        it.jsonObject["row"]?.jsonObject?.get("uuid")?.jsonPrimitive?.content == asset.clientUuid
                    }?.jsonObject
                    val current = currentEntries.single { it.jsonObject["uuid"]?.jsonPrimitive?.content == asset.clientUuid }
                    val item = sidecar?.items?.singleOrNull { it.mediaUuid == asset.clientUuid }
                    val adoptedCanonical = previous?.get("current") == evidence && item != null &&
                        item.role == CausalMediaPolicy.roleForEntityType(entityType) &&
                        item.sha256 == asset.sha256 && item.byteSize == asset.byteSize &&
                        item.mime == asset.mime && item.width == asset.width?.toLong() && item.height == asset.height?.toLong() &&
                        (sourceBytes[asset.clientUuid] == null || sourceBytes[asset.clientUuid] == (item.sha256 to item.byteSize))
                    adoptedCanonical || (captured?.get("row") == current && sourceBytesStillMatch(sourceBytes[asset.clientUuid], captured))
                }
            if (!matches) {
                quarantineUnboundPreparation(entityType, clientUuid, mutationId, evidenceKey, sidecar)
                return null
            }
        }
        val quarantine = cache.getTransportJournal(
            com.lezi.babylog.sync.disasterrecovery.RestoreArtifactRetirement.KEY,
        )?.payloadJson?.let { Json.parseToJsonElement(it).jsonObject }
        val donorIds = (quarantine?.get("groups") as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()
        val donors = donorIds.sorted().filter { it != mutationId }.mapNotNull { id ->
            cache.getFrozenMediaSpoolManifest(id)?.let { row ->
                decodeFrozenMediaSpoolManifest(row.payloadJson).also { require(it.mutationId == id) }
            }
        }
        val currentSources = evidence.getValue("media") as JsonArray
        val preparationProofs = donors.associate { donor -> donor.mutationId to
            cache.getTransportJournal("media-preparation-sources-v1:${donor.mutationId}")?.payloadJson
                ?.let { Json.parseToJsonElement(it).jsonObject.also { proof ->
                    require(proof.keys == setOf("format", "authority", "type", "uuid", "sources") &&
                        proof["format"]?.jsonPrimitive?.content == "1") { "invalid media preparation source proof" }
                } } }
        val sources = assets.sortedBy(MediaAssetEntity::clientUuid).map { asset ->
            val role = CausalMediaPolicy.roleForEntityType(entityType) ?: return null
            val currentSource = currentSources.single { it.jsonObject["uuid"]?.jsonPrimitive?.content == asset.clientUuid }
            var donorIdentity: PublishedMediaIdentity? = null
            val donor = donors.firstNotNullOfOrNull { group ->
                val item = group.items.singleOrNull { it.mediaUuid == asset.clientUuid && it.role == role }
                    ?: return@firstNotNullOfOrNull null
                val exactCanonical = asset.sha256 != null && item.sha256 == asset.sha256 && item.byteSize == asset.byteSize
                val proof = preparationProofs[group.mutationId]
                val source = (proof?.get("sources") as? JsonArray)?.singleOrNull {
                    it.jsonObject["row"]?.jsonObject?.get("uuid")?.jsonPrimitive?.content == asset.clientUuid
                }?.jsonObject
                val unchangedPreparation = proof?.get("authority")?.jsonPrimitive?.content == authorityKey &&
                    proof?.get("type")?.jsonPrimitive?.content == entityType && proof?.get("uuid")?.jsonPrimitive?.content == clientUuid &&
                    source?.get("row") == currentSource && sourceBytesStillMatch(sourceBytes[asset.clientUuid], source)
                if (!exactCanonical && !unchangedPreparation) return@firstNotNullOfOrNull null
                // A canonical result belongs to its original source revision even when bitmap
                // normalization changed SHA/size/MIME. Reuse only that item in new ownership.
                donorIdentity = if (exactCanonical) PublishedMediaIdentity(asset.sha256, asset.byteSize,
                    asset.mime, asset.width, asset.height) else PublishedMediaIdentity(item.sha256, item.byteSize,
                    item.mime, item.width?.toInt(), item.height?.toInt())
                com.lezi.babylog.sync.media.RetainedMediaSpoolSource(group.mutationId, item,
                    rebaseMetadata = exactCanonical && (item.mime != asset.mime || item.width != asset.width?.toLong() ||
                        item.height != asset.height?.toLong()))
            }
            val canonical = donor != null ||
                cache.getTransportJournal("canonical-media-bytes-v1:${asset.clientUuid}")?.payloadJson == asset.localUri ||
                cache.getTransportJournal("restored-media-bytes-v1:${asset.clientUuid}")?.payloadJson == asset.localUri
            if (asset.remoteUri?.isNotBlank() == true && donor == null) {
                val file = mediaFiles.readableFile(asset.localUri)
                if (!canonical || file == null || file.length() != asset.byteSize ||
                    MediaContentDigest.ofReadableFile(file) != asset.sha256) throw PublishedMediaAuthorityRequiredException()
            }
            ImmutableMediaSpoolSource(asset.clientUuid, role, asset.localUri,
                publishedIdentity = donorIdentity ?: if (canonical) PublishedMediaIdentity(asset.sha256, asset.byteSize,
                    asset.mime, asset.width, asset.height) else null,
                retainedSource = donor)
        }
        val preparationKey = "media-preparation-sources-v1:$mutationId"
        val newPreparationProof = if (cache.getTransportJournal(preparationKey) == null) buildJsonObject {
            put("format", 1); put("authority", authorityKey); put("type", entityType); put("uuid", clientUuid)
            put("sources", JsonArray(assets.sortedBy { it.clientUuid }.map { asset ->
                val bytes = sourceBytes[asset.clientUuid]
                buildJsonObject {
                    put("row", currentSources.single { it.jsonObject["uuid"]?.jsonPrimitive?.content == asset.clientUuid })
                    put("byte_sha", bytes?.first?.let(::JsonPrimitive) ?: JsonNull)
                    put("byte_size", bytes?.second?.let(::JsonPrimitive) ?: JsonNull)
                }
            }))
        }.toString() else null
        if (previous == null) transactionRunner.run {
            if (loadActiveCausalMedia(entityType, clientUuid).toCausalMediaRevisions() != revisions) return@run
            cache.putTransportJournal(evidenceKey, buildJsonObject {
                put("format", 1); put("source", evidence); put("current", evidence)
            }.toString(), contentEpoch)
        }
        if (cache.getTransportJournal(evidenceKey) == null) return null
        if (newPreparationProof != null) transactionRunner.run {
            if (loadActiveCausalMedia(entityType, clientUuid).toCausalMediaRevisions() != revisions) return@run
            if (cache.getTransportJournal(preparationKey) == null)
                cache.putTransportJournal(preparationKey, newPreparationProof, contentEpoch)
        }
        if (cache.getTransportJournal(preparationKey) == null) return null
        val group = if (storedRow != null) {
            require(decodeCausalMediaSettlementOrNull(storedRow.payloadJson) == null) { "bound media cannot be recaptured" }
            decodeFrozenMediaSpoolManifest(storedRow.payloadJson).also { stored ->
                require((immutableMediaSpool.recoverGroup(mutationId) as? ImmutableMediaSpoolRecovery.Complete)?.group == stored)
            }
        } else immutableMediaSpool.freezeGroup(mutationId, sources)
        require(group.mutationId == mutationId && group.items.size == sources.size)
        group.items.forEach { item ->
            val source = sources.single { it.mediaUuid == item.mediaUuid }
            require(source.role == item.role)
            source.publishedIdentity?.let { identity ->
                require(item.matches(identity)) { "recovered media does not match captured canonical identity" }
            }
        }
        val owned = transactionRunner.run {
            val current = loadCommitFirstLocal(entityType, clientUuid) ?: return@run false
            if (current.mutationId != mutationId || current.contentEpoch != contentEpoch ||
                mediaCaptureEvidence(authorityKey, entityType, clientUuid,
                    loadActiveCausalMedia(entityType, clientUuid)) != evidence) return@run false
            cache.putFrozenMediaSpoolManifest(mutationId, encodeImmutableMediaSpoolGroup(group), contentEpoch)
            true
        }
        if (!owned) return null
        // Persist canonical bytes at a unique product-local path before a terminal receipt can
        // retire the spool. Copy failure leaves the immutable Room reference and bytes intact.
        val copied = linkedMapOf<String, String>()
        val reservedPaths = linkedSetOf<String>()
        try {
        for (item in group.items) {
            val asset = assets.single { it.clientUuid == item.mediaUuid }
            val alreadyCanonical = asset.sha256 == item.sha256 && asset.byteSize == item.byteSize &&
                asset.mime == item.mime && asset.width?.toLong() == item.width && asset.height?.toLong() == item.height &&
                sourceBytes[asset.clientUuid] == (item.sha256 to item.byteSize)
            if (!alreadyCanonical) {
                val bytes = immutableMediaSpool.open(mutationId, item).openStream().use { it.readBytes() }
                require(bytes.size.toLong() == item.byteSize && MediaContentDigest.ofBytes(bytes) == item.sha256)
                copied[item.mediaUuid] = withContext(NonCancellable) {
                    mediaFiles.saveDownloadedOwned(UUID.randomUUID().toString(),
                        if (item.role == com.lezi.babylog.sync.media.CausalMediaRole.Avatar) "avatar" else "log", bytes, item.mime) { path ->
                        reservedPaths += path
                        cache.putTransportJournal("canonical-media-materialization-v1",
                            JsonArray(reservedPaths.map(::JsonPrimitive)).toString(), contentEpoch)
                    }
                }
            }
        }
        val canonicalRows = assets.map { asset ->
            val item = group.items.single { it.mediaUuid == asset.clientUuid }
            asset.copy(localUri = copied[asset.clientUuid] ?: asset.localUri, sha256 = item.sha256,
                byteSize = item.byteSize, mime = item.mime, width = item.width?.toInt(), height = item.height?.toInt())
        }
        val adopted = transactionRunner.run {
            val current = loadCommitFirstLocal(entityType, clientUuid) ?: return@run false
            if (current.mutationId != mutationId || current.contentEpoch != contentEpoch ||
                mediaCaptureEvidence(authorityKey, entityType, clientUuid,
                    loadActiveCausalMedia(entityType, clientUuid)) != evidence) return@run false
            canonicalRows.forEach { row ->
                mediaDao.update(row)
                cache.putTransportJournal("canonical-media-bytes-v1:${row.clientUuid}", row.localUri, row.updatedAt)
                if (row.kind == "avatar" && row.localUri != assets.single { it.clientUuid == row.clientUuid }.localUri) {
                    row.babyId?.let { id -> babyDao.getIncludingDeleted(id)?.let { baby ->
                        babyDao.update(baby.copy(avatarPath = row.localUri))
                    } }
                }
            }
            val original = Json.parseToJsonElement(requireNotNull(cache.getTransportJournal(evidenceKey)).payloadJson).jsonObject
            cache.putTransportJournal(evidenceKey, JsonObject(original + ("current" to
                mediaCaptureEvidence(authorityKey, entityType, clientUuid, canonicalRows))).toString(), contentEpoch)
            true
        }
        if (!adopted) return null
        return FrozenCausalMedia(group.items.map(ImmutableMediaSpoolItem::toCausalMediaItem),
            canonicalRows.toCausalMediaRevisions(), group)
        } finally {
            withContext(NonCancellable) {
                cleanupUnownedMediaPaths(reservedPaths)
                cache.deleteTransportJournal("canonical-media-materialization-v1")
            }
        }
    }

    private suspend fun recoverMaterializedMediaPaths() {
        val cache = conflictSnapshotCacheDao ?: return
        val journal = cache.getTransportJournal("canonical-media-materialization-v1") ?: return
        val paths = (Json.parseToJsonElement(journal.payloadJson) as JsonArray)
            .map { it.jsonPrimitive.content }.toSet()
        cleanupUnownedMediaPaths(paths)
        cache.deleteTransportJournal(journal.journalKey)
    }

    private fun sourceBytesStillMatch(bytes: Pair<String, Long>?, source: JsonObject?): Boolean {
        source ?: return false
        require(source.keys == setOf("row", "byte_sha", "byte_size")) { "invalid media source byte proof" }
        bytes ?: return true // retained canonical spool can be the sole surviving copy
        val sha = (source["byte_sha"] as? JsonPrimitive)?.contentOrNull ?: return false
        val size = (source["byte_size"] as? JsonPrimitive)?.longOrNull ?: return false
        return bytes.first == sha && bytes.second == size
    }

    private suspend fun mediaCaptureEvidence(authority: String, type: String, uuid: String,
        media: List<MediaAssetEntity>): JsonObject = buildJsonObject {
        put("authority", authority); put("type", type); put("uuid", uuid)
        put("base", loadCommitFirstLocal(type, uuid)?.baseVersion?.let(::JsonPrimitive) ?: JsonNull)
        put("root", buildCausalRootJson(type, uuid)?.let { Json.parseToJsonElement(it) } ?: JsonNull)
        put("deleted", loadCommitFirstLocal(type, uuid)?.deleted ?: false)
        put("media", JsonArray(media.sortedBy { it.clientUuid }.map { row -> buildJsonObject {
            put("uuid", row.clientUuid); put("uri", row.localUri); put("kind", row.kind)
            put("sha", row.sha256?.let(::JsonPrimitive) ?: JsonNull); put("size", row.byteSize)
            put("mime", row.mime?.let(::JsonPrimitive) ?: JsonNull)
            put("width", row.width?.let(::JsonPrimitive) ?: JsonNull); put("height", row.height?.let(::JsonPrimitive) ?: JsonNull)
            put("record", row.recordId?.let(::JsonPrimitive) ?: JsonNull); put("plan", row.carePlanId?.let(::JsonPrimitive) ?: JsonNull)
            put("baby", row.babyId?.let(::JsonPrimitive) ?: JsonNull); put("wake", row.wakeObservationId?.let(::JsonPrimitive) ?: JsonNull)
            put("updated", row.updatedAt); put("deleted", row.deletedAt?.let(::JsonPrimitive) ?: JsonNull)
        } }))
    }

    private suspend fun quarantineUnboundPreparation(type: String, uuid: String, mutation: String,
        evidenceKey: String, sidecar: ImmutableMediaSpoolGroup?) = transactionRunner.run {
        val cache = requireNotNull(conflictSnapshotCacheDao)
        check(cache.getFrozenMutation(type, uuid) == null) { "published mutation cannot be replaced" }
        val old = cache.getFrozenMediaSpoolManifest(mutation)
        check(old == null || decodeCausalMediaSettlementOrNull(old.payloadJson) == null) { "bound media cannot be replaced" }
        if (sidecar != null && sidecar.items.isNotEmpty()) {
            cache.putFrozenMediaSpoolManifest(mutation, encodeImmutableMediaSpoolGroup(sidecar), 0)
            val oldRetirement = cache.getTransportJournal(com.lezi.babylog.sync.disasterrecovery.RestoreArtifactRetirement.KEY)
                ?.payloadJson?.let { Json.parseToJsonElement(it).jsonObject }
            val ids = (oldRetirement?.get("groups") as? JsonArray).orEmpty().map { it.jsonPrimitive.content } + mutation
            val snapshot = oldRetirement?.get("snapshot_key")?.jsonPrimitive?.content ?: evidenceKey
            cache.putTransportJournal(com.lezi.babylog.sync.disasterrecovery.RestoreArtifactRetirement.KEY,
                com.lezi.babylog.sync.disasterrecovery.RestoreArtifactRetirement.encode(snapshot, ids), 0)
        }
        if (sidecar == null || sidecar.items.isEmpty()) {
            cache.deleteTransportJournal(evidenceKey)
            cache.deleteTransportJournal("media-preparation-sources-v1:$mutation")
        }
        val next = UUID.randomUUID().toString()
        when (type) {
            "baby" -> babyDao.getByClientUuid(uuid)?.takeIf { it.mutationId == mutation }?.let { babyDao.update(it.copy(mutationId = next)) }
            "record" -> recordDao.getByClientUuid(uuid)?.takeIf { it.mutationId == mutation }?.let { recordDao.update(it.copy(mutationId = next)) }
            "care_plan" -> carePlanDao.getByClientUuid(uuid)?.takeIf { it.mutationId == mutation }?.let { carePlanDao.update(it.copy(mutationId = next)) }
            "wake_observation" -> wakeObservationDao.getByClientUuid(uuid)?.takeIf { it.mutationId == mutation }?.let { wakeObservationDao.update(it.copy(mutationId = next)) }
        }
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
                sha256 = asset.sha256, byteSize = asset.byteSize, mime = asset.mime,
                width = asset.width, height = asset.height,
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
        frozen: List<FrozenCausalUnit>,
    ) {
        val unitsWithMedia = frozen.filter { it.mutation.media.isNotEmpty() }
        if (unitsWithMedia.isEmpty()) return
        requireRemoteAllowed(session)
        val cache = requireNotNull(conflictSnapshotCacheDao) {
            "causal media upload requires durable Room manifest storage"
        }
        val journal = requireNotNull(mediaSettlementJournal) {
            "causal media upload requires settlement journal"
        }
        // Manifest resolution + integrity requires are cheap local reads; do
        // them serially first so a bad manifest fails before any bytes move.
        data class PendingPreimage(
            val mutationId: String,
            val item: CausalMediaItem,
            val spoolItem: ImmutableMediaSpoolItem,
        )
        val pending = mutableListOf<PendingPreimage>()
        for (unit in unitsWithMedia) {
            val group = cache.getFrozenMediaSpoolManifest(unit.mutation.mutationId)?.let { row ->
                decodeFrozenMediaSpoolManifest(row.payloadJson)
            } ?: throw AuthorityProofException(
                session.pullGeneration,
                IllegalArgumentException("因果媒体缺少不可变 spool manifest"),
            )
            require(group.items.map(ImmutableMediaSpoolItem::toCausalMediaItem) == unit.mutation.media) {
                "因果媒体 spool manifest 与冻结 mutation 不一致"
            }
            for (item in unit.mutation.media) {
                val spoolItem = group.items.single { it.mediaUuid == item.mediaUuid }
                if (journal.preparedReceipt(unit.mutation.mutationId, spoolItem) != null) {
                    continue
                }
                pending += PendingPreimage(unit.mutation.mutationId, item, spoolItem)
            }
        }
        if (pending.isEmpty()) return
        val uploadSlots = Semaphore(CAUSAL_PREIMAGE_UPLOAD_PARALLELISM)
        coroutineScope {
            pending.forEach { entry ->
                launch {
                    uploadSlots.withPermit {
                        val receipt = putCausalMediaPreimageOrBind(
                            session = session,
                            mutationId = entry.mutationId,
                            item = entry.item,
                            spoolItem = entry.spoolItem,
                        )
                        journal.recordPrepared(entry.mutationId, receipt)
                    }
                }
            }
        }
    }

    private suspend fun putCausalMediaPreimageOrBind(
        session: SyncSession,
        mutationId: String,
        item: CausalMediaItem,
        spoolItem: ImmutableMediaSpoolItem,
    ): CausalMediaPreimageReceipt {
        if (mediaDao.hasPublishedContentIdentity(item.sha256)) {
            try {
                return backend.putCausalMediaPreimage(
                    session = session,
                    mediaUuid = item.mediaUuid,
                    source = EmptyCausalMediaBindSource(
                        declaredByteSize = item.byteSize,
                        mime = spoolItem.mime,
                    ),
                    sha256 = item.sha256,
                )
            } catch (error: SyncHttpException) {
                if (error.statusCode !in 400..499) throw error
            }
        }
        val source = immutableMediaSpool.open(mutationId, spoolItem)
        return backend.putCausalMediaPreimage(
            session = session,
            mediaUuid = item.mediaUuid,
            source = source,
            sha256 = item.sha256,
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
        result: CausalCommitUnitResult,
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
        val currentRoot = root
            .filterKeys { it != "updated_at" && it != "deleted_at" }
            .toMutableMap()
            .also { fields ->
                if ("created_by_membership_id" !in fields) {
                    fields["created_by_membership_id"] = existing.createdByMembershipId
                        .takeIf(String::isNotBlank)
                        ?.let(::JsonPrimitive)
                        ?: JsonNull
                }
                val typeKey = (fields["type"] as? JsonPrimitive)?.content
                if (typeKey == "sleep") {
                    if ("effective_wake_observation_client_uuid" !in fields) {
                        fields["effective_wake_observation_client_uuid"] = JsonNull
                    }
                } else if ("end_timestamp" !in fields) {
                    fields["end_timestamp"] = JsonNull
                }
            }
        val wire = parseRecordWire(
            JsonObject(currentRoot),
        )
        val baby = requireNotNull(babyDao.getByClientUuid(wire.babyClientUuid)) {
            "record stable_root Baby 引用无效"
        }
        val customItemId = wire.customItemClientUuid?.let { customItemUuid ->
            requireNotNull(customItemDao.getByClientUuid(customItemUuid)?.id) {
                "record stable_root CustomItem 引用无效"
            }
        }
        recordDao.update(
            existing.copy(
                babyId = baby.id,
                type = wire.type.key,
                timestamp = wire.timestamp,
                endTimestamp = if (wire.type.key == "sleep") {
                    existing.endTimestamp
                } else {
                    wire.endTimestamp
                },
                note = wire.note,
                createdByMembershipId = wire.createdByMembershipId,
                payloadJson = SyncWireMapper.localPayloadFromWire(
                    wire.type,
                    wire.payload,
                    customItemId,
                ),
                schemaVersion = wire.schemaVersion,
                updatedAt = root["updated_at"]?.jsonPrimitive?.longOrNull
                    ?: existing.updatedAt,
                deletedAt = deletedAt,
                baseVersion = stableVersion,
                // Ack already cleared mutation/dirty/conflict; keep those columns.
                mutationId = existing.mutationId,
                syncDirty = existing.syncDirty,
                openConflictId = existing.openConflictId,
                localBranchVersionId = existing.localBranchVersionId,
                effectiveWakeObservationClientUuid =
                    if (wire.effectiveWakeObservationPresent) {
                        wire.effectiveWakeObservationClientUuid
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
            CarePlanRootShape.StableRoot,
            requireCanonicalIds = true,
        )
        val references = requireNotNull(
            resolveCarePlanReferences(
                wire = wire,
                babyDao = babyDao,
                customItemDao = customItemDao,
                recordDao = recordDao,
            ).referencesOrNull(),
        ) { "care_plan stable_root 引用无效或跨 Baby" }
        val customItemId = references.customItem?.id
        val localPayload = SyncWireMapper.localPayloadFromWire(
            type = wire.type,
            payload = wire.payload,
            customItemId = customItemId,
            allowIntentOnlyFeed = carePlanAllowsIntentOnlyFeed(wire.type, wire.note),
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
                fulfilledRecordClientUuid = existing.fulfilledRecordClientUuid,
                fulfilledAt = existing.fulfilledAt,
                sourceRecordClientUuid = wire.sourceRecordClientUuid,
                updatedAt = requireNotNull(wire.inlineUpdatedAt),
                deletedAt = deletedAt,
                baseVersion = stableVersion,
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

    /** Causal fields of one local root; [contentEpoch] is the row's updated_at. */
    internal data class LocalCausal(
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
        batch: CausalCommitBatchResult,
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
        val byMutation = batch.results.groupBy(CausalCommitUnitResult::mutationId)
        if (byMutation.keys != expectedMutations || byMutation.values.any { it.size != 1 }) {
            fail("家庭服务器因果响应 mutation_id 不完整、重复或包含多余 key")
        }
        if (batch.results.map(CausalCommitUnitResult::mutationId) != expectedMutationOrder) {
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
            val known = setOf(
                CausalCommitStatus.ACCEPTED,
                CausalCommitStatus.MERGED,
                CausalCommitStatus.BRANCHED,
            )
            if (result.status !in known) {
                fail("家庭服务器返回未知因果 disposition: ${result.status}")
            }
            val unit = frozenByMutation.getValue(result.mutationId)
            if (!result.requestHash.matches(MediaContentDigest.HEX) ||
                result.requestHash != unit.contentHash
            ) {
                fail("因果 request_hash 不是冻结内容的 canonical SHA-256")
            }
            if (result.stableVersionId.isNullOrBlank()) {
                fail("因果 ${result.status} 缺少 stable_version_id")
            }
            validateStableProjection(unit, result, ::fail)
        }
    }

    private suspend fun validateStableProjection(
        unit: FrozenCausalUnit,
        result: CausalCommitUnitResult,
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
            "record" -> {
                if (stableRoot["created_by_membership_id"] === JsonNull) {
                    runCatching {
                        val stamp = stableRoot["updated_at"] as? JsonPrimitive
                        require(stamp != null && !stamp.isString && stamp.longOrNull?.let { it >= 0 } == true)
                        val wire = parseRecordWire(JsonObject(stableRoot - "updated_at" - "deleted_at"))
                        SyncWireMapper.requireCurrentTransportPayload(wire.type, wire.payload)
                        requireNotNull(babyDao.getByClientUuid(wire.babyClientUuid))
                        wire.customItemClientUuid?.let { requireNotNull(customItemDao.getByClientUuid(it)) }
                    }.getOrElse { fail("因果 ${result.status} anonymous record stable_root 类型或引用无效") }
                }
                null
            }
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
                        CarePlanRootShape.StableRoot,
                        requireCanonicalIds = true,
                    )
                        .also { wire ->
                            if (stableRoot["created_by_membership_id"] === JsonNull) {
                                SyncWireMapper.requireCurrentTransportPayload(
                                    wire.type,
                                    wire.payload,
                                    allowIntentOnlyFeed = carePlanAllowsIntentOnlyFeed(wire.type, wire.note),
                                )
                                require(resolveCarePlanReferences(wire, babyDao, customItemDao, recordDao)
                                    is CarePlanReferenceResult.Resolved)
                            }
                        }
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
                    ).sleepOrNull() == null
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
                    !it.sha256.matches(MediaContentDigest.HEX) ||
                    it.byteSize <= 0 ||
                    runCatching { requireCanonicalMediaMime(it.mime) }.isFailure
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
                    put("mime", media.mime?.let(::JsonPrimitive) ?: JsonNull)
                    put("role", media.role)
                    put("sha256", media.sha256)
                    // Rust CausalMediaItem serializes all seven keys, including explicit null dimensions.
                    put("height", media.height?.let(::JsonPrimitive) ?: JsonNull)
                    put("width", media.width?.let(::JsonPrimitive) ?: JsonNull)
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

private fun sha256Hex(bytes: ByteArray): String = MediaContentDigest.ofBytes(bytes)

internal fun SyncSession.mediaAuthorityKey(): String = listOf(baseUrl, familyId, membershipId, deviceId, pullGeneration)
    .joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }

private fun ImmutableMediaSpoolItem.matches(identity: PublishedMediaIdentity): Boolean =
    (identity.sha256 == null || sha256 == identity.sha256) && byteSize == identity.byteSize &&
        mime == identity.mime && width == identity.width?.toLong() && height == identity.height?.toLong()
