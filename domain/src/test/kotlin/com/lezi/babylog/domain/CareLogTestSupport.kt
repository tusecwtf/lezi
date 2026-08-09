package com.lezi.babylog.domain
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FamilyEntity
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.LocalUserDao
import com.lezi.babylog.core.database.LocalUserEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.MembershipDao
import com.lezi.babylog.core.database.MembershipEntity
import com.lezi.babylog.core.database.PendingReminderCleanup
import com.lezi.babylog.core.database.PendingReminderCleanupStore
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.TimelineWindowDao
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.core.datastore.LocalClearSettingsSnapshot
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import com.lezi.babylog.core.model.NEXT_FEED_PLAN_MARKER
import com.lezi.babylog.core.model.NextFeedPlanReconciliation
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.Sex
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.model.displayLabel
import com.lezi.babylog.core.model.isNextFeedPlanNote
import com.lezi.babylog.core.model.isPlanableNonStateful
import com.lezi.babylog.core.model.itemIdentity
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.yield
import org.junit.Test
import com.lezi.babylog.domain.calendar.SYSTEM_CALENDAR_UNSYNCED_LABEL
import com.lezi.babylog.domain.calendar.SystemCalendarEventState
import com.lezi.babylog.domain.calendar.SystemCalendarOwnedEventLookup
import com.lezi.babylog.domain.calendar.SystemCalendarPort
import com.lezi.babylog.domain.calendar.SystemCalendarTarget
import com.lezi.babylog.domain.calendar.SystemCalendarUpsert
import com.lezi.babylog.domain.calendar.SystemCalendarUpsertOutcome
import com.lezi.babylog.domain.calendar.SystemCalendarUpsertResult
import com.lezi.babylog.domain.calendar.encodeSystemCalendarEventMap
import com.lezi.babylog.domain.calendar.parseSystemCalendarEventMap
import com.lezi.babylog.domain.carelog.CareAggregation
import com.lezi.babylog.domain.carelog.FakeConflictDetailCacheDao
import com.lezi.babylog.domain.carelog.FakeConflictSummaryDao
import com.lezi.babylog.domain.carelog.FakeMediaAssetDao
import com.lezi.babylog.domain.carelog.FakeMediaReferenceDao
import com.lezi.babylog.domain.carelog.FakeSourceRelationDao
import com.lezi.babylog.domain.carelog.FakeSuspectedDuplicateGroupDao
import com.lezi.babylog.domain.carelog.FakeWakeObservationDao
import com.lezi.babylog.domain.carelog.matchesSqlLike
import com.lezi.babylog.domain.carelog.weekStartFor
import com.lezi.babylog.domain.careplan.CarePlanReminderProjection
import com.lezi.babylog.domain.careplan.ReminderCleanupPort
import com.lezi.babylog.domain.careplan.nextFeedPlanClientUuid
import com.lezi.babylog.domain.growth.CareLogGrowthMeasurementRecordStore
import com.lezi.babylog.domain.growth.DefaultGrowthMeasurementLifecycle
import com.lezi.babylog.domain.growth.GrowthMeasurementSaveResult
import com.lezi.babylog.domain.growth.GrowthReferenceSource
import com.lezi.babylog.domain.growth.SaveGrowthMeasurement
import com.lezi.babylog.domain.localdata.CalendarReminderMutationGuard
import com.lezi.babylog.domain.localdata.LocalDataClearInProgressException
import com.lezi.babylog.domain.localdata.LocalDataMutationEpoch
import com.lezi.babylog.domain.localdata.DaoLocalDataClearPersistence
import com.lezi.babylog.domain.localdata.DefaultLocalDataClearCoordinator
import com.lezi.babylog.domain.localdata.LocalDataClearCoordinator
import com.lezi.babylog.domain.localdata.LocalDataClearScope
import com.lezi.babylog.domain.localdata.LocalRecordsClearCommittedException
import com.lezi.babylog.domain.localdata.NursingTimerCleanupPort
import com.lezi.babylog.domain.localdata.StoreLocalDataClearSettings
import com.lezi.babylog.domain.nextSyncUpdatedAt
import com.lezi.babylog.domain.toModel

// Shared harness for CareLog* contract suites (ticket 06).

internal val zone = ZoneOffset.UTC


internal data class DeleteBabyAvatarFixture(
    val care: CareLog,
    val fakes: Fakes,
    val sync: RecordingSyncPort,
    val keeperId: Long,
    val targetId: Long,
    val primaryAvatarUuid: String,
)


internal suspend fun seedDeleteBabyAvatarFixture(
    primaryAvatarUuid: String,
    avatarPath: String,
    avatarUpdatedAt: Long,
    mime: String? = null,
    byteSize: Long = 0L,
    extraActiveAvatarUuids: List<Pair<String, String>> = emptyList(),
    otherBabyAvatarUuid: String? = null,
    wireTransactionalSnapshots: Boolean = false,
    cleanupFailure: Throwable? = null,
): DeleteBabyAvatarFixture {
    val sync = RecordingSyncPort().apply {
        cleanupFailure?.let { mediaCleanupFailures += it }
    }
    val fakes = Fakes(sync)
    if (wireTransactionalSnapshots) {
        fakes.wireTransactionalSnapshots()
    }
    val care = fakes.careLog()
    val keeperId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
    val targetId = care.addBaby(
        CreateBabyInput(
            nickname = "待删",
            birthdayEpochDay = 2,
            avatarPath = avatarPath,
        ),
    )
    fakes.babies.update(
        fakes.babies.get(targetId)!!.copy(
            avatarMediaUuid = primaryAvatarUuid,
            avatarPath = avatarPath,
            updatedAt = avatarUpdatedAt,
            syncDirty = false,
        ),
    )
    fakes.media.seed(
        MediaAssetEntity(
            clientUuid = primaryAvatarUuid,
            kind = "avatar",
            babyId = targetId,
            localUri = avatarPath,
            mime = mime,
            byteSize = byteSize,
            createdAt = avatarUpdatedAt,
            updatedAt = avatarUpdatedAt,
            syncDirty = false,
        ),
    )
    extraActiveAvatarUuids.forEachIndexed { index, (uuid, path) ->
        val at = avatarUpdatedAt - 1L - index
        fakes.media.seed(
            MediaAssetEntity(
                clientUuid = uuid,
                kind = "avatar",
                babyId = targetId,
                localUri = path,
                createdAt = at,
                updatedAt = at,
                syncDirty = false,
            ),
        )
    }
    if (otherBabyAvatarUuid != null) {
        val otherId = care.addBaby(CreateBabyInput(nickname = "保留", birthdayEpochDay = 3))
        fakes.media.seed(
            MediaAssetEntity(
                clientUuid = otherBabyAvatarUuid,
                kind = "avatar",
                babyId = otherId,
                localUri = "baby_avatars/other.jpg",
                createdAt = 1L,
                updatedAt = 1L,
                syncDirty = false,
            ),
        )
    }
    return DeleteBabyAvatarFixture(
        care = care,
        fakes = fakes,
        sync = sync,
        keeperId = keeperId,
        targetId = targetId,
        primaryAvatarUuid = primaryAvatarUuid,
    )
}

internal fun openSleep(clientUuid: String, babyId: Long, timestamp: Long) = RecordEntity(
    clientUuid = clientUuid,
    babyId = babyId,
    type = RecordType.SLEEP.key,
    timestamp = timestamp,
    endTimestamp = null,
    note = null,
    payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
    updatedAt = timestamp,
)

