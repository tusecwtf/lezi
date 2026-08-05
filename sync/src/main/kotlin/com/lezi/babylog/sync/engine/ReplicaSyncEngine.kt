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
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.FulfillmentAuthority
import com.lezi.babylog.core.model.FulfillmentCandidateEvidence
import com.lezi.babylog.core.model.isNextFeedPlanNote
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.matchesPublishedRevision
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.OpenSleepCandidate
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SleepPayload
import com.lezi.babylog.core.model.limitBabyNicknameInput
import com.lezi.babylog.core.model.normalizeOpenSleeps
import java.time.ZoneId
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.SyncPlan
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.AuthorityDisposition
import com.lezi.babylog.sync.backend.AuthorityProofException
import com.lezi.babylog.sync.backend.ReconcileResult
import com.lezi.babylog.sync.backend.ReconcileUnitDraft
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.media.ReferenceAwareMediaFileCleanup
import com.lezi.babylog.sync.media.SyncMediaFileStore
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

    fun forRecord(recordClientUuid: String, updatedAt: Long): String =
        fromRoot("record", recordClientUuid, updatedAt)

    fun forCarePlan(planClientUuid: String, updatedAt: Long): String =
        fromRoot("care_plan", planClientUuid, updatedAt)

    fun forBaby(babyClientUuid: String, updatedAt: Long): String =
        fromRoot("baby", babyClientUuid, updatedAt)

    fun forCustomItem(itemClientUuid: String, updatedAt: Long): String =
        fromRoot("custom_item", itemClientUuid, updatedAt)

    fun forFulfillmentCandidate(candidateClientUuid: String, updatedAt: Long): String =
        fromRoot("fulfillment_candidate", candidateClientUuid, updatedAt)

    private fun fromRoot(rootType: String, clientUuid: String, updatedAt: Long): String =
        UUID.nameUUIDFromBytes(
            "$NAMESPACE:$rootType:$clientUuid:$updatedAt".toByteArray(Charsets.UTF_8),
        ).toString()
}

internal sealed interface ReplicaSyncOutcome {
    data class Synchronized(
        /** Explicit server neighbor-loser uuids from this cycle's commits. */
        val neighborLoserClientUuids: Set<String> = emptySet(),
    ) : ReplicaSyncOutcome
}

private data class CapturedLocalChanges(
    val candidates: List<PublishCandidate>,
    val pendingCreatorAcknowledgements: Set<CreatorAcknowledgementRef>,
)

private data class FrozenAuthorityUnit(
    val draft: ReconcileUnitDraft,
    val candidates: List<PublishCandidate>,
    val syntheticRootExpectedUpdatedAt: Long? = null,
)

private data class SyntheticAuthorityRoot(
    val entity: SyncEntity,
    val expectedLocalUpdatedAt: Long,
)

private data class AuthoritySettlement(
    val publishable: List<PublishCandidate>,
    val retryCount: Int,
)

private class AuthorityCasMismatchException : IllegalStateException()

