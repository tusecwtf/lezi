package com.lezi.babylog.domain

import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.ConflictNotAdoptedAudit
import com.lezi.babylog.core.model.CustomPayload
import com.lezi.babylog.core.model.FulfillmentAdoptionStatus
import com.lezi.babylog.core.model.FulfillmentAuthority
import com.lezi.babylog.core.model.FulfillmentCandidate
import com.lezi.babylog.core.model.FulfillmentCandidateEvidence
import com.lezi.babylog.core.model.MilkPayload
import com.lezi.babylog.core.model.NextFeedPlanReconciliation
import com.lezi.babylog.core.model.NursingPayload
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordPayloadDocument
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.encodeNextFeedPlanNote
import com.lezi.babylog.core.model.isNextFeedPlanNote
import com.lezi.babylog.core.model.isPlanableCarePlanType
import com.lezi.babylog.core.model.visibleNextFeedPlanNote
import com.lezi.babylog.sync.SyncPort
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val NEXT_FEED_TYPES = setOf(
    RecordType.NURSING,
    RecordType.FORMULA,
    RecordType.PUMPED_FEED,
)

internal fun nextFeedPlanClientUuid(babyClientUuid: String, generationSeed: String): String =
    UUID.nameUUIDFromBytes(
        "lezi.next-feed.v1:$babyClientUuid:$generationSeed".toByteArray(StandardCharsets.UTF_8),
    ).toString()

internal fun nextFeedPlanGenerationSeed(plans: List<CarePlanEntity>): String =
    plans.maxWithOrNull(compareBy<CarePlanEntity> { it.updatedAt }.thenBy { it.clientUuid })
        ?.let { "${it.clientUuid}:${it.updatedAt}:${it.status}:${it.deletedAt ?: 0L}" }
        ?: "initial"

private fun openNextFeedPlans(
    plans: List<CarePlanEntity>,
    babyId: Long,
): List<CarePlanEntity> = plans
    .filter {
        it.babyId == babyId &&
            it.deletedAt == null &&
            it.status in setOf(
                CarePlanStatus.PENDING.storageKey,
                CarePlanStatus.MISSED.storageKey,
            ) &&
            isNextFeedPlanNote(it.note)
    }
    .sortedWith(compareBy<CarePlanEntity> { it.updatedAt }.thenBy { it.id })