internal fun samplePlanPayload(type: RecordType): String = when (type) {
    RecordType.PEE -> """{"pee_amount":2}"""
    RecordType.POOP ->
        """{"stool_amount":2,"stool_consistency":1,"stool_color":1}"""
    RecordType.BOTH_DIAPER ->
        """{"pee_amount":1,"stool_amount":1,"stool_consistency":1,"stool_color":1}"""
    RecordType.FORMULA, RecordType.PUMPED_FEED -> """{"amount_ml":90}"""
    RecordType.PUMP_EXPRESS -> """{"amount_ml":60}"""
    RecordType.TEMPERATURE -> """{"celsius":36.8}"""
    RecordType.MEDICINE -> """{"name":"维生素","dose":"1滴"}"""
    RecordType.VACCINE -> """{"name":"五联"}"""
    RecordType.HEIGHT -> """{"value":55.0,"unit":"cm"}"""
    RecordType.WEIGHT -> """{"value":5000.0,"unit":"g"}"""
    RecordType.HEAD, RecordType.CHEST, RecordType.FOOT_SIZE ->
        """{"value":35.0,"unit":"cm"}"""
    RecordType.BABY_FOOD, RecordType.SNACK, RecordType.DRINK ->
        """{"content":"米糊","amount":"1勺"}"""
    RecordType.DIARY -> """{"body":"备注"}"""
    RecordType.BATH, RecordType.WALK -> "{}"
    RecordType.COUGH, RecordType.RASH, RecordType.VOMIT, RecordType.INJURY ->
        """{"severity":2,"description":"备注"}"""
    RecordType.HOSPITAL -> """{"reason":"复诊","advice":"备注"}"""
    else -> "{}"
}

internal suspend fun Fakes.seedFamilyAuthorityBaby(
    clientUuid: String = "baby-authority",
): Long = babies.upsert(
    BabyEntity(
        familyId = 1L,
        clientUuid = clientUuid,
        nickname = "年年",
        birthdayEpochDay = 1,
        themeColorArgb = 0,
        updatedAt = 1L,
        syncDirty = false,
        familyAuthority = true,
    ),
)


internal class Fakes(
    private val syncPort: com.lezi.babylog.sync.SyncPort =
        com.lezi.babylog.sync.NoOpSyncPort(),
    val localDataMutationEpoch: LocalDataMutationEpoch = LocalDataMutationEpoch(),
) {
    val users = FakeLocalUserDao()
    val families = FakeFamilyDao()
    val memberships = FakeMembershipDao()
    val babies = FakeBabyDao()
    val fulfillmentCandidates = FakeFulfillmentCandidateDao()
    val wakeObservations = FakeWakeObservationDao()
    val records = FakeRecordDao(
        conflictExcluded = {
            fulfillmentCandidates.itemsSnapshot()
                .filter {
                    it.adoptionStatus ==
                        com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED &&
                        it.deletedAt == null
                }
                .map { it.recordClientUuid }
                .toSet()
        },
        hasActiveLegalWake = { sleepClientUuid, sleepStart ->
            wakeObservations.itemsSnapshot().any { wake ->
                wake.sleepRecordClientUuid == sleepClientUuid &&
                    wake.deletedAt == null &&
                    !wake.withdrawn &&
                    wake.wakeTimestamp >= sleepStart
            }
        },
    )
    val carePlans = FakeCarePlanDao()
    val customItems = FakeCustomItemDao()
    val media = FakeMediaAssetDao()
    val timelineWindow = FakeCareReadProjectionDao(records, media, wakeObservations)
    val conflictSummaries = FakeConflictSummaryDao()
    val conflictDetailCache = FakeConflictDetailCacheDao()
    val suspectedDuplicates = FakeSuspectedDuplicateGroupDao()
    val sourceRelations = FakeSourceRelationDao()
    val mediaReferences = FakeMediaReferenceDao()
    val pendingReminderCleanup = FakePendingReminderCleanupStore()
    val settings = FakeSettingsStore()
    val reminders = FakeReminderCleanupPort()
    val systemCalendar = FakeSystemCalendarPort()
    val transactions = RecordingTransactionRunner()
    val calendarReminderMutationGuard = CalendarReminderMutationGuard()
    val clock = FakePolicyClock()

    init {
        // Wake mutations must re-emit open-sleep observers (Room joins both tables).
        wakeObservations.onMutation = {
            records.touch()
            timelineWindow.invalidate()
        }
        records.onMutation = timelineWindow::invalidate
    }

    fun wireTransactionalSnapshots() {
        transactions.onBegin += {
            records.beginTx()
            media.beginTx()
            carePlans.beginTx()
            fulfillmentCandidates.beginTx()
            customItems.beginTx()
        }
        transactions.onCommit += {
            records.commitTx()
            media.commitTx()
            carePlans.commitTx()
            fulfillmentCandidates.commitTx()
            customItems.commitTx()
        }
        transactions.onRollback += {
            records.rollbackTx()
            media.rollbackTx()
            carePlans.rollbackTx()
            fulfillmentCandidates.rollbackTx()
            customItems.rollbackTx()
        }
    }

    fun localDataClearCoordinator(
        sync: com.lezi.babylog.sync.SyncPort = syncPort,
    ): LocalDataClearCoordinator = DefaultLocalDataClearCoordinator(
        persistence = DaoLocalDataClearPersistence(
            babyDao = babies,
            recordDao = records,
            carePlanDao = carePlans,
            customItemDao = customItems,
            localUserDao = users,
            familyDao = families,
            membershipDao = memberships,
            fulfillmentCandidateDao = fulfillmentCandidates,
            wakeObservationDao = wakeObservations,
            conflictSummaryDao = conflictSummaries,
            conflictDetailCacheDao = conflictDetailCache,
            suspectedDuplicateGroupDao = suspectedDuplicates,
            sourceRelationDao = sourceRelations,
            mediaReferenceDao = mediaReferences,
            pendingReminderCleanupStore = pendingReminderCleanup,
            transactionRunner = transactions,
        ),
        settings = StoreLocalDataClearSettings(settings),
        syncPort = sync,
        reminderCleanup = reminders,
        nursingTimerCleanup = NursingTimerCleanupPort { },
        systemCalendar = systemCalendar,
        widgetCleanup = object : com.lezi.babylog.domain.localdata.WidgetCleanupPort {
            override suspend fun clearAllWidgetState() = Unit
        },
        pendingReminderCleanupStore = pendingReminderCleanup,
        mutationGuard = calendarReminderMutationGuard,
        localDataMutationEpoch = localDataMutationEpoch,
    )

    fun careLog() = CareLog(
        babies,
        records,
        carePlans,
        customItems,
        users,
        families,
        memberships,
        media,
        settings,
        syncPort,
        reminders,
        transactions,
        systemCalendar,
        fulfillmentCandidates,
        calendarReminderMutationGuard,
        clock,
        mediaPathGate = com.lezi.babylog.core.database.MediaLocalPathGate(),
        localDataMutationEpoch = localDataMutationEpoch,
        wakeObservationDao = wakeObservations,
        conflictSummaryDao = conflictSummaries,
        conflictDetailCacheDao = conflictDetailCache,
        sourceRelationDao = sourceRelations,
        recordWakeProjectionDao = timelineWindow,
    )

    fun reminderProjection() = CarePlanReminderProjection(
        carePlanDao = carePlans,
        babyDao = babies,
        mediaAssetDao = media,
        settings = settings,
        reminderCleanup = reminders,
        systemCalendar = systemCalendar,
        calendarReminderMutationGuard = calendarReminderMutationGuard,
    )
}

