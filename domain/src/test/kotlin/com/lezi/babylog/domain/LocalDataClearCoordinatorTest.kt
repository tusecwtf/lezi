package com.lezi.babylog.domain

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
import com.lezi.babylog.sync.SyncSession
import com.lezi.babylog.sync.localClearCommittedFailure
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocalDataClearCoordinatorTest {
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
        assertThat(rig.pending.pending).isNull()
    }

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

private const val TIMER_JSON_OLD =
    """{"schemaVersion":1,"completionClientUuid":"session-old","leftRunning":true,"serviceState":"RUNNING"}"""
private const val TIMER_JSON_NEW =
    """{"schemaVersion":1,"completionClientUuid":"session-new","leftRunning":true,"serviceState":"RUNNING"}"""
private const val TIMER_JSON_PAUSED =
    """{"schemaVersion":1,"completionClientUuid":"session-old","leftRunning":false,"serviceState":"PAUSED"}"""
private const val TIMER_JSON_FAILED =
    """{"schemaVersion":1,"completionClientUuid":"session-old","leftRunning":false,"serviceState":"FAILED","requestedSide":"L","serviceFailure":"RESTRICTED"}"""

private class ClearCoordinatorRig(
    familyServerRetained: Boolean = false,
) {
    val pending = RecordingPendingReminderCleanupStore()
    val persistence = RecordingLocalDataClearPersistence(pending)
    val settings = RecordingLocalDataClearSettings()
    val reminders = RecordingClearReminderPort()
    val timer = RecordingNursingTimerCleanupPort()
    val systemCalendar = RecordingSystemCalendarPort()
    val sync = RecordingClearSyncPort(familyServerRetained)
    val coordinator: LocalDataClearCoordinator = newCoordinator()

    fun newCoordinator(): LocalDataClearCoordinator =
        DefaultLocalDataClearCoordinator(
            persistence = persistence,
            settings = settings,
            syncPort = sync,
            reminderCleanup = reminders,
            nursingTimerCleanup = timer,
            systemCalendar = systemCalendar,
            pendingReminderCleanupStore = pending,
            mutationGuard = CalendarReminderMutationGuard(),
        )
}

private class RecordingLocalDataClearPersistence(
    private val pendingStore: PendingReminderCleanupStore,
) : LocalDataClearPersistence {
    val scopes = mutableListOf<LocalDataClearScope>()
    var failure: Throwable? = null

    override suspend fun clear(
        scope: LocalDataClearScope,
        familyServerRetained: Boolean,
        settingsSnapshot: LocalClearSettingsSnapshot,
    ) {
        failure?.let { throw it }
        scopes += scope
        pendingStore.upsert(
            PendingReminderCleanup(
                scope = scope,
                carePlanIds = setOf(21L, 22L),
                systemCalendarProjections = settingsSnapshot.systemCalendarProjections,
                currentBabyId = settingsSnapshot.currentBabyId,
                nursingTimer = settingsSnapshot.nursingTimer,
                familyServerRetained = familyServerRetained,
            ),
        )
    }

}

private class RecordingLocalDataClearSettings : LocalDataClearSettings {
    val scopes = mutableListOf<LocalDataClearScope>()
    val finishedTimerSessions = mutableListOf<String>()
    private val projections = linkedMapOf(
        "plan-21" to "evt-21",
        "plan-22" to "evt-22",
    )
    private var currentBabyId: Long? = 7L
    var nursingTimer: NursingTimerClearEpoch = NursingTimerClearEpoch(
        json = TIMER_JSON_OLD,
        sessionToken = "session-old",
    )
    var failure: Throwable? = null

    val nursingTimerJson: String? get() = nursingTimer.json
    val nursingTimerSessionToken: String? get() = nursingTimer.sessionToken

    fun addSystemCalendarEvent(eventId: String) {
        projections["plan-$eventId"] = eventId
    }

    fun removeAllSystemCalendarEvents() {
        projections.clear()
    }

    fun replaceSystemCalendarProjections(values: Map<String, String>) {
        projections.clear()
        projections.putAll(values)
    }

    fun replaceNursingTimer(json: String?, sessionToken: String?) {
        nursingTimer = NursingTimerClearEpoch(json = json, sessionToken = sessionToken)
    }

    suspend fun systemCalendarEventIds(): Set<String> = projections.values.toSet()

    suspend fun systemCalendarProjections(): Map<String, String> = projections.toMap()

