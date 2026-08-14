package com.lezi.babylog.sync.engine

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.fulfillment.FulfillmentAuthoritySettlement
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.isNextFeedPlanNote
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.core.database.causal.SourceRelationReason
import com.lezi.babylog.core.database.causal.WakeObservationDao
import com.lezi.babylog.core.model.RecordType
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.SyncPlan
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.AuthorityProofException
import com.lezi.babylog.sync.backend.AuthenticatedSyncHandshake
import com.lezi.babylog.sync.backend.AUTHENTICATED_SYNC_PROTOCOL_VERSION
import com.lezi.babylog.sync.backend.REQUIRED_CAUSAL_WIRE_CAPABILITIES
import com.lezi.babylog.sync.backend.SyncHandshakeRejectedException
import com.lezi.babylog.sync.backend.PullTransportContract
import com.lezi.babylog.sync.backend.requireValidPage
import com.lezi.babylog.sync.media.ReferenceAwareMediaFileCleanup
import com.lezi.babylog.sync.media.SyncMediaFileStore
import com.lezi.babylog.sync.media.ImmutableMediaSpool
import com.lezi.babylog.sync.session.CreatorAcknowledgementRef
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.FamilySessionReplica
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SyncPreferences
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.normalizeFamilyNameForWire
import com.lezi.babylog.sync.session.receiptFor

internal object AtomicBundleId {
    private const val NAMESPACE = "lezi.atomic-bundle.v1"

    fun forFulfillmentCandidate(candidateClientUuid: String, updatedAt: Long): String =
        UUID.nameUUIDFromBytes(
            "$NAMESPACE:fulfillment_candidate:$candidateClientUuid:$updatedAt"
                .toByteArray(Charsets.UTF_8),
        ).toString()
}

internal sealed interface ReplicaSyncOutcome {
    data object Synchronized : ReplicaSyncOutcome
}

private data class CapturedLocalChanges(
    val candidates: List<PublishCandidate>,
    val pendingCreatorAcknowledgements: Set<CreatorAcknowledgementRef>,
)

/**
 * Owns one complete foreground replica cycle behind a single interface.
 *
 * The caller supplies a joined session and trigger. Full cycles
 * ([SyncTrigger.Foreground] / [SyncTrigger.PullToRefresh]) always pull remote pages before
 * freezing dirty Room entities into an ephemeral publication plan.
 *
 * [SyncTrigger.LocalWrite] declares [SyncPlan.pull]=false. That no-pull plan is applied only
 * when [SyncBackend.supportsCausalWire] is true: freeze current dirty atomic roots and settle
 * through each root's causal settlement seam without incremental pull or pull-cursor
 * advance. Every mutable root uses commit-first; without causal wire capability, mutable roots
 * fail closed after the safety pull. Immutable FulfillmentCandidate evidence continues through
 * its existing atomic bundle because it is a historical fact, not a client-resolved root.
 * Authenticated [SyncBackend.members] + self-membership
 * convergence remains a deliberate LocalWrite precondition (roster, not pull cursor).
 *
 * Every remote page and media retry re-enters the same gate. Failures and cancellation escape
 * without being translated; Room remains authoritative and the next cycle replans from its
 * current state.
 */
