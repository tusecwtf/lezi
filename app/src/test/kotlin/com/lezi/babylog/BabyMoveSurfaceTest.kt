package com.lezi.babylog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FamilyEntity
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.LocalUserDao
import com.lezi.babylog.core.database.LocalUserEntity
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaLocalPathGate
import com.lezi.babylog.core.database.MembershipDao
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordWakeProjectionDao
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.core.database.causal.SourceRelationDao
import com.lezi.babylog.core.database.causal.WakeObservationDao
import com.lezi.babylog.core.database.fulfillment.FulfillmentAuthoritySettlement
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.family.BabyLocalLayoutCommands
import com.lezi.babylog.domain.careplan.ReminderCleanupPort
import com.lezi.babylog.domain.localdata.CalendarReminderMutationGuard
import com.lezi.babylog.domain.localdata.LocalDataMutationEpoch
import com.lezi.babylog.feature.family.baby.BabyAvatarFileStore
import com.lezi.babylog.feature.family.overview.AccountOverviewHost
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.session.PolicyClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
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
    fun forcedRootStatePreventsNewBusinessNavigationAndKeepsExistingDraft() =
        runTest(mainDispatcherRule.testDispatcher) {
            val forced = kotlinx.coroutines.flow.MutableStateFlow<com.lezi.babylog.sync.ForcedAppUpdateState?>(null)
            val sync = object : com.lezi.babylog.sync.SyncPort by NoOpSyncPort() {
                override fun availableForcedAppUpdate() = forced
            }
            val careLog = careLogWithOneVisibleBaby(71L, empty = true)
            val settings = mock(SettingsStore::class.java)
            `when`(settings.settings).thenReturn(flowOf(com.lezi.babylog.core.model.SettingsLocal()))
            val root = RootViewModel(
                careLog, sync, settings,
                mock(com.lezi.babylog.domain.calendar.SystemCalendarConfigurationCoordinator::class.java),
                androidx.lifecycle.SavedStateHandle(),
                dagger.Lazy { mock(com.lezi.babylog.feature.widget.CareWidgetRefreshController::class.java) },
                com.lezi.babylog.feature.onboarding.OnboardingBabyStepHold(),
            )
            backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
                root.ui.collect { }
            }
            val draft = com.lezi.babylog.feature.log.composer.RecordComposerRequest.New(
                babyId = 71L, type = com.lezi.babylog.core.model.RecordType.FORMULA,
                timestamp = 55L, historical = false,
            )
            root.openComposer(draft)
            forced.value = com.lezi.babylog.sync.ForcedAppUpdateState.PackageUnknown
            runCurrent()
            root.openComposer(com.lezi.babylog.feature.log.composer.RecordComposerRequest.Fulfill(8L))
            root.openExternalCarePlan(9L, "")
            advanceUntilIdle()
            assertThat(root.forcedAppUpdate.value).isEqualTo(com.lezi.babylog.sync.ForcedAppUpdateState.PackageUnknown)
            assertThat(root.ui.value?.composerRequest).isEqualTo(draft)
        }

    @Test
    fun lateExternalPlanResolutionCannotReplaceNewComposer() =
        runTest(mainDispatcherRule.testDispatcher) {
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val dao = object : CarePlanDao by mock(CarePlanDao::class.java) {
                override suspend fun getByClientUuid(clientUuid: String): com.lezi.babylog.core.database.CarePlanEntity? {
                    release.await()
                    return com.lezi.babylog.core.database.CarePlanEntity(
                        id = 8L, clientUuid = clientUuid, babyId = 71L, type = "formula",
                        scheduledAt = 1L, scheduledZoneId = "UTC", updatedAt = 1L,
                    )
                }
            }
            val careLog = careLogWithOneVisibleBaby(71L, planDao = dao, empty = true)
            val settings = mock(SettingsStore::class.java)
            `when`(settings.settings).thenReturn(flowOf(com.lezi.babylog.core.model.SettingsLocal()))
            val root = RootViewModel(
                careLog, NoOpSyncPort(), settings,
                mock(com.lezi.babylog.domain.calendar.SystemCalendarConfigurationCoordinator::class.java),
                androidx.lifecycle.SavedStateHandle(),
                dagger.Lazy { mock(com.lezi.babylog.feature.widget.CareWidgetRefreshController::class.java) },
                com.lezi.babylog.feature.onboarding.OnboardingBabyStepHold(),
            )
            backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
                root.ui.collect { }
            }
            root.openExternalCarePlan(null, "old-plan")
            runCurrent()
            val draft = com.lezi.babylog.feature.log.composer.RecordComposerRequest.New(
                babyId = 71L, type = com.lezi.babylog.core.model.RecordType.FORMULA,
                timestamp = 55L, historical = false,
            )
            root.openComposer(draft)
            release.complete(Unit)
            advanceUntilIdle()
            assertThat(root.ui.value?.composerRequest).isEqualTo(draft)
        }

    @Test
    fun createdBabyKeepsCommittedIdentityWhenAvatarReadbackFails() =
        runTest(mainDispatcherRule.testDispatcher) {
            val insertedBabies = mutableListOf<BabyEntity>()
            val careLog = careLogWithOneVisibleBaby(
                babyId = 71L,
                createdBabyId = 72L,
                onBabyInserted = insertedBabies::add,
            )
            val host = AccountOverviewHost(
                NoOpSyncPort(), careLog, BabyLocalLayoutCommands(careLog),
                mock(BabyAvatarFileStore::class.java),
            )
            host.addBaby("第二个宝宝", null, 1, null, 0, byteArrayOf(1))
            advanceUntilIdle()
            val receipt = host.babyCreation.value!!
            assertThat(receipt.createdBabyId).isEqualTo(72L)
            assertThat(receipt.pending).isFalse()
            assertThat(insertedBabies).hasSize(1)
            assertThat(insertedBabies.single().familyId).isEqualTo(1L)
            assertThat(insertedBabies.single().clientUuid).isEqualTo(receipt.clientUuid)
            assertThat(receipt.warning).isEqualTo("宝宝已添加，头像尚未保存，请在宝宝档案中重试")
            assertThat(receipt.error).isNull()
            // A recreated create form cannot replay a completed command before acknowledgement.
            host.addBaby("重复宝宝", null, 1, null, 0, null)
            advanceUntilIdle()
            assertThat(host.babyCreation.value).isEqualTo(receipt)
            assertThat(insertedBabies).hasSize(1)
        }

    @Test
    fun accountOverviewUsesLiveOrderBeforeItsUiSnapshotHydrates() =
        runTest(mainDispatcherRule.testDispatcher) {
            val careLog = careLogWithOneVisibleBaby(babyId = 71L)
            val host = AccountOverviewHost(
                sync = NoOpSyncPort(),
                careLog = careLog,
                babyLocalLayout = BabyLocalLayoutCommands(careLog),
                avatarFileStore = mock(BabyAvatarFileStore::class.java),
            )
            var feedback: String? = null

            host.moveBabyLocal(71L, -1) { feedback = it }
            advanceUntilIdle()

            assertThat(feedback).isEqualTo("宝宝已在该位置")
        }
}

