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
class LocalDataClearTimerTest {
    @Test
    fun activeTimerStopAndClearSurvivesProcessRecreation() = runTest {
        val rig = ClearCoordinatorRig(familyServerRetained = true)
        rig.timer.failuresRemaining = 1

        val failure = runCatching {
            rig.coordinator.clear(LocalDataClearScope.RecordsOnly)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalRecordsClearCommittedException::class.java)
        assertThat(rig.pending.pending?.nursingTimerSessionToken).isEqualTo("session-old")
        assertThat(rig.pending.pending?.nursingTimerJson).isEqualTo(TIMER_JSON_OLD)
        assertThat(rig.timer.stoppedSessionTokens).containsExactly("session-old")
        // Settings CAS still runs after a stop failure; marker stays until stop also succeeds.
        assertThat(rig.settings.finishedTimerSessions).containsExactly("session-old")
        assertThat(rig.settings.nursingTimerJson).isNull()

        rig.timer.failuresRemaining = 0
        rig.newCoordinator().recoverPendingReminderCleanup()

        assertThat(rig.pending.pending).isNull()
        assertThat(rig.timer.stoppedSessionTokens)
            .containsExactly("session-old", "session-old")
            .inOrder()
        assertThat(rig.settings.finishedTimerSessions).containsExactly("session-old")
    }
    @Test
    fun recoveryDoesNotStopOrClearNewerTimerSession() = runTest {
        val rig = ClearCoordinatorRig()
        rig.timer.failuresRemaining = 1

        val failure = runCatching {
            rig.coordinator.clear(LocalDataClearScope.AllLocalData)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(LocalRecordsClearCommittedException::class.java)

        // Post-commit newer epoch: settings and runtime now hold session-new.
        rig.settings.replaceNursingTimer(TIMER_JSON_NEW, "session-new")
        rig.timer.activeSessionToken = "session-new"
        rig.timer.failuresRemaining = 0
        // Adapter no-ops when active != captured; simulate by not recording stop for mismatch.
        rig.timer.skipTokens += "session-old"

        rig.newCoordinator().recoverPendingReminderCleanup()

        assertThat(rig.pending.pending).isNull()
        assertThat(rig.settings.nursingTimerSessionToken).isEqualTo("session-new")
        assertThat(rig.settings.nursingTimerJson).isEqualTo(TIMER_JSON_NEW)
        // stop was attempted for old token but skipped (ABA); never stops session-new
        assertThat(rig.timer.stoppedSessionTokens).doesNotContain("session-new")
    }
    @Test
    fun pausedAndFailedTimerSnapshotsAreStillClearedAfterCommit() = runTest {
        listOf(TIMER_JSON_PAUSED, TIMER_JSON_FAILED).forEach { timerJson ->
            val rig = ClearCoordinatorRig()
            rig.settings.replaceNursingTimer(timerJson, "session-old")

            rig.coordinator.clear(LocalDataClearScope.RecordsOnly)

            assertThat(rig.timer.stoppedSessionTokens).containsExactly("session-old")
            assertThat(rig.settings.nursingTimerJson).isNull()
            assertThat(rig.pending.pending).isNull()
        }
    }
}