internal class ReplicaSyncEngine(
    private val backend: SyncBackend,
    private val preferences: SyncPreferences,
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val babyDao: BabyDao,
    private val mediaDao: MediaAssetDao,
    private val customItemDao: CustomItemDao,
    private val familyDao: FamilyDao,
    private val clock: PolicyClock,
    private val mediaFiles: SyncMediaFileStore,
    private val immutableMediaSpool: ImmutableMediaSpool,
    private val mediaFileCleanup: ReferenceAwareMediaFileCleanup,
    private val transactionRunner: DatabaseTransactionRunner,
    private val carePlanAppliedListener: CarePlanFamilyAppliedListener,
    private val familyBabyAppliedListener: FamilyBabyAuthorityAppliedListener =
        NoOpFamilyBabyAuthorityAppliedListener(),
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val fulfillmentAuthoritySettlement: FulfillmentAuthoritySettlement,
    private val requireRemoteAllowed: suspend (SyncSession) -> Unit,
    private val wakeObservationDao: WakeObservationDao,
    private val conflictSummaryDao: ConflictSummaryDao,
    private val conflictSnapshotCacheDao:
        com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao? = null,
    private val sourceRelationDao:
        com.lezi.babylog.core.database.causal.SourceRelationDao? = null,
) : FamilySessionReplica {
    private val resetReceiptJournal = conflictSnapshotCacheDao?.let(::ReplicaResetReceiptJournal)

    private val publisher = EphemeralPublishPipeline(
        backend = backend,
        carePlanDao = carePlanDao,
        babyDao = babyDao,
        fulfillmentCandidateDao = fulfillmentCandidateDao,
        requireRemoteAllowed = requireRemoteAllowed,
    )
    private val causalSettlement = CausalSettlement(
        backend = backend,
        recordDao = recordDao,
        carePlanDao = carePlanDao,
        babyDao = babyDao,
        mediaDao = mediaDao,
        customItemDao = customItemDao,
        wakeObservationDao = wakeObservationDao,
        conflictSummaryDao = conflictSummaryDao,
        conflictSnapshotCacheDao = conflictSnapshotCacheDao,
        immutableMediaSpool = immutableMediaSpool,
        transactionRunner = transactionRunner,
        requireRemoteAllowed = requireRemoteAllowed,
    )

    suspend fun synchronize(
        session: SyncSession,
        trigger: SyncTrigger,
    ): ReplicaSyncOutcome {
        val plan = SyncPlan.forTrigger(trigger)
        // Full cycles recover a missing generation via pull 409 → full resync.
        // LocalWrite still fail-closes: it must not invent a replica epoch.
        session.requireCurrentReplicaSession(requireGeneration = !plan.pull)
        mediaFileCleanup.cleanupPendingTombstones()
        val mediaEditGuard = captureLocalMediaEditGuard()
        var current = preferences.session.first()
        val transitionReceipt = resetReceiptJournal?.load()?.takeIf { it.belongsTo(current) }
        requireRemoteAllowed(current)
        val handshake = backend.authenticatedHandshake(current)
        handshake.requireCompatible(current)
        val pullTransport = handshake.pullTransport()
        if (preferences.familyMemberDirectoryGeneration.first() != handshake.directoryGeneration) {
            val directory = backend.memberDirectory(current)
            check(directory.generation == handshake.directoryGeneration) {
                "member directory changed during authenticated sync handshake"
            }
            current = convergeAuthenticatedSelfMembership(current, directory.members)
            preferences.saveFamilyMemberDirectorySnapshot(
                generation = directory.generation,
                members = directory.members,
            )
        }
        // LocalWrite no-pull plan applies only with the capabilities frozen by this handshake.
        // Spec / ADR-0020: no-pull before causal base/three-way/branch is rejected.
        val doPull = plan.pull || !backend.supportsCausalWire() || transitionReceipt != null
        var recovered = false
        // When doPull is false (LocalWrite + causal): freeze dirty roots → settle only.
        // Do not incremental-pull and do not advance the pull cursor; full cycles still pull.
        if (doPull) {
            try {
                current = pullAllPages(
                    current,
                    forceAuthority = transitionReceipt != null,
                    resetReceipt = transitionReceipt,
                    pullTransport = pullTransport,
                    mediaEditGuard = mediaEditGuard,
                )
            } catch (error: SyncHttpException) {
                val checkpoint = error.fullResyncCheckpointOrNull() ?: throw error
                current = recoverFullResync(
                    current,
                    checkpoint,
                    pullTransport,
                    mediaEditGuard,
                    transitionReceipt,
                )
                recovered = true
            }
        }
        if (plan.push && !recovered) {
            if (current.role == FamilyRole.Member) {
                // Incremental cycles do not go through recoverFullResync. Local-only
                // orphan subtrees are not family intent; settle them before capture
                // so a leftover dirty Baby is not committed and rejected as forbidden.
                settleMemberLocalOnlySubtrees()
            }
            val captured = captureLocalChanges(current)
            if (captured.pendingCreatorAcknowledgements.isNotEmpty()) {
                preferences.updateCreatorAcknowledgements(
                    add = captured.pendingCreatorAcknowledgements,
                )
                current = preferences.session.first()
            }
            try {
                settleAndPublish(current, captured.candidates)
            } catch (error: AuthorityProofException) {
                current = recoverFullResync(
                    current,
                    FullResyncCheckpoint(
                        resetCursor = 0,
                        serverGeneration = error.serverGeneration,
                    ),
                    pullTransport,
                    mediaEditGuard,
                    transitionReceipt,
                )
                recovered = true
            } catch (error: SyncHttpException) {
                val checkpoint = error.fullResyncCheckpointOrNull() ?: throw error
                current = recoverFullResync(
                    current,
                    checkpoint,
                    pullTransport,
                    mediaEditGuard,
                    transitionReceipt,
                )
                recovered = true
            }
            // Creator-ack and peer convergence require pull; only cycles that pulled do it.
            if (doPull &&
                captured.pendingCreatorAcknowledgements.isNotEmpty() &&
                !recovered
            ) {
                try {
                    pullAllPages(
                        current,
                        pullTransport = pullTransport,
                        mediaEditGuard = mediaEditGuard,
                    )
                } catch (error: SyncHttpException) {
                    val checkpoint = error.fullResyncCheckpointOrNull() ?: throw error
                    current = recoverFullResync(
                        current,
                        checkpoint,
                        pullTransport,
                        mediaEditGuard,
                        transitionReceipt,
                    )
                    recovered = true
                }
            }
        }
        // A completed quiet cycle also completes file-side technical cleanup.
        // Tombstone metadata remains as family deletion evidence; only unowned
        // bytes and their retry marker are reclaimed here.
        mediaFileCleanup.cleanupPendingTombstones()
        transitionReceipt?.let { resetReceiptJournal?.complete(it) }
        return ReplicaSyncOutcome.Synchronized
    }

    private fun AuthenticatedSyncHandshake.requireCompatible(session: SyncSession) {
        if (!ready) throw SyncHandshakeRejectedException("not_ready")
        if (
            protocolVersion != AUTHENTICATED_SYNC_PROTOCOL_VERSION ||
            capabilities != REQUIRED_CAUSAL_WIRE_CAPABILITIES
        ) {
            throw SyncHandshakeRejectedException("capability_mismatch")
        }
        if (
            principal.membershipId != session.membershipId ||
            principal.deviceId != session.deviceId ||
            principal.role != session.role
        ) {
            throw SyncHandshakeRejectedException("unauthenticated")
        }
        if (directoryGeneration.isBlank()) {
            throw SyncHandshakeRejectedException("capability_mismatch")
        }
    }

    override suspend fun applyInitialEntities(
        session: SyncSession,
        entities: List<SyncEntity>,
        resetReceipt: FamilySessionReplica.ResetReceipt?,
    ) {
        session.requireCurrentReplicaSession()
        mediaFileCleanup.cleanupPendingTombstones()
        if (session.role == FamilyRole.Member) {
            // A join snapshot starts a new authority set. Never let a Baby marker
            // retained from a previous family/session masquerade as current authority.
            babyDao.clearFamilyAuthority()
        }
        applyRemote(
            session,
            entities,
            forceAuthority = resetReceipt != null,
            resetReceipt = resetReceipt,
        )
        if (session.role == FamilyRole.Member) {
            familyBabyAppliedListener.onFamilyBabyAuthorityApplied()
        }
    }

    override suspend fun completeLocalSyncReset(receipt: FamilySessionReplica.ResetReceipt) {
        requireNotNull(resetReceiptJournal) {
            "replica reset completion requires durable Room receipt storage"
        }.complete(receipt)
    }

    private suspend fun pushPending(
        session: SyncSession,
        candidates: List<PublishCandidate>,
    ) = publisher.pushPending(session, candidates)

    /**
     * Resolve dependency-ordered authority in one bounded foreground cycle.
     * A batch may legitimately publish a missing Baby/CustomItem while a
     * dependent Record/Plan returns retry_authority. Publishing that proven
     * prerequisite and freezing Room again is progress, not a partial clear.
     */
    private suspend fun settleAndPublish(
        session: SyncSession,
        initialCandidates: List<PublishCandidate>,
    ) {
        var candidates = initialCandidates
        for (pass in 0 until MAX_AUTHORITY_SETTLEMENT_PASSES) {
            // Causal roots (baby/record/care_plan/custom_item/wake + media manifests)
            // settle via the per-root causal path with exact mutation CAS — not LWW updatedAt.
            val causalSlice = candidates.filter {
                it.entityType in CAUSAL_ROOT_TYPES || it.entityType == "media"
            }
            if (causalSlice.isNotEmpty()) {
                check(backend.supportsCausalWire()) {
                    "家庭服务器缺少因果同步协议，已保留本机待同步内容"
                }
                causalSettlement.settle(session, causalSlice)
                // Concurrent user edits keep dirty Room state for the next cycle.
                // Co-batched create-create (e.g. Sleep then Wake under LocalWrite) can
                // leave residual dirty causal roots after a recoverable reject such as
                // missing_sleep_reference once the prior unit is stable — residual-replan
                // only when the dirty causal set shrank (progress), same pass budget as
                // commit-first replan. Fulfillment facts use their immutable atomic bundle below.
                val remaining = captureLocalChanges(session).candidates
                val remainingCausal = remaining.filter {
                    it.entityType in CAUSAL_ROOT_TYPES || it.entityType == "media"
                }
                val remainingLegacy = remaining.filter {
                    it.entityType == "fulfillment_candidate"
                }
                val priorCausalKeys = causalSlice
                    .map { it.entityType to it.clientUuid }
                    .toSet()
                val remainingCausalKeys = remainingCausal
                    .map { it.entityType to it.clientUuid }
                    .toSet()
                if (remainingCausal.isNotEmpty() && remainingCausalKeys != priorCausalKeys) {
                    candidates = remainingCausal + remainingLegacy
                    continue
                }
                if (remainingLegacy.isEmpty()) {
                    return
                }
                candidates = remainingLegacy
            }
            val fulfillmentFacts = candidates.filter {
                it.entityType == "fulfillment_candidate"
            }
            if (fulfillmentFacts.isEmpty()) return
            pushPending(session, fulfillmentFacts)
            return
        }
        error("家庭同步依赖在 $MAX_AUTHORITY_SETTLEMENT_PASSES 轮内未收敛，请稍后重试")
    }

    private suspend fun applyRemote(
        session: SyncSession,
        entities: List<SyncEntity>,
        mediaEditGuard: LocalMediaEditGuard? = null,
        forceAuthority: Boolean = false,
        resetReceipt: FamilySessionReplica.ResetReceipt? = null,
    ) {
        val unsupportedTypes = entities
            .map(SyncEntity::type)
            .filter { it !in CURRENT_ENTITY_TYPES }
            .distinct()
        require(unsupportedTypes.isEmpty()) {
            "家庭服务器返回了非 current 实体类型: ${unsupportedTypes.joinToString()}"
        }
        val deletedMediaClientUuids = mutableListOf<String>()
        val discardedLocalMediaPaths = mutableListOf<String>()
        // Atomic receive: download all log media bytes for new/updated packages into
        // a staging map BEFORE any Room apply, so partial failure never exposes a
        // record/plan with placeholder media or advances past an incomplete package.
        val stagedLogMediaBytes = stageLogMediaDownloads(session, entities)
        val appliedCarePlanUuids = mutableListOf<String>()
        try {
            transactionRunner.run {
                val activeResetReceipt = resetReceipt
                val receiptRoots = activeResetReceipt?.roots
                    ?.associateBy { it.entityType to it.clientUuid }
                    .orEmpty()
                val preservePendingKeys = entities.mapNotNullTo(mutableSetOf()) { entity ->
                    val root = receiptRoots[entity.type to entity.clientUuid]
                        ?: return@mapNotNullTo null
                    (entity.type to entity.clientUuid).takeIf {
                        causalSettlement.shouldPreserveRecoveryIntent(
                            resetRoot = root,
                            crossingFamilyBoundary =
                                activeResetReceipt?.crossingFamilyBoundary == true,
                        )
                    }
                }
                entities.filter { it.type to it.clientUuid in preservePendingKeys }
                    .forEach { entity ->
                        causalSettlement.rebaseRecoveryPendingIntent(
                            entityType = entity.type,
                            clientUuid = entity.clientUuid,
                            remoteVersionId = entity.versionId,
                        )
                    }
                fun force(entity: SyncEntity): Boolean =
                    forceAuthority && entity.type to entity.clientUuid !in preservePendingKeys
                requireCustomItemCapacityAfterApply(
                    existing = customItemDao.listAllIncludingDeleted(),
                    incoming = entities,
                )
                val unresolved = mutableListOf<SyncEntity>()
                for (entity in entities.filter { it.type == "baby" }) {
                    if (!applyBaby(session, entity, forceAuthority = force(entity))) {
                        unresolved += entity
                    }
                }
                for (entity in entities.filter { it.type == "custom_item" }) {
                    if (!applyCustomItem(session, entity, forceAuthority = force(entity))) {
                        unresolved += entity
                    }
                }
                // Fulfillment full-set: record(+photos) before completed care_plan before
                // fulfillment_candidate. Incomplete sets leave cursor unmoved (unresolved).
                for (entity in entities.filter { it.type == "record" }) {
                    if (!applyRecord(entity, forceAuthority = force(entity))) {
                        unresolved += entity
                    }
                }
                for (entity in entities.filter { it.type == "wake_observation" }) {
                    if (!applyWakeObservation(entity, forceAuthority = force(entity))) {
                        unresolved += entity
                    }
                }
                for (entity in entities.filter { it.type == "care_plan" }) {
                    val applied = applyCarePlan(
                        session,
                        entity,
                        discardedLocalMediaPaths,
                        forceAuthority = force(entity),
                    )
                    if (!applied) {
                        unresolved += entity
                    } else {
                        appliedCarePlanUuids += entity.clientUuid
                    }
                }
                for (entity in entities.filter { it.type == "media" }) {
                    if (!applyMedia(
                            session,
                            entity,
                            deletedMediaClientUuids,
                            stagedLogMediaBytes,
                            mediaEditGuard,
                            forceAuthority = force(entity),
                        )
                    ) {
                        unresolved += entity
                    }
                }
                for (entity in entities.filter { it.type == "fulfillment_candidate" }) {
                    if (!applyFulfillmentCandidate(
                            entity,
                            forceAuthority = force(entity),
                        )
                    ) {
                        unresolved += entity
                    }
                }
                require(unresolved.isEmpty()) {
                    "同步数据引用尚未就绪，保留 cursor 以便重试"
                }
                // Root projection and conflict receipt converge in this same page
                // transaction. An explicit no-conflict entity removes every stale
                // summary/snapshot for that root instead of leaving a ghost badge.
                entities.filter { it.type in CAUSAL_ROOT_TYPES }.forEach { entity ->
                    causalSettlement.applyPullConflictSummary(
                        entityType = entity.type,
                        clientUuid = entity.clientUuid,
                        summary = entity.conflictSummary,
                        updatedAt = entity.updatedAt,
                    )
                }
                // Full page applied: re-link each affected plan to the deterministic
                // authority (independent of care_plan LWW / push arrival order).
                val planUuidsForResolve = buildSet {
                    entities.filter { it.type == "fulfillment_candidate" }.forEach { entity ->
                        runCatching {
                            Json.parseToJsonElement(entity.payloadJson).jsonObject
                                .string("care_plan_client_uuid")
                        }.getOrNull()?.let { add(it) }
                    }
                    entities.filter { it.type == "care_plan" }.forEach { add(it.clientUuid) }
                }
                for (planUuid in planUuidsForResolve) {
                    fulfillmentAuthoritySettlement.settle(planUuid)
                }
                entities.filter { it.type == "care_plan" }
                    .mapNotNull { entity -> carePlanDao.getByClientUuid(entity.clientUuid)?.babyId }
                    .distinct()
                    .forEach { babyId ->
                        appliedCarePlanUuids += healDuplicateOpenNextFeedPlans(session, babyId)
                    }
                (
                    entities.filter { it.type == "baby" }
                        .mapNotNull { entity -> babyDao.getByClientUuid(entity.clientUuid)?.id } +
                        entities.filter { it.type == "media" }
                            .mapNotNull { entity ->
                                mediaDao.getByClientUuid(entity.clientUuid)?.babyId
                            }
                    )
                    .distinct()
                    .forEach {
                        refreshBabyAvatar(it, mediaEditGuard)
                    }
            }
        } finally {
            cleanupUnownedStagedMedia(stagedLogMediaBytes.values.toSet())
        }
        mediaFileCleanup.cleanupTombstones(deletedMediaClientUuids.toSet())
        cleanupDiscardedLocalMedia(discardedLocalMediaPaths)
        // Side effects only after full package apply — never during partial download.
        if (appliedCarePlanUuids.isNotEmpty()) {
            carePlanAppliedListener.onFamilyCarePlansApplied(appliedCarePlanUuids.distinct())
        }
        check(session.isJoined)
    }

    private suspend fun cleanupUnownedStagedMedia(paths: Set<String>) {
        mediaFileCleanup.cleanupUnreferencedPaths(paths)
    }

    private suspend fun cleanupDiscardedLocalMedia(paths: List<String>) {
        mediaFileCleanup.cleanupUnreferencedPaths(paths.toSet())
    }

    /**
     * Apply a remote care plan. Custom-item plans wait until the definition is
     * local (return false → page retries, plan stays invisible). Concurrent local
     * dirty revisions are not clobbered.
     *
     * Completed plans that link a fulfilled record require that record to already
     * be local (same-page records are applied first) so receivers never see
     * completed-without-Record partial state.
     */
    private suspend fun applyCarePlan(
        session: SyncSession,
        entity: SyncEntity,
        discardedLocalMediaPaths: MutableList<String>,
        forceAuthority: Boolean = false,
    ): Boolean {
        val existing = carePlanDao.getByClientUuid(entity.clientUuid)
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = decodeCarePlanWire(
            payload,
            requireCanonicalIds = backend.supportsCausalWire(),
        )
        val references = resolveCarePlanReferences(
            wire = wire,
            babyDao = babyDao,
            customItemDao = customItemDao,
            recordDao = recordDao,
        ) ?: return false
        val baby = references.baby
        val customItemId = references.customItem?.id
        if (!forceAuthority &&
            existing != null &&
            !causalSettlement.shouldApplyStablePull(
                entityType = "care_plan",
                clientUuid = entity.clientUuid,
                remoteVersionId = entity.versionId,
                forceAuthority = false,
            )
        ) {
            return true
        }
        val concurrentNextFeedCreate = !forceAuthority && existing != null &&
            (
                existing.syncDirty ||
                    session.isCreatorAcknowledgementPending("care_plan", entity.clientUuid)
            ) &&
            isNextFeedPlanNote(existing.note) &&
            isNextFeedPlanNote(wire.note) &&
            existing.createdByMembershipId.isNotBlank() &&
            existing.createdByMembershipId != wire.createdByMembershipId
        val causalVersionAdvance = !forceAuthority &&
            backend.supportsCausalWire() &&
            existing != null &&
            entity.versionId != null &&
            entity.versionId != existing.baseVersion
        val causalSameVersion = !forceAuthority &&
            backend.supportsCausalWire() &&
            existing != null &&
            entity.versionId != null &&
            entity.versionId == existing.baseVersion
        if (causalSameVersion && !concurrentNextFeedCreate) {
            acknowledgedEqualRevisionCreator(
                session = session,
                existingCreator = existing.createdByMembershipId,
                payloadJson = entity.payloadJson,
            )?.let { creator ->
                carePlanDao.update(existing.copy(createdByMembershipId = creator))
            }
            carePlanDao.acknowledgeFamilyPublishedVersion(entity.clientUuid, entity.updatedAt)
            return true
        }
        // A deterministic next-feed UUID lets the NAS choose one creator when two
        // members schedule offline. The losing local create must accept that winner;
        // Standard dirty CarePlan edits keep the creator ACL/LWW behavior.
        if (!concurrentNextFeedCreate && !forceAuthority && !causalVersionAdvance) {
            // Match server LWW for business fields. Equal revisions may still carry
            // the NAS-owned immutable creator acknowledgement after a push.
            if (existing != null && existing.updatedAt > entity.updatedAt) {
                carePlanDao.acknowledgeFamilyPublishedVersion(
                    entity.clientUuid,
                    entity.updatedAt,
                )
                return true
            }
            if (existing != null && existing.updatedAt == entity.updatedAt) {
                acknowledgedEqualRevisionCreator(
                    session = session,
                    existingCreator = existing.createdByMembershipId,
                    payloadJson = entity.payloadJson,
                )?.let { creator ->
                    carePlanDao.update(existing.copy(createdByMembershipId = creator))
                }
                carePlanDao.acknowledgeFamilyPublishedVersion(
                    entity.clientUuid,
                    entity.updatedAt,
                )
                return true
            }
        }
        val remotePayloadJson = SyncWireMapper.localPayloadFromWire(
            wire.type,
            wire.payload,
            customItemId,
            allowIntentOnlyFeed = isNextFeedPlanNote(wire.note),
        )
        val calendarDisposition = carePlanCalendarDisposition(
            existing = existing,
            babyId = baby.id,
            type = wire.type.key,
            customItemId = customItemId,
            scheduledAt = wire.scheduledAt,
            scheduledZoneId = wire.scheduledZoneId,
            note = wire.note,
            payloadJson = remotePayloadJson,
            schemaVersion = wire.schemaVersion,
            status = wire.status,
            deleted = entity.deletedAt != null,
        )
        if (concurrentNextFeedCreate) {
            val losingMedia = mediaDao.listForCarePlan(existing!!.id)
            val losingMediaUuids = losingMedia.map(MediaAssetEntity::clientUuid)
            discardedLocalMediaPaths += losingMedia.map(MediaAssetEntity::localUri)
            if (losingMediaUuids.isNotEmpty()) {
                mediaDao.deleteByClientUuids(losingMediaUuids)
            }
        }
        carePlanDao.upsert(
            CarePlanEntity(
                id = existing?.id ?: 0,
                clientUuid = entity.clientUuid,
                babyId = baby.id,
                type = wire.type.key,
                customItemId = customItemId,
                scheduledAt = wire.scheduledAt,
                scheduledZoneId = wire.scheduledZoneId,
                note = wire.note,
                payloadJson = remotePayloadJson,
                schemaVersion = wire.schemaVersion,
                status = wire.status,
                createdByMembershipId = if (concurrentNextFeedCreate) {
                    wire.createdByMembershipId
                } else {
                    resolvedImmutableCreator(
                        session = session,
                        existingCreator = existing?.createdByMembershipId,
                        remoteCreator = wire.createdByMembershipId,
                    )
                },
                fulfilledRecordClientUuid = wire.fulfilledRecordClientUuid,
                fulfilledAt = wire.fulfilledAt,
                sourceRecordClientUuid = existing?.sourceRecordClientUuid,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncDirty = false,
                familyPublishedUpdatedAt = entity.updatedAt,
                baseVersion = entity.versionId ?: existing?.baseVersion,
                mutationId = if (forceAuthority) null else existing?.mutationId,
                openConflictId = entity.conflictSummary?.conflictId,
                localBranchVersionId = if (entity.conflictSummary == null) {
                    null
                } else {
                    existing?.localBranchVersionId
                },
                systemCalendarProjectionEnabled =
                    existing?.systemCalendarProjectionEnabled ?: true,
                systemCalendarEventId = existing?.systemCalendarEventId,
                systemCalendarReminderReady = calendarDisposition.reminderReady,
                systemCalendarProjectionPending = calendarDisposition.projectionPending,
            ),
        )
        return true
    }

    /**
     * Different devices can complete the old next-feed plan offline and derive
     * different generation UUIDs for the replacement. Resolve that family intent
     * during pull rather than waiting for another local schedule action.
     *
     * The oldest published revision wins, with client UUID as the cross-replica
     * tie breaker. A device publishes tombstones only for rows its principal can
     * manage. Foreign losers become a clean local terminal projection: this
     * immediately removes duplicate UI/reminders without forging a server write;
     * the loser creator (or owner) publishes the durable tombstone when online.
     */
    private suspend fun healDuplicateOpenNextFeedPlans(
        session: SyncSession,
        babyId: Long,
    ): List<String> {
        val open = carePlanDao.listAllIncludingDeleted()
            .filter { plan ->
                plan.babyId == babyId &&
                    plan.deletedAt == null &&
                    plan.status in setOf(
                        CarePlanStatus.PENDING.storageKey,
                        CarePlanStatus.MISSED.storageKey,
                    ) &&
                    isNextFeedPlanNote(plan.note)
            }
            .sortedWith(
                compareBy<CarePlanEntity> { it.updatedAt }
                    .thenBy { it.clientUuid },
            )
        if (open.size <= 1) return emptyList()

        val revisionFloor = maxOf(
            clock.nowMillis(),
            open.maxOf(CarePlanEntity::updatedAt),
        )
        val tombstoneAt = if (revisionFloor == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            revisionFloor + 1L
        }
        val actor = session.membershipId.trim()
        val losers = open.drop(1)
        losers.forEach { loser ->
            val canPublishTerminal = session.role == FamilyRole.Owner ||
                (actor.isNotEmpty() && loser.createdByMembershipId.trim() == actor) ||
                (
                    loser.createdByMembershipId.isBlank() &&
                        session.isCreatorAcknowledgementPending("care_plan", loser.clientUuid)
                    )
            if (canPublishTerminal) {
                carePlanDao.softDelete(loser.id, tombstoneAt)
            } else {
                carePlanDao.update(
                    loser.copy(
                        status = CarePlanStatus.SKIPPED.storageKey,
                        syncDirty = false,
                    ),
                )
            }
        }
        return losers.map(CarePlanEntity::clientUuid)
    }

    /**
     * Apply a remote fulfillment candidate. Requires plan + record to already be
     * local so the candidate is never the sole visible half of a fulfill result.
     * Winner selection runs after the full page apply via [FulfillmentAuthoritySettlement].
     */
    private suspend fun applyFulfillmentCandidate(
        entity: SyncEntity,
        forceAuthority: Boolean = false,
    ): Boolean {
        val existing = fulfillmentCandidateDao.getByClientUuid(entity.clientUuid)
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = parseFulfillmentCandidateWire(payload)
        // Full-set: plan and record must already be applied (or present).
        if (carePlanDao.getByClientUuid(wire.carePlanClientUuid) == null) return false
        if (recordDao.getByClientUuid(wire.recordClientUuid) == null) return false
        // Ticket 26 multi-device convergence: originators keep equal/higher updatedAt
        // after markSynced, but must still adopt server-frozen stamps (role/membership/
        // confirmed_at) so every device adjudicates with the same evidence.
        if (!forceAuthority && existing != null && existing.updatedAt >= entity.updatedAt) {
            val sameRevision = existing.updatedAt == entity.updatedAt
            val acknowledgedDeletedAt = if (sameRevision && entity.deletedAt != null) {
                entity.deletedAt
            } else {
                existing.deletedAt
            }
            val needsStampMerge =
                existing.submitterRole != wire.submitterRole ||
                    existing.submitterMembershipId != wire.submitterMembershipId ||
                    existing.confirmedAt != wire.confirmedAt ||
                    existing.deletedAt != acknowledgedDeletedAt ||
                    (sameRevision && existing.syncDirty)
            if (!needsStampMerge) return true
            fulfillmentCandidateDao.update(
                existing.copy(
                    confirmedAt = wire.confirmedAt,
                    submitterMembershipId = wire.submitterMembershipId,
                    submitterRole = wire.submitterRole,
                    updatedAt = maxOf(existing.updatedAt, entity.updatedAt),
                    deletedAt = acknowledgedDeletedAt,
                    syncDirty = if (sameRevision) false else existing.syncDirty,
                ),
            )
            return true
        }
        fulfillmentCandidateDao.upsert(
            FulfillmentCandidateEntity(
                id = existing?.id ?: 0,
                clientUuid = entity.clientUuid,
                carePlanClientUuid = wire.carePlanClientUuid,
                recordClientUuid = wire.recordClientUuid,
                actualTimestamp = wire.actualTimestamp,
                confirmedAt = wire.confirmedAt,
                submitterMembershipId = wire.submitterMembershipId,
                submitterRole = wire.submitterRole,
                // Preserve local adoption until resolve re-marks the full set.
                adoptionStatus = existing?.adoptionStatus.orEmpty(),
                // Device-local convert pointer (ticket 27); never overwrite from wire.
                convertedRecordClientUuid = existing?.convertedRecordClientUuid.orEmpty(),
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncDirty = false,
            ),
        )
        return true
    }

    /**
     * Apply a remote custom item definition with pure updated_at LWW.
     * Preserves local sortOrder (layout). Does not resurrect local layout prefs.
     */
    private suspend fun applyWakeObservation(
        entity: SyncEntity,
        forceAuthority: Boolean = false,
    ): Boolean {
        val existing = wakeObservationDao.getByClientUuid(entity.clientUuid)
        if (!forceAuthority &&
            existing != null &&
            !causalSettlement.shouldApplyStablePull(
                entityType = "wake_observation",
                clientUuid = entity.clientUuid,
                remoteVersionId = entity.versionId,
                forceAuthority = false,
            )
        ) {
            return true
        }
        val causalVersionAdvance = !forceAuthority &&
            backend.supportsCausalWire() &&
            existing != null &&
            entity.versionId != null &&
            entity.versionId != existing.baseVersion
        val causalSameVersion = !forceAuthority &&
            backend.supportsCausalWire() &&
            existing != null &&
            entity.versionId != null &&
            entity.versionId == existing.baseVersion
        if (causalSameVersion) {
            return true
        }
        if (!forceAuthority && !causalVersionAdvance && existing != null &&
            existing.updatedAt > entity.updatedAt
        ) {
            return true
        }
        val wire = decodeWakeRootWire(
            Json.parseToJsonElement(entity.payloadJson).jsonObject,
            WakeRootWireShape.Pull,
        )
        // Records from the same pull page apply first; an existing Wake cannot
        // be retargeted to a different source Sleep by a later stable version.
        if (resolveWakeReference(
                wire = wire,
                recordDao = recordDao,
                expectedSleepClientUuid = existing?.sleepRecordClientUuid,
            ) == null
        ) {
            return false
        }
        wakeObservationDao.upsert(
            com.lezi.babylog.core.database.causal.WakeObservationEntity(
                id = existing?.id ?: 0,
                clientUuid = entity.clientUuid,
                sleepRecordClientUuid = wire.sleepRecordClientUuid,
                wakeTimestamp = wire.wakeTimestamp,
                observerMembershipId = wire.observerMembershipId.orEmpty(),
                note = wire.note,
                withdrawn = wire.withdrawn,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncDirty = false,
                baseVersion = entity.versionId ?: existing?.baseVersion,
                mutationId = if (forceAuthority) null else existing?.mutationId,
                openConflictId = entity.conflictSummary?.conflictId,
                localBranchVersionId = if (entity.conflictSummary == null) {
                    null
                } else {
                    existing?.localBranchVersionId
                },
            ),
        )
        return true
    }

    private suspend fun applyCustomItem(
        session: SyncSession,
        entity: SyncEntity,
        forceAuthority: Boolean = false,
    ): Boolean {
        val existing = customItemDao.getByClientUuid(entity.clientUuid)
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = decodeCustomItemWire(payload)
        if (!forceAuthority &&
            existing != null &&
            !causalSettlement.shouldApplyStablePull(
                entityType = "custom_item",
                clientUuid = entity.clientUuid,
                remoteVersionId = entity.versionId,
                forceAuthority = false,
            )
        ) {
            return true
        }
        val causalVersionAdvance = !forceAuthority &&
            backend.supportsCausalWire() &&
            existing != null &&
            entity.versionId != null &&
            entity.versionId != existing.baseVersion
        val causalSameVersion = !forceAuthority &&
            backend.supportsCausalWire() &&
            existing != null &&
            entity.versionId != null &&
            entity.versionId == existing.baseVersion
        if (causalSameVersion) {
            return true
        }
        // Pre-causal residual LWW only when version_id is absent or unchanged.
        if (!forceAuthority && !causalVersionAdvance && existing != null &&
            existing.updatedAt > entity.updatedAt
        ) {
            return true
        }
        val creator = resolvedImmutableCreator(
            session = session,
            existingCreator = existing?.createdByMembershipId,
            remoteCreator = wire.createdByMembershipId,
        )
        val familyId = existing?.familyId
            ?: familyDao.listAll().firstOrNull()?.id
            ?: return false
        customItemDao.upsert(
            CustomItemEntity(
                id = existing?.id ?: 0,
                clientUuid = entity.clientUuid,
                familyId = familyId,
                name = wire.name,
                iconSlot = wire.iconSlot,
                // Device layout: keep local order; new remote items append.
                sortOrder = existing?.sortOrder
                    ?: customItemDao.listAllIncludingDeleted().size,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                createdByMembershipId = creator,
                syncDirty = false,
                baseVersion = entity.versionId ?: existing?.baseVersion,
                mutationId = if (forceAuthority) null else existing?.mutationId,
                openConflictId = entity.conflictSummary?.conflictId,
                localBranchVersionId = if (entity.conflictSummary == null) {
                    null
                } else {
                    existing?.localBranchVersionId
                },
            ),
        )
        return true
    }

    private fun acknowledgedEqualRevisionCreator(
        session: SyncSession,
        existingCreator: String?,
        payloadJson: String,
    ): String? {
        val remoteCreator = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }
            .getOrNull()
            ?.string("created_by_membership_id")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        return resolvedImmutableCreator(
            session = session,
            existingCreator = existingCreator,
            remoteCreator = remoteCreator,
        ).takeIf { it != existingCreator }
    }

    private fun resolvedImmutableCreator(
        session: SyncSession,
        existingCreator: String?,
        remoteCreator: String?,
    ): String {
        // A present JSON null is parsed as an empty string and is the server's
        // authoritative hard-delete anonymization. It must clear even a prior
        // self author instead of being treated as a missing acknowledgement.
        if (remoteCreator != null && remoteCreator.isBlank()) return ""
        val canonicalSelf = session.membershipId.trim()
        return existingCreator
            ?.takeIf { canonicalSelf.isNotEmpty() && it == canonicalSelf }
            ?: remoteCreator
            ?: existingCreator
            ?: ""
    }

    private suspend fun applyBaby(
        session: SyncSession,
        entity: SyncEntity,
        forceAuthority: Boolean = false,
    ): Boolean {
        val existing = babyDao.getByClientUuid(entity.clientUuid)
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = decodeBabyWire(payload)
        // Members still accept family authority babies (force path / member role).
        if (existing != null && session.role != FamilyRole.Member && !forceAuthority) {
            if (!causalSettlement.shouldApplyStablePull(
                    entityType = "baby",
                    clientUuid = entity.clientUuid,
                    remoteVersionId = entity.versionId,
                    forceAuthority = false,
                )
            ) {
                return true
            }
            val causalVersionAdvance = backend.supportsCausalWire() &&
                entity.versionId != null &&
                entity.versionId != existing.baseVersion
            val causalSameVersion = backend.supportsCausalWire() &&
                entity.versionId != null &&
                entity.versionId == existing.baseVersion
            if (causalSameVersion) {
                return true
            }
            if (!causalVersionAdvance) {
                if (existing.updatedAt > entity.updatedAt) return true
                val exactRevision = existing.updatedAt == entity.updatedAt &&
                    existing.nickname == wire.nickname &&
                    existing.sex == wire.sex &&
                    existing.birthdayEpochDay == wire.birthdayEpochDay &&
                    existing.birthWeightGrams == wire.birthWeightGrams &&
                    existing.avatarMediaUuid == wire.avatarMediaUuid &&
                    existing.deletedAt == entity.deletedAt
                if (exactRevision) {
                    if (existing.syncDirty) {
                        babyDao.markSynced(entity.clientUuid, entity.updatedAt)
                    }
                    if (entity.versionId != null && existing.baseVersion != entity.versionId) {
                        babyDao.update(
                            existing.copy(baseVersion = entity.versionId, syncDirty = false),
                        )
                    }
                    return true
                }
                if (existing.updatedAt == entity.updatedAt) {
                    return !existing.syncDirty
                }
                if (existing.syncDirty) return false
            }
        }
        val familyId = existing?.familyId ?: familyDao.listAll().firstOrNull()?.id ?: return false
        babyDao.upsert(
            BabyEntity(
                id = existing?.id ?: 0,
                familyId = familyId,
                nickname = wire.nickname,
                sex = wire.sex,
                birthdayEpochDay = wire.birthdayEpochDay,
                birthWeightGrams = wire.birthWeightGrams,
                themeColorArgb = existing?.themeColorArgb ?: 0xFFE6A67A.toInt(),
                sortOrder = existing?.sortOrder ?: 0,
                clientUuid = entity.clientUuid,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncDirty = false,
                avatarMediaUuid = wire.avatarMediaUuid,
                avatarPath = existing?.avatarPath,
                familyAuthority = session.role == FamilyRole.Member ||
                    existing?.familyAuthority == true,
                baseVersion = entity.versionId ?: existing?.baseVersion,
                mutationId = if (forceAuthority) null else existing?.mutationId,
                openConflictId = entity.conflictSummary?.conflictId,
                localBranchVersionId = if (entity.conflictSummary == null) {
                    null
                } else {
                    existing?.localBranchVersionId
                },
            ),
        )
        return true
    }

    private suspend fun applyRecord(
        entity: SyncEntity,
        forceAuthority: Boolean = false,
    ): Boolean {
        val existing = recordDao.getByClientUuid(entity.clientUuid)
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = parseRecordWire(payload)
        applySourceRelationSummary(entity)
        val customItemId = wire.customItemClientUuid?.let { customItemUuid ->
            customItemDao.getByClientUuid(customItemUuid)?.id ?: return false
        }
        if (!forceAuthority &&
            existing != null &&
            !causalSettlement.shouldApplyStablePull(
                entityType = "record",
                clientUuid = entity.clientUuid,
                remoteVersionId = entity.versionId,
                forceAuthority = false,
            )
        ) {
            return true
        }
        // Causal stable projection is version_id-addressed. When version_id advanced,
        // apply content even if updated_at is equal/lower (server max() may equalize stamps).
        // Residual updatedAt LWW remains only for pre-causal rows (no version_id).
        val causalVersionAdvance = !forceAuthority &&
            backend.supportsCausalWire() &&
            existing != null &&
            entity.versionId != null &&
            entity.versionId != existing.baseVersion
        val causalSameVersion = !forceAuthority &&
            backend.supportsCausalWire() &&
            existing != null &&
            entity.versionId != null &&
            entity.versionId == existing.baseVersion
        if (causalSameVersion) {
            recordDao.mergeCanonicalAuthor(
                clientUuid = entity.clientUuid,
                expectedUpdatedAt = existing.updatedAt,
                membershipId = wire.createdByMembershipId,
            )
            recordDao.acknowledgeFamilyPublishedVersion(entity.clientUuid, entity.updatedAt)
            return true
        }
        if (!causalVersionAdvance) {
            // Match server LWW for business fields. Equal revisions may still carry
            // a server-owned author metadata acknowledgement from the current server.
            if (!forceAuthority && existing != null && existing.updatedAt > entity.updatedAt) {
                recordDao.acknowledgeFamilyPublishedVersion(entity.clientUuid, entity.updatedAt)
                return true
            }
            if (!forceAuthority && existing != null && existing.updatedAt == entity.updatedAt) {
                recordDao.mergeCanonicalAuthor(
                    clientUuid = entity.clientUuid,
                    expectedUpdatedAt = entity.updatedAt,
                    membershipId = wire.createdByMembershipId,
                )
                recordDao.acknowledgeFamilyPublishedVersion(entity.clientUuid, entity.updatedAt)
                if (entity.versionId != null && existing.baseVersion != entity.versionId) {
                    recordDao.update(existing.copy(baseVersion = entity.versionId, syncDirty = false))
                }
                return true
            }
        }
        val baby = babyDao.getByClientUuid(wire.babyClientUuid) ?: return false
        recordDao.upsert(
            RecordEntity(
                id = existing?.id ?: 0,
                clientUuid = entity.clientUuid,
                babyId = baby.id,
                type = wire.type.key,
                timestamp = wire.timestamp,
                endTimestamp = wire.endTimestamp,
                note = wire.note,
                createdByMembershipId = wire.createdByMembershipId,
                payloadJson = SyncWireMapper.localPayloadFromWire(
                    wire.type,
                    wire.payload,
                    customItemId,
                ),
                schemaVersion = wire.schemaVersion,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncDirty = false,
                familyPublishedUpdatedAt = entity.updatedAt,
                baseVersion = entity.versionId ?: existing?.baseVersion,
                mutationId = if (forceAuthority) null else existing?.mutationId,
                openConflictId = entity.conflictSummary?.conflictId,
                localBranchVersionId = if (entity.conflictSummary == null) {
                    null
                } else {
                    existing?.localBranchVersionId
                },
                effectiveWakeObservationClientUuid =
                    if (wire.effectiveWakeObservationPresent) {
                        wire.effectiveWakeObservationClientUuid
                    } else {
                        existing?.effectiveWakeObservationClientUuid
                    },
            ),
        )
        return true
    }

    /**
     * Persist pull `source_relation_summary` without touching Record.deletedAt.
     * Never invents owner_group_resolve provenance; interim pull rows use
     * [SourceRelationReason.PULL_SUMMARY]. Display is set only when a display-role
     * summary arrives (or an existing relation already named one).
     */
    private suspend fun applySourceRelationSummary(entity: SyncEntity) {
        val summary = entity.sourceRelationSummary ?: return
        val dao = sourceRelationDao ?: return
        dao.applyPullSummary(
            relationId = summary.relationId,
            recordClientUuid = entity.clientUuid,
            role = summary.role,
            peerIds = summary.peerIds,
            observedAt = entity.updatedAt,
        )
    }

    /**
     * Download every non-deleted log media blob referenced in this pull page into
     * durable local files first. Any failure aborts the page so cursor stays put
     * and no placeholder media rows are written.
     */
    private suspend fun stageLogMediaDownloads(
        session: SyncSession,
        entities: List<SyncEntity>,
    ): Map<String, String> {
        val staged = linkedMapOf<String, String>()
        val logMedia = entities
            .filter { it.type == "media" }
            .map { entity ->
                entity to parseMediaWire(Json.parseToJsonElement(entity.payloadJson).jsonObject)
            }
            .filter { (entity, wire) -> entity.deletedAt == null && wire.kind == "log" }
        for ((entity, wire) in logMedia) {
            requireCanonicalUuid(entity.clientUuid, "media client_uuid")
            val existing = mediaDao.getByClientUuid(entity.clientUuid)
            if (existing != null && existing.updatedAt >= entity.updatedAt &&
                existing.localUri.isNotBlank()
            ) {
                staged[entity.clientUuid] = existing.localUri
                continue
            }
            requireRemoteAllowed(session)
            val bytes = backend.getMedia(session, entity.clientUuid)
            val localUri = mediaFiles.saveDownloaded(
                entity.clientUuid,
                "log",
                bytes,
                wire.mime,
            )
            staged[entity.clientUuid] = localUri
        }
        return staged
    }

    private suspend fun applyMedia(
        session: SyncSession,
        entity: SyncEntity,
        deletedMediaClientUuids: MutableList<String>,
        stagedLogMediaBytes: Map<String, String> = emptyMap(),
        mediaEditGuard: LocalMediaEditGuard? = null,
        forceAuthority: Boolean = false,
    ): Boolean {
        requireCanonicalUuid(entity.clientUuid, "media client_uuid")
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = parseMediaWire(payload)
        val existing = mediaDao.getByClientUuid(entity.clientUuid)
        if (!forceAuthority && existing != null && mediaEditGuard?.canReplace(existing) == false) {
            return false
        }
        // A newer local version still wins LWW. An equal remote version is the
        // authoritative receipt for the exact local bytes/metadata, including
        // after full-resync deliberately invalidated only sync bookkeeping.
        if (!forceAuthority && existing != null && existing.updatedAt > entity.updatedAt) return true
        if (!forceAuthority && existing != null && existing.updatedAt == entity.updatedAt) {
            val acknowledged = existing.copy(
                remoteUri = session.receiptFor(entity.clientUuid),
                deletedAt = entity.deletedAt ?: existing.deletedAt,
                syncDirty = false,
            )
            mediaDao.update(acknowledged)
            mediaEditGuard?.mediaRefreshed(acknowledged)
            if (entity.deletedAt != null && existing.localUri.isNotBlank()) {
                deletedMediaClientUuids += entity.clientUuid
            }
            return true
        }
        val recordId = wire.recordClientUuid?.let {
            recordDao.getByClientUuid(it)?.id ?: return false
        }
        val carePlanId = wire.carePlanClientUuid?.let {
            carePlanDao.getByClientUuid(it)?.id ?: return false
        }
        val babyId = wire.babyClientUuid?.let {
            babyDao.getByClientUuid(it)?.id ?: return false
        }
        val stagedLocal = stagedLogMediaBytes[entity.clientUuid]
        // Log media without staged local bytes would be a placeholder — refuse.
        if (wire.kind == "log" && entity.deletedAt == null && stagedLocal.isNullOrBlank() &&
            existing?.localUri.isNullOrBlank()
        ) {
            return false
        }
        val applied = MediaAssetEntity(
                id = existing?.id ?: 0,
                recordId = recordId,
                carePlanId = carePlanId,
                clientUuid = entity.clientUuid,
                kind = wire.kind,
                babyId = babyId,
                localUri = stagedLocal ?: existing?.localUri.orEmpty(),
                remoteUri = session.receiptFor(entity.clientUuid),
                mime = wire.mime,
                width = wire.width,
                height = wire.height,
                byteSize = wire.byteSize,
                createdAt = existing?.createdAt ?: entity.updatedAt,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncDirty = false,
        )
        val appliedId = mediaDao.upsert(applied)
        mediaEditGuard?.mediaRefreshed(applied.copy(id = existing?.id ?: appliedId))
        if (entity.deletedAt != null && !existing?.localUri.isNullOrBlank()) {
            // Keep the exact path on the tombstoned row until physical cleanup
            // succeeds. The row is the durable hand-off across process death;
            // clearing it before deletion would make an equal LWW retry skip the
            // only remaining file identity.
            deletedMediaClientUuids += entity.clientUuid
        }
        return true
    }

    override suspend fun convergeAuthenticatedSelfMembership(
        session: SyncSession,
        members: List<FamilyMember>,
    ): SyncSession {
        val sessionMembershipId = session.membershipId.trim()
        require(sessionMembershipId.isNotEmpty()) {
            "当前家庭会话缺少 membership_id"
        }
        val authenticatedMembershipId = requireNotNull(
            members.singleOrNull { it.isSelf }
                ?.membershipId
                ?.trim()
                ?.takeIf(String::isNotEmpty),
        ) {
            "当前成员响应缺少唯一的 self membership_id"
        }
        require(authenticatedMembershipId == sessionMembershipId) {
            "当前家庭会话 membership_id 与服务端身份不一致"
        }
        return session
    }

    override suspend fun resetLocalSyncReceipts(
        previous: SyncSession,
        invalidateCurrentReceipts: Boolean,
        crossingFamilyBoundary: Boolean,
        recoveryTarget: FamilySessionReplica.RecoveryTarget?,
    ): FamilySessionReplica.ResetReceipt {
        val journal = requireNotNull(resetReceiptJournal) {
            "replica reset requires durable Room receipt storage"
        }
        return transactionRunner.run {
            val existing = journal.load()
            if (existing != null && existing.matchesResetRequest(
                    previous = previous,
                    crossingFamilyBoundary = crossingFamilyBoundary,
                    recoveryTarget = recoveryTarget,
                )
            ) {
                return@run existing
            }
            val roots = buildList {
                babyDao.listAllIncludingDeleted().forEach {
                    add(FamilySessionReplica.ResetRoot("baby", it.clientUuid, it.updatedAt, it.syncDirty))
                }
                recordDao.listAllIncludingDeleted().forEach {
                    add(FamilySessionReplica.ResetRoot("record", it.clientUuid, it.updatedAt, it.syncDirty))
                }
                carePlanDao.listAllIncludingDeleted().forEach {
                    add(FamilySessionReplica.ResetRoot("care_plan", it.clientUuid, it.updatedAt, it.syncDirty))
                }
                customItemDao.listAllIncludingDeleted().forEach {
                    add(FamilySessionReplica.ResetRoot("custom_item", it.clientUuid, it.updatedAt, it.syncDirty))
                }
                wakeObservationDao.listPendingSync().forEach {
                    add(FamilySessionReplica.ResetRoot("wake_observation", it.clientUuid, it.updatedAt, it.syncDirty))
                }
            }
            babyDao.markAllPendingSync()
            if (crossingFamilyBoundary || previous.role == FamilyRole.Member) {
                babyDao.clearFamilyAuthority()
            }
            if (crossingFamilyBoundary || invalidateCurrentReceipts) {
                recordDao.listAllIncludingDeleted().forEach { record ->
                    recordDao.update(
                        record.copy(
                            familyPublishedUpdatedAt = null,
                            syncDirty = true,
                        ),
                    )
                }
            } else {
                recordDao.markAllPendingSync()
            }
            if (crossingFamilyBoundary) {
                carePlanDao.listAllIncludingDeleted().forEach { plan ->
                    carePlanDao.update(
                        plan.copy(
                            createdByMembershipId = "",
                            familyPublishedUpdatedAt = null,
                            syncDirty = true,
                        ),
                    )
                }
                customItemDao.listAllIncludingDeleted().forEach { item ->
                    customItemDao.update(
                        item.copy(
                            createdByMembershipId = "",
                            syncDirty = true,
                        ),
                    )
                }
                fulfillmentCandidateDao.listAllIncludingDeleted().forEach { candidate ->
                    fulfillmentCandidateDao.update(
                        candidate.copy(
                            submitterMembershipId = "",
                            submitterRole = "",
                            syncDirty = true,
                        ),
                    )
                }
            } else if (invalidateCurrentReceipts) {
                carePlanDao.listAllIncludingDeleted().forEach { plan ->
                    carePlanDao.update(
                        plan.copy(
                            familyPublishedUpdatedAt = null,
                            syncDirty = true,
                        ),
                    )
                }
                customItemDao.markAllPendingSync()
                fulfillmentCandidateDao.markAllPendingSync()
            } else {
                carePlanDao.markAllPendingSync()
                customItemDao.markAllPendingSync()
                fulfillmentCandidateDao.markAllPendingSync()
            }
            mediaDao.listAllIncludingDeleted().forEach { media ->
                val hasCurrentReceipt = previous.familyId.isNotBlank() &&
                    previous.baseUrl.isNotBlank() &&
                    media.hasReceiptFor(previous)
                val preserveCurrentReceipt = !crossingFamilyBoundary &&
                    (!invalidateCurrentReceipts ||
                        (previous.role == FamilyRole.Member && media.kind == "avatar"))
                val nextReceipt = when {
                    !hasCurrentReceipt -> null
                    preserveCurrentReceipt -> media.remoteUri
                    else -> null
                }
                mediaDao.update(
                    media.copy(
                        remoteUri = nextReceipt,
                        syncDirty = true,
                    ),
                )
            }
            val receipt = FamilySessionReplica.ResetReceipt(
                previousFamilyId = previous.familyId,
                previousMembershipId = previous.membershipId,
                previousDeviceId = previous.deviceId,
                crossingFamilyBoundary = crossingFamilyBoundary,
                recoveryTarget = recoveryTarget,
                roots = roots,
            )
            journal.replace(receipt)
            receipt
        }
    }

    private suspend fun recoverFullResync(
        previous: SyncSession,
        checkpoint: FullResyncCheckpoint,
        pullTransport: PullTransportContract,
        mediaEditGuard: LocalMediaEditGuard,
        transitionReceipt: FamilySessionReplica.ResetReceipt? = null,
    ): SyncSession {
        val durableReceipt = transitionReceipt
            ?: resetReceiptJournal?.load()?.takeIf { it.belongsTo(previous) }
        val resetReceipt = durableReceipt ?: resetLocalSyncReceipts(
            previous = previous,
            invalidateCurrentReceipts = true,
        ).also {
            require(!it.crossingFamilyBoundary && it.belongsTo(previous)) {
                "full-resync receipt does not belong to the recovering replica"
            }
        }
        preferences.updateCursor(checkpoint.resetCursor, generation = checkpoint.serverGeneration)
        var current = preferences.session.first()
        current = pullAllPages(
            initial = current,
            reconcileMemberAvatars = current.role == FamilyRole.Member,
            deferCursorUntilComplete = true,
            forceAuthority = true,
            resetReceipt = resetReceipt,
            pullTransport = pullTransport,
            mediaEditGuard = mediaEditGuard,
        )
        if (current.role == FamilyRole.Member) {
            settleMemberLocalOnlySubtrees()
        }
        val captured = captureLocalChanges(current)
        if (captured.pendingCreatorAcknowledgements.isNotEmpty()) {
            preferences.updateCreatorAcknowledgements(
                add = captured.pendingCreatorAcknowledgements,
            )
            current = preferences.session.first()
        }
        settleAndPublish(current, captured.candidates)
        current = preferences.session.first()
        val recovered = pullAllPages(
            initial = current,
            pullTransport = pullTransport,
            mediaEditGuard = mediaEditGuard,
        )
        requireNotNull(resetReceiptJournal).complete(resetReceipt)
        return recovered
    }

    private fun FamilySessionReplica.ResetReceipt.belongsTo(
        session: SyncSession,
    ): Boolean {
        if (!crossingFamilyBoundary) {
            return previousFamilyId == session.familyId &&
                previousMembershipId == session.membershipId &&
                previousDeviceId == session.deviceId
        }
        val target = recoveryTarget ?: return false
        val targetIdentityMatches = if (target.familyId == null) {
            session.familyId.isNotBlank() && session.familyId != previousFamilyId
        } else {
            target.baseUrl == session.baseUrl && target.familyId == session.familyId
        }
        return crossingFamilyBoundary && targetIdentityMatches &&
            (target.membershipId == null || target.membershipId == session.membershipId) &&
            (target.deviceId == null || target.deviceId == session.deviceId)
    }

    private fun FamilySessionReplica.ResetReceipt.matchesResetRequest(
        previous: SyncSession,
        crossingFamilyBoundary: Boolean,
        recoveryTarget: FamilySessionReplica.RecoveryTarget?,
    ): Boolean = previousFamilyId == previous.familyId &&
        previousMembershipId == previous.membershipId &&
        previousDeviceId == previous.deviceId &&
        this.crossingFamilyBoundary == crossingFamilyBoundary &&
        this.recoveryTarget == recoveryTarget

    /**
     * A completed member full-resync is the closed authority set for the family. Local-only
     * subtrees are device history, not publishable family intent; settle their pending flags
     * before capture so deleting ordinary reconcile cannot turn them into causal commits.
     */
    private suspend fun settleMemberLocalOnlySubtrees() {
        val localBabies = babyDao.listAllIncludingDeleted().filterNot(BabyEntity::familyAuthority)
        if (localBabies.isEmpty()) return
        val babyIds = localBabies.mapTo(mutableSetOf(), BabyEntity::id)
        val records = recordDao.listAllIncludingDeleted().filter { it.babyId in babyIds }
        val recordIds = records.mapTo(mutableSetOf(), RecordEntity::id)
        val recordUuids = records.mapTo(mutableSetOf(), RecordEntity::clientUuid)
        val plans = carePlanDao.listAllIncludingDeleted().filter { it.babyId in babyIds }
        val planIds = plans.mapTo(mutableSetOf(), CarePlanEntity::id)
        val planUuids = plans.mapTo(mutableSetOf(), CarePlanEntity::clientUuid)
        val wakes = records.flatMap { wakeObservationDao.listForSleep(it.clientUuid) }
            .distinctBy { it.id }
        val wakeIds = wakes.mapTo(mutableSetOf()) { it.id }
        val media = mediaDao.listAllIncludingDeleted().filter {
            it.babyId in babyIds || it.recordId in recordIds || it.carePlanId in planIds ||
                it.wakeObservationId in wakeIds
        }
        val candidates = fulfillmentCandidateDao.listAllIncludingDeleted().filter {
            it.recordClientUuid in recordUuids || it.carePlanClientUuid in planUuids
        }
        transactionRunner.run {
            localBabies.forEach { babyDao.markSynced(it.clientUuid, it.updatedAt) }
            records.forEach { recordDao.markSynced(it.clientUuid, it.updatedAt) }
            plans.forEach { carePlanDao.markSynced(it.clientUuid, it.updatedAt) }
            wakes.forEach { wakeObservationDao.update(it.copy(syncDirty = false)) }
            candidates.forEach {
                fulfillmentCandidateDao.markSynced(it.clientUuid, it.updatedAt)
            }
            media.forEach { mediaDao.markSynced(it.clientUuid, it.updatedAt) }
        }
    }

    /**
     * Applies one bounded server page at a time. Normal incremental pulls
     * durably advance only after that page (including media materialization)
     * succeeds; the authoritative pre-push phase of full resync publishes its
     * cursor only after every page succeeds. Current-server `hasMore` and
     * `familyName` envelope fields are mandatory at the HTTP boundary.
     */
    private suspend fun pullAllPages(
        initial: SyncSession,
        reconcileMemberAvatars: Boolean = false,
        deferCursorUntilComplete: Boolean = false,
        forceAuthority: Boolean = false,
        resetReceipt: FamilySessionReplica.ResetReceipt? = null,
        pullTransport: PullTransportContract,
        mediaEditGuard: LocalMediaEditGuard,
    ): SyncSession {
        var current = initial
        val authoritativeMemberAvatarPointers = if (reconcileMemberAvatars) {
            linkedMapOf<String, String?>()
        } else {
            null
        }
        var pageCount = 0
        val observedEntityKeys = mutableSetOf<Pair<String, String>>()
        var hasObservedFamilyName = false
        var observedFamilyName: String? = null
        do {
            require(pageCount < pullTransport.budget.maxPages) {
                "家庭服务器同步超过 ${pullTransport.budget.maxPages} 页上限，请稍后重试"
            }
            requireRemoteAllowed(current)
            val pageRequest = pullTransport.page(pageCount)
            val pulled = backend.pull(current, pageRequest).requireValidPage(pageRequest)
            pageCount++
            val pageKeys = pulled.entities.map { it.type to it.clientUuid }
            require(pageKeys.none(observedEntityKeys::contains)) {
                "家庭服务器在连续 pull 页重复返回实体"
            }
            require(pulled.cursor >= current.pullCursor) {
                "家庭服务器返回了倒退的同步 cursor"
            }
            if (pulled.hasMore) {
                require(pulled.cursor > current.pullCursor) {
                    "家庭服务器分页 cursor 未推进"
                }
            }
            require(pulled.generation == current.pullGeneration) {
                "家庭服务器在分页期间返回了非当前同步代际"
            }
            val pageFamilyName = normalizeFamilyNameForWire(pulled.familyName)
            require(!hasObservedFamilyName || observedFamilyName == pageFamilyName) {
                "家庭服务器在分页期间变更了家庭名，请重试"
            }
            if (!hasObservedFamilyName) observedFamilyName = pageFamilyName
            hasObservedFamilyName = true
            authoritativeMemberAvatarPointers?.let { pointers ->
                pulled.entities
                    .filter { it.type == "baby" }
                    .forEach { entity ->
                        if (entity.deletedAt == null) {
                            val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
                            pointers[entity.clientUuid] = payload.string("avatar_media_uuid")
                        } else {
                            pointers.remove(entity.clientUuid)
                        }
                    }
            }
            applyRemote(
                current,
                pulled.entities,
                mediaEditGuard = mediaEditGuard,
                forceAuthority = forceAuthority,
                resetReceipt = resetReceipt,
            )
            observedEntityKeys += pageKeys
            val acknowledgedCreators = authoritativeCreatorAcknowledgements(
                pending = preferences.session.first().pendingCreatorAcknowledgements,
                entities = pulled.entities,
            )
            if (acknowledgedCreators.isNotEmpty()) {
                preferences.updateCreatorAcknowledgements(remove = acknowledgedCreators)
            }
            downloadMissingMedia(current, mediaEditGuard)
            if (!deferCursorUntilComplete) {
                preferences.updatePullCheckpoint(
                    cursor = pulled.cursor,
                    generation = pulled.generation,
                    familyName = pageFamilyName,
                )
                current = preferences.session.first()
            } else {
                // The authoritative pre-push phase of full resync must not
                // publish a partial cursor. Otherwise a restart can push the
                // re-queued local replica before the remaining server pages
                // have been applied.
                current = current.copy(
                    pullCursor = pulled.cursor,
                    pullGeneration = pulled.generation,
                    familyName = pageFamilyName,
                )
            }
        } while (pulled.hasMore)
        authoritativeMemberAvatarPointers?.let { pointers ->
            val discardedPaths = mutableSetOf<String>()
            transactionRunner.run {
                reconcileMemberAvatarAuthority(pointers, mediaEditGuard, discardedPaths)
            }
            cleanupUnownedStagedMedia(discardedPaths)
        }
        if (deferCursorUntilComplete) {
            preferences.updatePullCheckpoint(
                cursor = current.pullCursor,
                generation = current.pullGeneration,
                familyName = observedFamilyName,
            )
            current = preferences.session.first()
        }
        if (initial.role == FamilyRole.Member) {
            familyBabyAppliedListener.onFamilyBabyAuthorityApplied()
        }
        return current
    }

    private suspend fun reconcileMemberAvatarAuthority(
        serverPointers: Map<String, String?>,
        mediaEditGuard: LocalMediaEditGuard?,
        discardedPaths: MutableSet<String>,
    ) {
        val activeAvatarsByBaby = mediaDao.listAllIncludingDeleted()
            .filter { it.kind == "avatar" && it.deletedAt == null }
            .groupBy(MediaAssetEntity::babyId)
        babyDao.listAllIncludingDeleted().forEach { baby ->
            val authoritativePointer = serverPointers[baby.clientUuid]
            activeAvatarsByBaby[baby.id].orEmpty()
                .filter { it.clientUuid != authoritativePointer }
                .forEach { stale ->
                    stale.localUri.takeIf(String::isNotBlank)?.let(discardedPaths::add)
                    mediaDao.deleteByClientUuids(listOf(stale.clientUuid))
                }
            if (baby.avatarMediaUuid != authoritativePointer) {
                babyDao.updateAvatarReplica(
                    clientUuid = baby.clientUuid,
                    avatarMediaUuid = authoritativePointer,
                    avatarPath = null,
                )
                mediaEditGuard?.babyRefreshed(baby.clientUuid, null)
            }
        }
    }

    private suspend fun captureLocalChanges(
        session: SyncSession,
    ): CapturedLocalChanges {
        val spooledMedia = causalSettlement.recoverImmutableMediaSpool()
        repairTechnicalMediaBeforeCapture(session, spooledMedia)
        val candidates = mutableListOf<PublishCandidate>()
        fun enqueue(entity: SyncEntity, localMediaUri: String? = null) {
            candidates += PublishCandidate(
                planId = candidates.size.toLong() + 1,
                entityType = entity.type,
                clientUuid = entity.clientUuid,
                payloadJson = entity.payloadJson,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                localMediaUri = localMediaUri,
            )
        }
        val babySnapshots = babyDao.listPendingSync()
        val records = recordDao.listPendingSync()
        val carePlans = carePlanDao.listPendingSync()
        val customItems = customItemDao.listPendingSync()
        val wakeObservations = wakeObservationDao.listPendingSync()
        val fulfillmentCandidates = fulfillmentCandidateDao.listPendingSync()
        val capturedPendingCreatorAcknowledgements = mutableSetOf<CreatorAcknowledgementRef>()
        val babies = if (babySnapshots.isNotEmpty()) {
            materializeLocalMedia(
                includeAvatars = session.role != FamilyRole.Member,
                babies = babySnapshots,
            )
            // Avatar inspection can suspend. Re-read every pending Baby before
            // materializing its plan row so a concurrent profile edit is either
            // packaged as one current epoch or rejected later by the push CAS.
            babyDao.listPendingSync()
        } else {
            emptyList()
        }
        val directlyChangedMedia = mediaDao.listPendingSync()
        val referencedMedia = buildList {
            babies.forEach { baby ->
                // Deleted roots must never pull a live avatar into the tombstone package.
                if (baby.deletedAt == null) {
                    mediaDao.activeAvatarForBaby(baby.id)?.let(::add)
                }
            }
            records.forEach { record ->
                addAll(
                    mediaDao.listForRecord(record.id).filter { media ->
                        media.deletedAt == null || media.syncDirty
                    },
                )
            }
            carePlans.forEach { plan ->
                addAll(
                    mediaDao.listForCarePlan(plan.id).filter { media ->
                        media.deletedAt == null || media.syncDirty
                    },
                )
            }
            wakeObservations.forEach { wake ->
                addAll(
                    mediaDao.listActiveForWakeObservation(wake.id).filter { media ->
                        media.deletedAt == null || media.syncDirty
                    },
                )
            }
        }
        val capturedMedia = requirePortableMediaUuids(
            (directlyChangedMedia + referencedMedia).distinctBy(MediaAssetEntity::id),
        )
        val memberAvatarMedia = if (session.role == FamilyRole.Member) {
            capturedMedia.filter { asset ->
                asset.kind == "avatar" && asset.babyId != null
            }
        } else {
            emptyList()
        }
        val memberAvatarBabyIds = memberAvatarMedia.mapNotNullTo(
            mutableSetOf(),
            MediaAssetEntity::babyId,
        )
        if (memberAvatarMedia.isNotEmpty()) {
            transactionRunner.run {
                memberAvatarMedia.forEach { mediaDao.markSynced(it.clientUuid, it.updatedAt) }
                babies.filter { it.id in memberAvatarBabyIds }.forEach {
                    babyDao.markSynced(it.clientUuid, it.updatedAt)
                }
            }
        }
        val memberAvatarUuids = memberAvatarMedia.mapTo(mutableSetOf(), MediaAssetEntity::clientUuid)
        val media = capturedMedia.filterNot { candidate ->
            candidate.clientUuid in memberAvatarUuids
        }
        babies.forEach { baby ->
            if (baby.id in memberAvatarBabyIds) return@forEach
            // Tombstone packages always publish a null avatar pointer; never repair to live media.
            val avatarMediaUuid = if (baby.deletedAt != null) {
                null
            } else {
                val eligibleAvatars = media.filter {
                    it.kind == "avatar" &&
                        it.babyId == baby.id &&
                        it.deletedAt == null &&
                        (session.role != FamilyRole.Member || it.hasReceiptFor(session))
                }
                baby.avatarMediaUuid
                    ?.let { pointer ->
                        eligibleAvatars.firstOrNull { it.clientUuid == pointer }?.clientUuid
                    }
                    ?: if (session.role == FamilyRole.Member) {
                        null
                    } else {
                        eligibleAvatars
                            .maxWithOrNull(
                                compareBy<MediaAssetEntity> { it.updatedAt }.thenBy { it.id },
                            )
                            ?.clientUuid
                    }
            }
            enqueue(
                SyncWireMapper.baby(
                    baby,
                    avatarMediaUuid,
                ),
            )
        }
        customItems.forEach { item ->
            enqueue(SyncWireMapper.customItem(item))
            if (item.createdByMembershipId.isBlank()) {
                capturedPendingCreatorAcknowledgements += CreatorAcknowledgementRef(
                    entityType = "custom_item",
                    clientUuid = item.clientUuid,
                )
            }
        }
        // Ephemeral plan materialization only; atomic commit order is care_plan
        // packages → record packages → fulfillment_candidate package.
        records.forEach { record ->
            val babyUuid = babyDao.getIncludingDeleted(record.babyId)?.clientUuid
                ?: return@forEach
            val customItemUuid = recordCustomItemClientUuid(record)
            enqueue(
                SyncWireMapper.record(
                    record,
                    babyUuid,
                    customItemUuid,
                ),
            )
        }
        wakeObservations.forEach { wake ->
            enqueue(
                SyncEntity(
                    type = "wake_observation",
                    clientUuid = wake.clientUuid,
                    payloadJson = buildJsonObject {
                        put("sleep_record_client_uuid", wake.sleepRecordClientUuid)
                        put("wake_timestamp", wake.wakeTimestamp)
                        if (wake.note == null) {
                            put("note", JsonNull)
                        } else {
                            put("note", wake.note)
                        }
                        put("withdrawn", wake.withdrawn)
                        put("updated_at", wake.updatedAt)
                        if (wake.observerMembershipId.isNotBlank()) {
                            put("observer_membership_id", wake.observerMembershipId)
                        }
                    }.toString(),
                    updatedAt = wake.updatedAt,
                    deletedAt = wake.deletedAt,
                ),
            )
        }
        carePlans.forEach { plan ->
            val babyUuid = babyDao.getIncludingDeleted(plan.babyId)?.clientUuid
                ?: return@forEach
            val customItemUuid = plan.customItemId
                ?.let { customItemDao.getById(it)?.clientUuid }
            // Custom-item plans need a definition on the wire path; if the def is
            // still local-only, capture it via customItems dirty (dependency expand).
            enqueue(
                SyncWireMapper.carePlan(
                    plan,
                    babyUuid,
                    customItemUuid,
                ),
            )
            val memberNextFeedNeedsNasWinner = session.role == FamilyRole.Member &&
                plan.deletedAt == null &&
                plan.status in setOf(
                    CarePlanStatus.PENDING.storageKey,
                    CarePlanStatus.MISSED.storageKey,
                ) &&
                isNextFeedPlanNote(plan.note)
            if (plan.createdByMembershipId.isBlank() || memberNextFeedNeedsNasWinner) {
                capturedPendingCreatorAcknowledgements += CreatorAcknowledgementRef(
                    entityType = "care_plan",
                    clientUuid = plan.clientUuid,
                )
            }
        }
        fulfillmentCandidates.forEach { candidate ->
            enqueue(SyncWireMapper.fulfillmentCandidate(candidate))
        }
        media.forEach { asset ->
            // Wake media is part of the WakeObservation causal root. Until the
            // generic media spool lands, keep it pending instead of routing it
            // through the legacy standalone media publisher.
            if (asset.kind == "wake") return@forEach
            val recordUuid = asset.recordId
                ?.let { recordDao.getIncludingDeleted(it)?.clientUuid }
            val carePlanUuid = asset.carePlanId
                ?.let { carePlanDao.get(it)?.clientUuid }
            val ownerBaby = asset.babyId?.let { babyDao.getIncludingDeleted(it) }
            val babyUuid = ownerBaby?.clientUuid
            // Pre-fix orphans / soft-delete bypass: never ship a live avatar with a deleted Baby.
            if (asset.kind == "avatar" && asset.deletedAt == null && ownerBaby?.deletedAt != null) {
                return@forEach
            }
            if (asset.kind == "log") {
                // XOR ownership: record OR care_plan, never both, never neither.
                if (recordUuid == null && carePlanUuid == null) return@forEach
                if (recordUuid != null && carePlanUuid != null) return@forEach
            }
            if (asset.kind == "avatar" && babyUuid == null) {
                return@forEach
            }
            enqueue(
                SyncWireMapper.media(
                    asset,
                    recordClientUuid = recordUuid,
                    babyClientUuid = babyUuid,
                    carePlanClientUuid = carePlanUuid,
                ),
                localMediaUri = asset.localUri,
            )
        }
        return CapturedLocalChanges(
            candidates = candidates,
            pendingCreatorAcknowledgements = capturedPendingCreatorAcknowledgements,
        )
    }

    /**
     * Converts impossible local media shapes into deterministic technical
     * dispositions before the causal mutation snapshot is frozen.
     *
     * Missing bytes never delete their Record/CarePlan. A never-published
     * attachment becomes an atomic media tombstone, while a media row with a
     * current server receipt drops only its broken local path and lets the NAS
     * copy remain authoritative. Rows with no business owner are safe to hard
     * delete; their paths are reclaimed through the reference-aware file gate.
     */
    private suspend fun repairTechnicalMediaBeforeCapture(
        session: SyncSession,
        spooledMedia: Set<String>,
    ) {
        val orphanPaths = mutableSetOf<String>()
        // Causal settlement can include a clean media row when its owning root is pending (and
        // the first settle after disaster recovery can cover an entirely clean restored set).
        // Inspect the complete local media set so historical zero/null probe fields cannot reach
        // the commit merely because the row already has a publication receipt.
        for (snapshot in mediaDao.listAllIncludingDeleted()) {
            // Wake media belongs exclusively to the causal root owner. This technical repair
            // path must not inspect, normalize, tombstone, or delete it; its immutable spool
            // and receipt settlement are owned by the causal root.
            if (snapshot.kind == "wake") continue
            if (snapshot.clientUuid in spooledMedia) continue
            val inspected = snapshot.localUri
                .takeIf(String::isNotBlank)
                ?.let { mediaFiles.inspect(it) }
            transactionRunner.run {
                val current = mediaDao.getByClientUuid(snapshot.clientUuid) ?: return@run
                if (current != snapshot) return@run
                // Existing tombstones are durable family deletion evidence.
                // Their owner may already be gone; file cleanup clears only
                // localUri and must not hard-delete the metadata row here.
                if (current.deletedAt != null) return@run
                val record = current.recordId?.let { recordDao.getIncludingDeleted(it) }
                val carePlan = current.carePlanId?.let { carePlanDao.get(it) }
                val baby = current.babyId?.let { babyDao.getIncludingDeleted(it) }
                val validOwner = when (current.kind) {
                    "log" -> ((record != null) xor (carePlan != null)) &&
                        current.babyId == null && current.wakeObservationId == null
                    "avatar" -> baby != null && current.recordId == null &&
                        current.carePlanId == null && current.wakeObservationId == null
                    else -> false
                }
                if (!validOwner) {
                    orphanPaths += current.localUri
                    mediaDao.deleteByClientUuids(listOf(current.clientUuid))
                    return@run
                }
                val invalidDeletedBabyAvatar = current.kind == "avatar" && baby?.deletedAt != null
                val missingLocalBytes = current.localUri.isBlank() ||
                    inspected == null ||
                    inspected.byteSize <= 0
                if (!invalidDeletedBabyAvatar && !missingLocalBytes) {
                    // v12 media rows can retain the default zero/null probe fields even though
                    // their app-owned file is intact. Causal commit validates the manifest before
                    // the publisher gets a chance to prepare the upload, so repair
                    // those historical fields under the same revision CAS before capture.
                    mediaDao.mergePreparedMetadata(
                        clientUuid = current.clientUuid,
                        expectedUpdatedAt = current.updatedAt,
                        expectedLocalUri = current.localUri,
                        expectedDeletedAt = current.deletedAt,
                        mime = inspected.mime,
                        width = inspected.width,
                        height = inspected.height,
                        byteSize = inspected.byteSize,
                    )
                    return@run
                }

                // The complete-set scan above exists only to repair intact historical rows.
                // Preserve the prior pending-only disposition for missing bytes: a clean row
                // may be waiting for pull-side recovery, and an untrusted receipt-shaped value
                // must not turn that row into a family tombstone.
                if (!current.syncDirty) return@run

                if (missingLocalBytes && current.hasReceiptFor(session)) {
                    mediaDao.update(current.copy(localUri = ""))
                    return@run
                }

                mediaDao.update(
                    current.copy(
                        deletedAt = current.updatedAt,
                        syncDirty = true,
                    ),
                )
                if (current.kind == "avatar" && baby?.avatarMediaUuid == current.clientUuid) {
                    babyDao.updateAvatarReplica(
                        clientUuid = baby.clientUuid,
                        avatarMediaUuid = null,
                        avatarPath = null,
                    )
                }
            }
        }
        mediaFileCleanup.cleanupUnreferencedPaths(orphanPaths)
    }

    private suspend fun recordCustomItemClientUuid(record: RecordEntity): String? =
        resolveRecordCustomItemClientUuid(record, customItemDao)

    private suspend fun authoritativeCreatorAcknowledgements(
        pending: Set<CreatorAcknowledgementRef>,
        entities: List<SyncEntity>,
    ): Set<CreatorAcknowledgementRef> = entities.mapNotNull { entity ->
        val ref = CreatorAcknowledgementRef(entity.type, entity.clientUuid)
        if (ref !in pending) return@mapNotNull null
        val remoteCreator = runCatching {
            Json.parseToJsonElement(entity.payloadJson).jsonObject
        }.getOrNull()
            ?.string("created_by_membership_id")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return@mapNotNull null
        val applied = when (entity.type) {
            "care_plan" -> carePlanDao.getByClientUuid(entity.clientUuid)?.let { local ->
                local.updatedAt == entity.updatedAt &&
                    local.createdByMembershipId.trim() == remoteCreator
            } == true
            "custom_item" -> customItemDao.getByClientUuid(entity.clientUuid)?.let { local ->
                local.updatedAt == entity.updatedAt &&
                    local.createdByMembershipId.trim() == remoteCreator
            } == true
            else -> false
        }
        ref.takeIf { applied }
    }.toSet()

    private suspend fun materializeLocalMedia(
        includeAvatars: Boolean,
        babies: List<BabyEntity>,
    ) {
        if (includeAvatars) {
            babies.forEach { snapshot ->
                val snapshotPath = snapshot.avatarPath?.takeIf { it.isNotBlank() }
                val inspected = snapshotPath?.let { mediaFiles.inspect(it) }
                transactionRunner.run {
                    val baby = babyDao.getIncludingDeleted(snapshot.id) ?: return@run
                    if (
                        baby.updatedAt != snapshot.updatedAt ||
                        baby.avatarPath != snapshot.avatarPath
                    ) {
                        return@run
                    }
                    val existing = mediaDao.activeAvatarForBaby(baby.id)
                    val path = baby.avatarPath?.takeIf { it.isNotBlank() }
                    if (path == null) {
                        if (existing != null) {
                            mediaDao.update(
                                existing.copy(
                                    updatedAt = baby.updatedAt,
                                    deletedAt = baby.updatedAt,
                                    syncDirty = true,
                                ),
                            )
                        }
                        if (baby.avatarMediaUuid != null) {
                            babyDao.updateAvatarMediaForLocalSnapshot(
                                id = baby.id,
                                expectedUpdatedAt = baby.updatedAt,
                                expectedAvatarPath = baby.avatarPath,
                                avatarMediaUuid = null,
                            )
                        }
                        return@run
                    }
                    if (existing?.localUri == path) {
                        if (baby.avatarMediaUuid != existing.clientUuid) {
                            babyDao.updateAvatarMediaForLocalSnapshot(
                                id = baby.id,
                                expectedUpdatedAt = baby.updatedAt,
                                expectedAvatarPath = baby.avatarPath,
                                avatarMediaUuid = existing.clientUuid,
                            )
                        }
                        return@run
                    }
                    val info = inspected ?: return@run
                    if (existing != null) {
                        mediaDao.update(
                            existing.copy(
                                updatedAt = baby.updatedAt,
                                deletedAt = baby.updatedAt,
                                syncDirty = true,
                            ),
                        )
                    }
                    val avatarMediaUuid = UUID.randomUUID().toString()
                    mediaDao.upsert(
                        MediaAssetEntity(
                            clientUuid = avatarMediaUuid,
                            kind = "avatar",
                            babyId = baby.id,
                            localUri = path,
                            mime = info.mime,
                            width = info.width,
                            height = info.height,
                            byteSize = info.byteSize,
                            createdAt = baby.updatedAt,
                            updatedAt = baby.updatedAt,
                        ),
                    )
                    check(
                        babyDao.updateAvatarMediaForLocalSnapshot(
                            id = baby.id,
                            expectedUpdatedAt = baby.updatedAt,
                            expectedAvatarPath = baby.avatarPath,
                            avatarMediaUuid = avatarMediaUuid,
                        ) == 1,
                    ) {
                        "宝宝头像在媒体快照期间发生变化"
                    }
                }
            }
        }
    }

    private fun requirePortableMediaUuids(
        candidates: List<MediaAssetEntity>,
    ): List<MediaAssetEntity> {
        candidates.forEach { media ->
            requireCanonicalUuid(media.clientUuid, "media client_uuid")
        }
        return candidates
    }

    private suspend fun downloadMissingMedia(
        session: SyncSession,
        mediaEditGuard: LocalMediaEditGuard?,
    ) {
        mediaDao.listMissingLocalBytes()
            .filter { it.hasReceiptFor(session) }
            .forEach { media ->
                if (mediaEditGuard?.canReplace(media) == false) return@forEach
                requireRemoteAllowed(session)
                // A 404 is an isolated half-upload and stays queued for retry.
                // Auth, server, and transport failures fail the whole cycle so
                // they cannot be reported as a successful sync.
                val bytes = try {
                    backend.getMedia(session, media.clientUuid)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: SyncHttpException) {
                    if (error.statusCode == 404) return@forEach
                    throw error
                }
                val localUri = try {
                    mediaFiles.saveDownloaded(
                        media.clientUuid,
                        media.kind,
                        bytes,
                        media.mime,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@forEach
                }
                var adopted = false
                transactionRunner.run {
                    val current = mediaDao.getByClientUuid(media.clientUuid) ?: return@run
                    if (current != media || mediaEditGuard?.canReplace(current) == false) return@run
                    val next = current.copy(localUri = localUri)
                    mediaDao.update(next)
                    mediaEditGuard?.mediaRefreshed(next)
                    current.babyId?.let { babyId ->
                        refreshBabyAvatar(babyId, mediaEditGuard)
                    }
                    adopted = true
                }
                if (!adopted) mediaFiles.delete(localUri)
            }
    }

    private suspend fun refreshBabyAvatar(
        babyId: Long,
        mediaEditGuard: LocalMediaEditGuard? = null,
    ) {
        val baby = babyDao.getIncludingDeleted(babyId) ?: return
        if (mediaEditGuard?.canRefresh(baby) == false) return
        val avatarPath = baby.avatarMediaUuid
            ?.let { mediaDao.getByClientUuid(it) }
            ?.takeIf {
                it.kind == "avatar" &&
                    it.babyId == babyId &&
                    it.deletedAt == null
            }
            ?.localUri
            ?.takeIf(String::isNotBlank)
        if (baby.avatarPath != avatarPath) {
            babyDao.updateAvatarPathForReplica(
                id = baby.id,
                expectedAvatarMediaUuid = baby.avatarMediaUuid,
                avatarPath = avatarPath,
            )
        }
        mediaEditGuard?.babyRefreshed(baby.clientUuid, avatarPath)
    }

    private suspend fun captureLocalMediaEditGuard(): LocalMediaEditGuard =
        LocalMediaEditGuard(
            mediaSnapshots = mediaDao.listAllIncludingDeleted()
                .associateBy(MediaAssetEntity::clientUuid)
                .toMutableMap(),
            babyAvatarPaths = babyDao.listAllIncludingDeleted()
                .associate { it.clientUuid to it.avatarPath }
                .toMutableMap(),
        )

}

