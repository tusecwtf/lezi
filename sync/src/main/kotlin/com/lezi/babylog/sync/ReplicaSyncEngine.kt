package com.lezi.babylog.sync

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
import com.lezi.babylog.core.model.NEXT_FEED_PLAN_MARKER
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.OutboxDao
import com.lezi.babylog.core.database.OutboxEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SleepPayload
import com.lezi.babylog.core.model.limitBabyNicknameInput
import java.time.ZoneId
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

internal object AtomicBundleId {
    private const val NAMESPACE = "lezi.atomic-bundle.v1"

    fun forRecord(recordClientUuid: String, updatedAt: Long): String =
        fromRoot("record", recordClientUuid, updatedAt)

    fun forCarePlan(planClientUuid: String, updatedAt: Long): String =
        fromRoot("care_plan", planClientUuid, updatedAt)

    private fun fromRoot(rootType: String, clientUuid: String, updatedAt: Long): String =
        UUID.nameUUIDFromBytes(
            "$NAMESPACE:$rootType:$clientUuid:$updatedAt".toByteArray(Charsets.UTF_8),
        ).toString()
}

internal sealed interface ReplicaSyncOutcome {
    data object Synchronized : ReplicaSyncOutcome
}

/**
 * Owns one complete foreground replica cycle behind a single interface.
 *
 * The caller supplies a joined session and trigger. Local changes are snapshotted
 * before the remote gate is evaluated so offline writes remain durable. Every
 * remote page and media retry re-enters the same gate. Failures and cancellation
 * escape without being translated, leaving the last durable checkpoint intact.
 */
