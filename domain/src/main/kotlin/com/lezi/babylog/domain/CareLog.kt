package com.lezi.babylog.domain

import com.lezi.babylog.domain.carelog.*
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
import com.lezi.babylog.core.database.fulfillment.FulfillmentAuthoritySettlement
import com.lezi.babylog.core.database.LocalUserDao
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.MembershipDao
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.RecordWakeProjectionDao
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
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
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.CustomPayload
import com.lezi.babylog.core.model.parseBabySex
import com.lezi.babylog.core.model.visibleNextFeedPlanNote
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import com.lezi.babylog.domain.calendar.NoOpSystemCalendarPort
import com.lezi.babylog.domain.calendar.SYSTEM_CALENDAR_WRITE_MAX_ELAPSED_MILLIS
import com.lezi.babylog.domain.calendar.SystemCalendarPort
import com.lezi.babylog.domain.carelog.CareLogQueries
import com.lezi.babylog.domain.carelog.ConflictAuditQueries
import com.lezi.babylog.domain.carelog.CareDayBounds
import com.lezi.babylog.domain.carelog.ConflictResolutionCoordinator
import com.lezi.babylog.domain.carelog.DailySummary
import com.lezi.babylog.domain.carelog.PhotoAttachmentReconciler
import com.lezi.babylog.domain.carelog.RecordMutationCoordinator
import com.lezi.babylog.domain.carelog.SleepRecordProjection
import com.lezi.babylog.domain.carelog.toSleepRecordProjection
import com.lezi.babylog.domain.carelog.SourceRelationCoordinator
import com.lezi.babylog.domain.carelog.SourceRelationOutcome
import com.lezi.babylog.domain.carelog.SuspectedDuplicateBounds
import com.lezi.babylog.domain.carelog.SuspectedDuplicateGroup
import com.lezi.babylog.domain.carelog.SuspectedDuplicateProjection
import com.lezi.babylog.domain.carelog.SuspectedDuplicateProjectionResult
import com.lezi.babylog.domain.carelog.DuplicateTimelineIndex
import com.lezi.babylog.domain.carelog.WakeObservation
import com.lezi.babylog.domain.carelog.WakeObservationCoordinator
import com.lezi.babylog.domain.carelog.WeekSummary
import com.lezi.babylog.domain.carelog.WidgetSummaryDto
import com.lezi.babylog.domain.carelog.toProjectedSleepRecord
import com.lezi.babylog.domain.careplan.CarePlanCoordinator
import com.lezi.babylog.domain.careplan.CarePlanReminderProjection
import com.lezi.babylog.domain.careplan.ReminderCleanupPort
import com.lezi.babylog.domain.catalog.CustomItemCatalog
import com.lezi.babylog.domain.family.BabyFamilyProfileCoordinator
import com.lezi.babylog.domain.family.BabyLocalMoveResult
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

/** The baby exists; only device-local selection or the sync wake-up remains pending. */
class BabyCreationCommittedException(
    val babyId: Long,
    cause: Throwable,
) : IllegalStateException("宝宝已创建，切换或同步提示暂未完成", cause)