/**
 * Fail closed before a pull transaction could expose an eleventh live family
 * custom definition. The calculation mirrors updated_at LWW and includes
 * tombstones/replacements arriving in the same page.
 */
internal fun requireCustomItemCapacityAfterApply(
    existing: List<CustomItemEntity>,
    incoming: List<SyncEntity>,
) {
    val effective = existing.associate { item ->
        item.clientUuid to (item.updatedAt to item.deletedAt)
    }.toMutableMap()
    incoming.asSequence()
        .filter { it.type == "custom_item" }
        .forEach { remote ->
            val localRevision = effective[remote.clientUuid]?.first
            if (localRevision == null || remote.updatedAt >= localRevision) {
                effective[remote.clientUuid] = remote.updatedAt to remote.deletedAt
            }
        }
    require(effective.values.count { (_, deletedAt) -> deletedAt == null } <= 10) {
        "家庭自定义项目最多 10 个，请先删除一个后重试同步"
    }
}

internal data class RecordWire(
    val babyClientUuid: String,
    val createdByMembershipId: String,
    val type: RecordType,
    val customItemClientUuid: String?,
    val timestamp: Long,
    val endTimestamp: Long?,
    val note: String?,
    val payload: JsonObject,
    val schemaVersion: Int,
    val effectiveWakeObservationClientUuid: String? = null,
    val effectiveWakeObservationPresent: Boolean = false,
)