/**
 * Owns one complete foreground replica cycle behind a single interface.
 *
 * The caller supplies a joined session and trigger. Each cycle reconciles remote state before
 * snapshotting dirty Room entities into an ephemeral publication plan. Every remote page and
 * media retry re-enters the same gate. Failures and cancellation escape without being translated;
 * Room remains authoritative and the next cycle replans from its current state.
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
    private val mediaFileCleanup: ReferenceAwareMediaFileCleanup,
    private val transactionRunner: DatabaseTransactionRunner,
    private val carePlanAppliedListener: CarePlanFamilyAppliedListener,
    private val familyBabyAppliedListener: FamilyBabyAuthorityAppliedListener =
        NoOpFamilyBabyAuthorityAppliedListener(),
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val requireRemoteAllowed: suspend (SyncSession) -> Unit,
) : FamilySessionReplica {
    private val publisher = EphemeralPublishPipeline(
        backend = backend,
        recordDao = recordDao,
        carePlanDao = carePlanDao,
        babyDao = babyDao,
        mediaDao = mediaDao,
        customItemDao = customItemDao,
        fulfillmentCandidateDao = fulfillmentCandidateDao,
        mediaFiles = mediaFiles,
        requireRemoteAllowed = requireRemoteAllowed,
    )

    suspend fun synchronize(
        session: SyncSession,
        trigger: SyncTrigger,
    ): ReplicaSyncOutcome {
        session.requireCurrentReplicaSession()
        mediaFileCleanup.cleanupPendingTombstones()
        val mediaEditGuard = captureLocalMediaEditGuard()
        val plan = SyncPlan.forTrigger(trigger)
        var current = preferences.session.first()
        requireRemoteAllowed(current)
        current = convergeAuthenticatedSelfMembership(
            current,
            backend.members(current),
        )
        var recovered = false
        val neighborLosers = linkedSetOf<String>()
        try {
            current = pullAllPages(current, mediaEditGuard = mediaEditGuard)
        } catch (error: SyncHttpException) {
            val checkpoint = error.fullResyncCheckpointOrNull() ?: throw error
            current = recoverFullResync(current, checkpoint, mediaEditGuard)
            recovered = true
        }
        if (plan.push && !recovered) {
            val captured = captureLocalChanges(current)
            if (captured.pendingCreatorAcknowledgements.isNotEmpty()) {
                preferences.updateCreatorAcknowledgements(
                    add = captured.pendingCreatorAcknowledgements,
                )
                current = preferences.session.first()
            }
            try {
                neighborLosers += settleAndPublish(current, captured.candidates)
            } catch (error: AuthorityProofException) {
                current = recoverFullResync(
                    current,
                    FullResyncCheckpoint(
                        resetCursor = 0,
                        serverGeneration = error.serverGeneration,
                    ),
                    mediaEditGuard,
                )
                recovered = true
            } catch (error: SyncHttpException) {
                val checkpoint = error.fullResyncCheckpointOrNull() ?: throw error
                current = recoverFullResync(current, checkpoint, mediaEditGuard)
                recovered = true
            }
            if (captured.pendingCreatorAcknowledgements.isNotEmpty() && !recovered) {
                try {
                    pullAllPages(current, mediaEditGuard = mediaEditGuard)
                } catch (error: SyncHttpException) {
                    val checkpoint = error.fullResyncCheckpointOrNull() ?: throw error
                    current = recoverFullResync(current, checkpoint, mediaEditGuard)
                    recovered = true
                }
            }
        }
        // A completed quiet cycle also completes file-side technical cleanup.
        // Tombstone metadata remains as family deletion evidence; only unowned
        // bytes and their retry marker are reclaimed here.
        mediaFileCleanup.cleanupPendingTombstones()
        return ReplicaSyncOutcome.Synchronized(neighborLoserClientUuids = neighborLosers)
    }

    override suspend fun applyInitialEntities(
        session: SyncSession,
        entities: List<SyncEntity>,
    ) {
        session.requireCurrentReplicaSession()
        mediaFileCleanup.cleanupPendingTombstones()
        if (session.role == FamilyRole.Member) {
            // A join snapshot starts a new authority set. Never let a Baby marker
            // retained from a previous family/session masquerade as current authority.
            babyDao.clearFamilyAuthority()
        }
        applyRemote(session, entities)
        if (session.role == FamilyRole.Member) {
            familyBabyAppliedListener.onFamilyBabyAuthorityApplied()
        }
    }

    private suspend fun pushPending(
        session: SyncSession,
        candidates: List<PublishCandidate>,
    ): Set<String> = publisher.pushPending(session, candidates)

    /**
     * Resolve dependency-ordered authority in one bounded foreground cycle.
     * A batch may legitimately publish a missing Baby/CustomItem while a
     * dependent Record/Plan returns retry_authority. Publishing that proven
     * prerequisite and freezing Room again is progress, not a partial clear.
     */
    private suspend fun settleAndPublish(
        session: SyncSession,
        initialCandidates: List<PublishCandidate>,
    ): Set<String> {
        var candidates = initialCandidates
        val neighborLosers = linkedSetOf<String>()
        repeat(MAX_AUTHORITY_SETTLEMENT_PASSES) {
            val settlement = reconcileFrozenChanges(session, candidates)
            neighborLosers += pushPending(session, settlement.publishable)
            if (settlement.retryCount == 0) return neighborLosers
            require(settlement.publishable.isNotEmpty()) {
                "家庭服务器暂时无法完成权威裁决，请稍后重试"
            }
            candidates = captureLocalChanges(session).candidates
        }
        error("家庭同步依赖在 $MAX_AUTHORITY_SETTLEMENT_PASSES 轮内未收敛，请稍后重试")
    }

    private suspend fun applyRemote(
        session: SyncSession,
        entities: List<SyncEntity>,
        mediaEditGuard: LocalMediaEditGuard? = null,
        authoritativeKeys: Set<Pair<String, String>> = emptySet(),
        authorityCandidates: List<PublishCandidate> = emptyList(),
        authorityDiscardedMediaUuids: Set<String> = emptySet(),
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
            if (authorityCandidates.any { !candidateStillCurrent(it) }) {
                throw AuthorityCasMismatchException()
            }
            requireCustomItemCapacityAfterApply(
                existing = customItemDao.listAllIncludingDeleted(),
                incoming = entities,
            )
            val unresolved = mutableListOf<SyncEntity>()
            for (entity in entities.filter { it.type == "baby" }) {
                if (!applyBaby(session, entity, entity.authoritativeIn(authoritativeKeys))) {
                    unresolved += entity
                }
            }
            for (entity in entities.filter { it.type == "custom_item" }) {
                if (!applyCustomItem(session, entity, entity.authoritativeIn(authoritativeKeys))) {
                    unresolved += entity
                }
            }
            // Fulfillment full-set: record(+photos) before completed care_plan before
            // fulfillment_candidate. Incomplete sets leave cursor unmoved (unresolved).
            for (entity in entities.filter { it.type == "record" }) {
                if (!applyRecord(entity, entity.authoritativeIn(authoritativeKeys))) {
                    unresolved += entity
                }
            }
            for (entity in entities.filter { it.type == "care_plan" }) {
                val applied = applyCarePlan(
                    session,
                    entity,
                    discardedLocalMediaPaths,
                    entity.authoritativeIn(authoritativeKeys),
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
                        entity.authoritativeIn(authoritativeKeys),
                    )
                ) {
                    unresolved += entity
                }
            }
            for (entity in entities.filter { it.type == "fulfillment_candidate" }) {
                if (!applyFulfillmentCandidate(
                        entity,
                        entity.authoritativeIn(authoritativeKeys),
                    )
                ) {
                    unresolved += entity
                }
            }
            for (mediaUuid in authorityDiscardedMediaUuids) {
                mediaDao.getByClientUuid(mediaUuid)?.localUri
                    ?.takeIf(String::isNotBlank)
                    ?.let(discardedLocalMediaPaths::add)
            }
            if (authorityDiscardedMediaUuids.isNotEmpty()) {
                mediaDao.deleteByClientUuids(authorityDiscardedMediaUuids.toList())
            }
            require(unresolved.isEmpty()) {
                "同步数据引用尚未就绪，保留 cursor 以便重试"
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
                resolveFulfillmentAuthority(planUuid)
            }
            entities.filter { it.type == "care_plan" }
                .mapNotNull { entity -> carePlanDao.getByClientUuid(entity.clientUuid)?.babyId }
                .distinct()
                .forEach { babyId ->
                    appliedCarePlanUuids += healDuplicateOpenNextFeedPlans(session, babyId)
                }
            entities.filter { it.type == "record" }
                .mapNotNull { entity -> recordDao.getByClientUuid(entity.clientUuid)?.babyId }
                .distinct()
                .forEach { babyId ->
                    healOpenSleepsClosedByFamilyWake(babyId)
                    healDuplicateOpenSleeps(babyId)
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

    private fun SyncEntity.authoritativeIn(keys: Set<Pair<String, String>>): Boolean =
        type to clientUuid in keys

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
        val wire = parseCarePlanWire(payload)
        val baby = babyDao.getByClientUuid(wire.babyClientUuid) ?: return false
        val customItemId = wire.customItemClientUuid?.let { customItemUuid ->
            customItemDao.getByClientUuid(customItemUuid)?.id ?: return false
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
        // A deterministic next-feed UUID lets the NAS choose one creator when two
        // members schedule offline. The losing local create must accept that winner;
        // Standard dirty CarePlan edits keep the creator ACL/LWW behavior.
        if (!concurrentNextFeedCreate && !forceAuthority) {
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
        // Full-set co-gate: completed + linked record must not appear without the fact.
        if (
            entity.deletedAt == null &&
            wire.status == CarePlanStatus.COMPLETED.storageKey &&
            !wire.fulfilledRecordClientUuid.isNullOrBlank()
        ) {
            if (recordDao.getByClientUuid(wire.fulfilledRecordClientUuid) == null) return false
        }
        val remotePayloadJson = SyncWireMapper.localPayloadFromWire(
            wire.type,
            wire.payload,
            customItemId,
            allowIntentOnlyFeed = isNextFeedPlanNote(wire.note),
        )
        val terminal = entity.deletedAt != null ||
            wire.status == CarePlanStatus.COMPLETED.storageKey ||
            wire.status == CarePlanStatus.SKIPPED.storageKey
        val existingTerminal = existing?.let {
            it.deletedAt != null || it.status == "completed" || it.status == "skipped"
        }
        val projectionVisibleRevision = existing != null && (
            existing.babyId != baby.id ||
                existing.type != wire.type.key ||
                existing.customItemId != customItemId ||
                existing.scheduledAt != wire.scheduledAt ||
                existing.scheduledZoneId != wire.scheduledZoneId ||
                existing.note != wire.note ||
                existing.payloadJson != remotePayloadJson ||
                existing.schemaVersion != wire.schemaVersion ||
                existingTerminal != terminal
            )
        // Provider I/O runs only after this transaction. Persist the hand-off here so
        // a crash between replica apply and the listener cannot leave a stale event
        // claiming that its reminder is ready. Terminal rows use the same marker for
        // durable cleanup only when this device has evidence of a prior side effect.
        val calendarProjectionNeedsReconciliation = terminal || projectionVisibleRevision
        val hasLocalReminderSideEffectEvidence = existing?.let {
            it.systemCalendarEventId != null ||
                it.systemCalendarReminderReady ||
                it.systemCalendarProjectionPending
        } == true
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
                systemCalendarProjectionEnabled =
                    existing?.systemCalendarProjectionEnabled ?: true,
                systemCalendarEventId = existing?.systemCalendarEventId,
                systemCalendarReminderReady = if (calendarProjectionNeedsReconciliation) {
                    false
                } else {
                    existing?.systemCalendarReminderReady ?: false
                },
                systemCalendarProjectionPending = if (calendarProjectionNeedsReconciliation) {
                    hasLocalReminderSideEffectEvidence
                } else {
                    existing?.systemCalendarProjectionPending ?: false
                },
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
     * Winner selection runs after the full page apply via [resolveFulfillmentAuthority].
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
     * Re-link [CarePlanEntity.fulfilledRecordClientUuid] to the deterministic winner
     * among local candidates and mark losers conflict-not-adopted. Does not delete
     * records or photos. Local-only (no syncDirty) so plan LWW push order cannot
     * permanently pin a non-winner on any device.
     */
    private suspend fun resolveFulfillmentAuthority(carePlanClientUuid: String) {
        val live = fulfillmentCandidateDao.listForCarePlan(carePlanClientUuid)
            .filter { it.deletedAt == null }
        if (live.isEmpty()) return
        val resolution = FulfillmentAuthority.resolve(
            live.map {
                FulfillmentCandidateEvidence(
                    clientUuid = it.clientUuid,
                    recordClientUuid = it.recordClientUuid,
                    confirmedAt = it.confirmedAt,
                    submitterRole = it.submitterRole,
                )
            },
        ) ?: return
        // Same pure patches as CareLog.resolveFulfillmentAuthorityForPlan.
        val patches = FulfillmentAuthority.adoptionStatusPatches(
            liveClientUuidToStatus = live.associate { it.clientUuid to it.adoptionStatus },
            resolution = resolution,
        )
        if (patches.isNotEmpty()) {
            val byUuid = live.associateBy { it.clientUuid }
            for ((clientUuid, status) in patches) {
                val candidate = byUuid[clientUuid] ?: continue
                fulfillmentCandidateDao.update(candidate.copy(adoptionStatus = status))
            }
        }
        val plan = carePlanDao.getByClientUuid(carePlanClientUuid) ?: return
        if (plan.deletedAt != null) return
        if (
            !FulfillmentAuthority.needsPlanRelink(
                currentStatusStorageKey = plan.status,
                currentFulfilledRecordClientUuid = plan.fulfilledRecordClientUuid,
                currentFulfilledAt = plan.fulfilledAt,
                resolution = resolution,
            )
        ) {
            return
        }
        carePlanDao.update(
            plan.copy(
                status = CarePlanStatus.COMPLETED.storageKey,
                fulfilledRecordClientUuid = resolution.winnerRecordClientUuid,
                fulfilledAt = resolution.winnerConfirmedAt,
                // Keep updatedAt/syncDirty — resolution is device-local convergence.
                updatedAt = plan.updatedAt,
                syncDirty = plan.syncDirty,
            ),
        )
    }

    /**
     * Apply a remote custom item definition with pure updated_at LWW.
     * Preserves local sortOrder (layout). Does not resurrect local layout prefs.
     */
    private suspend fun applyCustomItem(
        session: SyncSession,
        entity: SyncEntity,
        forceAuthority: Boolean = false,
    ): Boolean {
        val existing = customItemDao.getByClientUuid(entity.clientUuid)
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = parseCustomItemWire(payload)
        // Match server LWW for business fields. Equal revisions may still carry
        // the NAS-owned immutable creator acknowledgement after a push.
        if (!forceAuthority && existing != null && existing.updatedAt > entity.updatedAt) return true
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
        val wire = parseBabyWire(payload)
        // Members never own Baby LWW. The NAS snapshot wins even over an old
        // local dirty/equal revision; local appearance/order/path stay device-local.
        if (existing != null && session.role != FamilyRole.Member && !forceAuthority) {
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
                return true
            }
            // Owner LWW ties never let a different server body overwrite the
            // local body. A dirty tie remains an explicit unresolved conflict;
            // a clean tie can advance because neither side is strictly newer.
            if (existing.updatedAt == entity.updatedAt) {
                return !existing.syncDirty
            }
            // A concurrent owner edit is an explicit conflict. Do not advance the
            // pull cursor past a remote revision that was not actually applied.
            if (existing.syncDirty) return false
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
        val customItemId = wire.customItemClientUuid?.let { customItemUuid ->
            customItemDao.getByClientUuid(customItemUuid)?.id ?: return false
        }
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
            return true
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
            ),
        )
        return true
    }

    /**
     * Keep at most one open sleep per baby after sync apply. Older open
     * intervals are closed at the next open's start and flagged as anomaly so
     * sleep aggregates cannot double-count forever.
     */
    private suspend fun healDuplicateOpenSleeps(babyId: Long) {
        val opens = recordDao.listOpenSleeps(babyId)
        if (opens.size <= 1) return
        val now = clock.nowMillis()
        val decision = normalizeOpenSleeps(
            candidates = opens.map { open ->
                OpenSleepCandidate(
                    stableKey = open.clientUuid,
                    startedAtMillis = open.timestamp,
                )
            },
            repairAtMillis = now,
        )
        val byClientUuid = opens.associateBy(RecordEntity::clientUuid)
        for (closure in decision.closures) {
            val current = byClientUuid.getValue(closure.candidate.stableKey)
            val flagged = withSleepAnomaly(current.payloadJson, current.schemaVersion)
            val updatedAt = if (current.updatedAt == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                maxOf(now, current.updatedAt + 1)
            }
            recordDao.update(
                current.copy(
                    endTimestamp = closure.closedAtMillis,
                    payloadJson = flagged.first,
                    schemaVersion = flagged.second,
                    updatedAt = updatedAt,
                    syncDirty = true,
                ),
            )
        }
    }

    /**
     * A wake is family-global, not scoped to the UUID opened on one device.
     * Close every still-open sleep that began no later than the newest known
     * wake. A clock-skewed open beginning after that wake remains the single
     * residual open instead of being given an invalid negative interval.
     */
    private suspend fun healOpenSleepsClosedByFamilyWake(babyId: Long) {
        val records = recordDao.listAllIncludingDeleted()
        val latestWake = records.asSequence()
            .filter { record ->
                record.babyId == babyId &&
                    record.type == RecordType.SLEEP.key &&
                    record.deletedAt == null &&
                    record.endTimestamp != null
            }
            .maxWithOrNull(
                compareBy<RecordEntity> { it.endTimestamp ?: Long.MIN_VALUE }
                    .thenBy { it.updatedAt }
                    .thenBy { it.clientUuid },
            ) ?: return
        val wakeAt = requireNotNull(latestWake.endTimestamp)
        val now = clock.nowMillis()
        recordDao.listOpenSleeps(babyId)
            .filter { it.timestamp <= wakeAt }
            .forEach { open ->
                val flagged = withSleepAnomaly(open.payloadJson, open.schemaVersion)
                val revisionFloor = maxOf(open.updatedAt, latestWake.updatedAt, now)
                val updatedAt = if (revisionFloor == Long.MAX_VALUE) {
                    Long.MAX_VALUE
                } else {
                    revisionFloor + 1
                }
                recordDao.update(
                    open.copy(
                        endTimestamp = wakeAt,
                        payloadJson = flagged.first,
                        schemaVersion = flagged.second,
                        updatedAt = updatedAt,
                        syncDirty = true,
                    ),
                )
            }
    }

    private fun withSleepAnomaly(
        payloadJson: String,
        schemaVersion: Int,
    ): Pair<String, Int> {
        val document = RecordPayloadCodec.decode(RecordType.SLEEP, payloadJson, schemaVersion)
        val sleep = document.payload as? SleepPayload
            ?: return payloadJson to schemaVersion
        val normalized = document.copy(
            payload = sleep.copy(anomaly = true),
            schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )
        return RecordPayloadCodec.encode(normalized) to CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
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
    ) {
        transactionRunner.run {
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
                val preserveCurrentReceipt = !invalidateCurrentReceipts ||
                    (previous.role == FamilyRole.Member && media.kind == "avatar")
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
        }
    }

    private suspend fun recoverFullResync(
        previous: SyncSession,
        checkpoint: FullResyncCheckpoint,
        mediaEditGuard: LocalMediaEditGuard,
    ): SyncSession {
        resetLocalSyncReceipts(
            previous = previous,
            invalidateCurrentReceipts = true,
        )
        preferences.updateCursor(checkpoint.resetCursor, generation = checkpoint.serverGeneration)
        var current = preferences.session.first()
        current = pullAllPages(
            initial = current,
            reconcileMemberAvatars = current.role == FamilyRole.Member,
            deferCursorUntilComplete = true,
            mediaEditGuard = mediaEditGuard,
        )
        val captured = captureLocalChanges(current)
        if (captured.pendingCreatorAcknowledgements.isNotEmpty()) {
            preferences.updateCreatorAcknowledgements(
                add = captured.pendingCreatorAcknowledgements,
            )
            current = preferences.session.first()
        }
        settleAndPublish(current, captured.candidates)
        current = preferences.session.first()
        return pullAllPages(
            initial = current,
            mediaEditGuard = mediaEditGuard,
        )
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
        mediaEditGuard: LocalMediaEditGuard,
    ): SyncSession {
        var current = initial
        val authoritativeMemberAvatarPointers = if (reconcileMemberAvatars) {
            linkedMapOf<String, String?>()
        } else {
            null
        }
        var pageCount = 0
        var hasObservedFamilyName = false
        var observedFamilyName: String? = null
        do {
            require(pageCount < MAX_PULL_PAGE_COUNT) {
                "家庭服务器同步超过 $MAX_PULL_PAGE_COUNT 页上限，请稍后重试"
            }
            pageCount++
            requireRemoteAllowed(current)
            val pulled = backend.pull(current)
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
            )
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
            transactionRunner.run {
                reconcileMemberAvatarAuthority(pointers, mediaEditGuard)
            }
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
    ) {
        babyDao.listAllIncludingDeleted().forEach { baby ->
            val authoritativePointer = serverPointers[baby.clientUuid]
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

    /**
     * Freeze one same-cycle Room snapshot into atomic authority units, validate
     * the complete server result before changing Room, then apply terminal
     * dispositions. Only `publish` rows reach the existing bundle pipeline.
     */
    private suspend fun reconcileFrozenChanges(
        session: SyncSession,
        candidates: List<PublishCandidate>,
    ): AuthoritySettlement {
        if (candidates.isEmpty()) return AuthoritySettlement(emptyList(), retryCount = 0)
        val frozen = freezeAuthorityUnits(session, candidates)
        if (frozen.isEmpty()) return AuthoritySettlement(emptyList(), retryCount = 0)
        requireRemoteAllowed(session)
        val responses = frozen.chunked(MAX_AUTHORITY_RECONCILE_UNITS).map { batch ->
            backend.reconcile(session, batch.map(FrozenAuthorityUnit::draft)).also { response ->
                require(response.generation == session.pullGeneration) {
                    "家庭服务器在权威裁决期间变更了同步代际"
                }
            }
        }
        val authorityCursors = responses.map(ReconcileResult::cursor)
        if (
            authorityCursors.any { it < session.pullCursor } ||
            authorityCursors.distinct().size != 1
        ) {
            throw AuthorityProofException(
                session.pullGeneration,
                IllegalArgumentException("家庭服务器分批权威裁决游标不一致或倒退"),
            )
        }
        val results = responses.flatMap(ReconcileResult::results)
        val byKey = results.associateBy { it.type to it.clientUuid }
        require(byKey.size == frozen.size && byKey.keys == frozen.map {
            it.draft.root.type to it.draft.root.clientUuid
        }.toSet()) {
            "家庭服务器权威裁决响应不完整、重复或包含多余 key"
        }
        val resolved = frozen.map { unit -> unit to byKey.getValue(
            unit.draft.root.type to unit.draft.root.clientUuid,
        ) }
        // A newer local edit invalidates only its frozen unit. The old verdict
        // cannot clear, overwrite, localize, or publish that newer revision.
        val current = resolved.filter { (unit, result) ->
            result.requestContentHash == unit.draft.contentHash &&
                unit.candidates.all { candidateStillCurrent(it) }
        }
        val adopted = current.filter { (_, result) ->
            result.disposition == AuthorityDisposition.AdoptRemote
        }
        for ((unit, result) in adopted) {
            val entities = listOf(requireNotNull(result.remoteRoot)) + result.remoteMedia
            val remoteMediaUuids = result.remoteMedia.mapTo(mutableSetOf(), SyncEntity::clientUuid)
            val discardedMediaUuids = unit.candidates
                .filter { it.entityType == "media" && it.clientUuid !in remoteMediaUuids }
                .mapTo(mutableSetOf(), PublishCandidate::clientUuid)
            try {
                applyRemote(
                    session = session,
                    entities = entities,
                    authoritativeKeys = entities.map { it.type to it.clientUuid }.toSet(),
                    authorityCandidates = unit.candidates,
                    authorityDiscardedMediaUuids = discardedMediaUuids,
                )
            } catch (_: AuthorityCasMismatchException) {
                // A newer Room revision won after the first preflight. Keep it
                // dirty for the next cycle; staged downloads were already
                // reclaimed by applyRemote's finally block.
            }
        }
        val technicalUnits = current
            .filter { (_, result) ->
                result.disposition == AuthorityDisposition.RemoteAbsentRejected &&
                    result.reason == "redundant_tombstone"
            }
        var discardableUnits = technicalUnits.mapNotNull { (unit, _) ->
            val root = unit.candidates.singleOrNull { candidate ->
                candidate.entityType == unit.draft.root.type &&
                    candidate.clientUuid == unit.draft.root.clientUuid
            }
            if (root != null && unit.candidates.all { it.deletedAt != null }) {
                unit
            } else {
                null
            }
        }
        while (discardableUnits.isNotEmpty()) {
            val candidateKeys = discardableUnits
                .flatMap(FrozenAuthorityUnit::candidates)
                .mapTo(mutableSetOf()) { it.entityType to it.clientUuid }
            val next = mutableListOf<FrozenAuthorityUnit>()
            for (unit in discardableUnits) {
                val root = unit.candidates.single { candidate ->
                    candidate.entityType == unit.draft.root.type &&
                        candidate.clientUuid == unit.draft.root.clientUuid
                }
                if (canDiscardTechnicalTombstone(root, candidateKeys)) {
                    next += unit
                }
            }
            if (next.size == discardableUnits.size) break
            discardableUnits = next
        }
        val discardablePlanIds = discardableUnits
            .flatMap(FrozenAuthorityUnit::candidates)
            .mapTo(mutableSetOf(), PublishCandidate::planId)
        transactionRunner.run {
            current.forEach { (unit, result) ->
                when (result.disposition) {
                    AuthorityDisposition.Confirmed -> {
                        val expectedRootUpdatedAt = unit.syntheticRootExpectedUpdatedAt
                        if (expectedRootUpdatedAt != null &&
                            !acknowledgeSyntheticAuthorityRoot(unit, expectedRootUpdatedAt)
                        ) {
                            throw AuthorityCasMismatchException()
                        }
                        unit.candidates.forEach {
                            acknowledgeAuthorityCandidate(it, confirmedByServer = true)
                        }
                    }
                    AuthorityDisposition.RemoteAbsentRejected -> unit.candidates
                        .filter { candidate ->
                            result.reason != "redundant_tombstone" ||
                                candidate.planId !in discardablePlanIds
                        }
                        .forEach {
                            acknowledgeAuthorityCandidate(it, confirmedByServer = false)
                        }
                    AuthorityDisposition.Publish,
                    AuthorityDisposition.AdoptRemote,
                    AuthorityDisposition.RetryAuthority,
                    -> Unit
                }
            }
        }
        if (discardableUnits.isNotEmpty()) {
            val discardableTechnicalTombstones = discardableUnits
                .flatMap(FrozenAuthorityUnit::candidates)
                .distinctBy(PublishCandidate::planId)
            mediaFileCleanup.cleanupTombstones(
                discardableTechnicalTombstones
                    .filter { it.entityType == "media" }
                    .mapTo(mutableSetOf(), PublishCandidate::clientUuid),
            )
            transactionRunner.run {
                discardableUnits.forEach { unit ->
                    val rootKey = unit.draft.root.type to unit.draft.root.clientUuid
                    unit.candidates
                        .sortedBy { (it.entityType to it.clientUuid) == rootKey }
                        .forEach { candidate ->
                            if (discardTechnicalTombstone(candidate) != 1) {
                                throw AuthorityCasMismatchException()
                            }
                        }
                }
            }
        }
        return AuthoritySettlement(
            publishable = current
                .filter { (_, result) -> result.disposition == AuthorityDisposition.Publish }
                .flatMap { (unit, _) -> unit.candidates }
                .distinctBy(PublishCandidate::planId),
            retryCount = current.count { (_, result) ->
                result.disposition == AuthorityDisposition.RetryAuthority
            },
        )
    }

    private suspend fun freezeAuthorityUnits(
        session: SyncSession,
        candidates: List<PublishCandidate>,
    ): List<FrozenAuthorityUnit> {
        val remaining = candidates.associateByTo(linkedMapOf(), PublishCandidate::planId)
        val units = mutableListOf<FrozenAuthorityUnit>()

        // A member's pre-join facts share the local Baby's explicit local-only
        // boundary. Ask authority about that Baby once; permanent rejection
        // settles the complete local subtree without sending its care payloads.
        if (session.role == FamilyRole.Member) {
            val localBabies = babyDao.listAllIncludingDeleted().filterNot(BabyEntity::familyAuthority)
            for (baby in localBabies) {
                val subtree = remaining.values.filter { candidate ->
                    candidateBelongsToBaby(candidate, baby.id)
                }
                if (subtree.isEmpty()) continue
                val root = SyncWireMapper.baby(baby, avatarMediaUuid = null)
                units += frozenUnit(root, emptyList(), subtree)
                subtree.forEach { remaining.remove(it.planId) }
            }
        }

        val roots = remaining.values.filter { it.entityType != "media" }
        for (rootRow in roots) {
            val mediaRows = remaining.values.filter { mediaRow ->
                mediaRow.entityType == "media" && mediaBelongsToRoot(mediaRow, rootRow)
            }
            units += frozenUnit(
                root = rootRow.toAuthoritySyncEntity(),
                media = mediaRows.map { it.toAuthoritySyncEntity() },
                candidates = listOf(rootRow) + mediaRows,
            )
            remaining.remove(rootRow.planId)
            mediaRows.forEach { remaining.remove(it.planId) }
        }

        // A photo-only edit still reconciles as its synthetic atomic root, exactly
        // like the existing publisher elevates the root revision before commit.
        val leftoverMedia = remaining.values.filter { it.entityType == "media" }
        val groupedByRoot = leftoverMedia.groupBy { mediaRootKey(it) }
        val orphanPaths = mutableSetOf<String>()
        for ((rootKey, mediaRows) in groupedByRoot) {
            if (rootKey == null) {
                // No business owner means technical residue. It is terminally
                // removed only if the exact frozen revision still exists. A
                // concurrent repair wins the CAS and remains dirty.
                mediaRows.forEach { row ->
                    val capturedPath = row.localMediaUri
                    val deleted = capturedPath != null && transactionRunner.run {
                        val current = mediaDao.getByClientUuid(row.clientUuid)
                            ?: return@run false
                        if (!current.matchesPublishedRevision(
                                expectedClientUuid = row.clientUuid,
                                expectedUpdatedAt = row.updatedAt,
                                expectedLocalUri = capturedPath,
                                expectedDeletedAt = row.deletedAt,
                            ) || !current.isOwnerlessTechnicalMedia()
                        ) {
                            return@run false
                        }
                        mediaDao.deleteExactRevision(
                            clientUuid = row.clientUuid,
                            expectedUpdatedAt = row.updatedAt,
                            expectedLocalUri = capturedPath,
                            expectedDeletedAt = row.deletedAt,
                        ) == 1
                    }
                    if (deleted) {
                        remaining.remove(row.planId)
                        capturedPath.takeIf(String::isNotBlank)?.let(orphanPaths::add)
                    }
                }
                continue
            }
            val syntheticRoot = syntheticRootForMedia(rootKey, mediaRows) ?: continue
            units += frozenUnit(
                root = syntheticRoot.entity,
                media = mediaRows.map { it.toAuthoritySyncEntity() },
                candidates = mediaRows,
                syntheticRootExpectedUpdatedAt = syntheticRoot.expectedLocalUpdatedAt,
            )
            mediaRows.forEach { remaining.remove(it.planId) }
        }
        mediaFileCleanup.cleanupUnreferencedPaths(orphanPaths)
        return units
    }

    private fun frozenUnit(
        root: SyncEntity,
        media: List<SyncEntity>,
        candidates: List<PublishCandidate>,
        syntheticRootExpectedUpdatedAt: Long? = null,
    ): FrozenAuthorityUnit = FrozenAuthorityUnit(
        draft = ReconcileUnitDraft(
            contentHash = authorityContentHash(root, media),
            root = root,
            media = media.sortedBy(SyncEntity::clientUuid),
        ),
        candidates = candidates,
        syntheticRootExpectedUpdatedAt = syntheticRootExpectedUpdatedAt,
    )

    private fun PublishCandidate.toAuthoritySyncEntity(): SyncEntity = SyncEntity(
        type = entityType,
        clientUuid = clientUuid,
        payloadJson = payloadJson,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

    private fun authorityContentHash(root: SyncEntity, media: List<SyncEntity>): String {
        val source = buildString {
            fun appendEntity(entity: SyncEntity) {
                append(entity.type).append('\u0000')
                append(entity.clientUuid).append('\u0000')
                append(entity.updatedAt).append('\u0000')
                append(entity.deletedAt ?: "-").append('\u0000')
                append(Json.parseToJsonElement(entity.payloadJson).toString()).append('\u0000')
            }
            appendEntity(root)
            media.sortedBy(SyncEntity::clientUuid).forEach(::appendEntity)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun candidateBelongsToBaby(candidate: PublishCandidate, babyId: Long): Boolean =
        when (candidate.entityType) {
            "baby" -> babyDao.getByClientUuid(candidate.clientUuid)?.id == babyId
            "record" -> recordDao.getByClientUuid(candidate.clientUuid)?.babyId == babyId
            "care_plan" -> carePlanDao.getByClientUuid(candidate.clientUuid)?.babyId == babyId
            "fulfillment_candidate" -> fulfillmentCandidateDao
                .getByClientUuid(candidate.clientUuid)
                ?.let { carePlanDao.getByClientUuid(it.carePlanClientUuid)?.babyId } == babyId
            "media" -> mediaDao.getByClientUuid(candidate.clientUuid)?.let { media ->
                media.babyId == babyId ||
                    media.recordId?.let { recordDao.getIncludingDeleted(it)?.babyId } == babyId ||
                    media.carePlanId?.let { carePlanDao.get(it)?.babyId } == babyId
            } == true
            else -> false
        }

    private fun mediaBelongsToRoot(
        media: PublishCandidate,
        root: PublishCandidate,
    ): Boolean = runCatching {
        val payload = Json.parseToJsonElement(media.payloadJson).jsonObject
        when (root.entityType) {
            "record" -> payload.string("record_client_uuid") == root.clientUuid
            "care_plan" -> payload.string("care_plan_client_uuid") == root.clientUuid
            "baby" -> payload.string("baby_client_uuid") == root.clientUuid &&
                payload.string("kind") == "avatar"
            else -> false
        }
    }.getOrDefault(false)

    private suspend fun mediaRootKey(row: PublishCandidate): Pair<String, Long>? {
        val media = mediaDao.getByClientUuid(row.clientUuid) ?: return null
        val recordId = media.recordId
        val carePlanId = media.carePlanId
        val babyId = media.babyId
        return when {
            recordId != null -> "record" to recordId
            carePlanId != null -> "care_plan" to carePlanId
            babyId != null && media.kind == "avatar" -> "baby" to babyId
            else -> null
        }
    }

    private suspend fun syntheticRootForMedia(
        key: Pair<String, Long>,
        rows: List<PublishCandidate>,
    ): SyntheticAuthorityRoot? {
        val mediaRevision = rows.maxOf(PublishCandidate::updatedAt)
        return when (key.first) {
            "record" -> recordDao.getIncludingDeleted(key.second)?.let { record ->
                val baby = babyDao.getIncludingDeleted(record.babyId) ?: return null
                SyncWireMapper.record(
                    record,
                    baby.clientUuid,
                    recordCustomItemClientUuid(record),
                ).copy(
                    updatedAt = maxOf(nextAuthorityPackageVersion(record.updatedAt), mediaRevision),
                ).let { SyntheticAuthorityRoot(it, record.updatedAt) }
            }
            "care_plan" -> carePlanDao.get(key.second)?.let { plan ->
                val baby = babyDao.getIncludingDeleted(plan.babyId) ?: return null
                SyncWireMapper.carePlan(
                    plan,
                    baby.clientUuid,
                    plan.customItemId?.let { customItemDao.getById(it)?.clientUuid },
                ).copy(
                    updatedAt = maxOf(nextAuthorityPackageVersion(plan.updatedAt), mediaRevision),
                ).let { SyntheticAuthorityRoot(it, plan.updatedAt) }
            }
            "baby" -> babyDao.getIncludingDeleted(key.second)?.let { baby ->
                SyncWireMapper.baby(
                    baby,
                    avatarMediaUuid = if (baby.deletedAt == null) {
                        rows.maxByOrNull(PublishCandidate::updatedAt)?.clientUuid
                    } else {
                        null
                    },
                ).copy(
                    updatedAt = maxOf(nextAuthorityPackageVersion(baby.updatedAt), mediaRevision),
                ).let { SyntheticAuthorityRoot(it, baby.updatedAt) }
            }
            else -> null
        }
    }

    private suspend fun acknowledgeSyntheticAuthorityRoot(
        unit: FrozenAuthorityUnit,
        expectedLocalUpdatedAt: Long,
    ): Boolean = when (unit.draft.root.type) {
        "baby" -> babyDao.acknowledgeSyntheticRootPublication(
            clientUuid = unit.draft.root.clientUuid,
            expectedLocalUpdatedAt = expectedLocalUpdatedAt,
            publishedUpdatedAt = unit.draft.root.updatedAt,
        )
        "record" -> recordDao.acknowledgeSyntheticRootPublication(
            clientUuid = unit.draft.root.clientUuid,
            expectedLocalUpdatedAt = expectedLocalUpdatedAt,
            publishedUpdatedAt = unit.draft.root.updatedAt,
        )
        "care_plan" -> carePlanDao.acknowledgeSyntheticRootPublication(
            clientUuid = unit.draft.root.clientUuid,
            expectedLocalUpdatedAt = expectedLocalUpdatedAt,
            publishedUpdatedAt = unit.draft.root.updatedAt,
        )
        else -> false
    }

    private fun nextAuthorityPackageVersion(updatedAt: Long): Long =
        if (updatedAt == Long.MAX_VALUE) Long.MAX_VALUE else updatedAt + 1L

    private suspend fun candidateStillCurrent(candidate: PublishCandidate): Boolean = when (
        candidate.entityType
    ) {
        "baby" -> babyDao.getByClientUuid(candidate.clientUuid)?.let {
            it.updatedAt == candidate.updatedAt && it.syncDirty
        } == true
        "record" -> recordDao.getByClientUuid(candidate.clientUuid)?.let {
            it.updatedAt == candidate.updatedAt && it.syncDirty
        } == true
        "care_plan" -> carePlanDao.getByClientUuid(candidate.clientUuid)?.let {
            it.updatedAt == candidate.updatedAt && it.syncDirty
        } == true
        "custom_item" -> customItemDao.getByClientUuid(candidate.clientUuid)?.let {
            it.updatedAt == candidate.updatedAt && it.syncDirty
        } == true
        "fulfillment_candidate" -> fulfillmentCandidateDao
            .getByClientUuid(candidate.clientUuid)?.let {
                it.updatedAt == candidate.updatedAt && it.syncDirty
            } == true
        "media" -> mediaDao.getByClientUuid(candidate.clientUuid)?.let {
            it.updatedAt == candidate.updatedAt
        } == true
        else -> false
    }

    private suspend fun acknowledgeAuthorityCandidate(
        candidate: PublishCandidate,
        confirmedByServer: Boolean,
    ) {
        when (candidate.entityType) {
            "baby" -> babyDao.markSynced(candidate.clientUuid, candidate.updatedAt)
            "record" -> if (confirmedByServer) {
                recordDao.acknowledgeFamilyPublishedVersion(candidate.clientUuid, candidate.updatedAt)
            } else {
                recordDao.markSynced(candidate.clientUuid, candidate.updatedAt)
            }
            "care_plan" -> if (confirmedByServer) {
                carePlanDao.acknowledgeFamilyPublishedVersion(candidate.clientUuid, candidate.updatedAt)
            } else {
                carePlanDao.markSynced(candidate.clientUuid, candidate.updatedAt)
            }
            "custom_item" -> customItemDao.markSynced(candidate.clientUuid, candidate.updatedAt)
            "fulfillment_candidate" ->
                fulfillmentCandidateDao.markSynced(candidate.clientUuid, candidate.updatedAt)
            "media" -> mediaDao.markSynced(candidate.clientUuid, candidate.updatedAt)
        }
    }

    private suspend fun discardTechnicalTombstone(candidate: PublishCandidate): Int {
        if (candidate.deletedAt == null) return 0
        return when (candidate.entityType) {
            "baby" -> babyDao.deleteTombstoneRevision(candidate.clientUuid, candidate.updatedAt)
            "record" -> recordDao.deleteTombstoneRevision(candidate.clientUuid, candidate.updatedAt)
            "care_plan" ->
                carePlanDao.deleteTombstoneRevision(candidate.clientUuid, candidate.updatedAt)
            "custom_item" ->
                customItemDao.deleteTombstoneRevision(candidate.clientUuid, candidate.updatedAt)
            "fulfillment_candidate" -> fulfillmentCandidateDao.deleteTombstoneRevision(
                candidate.clientUuid,
                candidate.updatedAt,
            )
            "media" -> mediaDao.deleteTombstoneRevision(candidate.clientUuid, candidate.updatedAt)
            else -> 0
        }
    }

    private fun MediaAssetEntity.isOwnerlessTechnicalMedia(): Boolean = when (kind) {
        "avatar" -> babyId == null
        "log" -> (recordId == null) == (carePlanId == null)
        else -> true
    }

    private suspend fun canDiscardTechnicalTombstone(
        candidate: PublishCandidate,
        technicalKeys: Set<Pair<String, String>>,
    ): Boolean {
        fun Iterable<Pair<String, String>>.allCovered(): Boolean = all { it in technicalKeys }
        return when (candidate.entityType) {
            "baby" -> babyDao.getByClientUuid(candidate.clientUuid)?.let { baby ->
                buildList {
                    addAll(
                        recordDao.listAllIncludingDeleted()
                            .filter { it.babyId == baby.id }
                            .map { "record" to it.clientUuid },
                    )
                    addAll(
                        carePlanDao.listAllIncludingDeleted()
                            .filter { it.babyId == baby.id }
                            .map { "care_plan" to it.clientUuid },
                    )
                    addAll(
                        mediaDao.listAllIncludingDeleted()
                            .filter { it.babyId == baby.id }
                            .map { "media" to it.clientUuid },
                    )
                }.allCovered()
            } ?: false
            "record" -> recordDao.getByClientUuid(candidate.clientUuid)?.let { record ->
                buildList {
                    addAll(
                        mediaDao.listForRecord(record.id).map { "media" to it.clientUuid },
                    )
                    addAll(
                        fulfillmentCandidateDao.listForRecord(record.clientUuid)
                            .map { "fulfillment_candidate" to it.clientUuid },
                    )
                    addAll(
                        carePlanDao.listAllIncludingDeleted()
                            .filter {
                                it.sourceRecordClientUuid == record.clientUuid ||
                                    it.fulfilledRecordClientUuid == record.clientUuid
                            }
                            .map { "care_plan" to it.clientUuid },
                    )
                }.allCovered()
            } ?: false
            "care_plan" -> carePlanDao.getByClientUuid(candidate.clientUuid)?.let { plan ->
                (
                    mediaDao.listForCarePlan(plan.id).map { "media" to it.clientUuid } +
                        fulfillmentCandidateDao.listForCarePlan(plan.clientUuid)
                            .map { "fulfillment_candidate" to it.clientUuid }
                    ).allCovered()
            } ?: false
            "custom_item" -> customItemDao.getByClientUuid(candidate.clientUuid)?.let { item ->
                (
                    recordDao.listAllIncludingDeleted()
                        .filter { recordCustomItemClientUuid(it) == item.clientUuid }
                        .map { "record" to it.clientUuid } +
                        carePlanDao.listAllIncludingDeleted()
                            .filter { it.customItemId == item.id }
                            .map { "care_plan" to it.clientUuid }
                    ).allCovered()
            } ?: false
            "fulfillment_candidate", "media" -> true
            else -> false
        }
    }

    private suspend fun captureLocalChanges(
        session: SyncSession,
    ): CapturedLocalChanges {
        repairTechnicalMediaBeforeCapture(session)
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
        val fulfillmentCandidates = fulfillmentCandidateDao.listPendingSync()
        val capturedPendingCreatorAcknowledgements = mutableSetOf<CreatorAcknowledgementRef>()
        materializeLocalMedia(
            includeAvatars = session.role != FamilyRole.Member,
            babies = babySnapshots,
        )
        // Avatar inspection can suspend. Re-read every pending Baby before
        // materializing its plan row so a concurrent profile edit is either
        // packaged as one current epoch or rejected later by the push CAS.
        val babies = babyDao.listPendingSync()
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
        }
        val media = requirePortableMediaUuids(
            (directlyChangedMedia + referencedMedia).distinctBy(MediaAssetEntity::id),
        )
        babies.forEach { baby ->
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
     * dispositions before the authority snapshot is frozen.
     *
     * Missing bytes never delete their Record/CarePlan. A never-published
     * attachment becomes an atomic media tombstone, while a media row with a
     * current server receipt drops only its broken local path and lets the NAS
     * copy remain authoritative. Rows with no business owner are safe to hard
     * delete; their paths are reclaimed through the reference-aware file gate.
     */
    private suspend fun repairTechnicalMediaBeforeCapture(session: SyncSession) {
        val orphanPaths = mutableSetOf<String>()
        for (snapshot in mediaDao.listPendingSync()) {
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
                    "log" -> (record != null) xor (carePlan != null)
                    "avatar" -> baby != null && current.recordId == null &&
                        current.carePlanId == null
                    else -> false
                }
                if (!validOwner) {
                    orphanPaths += current.localUri
                    mediaDao.deleteByClientUuids(listOf(current.clientUuid))
                    return@run
                }
                val invalidDeletedBabyAvatar = current.kind == "avatar" && baby?.deletedAt != null
                val missingLocalBytes = current.localUri.isBlank() || inspected == null
                if (!invalidDeletedBabyAvatar && !missingLocalBytes) return@run

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

private data class BabyWire(
    val nickname: String,
    val sex: String?,
    val birthdayEpochDay: Long,
    val birthWeightGrams: Int?,
    val avatarMediaUuid: String?,
)

private data class CustomItemWire(
    val name: String,
    val iconSlot: Int,
    val createdByMembershipId: String,
)

private data class RecordWire(
    val babyClientUuid: String,
    val createdByMembershipId: String,
    val type: RecordType,
    val customItemClientUuid: String?,
    val timestamp: Long,
    val endTimestamp: Long?,
    val note: String?,
    val payload: JsonObject,
    val schemaVersion: Int,
)

private data class CarePlanWire(
    val babyClientUuid: String,
    val type: RecordType,
    val customItemClientUuid: String?,
    val scheduledAt: Long,
    val scheduledZoneId: String,
    val note: String?,
    val payload: JsonObject,
    val schemaVersion: Int,
    val status: String,
    val createdByMembershipId: String,
    val fulfilledRecordClientUuid: String?,
    val fulfilledAt: Long?,
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

private fun parseBabyWire(payload: JsonObject): BabyWire {
    payload.requireExactKeys(
        "baby",
        "nickname",
        "sex",
        "birthday",
        "birth_weight_grams",
        "avatar_media_uuid",
    )
    val nickname = payload.requireNonBlankString("nickname", "baby").trim()
    require(limitBabyNicknameInput(nickname) == nickname) { "baby nickname 超出 current 限制" }
    val sex = payload.requireNullableString("sex", "baby")
    require(sex == null || sex == "female" || sex == "male") { "baby sex 无效" }
    val birthWeight = payload.requireNullableLong("birth_weight_grams", "baby")
    require(birthWeight == null || birthWeight in 0..100_000) {
        "baby birth_weight_grams 无效"
    }
    val avatar = payload.requireNullableString("avatar_media_uuid", "baby")
    avatar?.let { requireCanonicalUuid(it, "baby avatar_media_uuid") }
    return BabyWire(
        nickname = nickname,
        sex = sex,
        birthdayEpochDay = SyncWireMapper.birthdayEpochDay(payload),
        birthWeightGrams = birthWeight?.toInt(),
        avatarMediaUuid = avatar,
    )
}

private fun parseCustomItemWire(payload: JsonObject): CustomItemWire {
    payload.requireExactKeys(
        "custom_item",
        "name",
        "icon_slot",
        "created_by_membership_id",
    )
    val iconSlot = payload.requireLong("icon_slot", "custom_item")
    require(iconSlot in 0..7) { "custom_item icon_slot 无效" }
    return CustomItemWire(
        name = payload.requireNonBlankString("name", "custom_item").trim(),
        iconSlot = iconSlot.toInt(),
        createdByMembershipId = payload.requireNullableString(
            "created_by_membership_id",
            "custom_item",
        ).orEmpty().trim(),
    )
}

private fun parseRecordWire(payload: JsonObject): RecordWire {
    payload.requireExactKeys(
        "record",
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
    val endTimestamp = payload.requireNullableLong("end_timestamp", "record")
    require(endTimestamp == null || endTimestamp >= timestamp) { "record end_timestamp 无效" }
    val nested = payload.requireObject("payload_json", "record")
    require("photos" !in nested && "custom_item_id" !in nested) {
        "record payload_json 包含设备本地字段"
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
    )
}

private fun parseCarePlanWire(payload: JsonObject): CarePlanWire {
    payload.requireExactKeys(
        "care_plan",
        "baby_client_uuid",
        "type",
        "custom_item_client_uuid",
        "scheduled_at",
        "scheduled_zone_id",
        "note",
        "payload_json",
        "schema_version",
        "status",
        "created_by_membership_id",
        "fulfilled_record_client_uuid",
        "fulfilled_at",
    )
    val type = SyncWireMapper.requireCurrentRecordType(
        payload.requireNonBlankString("type", "care_plan"),
        "care plan type",
    )
    val customItemUuid = payload.requireNullableString("custom_item_client_uuid", "care_plan")
    require((type == RecordType.CUSTOM) == (customItemUuid != null)) {
        if (type == RecordType.CUSTOM) {
            "care plan type custom requires custom_item_client_uuid"
        } else {
            "care plan custom_item_client_uuid is only valid for type custom"
        }
    }
    val zone = payload.requireNonBlankString("scheduled_zone_id", "care_plan")
    require(runCatching { ZoneId.of(zone) }.isSuccess) { "care plan scheduled_zone_id 无效" }
    val status = payload.requireNonBlankString("status", "care_plan")
    require(status in CarePlanStatus.entries.map(CarePlanStatus::storageKey)) {
        "care plan status 无效"
    }
    val nested = payload.requireObject("payload_json", "care_plan")
    require("photos" !in nested && "custom_item_id" !in nested) {
        "care plan payload_json 包含设备本地字段"
    }
    val scheduledAt = payload.requireLong("scheduled_at", "care_plan")
    require(scheduledAt >= 0) { "care plan scheduled_at 无效" }
    val fulfilledAt = payload.requireNullableLong("fulfilled_at", "care_plan")
    require(fulfilledAt == null || fulfilledAt >= 0) { "care plan fulfilled_at 无效" }
    return CarePlanWire(
        babyClientUuid = payload.requireNonBlankString("baby_client_uuid", "care_plan"),
        type = type,
        customItemClientUuid = customItemUuid,
        scheduledAt = scheduledAt,
        scheduledZoneId = zone,
        note = payload.requireNullableString("note", "care_plan"),
        payload = nested,
        schemaVersion = SyncWireMapper.carePlanSchemaVersion(payload),
        status = status,
        createdByMembershipId = payload.requireNullableString(
            "created_by_membership_id",
            "care_plan",
        ).orEmpty().trim(),
        fulfilledRecordClientUuid = payload.requireNullableString(
            "fulfilled_record_client_uuid",
            "care_plan",
        ),
        fulfilledAt = fulfilledAt,
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
            "authority_response_too_large",
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

private fun SyncSession.requireCurrentReplicaSession() {
    require(isJoined) { "当前同步会话尚未加入家庭" }
    require(deviceId.isNotBlank()) { "当前同步会话缺少 device_id" }
    require(pullGeneration.isNotBlank()) { "当前同步会话缺少 generation" }
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
    "care_plan",
    "media",
    "fulfillment_candidate",
)
private val CURRENT_ENTITY_TYPES = ENTITY_ORDER.toSet()
internal const val PUSH_ROOT_BATCH_SIZE = 200
internal const val MAX_PUSH_BATCH_SIZE = 1_000
/** Normal home libraries are far smaller; reaching this many pages is anomalous. */
private const val MAX_PULL_PAGE_COUNT = 500
private const val MAX_AUTHORITY_RECONCILE_UNITS = 64
private const val MAX_AUTHORITY_SETTLEMENT_PASSES = 8
private const val SYNC_PULL_PAGE_ENTITY_LIMIT = 200
