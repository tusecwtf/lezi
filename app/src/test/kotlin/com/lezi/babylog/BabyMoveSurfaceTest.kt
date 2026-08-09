package com.lezi.babylog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.LocalUserDao
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaLocalPathGate
import com.lezi.babylog.core.database.MembershipDao
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordWakeProjectionDao
import com.lezi.babylog.core.database.causal.ConflictDetailCacheDao
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.core.database.causal.SourceRelationDao
import com.lezi.babylog.core.database.causal.WakeObservationDao
import com.lezi.babylog.core.database.fulfillment.FulfillmentAuthoritySettlement
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.calendar.SystemCalendarConfigurationCoordinator
import com.lezi.babylog.domain.calendar.SystemCalendarPort
import com.lezi.babylog.domain.careplan.ReminderCleanupPort
import com.lezi.babylog.domain.localdata.CalendarReminderMutationGuard
import com.lezi.babylog.domain.localdata.LocalDataClearCoordinator
import com.lezi.babylog.domain.localdata.LocalDataMutationEpoch
import com.lezi.babylog.feature.family.baby.BabyAvatarFileStore
import com.lezi.babylog.feature.family.overview.AccountOverviewHost
import com.lezi.babylog.feature.settings.SettingsViewModel
import com.lezi.babylog.sync.ClientAppVersion
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.session.PolicyClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class BabyMoveSurfaceTest {
    @get:Rule
    val mainDispatcherRule = BabyMoveMainDispatcherRule()

    @Test
    fun accountOverviewUsesLiveOrderBeforeItsUiSnapshotHydrates() =
        runTest(mainDispatcherRule.testDispatcher) {
            val careLog = careLogWithOneVisibleBaby(babyId = 71L)
            val host = AccountOverviewHost(
                sync = NoOpSyncPort(),
                careLog = careLog,
                avatarFileStore = mock(BabyAvatarFileStore::class.java),
            )
            var feedback: String? = null

            host.moveBabyLocal(71L, -1) { feedback = it }
            advanceUntilIdle()

            assertThat(feedback).isEqualTo("宝宝已在该位置")
        }

    @Test
    fun settingsUsesLiveOrderBeforeItsUiSnapshotHydrates() =
        runTest(mainDispatcherRule.testDispatcher) {
            val localSettings = mock(SettingsStore::class.java)
            `when`(localSettings.settings).thenReturn(flowOf(SettingsLocal()))
            `when`(localSettings.showAvgSleep).thenReturn(flowOf(false))
            `when`(localSettings.comparePrevWeek).thenReturn(flowOf(false))
            val viewModel = SettingsViewModel(
                settingsStore = localSettings,
                careLog = careLogWithOneVisibleBaby(babyId = 82L),
                syncPort = NoOpSyncPort(),
                systemCalendarPort = mock(SystemCalendarPort::class.java),
                systemCalendarConfiguration =
                    mock(SystemCalendarConfigurationCoordinator::class.java),
                localDataClearCoordinator = mock(LocalDataClearCoordinator::class.java),
                clientAppVersion = ClientAppVersion.FALLBACK,
            )
            var feedback: String? = null

            viewModel.moveBabyLocal(82L, 1) { feedback = it }
            advanceUntilIdle()

            assertThat(feedback).isEqualTo("宝宝已在该位置")
        }
}

private fun careLogWithOneVisibleBaby(babyId: Long): CareLog {
    val babyDao = mock(BabyDao::class.java)
    val baby = BabyEntity(
        id = babyId,
        familyId = 1,
        nickname = "宝宝",
        birthdayEpochDay = 1,
        themeColorArgb = 0,
        clientUuid = "baby-$babyId",
        updatedAt = 1,
    )
    `when`(babyDao.observeAll()).thenReturn(flowOf(listOf(baby)))
    runBlocking {
        `when`(babyDao.listAll()).thenReturn(listOf(baby))
    }
    val settings = mock(SettingsStore::class.java)
    `when`(settings.currentBabyId).thenReturn(flowOf(babyId))
    val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T = block()
    }
    val carePlanDao = mock(CarePlanDao::class.java)
    val fulfillmentCandidateDao = mock(FulfillmentCandidateDao::class.java)
    val customItemDao = mock(CustomItemDao::class.java)
    `when`(customItemDao.observeAll()).thenReturn(flowOf(emptyList()))

    return CareLog(
        babyDao = babyDao,
        recordDao = mock(RecordDao::class.java),
        carePlanDao = carePlanDao,
        customItemDao = customItemDao,
        localUserDao = mock(LocalUserDao::class.java),
        familyDao = mock(FamilyDao::class.java),
        membershipDao = mock(MembershipDao::class.java),
        mediaAssetDao = mock(MediaAssetDao::class.java),
        settings = settings,
        syncPort = NoOpSyncPort(),
        reminderCleanup = mock(ReminderCleanupPort::class.java),
        transactionRunner = transactionRunner,
        fulfillmentCandidateDao = fulfillmentCandidateDao,
        fulfillmentAuthoritySettlement = FulfillmentAuthoritySettlement(
            carePlanDao = carePlanDao,
            fulfillmentCandidateDao = fulfillmentCandidateDao,
            transactionRunner = transactionRunner,
        ),
        calendarReminderMutationGuard = CalendarReminderMutationGuard(),
        clock = mock(PolicyClock::class.java),
        mediaPathGate = MediaLocalPathGate(),
        localDataMutationEpoch = LocalDataMutationEpoch(),
        wakeObservationDao = mock(WakeObservationDao::class.java),
        conflictSummaryDao = mock(ConflictSummaryDao::class.java),
        conflictDetailCacheDao = mock(ConflictDetailCacheDao::class.java),
        sourceRelationDao = mock(SourceRelationDao::class.java),
        recordWakeProjectionDao = mock(RecordWakeProjectionDao::class.java),
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class BabyMoveMainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