private data class FulfillmentCandidateWire(
    val carePlanClientUuid: String,
    val recordClientUuid: String,
    val actualTimestamp: Long?,
    val submitterMembershipId: String,
    val submitterRole: String,
    val confirmedAt: Long,
)

private data class MediaWire(
    val kind: String,
    val recordClientUuid: String?,
    val carePlanClientUuid: String?,
    val babyClientUuid: String?,
    val mime: String?,
    val width: Int?,
    val height: Int?,
    val byteSize: Long,
)

internal fun parseRecordWire(payload: JsonObject): RecordWire {
    val baseKeys = setOf(
        "baby_client_uuid",
        "created_by_membership_id",
        "type",
        "custom_item_client_uuid",
        "timestamp",
        "end_timestamp",
        "note",
        "payload_json",
        "schema_version",
    )
    val type = SyncWireMapper.requireCurrentRecordType(
        payload.requireNonBlankString("type", "record"),
        "record type",
    )
    val sleepCausalKeys = (baseKeys - "end_timestamp") +
        "effective_wake_observation_client_uuid"
    val sleepLegacyKeys = baseKeys + "effective_wake_observation_client_uuid"
    val allowed = if (type == RecordType.SLEEP) {
        payload.keys == sleepCausalKeys ||
            payload.keys == sleepLegacyKeys ||
            payload.keys == baseKeys
    } else {
        payload.keys == baseKeys
    }
    require(allowed) {
        "record current wire 字段不完整或包含未知字段: ${payload.keys.sorted()}"
    }
    if (type != RecordType.SLEEP) {
        require("effective_wake_observation_client_uuid" !in payload) {
            "record effective_wake_observation_client_uuid 仅允许 sleep"
        }
    }
    val customItemUuid = payload.requireNullableString("custom_item_client_uuid", "record")
    require((type == RecordType.CUSTOM) == (customItemUuid != null)) {
        if (type == RecordType.CUSTOM) {
            "record type custom requires custom_item_client_uuid"
        } else {
            "record custom_item_client_uuid is only valid for type custom"
        }
    }
    val timestamp = payload.requireLong("timestamp", "record")
    require(timestamp >= 0) { "record timestamp 无效" }
    val endTimestamp = if ("end_timestamp" in payload) {
        payload.requireNullableLong("end_timestamp", "record")
    } else {
        null
    }
    require(endTimestamp == null || endTimestamp >= timestamp) { "record end_timestamp 无效" }
    val nested = payload.requireObject("payload_json", "record")
    require("photos" !in nested && "custom_item_id" !in nested) {
        "record payload_json 包含设备本地字段"
    }
    val effectiveWake = if ("effective_wake_observation_client_uuid" in payload) {
        payload.requireNullableString("effective_wake_observation_client_uuid", "record")
    } else {
        null
    }
    return RecordWire(
        babyClientUuid = payload.requireNonBlankString("baby_client_uuid", "record"),
        createdByMembershipId = payload.requireNullableString(
            "created_by_membership_id",
            "record",
        ).orEmpty().trim(),
        type = type,
        customItemClientUuid = customItemUuid,
        timestamp = timestamp,
        endTimestamp = endTimestamp,
        note = payload.requireNullableString("note", "record"),
        payload = nested,
        schemaVersion = SyncWireMapper.recordSchemaVersion(payload),
        effectiveWakeObservationClientUuid = effectiveWake,
        effectiveWakeObservationPresent =
            "effective_wake_observation_client_uuid" in payload,
    )
}

