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
class CareLogLocalDataTest {
    @Test
    fun localFamilyIdentityUsesReadOnlyDefaultsBeforeBootstrap() = runTest {
        val care = Fakes().careLog()

        assertThat(care.localFamilyIdentity()).isEqualTo(
            LocalFamilyIdentity(
                deviceId = "—",
                displayName = "我（本机）",
                familyId = 1L,
            ),
        )
    }
    @Test
    fun updateLocalDisplayNameCachesMembershipNameAndRejectsPlaceholder() = runTest {
        val care = Fakes().careLog()
        care.ensureFamilyScaffold()
        assertThat(care.localFamilyIdentity().displayName).isEqualTo("我（本机）")

        care.updateLocalDisplayName("  妈妈  ")
        assertThat(care.localFamilyIdentity().displayName).isEqualTo("妈妈")

        care.updateLocalDisplayName("我（本机）")
        assertThat(care.localFamilyIdentity().displayName).isEqualTo("我（本机）")

        care.updateLocalDisplayName("爸爸")
        care.updateLocalDisplayName("   ")
        assertThat(care.localFamilyIdentity().displayName).isEqualTo("我（本机）")
    }

    @Test
    fun updateLocalDisplayNameBumpsIdentityWhenTheStoredNameChanges() = runTest {
        val care = Fakes().careLog()
        care.ensureFamilyScaffold()
        val before = com.lezi.babylog.domain.family.LocalFamilyIdentityInvalidations.epoch.value

        care.updateLocalDisplayName("  妈妈  ")
        assertThat(com.lezi.babylog.domain.family.LocalFamilyIdentityInvalidations.epoch.value)
            .isEqualTo(before + 1)

        care.updateLocalDisplayName("妈妈")
        assertThat(com.lezi.babylog.domain.family.LocalFamilyIdentityInvalidations.epoch.value)
            .isEqualTo(before + 1)
    }
    @Test
    fun clearRecordsOnlyHardDeletesLocallyWithoutRequestingFamilySync() = runTest {
        val sync = RecordingSyncPort()
        val fakes = Fakes(sync)
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        care.addRecord(babyId, RecordType.PEE, timestamp = 1_000)
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        care.fulfillCarePlan(carePlanId = planId, actualTimestamp = now, nowMillis = now + 1)
        val requestsBeforeClear = sync.requests

        fakes.localDataClearCoordinator(sync).clear(LocalDataClearScope.RecordsOnly)

        assertThat(fakes.records.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.carePlans.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.fulfillmentCandidates.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.babies.listAll()).hasSize(1)
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
        assertThat(sync.requests).isEqualTo(requestsBeforeClear)
        assertThat(sync.localRecordReconciliations).isEqualTo(1)
    }
    @Test
    fun syncVersionAlwaysAdvancesAcrossClockRollbackAndSameMillisecondWrites() {
        assertThat(nextSyncUpdatedAt(previous = 2_000, candidate = 900)).isEqualTo(2_001)
        assertThat(nextSyncUpdatedAt(previous = 2_000, candidate = 2_000)).isEqualTo(2_001)
        assertThat(nextSyncUpdatedAt(previous = 2_000, candidate = 2_500)).isEqualTo(2_500)
    }
}
