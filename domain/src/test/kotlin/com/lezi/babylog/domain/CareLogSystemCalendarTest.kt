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
import com.lezi.babylog.domain.carelog.FakeMediaAssetDao
import com.lezi.babylog.domain.carelog.matchesSqlLike
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

// Split from CareLogTest kitchen sink by contract cluster (ticket 06).
class CareLogSystemCalendarTest {
    @Test
    fun recordsClearMarkerCapturesOnlyMappedOrProviderTouchedPlanUuids() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        suspend fun newPlan(): CarePlanEntity {
            val id = care.createCarePlan(
                babyId = babyId,
                type = RecordType.BATH,
                scheduledAt = now + 60_000L + fakes.carePlans.listAllIncludingDeleted().size,
                nowMillis = now,
            )
            return checkNotNull(fakes.carePlans.get(id))
        }
        val mapped = newPlan()
        val withEventId = newPlan().let { plan ->
            plan.copy(systemCalendarEventId = "evt-known").also {
                fakes.carePlans.update(it)
            }
        }
        val uidLookupOnly = newPlan().let { plan ->
            plan.copy(systemCalendarProjectionPending = true).also {
                fakes.carePlans.update(it)
            }
        }
        val untouched = newPlan()
        fakes.settings.setSystemCalendarEventMapJson(
            "{\"${mapped.clientUuid}\":\"evt-mapped\"}",
        )