private fun parseFulfillmentCandidateWire(payload: JsonObject): FulfillmentCandidateWire {
    payload.requireExactKeys(
        "fulfillment_candidate",
        "care_plan_client_uuid",
        "record_client_uuid",
        "actual_timestamp",
        "submitter_membership_id",
        "submitter_role",
        "confirmed_at",
    )
    val role = payload.requireNonBlankString("submitter_role", "fulfillment_candidate")
    require(role == "owner" || role == "member") { "fulfillment_candidate submitter_role 无效" }
    val actual = payload.requireNullableLong("actual_timestamp", "fulfillment_candidate")
    require(actual == null || actual >= 0) { "fulfillment_candidate actual_timestamp 无效" }
    val confirmed = payload.requireLong("confirmed_at", "fulfillment_candidate")
    require(confirmed >= 0) { "fulfillment_candidate confirmed_at 无效" }
    return FulfillmentCandidateWire(
        carePlanClientUuid = payload.requireNonBlankString(
            "care_plan_client_uuid",
            "fulfillment_candidate",
        ),
        recordClientUuid = payload.requireNonBlankString(
            "record_client_uuid",
            "fulfillment_candidate",
        ),
        actualTimestamp = actual,
        submitterMembershipId = payload.requireNullableString(
            "submitter_membership_id",
            "fulfillment_candidate",
        ).orEmpty().trim(),
        submitterRole = role,
        confirmedAt = confirmed,
    )
}

