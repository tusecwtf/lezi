package com.lezi.babylog.domain.carelog
import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.CustomPayload
import com.lezi.babylog.core.model.NursingPayload
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordPayloadDocument
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SleepPayload
import com.lezi.babylog.core.model.isPlanableCarePlanType
import com.lezi.babylog.core.model.validateWakeTimestamp
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.SyncPort
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.lezi.babylog.domain.RecordPermissionException
import com.lezi.babylog.domain.SleepStateChangedException
import com.lezi.babylog.domain.canManageCreatorOwnedFamilyEntity
import com.lezi.babylog.domain.nextSyncUpdatedAt
import com.lezi.babylog.domain.requireCurrentPayloadDocument
import com.lezi.babylog.domain.requireCurrentPayloadJson
import com.lezi.babylog.domain.stampCustomItemSnapshotIntoPayload
import com.lezi.babylog.domain.toModel
import com.lezi.babylog.domain.validateSleepInterval

internal class RecordMutationCoordinator(
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val customItemDao: CustomItemDao,
    private val photoAttachmentReconciler: PhotoAttachmentReconciler,
    private val transactionRunner: DatabaseTransactionRunner,
    /**
     * Device-local reminder/calendar side effects after plan create from record convert.
     * Injected from the facade so carelog does not import careplan types.
     */
    private val projectOrScheduleCarePlanReminder: suspend (
        plan: CarePlan,
        projectToSystemCalendar: Boolean,
    ) -> Unit,
    /**
     * Cancel Lezi reminder and remove system-calendar projection after linked fulfillment.
     * Injected from the facade so carelog does not import careplan types.
     */
    private val cancelCarePlanReminderAndProjection: suspend (carePlanId: Long) -> Unit,
    private val syncPort: SyncPort,
    private val clock: PolicyClock,
    private val sleepMutationMutex: Mutex,
    private val requireActiveBaby: suspend (Long) -> BabyEntity,
    private val currentMembershipActorId: suspend () -> String,
    private val completeOpenCarePlanWithRecord: suspend (
        carePlanId: Long,
        babyId: Long,
        expectedType: RecordType,
        recordClientUuid: String,
        now: Long,
        actualTimestamp: Long,
    ) -> Unit,
    /**
     * Authoritative active plan photo paths (ordered). Timer fulfillment clones these
     * into the new Record inside the same transaction; does not use UI snapshots.
     */
    private val listCarePlanPhotoPaths: suspend (Long) -> List<String>,
    private val requestLocalSync: () -> Unit,
    private val wakeObservations: WakeObservationCoordinator,
) {
    suspend fun addRecord(
        babyId: Long,
        type: RecordType,
        timestamp: Long = System.currentTimeMillis(),
        endTimestamp: Long? = null,
        note: String? = null,
        payloadJson: String = "{}",
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        photoLocalPaths: List<String> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
        clientUuid: String = newClientUuid(),
    ): Long {
        // Create path: zero clock skew (same as updateRecord). Future → schedule plan.
        RecordTime.intervalError(timestamp, endTimestamp, nowMillis)?.let {
            throw IllegalArgumentException(it)
        }
        validateSleepInterval(type, timestamp, endTimestamp)
        val photos = photoLocalPaths
        val persistedPayload = requireCurrentPayloadJson(
            type = type,
            payloadJson = payloadJson,
            schemaVersion = schemaVersion,
        )
        val now = System.currentTimeMillis()
        require(clientUuid.isNotBlank()) { "记录写入标识不能为空" }
        val record = RecordEntity(
            clientUuid = clientUuid,
            babyId = babyId,
            type = type.key,
            timestamp = timestamp,
            endTimestamp = endTimestamp,
            note = note,
            payloadJson = persistedPayload,
            schemaVersion = schemaVersion,
            updatedAt = now,
        )
        // Path gate before Room so attach shares exclusion with media file GC.
        val id = photoAttachmentReconciler.withInvolvedPaths(
            owner = null,
            additionalPaths = photos,
        ) {
            if (type == RecordType.SLEEP && endTimestamp == null) {
                sleepMutationMutex.withLock {
                    transactionRunner.run {
                        val existing = recordDao.getByClientUuid(clientUuid)
                        if (existing != null) {
                            check(existing.deletedAt == null) {
                                "这次记录已删除，请重新填写"
                            }
                            require(existing.babyId == babyId && existing.type == type.key) {
                                "记录写入标识与既有记录冲突"
                            }
                            return@run existing.id
                        }
                        requireActiveBaby(babyId)
                        healDuplicateOpenSleeps(babyId)
                        if (recordDao.findOpenSleep(babyId) != null) {
                            throw SleepStateChangedException()
                        }
                        val inserted = insertRecord(record)
                        photoAttachmentReconciler.reconcile(
                            PhotoAttachmentOwner.Record(inserted),
                            photos,
                            now,
                        )
                        inserted
                    }
                }
            } else {
                transactionRunner.run {
                    val existing = recordDao.getByClientUuid(clientUuid)
                    if (existing != null) {
                        check(existing.deletedAt == null) {
                            "这次记录已删除，请重新填写"
                        }
                        require(existing.babyId == babyId && existing.type == type.key) {
                            "记录写入标识与既有记录冲突"
                        }
                        return@run existing.id
                    }
                    requireActiveBaby(babyId)
                    val inserted = insertRecord(record)
                    photoAttachmentReconciler.reconcile(
                        PhotoAttachmentOwner.Record(inserted),
                        photos,
                        now,
                    )
                    inserted
                }
            }
        }
        requestLocalSync()
        return id
    }

    suspend fun updateRecord(
        id: Long,
        timestamp: Long,
        endTimestamp: Long?,
        note: String?,
        payloadJson: String,
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        /** Null preserves current photos; an explicit empty list clears them. */
        photoLocalPaths: List<String>? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        // Future start must use [convertRecordToCarePlan] after explicit UI confirm.
        // Closed-interval end also uses zero skew (invalid fact, not convert).
        RecordTime.intervalError(timestamp, endTimestamp, nowMillis)?.let {
            throw IllegalArgumentException(it)
        }
        val photos = photoLocalPaths
        val now = System.currentTimeMillis()
        val owner = PhotoAttachmentOwner.Record(id)
        val cleanupCandidates = photoAttachmentReconciler.withInvolvedPaths(
            owner = owner,
            additionalPaths = photos.orEmpty(),
        ) {
            sleepMutationMutex.withLock {
                transactionRunner.run {
                    val existing = recordDao.get(id) ?: return@run emptySet<String>()
                    val canManage = actorCanManageRecord(existing)
                    if (!canManage) {
                        throw RecordPermissionException()
                    }
                    requireActiveBaby(existing.babyId)
                    val type = RecordType.fromKey(existing.type) ?: error("未知记录类型")
                    requireCurrentPayloadDocument(type, existing.payloadJson, existing.schemaVersion)
                    if (
                        type == RecordType.SLEEP &&
                        existing.endTimestamp != null &&
                        endTimestamp == null
                    ) {
                        throw IllegalArgumentException("已完成的睡眠不可改为进行中")
                    }
                    // Sleep wake corrections go through WakeObservationCoordinator, not B1.
                    val effectiveTimestamp = timestamp
                    val effectivePayload = requireCurrentPayloadJson(
                        type = type,
                        payloadJson = payloadJson,
                        schemaVersion = schemaVersion,
                    )
                    val effectiveSchema = schemaVersion
                    validateSleepInterval(type, effectiveTimestamp, endTimestamp)
                    updateRecordEntity(
                        existing.copy(
                            timestamp = effectiveTimestamp,
                            endTimestamp = endTimestamp,
                            note = note,
                            payloadJson = effectivePayload,
                            schemaVersion = effectiveSchema,
                            updatedAt = now,
                        ),
                    )
                    photos?.let {
                        photoAttachmentReconciler.reconcile(
                            owner,
                            it,
                            now,
                        )
                    }?.tombstonedClientUuids.orEmpty()
                }
            }
        }
        cleanupCommittedPhotoTombstones(cleanupCandidates)
        requestLocalSync()
    }

    /**
     * Explicit conversion of an existing fact Record whose time was edited to the future.
     *
     * Single local transaction: soft-delete the record (media tombstones), create a pending
     * CarePlan transferring type/payload/note/photos, and store [CarePlan.sourceRecordClientUuid]
     * for later family-sync provenance. Any failure rolls back fully so the original record and
     * attachments stay visible with no dual-active media ownership.
     *
     * Does not start nursing timers or open sleep intervals. Post-commit reminder/projection
     * reuses the same path as [createCarePlan].
     *
     * @return new care plan local id
     */
    suspend fun convertRecordToCarePlan(
        recordId: Long,
        scheduledAt: Long,
        note: String? = null,
        payloadJson: String? = null,
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        photoLocalPaths: List<String> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
        projectToSystemCalendar: Boolean = true,
        clientUuid: String = newClientUuid(),
    ): Long {
        require(scheduledAt > nowMillis) { "转为护理计划须选择未来时刻" }
        require(clientUuid.isNotBlank()) { "转计划写入标识不能为空" }
        carePlanDao.getByClientUuid(clientUuid)?.let { replay ->
            check(replay.deletedAt == null) { "这次护理计划已删除，请重新填写" }
            val source = recordDao.getIncludingDeleted(recordId)
            require(source != null && replay.sourceRecordClientUuid == source.clientUuid) {
                "转计划写入标识与既有计划冲突"
            }
            requestLocalSync()
            replay.toModel().let { plan ->
                projectOrScheduleCarePlanReminder(plan, projectToSystemCalendar)
            }
            return replay.id
        }
        val photos = photoLocalPaths
        val peek = recordDao.get(recordId) ?: error("记录不存在")
        if (peek.deletedAt != null) error("记录已删除")
        val type = RecordType.fromKey(peek.type) ?: error("未知记录类型")
        requireCurrentPayloadDocument(type, peek.payloadJson, peek.schemaVersion)
        require(type.isPlanableCarePlanType || type == RecordType.CUSTOM) {
            "该项目不可转为护理计划"
        }

        // Global lock order: path gate → sleepMutationMutex → Room (never invert).
        val recordOwner = PhotoAttachmentOwner.Record(recordId)
        val (planId, cleanupCandidates) = photoAttachmentReconciler.withInvolvedPaths(
            owner = recordOwner,
            additionalPaths = photos,
        ) {
            suspend fun writeConvert(): Pair<Long, Set<String>> = transactionRunner.run {
                val existing = recordDao.get(recordId) ?: error("记录不存在")
                if (existing.deletedAt != null) error("记录已删除")
                requireCanManageRecord(existing)
                requireActiveBaby(existing.babyId)
                val resolvedType = RecordType.fromKey(existing.type) ?: error("未知记录类型")
                requireCurrentPayloadDocument(
                    resolvedType,
                    existing.payloadJson,
                    existing.schemaVersion,
                )
                require(resolvedType.isPlanableCarePlanType || resolvedType == RecordType.CUSTOM) {
                    "该项目不可转为护理计划"
                }
                val nextPayload = payloadJson ?: existing.payloadJson
                requireCurrentPayloadDocument(resolvedType, nextPayload, schemaVersion)
                val resolvedCustomItemId: Long?
                val stampedPayload: String
                if (resolvedType == RecordType.CUSTOM) {
                    val decoded = RecordPayloadCodec.decode(
                        RecordType.CUSTOM,
                        nextPayload,
                        schemaVersion,
                    ).payload as? CustomPayload
                    val id = decoded?.customItemId?.takeIf { it > 0L }
                        ?: error("具体自定义项目才可转为护理计划")
                    resolvedCustomItemId = id
                    val def = customItemDao.getById(id)
                    stampedPayload = if (def != null && def.deletedAt == null) {
                        stampCustomItemSnapshotIntoPayload(
                            payloadJson = nextPayload,
                            customItemId = id,
                            titleSnapshot = def.name,
                            iconSlot = def.iconSlot,
                        )
                    } else {
                        // Keep historical name/icon snapshot when definition is gone.
                        nextPayload
                    }
                } else {
                    resolvedCustomItemId = null
                    stampedPayload = nextPayload
                }
                val persistedPayload = requireCurrentPayloadJson(
                    type = resolvedType,
                    payloadJson = stampedPayload,
                    schemaVersion = schemaVersion,
                )

                val at = nextSyncUpdatedAt(existing.updatedAt, System.currentTimeMillis())
                recordDao.softDelete(recordId, at)
                val recordPhotoMutation = photoAttachmentReconciler.tombstone(
                    PhotoAttachmentOwner.Record(recordId),
                    at,
                )

                // Plan media rows are new ownership (separate clientUuids); record media
                // remain tombstoned only. Same localUri may be referenced by both, but
                // only plan rows stay active after commit.
                val planId = carePlanDao.upsert(
                    CarePlanEntity(
                        clientUuid = clientUuid,
                        babyId = existing.babyId,
                        type = resolvedType.key,
                        customItemId = resolvedCustomItemId,
                        scheduledAt = scheduledAt,
                        scheduledZoneId = zone.id,
                        note = note,
                        payloadJson = persistedPayload,
                        schemaVersion = schemaVersion,
                        status = CarePlanStatus.PENDING.storageKey,
                        createdByMembershipId = currentMembershipActorId(),
                        sourceRecordClientUuid = existing.clientUuid,
                        updatedAt = at,
                        syncDirty = true,
                        systemCalendarProjectionEnabled = projectToSystemCalendar,
                    ),
                )
                photoAttachmentReconciler.reconcile(
                    PhotoAttachmentOwner.CarePlan(planId),
                    photos,
                    at,
                )
                planId to recordPhotoMutation.tombstonedClientUuids
            }
            if (type == RecordType.SLEEP) {
                // Same mutex as soft-delete/open-sleep so convert cannot leave half-live intervals.
                sleepMutationMutex.withLock { writeConvert() }
            } else {
                writeConvert()
            }
        }
        // The converted family data is publishable before optional device-local projection.
        cleanupCommittedPhotoTombstones(cleanupCandidates)
        requestLocalSync()
        // Creator keeps full local plan + projection immediately.
        carePlanDao.get(planId)?.toModel()?.let { plan ->
            projectOrScheduleCarePlanReminder(plan, projectToSystemCalendar)
        }
        return planId
    }

    suspend fun deleteRecord(id: Long): Boolean {
        val owner = PhotoAttachmentOwner.Record(id)
        val (deleted, cleanupCandidates) = photoAttachmentReconciler.withInvolvedPaths(owner) {
            sleepMutationMutex.withLock {
                transactionRunner.run {
                    val existing = recordDao.get(id)
                    if (existing != null && existing.deletedAt == null) {
                        requireCanManageRecord(existing)
                        val deletedAt = nextSyncUpdatedAt(
                            existing.updatedAt,
                            System.currentTimeMillis(),
                        )
                        recordDao.softDelete(id, deletedAt)
                        val tombstones = photoAttachmentReconciler.tombstone(
                            owner,
                            deletedAt,
                        ).tombstonedClientUuids
                        true to tombstones
                    } else {
                        false to emptySet()
                    }
                }
            }
        }
        if (!deleted) return false
        cleanupCommittedPhotoTombstones(cleanupCandidates)
        requestLocalSync()
        return true
    }

    /**
     * Whether the actor may fully edit/delete/convert this nursing record.
     * Same membership rule as care plans: creator or family owner/admin.
     * B1 family-wake correction does **not** grant manage (delete stays forbidden).
     */
    fun canManageRecord(
        record: Record,
        actorMembershipId: String,
        actorIsAdmin: Boolean,
    ): Boolean = canManageCreatorOwnedFamilyEntity(
        creatorMembershipId = record.createdByMembershipId,
        actorMembershipId = actorMembershipId,
        actorIsAdmin = actorIsAdmin,
    )

    suspend fun canManageRecord(record: Record): Boolean {
        val session = syncPort.session().first()
        return canManageCreatorOwnedFamilyEntity(
            creatorMembershipId = record.createdByMembershipId,
            actorMembershipId = session.membershipId.trim(),
            actorIsAdmin = session.role == FamilyRole.Owner,
            creatorAcknowledgementPending = session.isCreatorAcknowledgementPending(
                entityType = "record",
                clientUuid = record.clientUuid,
            ),
        )
    }

    /** Timeline edit chrome: author or Owner only (B1 closer privilege retired). */
    suspend fun canEditRecord(record: Record): Boolean = canManageRecord(record)

    /** Delete stays author-or-owner. */
    suspend fun canDeleteRecord(record: Record): Boolean = canManageRecord(record)

    /**
     * @deprecated Ticket 06: B1 family-wake privilege is retired. Always false.
     * Use [WakeObservationCoordinator.canEditWake] for wake corrections.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun hasActiveFamilyWakePrivilege(record: Record): Boolean = false

    private suspend fun actorCanManageRecord(record: RecordEntity): Boolean {
        val session = syncPort.session().first()
        return canManageCreatorOwnedFamilyEntity(
            creatorMembershipId = record.createdByMembershipId,
            actorMembershipId = session.membershipId.trim(),
            actorIsAdmin = session.role == FamilyRole.Owner,
            creatorAcknowledgementPending = session.isCreatorAcknowledgementPending(
                entityType = "record",
                clientUuid = record.clientUuid,
            ),
        )
    }

    private suspend fun requireCanManageRecord(record: RecordEntity) {
        if (!actorCanManageRecord(record)) throw RecordPermissionException()
    }

    suspend fun completeNursing(
        babyId: Long,
        leftMin: Int,
        rightMin: Int,
        order: String,
        amountMl: Int? = null,
        note: String? = null,
        startedAt: Long,
        endedAt: Long,
        recordMode: String = "end",
        completionClientUuid: String = newClientUuid(),
        /**
         * When set, complete this open nursing CarePlan in the same transaction as
         * the timer record and clone the plan's current active photos (0–3) onto
         * the new Record as independent MediaAsset rows (shared local_uri).
         * Cancel / save-failure leave the plan pending and roll back any clone.
         */
        carePlanId: Long? = null,
        /**
         * Optional explicit record photo paths (Ticket 09 seed merge with Ticket 08
         * live plan media, already deduped and capped 0–3). When non-null, these
         * paths are reconciled onto the Record instead of cloning plan media alone.
         * When null and [carePlanId] is set, falls back to live plan media clone.
         */
        photoLocalPaths: List<String>? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        require(order in NURSING_ORDER_ALLOWLIST) {
            "不支持的哺乳顺序"
        }
        require(recordMode in setOf("start", "end")) {
            "不支持的记录时刻模式"
        }
        // Create: 0 skew. Completing a linked plan reuses fulfill actual-time skew.
        val actualSkew = if (carePlanId != null) {
            RecordTime.FULFILLMENT_ACTUAL_TIME_SKEW_MILLIS
        } else {
            0L
        }
        RecordTime.pointError(startedAt, nowMillis, actualSkew)?.let {
            throw IllegalArgumentException(it)
        }
        RecordTime.pointError(endedAt, nowMillis, actualSkew)?.let {
            throw IllegalArgumentException(it)
        }
        val payload = RecordPayloadCodec.encode(
            RecordPayloadDocument(
                type = RecordType.NURSING,
                payload = NursingPayload(
                    leftMinutes = leftMin,
                    rightMinutes = rightMin,
                    order = order,
                    amountMl = amountMl,
                    recordMode = recordMode,
                ),
                schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
            ),
        )
        require(completionClientUuid.isNotBlank()) { "计时完成标识不能为空" }
        val now = System.currentTimeMillis()
        // Ticket 09: explicit seed merge paths; Ticket 08 fallback: live plan media.
        val recordPhotos = when {
            photoLocalPaths != null -> photoLocalPaths
            carePlanId != null -> listCarePlanPhotoPaths(carePlanId)
            else -> emptyList()
        }
        val id = photoAttachmentReconciler.withInvolvedPaths(
            owner = null,
            additionalPaths = recordPhotos,
        ) {
            transactionRunner.run {
                val existing = recordDao.getByClientUuid(completionClientUuid)
                if (existing != null) {
                    check(existing.deletedAt == null) {
                        "这次计时记录已删除，请重试或改记"
                    }
                    require(existing.babyId == babyId && existing.type == RecordType.NURSING.key) {
                        "计时完成标识与既有记录冲突"
                    }
                    // Idempotent replay: if a plan was linked, ensure it is completed
                    // against this same record (no second session) and candidate identity.
                    if (carePlanId != null) {
                        completeOpenCarePlanWithRecord(
                            carePlanId,
                            babyId,
                            RecordType.NURSING,
                            existing.clientUuid,
                            now,
                            existing.timestamp,
                        )
                    }
                    return@run existing.id
                }
                requireActiveBaby(babyId)
                val recordTimestamp = if (recordMode == "start") startedAt else endedAt
                val inserted = insertRecord(
                    RecordEntity(
                        clientUuid = completionClientUuid,
                        babyId = babyId,
                        type = RecordType.NURSING.key,
                        timestamp = recordTimestamp,
                        endTimestamp = endedAt.takeIf { recordMode == "start" },
                        note = note,
                        payloadJson = payload,
                        schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                        updatedAt = now,
                    ),
                )
                if (recordPhotos.isNotEmpty()) {
                    // Independent record MediaAsset rows (shared local path with plan when
                    // overlapping). Plan ownership/order stay unchanged; replay skips clone.
                    photoAttachmentReconciler.reconcile(
                        PhotoAttachmentOwner.Record(inserted),
                        recordPhotos,
                        now,
                    )
                }
                if (carePlanId != null) {
                    completeOpenCarePlanWithRecord(
                        carePlanId,
                        babyId,
                        RecordType.NURSING,
                        completionClientUuid,
                        now,
                        recordTimestamp,
                    )
                }
                inserted
            }
        }
        if (carePlanId != null) {
            cancelCarePlanReminderAndProjection(carePlanId)
        }
        requestLocalSync()
        return id
    }

    /**
     * Confirm a stateful sleep action against the latest open interval.
     *
     * The check and local write share one process-level critical section so
     * two confirmations cannot both act on the same observed sleep state.
     *
     * Wake (closing an open interval) creates an independent WakeObservation
     * (ticket 06 / ADR-0021) and does **not** rewrite SleepStart `endTimestamp`.
     * Any joined membership may record a wake. Observer self-edit/withdraw
     * replaces device-local B1 closer privilege.
     */
    suspend fun confirmSleep(
        babyId: Long,
        expectedOpenSleepId: Long?,
        timestamp: Long,
        endTimestamp: Long?,
        note: String?,
        payloadJson: String,
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        photoLocalPaths: List<String> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
        clientUuid: String = newClientUuid(),
    ): Long {
        // Create path: zero clock skew on start; wake end validated separately.
        if (expectedOpenSleepId == null) {
            RecordTime.intervalError(timestamp, endTimestamp, nowMillis)?.let {
                throw IllegalArgumentException(it)
            }
        } else if (endTimestamp != null) {
            RecordTime.pointError(endTimestamp, nowMillis)?.let {
                throw IllegalArgumentException(it)
            }
        } else {
            RecordTime.intervalError(timestamp, endTimestamp, nowMillis)?.let {
                throw IllegalArgumentException(it)
            }
        }
        require(clientUuid.isNotBlank()) { "睡眠写入标识不能为空" }
        val photos = photoLocalPaths
        val incomingPayload = requireCurrentPayloadJson(
            type = RecordType.SLEEP,
            payloadJson = payloadJson,
            schemaVersion = schemaVersion,
        )
        val isWakeClose = expectedOpenSleepId != null && endTimestamp != null
        // Wake close: photos attach to WakeObservation, not Sleep log media.
        // [clientUuid] is the WakeObservation identity (composer idempotency), not Sleep.
        if (isWakeClose) {
            val wakeCoordinator = wakeObservations
            val wakeAt = requireNotNull(endTimestamp)
            val openId = requireNotNull(expectedOpenSleepId)
            // Exact replay by wake mutation identity.
            val existingWake = wakeCoordinator.get(clientUuid)
            if (existingWake != null) {
                return openId
            }
            sleepMutationMutex.withLock {
                transactionRunner.run {
                    requireActiveBaby(babyId)
                    val current = recordDao.get(openId)
                        ?: throw SleepStateChangedException()
                    val openCandidates = wakeCoordinator.listTrulyOpenSleeps(babyId)
                    if (openCandidates.none { it.id == openId }) {
                        // Already provisionally closed with same end+note (legacy replay).
                        val match = wakeCoordinator.listForSleep(current.clientUuid).firstOrNull {
                            it.wakeTimestamp == wakeAt && it.note == note && !it.withdrawn
                        }
                        if (match != null) return@run
                        throw SleepStateChangedException()
                    }
                }
            }
            wakeCoordinator.recordWake(
                babyId = babyId,
                at = wakeAt,
                note = note,
                photoLocalPaths = photos,
                nowMillis = nowMillis,
                sleepRecordId = openId,
                clientUuid = clientUuid,
            )
            return openId
        }
        val ownerHint = expectedOpenSleepId?.let(PhotoAttachmentOwner::Record)
        val (id, cleanupCandidates) = photoAttachmentReconciler.withInvolvedPaths(
            owner = ownerHint,
            additionalPaths = photos,
        ) {
            sleepMutationMutex.withLock {
                transactionRunner.run {
                    requireActiveBaby(babyId)
                    val replay = recordDao.getByClientUuid(clientUuid)
                    if (replay != null) {
                        check(replay.deletedAt == null) {
                            "这次睡眠记录已删除，请重新填写"
                        }
                        require(
                            replay.babyId == babyId && replay.type == RecordType.SLEEP.key,
                        ) {
                            "睡眠写入标识与既有记录冲突"
                        }
                        return@run replay.id to emptySet<String>()
                    }
                    // Ticket 06: never auto-close overlapping open SleepStarts.
                    val currentOpen = wakeObservations.findWakeShortcutTarget(babyId)
                    if (expectedOpenSleepId == null) {
                        if (currentOpen != null) throw SleepStateChangedException()
                        // New SleepStart only — end_timestamp forbidden on wire for sleep.
                        require(endTimestamp == null) {
                            "新睡眠只能记录入睡；醒来请使用醒来观察"
                        }
                        validateSleepInterval(RecordType.SLEEP, timestamp, null)
                        val now = System.currentTimeMillis()
                        val inserted = insertRecord(
                            RecordEntity(
                                clientUuid = clientUuid,
                                babyId = babyId,
                                type = RecordType.SLEEP.key,
                                timestamp = timestamp,
                                endTimestamp = null,
                                note = note,
                                payloadJson = incomingPayload,
                                schemaVersion = schemaVersion,
                                updatedAt = now,
                            ),
                        )
                        val photoMutation = photoAttachmentReconciler.reconcile(
                            PhotoAttachmentOwner.Record(inserted),
                            photos,
                            now,
                        )
                        inserted to photoMutation.tombstonedClientUuids
                    } else {
                        // Non-wake edit of open sleep (rare): author manages start fields only.
                        if (currentOpen?.id != expectedOpenSleepId) {
                            throw SleepStateChangedException()
                        }
                        requireCanManageRecord(currentOpen)
                        requireCurrentPayloadDocument(
                            RecordType.SLEEP,
                            currentOpen.payloadJson,
                            currentOpen.schemaVersion,
                        )
                        validateSleepInterval(RecordType.SLEEP, timestamp, null)
                        val now = System.currentTimeMillis()
                        updateRecordEntity(
                            currentOpen.copy(
                                timestamp = timestamp,
                                endTimestamp = null,
                                note = note,
                                payloadJson = incomingPayload,
                                schemaVersion = schemaVersion,
                                updatedAt = now,
                            ),
                        )
                        val photoMutation = photoAttachmentReconciler.reconcile(
                            PhotoAttachmentOwner.Record(expectedOpenSleepId),
                            photos,
                            now,
                        )
                        expectedOpenSleepId to photoMutation.tombstonedClientUuids
                    }
                }
            }
        }
        cleanupCommittedPhotoTombstones(cleanupCandidates)
        requestLocalSync()
        return id
    }

    suspend fun sleepDown(
        babyId: Long,
        at: Long = System.currentTimeMillis(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        RecordTime.pointError(at, nowMillis)?.let { throw IllegalArgumentException(it) }
        val id = sleepMutationMutex.withLock {
            transactionRunner.run {
                requireActiveBaby(babyId)
                // Ticket 06: keep overlapping open SleepStarts; do not auto-close.
                // If a true open already exists (no wake yet), refuse a second silent
                // open via this shortcut — mark anomaly on the latest open only when
                // the user retries sleepDown while still open (historical UX).
                val open = wakeObservations.findWakeShortcutTarget(babyId)
                if (open != null) {
                    val flagged = withAnomaly(open.payloadJson, open.schemaVersion)
                    if (flagged.first != open.payloadJson) {
                        updateRecordEntity(
                            open.copy(
                                payloadJson = flagged.first,
                                schemaVersion = flagged.second,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    return@run open.id
                }

                val now = System.currentTimeMillis()
                insertRecord(
                    RecordEntity(
                        clientUuid = newClientUuid(),
                        babyId = babyId,
                        type = RecordType.SLEEP.key,
                        timestamp = at,
                        endTimestamp = null,
                        note = null,
                        payloadJson = RecordPayloadCodec.encode(
                            RecordPayloadDocument(
                                type = RecordType.SLEEP,
                                payload = SleepPayload(),
                                schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                            ),
                        ),
                        updatedAt = now,
                    ),
                )
            }
        }
        requestLocalSync()
        return id
    }

    /**
     * Record wake for the latest open SleepStart as a WakeObservation.
     *
     * Does **not** rewrite Sleep `endTimestamp`. Returns the Sleep record local id
     * (stable for dock/composer callers that expect the sleep row id).
     */
    suspend fun sleepUp(
        babyId: Long,
        at: Long = System.currentTimeMillis(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        RecordTime.pointError(at, nowMillis)?.let { throw IllegalArgumentException(it) }
        val wakeCoordinator = wakeObservations
        requireActiveBaby(babyId)
        val open = sleepMutationMutex.withLock {
            transactionRunner.run {
                wakeCoordinator.findWakeShortcutTarget(babyId)
            }
        }
        if (open != null) {
            validateWakeTimestamp(open.timestamp, at)?.let {
                throw IllegalArgumentException(it)
            }
            wakeCoordinator.recordWake(
                babyId = babyId,
                at = at,
                nowMillis = nowMillis,
                sleepRecordId = open.id,
            )
            return open.id
        }

        // No open sleep: create SleepStart + WakeObservation (1-minute anomaly interval).
        val start = at - 60_000L
        validateWakeTimestamp(start, at)?.let { throw IllegalArgumentException(it) }
        val sleepId = sleepMutationMutex.withLock {
            transactionRunner.run {
                val now = System.currentTimeMillis()
                insertRecord(
                    RecordEntity(
                        clientUuid = newClientUuid(),
                        babyId = babyId,
                        type = RecordType.SLEEP.key,
                        timestamp = start,
                        endTimestamp = null,
                        note = null,
                        payloadJson = RecordPayloadCodec.encode(
                            RecordPayloadDocument(
                                type = RecordType.SLEEP,
                                payload = SleepPayload(anomaly = true),
                                schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                            ),
                        ),
                        schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                        updatedAt = now,
                    ),
                )
            }
        }
        wakeCoordinator.recordWake(
            babyId = babyId,
            at = at,
            nowMillis = nowMillis,
            sleepRecordId = sleepId,
        )
        return sleepId
    }


    internal suspend fun insertRecord(
        record: RecordEntity,
        allowDeletedCustomDefinitionForFulfillment: Boolean = false,
    ): Long {
        val type = RecordType.fromKey(record.type) ?: error("未知记录类型")
        val document = requireCurrentPayloadDocument(
            type,
            record.payloadJson,
            record.schemaVersion,
        )
        if (type == RecordType.CUSTOM) {
            val customItemId = (document.payload as CustomPayload).customItemId
            requireLiveCustomDefinition(
                document = document,
                definition = customItemDao.getById(customItemId),
                allowDeletedForFulfillment = allowDeletedCustomDefinitionForFulfillment,
            )
        }
        val membershipId = currentMembershipActorId()
        return recordDao.upsert(
            if (record.createdByMembershipId.isBlank() && membershipId.isNotEmpty()) {
                record.copy(createdByMembershipId = membershipId)
            } else {
                record
            },
        )
    }

    internal suspend fun updateRecordEntity(record: RecordEntity) {
        val type = RecordType.fromKey(record.type) ?: error("未知记录类型")
        requireCurrentPayloadDocument(type, record.payloadJson, record.schemaVersion)
        val previous = recordDao.getIncludingDeleted(record.id)?.updatedAt
        recordDao.update(
            record.copy(
                updatedAt = previous
                    ?.let { nextSyncUpdatedAt(it, record.updatedAt) }
                    ?: record.updatedAt,
                syncDirty = true,
            ),
        )
    }

    /**
     * Ticket 06 / ADR-0021: overlapping open SleepStarts are retained.
     * Older opens surface as overlap-pending via [projectSleepInterval]; this
     * method is intentionally a no-op so pull/sync never synthesizes end times.
     */
    internal suspend fun healDuplicateOpenSleeps(babyId: Long) {
        // No-op: do not auto-close, edit, or tombstone older open SleepStarts.
    }


    /** Logical writes stay committed when best-effort physical GC must retry. */
    internal suspend fun cleanupCommittedPhotoTombstones(clientUuids: Set<String>) {
        if (clientUuids.isEmpty()) return
        syncPort.cleanupTombstonedMedia(clientUuids)
    }
}

internal fun requireLiveCustomDefinition(
    document: RecordPayloadDocument,
    definition: CustomItemEntity?,
    allowDeletedForFulfillment: Boolean = false,
) {
    if (document.type != RecordType.CUSTOM) return
    val customItemId = (document.payload as? CustomPayload)?.customItemId
        ?: throw IllegalArgumentException("CUSTOM 必须携带具体项目身份")
    require(
        definition?.id == customItemId &&
            (definition.deletedAt == null || allowDeletedForFulfillment),
    ) {
        "该自定义项目已删除或不存在，不能新建记录"
    }
}

private val NURSING_ORDER_ALLOWLIST = setOf("L", "R", "LR", "RL")

private fun withAnomaly(payloadJson: String, schemaVersion: Int): Pair<String, Int> {
    val document = RecordPayloadCodec.decode(RecordType.SLEEP, payloadJson, schemaVersion)
    val sleep = document.payload as? SleepPayload ?: return payloadJson to schemaVersion
    val normalized = document.copy(
        payload = sleep.copy(anomaly = true),
        schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
    )
    return RecordPayloadCodec.encode(normalized) to CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
}
