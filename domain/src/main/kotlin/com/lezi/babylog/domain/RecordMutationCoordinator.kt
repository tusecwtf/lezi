package com.lezi.babylog.domain

import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.CustomPayload
import com.lezi.babylog.core.model.NursingPayload
import com.lezi.babylog.core.model.OpenSleepCandidate
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordPayloadDocument
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SleepPayload
import com.lezi.babylog.core.model.isPlanableCarePlanType
import com.lezi.babylog.core.model.normalizeOpenSleeps
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.PolicyClock
import com.lezi.babylog.sync.SyncPort
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RecordMutationCoordinator(
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val customItemDao: CustomItemDao,
    private val photoAttachmentReconciler: PhotoAttachmentReconciler,
    private val transactionRunner: DatabaseTransactionRunner,
    private val reminderProjection: CarePlanReminderProjection,
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
        val clientUuid = newClientUuid()
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
        val id = if (type == RecordType.SLEEP && endTimestamp == null) {
            sleepMutationMutex.withLock {
                transactionRunner.run {
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
        val cleanupCandidates = sleepMutationMutex.withLock {
            transactionRunner.run {
                val existing = recordDao.get(id) ?: return@run emptySet<String>()
                requireCanManageRecord(existing)
                requireActiveBaby(existing.babyId)
                val type = RecordType.fromKey(existing.type) ?: error("未知记录类型")
                requireCurrentPayloadDocument(type, existing.payloadJson, existing.schemaVersion)
                val persistedPayload = requireCurrentPayloadJson(
                    type = type,
                    payloadJson = payloadJson,
                    schemaVersion = schemaVersion,
                )
                if (
                    type == RecordType.SLEEP &&
                    existing.endTimestamp != null &&
                    endTimestamp == null
                ) {
                    throw IllegalArgumentException("已完成的睡眠不可改为进行中")
                }
                validateSleepInterval(type, timestamp, endTimestamp)
                updateRecordEntity(
                    existing.copy(
                        timestamp = timestamp,
                        endTimestamp = endTimestamp,
                        note = note,
                        payloadJson = persistedPayload,
                        schemaVersion = schemaVersion,
                        updatedAt = now,
                    ),
                )
                photos?.let {
                    photoAttachmentReconciler.reconcile(
                        PhotoAttachmentOwner.Record(id),
                        it,
                        now,
                    )
                }?.tombstonedClientUuids.orEmpty()
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
    ): Long {
        require(scheduledAt > nowMillis) { "转为护理计划须选择未来时刻" }
        val photos = photoLocalPaths
        val peek = recordDao.get(recordId) ?: error("记录不存在")
        if (peek.deletedAt != null) error("记录已删除")
        val type = RecordType.fromKey(peek.type) ?: error("未知记录类型")
        requireCurrentPayloadDocument(type, peek.payloadJson, peek.schemaVersion)
        require(type.isPlanableCarePlanType || type == RecordType.CUSTOM) {
            "该项目不可转为护理计划"
        }

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
                    clientUuid = newClientUuid(),
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

        val (planId, cleanupCandidates) = if (type == RecordType.SLEEP) {
            // Same mutex as soft-delete/open-sleep so convert cannot leave half-live intervals.
            sleepMutationMutex.withLock { writeConvert() }
        } else {
            writeConvert()
        }
        // The converted family data is publishable before optional device-local projection.
        cleanupCommittedPhotoTombstones(cleanupCandidates)
        requestLocalSync()
        // Creator keeps full local plan + projection immediately.
        carePlanDao.get(planId)?.toModel()?.let { plan ->
            reminderProjection.projectOrScheduleCarePlanReminder(
                plan,
                projectToSystemCalendar = projectToSystemCalendar,
            )
        }
        return planId
    }

    suspend fun deleteRecord(id: Long): Boolean {
        val (deleted, cleanupCandidates) = sleepMutationMutex.withLock {
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
                        PhotoAttachmentOwner.Record(id),
                        deletedAt,
                    ).tombstonedClientUuids
                    true to tombstones
                } else {
                    false to emptySet()
                }
            }
        }
        if (!deleted) return false
        cleanupCommittedPhotoTombstones(cleanupCandidates)
        requestLocalSync()
        return true
    }

    /**
     * Whether the actor may edit/delete/convert this nursing record.
     * Same membership rule as care plans: creator or family owner/admin.
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
        val id = transactionRunner.run {
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
            if (carePlanId != null) {
                // Clone current plan media into independent record rows (shared local path).
                // Plan ownership/order stay unchanged; replay path above skips this clone.
                val planPhotos = listCarePlanPhotoPaths(carePlanId)
                photoAttachmentReconciler.reconcile(
                    PhotoAttachmentOwner.Record(inserted),
                    planPhotos,
                    now,
                )
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
        if (carePlanId != null) {
            reminderProjection.cancelCarePlanReminderBestEffort(carePlanId)
            reminderProjection.removeSystemCalendarProjection(carePlanId)
        }
        requestLocalSync()
        return id
    }

    /**
     * Confirm a stateful sleep action against the latest open interval.
     *
     * The check and local write share one process-level critical section so
     * two confirmations cannot both act on the same observed sleep state.
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
    ): Long {
        // Create/close path: zero clock skew on start and closed end.
        RecordTime.intervalError(timestamp, endTimestamp, nowMillis)?.let {
            throw IllegalArgumentException(it)
        }
        val photos = photoLocalPaths
        val persistedPayload = requireCurrentPayloadJson(
            type = RecordType.SLEEP,
            payloadJson = payloadJson,
            schemaVersion = schemaVersion,
        )
        val (id, cleanupCandidates) = sleepMutationMutex.withLock {
            validateSleepInterval(RecordType.SLEEP, timestamp, endTimestamp)
            transactionRunner.run {
                requireActiveBaby(babyId)
                healDuplicateOpenSleeps(babyId)
                val currentOpen = recordDao.findOpenSleep(babyId)
                if (expectedOpenSleepId == null) {
                    if (currentOpen != null) throw SleepStateChangedException()
                    val now = System.currentTimeMillis()
                    val inserted = insertRecord(
                        RecordEntity(
                            clientUuid = newClientUuid(),
                            babyId = babyId,
                            type = RecordType.SLEEP.key,
                            timestamp = timestamp,
                            endTimestamp = endTimestamp,
                            note = note,
                            payloadJson = persistedPayload,
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
                    if (currentOpen?.id != expectedOpenSleepId) {
                        throw SleepStateChangedException()
                    }
                    requireCurrentPayloadDocument(
                        RecordType.SLEEP,
                        currentOpen.payloadJson,
                        currentOpen.schemaVersion,
                    )
                    val now = System.currentTimeMillis()
                    updateRecordEntity(
                        currentOpen.copy(
                            timestamp = timestamp,
                            endTimestamp = endTimestamp,
                            note = note,
                            payloadJson = persistedPayload,
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
                healDuplicateOpenSleeps(babyId)
                val open = recordDao.findOpenSleep(babyId)
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

    suspend fun sleepUp(
        babyId: Long,
        at: Long = System.currentTimeMillis(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        // Close/create path: wake time must not be in the future (0 skew).
        RecordTime.pointError(at, nowMillis)?.let { throw IllegalArgumentException(it) }
        val id = sleepMutationMutex.withLock {
            transactionRunner.run {
                requireActiveBaby(babyId)
                healDuplicateOpenSleeps(babyId)
                val open = recordDao.findOpenSleep(babyId)
                if (open != null) {
                    validateSleepInterval(RecordType.SLEEP, open.timestamp, at)
                    updateRecordEntity(
                        open.copy(
                            endTimestamp = at,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    return@run open.id
                }

                val now = System.currentTimeMillis()
                val start = at - 60_000L
                insertRecord(
                    RecordEntity(
                        clientUuid = newClientUuid(),
                        babyId = babyId,
                        type = RecordType.SLEEP.key,
                        timestamp = start,
                        endTimestamp = at,
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
        requestLocalSync()
        return id
    }


    internal suspend fun insertRecord(record: RecordEntity): Long {
        val type = RecordType.fromKey(record.type) ?: error("未知记录类型")
        requireCurrentPayloadDocument(type, record.payloadJson, record.schemaVersion)
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
     * Keep at most one open sleep per baby. Older open intervals are closed at
     * the next open's start and flagged anomaly (covers sync-introduced dups).
     */
    internal suspend fun healDuplicateOpenSleeps(babyId: Long) {
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
            val flagged = withAnomaly(current.payloadJson, current.schemaVersion)
            updateRecordEntity(
                current.copy(
                    endTimestamp = closure.closedAtMillis,
                    payloadJson = flagged.first,
                    schemaVersion = flagged.second,
                    updatedAt = now,
                ),
            )
        }
    }


    /** Logical writes stay committed when best-effort physical GC must retry. */
    internal suspend fun cleanupCommittedPhotoTombstones(clientUuids: Set<String>) {
        if (clientUuids.isEmpty()) return
        syncPort.cleanupTombstonedMedia(clientUuids)
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