internal class FakeCareReadProjectionDao(
    private val records: FakeRecordDao,
    private val media: FakeMediaAssetDao,
    private val wakes: FakeWakeObservationDao,
) : TimelineWindowDao {
    private val invalidations = MutableStateFlow(0L)

    override fun observeInvalidations(): Flow<Long> = invalidations

    fun invalidate() {
        invalidations.value += 1L
    }

    override suspend fun listRecordRoots(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<RecordEntity> = records.listForBaby(babyId).filter { root ->
        root.timestamp < endExclusive &&
            (
                root.timestamp >= startInclusive ||
                    root.type == RecordType.SLEEP.key &&
                    projectedEnd(root) > startInclusive
                )
    }

    override suspend fun listWakeObservationRoots(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<WakeObservationEntity> {
        val roots = listRecordRoots(babyId, startInclusive, endExclusive)
            .mapTo(hashSetOf(), RecordEntity::clientUuid)
        return wakes.itemsSnapshot().filter { it.sleepRecordClientUuid in roots }
    }

    override suspend fun listActiveWakeMedia(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<MediaAssetEntity> {
        val wakeIds = listWakeObservationRoots(babyId, startInclusive, endExclusive)
            .mapTo(hashSetOf(), WakeObservationEntity::id)
        return media.listAllIncludingDeleted().filter {
            it.wakeObservationId in wakeIds && it.deletedAt == null && it.kind == "wake"
        }
    }

    override suspend fun listRecordRootsByClientUuids(
        rootClientUuids: List<String>,
    ): List<RecordEntity> {
        val requested = records.listAllIncludingDeleted()
            .filter { it.clientUuid in rootClientUuids }
        val babyIds = requested.mapTo(hashSetOf(), RecordEntity::babyId)
        val peers = babyIds.flatMap { babyId -> listOpenSleepCandidateRoots(babyId) }
        return (requested + peers).distinctBy(RecordEntity::clientUuid)
    }

    override suspend fun listOpenSleepCandidateRoots(babyId: Long): List<RecordEntity> =
        records.listForBaby(babyId).filter { root ->
            root.type == RecordType.SLEEP.key && root.endTimestamp == null
        }.sortedWith(compareByDescending<RecordEntity> { it.timestamp }.thenByDescending { it.clientUuid })

    override suspend fun listWakesForOpenSleepCandidates(babyId: Long): List<WakeObservationEntity> {
        val roots = listOpenSleepCandidateRoots(babyId)
            .mapTo(hashSetOf(), RecordEntity::clientUuid)
        return wakes.itemsSnapshot().filter { it.sleepRecordClientUuid in roots }
    }

    override suspend fun listWakeMediaForOpenSleepCandidates(babyId: Long): List<MediaAssetEntity> {
        val wakeIds = listWakesForOpenSleepCandidates(babyId)
            .mapTo(hashSetOf(), WakeObservationEntity::id)
        return media.listAllIncludingDeleted().filter {
            it.wakeObservationId in wakeIds && it.deletedAt == null && it.kind == "wake"
        }
    }

    override suspend fun listWakeObservationsForRoots(
        sleepRootClientUuids: List<String>,
    ): List<WakeObservationEntity> {
        val projectedRoots = listRecordRootsByClientUuids(sleepRootClientUuids)
            .mapTo(hashSetOf(), RecordEntity::clientUuid)
        return wakes.itemsSnapshot().filter { it.sleepRecordClientUuid in projectedRoots }
    }

    override suspend fun listActiveWakeMediaForRoots(
        sleepRootClientUuids: List<String>,
    ): List<MediaAssetEntity> {
        val wakeIds = listWakeObservationsForRoots(sleepRootClientUuids)
            .mapTo(hashSetOf(), WakeObservationEntity::id)
        return media.listAllIncludingDeleted().filter {
            it.wakeObservationId in wakeIds && it.deletedAt == null && it.kind == "wake"
        }
    }

    override suspend fun listPlanRoots(
        babyId: Long,
        dayStart: Long,
        dayEnd: Long,
        nowMillis: Long,
        includeOverdue: Boolean,
    ): List<CarePlanEntity> = emptyList()

    override suspend fun listActiveLogMedia(
        babyId: Long,
        recordStartInclusive: Long,
        recordEndExclusive: Long,
        planDayStart: Long,
        planDayEnd: Long,
        nowMillis: Long,
        includeOverdue: Boolean,
    ): List<MediaAssetEntity> = emptyList()

    private fun projectedEnd(root: RecordEntity): Long =
        if (root.type != RecordType.SLEEP.key) {
            root.endTimestamp ?: root.timestamp
        } else {
            com.lezi.babylog.core.model.projectSleepInterval(
                sleepClientUuid = root.clientUuid,
                startTimestamp = root.timestamp,
                effectiveWakeObservationClientUuid = root.effectiveWakeObservationClientUuid,
                observations = wakes.itemsSnapshot()
                    .filter { it.sleepRecordClientUuid == root.clientUuid }
                    .map { wake ->
                        com.lezi.babylog.core.model.WakeObservationFact(
                            clientUuid = wake.clientUuid,
                            wakeTimestamp = wake.wakeTimestamp,
                            withdrawn = wake.withdrawn,
                            observerMembershipId = wake.observerMembershipId,
                            note = wake.note,
                            deleted = wake.deletedAt != null,
                        )
                    },
                legacyEndTimestamp = root.endTimestamp,
            ).endTimestamp ?: Long.MAX_VALUE
        }
}

internal class FakePolicyClock(var now: Long = 1_000L) : com.lezi.babylog.sync.session.PolicyClock {
    override fun nowMillis(): Long = now
}

internal class FakeSystemCalendarPort : SystemCalendarPort {
    var permission = false
    var failUpsert = false
    var failReminder = false
    var providerStillOwnsStaleReminder = false
    var beforeUpsert: suspend () -> Unit = {}
    /** When non-null, only these calendar ids are writable (simulates vanished target). */
    var writableCalendarIds: Set<String>? = null
    /** Event ids that no longer exist in the provider (external delete). */
    val missingEventIds = mutableSetOf<String>()
    val upserts = mutableListOf<SystemCalendarUpsert>()
    val deleted = mutableListOf<String>()
    private var nextEventId = 1L
    private val liveEventIds = mutableSetOf<String>()
    private val eventOwners = mutableMapOf<String, String>()

    override fun hasCalendarPermission(): Boolean = permission

    override suspend fun listWritableCalendars(): List<SystemCalendarTarget> =
        if (!permission) {
            emptyList()
        } else {
            val ids = writableCalendarIds ?: setOf("cal-1")
            ids.map { id ->
                SystemCalendarTarget(
                    calendarId = id,
                    displayName = "本地",
                    accountName = "local",
                )
            }
        }

    override suspend fun upsertEvent(
        request: SystemCalendarUpsert,
    ): SystemCalendarUpsertResult {
        upserts += request
        beforeUpsert()
        if (providerStillOwnsStaleReminder) {
            return SystemCalendarUpsertResult(
                eventId = request.existingEventId,
                outcome = SystemCalendarUpsertOutcome.ProviderStillOwnsStale,
            )
        }
        if (!permission || failUpsert || !isWritableCalendar(request.calendarId)) {
            return SystemCalendarUpsertResult(
                eventId = request.existingEventId,
                outcome = SystemCalendarUpsertOutcome.ReleasedOrAbsent,
            )
        }
        val existing = request.existingEventId
        if (!existing.isNullOrBlank()) {
            if (
                existing !in missingEventIds &&
                existing in liveEventIds &&
                eventOwners[existing] == request.carePlanClientUuid
            ) {
                return SystemCalendarUpsertResult(
                    eventId = existing,
                    outcome = if (failReminder) {
                        SystemCalendarUpsertOutcome.ReleasedOrAbsent
                    } else {
                        SystemCalendarUpsertOutcome.CurrentReady
                    },
                )
            }
        }
        val id = "evt-${nextEventId++}"
        liveEventIds += id
        eventOwners[id] = request.carePlanClientUuid
        missingEventIds.remove(id)
        return SystemCalendarUpsertResult(
            eventId = id,
            outcome = if (failReminder) {
                SystemCalendarUpsertOutcome.ReleasedOrAbsent
            } else {
                SystemCalendarUpsertOutcome.CurrentReady
            },
        )
    }

    override suspend fun findOwnedEvent(
        carePlanClientUuid: String,
    ): SystemCalendarOwnedEventLookup {
        if (!permission) return SystemCalendarOwnedEventLookup.Unavailable
        val ids = liveEventIds.filterTo(linkedSetOf()) {
            it !in missingEventIds && eventOwners[it] == carePlanClientUuid
        }
        return if (ids.isEmpty()) {
            SystemCalendarOwnedEventLookup.Absent
        } else {
            SystemCalendarOwnedEventLookup.Found(ids)
        }
    }

    override suspend fun deleteEvent(
        eventId: String,
        carePlanClientUuid: String?,
    ): Boolean {
        deleted += eventId
        if (!permission) return false
        if (carePlanClientUuid != null && eventOwners[eventId] != carePlanClientUuid) return false
        liveEventIds.remove(eventId)
        eventOwners.remove(eventId)
        return true
    }

    override suspend fun eventState(
        eventId: String,
        carePlanClientUuid: String?,
    ): SystemCalendarEventState = when {
        !permission -> SystemCalendarEventState.UNAVAILABLE
        eventId in missingEventIds -> SystemCalendarEventState.ABSENT
        eventId in liveEventIds &&
            (carePlanClientUuid == null || eventOwners[eventId] == carePlanClientUuid) ->
            SystemCalendarEventState.PRESENT
        else -> SystemCalendarEventState.ABSENT
    }

    override suspend fun isWritableCalendar(calendarId: String): Boolean =
        permission && listWritableCalendars().any { it.calendarId == calendarId }
}


internal class FakeReminderCleanupPort : ReminderCleanupPort {
    val scheduledCarePlanIds = linkedSetOf<Long>()
    val cancelledCarePlanIds = mutableListOf<Long>()
    val carePlanOperations = mutableListOf<String>()
    var carePlanScheduleEnabled: Boolean = true
    var carePlanPermissionGranted: Boolean = true
    var carePlanScheduleFailure: Throwable? = null
    var carePlanCancelFailure: Throwable? = null

    override suspend fun scheduleCarePlan(plan: CarePlan): Boolean {
        carePlanScheduleFailure?.let { throw it }
        if (!carePlanScheduleEnabled || !carePlanPermissionGranted) return false
        if (plan.deletedAt != null) return false
        // Domain already gates future scheduledAt via nowMillis. Tests inject
        // synthetic epochs, so do not re-check wall-clock here (real adapter does).
        if (plan.status != CarePlanStatus.PENDING) return false
        carePlanOperations += "schedule:${plan.id}:${plan.clientUuid}"
        scheduledCarePlanIds += plan.id
        // Replace semantics: a later schedule supersedes prior cancel bookkeeping.
        cancelledCarePlanIds.removeAll { it == plan.id }
        return true
    }

    override suspend fun cancelCarePlan(carePlanId: Long) {
        carePlanCancelFailure?.let { throw it }
        carePlanOperations += "cancel:$carePlanId"
        scheduledCarePlanIds -= carePlanId
        cancelledCarePlanIds += carePlanId
    }

}

internal class FakePendingReminderCleanupStore : PendingReminderCleanupStore {
    var pending: PendingReminderCleanup? = null
    var loadFailure: Throwable? = null
    var deleteCount: Int = 0

    override suspend fun load(
        scope: LocalDataClearScope,
    ): PendingReminderCleanup? {
        loadFailure?.let { throw it }
        return pending?.takeIf { it.scope == scope }
    }

    override suspend fun upsert(pending: PendingReminderCleanup) {
        val existing = this.pending?.takeIf { it.scope == pending.scope }
        this.pending = pending.copy(
            carePlanIds = existing?.carePlanIds.orEmpty() + pending.carePlanIds,
            systemCalendarProjections =
                existing?.systemCalendarProjections.orEmpty() +
                    pending.systemCalendarProjections,
            familyServerRetained =
                existing?.familyServerRetained == true || pending.familyServerRetained,
        )
    }

    override suspend fun delete(scope: LocalDataClearScope) {
        deleteCount += 1
        if (pending?.scope == scope) pending = null
    }
}

internal class RecordingTransactionRunner :
    com.lezi.babylog.core.database.DatabaseTransactionRunner {
    private val serializationMutex = Mutex()
    var runCount = 0
    var serializeRuns = false
    var beforeNextRun: (suspend () -> Unit)? = null
    val onBegin = mutableListOf<() -> Unit>()
    val onCommit = mutableListOf<() -> Unit>()
    val onRollback = mutableListOf<() -> Unit>()

    override suspend fun <T> run(block: suspend () -> T): T =
        if (serializeRuns) {
            serializationMutex.withLock { execute(block) }
        } else {
            execute(block)
        }

    private suspend fun <T> execute(block: suspend () -> T): T {
        runCount += 1
        onBegin.forEach { it() }
        beforeNextRun?.also { beforeNextRun = null }?.invoke()
        return try {
            val result = block()
            onCommit.forEach { it() }
            result
        } catch (t: Throwable) {
            onRollback.forEach { it() }
            throw t
        }
    }
}

internal class RecordingSyncPort(
    delegate: com.lezi.babylog.sync.SyncPort = com.lezi.babylog.sync.NoOpSyncPort(),
    private val deviceId: String = "",
    private val familyId: String = "",
    private val membershipId: String = "",
    private val role: com.lezi.babylog.sync.session.FamilyRole = com.lezi.babylog.sync.session.FamilyRole.None,
    pendingCreatorAcknowledgements: Set<com.lezi.babylog.sync.session.CreatorAcknowledgementRef> =
        emptySet(),
) : com.lezi.babylog.sync.SyncPort by delegate {
    var requests = 0
    var localRecordReconciliations = 0
    var fullLocalWipes = 0
    val mediaCleanupCandidates = mutableListOf<Set<String>>()
    val mediaCleanupFailures = ArrayDeque<Throwable>()
    var localClearEntered: CompletableDeferred<Unit>? = null
    var allowLocalClearToContinue: CompletableDeferred<Unit>? = null

    private val sessionState = MutableStateFlow(
        com.lezi.babylog.sync.session.SyncSession(
            familyId = familyId,
            deviceId = deviceId,
            membershipId = membershipId,
            role = role,
            pendingCreatorAcknowledgements = pendingCreatorAcknowledgements,
        ),
    )

    override fun session(): Flow<com.lezi.babylog.sync.session.SyncSession> = sessionState

    fun currentSession(): com.lezi.babylog.sync.session.SyncSession = sessionState.value

    fun replaceSession(session: com.lezi.babylog.sync.session.SyncSession) {
        sessionState.value = session
    }

    override fun requestSync(trigger: com.lezi.babylog.sync.SyncTrigger) {
        requests++
    }

    override fun notifyLocalChanges() {
        requests++
    }

    override suspend fun cleanupTombstonedMedia(clientUuids: Set<String>): Result<Unit> {
        mediaCleanupCandidates += clientUuids
        return mediaCleanupFailures.removeFirstOrNull()
            ?.let { Result.failure(it) }
            ?: Result.success(Unit)
    }

    override suspend fun clearLocalData(
        scope: LocalDataClearScope,
        workflow: com.lezi.babylog.sync.LocalClearWorkflow,
    ): Result<Unit> {
        when (scope) {
            LocalDataClearScope.RecordsOnly -> localRecordReconciliations++
            LocalDataClearScope.AllLocalData -> fullLocalWipes++
        }
        workflow.withLocalExclusion {
            localClearEntered?.complete(Unit)
            allowLocalClearToContinue?.await()
            workflow.clearRoom()
            workflow.finishCommitted()
        }
        return Result.success(Unit)
    }
}

internal class FakeFulfillmentCandidateDao : FulfillmentCandidateDao {
    private val items = MutableStateFlow<List<FulfillmentCandidateEntity>>(emptyList())
    private val seq = AtomicLong(1)
    private var snapshot: List<FulfillmentCandidateEntity>? = null

    fun itemsSnapshot(): List<FulfillmentCandidateEntity> = items.value

    fun beginTx() {
        snapshot = items.value
    }

    fun commitTx() {
        snapshot = null
    }

    fun rollbackTx() {
        snapshot?.let { items.value = it }
        snapshot = null
    }

    override suspend fun get(id: Long): FulfillmentCandidateEntity? =
        items.value.firstOrNull { it.id == id }

    override suspend fun getByClientUuid(clientUuid: String): FulfillmentCandidateEntity? =
        items.value.firstOrNull { it.clientUuid == clientUuid }

    override suspend fun listForCarePlan(carePlanClientUuid: String): List<FulfillmentCandidateEntity> =
        items.value.filter { it.carePlanClientUuid == carePlanClientUuid }.sortedBy { it.id }

    override suspend fun listForRecord(recordClientUuid: String): List<FulfillmentCandidateEntity> =
        items.value.filter { it.recordClientUuid == recordClientUuid }.sortedBy { it.id }

    override suspend fun listAllIncludingDeleted(): List<FulfillmentCandidateEntity> = items.value

    override suspend fun listPendingSync(): List<FulfillmentCandidateEntity> =
        items.value.filter { it.syncDirty }.sortedBy { it.id }

    override suspend fun listConflictNotAdoptedRecordUuids(): List<String> =
        items.value
            .filter {
                it.adoptionStatus ==
                    com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED &&
                    it.deletedAt == null
            }
            .map { it.recordClientUuid }

    override suspend fun listConflictNotAdopted(): List<FulfillmentCandidateEntity> =
        items.value
            .filter {
                it.adoptionStatus ==
                    com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED &&
                    it.deletedAt == null
            }
            .sortedWith(compareBy({ it.confirmedAt }, { it.clientUuid }))

    override suspend fun listConflictNotAdoptedForCarePlan(
        carePlanClientUuid: String,
    ): List<FulfillmentCandidateEntity> =
        listConflictNotAdopted().filter { it.carePlanClientUuid == carePlanClientUuid }

    override fun observeConflictNotAdoptedRecordUuids(): Flow<List<String>> =
        items.map { list ->
            list.filter {
                it.adoptionStatus ==
                    com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED &&
                    it.deletedAt == null
            }.map { it.recordClientUuid }
        }

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        items.value = items.value.map {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int {
        val before = items.value.size
        items.value = items.value.filterNot {
            it.clientUuid == clientUuid && it.updatedAt == updatedAt && it.deletedAt != null
        }
        return before - items.value.size
    }

    override suspend fun markAllPendingSync() {
        items.value = items.value.map { it.copy(syncDirty = true) }
    }

    override suspend fun upsert(candidate: FulfillmentCandidateEntity): Long {
        val id = candidate.id.takeIf { it > 0 } ?: seq.getAndIncrement()
        items.value = items.value.filterNot { it.id == id || it.clientUuid == candidate.clientUuid } +
            candidate.copy(id = id)
        return id
    }

    override suspend fun update(candidate: FulfillmentCandidateEntity) {
        items.value = items.value.map { if (it.id == candidate.id) candidate else it }
    }

    override suspend fun deleteAll() {
        items.value = emptyList()
    }
}

internal class FakeCarePlanDao : CarePlanDao {
    private val items = MutableStateFlow<List<CarePlanEntity>>(emptyList())
    private val seq = AtomicLong(1)
    private var snapshot: List<CarePlanEntity>? = null

    fun beginTx() {
        snapshot = items.value
    }

    fun commitTx() {
        snapshot = null
    }

    fun rollbackTx() {
        snapshot?.let { items.value = it }
        snapshot = null
    }

    override fun observeDayPending(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<CarePlanEntity>> = items.map { list ->
        list.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.status in setOf("pending", "missed") &&
                it.scheduledAt >= startInclusive &&
                it.scheduledAt < endExclusive
        }.sortedBy { it.scheduledAt }
    }

    override fun observeTodayPending(
        babyId: Long,
        dayStart: Long,
        dayEnd: Long,
        nowMillis: Long,
    ): Flow<List<CarePlanEntity>> = items.map { list ->
        list.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.status in setOf("pending", "missed") &&
                (it.scheduledAt < nowMillis ||
                    (it.scheduledAt >= dayStart && it.scheduledAt < dayEnd))
        }.sortedBy { it.scheduledAt }
    }

    override fun observeRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<CarePlanEntity>> = items.map { list ->
        list.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.scheduledAt >= startInclusive &&
                it.scheduledAt < endExclusive
        }.sortedBy { it.scheduledAt }
    }

    override suspend fun listOpenFuture(babyId: Long, nowMillis: Long): List<CarePlanEntity> =
        items.value.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.status in setOf("pending", "missed") &&
                it.scheduledAt > nowMillis
        }.sortedBy { it.scheduledAt }

    override suspend fun listAllOpenFuture(nowMillis: Long): List<CarePlanEntity> =
        items.value.filter {
            it.deletedAt == null &&
                it.status in setOf("pending", "missed") &&
                it.scheduledAt > nowMillis
        }.sortedBy { it.scheduledAt }

    override suspend fun get(id: Long): CarePlanEntity? =
        items.value.firstOrNull { it.id == id }

    override suspend fun getByClientUuid(clientUuid: String): CarePlanEntity? =
        items.value.firstOrNull { it.clientUuid == clientUuid }

    /** Test-only: includes tombstones for convert failure rollback assertions. */
    override suspend fun listAllIncludingDeleted(): List<CarePlanEntity> = items.value

    override suspend fun listPendingSync(): List<CarePlanEntity> =
        items.value.filter { it.syncDirty }.sortedBy { it.id }

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        items.value = items.value.map {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int {
        val before = items.value.size
        items.value = items.value.filterNot {
            it.clientUuid == clientUuid && it.updatedAt == updatedAt && it.deletedAt != null
        }
        return before - items.value.size
    }

    override suspend fun markAllPendingSync() {
        items.value = items.value.map { it.copy(syncDirty = true) }
    }

    override suspend fun upsert(plan: CarePlanEntity): Long {
        val id = plan.id.takeIf { it > 0 } ?: seq.getAndIncrement()
        items.value = items.value.filterNot { it.id == id } + plan.copy(id = id)
        return id
    }

    override suspend fun update(plan: CarePlanEntity) {
        items.value = items.value.map { if (it.id == plan.id) plan else it }
    }

    override suspend fun updateSystemCalendarProjection(
        clientUuid: String,
        eventId: String?,
        reminderReady: Boolean,
        pending: Boolean,
    ) {
        items.value = items.value.map {
            if (it.clientUuid == clientUuid) {
                it.copy(
                    systemCalendarEventId = eventId,
                    systemCalendarReminderReady = reminderReady,
                    systemCalendarProjectionPending = pending,
                )
            } else {
                it
            }
        }
    }

    override suspend fun updateSystemCalendarProjectionEnabled(
        clientUuid: String,
        enabled: Boolean,
    ) {
        items.value = items.value.map {
            if (it.clientUuid == clientUuid) {
                it.copy(
                    systemCalendarProjectionEnabled = enabled,
                )
            } else {
                it
            }
        }
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        items.value = items.value.map {
            if (it.id == id) {
                it.copy(deletedAt = deletedAt, updatedAt = deletedAt, syncDirty = true)
            } else {
                it
            }
        }
    }

    override suspend fun deleteAll() {
        items.value = emptyList()
    }
}

internal class FakeCustomItemDao : CustomItemDao {
    private val items = MutableStateFlow<List<CustomItemEntity>>(emptyList())
    private val seq = AtomicLong(1)
    private var transactionSnapshot: List<CustomItemEntity>? = null
    private var updateCalls = 0
    private var failOnUpdateCall: Int? = null
    var afterListAllSnapshot: suspend () -> Unit = {}

    fun beginTx() {
        transactionSnapshot = items.value
    }

    fun commitTx() {
        transactionSnapshot = null
    }

    fun rollbackTx() {
        transactionSnapshot?.let { items.value = it }
        transactionSnapshot = null
    }

    fun failOnNthUpdateFromNow(offset: Int) {
        require(offset > 0)
        failOnUpdateCall = updateCalls + offset
    }

    override fun observeAll(): Flow<List<CustomItemEntity>> =
        items.map { list -> list.filter { it.deletedAt == null } }

    override suspend fun listAll(): List<CustomItemEntity> {
        val snapshot = items.value.filter { it.deletedAt == null }
        afterListAllSnapshot()
        return snapshot
    }

    override suspend fun listAllIncludingDeleted(): List<CustomItemEntity> = items.value

    override suspend fun getById(id: Long): CustomItemEntity? =
        items.value.firstOrNull { it.id == id }

    override suspend fun getByClientUuid(clientUuid: String): CustomItemEntity? =
        items.value.firstOrNull { it.clientUuid == clientUuid }

    override suspend fun listPendingSync(): List<CustomItemEntity> =
        items.value.filter { it.syncDirty }

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        items.value = items.value.map {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int {
        val before = items.value.size
        items.value = items.value.filterNot {
            it.clientUuid == clientUuid && it.updatedAt == updatedAt && it.deletedAt != null
        }
        return before - items.value.size
    }

    override suspend fun markAllPendingSync() {
        items.value = items.value.map { it.copy(syncDirty = true) }
    }

    override suspend fun upsert(item: CustomItemEntity): Long {
        val id = item.id.takeIf { it > 0 } ?: seq.getAndIncrement()
        items.value = items.value.filterNot { it.id == id } + item.copy(id = id)
        return id
    }

    override suspend fun update(item: CustomItemEntity) {
        updateCalls += 1
        check(updateCalls != failOnUpdateCall) { "fixture custom-item update failed" }
        items.value = items.value.map { if (it.id == item.id) item else it }
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        // Keep tombstone in memory (include-deleted semantics for leave/ownership tests).
        items.value = items.value.map {
            if (it.id == id) {
                it.copy(deletedAt = deletedAt, updatedAt = deletedAt, syncDirty = true)
            } else {
                it
            }
        }
    }

    override suspend fun deleteAll() {
        items.value = emptyList()
    }
}

internal class FakeSettingsStore : SettingsStore {
    private val babyId = MutableStateFlow<Long?>(null)
    private val timer = MutableStateFlow<String?>(null)
    private val dark = MutableStateFlow("system")
    private val step = MutableStateFlow(5)
    private val timerEnabled = MutableStateFlow(true)
    private val interval = MutableStateFlow(180)
    private val recordAt = MutableStateFlow("end")
    private val order = MutableStateFlow("[]")
    private val hidden = MutableStateFlow(emptySet<String>())
    private val weekStart = MutableStateFlow(1)
    private val systemCalEnabled = MutableStateFlow(false)
    private val carePlanLocalReminders = MutableStateFlow(true)
    private val systemCalId = MutableStateFlow<String?>(null)
    private val systemCalDisclosure = MutableStateFlow(2)
    private val systemCalMap = MutableStateFlow("{}")
    private val showAvg = MutableStateFlow(false)
    private val comparePrev = MutableStateFlow(false)

    private fun snapshot(): SettingsLocal = SettingsLocal(
        darkMode = dark.value,
        amountStepMl = step.value,
        timerEnabled = timerEnabled.value,
        nursingIntervalMin = interval.value,
        recordAtStartOrEnd = recordAt.value,
        itemOrderJson = order.value,
        hiddenItems = hidden.value,
        weekStart = weekStart.value,
        systemCalendarEnabled = systemCalEnabled.value,
        carePlanLocalRemindersEnabled = carePlanLocalReminders.value,
        systemCalendarId = systemCalId.value,
        systemCalendarDisclosureLevel = systemCalDisclosure.value,
        systemCalendarEventMapJson = systemCalMap.value,
    )

    private val settingsState = MutableStateFlow(snapshot())

    private fun publish() {
        settingsState.value = snapshot()
    }

    override val settings: Flow<SettingsLocal> = settingsState

    override val currentBabyId: Flow<Long?> = babyId
    override val nursingTimerJson: Flow<String?> = timer

    override suspend fun setCurrentBabyId(id: Long?) {
        babyId.value = id
    }

    override suspend fun setDarkMode(mode: String) {
        dark.value = mode
        publish()
    }

    override suspend fun setVisualStyle(style: String) = Unit
    override suspend fun setPreferredHand(hand: String) = Unit

    override suspend fun setTimerEnabled(enabled: Boolean) {
        timerEnabled.value = enabled
        publish()
    }

    override suspend fun setAmountStepMl(stepMl: Int) {
        step.value = stepMl
        publish()
    }

    override suspend fun setTimeStepMin(step: Int) = Unit

    override suspend fun setTimePickerStyle(style: String) = Unit
    override suspend fun setInfantFeverAdviceEnabled(enabled: Boolean) = Unit

    override suspend fun setNursingIntervalMin(min: Int) {
        interval.value = min
        publish()
    }

    override suspend fun setRecordAt(startOrEnd: String) {
        recordAt.value = startOrEnd
        publish()
    }

    override suspend fun setDeviceLayoutSnapshot(snapshot: DeviceLayoutSnapshot) {
        order.value = snapshot.itemOrderJson
        hidden.value = snapshot.hiddenItems
        publish()
    }

    override suspend fun setTimelineOrder(order: String) = Unit

    override suspend fun setNursingTimerJson(json: String?) {
        timer.value = json
    }

    override suspend fun setWeekStart(day: Int) {
        weekStart.value = day
        publish()
    }

    override val showAvgSleep = showAvg
    override val comparePrevWeek = comparePrev
    override suspend fun setShowAvgSleep(enabled: Boolean) {
        showAvg.value = enabled
    }
    override suspend fun setCarePlanLocalRemindersEnabled(enabled: Boolean) {
        carePlanLocalReminders.value = enabled
        publish()
    }

    override suspend fun setSystemCalendarEnabled(enabled: Boolean) {
        systemCalEnabled.value = enabled
        publish()
    }

    override suspend fun setSystemCalendarId(calendarId: String?) {
        systemCalId.value = calendarId
        publish()
    }

    override suspend fun setSystemCalendarDisclosureLevel(level: Int) {
        systemCalDisclosure.value = level
        publish()
    }

    override suspend fun setSystemCalendarEventMapJson(json: String) {
        systemCalMap.value = json
        publish()
    }

    override suspend fun captureLocalClearSettings(): LocalClearSettingsSnapshot {
        return LocalClearSettingsSnapshot(
            currentBabyId = babyId.value,
            systemCalendarProjections = parseSystemCalendarEventMap(systemCalMap.value),
            nursingTimer = com.lezi.babylog.core.model.NursingTimerClearEpoch.captureFromJson(
                timer.value,
            ),
        )
    }

    override suspend fun finishLocalClearSettings(
        snapshot: LocalClearSettingsSnapshot,
        clearCurrentBabyId: Boolean,
    ) {
        if (clearCurrentBabyId && babyId.value == snapshot.currentBabyId) {
            babyId.value = null
        }
        val retained = parseSystemCalendarEventMap(systemCalMap.value)
            .filter { (clientUuid, eventId) ->
                snapshot.systemCalendarProjections[clientUuid] != eventId
            }
        systemCalMap.value = encodeSystemCalendarEventMap(retained)
        if (
            com.lezi.babylog.core.model.shouldCasRemoveNursingTimerJson(
                snapshot.nursingTimer,
                timer.value,
            )
        ) {
            timer.value = null
        }
        publish()
    }

    override suspend fun setComparePrevWeek(enabled: Boolean) {
        comparePrev.value = enabled
    }
}

internal class FakeLocalUserDao : LocalUserDao {
    private var user: LocalUserEntity? = null
    private val seq = AtomicLong(1)

    override suspend fun get(): LocalUserEntity? = user

    override suspend fun upsert(user: LocalUserEntity): Long {
        val id = if (user.id == 0L) seq.getAndIncrement() else user.id
        this.user = user.copy(id = id)
        return id
    }

    override suspend fun deleteAll() {
        user = null
    }
}

internal class FakeFamilyDao : FamilyDao {
    private val items = mutableListOf<FamilyEntity>()
    private val seq = AtomicLong(1)

    override suspend fun get(id: Long): FamilyEntity? = items.find { it.id == id }

    override suspend fun listAll(): List<FamilyEntity> = items.toList()

    override suspend fun insert(family: FamilyEntity): Long {
        val id = if (family.id == 0L) seq.getAndIncrement() else family.id
        items.removeAll { it.id == id }
        items += family.copy(id = id)
        return id
    }

    override suspend fun deleteAll() {
        items.clear()
    }
}

internal class FakeMembershipDao : MembershipDao {
    private val items = mutableListOf<MembershipEntity>()

    override suspend fun upsert(membership: MembershipEntity) {
        items.removeAll { it.familyId == membership.familyId && it.userId == membership.userId }
        items += membership
    }

    override suspend fun listForFamily(familyId: Long): List<MembershipEntity> =
        items.filter { it.familyId == familyId }

    override suspend fun deleteAll() {
        items.clear()
    }
}

internal class FakeBabyDao : BabyDao {
    private val items = MutableStateFlow<List<BabyEntity>>(emptyList())
    private val seq = AtomicLong(1)

    private fun active(): List<BabyEntity> = items.value.filter { it.deletedAt == null }

    override fun observeAll(): Flow<List<BabyEntity>> = items.map { list -> list.filter { it.deletedAt == null } }

    override suspend fun listAll(): List<BabyEntity> = active()

    override suspend fun listFamilyAuthority(): List<BabyEntity> =
        active().filter(BabyEntity::familyAuthority)

    override suspend fun get(id: Long): BabyEntity? = active().find { it.id == id }

    override suspend fun getIncludingDeleted(id: Long): BabyEntity? =
        items.value.find { it.id == id }

    override suspend fun getByClientUuid(uuid: String): BabyEntity? =
        active().find { it.clientUuid == uuid }

    override suspend fun listAllIncludingDeleted(): List<BabyEntity> = items.value

    override suspend fun listPendingSync(): List<BabyEntity> =
        items.value.filter(BabyEntity::syncDirty).sortedBy(BabyEntity::id)

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        items.update { values ->
            values.map {
                if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                    it.copy(syncDirty = false)
                } else {
                    it
                }
            }
        }
    }

    override suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int {
        val before = items.value.size
        items.value = items.value.filterNot {
            it.clientUuid == clientUuid && it.updatedAt == updatedAt && it.deletedAt != null
        }
        return before - items.value.size
    }

    override suspend fun markAllPendingSync() {
        items.update { values -> values.map { it.copy(syncDirty = true) } }
    }

    override suspend fun clearFamilyAuthority() {
        items.update { values -> values.map { it.copy(familyAuthority = false) } }
    }

    override suspend fun countByNickname(nickname: String, excludeId: Long): Int =
        active().count {
            it.nickname.trim() == nickname.trim() && (excludeId < 0 || it.id != excludeId)
        }

    override suspend fun countActive(): Int = active().size

    override suspend fun upsert(baby: BabyEntity): Long {
        val id = if (baby.id == 0L) seq.getAndIncrement() else baby.id
        val next = baby.copy(id = id)
        items.update { cur -> cur.filterNot { it.id == id } + next }
        return id
    }

    override suspend fun update(baby: BabyEntity) {
        items.update { cur -> cur.map { if (it.id == baby.id) baby else it } }
    }

    override suspend fun updateLocalTheme(id: Long, themeColorArgb: Int) {
        items.update { cur ->
            cur.map { if (it.id == id) it.copy(themeColorArgb = themeColorArgb) else it }
        }
    }

    override suspend fun updateLocalSortOrder(id: Long, sortOrder: Int) {
        items.update { cur ->
            cur.map { if (it.id == id) it.copy(sortOrder = sortOrder) else it }
        }
    }

    override suspend fun updateAvatarReplica(
        clientUuid: String,
        avatarMediaUuid: String?,
        avatarPath: String?,
    ) {
        items.update { current ->
            current.map {
                if (it.clientUuid == clientUuid) {
                    it.copy(
                        avatarMediaUuid = avatarMediaUuid,
                        avatarPath = avatarPath,
                    )
                } else {
                    it
                }
            }
        }
    }

    override suspend fun updateAvatarMediaForLocalSnapshot(
        id: Long,
        expectedUpdatedAt: Long,
        expectedAvatarPath: String?,
        avatarMediaUuid: String?,
    ): Int {
        var changed = 0
        items.update { current ->
            current.map {
                if (
                    it.id == id &&
                    it.updatedAt == expectedUpdatedAt &&
                    it.avatarPath == expectedAvatarPath
                ) {
                    changed = 1
                    it.copy(avatarMediaUuid = avatarMediaUuid, syncDirty = true)
                } else {
                    it
                }
            }
        }
        return changed
    }

    override suspend fun updateAvatarPathForReplica(
        id: Long,
        expectedAvatarMediaUuid: String?,
        avatarPath: String?,
    ): Int {
        var changed = 0
        items.update { current ->
            current.map {
                if (it.id == id && it.avatarMediaUuid == expectedAvatarMediaUuid) {
                    changed = 1
                    it.copy(avatarPath = avatarPath)
                } else {
                    it
                }
            }
        }
        return changed
    }

    override suspend fun deleteAll() {
        items.value = emptyList()
    }
}

internal class FakeRecordDao(
    private val conflictExcluded: () -> Set<String> = { emptySet() },
    private val hasActiveLegalWake: (sleepClientUuid: String, sleepStart: Long) -> Boolean =
        { _, _ -> false },
) : RecordDao {
    private val items = MutableStateFlow<List<RecordEntity>>(emptyList())
    private val seq = AtomicLong(1)
    private var txSnapshot: List<RecordEntity>? = null
    private var txSeq: Long? = null
    var onMutation: (() -> Unit)? = null

    private fun RecordEntity.isSurfaceRecord(): Boolean =
        clientUuid !in conflictExcluded()

    private fun RecordEntity.isTrulyOpenSleep(): Boolean =
        type == "sleep" &&
            deletedAt == null &&
            endTimestamp == null &&
            effectiveWakeObservationClientUuid == null &&
            !hasActiveLegalWake(clientUuid, timestamp)

    /** Force open-sleep / range observers to recompute after wake mutations. */
    fun touch() {
        items.value = items.value.toList()
    }

    fun beginTx() {
        txSnapshot = items.value
        txSeq = seq.get()
    }

    fun commitTx() {
        txSnapshot = null
        txSeq = null
    }

    fun rollbackTx() {
        txSnapshot?.let { items.value = it }
        txSeq?.let { seq.set(it) }
        txSnapshot = null
        txSeq = null
    }

    override fun observeRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<RecordEntity>> =
        items.map { list ->
            list.filter {
                it.babyId == babyId &&
                    it.deletedAt == null &&
                    it.isSurfaceRecord() &&
                    it.overlapsRange(startInclusive, endExclusive)
            }.sortedByDescending { it.timestamp }
        }

    override fun observeDay(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<RecordEntity>> =
        items.map { list ->
            list.filter {
                it.babyId == babyId &&
                    it.deletedAt == null &&
                    it.isSurfaceRecord() &&
                    it.overlapsRange(startInclusive, endExclusive)
            }.sortedByDescending { it.timestamp }
        }

    override suspend fun listDay(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<RecordEntity> =
        items.value.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.isSurfaceRecord() &&
                it.overlapsRange(startInclusive, endExclusive)
        }.sortedByDescending { it.timestamp }

    override suspend fun get(id: Long): RecordEntity? =
        items.value.find { it.id == id && it.deletedAt == null }

    override suspend fun getIncludingDeleted(id: Long): RecordEntity? =
        items.value.find { it.id == id }

    override suspend fun getByClientUuid(uuid: String): RecordEntity? =
        items.value.find { it.clientUuid == uuid }

    override suspend fun listAllIncludingDeleted(): List<RecordEntity> = items.value

    override suspend fun listPendingSync(): List<RecordEntity> =
        items.value.filter(RecordEntity::syncDirty).sortedBy(RecordEntity::id)

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        items.update { values ->
            values.map {
                if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                    it.copy(syncDirty = false)
                } else {
                    it
                }
            }
        }
    }

    override suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int {
        val before = items.value.size
        items.value = items.value.filterNot {
            it.clientUuid == clientUuid && it.updatedAt == updatedAt && it.deletedAt != null
        }
        return before - items.value.size
    }

    override suspend fun mergeCanonicalAuthor(
        clientUuid: String,
        expectedUpdatedAt: Long,
        membershipId: String,
    ): Int {
        var changed = 0
        items.update { values ->
            values.map {
                if (
                    it.clientUuid == clientUuid &&
                    it.updatedAt == expectedUpdatedAt &&
                    membershipId.isNotBlank()
                ) {
                    changed = 1
                    it.copy(createdByMembershipId = membershipId)
                } else {
                    it
                }
            }
        }
        return changed
    }

    override suspend fun markAllPendingSync() {
        items.update { values -> values.map { it.copy(syncDirty = true) } }
    }

    override suspend fun findOpenSleep(babyId: Long): RecordEntity? =
        listOpenSleeps(babyId).firstOrNull()

    override suspend fun listOpenSleeps(babyId: Long): List<RecordEntity> =
        items.value
            .filter { it.babyId == babyId && it.isTrulyOpenSleep() }
            .sortedWith(
                compareByDescending<RecordEntity> { it.timestamp }
                    .thenByDescending { it.clientUuid },
            )

    override fun observeOpenSleep(babyId: Long): Flow<RecordEntity?> =
        items.map { records ->
            records
                .filter { it.babyId == babyId && it.isTrulyOpenSleep() }
                .maxWithOrNull(
                    compareBy<RecordEntity> { it.timestamp }.thenBy { it.clientUuid },
                )
        }

    override suspend fun listForBaby(babyId: Long): List<RecordEntity> =
        items.value.filter {
            it.babyId == babyId && it.deletedAt == null && it.isSurfaceRecord()
        }.sortedByDescending { it.timestamp }

    override suspend fun searchCandidates(
        babyId: Long,
        escapedPattern: String,
        matchingTypeKeys: List<String>,
    ): List<RecordEntity> {
        // Mirror Room `LIKE :escapedPattern ESCAPE '\'` (see SqlLikeEscaped +
        // RecordSearchTest) so tests exercise user metacharacter handling rather
        // than a weaker contains() approximation.
        return items.value.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.isSurfaceRecord() &&
                (
                    it.note?.lowercase()?.let { note ->
                        matchesSqlLike(note, escapedPattern)
                    } == true ||
                        matchesSqlLike(it.payloadJson.lowercase(), escapedPattern) ||
                        it.type in matchingTypeKeys
                )
        }.sortedByDescending { it.timestamp }
    }

    override suspend fun listRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<RecordEntity> =
        items.value.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.isSurfaceRecord() &&
                it.overlapsRange(startInclusive, endExclusive)
        }.sortedBy { it.timestamp }

    override suspend fun listByType(babyId: Long, type: String): List<RecordEntity> =
        items.value.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.isSurfaceRecord() &&
                it.type == type
        }.sortedBy { it.timestamp }

    override suspend fun upsert(record: RecordEntity): Long {
        val id = if (record.id == 0L) seq.getAndIncrement() else record.id
        val next = record.copy(id = id)
        items.update { cur -> cur.filterNot { it.id == id } + next }
        onMutation?.invoke()
        return id
    }

    override suspend fun update(record: RecordEntity) {
        items.update { cur -> cur.map { if (it.id == record.id) record else it } }
        onMutation?.invoke()
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        items.update { cur ->
            cur.map {
                if (it.id == id) {
                    it.copy(deletedAt = deletedAt, updatedAt = deletedAt, syncDirty = true)
                } else {
                    it
                }
            }
        }
        onMutation?.invoke()
    }

    override suspend fun deleteAll() {
        items.value = emptyList()
        onMutation?.invoke()
    }

    private fun RecordEntity.overlapsRange(
        startInclusive: Long,
        endExclusive: Long,
    ): Boolean {
        val sleepEnd = endTimestamp
        return timestamp < endExclusive &&
            (
                timestamp >= startInclusive ||
                    (
                        type == RecordType.SLEEP.key &&
                            (sleepEnd == null || sleepEnd > startInclusive)
                        )
                )
    }
}