private fun careLogWithOneVisibleBaby(
    babyId: Long, createdBabyId: Long = 72L,
    planDao: CarePlanDao = mock(CarePlanDao::class.java), empty: Boolean = false,
    onBabyInserted: (BabyEntity) -> Unit = {},
): CareLog {
    val babyDao = object : BabyDao by mock(BabyDao::class.java) {
        override suspend fun getByClientUuid(uuid: String): BabyEntity? = null
        override suspend fun countByNickname(nickname: String, excludeId: Long): Int = 0
        override suspend fun upsert(baby: BabyEntity): Long {
            onBabyInserted(baby.copy(id = createdBabyId))
            return createdBabyId
        }
    }
    val baby = BabyEntity(
        id = babyId,
        familyId = 1,
        nickname = "宝宝",
        birthdayEpochDay = 1,
        themeColorArgb = 0,
        clientUuid = "baby-$babyId",
        updatedAt = 1,
    )
    `when`(babyDao.observeAll()).thenReturn(flowOf(if (empty) emptyList() else listOf(baby)))
    runBlocking {
        // Deliberately omit the newly inserted identity from avatar readback.
        `when`(babyDao.listAll()).thenReturn(listOf(baby))
    }
    val localUserDao = object : LocalUserDao by mock(LocalUserDao::class.java) {
        override suspend fun get() = LocalUserEntity(id = 1L, deviceId = "fixture-device", createdAt = 1L)
    }
    val familyDao = object : FamilyDao by mock(FamilyDao::class.java) {
        override suspend fun listAll() = listOf(FamilyEntity(id = 1L, ownerUserId = 1L, createdAt = 1L))
    }
    val settings = object : SettingsStore by mock(SettingsStore::class.java) {
        override suspend fun setCurrentBabyId(id: Long?) = Unit
    }
    `when`(settings.currentBabyId).thenReturn(flowOf(babyId))
    val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T = block()
    }
    val carePlanDao = planDao
    val fulfillmentCandidateDao = mock(FulfillmentCandidateDao::class.java)
    val customItemDao = mock(CustomItemDao::class.java)
    `when`(customItemDao.observeAll()).thenReturn(flowOf(emptyList()))

    return CareLog(
        babyDao = babyDao,
        recordDao = mock(RecordDao::class.java),
        carePlanDao = carePlanDao,
        customItemDao = customItemDao,
        localUserDao = localUserDao,
        familyDao = familyDao,
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
        conflictSnapshotCacheDao = mock(ConflictSnapshotCacheDao::class.java),
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