internal class CarePlanCoordinator(
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val customItemDao: CustomItemDao,
    private val mediaAssetDao: MediaAssetDao,
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val photoAttachmentReconciler: PhotoAttachmentReconciler,
    private val reminderProjection: CarePlanReminderProjection,
    private val conflictAuditQueries: ConflictAuditQueries,
    private val calendarReminderMutationGuard: CalendarReminderMutationGuard,
    private val syncPort: SyncPort,
    private val recordMutations: RecordMutationCoordinator,
    private val nextFeedPlanMutationMutex: Mutex,
    private val sleepMutationMutex: Mutex,
    private val requireActiveBaby: suspend (Long) -> BabyEntity,
    private val listRecordPhotoPaths: suspend (Long) -> List<String>,
    private val requestLocalSync: () -> Unit,
) {
    internal suspend fun currentMembershipActorId(): String =
        syncPort.session().first().membershipId.trim()

    suspend fun createCarePlan(
        babyId: Long,
        type: RecordType,
        scheduledAt: Long,
        note: String? = null,
        payloadJson: String = "{}",
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        customItemId: Long? = null,
        photoLocalPaths: List<String> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
        /**
         * Default-on per-plan projection (ticket 21). When false, only Lezi
         * reminders are used. Unconfigured / permission deny still saves the plan.
         */
        projectToSystemCalendar: Boolean = true,
    ): Long {
        require(schemaVersion == CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION) {
            "仅支持当前 payload schema"
        }
        require(type.isPlanableCarePlanType || type == RecordType.CUSTOM) {
            "该项目不可新建护理计划"
        }
        require(scheduledAt > nowMillis) { "安排护理须选择未来时刻" }
        requireActiveBaby(babyId)
        val resolvedCustomItemId: Long?
        val stampedPayload: String
        if (type == RecordType.CUSTOM) {
            val id = customItemId?.takeIf { it > 0L }
                ?: error("具体自定义项目才可安排护理计划")
            val def = customItemDao.getById(id)
                ?: error("自定义项目不存在或已删除")
            if (def.deletedAt != null) error("自定义项目已删除")
            resolvedCustomItemId = id
            // Domain-stamps name/icon snapshots so hide/rename later still fulfill/display.
            stampedPayload = stampCustomItemSnapshotIntoPayload(
                payloadJson = payloadJson,
                customItemId = id,
                titleSnapshot = def.name,
                iconSlot = def.iconSlot,
            )
        } else {
            resolvedCustomItemId = null
            stampedPayload = payloadJson
        }
        val photos = photoLocalPaths
        val persistedPayload = requireCurrentPayloadJson(
            type = type,
            payloadJson = stampedPayload,
            schemaVersion = schemaVersion,
        )
        requireCustomPayloadMatches(type, persistedPayload, schemaVersion, resolvedCustomItemId)
        val now = System.currentTimeMillis()
        val id = photoAttachmentReconciler.withInvolvedPaths(
            owner = null,
            additionalPaths = photos,
        ) {
            transactionRunner.run {
                val planId = carePlanDao.upsert(
                    CarePlanEntity(
                        clientUuid = newClientUuid(),
                        babyId = babyId,
                        type = type.key,
                        customItemId = resolvedCustomItemId,
                        scheduledAt = scheduledAt,
                        scheduledZoneId = zone.id,
                        note = note,
                        payloadJson = persistedPayload,
                        schemaVersion = schemaVersion,
                        status = CarePlanStatus.PENDING.storageKey,
                        createdByMembershipId = currentMembershipActorId(),
                        updatedAt = now,
                        syncDirty = true,
                        systemCalendarProjectionEnabled = projectToSystemCalendar,
                    ),
                )
                photoAttachmentReconciler.reconcile(
                    PhotoAttachmentOwner.CarePlan(planId),
                    photos,
                    now,
                )
                planId
            }
        }
        // Shared CarePlan is committed and publishable before optional device-local projection.
        requestLocalSync()
        // Creator: full local plan + own reminders/calendar immediately (amber until publish).
        carePlanDao.get(id)?.toModel()?.let { plan ->
            reminderProjection.projectOrScheduleCarePlanReminder(
                plan,
                projectToSystemCalendar = projectToSystemCalendar,
            )
        }
        return id
    }

    /**
     * Create or move the one open, family-shared next-feed CarePlan for [babyId].
     * The marker is stable sync data but is stripped from every model/UI surface.
     * Intent-only feed payloads deliberately contain no fabricated amount/duration.
     */
    suspend fun reconcileNextFeedPlan(babyId: Long): NextFeedPlanReconciliation =
        nextFeedPlanMutationMutex.withLock {
            requireActiveBaby(babyId)
            openNextFeedPlans(carePlanDao.listAllIncludingDeleted(), babyId)
                .firstOrNull()
                ?.let {
                    NextFeedPlanReconciliation.Found(
                        clientUuid = it.clientUuid,
                        scheduledAtMillis = it.scheduledAt,
                    )
                }
                ?: NextFeedPlanReconciliation.Absent
        }

    suspend fun scheduleNextFeedCarePlan(
        babyId: Long,
        feedType: RecordType,
        scheduledAt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        require(feedType in NEXT_FEED_TYPES) { "仅喂养记录可安排下次喂养" }
        require(scheduledAt > nowMillis) { "下次喂养须选择未来时刻" }
        val baby = requireActiveBaby(babyId)
        val payload = when (feedType) {
            RecordType.NURSING -> NursingPayload()
            RecordType.FORMULA, RecordType.PUMPED_FEED -> MilkPayload(feedType)
            else -> error("unsupported next-feed type")
        }
        val payloadJson = RecordPayloadCodec.encode(
            RecordPayloadDocument(
                type = feedType,
                payload = payload,
                schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
            ),
        )
        val (id, duplicateIds) = nextFeedPlanMutationMutex.withLock {
            transactionRunner.run {
                val allPlans = carePlanDao.listAllIncludingDeleted()
                val open = openNextFeedPlans(allPlans, babyId)
                val existing = open.firstOrNull()
                if (existing != null) {
                    requireCanManageCarePlan(existing)
                    open.drop(1).forEach { requireCanManageCarePlan(it) }
                }
                val at = nowMillis.coerceAtLeast((existing?.updatedAt ?: 0L) + 1L)
                val plan = if (existing == null) {
                    val generationSeed = nextFeedPlanGenerationSeed(
                        allPlans.filter {
                            it.babyId == babyId && isNextFeedPlanNote(it.note)
                        },
                    )
                    CarePlanEntity(
                        clientUuid = nextFeedPlanClientUuid(baby.clientUuid, generationSeed),
                        babyId = babyId,
                        type = feedType.key,
                        scheduledAt = scheduledAt,
                        scheduledZoneId = zone.id,
                        note = encodeNextFeedPlanNote(null),
                        payloadJson = payloadJson,
                        schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                        status = CarePlanStatus.PENDING.storageKey,
                        createdByMembershipId = currentMembershipActorId(),
                        updatedAt = at,
                        syncDirty = true,
                        systemCalendarProjectionEnabled = true,
                    )
                } else {
                    existing.copy(
                        type = feedType.key,
                        scheduledAt = scheduledAt,
                        scheduledZoneId = zone.id,
                        note = encodeNextFeedPlanNote(visibleNextFeedPlanNote(existing.note)),
                        payloadJson = payloadJson,
                        schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                        status = CarePlanStatus.PENDING.storageKey,
                        fulfilledRecordClientUuid = null,
                        fulfilledAt = null,
                        updatedAt = at,
                        syncDirty = true,
                        systemCalendarProjectionEnabled = true,
                        systemCalendarReminderReady = false,
                    )
                }
                val planId = carePlanDao.upsert(plan)
                // Defensive healing for old concurrent duplicates: preserve the oldest
                // stable identity and tombstone every other open marker row.
                open.drop(1).forEach { duplicate ->
                    carePlanDao.softDelete(
                        duplicate.id,
                        at.coerceAtLeast(duplicate.updatedAt + 1L),
                    )
                }
                planId to open.drop(1).map(CarePlanEntity::id)
            }
        }
        duplicateIds.forEach { duplicateId ->
            reminderProjection.cancelCarePlanReminderBestEffort(duplicateId)
            reminderProjection.removeSystemCalendarProjection(duplicateId)
        }
        // Shared next-feed plan is publishable before optional device-local projection.
        requestLocalSync()
        carePlanDao.get(id)?.toModel()?.let { plan ->
            reminderProjection.projectOrScheduleCarePlanReminder(
                plan,
                projectToSystemCalendar = true,
            )
        }
        return id
    }

    /**
     * Fulfill a pending/missed plan: confirm non-future actual time, insert linked
     * Record and complete the plan in one transaction. Any active family member may
     * fulfill any plan; manage rights (edit/skip/delete) stay author/admin-only.
     *
     * Also writes a durable [FulfillmentCandidate] with a stable UUID and immutable
     * confirm time so family publish/retry shares one candidate identity.
     *
     * Nursing manual 补记 uses this path; timer completion uses [completeNursing]
     * with [carePlanId]. Sleep uses the open-sleep mutex and may create an open
     * interval ([endTimestamp] null) or a closed interval.
     *
     * @return the new record id
     */
    suspend fun fulfillCarePlan(
        carePlanId: Long,
        actualTimestamp: Long,
        endTimestamp: Long? = null,
        note: String? = null,
        payloadJson: String? = null,
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        photoLocalPaths: List<String> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        // Fulfill actual times (start + closed sleep end) allow device-now + 5 minutes.
        RecordTime.intervalError(
            start = actualTimestamp,
            end = endTimestamp,
            now = nowMillis,
            maxFutureSkewMillis = RecordTime.FULFILLMENT_ACTUAL_TIME_SKEW_MILLIS,
        )?.let { throw IllegalArgumentException(it) }
        val photos = photoLocalPaths
        // Freeze confirm time once for the candidate; wall clock for writer bookkeeping.
        val confirmedAt = System.currentTimeMillis()
        val now = confirmedAt

        // Peek type to decide whether sleep mutex is required (fail closed on races).
        val planPeek = carePlanDao.get(carePlanId) ?: error("护理计划不存在")
        val planType = RecordType.fromKey(planPeek.type) ?: error("未知记录类型")
        requireCurrentPayloadDocument(planType, planPeek.payloadJson, planPeek.schemaVersion)
        requireCustomPayloadMatches(
            planType,
            planPeek.payloadJson,
            planPeek.schemaVersion,
            planPeek.customItemId,
        )

        // Global lock order: path gate → sleepMutationMutex → Room (never invert).
        val recordId = photoAttachmentReconciler.withInvolvedPaths(
            owner = PhotoAttachmentOwner.CarePlan(carePlanId),
            additionalPaths = photos,
        ) {
            suspend fun writeFulfill(): Long = transactionRunner.run {
                val plan = carePlanDao.get(carePlanId)
                    ?: error("护理计划不存在")
                if (plan.deletedAt != null) error("护理计划已删除")
                val status = CarePlanStatus.fromStorage(plan.status)
                require(status == CarePlanStatus.PENDING || status == CarePlanStatus.MISSED) {
                    "该护理计划不可履行"
                }
                // Fulfill is allowed for any member; no author manage ACL here.
                requireActiveBaby(plan.babyId)
                val type = RecordType.fromKey(plan.type) ?: error("未知记录类型")
                requireCurrentPayloadDocument(type, plan.payloadJson, plan.schemaVersion)
                requireCustomPayloadMatches(
                    type,
                    plan.payloadJson,
                    plan.schemaVersion,
                    plan.customItemId,
                )
                val resolvedEnd = endTimestamp
                if (type == RecordType.SLEEP) {
                    validateSleepInterval(RecordType.SLEEP, actualTimestamp, resolvedEnd)
                    recordMutations.healDuplicateOpenSleeps(plan.babyId)
                    val currentOpen = recordDao.findOpenSleep(plan.babyId)
                    // Open-interval fulfill and closed-interval fulfill both require
                    // no competing open sleep for a different interval.
                    if (currentOpen != null) {
                        throw SleepStateChangedException()
                    }
                } else if (resolvedEnd != null) {
                    // Non-sleep types do not use interval ends on fulfill.
                    error("该项目履行不支持结束时间")
                }
                val nextPayload = payloadJson ?: plan.payloadJson
                val persistedPayload = requireCurrentPayloadJson(
                    type = type,
                    payloadJson = nextPayload,
                    schemaVersion = schemaVersion,
                )
                requireCustomPayloadMatches(
                    type,
                    persistedPayload,
                    schemaVersion,
                    plan.customItemId,
                )
                val recordClientUuid = newClientUuid()
                // Next-feed plans store an internal protocol marker on the plan row. The
                // fulfilled care-record fact must never carry that prefix: omit note derives
                // the visible part; explicit notes are still fail-closed stripped when the
                // plan is a next-feed plan (never invent a marker; never leave one on the fact).
                // Non-next-feed plans keep note ?: plan.note without incidental trim/null.
                val recordNote = if (isNextFeedPlanNote(plan.note)) {
                    visibleNextFeedPlanNote(note ?: plan.note)
                } else {
                    note ?: plan.note
                }
                val record = RecordEntity(
                    clientUuid = recordClientUuid,
                    babyId = plan.babyId,
                    type = type.key,
                    timestamp = actualTimestamp,
                    endTimestamp = resolvedEnd,
                    note = recordNote,
                    payloadJson = persistedPayload,
                    schemaVersion = schemaVersion,
                    updatedAt = now,
                )
                val inserted = recordMutations.insertRecord(record)
                photoAttachmentReconciler.reconcile(
                    PhotoAttachmentOwner.Record(inserted),
                    photos,
                    now,
                )
                // Manager (creator/owner) may LWW-push completed plan status. Non-managers
                // complete only locally — server forbids care_plan rewrites for them;
                // peers re-link via fulfillment_candidate + resolveFulfillmentAuthority.
                val publishPlanCompletion = actorCanManageCarePlan(plan)
                carePlanDao.update(
                    plan.copy(
                        status = CarePlanStatus.COMPLETED.storageKey,
                        fulfilledRecordClientUuid = recordClientUuid,
                        fulfilledAt = confirmedAt,
                        updatedAt = nextSyncUpdatedAt(plan.updatedAt, now),
                        syncDirty = publishPlanCompletion,
                    ),
                )
                ensureFulfillmentCandidate(
                    carePlanClientUuid = plan.clientUuid,
                    recordClientUuid = recordClientUuid,
                    actualTimestamp = actualTimestamp,
                    confirmedAt = confirmedAt,
                )
                // Local multi-candidate sets (rare) re-link the plan to the authority.
                resolveFulfillmentAuthorityForPlan(plan.clientUuid)
                inserted
            }
            if (planType == RecordType.SLEEP) {
                sleepMutationMutex.withLock { writeFulfill() }
            } else {
                writeFulfill()
            }
        }
        reminderProjection.cancelCarePlanReminderBestEffort(carePlanId)
        reminderProjection.removeSystemCalendarProjection(carePlanId)
        requestLocalSync()
        return recordId
    }

    /**
     * Mark an open care plan completed against [recordClientUuid] inside an
     * already-open transaction. Idempotent when already completed with the same
     * record; refuses double-complete with a different record. Always ensures a
     * durable fulfillment candidate for the plan+record pair (nursing timer path).
     */
    internal suspend fun completeOpenCarePlanWithRecord(
        carePlanId: Long,
        babyId: Long,
        expectedType: RecordType,
        recordClientUuid: String,
        now: Long,
        actualTimestamp: Long,
    ) {
        val plan = carePlanDao.get(carePlanId) ?: error("护理计划不存在")
        if (plan.deletedAt != null) error("护理计划已删除")
        require(plan.babyId == babyId) { "护理计划与宝宝不匹配" }
        require(plan.type == expectedType.key) { "护理计划类型不匹配" }
        requireCurrentPayloadDocument(expectedType, plan.payloadJson, plan.schemaVersion)
        requireCustomPayloadMatches(
            expectedType,
            plan.payloadJson,
            plan.schemaVersion,
            plan.customItemId,
        )
        val status = CarePlanStatus.fromStorage(plan.status)
        if (status == CarePlanStatus.COMPLETED) {
            require(plan.fulfilledRecordClientUuid == recordClientUuid) {
                "该护理计划已由其他记录完成"
            }
            // Retry path: keep the same candidate identity for this pair.
            ensureFulfillmentCandidate(
                carePlanClientUuid = plan.clientUuid,
                recordClientUuid = recordClientUuid,
                actualTimestamp = actualTimestamp,
                confirmedAt = plan.fulfilledAt ?: now,
            )
            return
        }
        require(status == CarePlanStatus.PENDING || status == CarePlanStatus.MISSED) {
            "该护理计划不可履行"
        }
        // Same ACL as fulfillCarePlan: only managers publish plan completion.
        val publishPlanCompletion = actorCanManageCarePlan(plan)
        carePlanDao.update(
            plan.copy(
                status = CarePlanStatus.COMPLETED.storageKey,
                fulfilledRecordClientUuid = recordClientUuid,
                fulfilledAt = now,
                updatedAt = nextSyncUpdatedAt(plan.updatedAt, now),
                syncDirty = publishPlanCompletion,
            ),
        )
        ensureFulfillmentCandidate(
            carePlanClientUuid = plan.clientUuid,
            recordClientUuid = recordClientUuid,
            actualTimestamp = actualTimestamp,
            confirmedAt = now,
        )
        resolveFulfillmentAuthorityForPlan(plan.clientUuid)
    }

    /**
     * Create or re-dirty the durable fulfillment candidate for a plan+record pair.
     * Same-device retries reuse the existing [FulfillmentCandidateEntity.clientUuid]
     * so outbox republish never invents a second candidate identity.
     *
     * Must run inside the caller's transaction.
     */
    private suspend fun ensureFulfillmentCandidate(
        carePlanClientUuid: String,
        recordClientUuid: String,
        actualTimestamp: Long,
        confirmedAt: Long,
    ) {
        val session = syncPort.session().first()
        // Offline trail for multi-device authority until server freeze lands on pull.
        // Server overwrites membership/role/confirmed_at on first accept; we still
        // stamp local role so admin fulfills adjudicate correctly on the originator.
        val localMembershipId = session.membershipId.trim()
        val localRole = when (session.role) {
            com.lezi.babylog.sync.FamilyRole.Owner -> "owner"
            com.lezi.babylog.sync.FamilyRole.Member -> "member"
            com.lezi.babylog.sync.FamilyRole.None -> ""
        }
        val existing = fulfillmentCandidateDao.listForCarePlan(carePlanClientUuid)
            .firstOrNull { it.recordClientUuid == recordClientUuid && it.deletedAt == null }
        if (existing != null) {
            if (!existing.syncDirty) {
                fulfillmentCandidateDao.update(
                    existing.copy(
                        syncDirty = true,
                        // Never rewrite immutable confirm time or candidate uuid.
                        // Fill empty offline trails only (server freeze may already be present).
                        submitterMembershipId = existing.submitterMembershipId
                            .ifBlank { localMembershipId },
                        submitterRole = existing.submitterRole.ifBlank { localRole },
                        updatedAt = confirmedAt.coerceAtLeast(existing.updatedAt + 1),
                    ),
                )
            }
            return
        }
        fulfillmentCandidateDao.upsert(
            FulfillmentCandidateEntity(
                clientUuid = newClientUuid(),
                carePlanClientUuid = carePlanClientUuid,
                recordClientUuid = recordClientUuid,
                actualTimestamp = actualTimestamp,
                confirmedAt = confirmedAt,
                submitterMembershipId = localMembershipId,
                submitterRole = localRole,
                updatedAt = confirmedAt,
                syncDirty = true,
            ),
        )
    }

    /**
     * Deterministic multi-candidate authority for one care plan.
     *
     * Points [CarePlan.fulfilledRecordClientUuid] at the single winner, marks losers
     * [FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED], and never deletes Record/photos.
     * Local-only: does not dirty the plan or candidates for republish (every device
     * re-derives from frozen submitter/confirmed_at evidence after pull).
     *
     * Idempotent; safe to re-run after every apply of candidates for the plan.
     */
    suspend fun resolveFulfillmentAuthorityForPlan(carePlanClientUuid: String) {
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
        // Local marks only — keep updatedAt/syncDirty so we do not republish.
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
                // Local re-link only; LWW plan push order must not fight resolution.
                updatedAt = plan.updatedAt,
                syncDirty = plan.syncDirty,
            ),
        )
    }

    /** Test/debug surface: candidates linked to a plan's portable id. */
    suspend fun listFulfillmentCandidatesForPlan(carePlanClientUuid: String): List<FulfillmentCandidate> =
        fulfillmentCandidateDao.listForCarePlan(carePlanClientUuid).map { it.toModel() }

    /** True when the joined session is family owner/admin. */
    suspend fun isFamilyAdmin(): Boolean {
        val session = syncPort.session().first()
        return session.role == com.lezi.babylog.sync.FamilyRole.Owner
    }


    suspend fun listConflictNotAdoptedAudits(
        carePlanClientUuid: String? = null,
        babyId: Long? = null,
    ): List<ConflictNotAdoptedAudit> =
        conflictAuditQueries.listConflictNotAdoptedAudits(carePlanClientUuid, babyId)

    suspend fun getConflictNotAdoptedAudit(
        candidateClientUuid: String,
    ): ConflictNotAdoptedAudit? =
        conflictAuditQueries.getConflictNotAdoptedAudit(candidateClientUuid)

    /**
     * Admin-only: create a new ordinary Record from a conflict-not-adopted candidate.
     *
     * Does **not** flip [FulfillmentCandidate.adoptionStatus] or re-link
     * [CarePlan.fulfilledRecordClientUuid]. Copies fields/photos into a new record
     * identity with new media ownership; keeps the loser row for audit.
     *
     * Idempotent via [FulfillmentCandidateEntity.convertedRecordClientUuid]: retries
     * after partial sync failure return the same independent record id.
     *
     * @return local id of the independent ordinary Record
     */
    suspend fun convertConflictNotAdoptedToIndependentRecord(
        candidateClientUuid: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        if (!isFamilyAdmin()) throw ConflictAuditPermissionException()

        val candidatePeek = fulfillmentCandidateDao.getByClientUuid(candidateClientUuid)
            ?: error("冲突未采纳履行不存在")
        val sourcePeek = recordDao.getByClientUuid(candidatePeek.recordClientUuid)
        val photoPaths = sourcePeek?.let { source ->
            if (source.deletedAt == null) {
                listRecordPhotoPaths(source.id)
            } else {
                mediaAssetDao.listForRecord(source.id)
                    .map(MediaAssetEntity::localUri)
                    .filter(String::isNotBlank)
                    .distinct()
            }
        }.orEmpty()
        val sourceType = sourcePeek?.type
        // Global lock order: path gate → sleepMutationMutex → Room (never invert).
        val recordId = photoAttachmentReconciler.withInvolvedPaths(
            owner = null,
            additionalPaths = photoPaths,
        ) {
            suspend fun writeConvert(): Long = transactionRunner.run {
                val candidate = fulfillmentCandidateDao.getByClientUuid(candidateClientUuid)
                    ?: error("冲突未采纳履行不存在")
                if (candidate.deletedAt != null) error("冲突未采纳履行已删除")
                require(candidate.adoptionStatus == FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED) {
                    "仅冲突未采纳履行可转为独立记录"
                }

                val existingPointer = candidate.convertedRecordClientUuid.trim()
                if (existingPointer.isNotEmpty()) {
                    val existing = recordDao.getByClientUuid(existingPointer)
                    if (existing != null && existing.deletedAt == null) {
                        return@run existing.id
                    }
                }

                val source = recordDao.getByClientUuid(candidate.recordClientUuid)
                    ?: error("未采纳履行对应的记录不存在")
                // MediaAsset is the sole current photo source, including tombstoned facts.
                val photos = if (source.deletedAt == null) {
                    listRecordPhotoPaths(source.id)
                } else {
                    mediaAssetDao.listForRecord(source.id)
                        .map(MediaAssetEntity::localUri)
                        .filter(String::isNotBlank)
                        .distinct()
                }
                val targetUuid = existingPointer.ifEmpty { newClientUuid() }
                val at = nowMillis.coerceAtLeast(source.updatedAt + 1)
                val type = RecordType.fromKey(source.type) ?: error("未知记录类型")
                if (type == RecordType.SLEEP) {
                    validateSleepInterval(type, source.timestamp, source.endTimestamp)
                }
                requireActiveBaby(source.babyId)
                if (type == RecordType.SLEEP && source.endTimestamp == null) {
                    // Open sleep from a fulfill is unexpected; still guard open-sleep invariants.
                    recordMutations.healDuplicateOpenSleeps(source.babyId)
                    if (recordDao.findOpenSleep(source.babyId) != null) {
                        throw SleepStateChangedException()
                    }
                }
                val newRecord = RecordEntity(
                    // Reuse pointer uuid on retry so we never mint a second ordinary fact.
                    id = recordDao.getByClientUuid(targetUuid)?.id ?: 0L,
                    clientUuid = targetUuid,
                    babyId = source.babyId,
                    type = source.type,
                    timestamp = source.timestamp,
                    endTimestamp = source.endTimestamp,
                    note = source.note,
                    payloadJson = source.payloadJson,
                    schemaVersion = source.schemaVersion,
                    updatedAt = at,
                    deletedAt = null,
                    syncDirty = true,
                )
                val inserted = recordMutations.insertRecord(newRecord)
                photoAttachmentReconciler.reconcile(
                    PhotoAttachmentOwner.Record(inserted),
                    photos,
                    at,
                )

                // Pointer only — never touch adoptionStatus or plan authority.
                if (candidate.convertedRecordClientUuid != targetUuid) {
                    fulfillmentCandidateDao.update(
                        candidate.copy(convertedRecordClientUuid = targetUuid),
                    )
                }
                inserted
            }
            if (sourceType == RecordType.SLEEP.key) {
                sleepMutationMutex.withLock { writeConvert() }
            } else {
                writeConvert()
            }
        }
        requestLocalSync()
        return recordId
    }

    /**
     * Whether the actor may edit/skip/soft-delete this plan.
     * Same membership rule as custom items: creator or family owner/admin.
     */
    fun canManageCarePlan(
        plan: CarePlan,
        actorMembershipId: String,
        actorIsAdmin: Boolean,
    ): Boolean = canManageCreatorOwnedFamilyEntity(
        creatorMembershipId = plan.createdByMembershipId,
        actorMembershipId = actorMembershipId,
        actorIsAdmin = actorIsAdmin,
    )

    suspend fun canManageCarePlan(plan: CarePlan): Boolean {
        val session = syncPort.session().first()
        return canManageCreatorOwnedFamilyEntity(
            creatorMembershipId = plan.createdByMembershipId,
            actorMembershipId = session.membershipId.trim(),
            actorIsAdmin = session.role == com.lezi.babylog.sync.FamilyRole.Owner,
            creatorAcknowledgementPending = session.isCreatorAcknowledgementPending(
                entityType = "care_plan",
                clientUuid = plan.clientUuid,
            ),
        )
    }

    private suspend fun actorCanManageCarePlan(plan: CarePlanEntity): Boolean {
        val session = syncPort.session().first()
        return canManageCreatorOwnedFamilyEntity(
            creatorMembershipId = plan.createdByMembershipId,
            actorMembershipId = session.membershipId.trim(),
            actorIsAdmin = session.role == com.lezi.babylog.sync.FamilyRole.Owner,
            creatorAcknowledgementPending = session.isCreatorAcknowledgementPending(
                entityType = "care_plan",
                clientUuid = plan.clientUuid,
            ),
        )
    }

    private suspend fun requireCanManageCarePlan(plan: CarePlanEntity) {
        if (!actorCanManageCarePlan(plan)) throw CarePlanPermissionException()
    }

    /**
     * Update an open (pending/missed) plan. Scheduled time may move into the past —
     * that only yields effective [CarePlanStatus.MISSED], never auto-fulfill.
     * Does not write a Record.
     */
    suspend fun updateCarePlan(
        carePlanId: Long,
        scheduledAt: Long,
        note: String? = null,
        payloadJson: String? = null,
        schemaVersion: Int? = null,
        photoLocalPaths: List<String>? = null,
        zone: ZoneId? = null,
        nowMillis: Long = System.currentTimeMillis(),
        projectToSystemCalendar: Boolean? = null,
    ) {
        val photos = photoLocalPaths
        val planOwner = PhotoAttachmentOwner.CarePlan(carePlanId)
        val cleanupCandidates = photoAttachmentReconciler.withInvolvedPaths(
            owner = planOwner,
            additionalPaths = photos.orEmpty(),
        ) {
            transactionRunner.run {
                val plan = carePlanDao.get(carePlanId) ?: error("护理计划不存在")
                if (plan.deletedAt != null) error("护理计划已删除")
                val status = CarePlanStatus.fromStorage(plan.status)
                require(status == CarePlanStatus.PENDING || status == CarePlanStatus.MISSED) {
                    "已完成或已跳过的计划不可编辑"
                }
                requireCanManageCarePlan(plan)
                requireActiveBaby(plan.babyId)
                val type = RecordType.fromKey(plan.type) ?: error("未知记录类型")
                requireCurrentPayloadDocument(type, plan.payloadJson, plan.schemaVersion)
                requireCustomPayloadMatches(
                    type,
                    plan.payloadJson,
                    plan.schemaVersion,
                    plan.customItemId,
                )
                val at = nowMillis.coerceAtLeast(plan.updatedAt + 1)
                val nextSchemaVersion = schemaVersion ?: plan.schemaVersion
                val rawNextPayload = payloadJson ?: plan.payloadJson
                val nextPayload = if (photos != null) {
                    requireCurrentPayloadJson(
                        type = type,
                        payloadJson = rawNextPayload,
                        schemaVersion = nextSchemaVersion,
                    )
                } else {
                    requireCurrentPayloadDocument(type, rawNextPayload, nextSchemaVersion)
                    rawNextPayload
                }
                requireCustomPayloadMatches(
                    type,
                    nextPayload,
                    nextSchemaVersion,
                    plan.customItemId,
                )
                val nextZoneId = zone?.id ?: plan.scheduledZoneId
                val desiredProjection =
                    projectToSystemCalendar ?: plan.systemCalendarProjectionEnabled
                val persistedNote = if (isNextFeedPlanNote(plan.note)) {
                    encodeNextFeedPlanNote(note)
                } else {
                    note
                }
                val sharedChanged =
                    plan.scheduledAt != scheduledAt ||
                        plan.scheduledZoneId != nextZoneId ||
                        plan.note != persistedNote ||
                        plan.payloadJson != nextPayload ||
                        plan.schemaVersion != nextSchemaVersion ||
                        plan.status != CarePlanStatus.PENDING.storageKey
                // Photo-only edits are still atomic CarePlan bundle mutations. Reconcile first so an
                // identical explicit list remains a no-op; the enclosing transaction owns both writes.
                val photoMutation = photos?.let {
                    photoAttachmentReconciler.reconcile(
                        PhotoAttachmentOwner.CarePlan(carePlanId),
                        it,
                        at,
                    )
                }
                val photosChanged = photoMutation?.changed == true
                if (sharedChanged || photosChanged) {
                    // Persist stored status as pending; missed is always derived from clock.
                    carePlanDao.update(
                        plan.copy(
                            scheduledAt = scheduledAt,
                            scheduledZoneId = nextZoneId,
                            note = persistedNote,
                            payloadJson = nextPayload,
                            schemaVersion = nextSchemaVersion,
                            status = CarePlanStatus.PENDING.storageKey,
                            updatedAt = at,
                            syncDirty = true,
                            systemCalendarProjectionEnabled = desiredProjection,
                            systemCalendarReminderReady = false,
                        ),
                    )
                } else if (desiredProjection != plan.systemCalendarProjectionEnabled) {
                    carePlanDao.updateSystemCalendarProjectionEnabled(
                        clientUuid = plan.clientUuid,
                        enabled = desiredProjection,
                    )
                }
                photoMutation?.tombstonedClientUuids.orEmpty()
            }
        }
        // Shared update is committed and publishable before optional local side effects.
        recordMutations.cleanupCommittedPhotoTombstones(cleanupCandidates)
        requestLocalSync()
        calendarReminderMutationGuard.withLock {
            carePlanDao.get(carePlanId)?.toModel()?.let { plan ->
                if (plan.scheduledAt <= nowMillis) {
                    reminderProjection.cancelCarePlanReminderBestEffort(plan.id)
                    reminderProjection.removeSystemCalendarProjectionLocked(plan.id)
                } else {
                    reminderProjection.projectOrScheduleCarePlanReminderLocked(
                        plan,
                        projectToSystemCalendar = plan.systemCalendarProjectionEnabled,
                    )
                }
            }
        }
    }

    /**
     * Skip an open plan without creating a Record.
     */
    suspend fun skipCarePlan(
        carePlanId: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        transactionRunner.run {
            val plan = carePlanDao.get(carePlanId) ?: error("护理计划不存在")
            if (plan.deletedAt != null) error("护理计划已删除")
            val status = CarePlanStatus.fromStorage(plan.status)
            require(status == CarePlanStatus.PENDING || status == CarePlanStatus.MISSED) {
                "该护理计划不可跳过"
            }
            requireCanManageCarePlan(plan)
            carePlanDao.update(
                plan.copy(
                    status = CarePlanStatus.SKIPPED.storageKey,
                    updatedAt = nowMillis.coerceAtLeast(plan.updatedAt + 1),
                    syncDirty = true,
                ),
            )
        }
        reminderProjection.cancelCarePlanReminderBestEffort(carePlanId)
        reminderProjection.removeSystemCalendarProjection(carePlanId)
        requestLocalSync()
    }

    /**
     * Soft-delete a plan (tombstone). Removes it from pending views; no Record created.
     */
    suspend fun deleteCarePlan(
        carePlanId: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val planOwner = PhotoAttachmentOwner.CarePlan(carePlanId)
        val (deleted, cleanupCandidates) = photoAttachmentReconciler.withInvolvedPaths(planOwner) {
            transactionRunner.run {
                val plan = carePlanDao.get(carePlanId)
                    ?: return@run false to emptySet<String>()
                if (plan.deletedAt != null) return@run false to emptySet<String>()
                requireCanManageCarePlan(plan)
                val deletedAt = nowMillis.coerceAtLeast(plan.updatedAt + 1)
                carePlanDao.softDelete(carePlanId, deletedAt)
                val tombstones = photoAttachmentReconciler.tombstone(
                    planOwner,
                    deletedAt,
                ).tombstonedClientUuids
                true to tombstones
            }
        }
        if (!deleted) return false
        recordMutations.cleanupCommittedPhotoTombstones(cleanupCandidates)
        reminderProjection.cancelCarePlanReminderBestEffort(carePlanId)
        reminderProjection.removeSystemCalendarProjection(carePlanId)
        requestLocalSync()
        return true
    }


    suspend fun onFamilyCarePlansApplied(
        planClientUuids: List<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ) = reminderProjection.onFamilyCarePlansApplied(planClientUuids, nowMillis)

    suspend fun setCarePlanLocalRemindersEnabled(
        enabled: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ) = reminderProjection.setCarePlanLocalRemindersEnabled(enabled, nowMillis)

    suspend fun projectOrScheduleCarePlanReminder(
        plan: CarePlan,
        projectToSystemCalendar: Boolean = plan.systemCalendarProjectionEnabled,
    ): Boolean = reminderProjection.projectOrScheduleCarePlanReminder(
        plan,
        projectToSystemCalendar,
    )

    suspend fun reprojectOpenFutureSystemCalendarCopies(
        nowMillis: Long = System.currentTimeMillis(),
    ) = reminderProjection.reprojectOpenFutureSystemCalendarCopies(nowMillis)

    suspend fun isCarePlanSystemCalendarUnsynced(carePlanId: Long): Boolean =
        reminderProjection.isCarePlanSystemCalendarUnsynced(carePlanId)

    suspend fun disableSystemCalendarProjection() =
        reminderProjection.disableSystemCalendarProjection()

    suspend fun rescheduleCarePlanReminders(
        nowMillis: Long = System.currentTimeMillis(),
    ) = reminderProjection.rescheduleCarePlanReminders(nowMillis)

    suspend fun shouldDeliverCarePlanReminder(
        carePlanId: Long,
        clientUuid: String,
        expectedScheduledAt: Long,
    ): Boolean = reminderProjection.shouldDeliverCarePlanReminder(
        carePlanId,
        clientUuid,
        expectedScheduledAt,
    )

    /** Active plan photo paths. MediaAsset is authoritative. */
    suspend fun listCarePlanPhotoPaths(carePlanId: Long): List<String> {
        val active = mediaAssetDao.listActiveForCarePlan(carePlanId)
            .map(MediaAssetEntity::localUri)
            .filter { it.isNotBlank() }
        return active
    }


}