    override suspend fun capture(): LocalClearSettingsSnapshot = LocalClearSettingsSnapshot(
        currentBabyId = currentBabyId,
        systemCalendarProjections = projections.toMap(),
        nursingTimer = nursingTimer,
    )

    override suspend fun finish(
        scope: LocalDataClearScope,
        snapshot: LocalClearSettingsSnapshot,
    ) {
        scopes += scope
        failure?.let { throw it }
        projections.entries.removeAll { (clientUuid, eventId) ->
            snapshot.systemCalendarProjections[clientUuid] == eventId
        }
        if (
            scope == LocalDataClearScope.AllLocalData &&
            currentBabyId == snapshot.currentBabyId
        ) {
            currentBabyId = null
        }
        // CAS-remove by session token (primary) or exact JSON (secondary).
        if (shouldCasRemoveNursingTimerJson(snapshot.nursingTimer, nursingTimer.json)) {
            finishedTimerSessions += snapshot.nursingTimerSessionToken.orEmpty()
            nursingTimer = NursingTimerClearEpoch.EMPTY
        }
    }
}

private class RecordingNursingTimerCleanupPort : NursingTimerCleanupPort {
    val stoppedSessionTokens = mutableListOf<String>()
    val skipTokens = mutableSetOf<String>()
    var activeSessionToken: String? = "session-old"
    var failuresRemaining = 0

    override fun stopCapturedSession(sessionToken: String?) {
        if (sessionToken.isNullOrBlank()) return
        if (sessionToken in skipTokens) return
        // Mirror production: only stop when active exactly matches captured.
        if (
            !shouldStopCapturedNursingTimerSession(
                activeSession = activeSessionToken,
                capturedSession = sessionToken,
            )
        ) {
            return
        }
        stoppedSessionTokens += sessionToken
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            throw IllegalStateException("nursing timer stop failed")
        }
        if (activeSessionToken == sessionToken) {
            activeSessionToken = null
        }
    }
}

private class RecordingPendingReminderCleanupStore : PendingReminderCleanupStore {
    var pending: PendingReminderCleanup? = null
    var loadFailure: Throwable? = null
    var deleteCount = 0

    override suspend fun load(
        scope: LocalDataClearScope,
    ): PendingReminderCleanup? {
        loadFailure?.let { throw it }
        return pending?.takeIf { it.scope == scope }
    }

    override suspend fun upsert(pending: PendingReminderCleanup) {
        val existing = this.pending?.takeIf { it.scope == pending.scope }
        val nursingTimer = when {
            existing != null && !existing.nursingTimer.isEmpty -> existing.nursingTimer
            else -> pending.nursingTimer
        }
        this.pending = pending.copy(
            carePlanIds = existing?.carePlanIds.orEmpty() + pending.carePlanIds,
            systemCalendarProjections =
                existing?.systemCalendarProjections.orEmpty() + pending.systemCalendarProjections,
            currentBabyId = pending.currentBabyId,
            nursingTimer = nursingTimer,
            familyServerRetained =
                existing?.familyServerRetained == true || pending.familyServerRetained,
        )
    }

    override suspend fun delete(scope: LocalDataClearScope) {
        deleteCount += 1
        if (pending?.scope == scope) pending = null
    }
}

private class RecordingSystemCalendarPort : SystemCalendarPort {
    val deleted = mutableListOf<String>()
    val deletedProjectionPairs = mutableListOf<Pair<String, String>>()
    val liveEventIds = linkedSetOf("evt-21", "evt-22")
    private val eventOwners = linkedMapOf(
        "evt-21" to "plan-21",
        "evt-22" to "plan-22",
    )
    var deleteFailuresRemaining = 0
    var deleteFalseRemaining = 0
    var permission = true
    var lookupOverride: SystemCalendarEventState? = null
    var deleteStarted: CompletableDeferred<Unit>? = null
    var allowDelete: CompletableDeferred<Unit>? = null
    var deleteFailure: Throwable? = null

    fun addLiveEvent(eventId: String) {
        liveEventIds += eventId
        eventOwners[eventId] = "plan-$eventId"
    }

    fun replaceLiveProjections(values: Map<String, String>) {
        liveEventIds.clear()
        eventOwners.clear()
        values.forEach { (clientUuid, eventId) ->
            liveEventIds += eventId
            eventOwners[eventId] = clientUuid
        }
    }

    fun liveProjections(): Map<String, String> = liveEventIds.associate { eventId ->
        checkNotNull(eventOwners[eventId]) to eventId
    }

