package com.lezi.babylog.domain
import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.MediaLocalPathGate
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.LocalUserDao
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.MembershipDao
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.ConflictDetailCacheDao
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.core.database.causal.SourceRelationDao
import com.lezi.babylog.core.database.causal.WakeObservationDao
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.ConflictNotAdoptedAudit
import com.lezi.babylog.core.model.FulfillmentCandidate
import com.lezi.babylog.core.model.NextFeedPlanReconciliation
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordPayloadDocument
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.CustomPayload
import com.lezi.babylog.core.model.Sex
import com.lezi.babylog.core.model.visibleNextFeedPlanNote
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import com.lezi.babylog.domain.calendar.NoOpSystemCalendarPort
import com.lezi.babylog.domain.calendar.SystemCalendarPort
import com.lezi.babylog.domain.carelog.CareLogQueries
import com.lezi.babylog.domain.carelog.ConflictAuditQueries
import com.lezi.babylog.domain.carelog.CareDayBounds
import com.lezi.babylog.domain.carelog.ConflictResolutionCoordinator
import com.lezi.babylog.domain.carelog.DailySummary
import com.lezi.babylog.domain.carelog.PhotoAttachmentReconciler
import com.lezi.babylog.domain.carelog.RecordMutationCoordinator
import com.lezi.babylog.domain.carelog.SleepRecordProjection
import com.lezi.babylog.domain.carelog.SourceRelationCoordinator
import com.lezi.babylog.domain.carelog.SourceRelationOutcome
import com.lezi.babylog.domain.carelog.SuspectedDuplicateBounds
import com.lezi.babylog.domain.carelog.SuspectedDuplicateGroup
import com.lezi.babylog.domain.carelog.WakeObservation
import com.lezi.babylog.domain.carelog.WakeObservationCoordinator
import com.lezi.babylog.domain.carelog.WeekSummary
import com.lezi.babylog.domain.carelog.WidgetSummaryDto
import com.lezi.babylog.domain.careplan.CarePlanCoordinator
import com.lezi.babylog.domain.careplan.CarePlanReminderProjection
import com.lezi.babylog.domain.careplan.ReminderCleanupPort
import com.lezi.babylog.domain.catalog.CustomItemCatalog
import com.lezi.babylog.domain.family.BabyFamilyProfileCoordinator
import com.lezi.babylog.domain.localdata.CalendarReminderMutationGuard
import com.lezi.babylog.domain.localdata.LocalDataMutationEpoch

data class CreateBabyInput(
    val nickname: String,
    val sex: String? = null,
    val birthdayEpochDay: Long,
    /** Birth weight in grams; null when not set. */
    val birthWeightGrams: Int? = null,
    val avatarPath: String? = null,
    val themeColorArgb: Int = DEFAULT_THEME_COLOR,
) {
    companion object {
        const val DEFAULT_THEME_COLOR: Int = 0xFF007BAE.toInt()
    }
}

data class UpdateBabyInput(
    val nickname: String,
    val sex: String? = null,
    val birthdayEpochDay: Long,
    val birthWeightGrams: Int? = null,
    val avatarPath: String? = null,
    val themeColorArgb: Int? = null,
)

/** Thrown when another active baby already uses the nickname. */
class DuplicateBabyNicknameException(val nickname: String) :
    IllegalArgumentException("宝宝昵称「$nickname」已存在")

class BabyProfilePermissionException :
    IllegalStateException("宝宝档案由家庭管理员管理")

class SleepStateChangedException :
    IllegalStateException("睡眠状态已变化，请重新打开睡眠菜单")

data class CustomRecordItem(
    val id: Long,
    val name: String,
    val iconSlot: Int,
    val sortOrder: Int,
    /** Stable family identity (clientUuid); not the local row id catalog key. */
    val clientUuid: String = "",
    /**
     * Server-minted membership id of the creator; empty for local-only definitions.
     */
    val createdByMembershipId: String = "",
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null,
)

class CustomItemLimitException :
    IllegalStateException("自定义项目最多 10 个")

class CarePlanPermissionException :
    IllegalStateException("无权管理此护理计划")

class RecordPermissionException :
    IllegalStateException("无权管理此护理记录")

/** Thrown when a non-admin tries to read or convert conflict-not-adopted audits. */
class ConflictAuditPermissionException :
    IllegalStateException("仅家庭管理员可查看冲突未采纳履行或转为独立记录")

class CustomItemPermissionException :
    IllegalStateException("无权修改该自定义项目")

data class LocalFamilyIdentity(
    val deviceId: String,
    val displayName: String,
    val familyId: Long,
)

data class BabyMergePreview(
    val sourceBabyId: Long,
    val sourceNickname: String,
    val targetBabyId: Long,
    val targetNickname: String,
    val recordCount: Int,
    val carePlanCount: Int,
)

