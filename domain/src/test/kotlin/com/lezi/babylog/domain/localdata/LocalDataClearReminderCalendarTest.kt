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
class LocalDataClearReminderCalendarTest {
    @Test
    fun failureBeforeDomainCommitLeavesSettingsAndRemindersUntouched() = runTest {
        val rig = ClearCoordinatorRig()
        val failure = IllegalStateException("room transaction rejected")
        rig.persistence.failure = failure

        val actual = runCatching {
            rig.coordinator.clear(LocalDataClearScope.RecordsOnly)
        }.exceptionOrNull()

        assertThat(actual).isSameInstanceAs(failure)
        assertThat(rig.settings.scopes).isEmpty()
        assertThat(rig.reminders.cancelledCarePlanIds).isEmpty()
        assertThat(rig.timer.stoppedSessionTokens).isEmpty()
        assertThat(rig.pending.pending).isNull()
    }
    @Test
    fun reminderFailureAfterCommitIsClassifiedAndRecoveryFinishesPendingWork() = runTest {
        val rig = ClearCoordinatorRig(familyServerRetained = true)
        rig.reminders.carePlanFailuresRemaining = 1

        val failure = runCatching {
            rig.coordinator.clear(LocalDataClearScope.RecordsOnly)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalRecordsClearCommittedException::class.java)
        assertThat((failure as LocalRecordsClearCommittedException).familyServerRetained).isTrue()
        assertThat(rig.pending.pending?.carePlanIds).containsExactly(21L, 22L)
        assertThat(rig.pending.deleteCount).isEqualTo(0)

        rig.coordinator.recoverPendingReminderCleanup()

        assertThat(rig.pending.pending).isNull()
        assertThat(rig.pending.deleteCount).isEqualTo(1)
        assertThat(rig.reminders.cancelledCarePlanIds)
            .containsExactly(21L, 22L, 21L, 22L)
            .inOrder()
    }
    @Test
    fun committedReplicaFailureStillClearsSettingsAndFinishesReminders() = runTest {
        val rig = ClearCoordinatorRig(familyServerRetained = true)
        val replicaFailure = IllegalStateException("replica finalization failed")
        rig.sync.failureAfterClear = replicaFailure

        val actual = runCatching {
            rig.coordinator.clear(LocalDataClearScope.RecordsOnly)
        }.exceptionOrNull()

        // Single committed-clear type (no domain re-wrap of sync's exception).
        assertThat(actual).isInstanceOf(LocalRecordsClearCommittedException::class.java)
        assertThat(actual!!.cause).isSameInstanceAs(replicaFailure)
        assertThat(rig.settings.scopes).containsExactly(LocalDataClearScope.RecordsOnly)
        assertThat(rig.reminders.cancelledCarePlanIds).containsExactly(21L, 22L).inOrder()
        assertThat(rig.pending.pending).isNull()
    }
    @Test
    fun carePlanReminderFailureSurvivesCoordinatorRecreationAndRecovery() = runTest {
        val rig = ClearCoordinatorRig(familyServerRetained = true)
        rig.reminders.carePlanFailuresRemaining = 1

        val failure = runCatching {
            rig.coordinator.clear(LocalDataClearScope.RecordsOnly)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalRecordsClearCommittedException::class.java)
        assertThat(rig.reminders.cancelledCarePlanIds).containsExactly(21L, 22L).inOrder()

        rig.newCoordinator().recoverPendingReminderCleanup()

        assertThat(rig.reminders.cancelledCarePlanIds)
            .containsExactly(21L, 22L, 21L, 22L)
            .inOrder()
        assertThat(rig.pending.pending).isNull()
    }
    @Test
    fun systemCalendarDeleteFailureRetainsMarkerAndRecreationFinishesBothScopes() = runTest {
        listOf(
            LocalDataClearScope.RecordsOnly,
            LocalDataClearScope.AllLocalData,
        ).forEach { scope ->
            val rig = ClearCoordinatorRig()
            rig.systemCalendar.deleteFailuresRemaining = 1

            val failure = runCatching { rig.coordinator.clear(scope) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(LocalRecordsClearCommittedException::class.java)
            assertThat(rig.pending.pending?.systemCalendarProjections)
                .containsExactly("plan-21", "evt-21", "plan-22", "evt-22")
            assertThat(rig.settings.scopes).containsExactly(scope)
            assertThat(rig.reminders.cancelledCarePlanIds).containsExactly(21L, 22L).inOrder()

            rig.newCoordinator().recoverPendingReminderCleanup()

            assertThat(rig.systemCalendar.deleted)
                .containsExactly("evt-21", "evt-22", "evt-21", "evt-22")
                .inOrder()
            assertThat(rig.settings.scopes).containsExactly(scope, scope).inOrder()
            assertThat(rig.pending.pending).isNull()
        }
    }
    @Test
    fun revokedCalendarPermissionRetainsMarkerUntilPermissionIsRestored() = runTest {
        val rig = ClearCoordinatorRig()
        rig.systemCalendar.permission = false

        val failure = runCatching {
            rig.coordinator.clear(LocalDataClearScope.RecordsOnly)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalRecordsClearCommittedException::class.java)
        assertThat(rig.pending.pending?.systemCalendarProjections)
            .containsExactly("plan-21", "evt-21", "plan-22", "evt-22")
        assertThat(rig.settings.scopes).containsExactly(LocalDataClearScope.RecordsOnly)
        assertThat(rig.reminders.cancelledCarePlanIds).containsExactly(21L, 22L).inOrder()

        rig.systemCalendar.permission = true
        rig.newCoordinator().recoverPendingReminderCleanup()

        assertThat(rig.pending.pending).isNull()
        assertThat(rig.systemCalendar.deleted)
            .containsExactly("evt-21", "evt-22", "evt-21", "evt-22")
            .inOrder()
    }
    @Test
    fun providerLookupUnavailableDoesNotMasqueradeAsConfirmedAbsence() = runTest {
        val rig = ClearCoordinatorRig()
        rig.systemCalendar.deleteFalseRemaining = 1
        rig.systemCalendar.lookupOverride = SystemCalendarEventState.UNAVAILABLE

        val failure = runCatching {
            rig.coordinator.clear(LocalDataClearScope.RecordsOnly)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalRecordsClearCommittedException::class.java)
        assertThat(rig.pending.pending?.systemCalendarProjections)
            .containsExactly("plan-21", "evt-21", "plan-22", "evt-22")
        assertThat(rig.settings.scopes).containsExactly(LocalDataClearScope.RecordsOnly)
        assertThat(rig.reminders.cancelledCarePlanIds).containsExactly(21L, 22L).inOrder()
    }
    @Test
    fun retainedEmptySnapshotDoesNotConsumeProjectionCreatedAfterFailure() = runTest {
        val rig = ClearCoordinatorRig()
        rig.settings.removeAllSystemCalendarEvents()
        rig.systemCalendar.liveEventIds.clear()
        rig.reminders.carePlanFailuresRemaining = 1

        val failure = runCatching {
            rig.coordinator.clear(LocalDataClearScope.RecordsOnly)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(LocalRecordsClearCommittedException::class.java)
        assertThat(rig.pending.pending?.systemCalendarProjections).isEmpty()

        rig.settings.addSystemCalendarEvent("evt-after-failure")
        rig.systemCalendar.addLiveEvent("evt-after-failure")
        rig.newCoordinator().recoverPendingReminderCleanup()

        assertThat(rig.systemCalendar.deleted).doesNotContain("evt-after-failure")
        assertThat(rig.systemCalendar.liveEventIds).containsExactly("evt-after-failure")
        assertThat(rig.settings.systemCalendarEventIds()).containsExactly("evt-after-failure")
        assertThat(rig.pending.pending).isNull()
    }
    @Test
    fun retainedNonEmptySnapshotRemovesOnlyTheCapturedProjectionEpoch() = runTest {
        val rig = ClearCoordinatorRig()
        rig.reminders.carePlanFailuresRemaining = 1

        val failure = runCatching {
            rig.coordinator.clear(LocalDataClearScope.RecordsOnly)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(LocalRecordsClearCommittedException::class.java)

        rig.settings.addSystemCalendarEvent("evt-after-failure")
        rig.systemCalendar.addLiveEvent("evt-after-failure")
        rig.newCoordinator().recoverPendingReminderCleanup()

        assertThat(rig.systemCalendar.deleted)
            .containsExactly("evt-21", "evt-22", "evt-21", "evt-22")
            .inOrder()
        assertThat(rig.systemCalendar.liveEventIds).containsExactly("evt-after-failure")
        assertThat(rig.settings.systemCalendarEventIds()).containsExactly("evt-after-failure")
        assertThat(rig.pending.pending).isNull()
    }
    @Test
    fun syncBarrierContainsSnapshotAndFinishWithoutErasingLaterProjection() = runTest {
        val rig = ClearCoordinatorRig()
        rig.sync.beforeLocalClear = {
            rig.settings.addSystemCalendarEvent("evt-before")
            rig.systemCalendar.addLiveEvent("evt-before")
        }
        rig.sync.afterLocalClear = {
            rig.settings.addSystemCalendarEvent("evt-after")
            rig.systemCalendar.addLiveEvent("evt-after")
        }

        rig.coordinator.clear(LocalDataClearScope.RecordsOnly)

        assertThat(rig.systemCalendar.deleted)
            .containsExactly("evt-21", "evt-22", "evt-before")
            .inOrder()
        assertThat(rig.settings.systemCalendarEventIds()).containsExactly("evt-after")
        assertThat(rig.systemCalendar.liveEventIds).containsExactly("evt-after")
    }
}