    override fun hasCalendarPermission(): Boolean = permission
    override suspend fun listWritableCalendars(): List<SystemCalendarTarget> = emptyList()
    override suspend fun upsertEvent(
        request: SystemCalendarUpsert,
    ): SystemCalendarUpsertResult = SystemCalendarUpsertResult(
        eventId = null,
        outcome = SystemCalendarUpsertOutcome.ReleasedOrAbsent,
    )
    override suspend fun findOwnedEvent(
        carePlanClientUuid: String,
    ): SystemCalendarOwnedEventLookup {
        if (!permission) return SystemCalendarOwnedEventLookup.Unavailable
        val owned = liveEventIds.filterTo(linkedSetOf()) {
            eventOwners[it] == carePlanClientUuid
        }
        return if (owned.isEmpty()) {
            SystemCalendarOwnedEventLookup.Absent
        } else {
            SystemCalendarOwnedEventLookup.Found(owned)
        }
    }
    override suspend fun deleteEvent(
        eventId: String,
        carePlanClientUuid: String?,
    ): Boolean {
        deleted += eventId
        carePlanClientUuid?.let { deletedProjectionPairs += it to eventId }
        deleteStarted?.complete(Unit)
        allowDelete?.await()
        deleteFailure?.let { throw it }
        if (!permission) return false
        if (deleteFailuresRemaining > 0) {
            deleteFailuresRemaining -= 1
            throw IllegalStateException("system calendar delete failed")
        }
        if (deleteFalseRemaining > 0) {
            deleteFalseRemaining -= 1
            return false
        }
        if (
            carePlanClientUuid != null &&
            eventOwners[eventId] != carePlanClientUuid
        ) return false
        val removed = liveEventIds.remove(eventId)
        if (removed) eventOwners.remove(eventId)
        return removed
    }
    override suspend fun eventState(
        eventId: String,
        carePlanClientUuid: String?,
    ): SystemCalendarEventState =
        lookupOverride ?: if (
            eventId in liveEventIds &&
            (carePlanClientUuid == null || eventOwners[eventId] == carePlanClientUuid)
        ) {
            SystemCalendarEventState.PRESENT
        } else {
            SystemCalendarEventState.ABSENT
        }
    override suspend fun isWritableCalendar(calendarId: String): Boolean = false
}

private class RecordingClearSyncPort(
    private val familyServerRetained: Boolean,
) : SyncPort by NoOpSyncPort() {
    private val session = MutableStateFlow(
        SyncSession(familyId = if (familyServerRetained) "family" else ""),
    )
    var recordsClearCount = 0
    var allLocalDataClearCount = 0
    var failureBeforeClear: Throwable? = null
    var failureAfterClear: Throwable? = null
    var beforeLocalClear: suspend () -> Unit = {}
    var afterLocalClear: suspend () -> Unit = {}

    override fun session(): Flow<SyncSession> = session

    override suspend fun clearLocalData(
        scope: LocalDataClearScope,
        workflow: LocalClearWorkflow,
    ): Result<Unit> {
        when (scope) {
            LocalDataClearScope.RecordsOnly -> recordsClearCount += 1
            LocalDataClearScope.AllLocalData -> allLocalDataClearCount += 1
        }
        return executeClear(workflow)
    }

    private suspend fun executeClear(
        workflow: LocalClearWorkflow,
    ): Result<Unit> = runCatching {
        beforeLocalClear()
        workflow.withLocalExclusion {
            // Production RealSyncPort runs the domain recovery gate first.
            workflow.finishCommitted()
            failureBeforeClear?.let { throw it }
            workflow.clearRoom()
            var committedFailure = failureAfterClear?.let { failure ->
                localClearCommittedFailure(
                    familyServerRetained = familyServerRetained,
                    cause = failure,
                )
            }
            try {
                workflow.finishCommitted()
            } catch (finishFailure: Throwable) {
                if (committedFailure == null) {
                    throw finishFailure
                }
                committedFailure.addSuppressed(finishFailure)
            }
            committedFailure?.let { throw it }
        }
        afterLocalClear()
    }
}

private class RecordingClearReminderPort : ReminderCleanupPort {
    val cancelledCarePlanIds = mutableListOf<Long>()
    var carePlanFailuresRemaining = 0

    override suspend fun scheduleCarePlan(plan: CarePlan): Boolean = true
    override suspend fun cancelCarePlan(carePlanId: Long) {
        cancelledCarePlanIds += carePlanId
        if (carePlanFailuresRemaining > 0) {
            carePlanFailuresRemaining -= 1
            throw IllegalStateException("care-plan reminder cleanup failed")
        }
    }
}