private fun FulfillmentCandidateEntity.toModel(): FulfillmentCandidate =
    FulfillmentCandidate(
        id = id,
        clientUuid = clientUuid,
        carePlanClientUuid = carePlanClientUuid,
        recordClientUuid = recordClientUuid,
        actualTimestamp = actualTimestamp,
        confirmedAt = confirmedAt,
        submitterMembershipId = submitterMembershipId,
        submitterRole = submitterRole,
        adoptionStatus = adoptionStatus,
        convertedRecordClientUuid = convertedRecordClientUuid,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        syncDirty = syncDirty,
    )

private fun requireCustomPayloadMatches(
    type: RecordType,
    payloadJson: String,
    schemaVersion: Int,
    expectedCustomItemId: Long?,
) {
    if (type != RecordType.CUSTOM) {
        require(expectedCustomItemId == null) { "内置项目不得携带 customItemId" }
        return
    }
    val expected = expectedCustomItemId?.takeIf { it > 0L }
        ?: throw IllegalArgumentException("CUSTOM 计划必须携带具体项目身份")
    val payload = requireCurrentPayloadDocument(type, payloadJson, schemaVersion).payload
        as CustomPayload
    require(payload.customItemId == expected) { "CUSTOM payload 与计划项目身份不匹配" }
}
