package com.lezi.babylog.domain

import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.LocalUserDao
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.MembershipDao
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.sync.PolicyClock
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
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
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex

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
) {
    private val queries = CareLogQueries(
        babyDao = babyDao,
        recordDao = recordDao,
        carePlanDao = carePlanDao,
        fulfillmentCandidateDao = fulfillmentCandidateDao,
    )
    private val photoAttachmentReconciler = PhotoAttachmentReconciler(mediaAssetDao)
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

    /**
     * Serializes record create, update, delete, and confirm operations that may
     * change active sleep state. Room transactions provide atomic writes; this
     * lock makes read-check-write sequences deterministic inside this process.
     */
    private val sleepMutationMutex = Mutex()
    private lateinit var carePlans: CarePlanCoordinator
    private val recordMutations: RecordMutationCoordinator = RecordMutationCoordinator(
        recordDao = recordDao,
        carePlanDao = carePlanDao,
        customItemDao = customItemDao,
        photoAttachmentReconciler = photoAttachmentReconciler,
        transactionRunner = transactionRunner,
        reminderProjection = reminderProjection,
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
        requestLocalSync = ::requestLocalSync,
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
        healDuplicateOpenSleeps = recordMutations::healDuplicateOpenSleeps,
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

    suspend fun createBaby(input: CreateBabyInput): Long = babyProfiles.createBaby(input)

    suspend fun ensureFamilyScaffold() = babyProfiles.ensureFamilyScaffold()

    suspend fun addBaby(input: CreateBabyInput): Long = babyProfiles.addBaby(input)

    suspend fun updateBabyProfile(babyId: Long, input: UpdateBabyInput) =
        babyProfiles.updateBabyProfile(babyId, input)

    suspend fun deleteBaby(babyId: Long): Boolean = babyProfiles.deleteBaby(babyId)

    suspend fun getCurrentBaby(): Baby? = babyProfiles.getCurrentBaby()

    suspend fun listBabies(): List<Baby> = babyProfiles.listBabies()

    suspend fun localFamilyIdentity(): LocalFamilyIdentity = babyProfiles.localFamilyIdentity()

    suspend fun updateLocalDisplayName(displayName: String?) =
        babyProfiles.updateLocalDisplayName(displayName)

    suspend fun setCurrentBaby(babyId: Long) = babyProfiles.setCurrentBaby(babyId)

    suspend fun updateBabyLocalPreferences(
        babyId: Long,
        themeColorArgb: Int? = null,
        sortOrder: Int? = null,
    ) = babyProfiles.updateBabyLocalPreferences(babyId, themeColorArgb, sortOrder)

    suspend fun updateBabyLocalOrder(orderedBabyIds: List<Long>) =
        babyProfiles.updateBabyLocalOrder(orderedBabyIds)

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
        customItemCatalog.addCustomItem(name, iconSlot)

    suspend fun updateCustomItem(item: CustomRecordItem) =
        customItemCatalog.updateCustomItem(item)

    suspend fun moveCustomItem(id: Long, delta: Int) =
        customItemCatalog.moveCustomItem(id, delta)

    suspend fun deleteCustomItem(id: Long) =
        customItemCatalog.deleteCustomItem(id)

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
    ): Long = recordMutations.addRecord(
        babyId,
        type,
        timestamp,
        endTimestamp,
        note,
        payloadJson,
        schemaVersion,
        photoLocalPaths,
    )

    suspend fun updateRecord(
        id: Long,
        timestamp: Long,
        endTimestamp: Long?,
        note: String?,
        payloadJson: String,
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        photoLocalPaths: List<String>? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) = recordMutations.updateRecord(
        id,
        timestamp,
        endTimestamp,
        note,
        payloadJson,
        schemaVersion,
        photoLocalPaths,
        nowMillis,
    )

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
    ): Long = recordMutations.convertRecordToCarePlan(
        recordId,
        scheduledAt,
        note,
        payloadJson,
        schemaVersion,
        photoLocalPaths,
        zone,
        nowMillis,
        projectToSystemCalendar,
    )

    suspend fun deleteRecord(id: Long): Boolean = recordMutations.deleteRecord(id)


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
    ): Long = recordMutations.completeNursing(
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
    )

    suspend fun confirmSleep(
        babyId: Long,
        expectedOpenSleepId: Long?,
        timestamp: Long,
        endTimestamp: Long?,
        note: String?,
        payloadJson: String,
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        photoLocalPaths: List<String> = emptyList(),
    ): Long = recordMutations.confirmSleep(
        babyId,
        expectedOpenSleepId,
        timestamp,
        endTimestamp,
        note,
        payloadJson,
        schemaVersion,
        photoLocalPaths,
    )

    suspend fun sleepDown(
        babyId: Long,
        at: Long = System.currentTimeMillis(),
    ): Long = recordMutations.sleepDown(babyId, at)

    suspend fun sleepUp(
        babyId: Long,
        at: Long = System.currentTimeMillis(),
    ): Long = recordMutations.sleepUp(babyId, at)


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
    ): Long = carePlans.createCarePlan(
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
    )

    suspend fun reconcileNextFeedPlan(babyId: Long): NextFeedPlanReconciliation =
        carePlans.reconcileNextFeedPlan(babyId)

    suspend fun scheduleNextFeedCarePlan(
        babyId: Long,
        feedType: RecordType,
        scheduledAt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Long = carePlans.scheduleNextFeedCarePlan(babyId, feedType, scheduledAt, zone, nowMillis)

    suspend fun fulfillCarePlan(
        carePlanId: Long,
        actualTimestamp: Long,
        endTimestamp: Long? = null,
        note: String? = null,
        payloadJson: String? = null,
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        photoLocalPaths: List<String> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Long = carePlans.fulfillCarePlan(
        carePlanId,
        actualTimestamp,
        endTimestamp,
        note,
        payloadJson,
        schemaVersion,
        photoLocalPaths,
        nowMillis,
    )

    suspend fun resolveFulfillmentAuthorityForPlan(carePlanClientUuid: String) =
        carePlans.resolveFulfillmentAuthorityForPlan(carePlanClientUuid)

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
    ): Long = carePlans.convertConflictNotAdoptedToIndependentRecord(candidateClientUuid, nowMillis)

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
    ) = carePlans.updateCarePlan(
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

    suspend fun skipCarePlan(
        carePlanId: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ) = carePlans.skipCarePlan(carePlanId, nowMillis)

    suspend fun deleteCarePlan(
        carePlanId: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean = carePlans.deleteCarePlan(carePlanId, nowMillis)

    suspend fun onFamilyCarePlansApplied(
        planClientUuids: List<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ) = carePlans.onFamilyCarePlansApplied(planClientUuids, nowMillis)

    suspend fun setCarePlanLocalRemindersEnabled(
        enabled: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ) = carePlans.setCarePlanLocalRemindersEnabled(enabled, nowMillis)

    suspend fun projectOrScheduleCarePlanReminder(
        plan: CarePlan,
        projectToSystemCalendar: Boolean = plan.systemCalendarProjectionEnabled,
    ): Boolean = carePlans.projectOrScheduleCarePlanReminder(plan, projectToSystemCalendar)

    suspend fun reprojectOpenFutureSystemCalendarCopies(
        nowMillis: Long = System.currentTimeMillis(),
    ) = carePlans.reprojectOpenFutureSystemCalendarCopies(nowMillis)

    suspend fun isCarePlanSystemCalendarUnsynced(carePlanId: Long): Boolean =
        carePlans.isCarePlanSystemCalendarUnsynced(carePlanId)

    suspend fun disableSystemCalendarProjection() =
        carePlans.disableSystemCalendarProjection()

    suspend fun rescheduleCarePlanReminders(
        nowMillis: Long = System.currentTimeMillis(),
    ) = carePlans.rescheduleCarePlanReminders(nowMillis)

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
        babyProfiles.renameBaby(babyId, nickname)

    suspend fun previewBabyMerge(sourceBabyId: Long, targetBabyId: Long): BabyMergePreview? =
        babyProfiles.previewBabyMerge(sourceBabyId, targetBabyId)

    suspend fun mergeBabyProfiles(sourceBabyId: Long, targetBabyId: Long): Boolean =
        babyProfiles.mergeBabyProfiles(sourceBabyId, targetBabyId)

    suspend fun reconcileMemberLocalBabies(): Int =
        babyProfiles.reconcileMemberLocalBabies()

    internal suspend fun reconcileMemberLocalBabiesAfterFamilyApply(): Int =
        babyProfiles.reconcileMemberLocalBabiesAfterFamilyApply()


    private fun requestLocalSync() {
        syncPort.requestSync(SyncTrigger.LocalWrite)
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
        note = visibleCarePlanNote(note),
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
 * Pure ownership rule for creator-owned family entities (custom item definitions
 * and care plans share the same membership ACL — ADR 0001 / 0006).
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