/** Preserve cancellation semantics without hiding an already committed profile. */
class BabyCreationCommittedCancellationException(
    val babyId: Long,
    cause: kotlinx.coroutines.CancellationException,
) : kotlinx.coroutines.CancellationException("宝宝已创建，后续操作已取消") {
    init { initCause(cause) }
}

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
class CareLog internal constructor(
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
    private val fulfillmentAuthoritySettlement: FulfillmentAuthoritySettlement,
    private val calendarReminderMutationGuard: CalendarReminderMutationGuard,
    private val clock: PolicyClock,
    /** Process-wide path gate shared with reference-aware media reclaim (Hilt singleton). */
    private val mediaPathGate: MediaLocalPathGate,
    private val localDataMutationEpoch: LocalDataMutationEpoch,
    private val wakeObservationDao: WakeObservationDao,
    private val conflictSummaryDao: ConflictSummaryDao,
    private val conflictSnapshotCacheDao: ConflictSnapshotCacheDao,
    private val sourceRelationDao: SourceRelationDao,
    private val recordWakeProjectionDao: RecordWakeProjectionDao,
    private val systemCalendarWriteMaxElapsedMillis: Long,
    /** Filesystem boundary; production keeps the shared SHA-256 implementation. */
    private val digestPhotoFile: (String) -> String? = {
        com.lezi.babylog.core.common.MediaContentDigest.ofReadableFile(it)
    },
) {
    @Inject
    constructor(
        babyDao: BabyDao,
        recordDao: RecordDao,
        carePlanDao: CarePlanDao,
        customItemDao: CustomItemDao,
        localUserDao: LocalUserDao,
        familyDao: FamilyDao,
        membershipDao: MembershipDao,
        mediaAssetDao: MediaAssetDao,
        settings: SettingsStore,
        syncPort: SyncPort,
        reminderCleanup: ReminderCleanupPort,
        transactionRunner: DatabaseTransactionRunner,
        systemCalendar: SystemCalendarPort = NoOpSystemCalendarPort(),
        fulfillmentCandidateDao: FulfillmentCandidateDao,
        fulfillmentAuthoritySettlement: FulfillmentAuthoritySettlement,
        calendarReminderMutationGuard: CalendarReminderMutationGuard,
        clock: PolicyClock,
        mediaPathGate: MediaLocalPathGate,
        localDataMutationEpoch: LocalDataMutationEpoch,
        wakeObservationDao: WakeObservationDao,
        conflictSummaryDao: ConflictSummaryDao,
        conflictSnapshotCacheDao: ConflictSnapshotCacheDao,
        sourceRelationDao: SourceRelationDao,
        recordWakeProjectionDao: RecordWakeProjectionDao,
    ) : this(
        babyDao,
        recordDao,
        carePlanDao,
        customItemDao,
        localUserDao,
        familyDao,
        membershipDao,
        mediaAssetDao,
        settings,
        syncPort,
        reminderCleanup,
        transactionRunner,
        systemCalendar,
        fulfillmentCandidateDao,
        fulfillmentAuthoritySettlement,
        calendarReminderMutationGuard,
        clock,
        mediaPathGate,
        localDataMutationEpoch,
        wakeObservationDao,
        conflictSummaryDao,
        conflictSnapshotCacheDao,
        sourceRelationDao,
        recordWakeProjectionDao,
        SYSTEM_CALENDAR_WRITE_MAX_ELAPSED_MILLIS,
    )
    private val photoAttachmentReconciler = PhotoAttachmentReconciler.withDigest(
        mediaAssetDao = mediaAssetDao,
        pathGate = mediaPathGate,
        digestFile = digestPhotoFile,
    )
    /**
     * Serializes record create, update, delete, and confirm operations that may
     * change active sleep state. Room transactions provide atomic writes; this
     * lock makes read-check-write sequences deterministic inside this process.
     */
    private val sleepMutationMutex = Mutex()
    private val sourceRelationCoordinator = SourceRelationCoordinator(
        recordDao = recordDao,
        sourceRelationDao = sourceRelationDao,
        syncPort = syncPort,
        currentMembershipId = { carePlans.currentMembershipActorId() },
        isFamilyOwner = { carePlans.isFamilyAdmin() },
        nowMillis = { clock.nowMillis() },
    )
    private val wakeObservationCoordinator = WakeObservationCoordinator(
        recordDao = recordDao,
        wakeObservationDao = wakeObservationDao,
        mediaAssetDao = mediaAssetDao,
        transactionRunner = transactionRunner,
        syncPort = syncPort,
        sleepMutationMutex = sleepMutationMutex,
        recordWakeProjectionDao = recordWakeProjectionDao,
        currentMembershipActorId = { carePlans.currentMembershipActorId() },
        requestLocalSync = ::requestLocalSync,
        pathGate = mediaPathGate,
        sourceRoleClientUuids = { sourceRelationCoordinator.sourceRoleClientUuids() },
    )
    private val conflictResolutionCoordinator = ConflictResolutionCoordinator(
        conflictSummaryDao = conflictSummaryDao,
        conflictSnapshotCacheDao = conflictSnapshotCacheDao,
        syncPort = syncPort,
        transactionRunner = transactionRunner,
    )
    private val queries = CareLogQueries(
        babyDao = babyDao,
        recordDao = recordDao,
        carePlanDao = carePlanDao,
        fulfillmentCandidateDao = fulfillmentCandidateDao,
        recordWakeProjectionDao = recordWakeProjectionDao,
        sourceRoleClientUuids = { sourceRelationCoordinator.sourceRoleClientUuids() },
    )
    private val reminderProjection = CarePlanReminderProjection(
        carePlanDao = carePlanDao,
        babyDao = babyDao,
        mediaAssetDao = mediaAssetDao,
        settings = settings,
        reminderCleanup = reminderCleanup,
        systemCalendar = systemCalendar,
        calendarReminderMutationGuard = calendarReminderMutationGuard,
        writeMaxElapsedMillis = systemCalendarWriteMaxElapsedMillis,
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
        hasOpenSleep = ::hasVisibleOpenSleep,
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
            fulfillmentAuthoritySettlement = fulfillmentAuthoritySettlement,
            transactionRunner = transactionRunner,
            photoAttachmentReconciler = photoAttachmentReconciler,
            reminderProjection = reminderProjection,
            conflictAuditQueries = conflictAuditQueries,
            calendarReminderMutationGuard = calendarReminderMutationGuard,
            syncPort = syncPort,
            recordMutations = recordMutations,
            wakeObservations = wakeObservationCoordinator,
            nextFeedPlanMutationMutex = nextFeedPlanMutationMutex,
            sleepMutationMutex = sleepMutationMutex,
            hasOpenSleep = ::hasVisibleOpenSleep,
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

    suspend fun addBaby(
        input: CreateBabyInput,
        clientUuid: String = newClientUuid(),
    ): Long = localDataMutationEpoch.withMutation {
        babyProfiles.addBaby(input, clientUuid)
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

    suspend fun updateBabyLocalTheme(babyId: Long, themeColorArgb: Int) =
        localDataMutationEpoch.withMutation {
            babyProfiles.updateBabyLocalTheme(babyId, themeColorArgb)
        }

    suspend fun moveBabyLocal(babyId: Long, delta: Int): BabyLocalMoveResult =
        localDataMutationEpoch.withMutation {
            babyProfiles.moveBabyLocal(babyId, delta)
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
        recordWakeProjectionDao.observeRecordWakeInvalidations().combine(
            sourceRelationCoordinator.observeSourceRoleClientUuids(),
        ) { _, _ ->
            // The projected entity already carries the canonical sleep interval;
            // mapping it directly avoids a root re-read + second projection pass.
            wakeObservationCoordinator.findProjectedWakeShortcutTarget(babyId)?.let { projected ->
                projected.root.toProjectedSleepRecord(checkNotNull(projected.sleepInterval))
            }
        }.distinctUntilChanged()

    private suspend fun hasVisibleOpenSleep(babyId: Long): Boolean =
        wakeObservationCoordinator.listTrulyOpenSleeps(babyId).isNotEmpty()

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

    // Typed feature-facing commands. Legacy overloads below remain storage/import adapters;
    // both paths share the same transactional coordinators and perform no additional reads.
    suspend fun createRecord(intent: CreateCareRecord, nowMillis: Long = System.currentTimeMillis()): Long {
        require(intent.content.payload.type != RecordType.SLEEP) {
            "睡眠请使用睡下或闭合补记命令"
        }
        return addRecord(
            babyId = intent.baby.value, type = intent.content.payload.type,
            timestamp = intent.content.timestamp, endTimestamp = intent.content.endTimestamp,
            note = intent.content.note, payloadJson = intent.content.payload.encodeCarePayload(),
            photoLocalPaths = intent.attachments.forNew(), nowMillis = nowMillis,
            clientUuid = intent.writeId.value,
        )
    }

    suspend fun editRecord(intent: EditCareRecord, nowMillis: Long = System.currentTimeMillis()) = localDataMutationEpoch.withMutation {
        recordMutations.updateRecord(
            id = intent.target.value, timestamp = intent.content.timestamp,
            endTimestamp = intent.content.endTimestamp, note = intent.content.note,
            payloadJson = intent.content.payload.encodeCarePayload(),
            photoLocalPaths = intent.attachments.forEdit(), nowMillis = nowMillis,
            expectedPayloadType = intent.content.payload.type,
        )
    }

    suspend fun startSleep(intent: StartCareSleep, nowMillis: Long = System.currentTimeMillis()): Long =
        confirmSleep(
            babyId = intent.baby.value, expectedOpenSleepId = null, timestamp = intent.at,
            endTimestamp = null, note = intent.note, payloadJson = intent.payload.encodeCarePayload(),
            photoLocalPaths = intent.attachments.forNew(), nowMillis = nowMillis,
            clientUuid = intent.writeId.value,
        )

    suspend fun backfillSleep(intent: BackfillCareSleep, nowMillis: Long = System.currentTimeMillis()): Long =
        confirmSleep(
            babyId = intent.baby.value, expectedOpenSleepId = null, timestamp = intent.start,
            endTimestamp = intent.end, note = intent.note, payloadJson = intent.payload.encodeCarePayload(),
            photoLocalPaths = intent.attachments.forNew(), nowMillis = nowMillis,
            clientUuid = intent.writeId.value,
        )

    suspend fun editOpenSleep(
        intent: EditOpenCareSleep,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long = localDataMutationEpoch.withMutation {
        recordMutations.confirmSleep(
            babyId = intent.baby.value, expectedOpenSleepId = intent.target.value,
            timestamp = intent.start, endTimestamp = null,
            note = intent.note, payloadJson = intent.payload.encodeCarePayload(),
            photoLocalPaths = intent.attachments.forEdit(), nowMillis = nowMillis,
        )
    }

    suspend fun closeSleep(intent: CloseCareSleep, nowMillis: Long = System.currentTimeMillis()): Long =
        confirmSleep(
            babyId = intent.baby.value, expectedOpenSleepId = intent.target.value,
            timestamp = intent.start, endTimestamp = intent.wakeAt, note = intent.wakeNote,
            payloadJson = com.lezi.babylog.core.model.SleepPayload().encodeCarePayload(),
            photoLocalPaths = intent.attachments.forNew(), nowMillis = nowMillis,
            clientUuid = intent.writeId.value,
        )

    suspend fun correctWake(intent: CorrectCareWake, nowMillis: Long = System.currentTimeMillis()) =
        updateWakeObservation(
            clientUuid = intent.target.value, wakeTimestamp = intent.at, note = intent.note,
            photoLocalPaths = intent.attachments.forEdit(), nowMillis = nowMillis,
        )

    suspend fun createPlan(intent: CreateCarePlan, nowMillis: Long = System.currentTimeMillis()): Long =
        createCarePlan(
            babyId = intent.baby.value, type = intent.content.payload.type,
            scheduledAt = intent.content.timestamp, note = intent.content.note,
            payloadJson = intent.content.payload.encodeCarePayload(), customItemId = intent.customItemId,
            photoLocalPaths = intent.attachments.forNew(), nowMillis = nowMillis,
            projectToSystemCalendar = intent.projectToSystemCalendar, clientUuid = intent.writeId.value,
        )

    suspend fun editPlan(intent: EditCarePlan, nowMillis: Long = System.currentTimeMillis()) = localDataMutationEpoch.withMutation {
        carePlans.updateCarePlan(
            carePlanId = intent.target.value, scheduledAt = intent.content.timestamp,
            note = intent.content.note, payloadJson = intent.content.payload.encodeCarePayload(),
            photoLocalPaths = intent.attachments.forEdit(), nowMillis = nowMillis,
            projectToSystemCalendar = intent.projectToSystemCalendar,
            expectedPayloadType = intent.content.payload.type,
        )
    }

    suspend fun fulfillPlan(intent: FulfillCarePlan, nowMillis: Long = System.currentTimeMillis()): Long = localDataMutationEpoch.withMutation {
        carePlans.fulfillCarePlan(
            carePlanId = intent.target.value, actualTimestamp = intent.content.timestamp,
            endTimestamp = intent.content.endTimestamp, note = intent.content.note,
            payloadJson = intent.content.payload.encodeCarePayload(),
            photoLocalPaths = intent.attachments.forNew(), nowMillis = nowMillis,
            clientUuid = intent.writeId.value,
            expectedPayloadType = intent.content.payload.type,
        )
    }

    suspend fun convertRecordToPlan(
        intent: ConvertCareRecordToPlan,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long = localDataMutationEpoch.withMutation {
        recordMutations.convertRecordToCarePlan(
            recordId = intent.target.value, scheduledAt = intent.content.timestamp,
            note = intent.content.note, payloadJson = intent.content.payload.encodeCarePayload(),
            photoLocalPaths = intent.attachments.forNew(), nowMillis = nowMillis,
            projectToSystemCalendar = intent.projectToSystemCalendar, clientUuid = intent.writeId.value,
            expectedPayloadType = intent.content.payload.type,
        )
    }

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
        creatorAcknowledgementPending: Boolean = false,
    ): Boolean = recordMutations.canManageRecord(
        record,
        actorMembershipId,
        actorIsAdmin,
        creatorAcknowledgementPending,
    )

    suspend fun canManageRecord(record: Record): Boolean =
        recordMutations.canManageRecord(record)

    /** Full edit permission: record author or Owner. */
    suspend fun canEditRecord(record: Record): Boolean =
        recordMutations.canEditRecord(record)

    /** Soft-delete: author or Owner only. */
    suspend fun canDeleteRecord(record: Record): Boolean =
        recordMutations.canDeleteRecord(record)


    /** One committed care graph for export; no raw-root range or follow-up wake reads. */
    internal suspend fun exportCareFacts(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<com.lezi.babylog.domain.export.ExportCareFact> = transactionRunner.run {
        val context = currentCoroutineContext()
        var visited = 0
        fun checkWork() {
            if ((visited++ and 127) == 0) context.ensureActive()
        }
        val rows = queries.projectedRecords(babyId, startInclusive, endExclusive)
        val photos = linkedMapOf<Long?, MutableList<MediaAssetEntity>>()
        var offset = 0
        while (offset < rows.size) {
            checkWork()
            val ids = rows.subList(offset, minOf(rows.size, offset + RECORD_PHOTO_QUERY_CHUNK)).map {
                checkWork()
                it.root.id
            }
            for (photo in mediaAssetDao.listActiveForRecordsIn(ids)) {
                checkWork()
                photos.getOrPut(photo.recordId) { mutableListOf() }.add(photo)
            }
            offset += ids.size
        }
        rows.map { projected ->
            checkWork()
            val interval = projected.sleepInterval
            val model = if (interval != null) {
                projected.root.toModel().copy(endTimestamp = interval.endTimestamp)
            } else {
                projected.root.toModel()
            }
            val visibleWakeUuids = interval?.visibleObservations.orEmpty().mapTo(hashSetOf()) {
                checkWork()
                it.clientUuid
            }
            val visibleWakes = projected.wakeObservations.filter {
                checkWork()
                it.clientUuid in visibleWakeUuids
            }
            val wakeIds = visibleWakes.mapTo(hashSetOf()) { checkWork(); it.id }
            val paths = linkedSetOf<String>()
            for (photo in photos[model.id].orEmpty()) {
                checkWork()
                if (photo.localUri.isNotBlank()) paths.add(photo.localUri)
            }
            for (photo in projected.wakeMedia) {
                checkWork()
                if (photo.wakeObservationId in wakeIds && photo.localUri.isNotBlank()) paths.add(photo.localUri)
            }
            com.lezi.babylog.domain.export.ExportCareFact(
                record = model,
                wakeNotes = visibleWakes.mapNotNull { checkWork(); it.note?.takeIf(String::isNotBlank) },
                photoPaths = paths.toList(),
            )
        }
    }

    /** Active record photo paths. MediaAsset is the sole current photo source. */
    suspend fun listRecordPhotoPaths(recordId: Long): List<String> {
        val active = mediaAssetDao.listActiveForRecord(recordId)
            .map(MediaAssetEntity::localUri)
            .filter { it.isNotBlank() }
        return active
    }

    /**
     * Active photo paths for [recordIds], in that id order, then each record's id order.
     * Does not distinct across records; callers that need a unique list distinct the result.
     * Ids are loaded in chunks of [RECORD_PHOTO_QUERY_CHUNK] so each SQL `IN` list stays
     * under SQLite's 999 bind-variable limit.
     */
    suspend fun listRecordPhotoPaths(recordIds: List<Long>): List<String> {
        if (recordIds.isEmpty()) return emptyList()
        val assets = mutableListOf<MediaAssetEntity>()
        // Distinct so an id repeated across chunks is not loaded twice into one record's paths.
        for (chunk in recordIds.distinct().chunked(RECORD_PHOTO_QUERY_CHUNK)) {
            assets.addAll(mediaAssetDao.listActiveForRecordsIn(chunk))
        }
        val byRecord = assets.groupBy { it.recordId }
        return recordIds.flatMap { id ->
            byRecord[id].orEmpty()
                .map(MediaAssetEntity::localUri)
                .filter { it.isNotBlank() }
        }
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
        return recordWakeProjectionDao.loadRecordProjectionForRoots(listOf(entity.clientUuid))
            .singleOrNull()
            ?.toSleepRecordProjection()
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

    // --- Causal conflict inbox / resolver ---

    fun observeOpenConflictInbox(): Flow<com.lezi.babylog.domain.carelog.ConflictInbox> =
        conflictResolutionCoordinator.observeInbox()

    suspend fun loadConflictDetail(
        conflictId: String,
        forceRefresh: Boolean = true,
    ): com.lezi.babylog.domain.carelog.ConflictResolverLoad? =
        conflictResolutionCoordinator.loadDetail(conflictId, forceRefresh)

    suspend fun resolveConflict(
        conflictId: String,
        request: com.lezi.babylog.sync.backend.ConflictResolveRequest,
    ): com.lezi.babylog.domain.carelog.ConflictResolveOutcome =
        conflictResolutionCoordinator.resolve(
            conflictId = conflictId,
            request = request,
        )

    suspend fun withdrawConflictBranches(
        conflictId: String,
        request: com.lezi.babylog.sync.backend.ConflictWithdrawRequest,
    ): com.lezi.babylog.domain.carelog.ConflictResolveOutcome =
        conflictResolutionCoordinator.withdraw(
            conflictId = conflictId,
            request = request,
        )
    suspend fun abandonRejectedMutation(entityType: String, clientUuid: String): Result<Unit> =
        syncPort.abandonRejectedMutation(entityType, clientUuid)

    suspend fun dismissUnresolvedLocally(inboxId: String): Result<Unit> {
        val ref = com.lezi.babylog.domain.carelog.UnresolvedInboxIds.parse(inboxId)
            ?: return Result.failure(IllegalArgumentException("不是未对齐项"))
        if (!ref.dismissible) {
            return Result.failure(IllegalArgumentException("宝宝和家庭不能从本机去掉"))
        }
        val kind = when (ref.kind) {
            com.lezi.babylog.domain.carelog.ConflictInboxKind.LocalExtra ->
                com.lezi.babylog.sync.UnresolvedLocalKind.LocalExtra
            com.lezi.babylog.domain.carelog.ConflictInboxKind.Rejected ->
                com.lezi.babylog.sync.UnresolvedLocalKind.Rejected
            com.lezi.babylog.domain.carelog.ConflictInboxKind.PullHole ->
                com.lezi.babylog.sync.UnresolvedLocalKind.PullHole
            com.lezi.babylog.domain.carelog.ConflictInboxKind.Branched ->
                return Result.failure(IllegalArgumentException("冲突请走家庭裁决"))
        }
        return syncPort.dismissUnresolvedLocally(ref.entityType, ref.clientUuid, kind)
    }


    suspend fun getRecordByClientUuid(clientUuid: String): Record? =
        recordDao.getByClientUuid(clientUuid)?.takeIf { it.deletedAt == null }?.toModel()

    suspend fun getBabyByClientUuid(clientUuid: String): Baby? =
        babyDao.getByClientUuid(clientUuid)?.takeIf { it.deletedAt == null }?.toModel()

    suspend fun getCustomItemByClientUuid(clientUuid: String): CustomRecordItem? =
        customItemDao.getByClientUuid(clientUuid)?.takeIf { it.deletedAt == null }?.toModel()

    fun observeConflictStillOpen(conflictId: String): Flow<Boolean> =
        observeOpenConflictInbox().map { inbox ->
            inbox.items.any { it.conflictId == conflictId }
        }

    // --- Suspected duplicates + source relations (ticket 07) ---

    suspend fun listOpenSuspectedDuplicateGroups(records: List<Record>): List<SuspectedDuplicateGroup> =
        sourceRelationCoordinator.openSuspectedGroups(records)

    suspend fun sourceRoleClientUuids(): Set<String> =
        sourceRelationCoordinator.sourceRoleClientUuids()

    fun observeSourceRoleClientUuids(): Flow<Set<String>> =
        sourceRelationCoordinator.observeSourceRoleClientUuids()

    suspend fun autoAlignedDisplayClientUuids(): Set<String> =
        sourceRelationCoordinator.autoAlignedDisplayClientUuids()

    fun observeAutoAlignedDisplayClientUuids(): Flow<Set<String>> =
        sourceRelationCoordinator.observeAutoAlignedDisplayClientUuids()

    suspend fun sourceRecordsByDisplay(records: List<Record>): Map<String, List<Record>> =
        sourceRelationCoordinator.sourceRecordsByDisplay(records)

    fun listOpenSuspectedDuplicateGroups(
        records: List<Record>,
        sourceRoleClientUuids: Set<String>,
    ): List<SuspectedDuplicateGroup> = sourceRelationCoordinator.openSuspectedGroups(
        records,
        sourceRoleClientUuids,
    )

    suspend fun suspectedDuplicateProjection(
        records: List<Record>,
        startDate: LocalDate,
        dayCount: Int,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = clock.nowMillis(),
        sourceRoleClientUuids: Set<String>? = null,
    ): SuspectedDuplicateProjectionResult = SuspectedDuplicateProjection.project(
        records = records,
        startDate = startDate,
        dayCount = dayCount,
        zone = zone,
        now = now,
        sourceRoleClientUuids = sourceRoleClientUuids
            ?: sourceRelationCoordinator.sourceRoleClientUuids(),
    )

    /**
     * Ordinary timeline/stats projection: drop source-role UUIDs, keep display + independents.
     */
    suspend fun projectOrdinaryRecords(records: List<Record>): List<Record> =
        SuspectedDuplicateBounds.filterDisplayProjection(
            records,
            sourceRelationCoordinator.sourceRoleClientUuids(),
        )

    fun projectOrdinaryRecords(
        records: List<Record>,
        sourceRoleClientUuids: Set<String>,
    ): List<Record> = SuspectedDuplicateBounds.filterDisplayProjection(
        records,
        sourceRoleClientUuids,
    )

    suspend fun daySummaryBounds(
        records: List<Record>,
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = clock.nowMillis(),
        sourceRoleClientUuids: Set<String>? = null,
    ): CareDayBounds {
        return suspectedDuplicateProjection(
            records = records,
            startDate = date,
            dayCount = 1,
            zone = zone,
            now = now,
            sourceRoleClientUuids = sourceRoleClientUuids,
        ).bounds.days.single()
    }

    suspend fun rangeSummaryBounds(
        records: List<Record>,
        startDate: LocalDate,
        dayCount: Int,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = clock.nowMillis(),
    ): com.lezi.babylog.domain.carelog.CareRangeBounds {
        return suspectedDuplicateProjection(
            records = records,
            startDate = startDate,
            dayCount = dayCount,
            zone = zone,
            now = now,
        ).bounds
    }

    suspend fun timelineDuplicateRows(
        records: List<Record>,
        expandedGroupIds: Set<String>? = null,
        sourceRoleClientUuids: Set<String>? = null,
        projection: SuspectedDuplicateProjectionResult? = null,
    ): List<com.lezi.babylog.domain.carelog.TimelineDuplicateRow> {
        val sourceRoles = sourceRoleClientUuids ?: sourceRelationCoordinator.sourceRoleClientUuids()
        val openGroups = projection?.openGroups
            ?: sourceRelationCoordinator.openSuspectedGroups(records, sourceRoles)
        return com.lezi.babylog.domain.carelog.SuspectedDuplicatePresentation.timelineRows(
            records = records,
            openGroups = openGroups,
            sourceRoleClientUuids = sourceRoles,
            expandedGroupIds = expandedGroupIds,
            timelineIndex = projection?.timelineIndex
                ?: DuplicateTimelineIndex.build(records, openGroups, sourceRoles),
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

    /**
     * Sleep still projectively open for this baby, including hidden source-role
     * rows. Matches wake mutation admission, not the dock shortcut.
     */
    suspend fun requireProjectedOpenSleep(babyId: Long, recordId: Long): Record {
        val open = wakeObservationCoordinator.listProjectedOpenSleeps(babyId)
            .firstOrNull { it.id == recordId }
            ?: throw SleepStateChangedException()
        return open.toModel()
    }

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
            fulfillmentAuthoritySettlement.settle(carePlanClientUuid)
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

    /** Room-only projection result from the write path. Does not query the system calendar. */
    suspend fun lastSystemCalendarProjectionMissed(carePlanId: Long): Boolean =
        carePlans.lastSystemCalendarProjectionMissed(carePlanId)

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

    /**
     * Record, wake, media, fulfillment, and conflict-summary revisions.
     * Search stays subscribed so the open result list is not a one-shot snapshot.
     */
    fun observeRecordProjectionInvalidations(): Flow<Unit> =
        recordWakeProjectionDao.observeInvalidations().map { }

    suspend fun search(babyId: Long, query: String): List<Record> =
        queries.search(
            babyId = babyId,
            query = query,
            hiddenSourceClientUuids = sourceRelationCoordinator.sourceRoleClientUuids(),
        )

    suspend fun recentCareSummary(
        babyId: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): WidgetSummaryDto = recentCareSummaryAt(babyId, zone, RecordTime.currentTimeMillis())

    internal suspend fun recentCareSummaryAt(
        babyId: Long,
        zone: ZoneId,
        now: Long,
    ): WidgetSummaryDto = queries.recentCareSummary(
        babyId = babyId,
        zone = zone,
        hiddenSourceClientUuids = sourceRelationCoordinator.sourceRoleClientUuids(),
        now = now,
    )

    fun observeMeasurements(babyId: Long, type: RecordType): Flow<List<Record>> =
        queries.observeMeasurements(babyId, type).combine(observeSourceRoleClientUuids()) { records, hidden ->
            records.filter { it.clientUuid !in hidden }
        }

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

/** Room `IN (:recordIds)` for active record photos. SQLite binds at most 999 variables. */
private const val RECORD_PHOTO_QUERY_CHUNK = 500

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
        sex = sex?.let { parseBabySex(it) },
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

/** Shared scalar rules run after plan target/type/custom stamping and before persistence. */
internal fun requireValidCarePlanPayload(
    type: RecordType,
    payloadJson: String,
    schemaVersion: Int,
    note: String?,
) {
    val payload = requireCurrentPayloadDocument(type, payloadJson, schemaVersion).payload
    val errors = RecordPayloadCodec.validate(
        payload,
        allowIntentOnlyFeed = com.lezi.babylog.core.model.carePlanAllowsIntentOnlyFeed(type, note),
    )
    require(errors.isEmpty()) { errors.joinToString() }
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
 * Canonical Room storage for baby sex: Home-LAN wire values only
 * (`female` / `male` / null). Delegates to [com.lezi.babylog.core.model.normalizeBabySex].
 */
internal fun normalizeBabySexForStorage(raw: String?): String? =
    com.lezi.babylog.core.model.normalizeBabySex(raw)
