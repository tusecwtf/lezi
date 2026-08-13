package com.lezi.babylog.domain.localdata
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.PendingReminderCleanup
import com.lezi.babylog.core.database.PendingReminderCleanupStore
import com.lezi.babylog.core.datastore.LocalClearSettingsSnapshot
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.NursingTimerClearEpoch
import com.lezi.babylog.core.model.shouldCasRemoveNursingTimerJson
import com.lezi.babylog.core.model.shouldStopCapturedNursingTimerSession
import com.lezi.babylog.sync.LocalClearWorkflow
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.clear.localClearCommittedFailure
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.lezi.babylog.domain.calendar.SystemCalendarEventState
import com.lezi.babylog.domain.calendar.SystemCalendarOwnedEventLookup
import com.lezi.babylog.domain.calendar.SystemCalendarPort
import com.lezi.babylog.domain.calendar.SystemCalendarTarget
import com.lezi.babylog.domain.calendar.SystemCalendarUpsert
import com.lezi.babylog.domain.calendar.SystemCalendarUpsertOutcome
import com.lezi.babylog.domain.calendar.SystemCalendarUpsertResult
import com.lezi.babylog.domain.careplan.ReminderCleanupPort

// Contract-cluster split (ticket 08).
class LocalDataClearOrchestrationTest {
    @Test
    fun recordsAndAllScopesShareOneSuccessfulOrchestration() = runTest {
        val rig = ClearCoordinatorRig()

        rig.coordinator.clear(LocalDataClearScope.RecordsOnly)
        // A later AllLocalData clear may capture a newer timer epoch that still
        // must be stopped; re-seed after RecordsOnly removed the previous JSON.
        rig.settings.replaceNursingTimer(TIMER_JSON_OLD, "session-old")
        rig.timer.activeSessionToken = "session-old"
        rig.coordinator.clear(LocalDataClearScope.AllLocalData)

        assertThat(rig.sync.recordsClearCount).isEqualTo(1)
        assertThat(rig.sync.allLocalDataClearCount).isEqualTo(1)
        assertThat(rig.persistence.scopes)
            .containsExactly(LocalDataClearScope.RecordsOnly, LocalDataClearScope.AllLocalData)
            .inOrder()
        assertThat(rig.settings.scopes)
            .containsExactly(LocalDataClearScope.RecordsOnly, LocalDataClearScope.AllLocalData)
            .inOrder()
        assertThat(rig.reminders.cancelledCarePlanIds)
            .containsExactly(21L, 22L, 21L, 22L)
            .inOrder()
        assertThat(rig.systemCalendar.deleted).containsExactly("evt-21", "evt-22").inOrder()
        assertThat(rig.timer.stoppedSessionTokens)
            .containsExactly("session-old", "session-old")
            .inOrder()
        assertThat(rig.widgets.calls).isEqualTo(2)
        assertThat(rig.pending.pending).isNull()
    }
    @Test
    fun retainedCleanupCannotDeleteReusedEventIdOwnedByANewerPlanUuid() = runTest {
        val rig = ClearCoordinatorRig()
        rig.pending.pending = PendingReminderCleanup(
            scope = LocalDataClearScope.RecordsOnly,
            systemCalendarProjections = mapOf("plan-old" to "evt-reused"),
            familyServerRetained = false,
        )
        rig.settings.replaceSystemCalendarProjections(
            mapOf("plan-new" to "evt-reused"),
        )
        rig.systemCalendar.replaceLiveProjections(
            mapOf("plan-new" to "evt-reused"),
        )

        rig.coordinator.recoverPendingReminderCleanup()

        assertThat(rig.systemCalendar.deletedProjectionPairs)
            .containsExactly("plan-old" to "evt-reused")
        assertThat(rig.systemCalendar.liveProjections())
            .containsExactly("plan-new", "evt-reused")
        assertThat(rig.settings.systemCalendarProjections())
            .containsExactly("plan-new", "evt-reused")
        assertThat(rig.pending.pending).isNull()
    }
    @Test
    fun nestedCancellationAfterCommitPropagatesTheOriginalCancellation() = runTest {
        val rig = ClearCoordinatorRig(familyServerRetained = true)
        val cancellation = CancellationException("caller stopped")
        rig.settings.failure = IllegalStateException("wrapped", cancellation)

        val actual = runCatching {
            rig.coordinator.clear(LocalDataClearScope.AllLocalData)
        }.exceptionOrNull()

        assertThat(actual).isSameInstanceAs(cancellation)
        assertThat(rig.pending.pending).isNotNull()
        assertThat(rig.reminders.cancelledCarePlanIds).containsExactly(21L, 22L).inOrder()
    }
    @Test
    fun failedPriorRecoveryFinishesItsStoredScopeWithoutRunningTheNewScope() = runTest {
        val rig = ClearCoordinatorRig()
        rig.pending.pending = PendingReminderCleanup(
            scope = LocalDataClearScope.AllLocalData,
            carePlanIds = setOf(41L),
            familyServerRetained = true,
        )
        rig.sync.failureBeforeClear = localClearCommittedFailure(
            familyServerRetained = true,
            cause = IllegalStateException("prior replica cleanup failed"),
        )

        val failure = runCatching {
            rig.coordinator.clear(LocalDataClearScope.RecordsOnly)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalRecordsClearCommittedException::class.java)
        assertThat(rig.persistence.scopes).isEmpty()
        assertThat(rig.settings.scopes).containsExactly(LocalDataClearScope.AllLocalData)
        assertThat(rig.reminders.cancelledCarePlanIds).containsExactly(41L)
        assertThat(rig.pending.pending).isNull()
    }
    @Test
    fun corruptPendingRecoveryFailsClosedWithoutCancellingOrDeleting() = runTest {
        val rig = ClearCoordinatorRig()
        val corruption = IllegalStateException("corrupt pending hand-off")
        rig.pending.loadFailure = corruption

        val actual = runCatching {
            rig.coordinator.recoverPendingReminderCleanup()
        }.exceptionOrNull()

        assertThat(actual).isInstanceOf(IllegalStateException::class.java)
        assertThat(actual).hasMessageThat().isEqualTo(corruption.message)
        assertThat(rig.pending.deleteCount).isEqualTo(0)
    }
    @Test
    fun callerCancellationWinsWhenNonCancellableRecoveryAlsoFails() = runTest {
        val rig = ClearCoordinatorRig(familyServerRetained = true)
        rig.pending.pending = PendingReminderCleanup(
            scope = LocalDataClearScope.RecordsOnly,
            systemCalendarProjections = mapOf("plan-21" to "evt-21"),
            familyServerRetained = true,
        )
        rig.systemCalendar.deleteStarted = CompletableDeferred()
        rig.systemCalendar.allowDelete = CompletableDeferred()
        val providerFailure = IllegalStateException("provider failed")
        rig.systemCalendar.deleteFailure = providerFailure
        val cancellation = CancellationException("caller stopped")
        var result: Result<LocalDataClearScope?>? = null

        val recovering = launch {
            result = runCatching { rig.coordinator.recoverPendingReminderCleanup() }
        }
        rig.systemCalendar.deleteStarted!!.await()
        recovering.cancel(cancellation)
        rig.systemCalendar.allowDelete!!.complete(Unit)
        recovering.join()

        val failure = result!!.exceptionOrNull()
        assertThat(failure).isSameInstanceAs(cancellation)
        assertThat(failure!!.suppressed.asList()).hasSize(1)
        assertThat(failure.suppressed.single())
            .isInstanceOf(LocalRecordsClearCommittedException::class.java)
        assertThat(failure.suppressed.single().cause).isSameInstanceAs(providerFailure)
        assertThat(rig.pending.pending).isNotNull()
    }
}