private fun parseMediaWire(payload: JsonObject): MediaWire {
    payload.requireExactKeys(
        "media",
        "kind",
        "record_client_uuid",
        "care_plan_client_uuid",
        "baby_client_uuid",
        "mime",
        "width",
        "height",
        "byte_size",
    )
    val kind = payload.requireNonBlankString("kind", "media")
    require(kind == "log" || kind == "avatar") { "media kind 无效" }
    val recordUuid = payload.requireNullableString("record_client_uuid", "media")
    val carePlanUuid = payload.requireNullableString("care_plan_client_uuid", "media")
    val babyUuid = payload.requireNullableString("baby_client_uuid", "media")
    require(
        if (kind == "log") {
            (recordUuid == null) != (carePlanUuid == null)
        } else {
            babyUuid != null && recordUuid == null && carePlanUuid == null
        },
    ) { "media ownership 无效" }
    val width = payload.requireNullableLong("width", "media")
    val height = payload.requireNullableLong("height", "media")
    require(width == null || width in 1..Int.MAX_VALUE.toLong()) { "media width 无效" }
    require(height == null || height in 1..Int.MAX_VALUE.toLong()) { "media height 无效" }
    val byteSize = payload.requireLong("byte_size", "media")
    require(byteSize >= 0) { "media byte_size 无效" }
    return MediaWire(
        kind = kind,
        recordClientUuid = recordUuid,
        carePlanClientUuid = carePlanUuid,
        // Current Android writes log ownership through its record/plan root only.
        // The NAS contract also accepts a matching baby_client_uuid on historical
        // log media, so tolerate it on pull without persisting dual ownership.
        babyClientUuid = babyUuid.takeIf { kind == "avatar" },
        mime = payload.requireNullableString("mime", "media"),
        width = width?.toInt(),
        height = height?.toInt(),
        byteSize = byteSize,
    )
}