internal class ReplicaSyncEngine(
    private val backend: SyncBackend,
    private val preferences: SyncPreferences,
    private val outboxDao: OutboxDao,
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val babyDao: BabyDao,
    private val mediaDao: MediaAssetDao,
    private val customItemDao: CustomItemDao,
    private val familyDao: FamilyDao,
    private val clock: PolicyClock,
    private val mediaFiles: SyncMediaFileStore,
    private val transactionRunner: DatabaseTransactionRunner,
    private val carePlanAppliedListener: CarePlanFamilyAppliedListener,
    private val familyBabyAppliedListener: FamilyBabyAuthorityAppliedListener =
        NoOpFamilyBabyAuthorityAppliedListener(),
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val requireRemoteAllowed: suspend (SyncSession) -> Unit,
) : FamilySessionReplica {
    private val outboxPush = OutboxPushPipeline(
        backend = backend,
        outboxDao = outboxDao,
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
        cleanupPendingTombstonedMedia()
        val capturedPendingCreatorAcknowledgements = captureLocalChanges(session)
        val mediaEditGuard = captureLocalMediaEditGuard()
        val plan = SyncPlan.forTrigger(trigger)
        var current = preferences.session.first()
        // The home-network gate refreshes health capabilities. Reading them
        // before this call would lose first-cycle creator acknowledgement intent.
        requireRemoteAllowed(current)
        if (capturedPendingCreatorAcknowledgements.isNotEmpty()) {
            preferences.updateCreatorAcknowledgements(
                add = capturedPendingCreatorAcknowledgements,
            )
            current = preferences.session.first()
        }
        current = convergeAuthenticatedSelfMembership(
            current,
            backend.members(current),
        )
        var recovered = false
        val requiresCreatorAcknowledgementPull =
            current.pendingCreatorAcknowledgements.isNotEmpty()
        val memberPullFirst = current.role == FamilyRole.Member &&
            (plan.pull || requiresCreatorAcknowledgementPull)
        if (memberPullFirst) {
            try {
                current = pullAllPages(current, mediaEditGuard = mediaEditGuard)
            } catch (error: SyncHttpException) {
                val checkpoint = error.fullResyncCheckpointOrNull() ?: throw error
                current = recoverFullResync(current, checkpoint, mediaEditGuard)
                recovered = true
            }
        }
        if (plan.push && !recovered && !memberPullFirst) {
            try {
                pushPending(current)
            } catch (error: SyncHttpException) {
                val checkpoint = error.fullResyncCheckpointOrNull() ?: throw error
                current = recoverFullResync(current, checkpoint, mediaEditGuard)
                recovered = true
            }
        }
        // Exact local provenance is durable across process death. It only
        // schedules an authoritative acknowledgement pull and never supplies a
        // creator membership value of its own.
        if ((plan.pull || requiresCreatorAcknowledgementPull) && !recovered && !memberPullFirst) {
            try {
                current = pullAllPages(
                    initial = current,
                    mediaEditGuard = mediaEditGuard,
                )
            } catch (error: SyncHttpException) {
                val checkpoint = error.fullResyncCheckpointOrNull() ?: throw error
                current = recoverFullResync(current, checkpoint, mediaEditGuard)
            }
        }
        if (memberPullFirst && plan.push && !recovered) {
            val afterAuthorityCapture = captureLocalChanges(current)
            if (afterAuthorityCapture.isNotEmpty()) {
                preferences.updateCreatorAcknowledgements(add = afterAuthorityCapture)
                current = preferences.session.first()
            }
            try {
                pushPending(current)
            } catch (error: SyncHttpException) {
                val checkpoint = error.fullResyncCheckpointOrNull() ?: throw error
                current = recoverFullResync(current, checkpoint, mediaEditGuard)
                recovered = true
            }
            if (afterAuthorityCapture.isNotEmpty() && !recovered) {
                pullAllPages(current, mediaEditGuard = mediaEditGuard)
            }
        }
        return ReplicaSyncOutcome.Synchronized
    }

    override suspend fun applyInitialEntities(
        session: SyncSession,
        entities: List<SyncEntity>,
    ) {
        session.requireCurrentReplicaSession()
        cleanupPendingTombstonedMedia()
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

    private suspend fun pushPending(session: SyncSession) {
        outboxPush.pushPending(session)
    }

    private suspend fun applyRemote(
        session: SyncSession,
        entities: List<SyncEntity>,
        mediaEditGuard: LocalMediaEditGuard? = null,
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
            val unresolved = mutableListOf<SyncEntity>()
            for (entity in entities.filter { it.type == "baby" }) {
                if (!applyBaby(session, entity)) unresolved += entity
            }
            for (entity in entities.filter { it.type == "custom_item" }) {
                if (!applyCustomItem(session, entity)) unresolved += entity
            }
            // Fulfillment full-set: record(+photos) before completed care_plan before
            // fulfillment_candidate. Incomplete sets leave cursor unmoved (unresolved).
            for (entity in entities.filter { it.type == "record" }) {
                if (!applyRecord(entity)) unresolved += entity
            }
            for (entity in entities.filter { it.type == "care_plan" }) {
                val applied = applyCarePlan(session, entity, discardedLocalMediaPaths)
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
                    )
                ) {
                    unresolved += entity
                }
            }
            for (entity in entities.filter { it.type == "fulfillment_candidate" }) {
                if (!applyFulfillmentCandidate(entity)) unresolved += entity
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
            entities.filter { it.type == "record" }
                .mapNotNull { entity -> recordDao.getByClientUuid(entity.clientUuid)?.babyId }
                .distinct()
                .forEach { healDuplicateOpenSleeps(it) }
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
        cleanupPendingTombstonedMedia(deletedMediaClientUuids.toSet())
        cleanupDiscardedLocalMedia(discardedLocalMediaPaths)
        // Side effects only after full package apply — never during partial download.
        if (appliedCarePlanUuids.isNotEmpty()) {
            carePlanAppliedListener.onFamilyCarePlansApplied(appliedCarePlanUuids.distinct())
        }
        check(session.isJoined)
    }

    private suspend fun cleanupUnownedStagedMedia(paths: Set<String>) {
        if (paths.isEmpty()) return
        val owned = mediaDao.listAllIncludingDeleted()
            .mapTo(hashSetOf(), MediaAssetEntity::localUri)
        paths.filterNot(owned::contains).forEach { mediaFiles.delete(it) }
    }

    private suspend fun cleanupDiscardedLocalMedia(paths: List<String>) {
        if (paths.isEmpty()) return
        val owned = mediaDao.listAllIncludingDeleted()
            .filter { it.deletedAt == null }
            .mapTo(hashSetOf(), MediaAssetEntity::localUri)
        paths.filter(String::isNotBlank)
            .distinct()
            .filterNot(owned::contains)
            .forEach { mediaFiles.delete(it) }
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
    ): Boolean {
        val existing = carePlanDao.getByClientUuid(entity.clientUuid)
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = parseCarePlanWire(payload)
        val baby = babyDao.getByClientUuid(wire.babyClientUuid) ?: return false
        val customItemId = wire.customItemClientUuid?.let { customItemUuid ->
            customItemDao.getByClientUuid(customItemUuid)?.id ?: return false
        }
        val concurrentNextFeedCreate = existing != null &&
            (
                existing.syncDirty ||
                    session.isCreatorAcknowledgementPending("care_plan", entity.clientUuid)
            ) &&
            existing.note?.startsWith(NEXT_FEED_PLAN_MARKER) == true &&
            wire.note?.startsWith(NEXT_FEED_PLAN_MARKER) == true &&
            existing.createdByMembershipId.isNotBlank() &&
            existing.createdByMembershipId != wire.createdByMembershipId
        // A deterministic next-feed UUID lets the NAS choose one creator when two
        // members schedule offline. The losing local create must accept that winner;
        // ordinary dirty CarePlan edits keep the standard creator ACL/LWW behavior.
        if (!concurrentNextFeedCreate) {
            // Match server LWW for business fields. Equal revisions may still carry
            // the NAS-owned immutable creator acknowledgement after a push.
            if (existing != null && existing.updatedAt > entity.updatedAt) return true
            if (existing != null && existing.updatedAt == entity.updatedAt) {
                acknowledgedEqualRevisionCreator(
                    session = session,
                    existingCreator = existing.createdByMembershipId,
                    payloadJson = entity.payloadJson,
                )?.let { creator ->
                    carePlanDao.update(existing.copy(createdByMembershipId = creator))
                }
                return true
            }
            // Keep in-flight local create/edit until push commits.
            if (existing != null && existing.syncDirty) return true
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
            allowIntentOnlyFeed = wire.note?.startsWith(NEXT_FEED_PLAN_MARKER) == true,
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
                outboxDao.deleteEntities(session.familyId, "media", losingMediaUuids)
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
        if (concurrentNextFeedCreate) {
            outboxDao.deleteEntities(session.familyId, "care_plan", listOf(entity.clientUuid))
        }
        return true
    }

    /**
     * Apply a remote fulfillment candidate. Requires plan + record to already be
     * local so the candidate is never the sole visible half of a fulfill result.
     * Winner selection runs after the full page apply via [resolveFulfillmentAuthority].
     */
    private suspend fun applyFulfillmentCandidate(entity: SyncEntity): Boolean {
        val existing = fulfillmentCandidateDao.getByClientUuid(entity.clientUuid)
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = parseFulfillmentCandidateWire(payload)
        // Keep in-flight local dirty until push commits (then markSynced clears dirty).
        if (existing != null && existing.syncDirty) return true
        // Full-set: plan and record must already be applied (or present).
        if (carePlanDao.getByClientUuid(wire.carePlanClientUuid) == null) return false
        if (recordDao.getByClientUuid(wire.recordClientUuid) == null) return false
        // Ticket 26 multi-device convergence: originators keep equal/higher updatedAt
        // after markSynced, but must still adopt server-frozen stamps (role/membership/
        // confirmed_at) so every device adjudicates with the same evidence.
        if (existing != null && existing.updatedAt >= entity.updatedAt) {
            val needsStampMerge =
                existing.submitterRole != wire.submitterRole ||
                    existing.submitterMembershipId != wire.submitterMembershipId ||
                    existing.confirmedAt != wire.confirmedAt
            if (!needsStampMerge) return true
            fulfillmentCandidateDao.update(
                existing.copy(
                    confirmedAt = wire.confirmedAt,
                    submitterMembershipId = wire.submitterMembershipId,
                    submitterRole = wire.submitterRole,
                    updatedAt = maxOf(existing.updatedAt, entity.updatedAt),
                    deletedAt = entity.deletedAt ?: existing.deletedAt,
                    syncDirty = false,
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
    ): Boolean {
        val existing = customItemDao.getByClientUuid(entity.clientUuid)
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = parseCustomItemWire(payload)
        // Match server LWW for business fields. Equal revisions may still carry
        // the NAS-owned immutable creator acknowledgement after a push.
        if (existing != null && existing.updatedAt > entity.updatedAt) return true
        if (existing != null && existing.updatedAt == entity.updatedAt) {
            acknowledgedEqualRevisionCreator(
                session = session,
                existingCreator = existing.createdByMembershipId,
                payloadJson = entity.payloadJson,
            )?.let { creator ->
                customItemDao.update(existing.copy(createdByMembershipId = creator))
            }
            return true
        }
        // Keep in-flight local rename/delete until push commits.
        if (existing != null && existing.syncDirty) return true
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
        val canonicalSelf = session.membershipId.trim()
        return existingCreator
            ?.takeIf { canonicalSelf.isNotEmpty() && it == canonicalSelf }
            ?: remoteCreator
            ?: existingCreator
            ?: ""
    }

    private suspend fun applyBaby(session: SyncSession, entity: SyncEntity): Boolean {
        val existing = babyDao.getByClientUuid(entity.clientUuid)
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = parseBabyWire(payload)
        // Members never own Baby LWW. The NAS snapshot wins even over an old
        // local dirty/equal revision; local appearance/order/path stay device-local.
        if (
            existing != null &&
            existing.updatedAt >= entity.updatedAt &&
            session.role != FamilyRole.Member
        ) {
            return true
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

    private suspend fun applyRecord(entity: SyncEntity): Boolean {
        val existing = recordDao.getByClientUuid(entity.clientUuid)
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = parseRecordWire(payload)
        val customItemId = wire.customItemClientUuid?.let { customItemUuid ->
            customItemDao.getByClientUuid(customItemUuid)?.id ?: return false
        }
        // Match server LWW for business fields. Equal revisions may still carry
        // a server-owned author metadata acknowledgement from the current server.
        if (existing != null && existing.updatedAt > entity.updatedAt) return true
        if (existing != null && existing.updatedAt == entity.updatedAt) {
            recordDao.mergeCanonicalAuthor(
                clientUuid = entity.clientUuid,
                expectedUpdatedAt = entity.updatedAt,
                membershipId = wire.createdByMembershipId,
            )
            return true
        }
        // Concurrent local dirty mutation: never clobber the in-flight package or
        // clear syncDirty mid-edit. Creator keeps the local complete revision until
        // push commits; receivers still see the prior published package.
        if (existing != null && existing.syncDirty) return true
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
        // listOpenSleeps is newest-first; keep the latest open, close the rest.
        val keep = opens.first()
        val stale = opens.drop(1).sortedWith(
            compareBy<RecordEntity> { it.timestamp }.thenBy { it.id },
        )
        val chain = stale + keep
        val now = clock.nowMillis()
        for (index in 0 until chain.lastIndex) {
            val current = chain[index]
            val nextStart = chain[index + 1].timestamp
            val end = if (nextStart > current.timestamp) {
                nextStart
            } else {
                current.timestamp + 60_000L
            }
            val flagged = withSleepAnomaly(current.payloadJson, current.schemaVersion)
            val updatedAt = if (current.updatedAt == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                maxOf(now, current.updatedAt + 1)
            }
            recordDao.update(
                current.copy(
                    endTimestamp = end,
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
    ): Boolean {
        requireCanonicalUuid(entity.clientUuid, "media client_uuid")
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = parseMediaWire(payload)
        val existing = mediaDao.getByClientUuid(entity.clientUuid)
        if (existing != null && mediaEditGuard?.canReplace(existing) == false) return true
        // Match server LWW: existing wins on equal updatedAt (>= skip).
        if (existing != null && existing.updatedAt >= entity.updatedAt) return true
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

    /**
     * Finish durable media-tombstone cleanup.
     *
     * File deletion and clearing the tombstone path share the Room transaction
     * lease. A delete failure or process stop leaves [MediaAssetEntity.localUri]
     * intact for the next synchronization. If a new live row has taken ownership
     * of the same path, only the stale tombstone reference is cleared.
     */
    private suspend fun cleanupPendingTombstonedMedia(
        clientUuids: Set<String>? = null,
    ) {
        val pendingClientUuids = mediaDao.listAllIncludingDeleted()
            .asSequence()
            .filter { media ->
                media.deletedAt != null &&
                    media.localUri.isNotBlank() &&
                    (clientUuids == null || media.clientUuid in clientUuids)
            }
            .map(MediaAssetEntity::clientUuid)
            .distinct()
            .toList()
        pendingClientUuids.forEach { clientUuid ->
            transactionRunner.run {
                val current = mediaDao.getByClientUuid(clientUuid)
                    ?.takeIf { it.deletedAt != null && it.localUri.isNotBlank() }
                    ?: return@run
                val path = current.localUri
                val pathHasLiveOwner = mediaDao.listAllIncludingDeleted().any { media ->
                    media.clientUuid != current.clientUuid &&
                        media.deletedAt == null &&
                        media.localUri == path
                }
                if (!pathHasLiveOwner) {
                    mediaFiles.delete(path)
                }
                val stillPending = mediaDao.getByClientUuid(clientUuid)
                if (stillPending?.deletedAt != null && stillPending.localUri == path) {
                    mediaDao.update(stillPending.copy(localUri = ""))
                }
            }
        }
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
            recordDao.markAllPendingSync()
            if (crossingFamilyBoundary) {
                carePlanDao.listAllIncludingDeleted().forEach { plan ->
                    carePlanDao.update(
                        plan.copy(
                            createdByMembershipId = "",
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
            } else {
                carePlanDao.markAllPendingSync()
                customItemDao.markAllPendingSync()
                fulfillmentCandidateDao.markAllPendingSync()
            }
            mediaDao.listAllIncludingDeleted().forEach { media ->
                val hasCurrentReceipt = previous.isJoined && media.hasReceiptFor(previous)
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
        captureLocalChanges(current)
        pushPending(current)
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

    private suspend fun captureLocalChanges(
        session: SyncSession,
    ): Set<CreatorAcknowledgementRef> {
        val authorityBabyIds = if (session.role == FamilyRole.Member) {
            babyDao.listFamilyAuthority().mapTo(mutableSetOf(), BabyEntity::id)
        } else {
            null
        }
        val memberRecordsById = if (authorityBabyIds != null) {
            recordDao.listAllIncludingDeleted().associateBy(RecordEntity::id)
        } else {
            emptyMap()
        }
        val memberPlans = if (authorityBabyIds != null) {
            carePlanDao.listAllIncludingDeleted()
        } else {
            emptyList()
        }
        val memberPlansById = memberPlans.associateBy(CarePlanEntity::id)
        val memberPlansByUuid = memberPlans.associateBy(CarePlanEntity::clientUuid)
        val babies = if (session.role == FamilyRole.Member) {
            emptyList()
        } else {
            babyDao.listPendingSync()
        }
        val records = recordDao.listPendingSync().filter { record ->
            authorityBabyIds == null || record.babyId in authorityBabyIds
        }
        val carePlans = carePlanDao.listPendingSync().filter { plan ->
            authorityBabyIds == null || plan.babyId in authorityBabyIds
        }
        val customItems = customItemDao.listPendingSync()
        val fulfillmentCandidates = fulfillmentCandidateDao.listPendingSync().filter { candidate ->
            authorityBabyIds == null ||
                memberPlansByUuid[candidate.carePlanClientUuid]?.babyId in authorityBabyIds
        }
        val capturedPendingCreatorAcknowledgements = mutableSetOf<CreatorAcknowledgementRef>()
        materializeLocalMedia(
            includeAvatars = session.role != FamilyRole.Member,
            babies = babies,
        )
        val directlyChangedMedia = mediaDao.listPendingSync().filter { asset ->
            if (authorityBabyIds == null) {
                true
            } else {
                when {
                    asset.recordId != null ->
                        memberRecordsById[asset.recordId]?.babyId in authorityBabyIds
                    asset.carePlanId != null ->
                        memberPlansById[asset.carePlanId]?.babyId in authorityBabyIds
                    else -> false // Member avatar media never enters the family outbox.
                }
            }
        }
        val referencedMedia = buildList {
            babies.forEach { baby ->
                mediaDao.activeAvatarForBaby(baby.id)?.let(::add)
            }
            records.forEach { record ->
                addAll(mediaDao.listForRecord(record.id))
            }
            carePlans.forEach { plan ->
                addAll(mediaDao.listForCarePlan(plan.id))
            }
        }
        val media = requirePortableMediaUuids(
            (directlyChangedMedia + referencedMedia).distinctBy(MediaAssetEntity::id),
        )
        babies.forEach { baby ->
            val eligibleAvatars = media.filter {
                it.kind == "avatar" &&
                    it.babyId == baby.id &&
                    it.deletedAt == null &&
                    (session.role != FamilyRole.Member || it.hasReceiptFor(session))
            }
            val avatarMediaUuid = baby.avatarMediaUuid
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
            enqueue(
                session,
                SyncWireMapper.baby(
                    baby,
                    avatarMediaUuid,
                ),
            )
        }
        customItems.forEach { item ->
            enqueue(session, SyncWireMapper.customItem(item))
            if (item.createdByMembershipId.isBlank()) {
                capturedPendingCreatorAcknowledgements += CreatorAcknowledgementRef(
                    entityType = "custom_item",
                    clientUuid = item.clientUuid,
                )
            }
        }
        // Outbox materialization only; atomic commit order is record packages →
        // care_plan packages → fulfillment_candidate residual (see pushOutboxBatch).
        records.forEach { record ->
            val babyUuid = babyDao.getIncludingDeleted(record.babyId)?.clientUuid
                ?: return@forEach
            val customItemUuid = recordCustomItemClientUuid(record)
            enqueue(
                session,
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
                session,
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
                plan.note?.startsWith(NEXT_FEED_PLAN_MARKER) == true
            if (plan.createdByMembershipId.isBlank() || memberNextFeedNeedsNasWinner) {
                capturedPendingCreatorAcknowledgements += CreatorAcknowledgementRef(
                    entityType = "care_plan",
                    clientUuid = plan.clientUuid,
                )
            }
        }
        fulfillmentCandidates.forEach { candidate ->
            enqueue(session, SyncWireMapper.fulfillmentCandidate(candidate))
        }
        media.forEach { asset ->
            if (session.role == FamilyRole.Member && asset.kind == "avatar") {
                return@forEach
            }
            val recordUuid = asset.recordId
                ?.let { recordDao.getIncludingDeleted(it)?.clientUuid }
            val carePlanUuid = asset.carePlanId
                ?.let { carePlanDao.get(it)?.clientUuid }
            val babyUuid = asset.babyId
                ?.let { babyDao.getIncludingDeleted(it)?.clientUuid }
            if (asset.kind == "log") {
                // XOR ownership: record OR care_plan, never both, never neither.
                if (recordUuid == null && carePlanUuid == null) return@forEach
                if (recordUuid != null && carePlanUuid != null) return@forEach
            }
            if (asset.kind == "avatar" && babyUuid == null) {
                return@forEach
            }
            enqueue(
                session,
                SyncWireMapper.media(
                    asset,
                    recordClientUuid = recordUuid,
                    babyClientUuid = babyUuid,
                    carePlanClientUuid = carePlanUuid,
                ),
            )
        }
        return capturedPendingCreatorAcknowledgements
    }

    private suspend fun recordCustomItemClientUuid(record: RecordEntity): String? {
        val type = SyncWireMapper.requireCurrentRecordType(record.type, "record type")
        if (type != RecordType.CUSTOM) return null
        require(record.schemaVersion == CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION) {
            "custom record schema_version 必须是 $CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION"
        }
        val localId = runCatching {
            Json.parseToJsonElement(record.payloadJson).jsonObject["custom_item_id"]
                ?.jsonPrimitive
                ?.longOrNull
        }.getOrNull()?.takeIf { it > 0L }
        require(localId != null) { "custom record 缺少本地 custom_item_id" }
        val definition = requireNotNull(customItemDao.getById(localId)) {
            "custom record 引用的本地定义不存在"
        }
        return definition.clientUuid.takeIf(String::isNotBlank)
            ?: error("custom record 引用的定义缺少 client_uuid")
    }

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

    private suspend fun enqueue(session: SyncSession, entity: SyncEntity) {
        outboxDao.enqueue(
            com.lezi.babylog.core.database.OutboxEntity(
                familyId = session.familyId,
                entityType = entity.type,
                clientUuid = entity.clientUuid,
                payloadJson = entity.payloadJson,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
            ),
        )
    }

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
        createdByMembershipId = payload.requireNonBlankString(
            "created_by_membership_id",
            "custom_item",
        ).trim(),
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
        createdByMembershipId = payload.requireNonBlankString(
            "created_by_membership_id",
            "record",
        ).trim(),
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
        createdByMembershipId = payload.requireNonBlankString(
            "created_by_membership_id",
            "care_plan",
        ).trim(),
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
        submitterMembershipId = payload.requireNonBlankString(
            "submitter_membership_id",
            "fulfillment_candidate",
        ).trim(),
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
            (recordUuid == null) != (carePlanUuid == null) && babyUuid == null
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
        babyClientUuid = babyUuid,
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
    if (detail.string("code") !in setOf("cursor_ahead", "generation_changed")) return null
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

internal fun SyncSession.receiptFor(clientUuid: String): String {
    val namespace = UUID.nameUUIDFromBytes(
        "${baseUrl.trimEnd('/')}\n$familyId".toByteArray(Charsets.UTF_8),
    )
    return "$RECEIPT_PREFIX$namespace:$clientUuid"
}

internal fun BundleStageStatus.mediaUuidsToUpload(liveMediaUuids: Set<String>): Set<String> =
    if (isCommitted) {
        emptySet()
    } else {
        val missing = missingMedia.toSet()
        val staged = stagedMedia.toSet()
        require(missing.intersect(staged).isEmpty() && missing + staged == liveMediaUuids) {
            "家庭服务器返回了无效的原子同步包媒体状态"
        }
        missing
    }

internal fun MediaAssetEntity.hasReceiptFor(session: SyncSession): Boolean =
    remoteUri == session.receiptFor(clientUuid)

private class LocalMediaEditGuard(
    private val mediaSnapshots: MutableMap<String, MediaAssetEntity>,
    private val babyAvatarPaths: MutableMap<String, String?>,
) {
    fun canReplace(media: MediaAssetEntity): Boolean =
        media.clientUuid !in mediaSnapshots || mediaSnapshots[media.clientUuid] == media

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
private const val SYNC_PULL_PAGE_ENTITY_LIMIT = 200
private const val RECEIPT_PREFIX = "lezi-sync:"