@Singleton
class CareLog @Inject constructor(
    private val babyDao: BabyDao,
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val customItemDao: CustomItemDao,
    private val localUserDao: LocalUserDao,
    private val familyDao: FamilyDao,
    private val membershipDao: MembershipDao,
    private val mediaAssetDao: MediaAssetDao,
    private val settings: SettingsStore,
    private val syncPort: SyncPort,
    private val reminderCleanup: ReminderCleanupPort,
    private val transactionRunner: DatabaseTransactionRunner,
    private val systemCalendar: SystemCalendarPort = NoOpSystemCalendarPort(),
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val calendarReminderMutationGuard: CalendarReminderMutationGuard,
    private val clock: PolicyClock,
    /** Process-wide path gate shared with reference-aware media reclaim (Hilt singleton). */
    private val mediaPathGate: MediaLocalPathGate,
    private val localDataMutationEpoch: LocalDataMutationEpoch,
    private val wakeObservationDao: WakeObservationDao,
    private val conflictSummaryDao: ConflictSummaryDao,
    private val conflictDetailCacheDao: ConflictDetailCacheDao,
    private val sourceRelationDao: SourceRelationDao,
) {
    private val photoAttachmentReconciler = PhotoAttachmentReconciler(
        mediaAssetDao = mediaAssetDao,
        pathGate = mediaPathGate,
    )
    /**
     * Serializes record create, update, delete, and confirm operations that may
     * change active sleep state. Room transactions provide atomic writes; this
     * lock makes read-check-write sequences deterministic inside this process.
     */
    private val sleepMutationMutex = Mutex()
    private val wakeObservationCoordinator = WakeObservationCoordinator(
        recordDao = recordDao,
        wakeObservationDao = wakeObservationDao,
        mediaAssetDao = mediaAssetDao,
        transactionRunner = transactionRunner,
        syncPort = syncPort,
        sleepMutationMutex = sleepMutationMutex,
        currentMembershipActorId = { carePlans.currentMembershipActorId() },
        requestLocalSync = ::requestLocalSync,
        pathGate = mediaPathGate,
    )
    private val conflictResolutionCoordinator = ConflictResolutionCoordinator(
        conflictSummaryDao = conflictSummaryDao,
        conflictDetailCacheDao = conflictDetailCacheDao,
        syncPort = syncPort,
        recordDao = recordDao,
        wakeObservationDao = wakeObservationDao,
        babyDao = babyDao,
        carePlanDao = carePlanDao,
        customItemDao = customItemDao,
        transactionRunner = transactionRunner,
    )
    private val sourceRelationCoordinator = SourceRelationCoordinator(
        recordDao = recordDao,
        sourceRelationDao = sourceRelationDao,
        syncPort = syncPort,
        currentMembershipId = { carePlans.currentMembershipActorId() },
        isFamilyOwner = { carePlans.isFamilyAdmin() },
        nowMillis = { clock.nowMillis() },
    )
    private val queries = CareLogQueries(
        babyDao = babyDao,
        recordDao = recordDao,
        carePlanDao = carePlanDao,
        fulfillmentCandidateDao = fulfillmentCandidateDao,
        wakeObservationCoordinator = wakeObservationCoordinator,
    )
    private val reminderProjection = CarePlanReminderProjection(
        carePlanDao = carePlanDao,
        babyDao = babyDao,
        mediaAssetDao = mediaAssetDao,
        settings = settings,
        reminderCleanup = reminderCleanup,
        systemCalendar = systemCalendar,
        calendarReminderMutationGuard = calendarReminderMutationGuard,
    )
    private val conflictAuditQueries = ConflictAuditQueries(
        fulfillmentCandidateDao = fulfillmentCandidateDao,
        carePlanDao = carePlanDao,
        recordDao = recordDao,
        mediaAssetDao = mediaAssetDao,
        syncPort = syncPort,
        listRecordPhotoPaths = ::listRecordPhotoPaths,
    )
    private val nextFeedPlanMutationMutex = Mutex()
    private val customItemCatalog = CustomItemCatalog(
        customItemDao = customItemDao,
        transactionRunner = transactionRunner,
        syncPort = syncPort,
        resolveFamilyId = {
            getCurrentBaby()?.familyId
                ?: listBabies().firstOrNull()?.familyId
                ?: error("请先添加宝宝")
        },
        requestLocalSync = ::requestLocalSync,
    )

    private lateinit var carePlans: CarePlanCoordinator
    private val recordMutations: RecordMutationCoordinator = RecordMutationCoordinator(
        recordDao = recordDao,
        carePlanDao = carePlanDao,
        customItemDao = customItemDao,
        photoAttachmentReconciler = photoAttachmentReconciler,
        transactionRunner = transactionRunner,
        // Facade wires careplan projection so carelog stays free of careplan imports.
        projectOrScheduleCarePlanReminder = { plan, projectToSystemCalendar ->
            reminderProjection.projectOrScheduleCarePlanReminder(
                plan,
                projectToSystemCalendar = projectToSystemCalendar,
            )
        },
        cancelCarePlanReminderAndProjection = { carePlanId ->
            reminderProjection.cancelCarePlanReminderBestEffort(carePlanId)
            reminderProjection.removeSystemCalendarProjection(carePlanId)
        },
        syncPort = syncPort,
        clock = clock,
        sleepMutationMutex = sleepMutationMutex,
        requireActiveBaby = { babyId -> babyProfiles.requireActiveBaby(babyId) },
        currentMembershipActorId = { carePlans.currentMembershipActorId() },
        completeOpenCarePlanWithRecord = {
            carePlanId,
            babyId,
            expectedType,
            recordClientUuid,
            now,
            actualTimestamp,
            ->
            carePlans.completeOpenCarePlanWithRecord(
                carePlanId = carePlanId,
                babyId = babyId,
                expectedType = expectedType,
                recordClientUuid = recordClientUuid,
                now = now,
                actualTimestamp = actualTimestamp,
            )
        },
        // Deferred like completeOpenCarePlanWithRecord: CarePlanCoordinator owns
        // active plan-photo path policy (ordering/trim); do not re-read via DAO here.
        listCarePlanPhotoPaths = { carePlans.listCarePlanPhotoPaths(it) },
        requestLocalSync = ::requestLocalSync,
        wakeObservations = this.wakeObservationCoordinator,
    )
    private val babyProfiles: BabyFamilyProfileCoordinator = BabyFamilyProfileCoordinator(
        babyDao = babyDao,
        recordDao = recordDao,
        carePlanDao = carePlanDao,
        localUserDao = localUserDao,
        familyDao = familyDao,
        membershipDao = membershipDao,
        mediaAssetDao = mediaAssetDao,
        settings = settings,
        syncPort = syncPort,
        transactionRunner = transactionRunner,
        reminderProjection = reminderProjection,
        sleepMutationMutex = sleepMutationMutex,
        mediaPathGate = mediaPathGate,
        healDuplicateOpenSleeps = recordMutations::healDuplicateOpenSleeps,
        cleanupCommittedPhotoTombstones = recordMutations::cleanupCommittedPhotoTombstones,
        requestLocalSync = ::requestLocalSync,
    )

    init {
        carePlans = CarePlanCoordinator(
            recordDao = recordDao,
            carePlanDao = carePlanDao,
            customItemDao = customItemDao,
            mediaAssetDao = mediaAssetDao,
            fulfillmentCandidateDao = fulfillmentCandidateDao,
            transactionRunner = transactionRunner,
            photoAttachmentReconciler = photoAttachmentReconciler,
            reminderProjection = reminderProjection,
            conflictAuditQueries = conflictAuditQueries,
            calendarReminderMutationGuard = calendarReminderMutationGuard,
            syncPort = syncPort,
            recordMutations = recordMutations,
            nextFeedPlanMutationMutex = nextFeedPlanMutationMutex,
            sleepMutationMutex = sleepMutationMutex,
            requireActiveBaby = { babyId -> babyProfiles.requireActiveBaby(babyId) },
            listRecordPhotoPaths = ::listRecordPhotoPaths,
            requestLocalSync = ::requestLocalSync,
        )
    }

    fun observeHasBaby(): Flow<Boolean> = babyProfiles.observeHasBaby()

    fun observeBabies(): Flow<List<Baby>> = babyProfiles.observeBabies()

    fun observeMemberLocalBabyOrphans(): Flow<List<Baby>> =
        babyProfiles.observeMemberLocalBabyOrphans()

    fun observeCurrentBaby(): Flow<Baby?> = babyProfiles.observeCurrentBaby()

    suspend fun createBaby(input: CreateBabyInput): Long = localDataMutationEpoch.withMutation {
        babyProfiles.createBaby(input)
    }

    suspend fun ensureFamilyScaffold() = localDataMutationEpoch.withMutation {
        babyProfiles.ensureFamilyScaffold()
    }

    suspend fun addBaby(input: CreateBabyInput): Long = localDataMutationEpoch.withMutation {
        babyProfiles.addBaby(input)
    }

    suspend fun updateBabyProfile(babyId: Long, input: UpdateBabyInput) =
        localDataMutationEpoch.withMutation {
            babyProfiles.updateBabyProfile(babyId, input)
        }

    suspend fun deleteBaby(babyId: Long): Boolean = localDataMutationEpoch.withMutation {
        val deleted = babyProfiles.deleteBaby(babyId)
        if (deleted) {
            carePlans.onBabyDeleted(babyId)
        }
        deleted
    }

    suspend fun getCurrentBaby(): Baby? = babyProfiles.getCurrentBaby()

    suspend fun listBabies(): List<Baby> = babyProfiles.listBabies()

    suspend fun localFamilyIdentity(): LocalFamilyIdentity = babyProfiles.localFamilyIdentity()

    suspend fun updateLocalDisplayName(displayName: String?) =
        localDataMutationEpoch.withMutation {
            babyProfiles.updateLocalDisplayName(displayName)
        }

    suspend fun setCurrentBaby(babyId: Long) = localDataMutationEpoch.withMutation {
        babyProfiles.setCurrentBaby(babyId)
    }

    suspend fun updateBabyLocalPreferences(
        babyId: Long,
        themeColorArgb: Int? = null,
        sortOrder: Int? = null,
    ) = localDataMutationEpoch.withMutation {
        babyProfiles.updateBabyLocalPreferences(babyId, themeColorArgb, sortOrder)
    }

    suspend fun updateBabyLocalOrder(orderedBabyIds: List<Long>) =
        localDataMutationEpoch.withMutation {
            babyProfiles.updateBabyLocalOrder(orderedBabyIds)
        }

    /**
     * Observe records inside the half-open local-date range. Sleep records that
     * start earlier are also included when their interval overlaps the range.
     * Conflict-not-adopted fulfillment records are excluded from ordinary surfaces.
     */
    fun observeRecords(
        babyId: Long,
        startDayInclusive: LocalDate,
        endDayExclusive: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<Record>> = queries.observeRecords(babyId, startDayInclusive, endDayExclusive, zone)

    fun observeDayRecords(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<Record>> = queries.observeDayRecords(babyId, day, zone)

    /** Observe the baby's single active sleep independently of the viewed date. */
    fun observeOpenSleep(babyId: Long): Flow<Record?> =
        queries.observeOpenSleep(babyId)

    /**
     * All non-deleted care plans in an absolute window for the lezi calendar.
     * Independent of system-calendar projection flags.
     */
    fun observeCarePlansInRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<CarePlan>> = queries.observeCarePlansInRange(babyId, startInclusive, endExclusive)

    fun observeCustomItems(): Flow<List<CustomRecordItem>> =
        customItemCatalog.observeCustomItems()

    suspend fun addCustomItem(name: String, iconSlot: Int): Long =
        localDataMutationEpoch.withMutation {
            customItemCatalog.addCustomItem(name, iconSlot)
        }

    suspend fun updateCustomItem(item: CustomRecordItem) =
        localDataMutationEpoch.withMutation {
            customItemCatalog.updateCustomItem(item)
        }

    suspend fun moveCustomItem(id: Long, delta: Int) =
        localDataMutationEpoch.withMutation {
            customItemCatalog.moveCustomItem(id, delta)
        }

    suspend fun deleteCustomItem(id: Long) =
        localDataMutationEpoch.withMutation {
            customItemCatalog.deleteCustomItem(id)
        }

    fun canManageCustomItem(
        item: CustomRecordItem,
        actorMembershipId: String,
        actorIsAdmin: Boolean,
    ): Boolean = customItemCatalog.canManageCustomItem(item, actorMembershipId, actorIsAdmin)

    suspend fun canManageCustomItem(item: CustomRecordItem): Boolean =
        customItemCatalog.canManageCustomItem(item)

    suspend fun dayRecords(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Record> = queries.dayRecords(babyId, day, zone)

    suspend fun daySummary(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = System.currentTimeMillis(),
    ): DailySummary = queries.daySummary(babyId, day, zone, now)

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
    ): Long = localDataMutationEpoch.withMutation {
        recordMutations.addRecord(
            babyId,
            type,
            timestamp,
            endTimestamp,
            note,
            payloadJson,
            schemaVersion,
            photoLocalPaths,
            nowMillis,
            clientUuid,
        )
    }

    suspend fun updateRecord(
        id: Long,
        timestamp: Long,
        endTimestamp: Long?,
        note: String?,
        payloadJson: String,
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        photoLocalPaths: List<String>? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) = localDataMutationEpoch.withMutation {
        recordMutations.updateRecord(
            id,
            timestamp,
            endTimestamp,
            note,
            payloadJson,
            schemaVersion,
            photoLocalPaths,
            nowMillis,
        )
    }

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
    ): Long = localDataMutationEpoch.withMutation {
        recordMutations.convertRecordToCarePlan(
            recordId,
            scheduledAt,
            note,
            payloadJson,
            schemaVersion,
            photoLocalPaths,
            zone,
            nowMillis,
            projectToSystemCalendar,
            clientUuid,
        )
    }

    suspend fun deleteRecord(id: Long): Boolean = localDataMutationEpoch.withMutation {
        recordMutations.deleteRecord(id)
    }

    /**
     * Whether the actor may edit/delete/convert this nursing record.
     * Same membership rule as care plans and custom items: creator or family owner.
     */
    fun canManageRecord(
        record: Record,
        actorMembershipId: String,
        actorIsAdmin: Boolean,
    ): Boolean = recordMutations.canManageRecord(record, actorMembershipId, actorIsAdmin)

    suspend fun canManageRecord(record: Record): Boolean =
        recordMutations.canManageRecord(record)

    /** Full manage or device-local B1 restricted wake correction. */
    suspend fun canEditRecord(record: Record): Boolean =
        recordMutations.canEditRecord(record)

    /** Soft-delete: author or owner only; B1 never grants delete. */
    suspend fun canDeleteRecord(record: Record): Boolean =
        recordMutations.canDeleteRecord(record)

    /** Active B1 privilege for this record on the current device/session. */
    suspend fun hasActiveFamilyWakePrivilege(record: Record): Boolean =
        recordMutations.hasActiveFamilyWakePrivilege(record)


    /** Active record photo paths. MediaAsset is the sole current photo source. */
    suspend fun listRecordPhotoPaths(recordId: Long): List<String> {
        val active = mediaAssetDao.listActiveForRecord(recordId)
            .map(MediaAssetEntity::localUri)
            .filter { it.isNotBlank() }
        return active
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
        carePlanId: Long? = null,
        /**
         * Optional merged seed + live plan photo paths (Ticket 09). When null and
         * [carePlanId] is set, clones current plan media (Ticket 08).
         */
        photoLocalPaths: List<String>? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long = localDataMutationEpoch.withMutation {
        recordMutations.completeNursing(
            babyId,
            leftMin,
            rightMin,
            order,
            amountMl,
            note,
            startedAt,
            endedAt,
            recordMode,
            completionClientUuid,
            carePlanId,
            photoLocalPaths,
            nowMillis,
        )
    }

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
    ): Long = localDataMutationEpoch.withMutation {
        recordMutations.confirmSleep(
            babyId,
            expectedOpenSleepId,
            timestamp,
            endTimestamp,
            note,
            payloadJson,
            schemaVersion,
            photoLocalPaths,
            nowMillis,
            clientUuid,
        )
    }

    suspend fun sleepDown(
        babyId: Long,
        at: Long = System.currentTimeMillis(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Long = localDataMutationEpoch.withMutation {
        recordMutations.sleepDown(babyId, at, nowMillis)
    }

    suspend fun sleepUp(
        babyId: Long,
        at: Long = System.currentTimeMillis(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Long = localDataMutationEpoch.withMutation {
        recordMutations.sleepUp(babyId, at, nowMillis)
    }

    // --- WakeObservation (ticket 06 / ADR-0021) ---

    suspend fun listWakeObservations(sleepRecordClientUuid: String): List<WakeObservation> =
        wakeObservationCoordinator.listForSleep(sleepRecordClientUuid)

    suspend fun getWakeObservation(clientUuid: String): WakeObservation? =
        wakeObservationCoordinator.get(clientUuid)

    suspend fun projectSleepRecord(recordId: Long): SleepRecordProjection? {
        val entity = recordDao.get(recordId) ?: return null
        if (entity.type != RecordType.SLEEP.key) return null
        return wakeObservationCoordinator.projectSleep(entity)
    }

    suspend fun recordWakeObservation(
        babyId: Long,
        at: Long = System.currentTimeMillis(),
        note: String? = null,
        photoLocalPaths: List<String> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
        sleepRecordId: Long? = null,
        clientUuid: String = newClientUuid(),
    ): Long = localDataMutationEpoch.withMutation {
        wakeObservationCoordinator.recordWake(
            babyId = babyId,
            at = at,
            note = note,
            photoLocalPaths = photoLocalPaths,
            nowMillis = nowMillis,
            sleepRecordId = sleepRecordId,
            clientUuid = clientUuid,
        )
    }

    suspend fun updateWakeObservation(
        clientUuid: String,
        wakeTimestamp: Long,
        note: String?,
        photoLocalPaths: List<String>? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) = localDataMutationEpoch.withMutation {
        wakeObservationCoordinator.updateWake(
            clientUuid,
            wakeTimestamp,
            note,
            photoLocalPaths,
            nowMillis,
        )
    }

    suspend fun withdrawWakeObservation(clientUuid: String) = localDataMutationEpoch.withMutation {
        wakeObservationCoordinator.withdrawWake(clientUuid)
    }

    suspend fun selectEffectiveWakeObservation(
        sleepRecordClientUuid: String,
        wakeObservationClientUuid: String?,
    ) = localDataMutationEpoch.withMutation {
        wakeObservationCoordinator.selectEffectiveWake(
            sleepRecordClientUuid,
            wakeObservationClientUuid,
        )
    }

    suspend fun canEditWakeObservation(clientUuid: String): Boolean =
        wakeObservationCoordinator.canEditWake(clientUuid)

    suspend fun canSelectEffectiveWakeObservation(sleepRecordClientUuid: String): Boolean =
        wakeObservationCoordinator.canSelectEffectiveWake(sleepRecordClientUuid)

    // --- Causal conflict badge / resolver (ticket 06) ---

    fun observeOpenConflictSummaries(): Flow<List<com.lezi.babylog.domain.carelog.OpenConflictSummary>> =
        conflictResolutionCoordinator.observeOpenSummaries()

    suspend fun listOpenConflictSummaries(): List<com.lezi.babylog.domain.carelog.OpenConflictSummary> =
        conflictResolutionCoordinator.listOpenSummaries()

    suspend fun conflictSummaryForRecord(clientUuid: String): com.lezi.babylog.domain.carelog.OpenConflictSummary? =
        conflictResolutionCoordinator.summaryForRoot("record", clientUuid)

    suspend fun loadConflictDetail(
        conflictId: String,
        forceRefresh: Boolean = true,
    ): com.lezi.babylog.domain.carelog.ConflictResolverDetail? =
        conflictResolutionCoordinator.loadDetail(conflictId, forceRefresh)

    suspend fun resolveConflict(
        conflictId: String,
        expectedStableVersion: String,
        expectedBranchVersions: List<String>,
        resolvedRootJson: String,
        resolvedMedia: List<com.lezi.babylog.sync.backend.CausalMediaItem> = emptyList(),
        conflictChoices: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
        resolutionMutationId: String = newClientUuid(),
    ): com.lezi.babylog.domain.carelog.ConflictResolveOutcome =
        conflictResolutionCoordinator.resolve(
            conflictId = conflictId,
            expectedStableVersion = expectedStableVersion,
            expectedBranchVersions = expectedBranchVersions,
            resolvedRootJson = resolvedRootJson,
            resolvedMedia = resolvedMedia,
            conflictChoices = conflictChoices,
            resolutionMutationId = resolutionMutationId,
        )

    // --- Suspected duplicates + source relations (ticket 07) ---

    suspend fun listOpenSuspectedDuplicateGroups(records: List<Record>): List<SuspectedDuplicateGroup> =
        sourceRelationCoordinator.openSuspectedGroups(records)

    suspend fun sourceRoleClientUuids(): Set<String> =
        sourceRelationCoordinator.sourceRoleClientUuids()

    /**
     * Ordinary timeline/stats projection: drop source-role UUIDs, keep display + independents.
     */
    suspend fun projectOrdinaryRecords(records: List<Record>): List<Record> =
        SuspectedDuplicateBounds.filterDisplayProjection(
            records,
            sourceRelationCoordinator.sourceRoleClientUuids(),
        )

    suspend fun daySummaryBounds(
        records: List<Record>,
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = clock.nowMillis(),
    ): CareDayBounds {
        val projected = projectOrdinaryRecords(records)
        val openGroups = sourceRelationCoordinator.openSuspectedGroups(projected)
        return SuspectedDuplicateBounds.day(projected, openGroups, date, zone, now)
    }

    suspend fun rangeSummaryBounds(
        records: List<Record>,
        startDate: LocalDate,
        dayCount: Int,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = clock.nowMillis(),
    ): com.lezi.babylog.domain.carelog.CareRangeBounds {
        val projected = projectOrdinaryRecords(records)
        val openGroups = sourceRelationCoordinator.openSuspectedGroups(projected)
        return SuspectedDuplicateBounds.range(
            records = projected,
            openGroups = openGroups,
            startDate = startDate,
            dayCount = dayCount,
            zone = zone,
            now = now,
        )
    }

    suspend fun timelineDuplicateRows(
        records: List<Record>,
        expandedGroupIds: Set<String>? = null,
    ): List<com.lezi.babylog.domain.carelog.TimelineDuplicateRow> {
        val sourceRoles = sourceRelationCoordinator.sourceRoleClientUuids()
        val openGroups = sourceRelationCoordinator.openSuspectedGroups(records)
        return com.lezi.babylog.domain.carelog.SuspectedDuplicatePresentation.timelineRows(
            records = records,
            openGroups = openGroups,
            sourceRoleClientUuids = sourceRoles,
            expandedGroupIds = expandedGroupIds,
        )
    }

    suspend fun declareRecordEquivalent(
        recordClientUuid: String,
        equivalentToClientUuid: String,
    ): SourceRelationOutcome = sourceRelationCoordinator.declareEquivalent(
        recordClientUuid = recordClientUuid,
        equivalentToClientUuid = equivalentToClientUuid,
    )

    suspend fun resolveSuspectedDuplicateGroupAsOwner(
        memberClientUuids: List<String>,
        displayClientUuid: String,
    ): SourceRelationOutcome = sourceRelationCoordinator.resolveGroupAsOwner(
        memberClientUuids = memberClientUuids,
        displayClientUuid = displayClientUuid,
    )

    suspend fun getRecord(id: Long): Record? = queries.getRecord(id)

    suspend fun getCarePlan(id: Long): CarePlan? = queries.getCarePlan(id)

    /**
     * Pending/missed plans for the selected local day (non-today views).
     * Never mixed into record list/summary/search/export.
     */
    fun observeDayPendingPlans(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<CarePlan>> = queries.observeDayPendingPlans(babyId, day, zone)

    /**
     * Today: all overdue open plans plus today's not-yet-due plans, by scheduledAt ASC.
     */
    fun observeTodayPendingPlans(
        babyId: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Flow<List<CarePlan>> = queries.observeTodayPendingPlans(babyId, zone, nowMillis)

    /**
     * Create a local care plan for a concrete built-in (including intent-only
     * nursing/sleep) or custom item. Future scheduled time only — past/current
     * must use [addRecord]. Schedule never starts timers or open sleep intervals.
     */
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
        projectToSystemCalendar: Boolean = true,
        clientUuid: String = newClientUuid(),
    ): Long = localDataMutationEpoch.withMutation {
        carePlans.createCarePlan(
            babyId,
            type,
            scheduledAt,
            note,
            payloadJson,
            schemaVersion,
            customItemId,
            photoLocalPaths,
            zone,
            nowMillis,
            projectToSystemCalendar,
            clientUuid,
        )
    }

    suspend fun reconcileNextFeedPlan(babyId: Long): NextFeedPlanReconciliation =
        localDataMutationEpoch.withMutation {
            carePlans.reconcileNextFeedPlan(babyId)
        }

    suspend fun scheduleNextFeedCarePlan(
        babyId: Long,
        feedType: RecordType,
        scheduledAt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Long = localDataMutationEpoch.withMutation {
        carePlans.scheduleNextFeedCarePlan(babyId, feedType, scheduledAt, zone, nowMillis)
    }

    suspend fun fulfillCarePlan(
        carePlanId: Long,
        actualTimestamp: Long,
        endTimestamp: Long? = null,
        note: String? = null,
        payloadJson: String? = null,
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        photoLocalPaths: List<String> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
        clientUuid: String = newClientUuid(),
    ): Long = localDataMutationEpoch.withMutation {
        carePlans.fulfillCarePlan(
            carePlanId,
            actualTimestamp,
            endTimestamp,
            note,
            payloadJson,
            schemaVersion,
            photoLocalPaths,
            nowMillis,
            clientUuid,
        )
    }

    suspend fun resolveFulfillmentAuthorityForPlan(carePlanClientUuid: String) =
        localDataMutationEpoch.withMutation {
            carePlans.resolveFulfillmentAuthorityForPlan(carePlanClientUuid)
        }

    suspend fun listFulfillmentCandidatesForPlan(
        carePlanClientUuid: String,
    ): List<FulfillmentCandidate> =
        carePlans.listFulfillmentCandidatesForPlan(carePlanClientUuid)

    suspend fun filterSurfaceRecords(records: List<Record>): List<Record> =
        queries.filterSurfaceRecords(records)

    suspend fun isSurfaceRecord(clientUuid: String): Boolean =
        queries.isSurfaceRecord(clientUuid)

    suspend fun isFamilyAdmin(): Boolean = carePlans.isFamilyAdmin()

    suspend fun listConflictNotAdoptedAudits(
        carePlanClientUuid: String? = null,
        babyId: Long? = null,
    ): List<ConflictNotAdoptedAudit> =
        carePlans.listConflictNotAdoptedAudits(carePlanClientUuid, babyId)

    suspend fun getConflictNotAdoptedAudit(
        candidateClientUuid: String,
    ): ConflictNotAdoptedAudit? =
        carePlans.getConflictNotAdoptedAudit(candidateClientUuid)

    suspend fun convertConflictNotAdoptedToIndependentRecord(
        candidateClientUuid: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long = localDataMutationEpoch.withMutation {
        carePlans.convertConflictNotAdoptedToIndependentRecord(candidateClientUuid, nowMillis)
    }

    fun canManageCarePlan(
        plan: CarePlan,
        actorMembershipId: String,
        actorIsAdmin: Boolean,
    ): Boolean = carePlans.canManageCarePlan(plan, actorMembershipId, actorIsAdmin)

    suspend fun canManageCarePlan(plan: CarePlan): Boolean =
        carePlans.canManageCarePlan(plan)

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
    ) = localDataMutationEpoch.withMutation {
        carePlans.updateCarePlan(
            carePlanId,
            scheduledAt,
            note,
            payloadJson,
            schemaVersion,
            photoLocalPaths,
            zone,
            nowMillis,
            projectToSystemCalendar,
        )
    }

    suspend fun skipCarePlan(
        carePlanId: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ) = localDataMutationEpoch.withMutation {
        carePlans.skipCarePlan(carePlanId, nowMillis)
    }

    suspend fun deleteCarePlan(
        carePlanId: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean = localDataMutationEpoch.withMutation {
        carePlans.deleteCarePlan(carePlanId, nowMillis)
    }

    suspend fun onFamilyCarePlansApplied(
        planClientUuids: List<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ) = localDataMutationEpoch.withMutation {
        carePlans.onFamilyCarePlansApplied(planClientUuids, nowMillis)
    }

    suspend fun setCarePlanLocalRemindersEnabled(
        enabled: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ) = localDataMutationEpoch.withMutation {
        carePlans.setCarePlanLocalRemindersEnabled(enabled, nowMillis)
    }

    suspend fun projectOrScheduleCarePlanReminder(
        plan: CarePlan,
        projectToSystemCalendar: Boolean = plan.systemCalendarProjectionEnabled,
    ): Boolean = localDataMutationEpoch.withMutation {
        carePlans.projectOrScheduleCarePlanReminder(plan, projectToSystemCalendar)
    }

    suspend fun reprojectOpenFutureSystemCalendarCopies(
        nowMillis: Long = System.currentTimeMillis(),
    ) = localDataMutationEpoch.withMutation {
        carePlans.reprojectOpenFutureSystemCalendarCopies(nowMillis)
    }

    suspend fun isCarePlanSystemCalendarUnsynced(carePlanId: Long): Boolean =
        carePlans.isCarePlanSystemCalendarUnsynced(carePlanId)

    suspend fun disableSystemCalendarProjection() = localDataMutationEpoch.withMutation {
        carePlans.disableSystemCalendarProjection()
    }

    suspend fun rescheduleCarePlanReminders(
        nowMillis: Long = System.currentTimeMillis(),
    ) = localDataMutationEpoch.withMutation {
        carePlans.rescheduleCarePlanReminders(nowMillis)
    }

    suspend fun shouldDeliverCarePlanReminder(
        carePlanId: Long,
        clientUuid: String,
        expectedScheduledAt: Long,
    ): Boolean = carePlans.shouldDeliverCarePlanReminder(
        carePlanId,
        clientUuid,
        expectedScheduledAt,
    )

    suspend fun getCarePlanByClientUuid(clientUuid: String): CarePlan? =
        queries.getCarePlanByClientUuid(clientUuid)

    suspend fun listCarePlanPhotoPaths(carePlanId: Long): List<String> =
        carePlans.listCarePlanPhotoPaths(carePlanId)

    suspend fun weekSummary(
        babyId: Long,
        weekStart: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = System.currentTimeMillis(),
    ): WeekSummary = queries.weekSummary(babyId, weekStart, zone, now)

    suspend fun search(babyId: Long, query: String): List<Record> =
        queries.search(babyId, query)

    suspend fun recentCareSummary(
        babyId: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): WidgetSummaryDto = queries.recentCareSummary(babyId, zone)

    fun observeMeasurements(babyId: Long, type: RecordType): Flow<List<Record>> =
        queries.observeMeasurements(babyId, type)

    suspend fun recentMilkAmounts(
        babyId: Long,
        type: RecordType,
        limit: Int = 3,
    ): List<Int> = queries.recentMilkAmounts(babyId, type, limit)

    suspend fun recentNotes(
        babyId: Long,
        type: RecordType,
        limit: Int = 5,
    ): List<String> = queries.recentNotes(babyId, type, limit)

    suspend fun renameBaby(babyId: Long, nickname: String) =
        localDataMutationEpoch.withMutation {
            babyProfiles.renameBaby(babyId, nickname)
        }

    suspend fun previewBabyMerge(sourceBabyId: Long, targetBabyId: Long): BabyMergePreview? =
        babyProfiles.previewBabyMerge(sourceBabyId, targetBabyId)

    suspend fun mergeBabyProfiles(sourceBabyId: Long, targetBabyId: Long): Boolean =
        localDataMutationEpoch.withMutation {
            babyProfiles.mergeBabyProfiles(sourceBabyId, targetBabyId)
        }

    suspend fun reconcileMemberLocalBabies(): Int =
        localDataMutationEpoch.withMutation {
            babyProfiles.reconcileMemberLocalBabies()
        }

    internal suspend fun reconcileMemberLocalBabiesAfterFamilyApply(): Int =
        localDataMutationEpoch.withMutation {
            babyProfiles.reconcileMemberLocalBabiesAfterFamilyApply()
        }


    private fun requestLocalSync() {
        syncPort.notifyLocalChanges()
    }


}

internal fun nextSyncUpdatedAt(previous: Long, candidate: Long): Long =
    if (previous == Long.MAX_VALUE) {
        Long.MAX_VALUE
    } else {
        maxOf(candidate, previous + 1)
    }

internal fun validateSleepInterval(
    type: RecordType?,
    timestamp: Long,
    endTimestamp: Long?,
) {
    if (type == RecordType.SLEEP && endTimestamp != null) {
        require(endTimestamp > timestamp) {
            "睡眠结束时间必须晚于开始时间"
        }
    }
}

internal fun BabyEntity.toModel(): Baby =
    Baby(
        id = id,
        familyId = familyId,
        nickname = nickname,
        sex = sex?.let { parseSex(it) },
        birthdayEpochDay = birthdayEpochDay,
        birthWeightGrams = birthWeightGrams,
        avatarPath = avatarPath,
        themeColorArgb = themeColorArgb,
        sortOrder = sortOrder,
        clientUuid = clientUuid,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

internal fun RecordEntity.toModel(): Record =
    Record(
        id = id,
        clientUuid = clientUuid,
        babyId = babyId,
        type = RecordType.fromKey(type) ?: error("Unknown record type: $type"),
        timestamp = timestamp,
        endTimestamp = endTimestamp,
        note = note,
        createdByMembershipId = createdByMembershipId,
        payloadJson = payloadJson,
        schemaVersion = schemaVersion,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        syncDirty = syncDirty,
        familyPublishedUpdatedAt = familyPublishedUpdatedAt,
        openConflictId = openConflictId,
        effectiveWakeObservationClientUuid = effectiveWakeObservationClientUuid,
    )

internal fun CustomItemEntity.toModel(): CustomRecordItem =
    CustomRecordItem(
        id = id,
        name = name,
        iconSlot = iconSlot,
        sortOrder = sortOrder,
        clientUuid = clientUuid,
        createdByMembershipId = createdByMembershipId,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

internal fun CarePlanEntity.toModel(): CarePlan =
    CarePlan(
        id = id,
        clientUuid = clientUuid,
        babyId = babyId,
        type = RecordType.fromKey(type) ?: error("Unknown care plan type: $type"),
        customItemId = customItemId,
        scheduledAt = scheduledAt,
        scheduledZoneId = scheduledZoneId,
        note = visibleNextFeedPlanNote(note),
        payloadJson = payloadJson,
        schemaVersion = schemaVersion,
        status = CarePlanStatus.fromStorage(status),
        createdByMembershipId = createdByMembershipId,
        fulfilledRecordClientUuid = fulfilledRecordClientUuid,
        fulfilledAt = fulfilledAt,
        sourceRecordClientUuid = sourceRecordClientUuid,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        syncDirty = syncDirty,
        systemCalendarProjectionEnabled = systemCalendarProjectionEnabled,
        familyPublishedUpdatedAt = familyPublishedUpdatedAt,
    )

/**
 * Pure ownership rule for creator-owned family entities (nursing records, custom
 * item definitions, and care plans share the same membership ACL — ADR 0001 / 0006).
 *
 * Empty creator + empty actor → offline single-device local owner.
 * Empty creator + exact local pending acknowledgement → temporarily allow.
 * Any other empty creator + joined non-admin → deny (await authoritative data).
 * Non-empty creator match → member may manage own entity.
 * Admin always may manage (including after creator leave).
 */
fun canManageCreatorOwnedFamilyEntity(
    creatorMembershipId: String,
    actorMembershipId: String,
    actorIsAdmin: Boolean,
    creatorAcknowledgementPending: Boolean = false,
): Boolean {
    if (actorIsAdmin) return true
    val creator = creatorMembershipId.trim()
    val actor = actorMembershipId.trim()
    if (creator.isEmpty() && actor.isEmpty()) return true
    if (creator.isEmpty() && creatorAcknowledgementPending) return true
    if (creator.isEmpty()) return false
    return creator == actor
}

internal fun requireCurrentPayloadDocument(
    type: RecordType,
    payloadJson: String,
    schemaVersion: Int,
): RecordPayloadDocument {
    require(schemaVersion == CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION) {
        "仅支持当前 payload schema"
    }
    val document = RecordPayloadCodec.decode(type, payloadJson, schemaVersion)
    require(!document.isUnknown) { "payload 与当前 $type 类型不匹配或格式损坏" }
    require(document.type == type && document.payload.type == type) {
        "payload 类型与记录类型不匹配"
    }
    if (type == RecordType.CUSTOM) {
        require(document.payload is CustomPayload) { "CUSTOM 必须携带具体项目身份" }
    }
    return document
}

internal fun requireCurrentPayloadJson(
    type: RecordType,
    payloadJson: String,
    schemaVersion: Int,
): String {
    requireCurrentPayloadDocument(type, payloadJson, schemaVersion)
    return payloadJson
}

/**
 * Merge durable custom definition snapshot fields into a plan/record payload.
 * Prefer live definition values so renames after draft open still stamp correctly.
 */
internal fun stampCustomItemSnapshotIntoPayload(
    payloadJson: String,
    customItemId: Long,
    titleSnapshot: String,
    iconSlot: Int,
): String {
    val existing = if (payloadJson.trim() == "{}") {
        null
    } else {
        val document = requireCurrentPayloadDocument(
            RecordType.CUSTOM,
            payloadJson,
            CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )
        document.payload as CustomPayload
    }
    val stamped = CustomPayload(
        titleSnapshot = titleSnapshot.trim().ifBlank {
            existing?.titleSnapshot.orEmpty()
        },
        detail = existing?.detail,
        customItemId = customItemId,
        iconSlot = iconSlot,
    )
    return RecordPayloadCodec.encode(
        RecordPayloadDocument(
            type = RecordType.CUSTOM,
            payload = stamped,
            schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        ),
    )
}

/**
 * Write-time name/icon snapshot for a custom definition used by Record / CarePlan payloads.
 * Callers must persist these fields; later renames must not rewrite historical rows.
 */
data class CustomItemFieldSnapshot(
    val titleSnapshot: String,
    val iconSlot: Int?,
    val customItemId: Long?,
)

fun CustomRecordItem.toFieldSnapshot(): CustomItemFieldSnapshot =
    CustomItemFieldSnapshot(
        titleSnapshot = name,
        iconSlot = iconSlot,
        customItemId = id.takeIf { it > 0L },
    )

private fun parseSex(raw: String): Sex = com.lezi.babylog.core.model.parseBabySex(raw)

/**
 * Canonical Room storage for baby sex: Home-LAN wire values only
 * (`female` / `male` / null). Delegates to [com.lezi.babylog.core.model.normalizeBabySex].
 */
internal fun normalizeBabySexForStorage(raw: String?): String? =
    com.lezi.babylog.core.model.normalizeBabySex(raw)