private fun JsonObject.requireExactKeys(context: String, vararg expected: String) {
    require(keys == expected.toSet()) {
        "$context current wire 字段不完整或包含未知字段: ${keys.sorted()}"
    }
}

private fun JsonObject.requireNonBlankString(key: String, context: String): String {
    val primitive = get(key) as? JsonPrimitive
    require(primitive?.isString == true && primitive.content.isNotBlank()) {
        "$context.$key 必须是非空字符串"
    }
    return primitive.content
}

private fun JsonObject.requireNullableString(key: String, context: String): String? {
    require(key in this) { "$context 缺少 $key" }
    val value = getValue(key)
    if (value === JsonNull) return null
    val primitive = value as? JsonPrimitive
    require(primitive?.isString == true) { "$context.$key 必须是字符串或 null" }
    return primitive.content
}

private fun JsonObject.requireLong(key: String, context: String): Long {
    val primitive = get(key) as? JsonPrimitive
    require(primitive?.isString == false && primitive.longOrNull != null) {
        "$context.$key 必须是整数"
    }
    return requireNotNull(primitive.longOrNull)
}

private fun JsonObject.requireNullableLong(key: String, context: String): Long? {
    require(key in this) { "$context 缺少 $key" }
    val value = getValue(key)
    if (value === JsonNull) return null
    val primitive = value as? JsonPrimitive
    require(primitive?.isString == false && primitive.longOrNull != null) {
        "$context.$key 必须是整数或 null"
    }
    return primitive.longOrNull
}