        val failure = runCatching {
            fakes.localDataClearCoordinator().clear(LocalDataClearScope.RecordsOnly)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalRecordsClearCommittedException::class.java)
        assertThat(fakes.pendingReminderCleanup.pending?.systemCalendarProjections)
            .containsExactly(
                mapped.clientUuid,
                "evt-mapped",
                withEventId.clientUuid,
                "evt-known",
                uidLookupOnly.clientUuid,
                null,
            )
        assertThat(fakes.pendingReminderCleanup.pending?.systemCalendarProjections)
            .doesNotContainKey(untouched.clientUuid)
    }
    @Test
    fun systemCalendarProjectionCancelsLeziReminderOnSuccess() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.FORMULA,
            scheduledAt = now + 60_000L,
            payloadJson = """{"amount_ml":90}""",
            nowMillis = now,
        )
        assertThat(fakes.systemCalendar.upserts).isNotEmpty()
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isFalse()
    }
    @Test
    fun systemCalendarFailureFallsBackToLeziReminderWithoutRollingBackPlan() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.systemCalendar.failUpsert = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 60_000L,
            payloadJson = """{"pee_amount":1}""",
            nowMillis = now,
        )
        assertThat(care.getCarePlan(planId)).isNotNull()
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isTrue()
        assertThat(
            com.lezi.babylog.core.common.failure.failureExplanation(
                com.lezi.babylog.core.common.failure.FailureKind.SystemCalendarWriteFailed,
            ).dialogTitle,
        ).isEqualTo("本机数据问题：系统日历没写上")
    }
    @Test
    fun slowSystemCalendarNeverDelaysCommittedPlanOrFamilySyncRequest() = runTest {
        val sync = RecordingSyncPort()
        val fakes = Fakes(sync)
        val providerStarted = CompletableDeferred<Unit>()
        val allowProvider = CompletableDeferred<Unit>()
        fakes.systemCalendar.permission = true
        fakes.systemCalendar.beforeUpsert = {
            providerStarted.complete(Unit)
            allowProvider.await()
        }
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val requestsBeforePlan = sync.requests
        val now = System.currentTimeMillis()

        val create = async {
            care.createCarePlan(
                babyId = babyId,
                type = RecordType.BATH,
                scheduledAt = now + 60_000L,
                nowMillis = now,
            )
        }
        providerStarted.await()

        assertThat(fakes.carePlans.listAllIncludingDeleted()).hasSize(1)
        assertThat(sync.requests).isGreaterThan(requestsBeforePlan)

        allowProvider.complete(Unit)
        assertThat(create.await()).isEqualTo(fakes.carePlans.listAllIncludingDeleted().single().id)
    }
    @Test
    fun systemCalendarWriteTimeoutKeepsCommittedPlanAndUnblocksSave() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.systemCalendar.beforeUpsert = { kotlinx.coroutines.awaitCancellation() }
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog(systemCalendarWriteMaxElapsedMillis = 150L)
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()

        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )

        assertThat(care.getCarePlan(planId)).isNotNull()
        assertThat(fakes.carePlans.get(planId)?.deletedAt).isNull()
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isTrue()
    }
    @Test
    fun cancellingSlowProjectionPublishesNoLaterReminderState() = runTest {
        val fakes = Fakes()
        val providerStarted = CompletableDeferred<Unit>()
        fakes.systemCalendar.permission = true
        fakes.systemCalendar.beforeUpsert = {
            providerStarted.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val create = launch {
            care.createCarePlan(
                babyId = babyId,
                type = RecordType.BATH,
                scheduledAt = now + 60_000L,
                nowMillis = now,
            )
        }
        providerStarted.await()

        create.cancelAndJoin()

        val committed = fakes.carePlans.listAllIncludingDeleted().single()
        assertThat(committed.systemCalendarProjectionPending).isTrue()
        assertThat(fakes.systemCalendar.upserts).hasSize(1)
        assertThat(fakes.reminders.carePlanOperations).isEmpty()
    }
    @Test
    fun providerEventWithoutReadyReminderKeepsLeziFallbackAndUnsyncedStatus() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.systemCalendar.failReminder = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()

        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )

        val entity = fakes.carePlans.get(planId)!!
        assertThat(entity.systemCalendarEventId).isNotNull()
        assertThat(entity.systemCalendarReminderReady).isFalse()
        assertThat(entity.systemCalendarProjectionPending).isFalse()
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isTrue()
    }
    @Test
    fun failedProviderUpdateCannotReusePriorReminderGeneration() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        assertThat(fakes.carePlans.get(planId)!!.systemCalendarReminderReady).isTrue()

        fakes.systemCalendar.failUpsert = true
        fakes.reminders.scheduledCarePlanIds.clear()
        care.updateCarePlan(
            carePlanId = planId,
            scheduledAt = now + 180_000L,
            nowMillis = now + 1L,
        )

        assertThat(fakes.carePlans.get(planId)!!.systemCalendarReminderReady).isFalse()
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isTrue()
    }
    @Test
    fun indeterminateStaleProviderOwnerNeverEnablesSecondLeziReminder() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )

        fakes.systemCalendar.providerStillOwnsStaleReminder = true
        fakes.reminders.scheduledCarePlanIds.clear()
        fakes.reminders.cancelledCarePlanIds.clear()
        care.updateCarePlan(
            carePlanId = planId,
            scheduledAt = now + 180_000L,
            nowMillis = now + 1L,
        )

        val entity = fakes.carePlans.get(planId)!!
        assertThat(entity.systemCalendarReminderReady).isFalse()
        assertThat(entity.systemCalendarProjectionPending).isTrue()
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isTrue()
    }
    @Test
    fun configuredCalendarWithoutPermissionSchedulesLeziForPendingHandoff() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = false
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()

        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )

        assertThat(fakes.carePlans.get(planId)!!.systemCalendarProjectionPending).isFalse()
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
        assertThat(fakes.reminders.cancelledCarePlanIds).doesNotContain(planId)
    }
    @Test
    fun perPlanCalendarRouteOnlyEditDoesNotAdvanceFamilyRevision() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 120_000L,
            nowMillis = now,
        )
        val before = fakes.carePlans.get(planId)!!
        fakes.carePlans.markSynced(before.clientUuid, before.updatedAt)

        care.updateCarePlan(
            carePlanId = planId,
            scheduledAt = before.scheduledAt,
            note = before.note,
            payloadJson = before.payloadJson,
            schemaVersion = before.schemaVersion,
            projectToSystemCalendar = false,
            nowMillis = now + 1,
        )

        val after = fakes.carePlans.get(planId)!!
        assertThat(after.updatedAt).isEqualTo(before.updatedAt)
        assertThat(after.syncDirty).isFalse()
        assertThat(after.systemCalendarProjectionEnabled).isFalse()
    }
    @Test
    fun systemCalendarUnconfiguredUsesLeziReminderOnly() = runTest {
        val fakes = Fakes()
        // Enabled false / no calendar id — never project, never request permission.
        fakes.systemCalendar.permission = false
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        assertThat(fakes.systemCalendar.upserts).isEmpty()
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isFalse()
    }
    @Test
    fun babyDeletionCleanupRetiresAlarmAndSystemCalendarOwnership() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        care.createBaby(CreateBabyInput(nickname = "保留", birthdayEpochDay = 1))
        val deletedBabyId = care.addBaby(
            CreateBabyInput(nickname = "待删除", birthdayEpochDay = 2),
        )
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = deletedBabyId,
            type = RecordType.BATH,
            scheduledAt = now + 120_000L,
            nowMillis = now,
        )
        val plan = requireNotNull(fakes.carePlans.get(planId))
        val eventId = requireNotNull(plan.systemCalendarEventId)
        fakes.reminders.scheduledCarePlanIds += planId
        fakes.reminders.cancelledCarePlanIds.clear()
        fakes.systemCalendar.deleted.clear()
        assertThat(care.deleteBaby(deletedBabyId)).isTrue()

        fakes.reminderProjection().onBabyDeleted(deletedBabyId)

        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
        assertThat(fakes.systemCalendar.deleted).contains(eventId)
        val cleaned = requireNotNull(fakes.carePlans.get(planId))
        assertThat(cleaned.systemCalendarEventId).isNull()
        assertThat(cleaned.systemCalendarReminderReady).isFalse()
        assertThat(cleaned.systemCalendarProjectionPending).isFalse()
        assertThat(
            parseSystemCalendarEventMap(
                fakes.settings.settings.first().systemCalendarEventMapJson,
            ),
        ).doesNotContainKey(plan.clientUuid)
    }
    @Test
    fun systemCalendarPermissionRevokeMarksUnsyncedWithoutRollingBackPlan() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.FORMULA,
            scheduledAt = now + 90_000L,
            payloadJson = """{"amount_ml":90}""",
            nowMillis = now,
        )
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isFalse()
        fakes.systemCalendar.permission = false
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isTrue()
        assertThat(care.getCarePlan(planId)!!.status).isEqualTo(CarePlanStatus.PENDING)
        assertThat(SYSTEM_CALENDAR_UNSYNCED_LABEL).isEqualTo("未同步到系统日历")
    }
    @Test
    fun systemCalendarVanishedTargetOrEventMarksUnsynced() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 90_000L,
            payloadJson = """{"pee_amount":1}""",
            nowMillis = now,
        )
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isFalse()
        // Target calendar disappears from provider.
        fakes.systemCalendar.writableCalendarIds = emptySet()
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isTrue()
        // Restore target but drop the mapped event.
        fakes.systemCalendar.writableCalendarIds = setOf("cal-1")
        val plan = care.getCarePlan(planId)!!
        val map = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        )
        val eventId = map[plan.clientUuid]
        assertThat(eventId).isNotNull()
        fakes.systemCalendar.missingEventIds += eventId!!
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isTrue()
    }
    @Test
    fun systemCalendarDisclosureLevelsProjectTitleDescriptionAndDeepLink() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        fakes.settings.setSystemCalendarDisclosureLevel(1)
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.FORMULA,
            scheduledAt = now + 60_000L,
            note = "补充维D",
            payloadJson = """{"amount_ml":90}""",
            photoLocalPaths = listOf(
                "file:///data/data/com.lezi.babylog/cache/photo1.jpg",
                "content://media/external/images/media/99",
            ),
            nowMillis = now,
        )
        val l1 = fakes.systemCalendar.upserts.last()
        assertThat(l1.title).isEqualTo("乐记 · 护理计划")
        assertThat(l1.description).isNull()
        assertThat(l1.customAppUri).isNull()
        assertThat(l1.title).doesNotContain("content://")
        assertThat(l1.title).doesNotContain("file://")

        fakes.settings.setSystemCalendarDisclosureLevel(2)
        care.projectOrScheduleCarePlanReminder(care.getCarePlan(planId)!!)
        val l2 = fakes.systemCalendar.upserts.last()
        assertThat(l2.title).isEqualTo("年年 · 配方奶")
        assertThat(l2.description).isNull()
        assertThat(l2.customAppUri).isNull()

        fakes.settings.setSystemCalendarDisclosureLevel(3)
        care.projectOrScheduleCarePlanReminder(care.getCarePlan(planId)!!)
        val l3 = fakes.systemCalendar.upserts.last()
        val plan = care.getCarePlan(planId)!!
        assertThat(l3.title).isEqualTo("年年 · 配方奶")
        assertThat(l3.description).contains("补充维D")
        assertThat(l3.description).contains("照片 2 张，打开乐记查看")
        assertThat(l3.description).contains("lezi://care-plan/${plan.clientUuid}")
        assertThat(l3.customAppUri).isEqualTo("lezi://care-plan/${plan.clientUuid}")
        // Photo local paths / content URIs must never leak into calendar text.
        assertThat(l3.description).doesNotContain("content://")
        assertThat(l3.description).doesNotContain("file://")
        assertThat(l3.description).doesNotContain("/data/data/")
        assertThat(l3.description).doesNotContain("photo1.jpg")
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
    }
    @Test
    fun systemCalendarDisclosureChangeReprojectsOnlyOpenFuturePlans() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        fakes.settings.setSystemCalendarDisclosureLevel(2)
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val futureId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 120_000L,
            nowMillis = now,
        )
        val pastId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 30_000L,
            payloadJson = """{"pee_amount":1}""",
            nowMillis = now,
        )
        // Move past plan into missed (scheduled before "later" now).
        care.skipCarePlan(pastId, nowMillis = now + 1)
        // Re-create a completed terminal path: fulfill is heavier; soft-delete also terminals.
        care.deleteCarePlan(pastId, nowMillis = now + 2)

        fakes.systemCalendar.upserts.clear()
        fakes.settings.setSystemCalendarDisclosureLevel(1)
        care.reprojectOpenFutureSystemCalendarCopies(nowMillis = now + 10)
        // Only the still-open future plan is reprojected.
        assertThat(fakes.systemCalendar.upserts).hasSize(1)
        assertThat(fakes.systemCalendar.upserts.single().title).isEqualTo("乐记 · 护理计划")
        val futurePlan = care.getCarePlan(futureId)!!
        assertThat(fakes.systemCalendar.upserts.single().carePlanClientUuid)
            .isEqualTo(futurePlan.clientUuid)
    }
    @Test
    fun systemCalendarExternalDeleteRebuildsEventWithoutDuplicateLeziReminder() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.FORMULA,
            scheduledAt = now + 90_000L,
            payloadJson = """{"amount_ml":120}""",
            nowMillis = now,
        )
        val plan = care.getCarePlan(planId)!!
        val firstMap = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        )
        val oldEventId = firstMap[plan.clientUuid]!!
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)

        // User deletes the event outside Lezi.
        fakes.systemCalendar.missingEventIds += oldEventId
        fakes.systemCalendar.upserts.clear()
        fakes.reminders.scheduledCarePlanIds.clear()
        fakes.reminders.cancelledCarePlanIds.clear()

        val ok = care.projectOrScheduleCarePlanReminder(plan)
        assertThat(ok).isTrue()
        // The adapter strictly detects absence and rebuilds within one upsert command.
        assertThat(fakes.systemCalendar.upserts).hasSize(1)
        assertThat(fakes.systemCalendar.upserts.single().existingEventId).isEqualTo(oldEventId)
        val newMap = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        )
        val newEventId = newMap[plan.clientUuid]
        assertThat(newEventId).isNotNull()
        assertThat(newEventId).isNotEqualTo(oldEventId)
        // Single reminder owner remains system calendar — no Lezi schedule.
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
    }
    @Test
    fun systemCalendarRemovedOnCompleteSkipAndDelete() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()

        val skipId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        val skipPlan = care.getCarePlan(skipId)!!
        val skipEvent = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        )[skipPlan.clientUuid]
        assertThat(skipEvent).isNotNull()
        care.skipCarePlan(skipId, nowMillis = now + 1)
        assertThat(fakes.systemCalendar.deleted).contains(skipEvent)
        assertThat(
            parseSystemCalendarEventMap(
                fakes.settings.settings.first().systemCalendarEventMapJson,
            ),
        ).doesNotContainKey(skipPlan.clientUuid)

        val delId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 90_000L,
            payloadJson = """{"pee_amount":1}""",
            nowMillis = now,
        )
        val delPlan = care.getCarePlan(delId)!!
        val delEvent = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        )[delPlan.clientUuid]
        care.deleteCarePlan(delId, nowMillis = now + 2)
        assertThat(fakes.systemCalendar.deleted).contains(delEvent)
        assertThat(
            parseSystemCalendarEventMap(
                fakes.settings.settings.first().systemCalendarEventMapJson,
            ),
        ).doesNotContainKey(delPlan.clientUuid)

        val fulfillId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.FORMULA,
            scheduledAt = now + 120_000L,
            payloadJson = """{"amount_ml":90}""",
            nowMillis = now,
        )
        val fulfillPlan = care.getCarePlan(fulfillId)!!
        val fulfillEvent = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        )[fulfillPlan.clientUuid]
        care.fulfillCarePlan(
            carePlanId = fulfillId,
            actualTimestamp = now,
            payloadJson = """{"amount_ml":90}""",
            nowMillis = now,
        )
        assertThat(fakes.systemCalendar.deleted).contains(fulfillEvent)
        assertThat(
            parseSystemCalendarEventMap(
                fakes.settings.settings.first().systemCalendarEventMapJson,
            ),
        ).doesNotContainKey(fulfillPlan.clientUuid)
    }
    @Test
    fun terminalPlanRetainsProjectionIdentityUntilProviderDeletionIsConfirmed() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        val plan = care.getCarePlan(planId)!!
        val eventId = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        ).getValue(plan.clientUuid)

        fakes.systemCalendar.permission = false
        care.skipCarePlan(planId, nowMillis = now + 1)

        assertThat(
            parseSystemCalendarEventMap(
                fakes.settings.settings.first().systemCalendarEventMapJson,
            ),
        ).containsEntry(plan.clientUuid, eventId)

        fakes.systemCalendar.permission = true
        care.rescheduleCarePlanReminders(nowMillis = now + 2)

        assertThat(fakes.systemCalendar.deleted).contains(eventId)
        assertThat(
            parseSystemCalendarEventMap(
                fakes.settings.settings.first().systemCalendarEventMapJson,
            ),
        ).doesNotContainKey(plan.clientUuid)
    }
    @Test
    fun processRestartRemovesOldFutureProjectionAfterPlanMovesToMissed() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 120_000L,
            nowMillis = now,
        )
        val beforeCrash = fakes.carePlans.get(planId)!!
        val eventId = beforeCrash.systemCalendarEventId!!

        // Simulate process death after the Room edit committed but before provider I/O.
        fakes.carePlans.update(
            beforeCrash.copy(
                scheduledAt = now - 1L,
                systemCalendarReminderReady = false,
            ),
        )
        fakes.systemCalendar.deleted.clear()
        fakes.reminders.scheduledCarePlanIds.clear()

        care.rescheduleCarePlanReminders(nowMillis = now)

        assertThat(fakes.systemCalendar.deleted).contains(eventId)
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
    }
}
