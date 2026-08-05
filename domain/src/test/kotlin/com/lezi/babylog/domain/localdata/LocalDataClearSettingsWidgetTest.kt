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
class LocalDataClearSettingsWidgetTest {
    @Test
    fun settingsFailureRetainsDurableClearForProcessRecreationRecovery() = runTest {
        val rig = ClearCoordinatorRig(familyServerRetained = true)
        rig.settings.failure = IllegalStateException("settings unavailable")

        val failure = runCatching {
            rig.coordinator.clear(LocalDataClearScope.AllLocalData)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalRecordsClearCommittedException::class.java)
        assertThat(rig.pending.pending?.scope)
            .isEqualTo(LocalDataClearScope.AllLocalData)
        assertThat(rig.reminders.cancelledCarePlanIds).containsExactly(21L, 22L).inOrder()

        rig.settings.failure = null
        rig.newCoordinator().recoverPendingReminderCleanup()

        assertThat(rig.pending.pending).isNull()
        assertThat(rig.settings.scopes)
            .containsExactly(
                LocalDataClearScope.AllLocalData,
                LocalDataClearScope.AllLocalData,
            ).inOrder()
    }
    @Test
    fun widgetCleanupFailureRetainsDurableClearAndRecoveryRetriesIt() = runTest {
        val rig = ClearCoordinatorRig(familyServerRetained = true)
        rig.widgets.failuresRemaining = 1

        val failure = runCatching {
            rig.coordinator.clear(LocalDataClearScope.RecordsOnly)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalRecordsClearCommittedException::class.java)
        assertThat(rig.widgets.calls).isEqualTo(1)
        assertThat(rig.pending.pending).isNotNull()

        rig.newCoordinator().recoverPendingReminderCleanup()

        assertThat(rig.widgets.calls).isEqualTo(2)
        assertThat(rig.pending.pending).isNull()
    }
}