private fun JsonObject.requireObject(key: String, context: String): JsonObject =
    requireNotNull(get(key) as? JsonObject) { "$context.$key 必须是对象" }

private fun requireCanonicalUuid(value: String, field: String) {
    val parsed = runCatching { UUID.fromString(value) }.getOrNull()
    require(parsed?.toString() == value) {
        "$field 必须是规范 UUID: $value"
    }
}

private data class FullResyncCheckpoint(
    val resetCursor: Long,
    val serverGeneration: String,
)

private fun SyncHttpException.fullResyncCheckpointOrNull(): FullResyncCheckpoint? {
    if (statusCode != 409) return null
    val detail = runCatching {
        Json.parseToJsonElement(responseBody).jsonObject["detail"]?.jsonObject
    }.getOrNull() ?: return null
    if (
        detail.string("code") !in setOf(
            "cursor_ahead",
            "generation_changed",
        )
    ) {
        return null
    }
    if (detail.string("action") != "full_resync") return null
    val resetCursor = detail.long("reset_cursor")?.takeIf { it == 0L } ?: return null
    val generation = detail.string("server_generation")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: return null
    return FullResyncCheckpoint(resetCursor, generation)
}

private fun SyncSession.requireCurrentReplicaSession(requireGeneration: Boolean = true) {
    require(isJoined) { "当前同步会话尚未加入家庭" }
    require(deviceId.isNotBlank()) { "当前同步会话缺少 device_id" }
    if (requireGeneration) {
        require(pullGeneration.isNotBlank()) { "当前同步会话缺少 generation" }
    }
    require(membershipId.isNotBlank()) { "当前同步会话缺少 membership_id" }
}

private fun MediaAssetEntity.hasReceiptFor(session: SyncSession): Boolean =
    remoteUri == session.receiptFor(clientUuid)

private class LocalMediaEditGuard(
    private val mediaSnapshots: MutableMap<String, MediaAssetEntity>,
    private val babyAvatarPaths: MutableMap<String, String?>,
) {
    fun canReplace(media: MediaAssetEntity): Boolean {
        val snapshot = mediaSnapshots[media.clientUuid] ?: return true
        return snapshot.copy(
            remoteUri = media.remoteUri,
            syncDirty = media.syncDirty,
        ) == media
    }

    fun canRefresh(baby: BabyEntity): Boolean =
        baby.clientUuid !in babyAvatarPaths ||
            babyAvatarPaths[baby.clientUuid] == baby.avatarPath

    fun mediaRefreshed(media: MediaAssetEntity) {
        mediaSnapshots[media.clientUuid] = media
    }

    fun babyRefreshed(clientUuid: String, avatarPath: String?) {
        babyAvatarPaths[clientUuid] = avatarPath
    }
}

internal val ENTITY_ORDER = listOf(
    "baby",
    "custom_item",
    "record",
    "wake_observation",
    "care_plan",
    "media",
    "fulfillment_candidate",
)
private val CURRENT_ENTITY_TYPES = ENTITY_ORDER.toSet()
internal const val PUSH_ROOT_BATCH_SIZE = 200
internal const val MAX_PUSH_BATCH_SIZE = 1_000
private const val MAX_AUTHORITY_SETTLEMENT_PASSES = 8
