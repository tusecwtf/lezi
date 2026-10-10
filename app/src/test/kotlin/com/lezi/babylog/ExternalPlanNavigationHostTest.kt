package com.lezi.babylog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.calendar.SystemCalendarConfigurationCoordinator
import com.lezi.babylog.feature.log.composer.RecordComposerRequest
import com.lezi.babylog.feature.onboarding.OnboardingBabyStepHold
import com.lezi.babylog.feature.widget.CareWidgetRefreshController
import com.lezi.babylog.sync.NoOpSyncPort
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** Exercises the retained root used by LeziMainScaffold, not a copied navigation reducer. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalPlanNavigationHostTest {
    @Test
    fun confirmedLatePlanCannotReplaceNewDraftAcrossHostResubscription() = hostTest { host ->
        val result = host.holdPlan(PLAN_A)
        host.confirmExternal(PLAN_A)
        runCurrent()
        host.root.openComposer(DRAFT)
        runCurrent()

        // Configuration replacement re-subscribes to the same retained owner. This is
        // deliberately not represented as an Activity recreation or photo ownership test.
        host.subscription.cancel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.root.ui.collect { } }
        result().resumeWith(Result.success(plan(8, PLAN_A)))
        runCurrent()

        assertThat(host.root.ui.value?.composerRequest).isEqualTo(DRAFT)
    }

    @Test
    fun closeInvalidatesPendingPlanEvenWhenThereIsNoOpenComposer() = hostTest { host ->
        val result = host.holdPlan(PLAN_A)
        host.confirmExternal(PLAN_A)
        runCurrent()
        host.root.closeComposer()
        result().resumeWith(Result.success(plan(8, PLAN_A)))
        runCurrent()
        assertThat(host.root.ui.value?.composerRequest).isNull()
    }

    @Test
    fun newestExternalRequestWinsWhenOlderLookupReturnsFirst() = hostTest { host ->
        val older = host.holdPlan(PLAN_A)
        val newer = host.holdPlan(PLAN_B)
        host.confirmExternal(PLAN_A)
        runCurrent()
        host.confirmExternal(PLAN_B)
        runCurrent()
        older().resumeWith(Result.success(plan(8, PLAN_A)))
        runCurrent()
        assertThat(host.root.ui.value?.composerRequest).isNull()

        newer().resumeWith(Result.success(plan(9, PLAN_B)))
        runCurrent()
        assertThat(host.root.ui.value?.composerRequest).isEqualTo(RecordComposerRequest.Fulfill(9))
    }

    @Test
    fun repeatedConfirmationCannotReopenAPlanAfterTheDeliveredComposerCloses() = hostTest { host ->
        val pending = mutableListOf<Continuation<CarePlan?>>()
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            pending += invocation.rawArguments.last() as Continuation<CarePlan?>
            COROUTINE_SUSPENDED
        }.`when`(host.careLog).getCarePlanByClientUuid(PLAN_A)
        host.confirmExternal(PLAN_A)
        host.confirmExternal(PLAN_A)
        runCurrent()
        assertThat(pending).hasSize(2)
        pending[1].resumeWith(Result.success(plan(8, PLAN_A)))
        runCurrent()
        assertThat(host.root.ui.value?.composerRequest).isEqualTo(RecordComposerRequest.Fulfill(8))
        host.root.closeComposer()
        pending[0].resumeWith(Result.success(plan(8, PLAN_A)))
        runCurrent()
        assertThat(host.root.ui.value?.composerRequest).isNull()
    }

    @Test
    fun confirmationWithAnExistingDraftDoesNotEvenStartTheLookup() = hostTest { host ->
        var lookups = 0
        doAnswer { lookups++; plan(8, PLAN_A) }.`when`(host.careLog).getCarePlanByClientUuid(PLAN_A)
        host.root.openComposer(DRAFT)
        host.confirmExternal(PLAN_A)
        runCurrent()
        assertThat(lookups).isEqualTo(0)
        assertThat(host.root.ui.value?.composerRequest).isEqualTo(DRAFT)
    }

    private fun hostTest(block: suspend TestScope.(Host) -> Unit) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val store = ViewModelStore()
                try {
                    val host = Host()
                    store.put("root", host.root)
                    host.subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        host.root.ui.collect { }
                    }
                    runCurrent()
                    block(host)
                } finally {
                    store.clear()
                }
            }
        } finally {
            // runTest must drain ViewModel and collector cancellation before Main is removed.
            Dispatchers.resetMain()
        }
    }

    private class Host {
        lateinit var subscription: Job
        val careLog = mock(CareLog::class.java).also {
            `when`(it.observeHasBaby()).thenReturn(flowOf(false))
            `when`(it.observeCurrentBaby()).thenReturn(flowOf(null))
            `when`(it.observeBabies()).thenReturn(flowOf(emptyList()))
        }
        private val settings = mock(SettingsStore::class.java).also {
            `when`(it.settings).thenReturn(flowOf(SettingsLocal()))
        }
        val handle = SavedStateHandle()
        val root = RootViewModel(
            careLog, NoOpSyncPort(), settings,
            mock(SystemCalendarConfigurationCoordinator::class.java), handle,
            dagger.Lazy { mock(CareWidgetRefreshController::class.java) }, OnboardingBabyStepHold(),
        )

        suspend fun holdPlan(uuid: String): () -> Continuation<CarePlan?> {
            var continuation: Continuation<CarePlan?>? = null
            doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                continuation = invocation.rawArguments.last() as Continuation<CarePlan?>
                COROUTINE_SUSPENDED
            }.`when`(careLog).getCarePlanByClientUuid(uuid)
            return { checkNotNull(continuation) { "The actual root never started plan resolution" } }
        }

        fun confirmExternal(uuid: String) {
            val target = requireNotNull(decodeUntrustedFulfill(FulfillIntentSnapshot(
                action = "android.intent.action.VIEW", scheme = "lezi", host = "care-plan",
                pathSegments = listOf(uuid), planIdExtra = null, clientUuidExtra = null,
            )))
            val authorized = authorizeExternalNavigation(UntrustedExternalNavigation.Fulfill(target), true)
                as AuthorizedExternalNavigation.Fulfill
            root.openExternalCarePlan(authorized.target.planId, authorized.target.clientUuid)
        }
    }

    private companion object {
        const val PLAN_A = "00000000-0000-4000-8000-000000000088"
        const val PLAN_B = "00000000-0000-4000-8000-000000000089"
        val DRAFT = RecordComposerRequest.New(71, RecordType.PEE, 55, historical = false)

        fun plan(id: Long, uuid: String) = CarePlan(
            id = id, clientUuid = uuid, babyId = 71, type = RecordType.FORMULA,
            scheduledAt = 1, scheduledZoneId = "UTC", updatedAt = 1,
        )
    }
}
