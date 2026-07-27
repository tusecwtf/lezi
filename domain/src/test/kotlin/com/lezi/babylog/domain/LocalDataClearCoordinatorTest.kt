package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.PendingReminderCleanup
import com.lezi.babylog.core.database.PendingReminderCleanupOperation
import com.lezi.babylog.core.database.PendingReminderCleanupStore
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.sync.LocalClearCommittedException
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncSession
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocalDataClearCoordinatorTest {
    @Test
    fun recordsAndAllScopesShareOneSuccessfulOrchestration() = runTest {
        val rig = ClearCoordinatorRig()

        rig.coordinator.clear(LocalDataClearScope.RecordsOnly)
        rig.coordinator.clear(LocalDataClearScope.AllLocalData)

        assertThat(rig.sync.recordsClearCount).isEqualTo(1)
        assertThat(rig.sync.allLocalDataClearCount).isEqualTo(1)
        assertThat(rig.persistence.scopes)
            .containsExactly(LocalDataClearScope.RecordsOnly, LocalDataClearScope.AllLocalData)
            .inOrder()
        assertThat(rig.settings.scopes)
            .containsExactly(LocalDataClearScope.RecordsOnly, LocalDataClearScope.AllLocalData)
            .inOrder()
        assertThat(rig.reminders.recordClearBatches)
            .containsExactly(setOf(11L, 12L), setOf(11L, 12L))
            .inOrder()
        assertThat(rig.reminders.cancelledCarePlanIds)
            .containsExactly(21L, 22L, 21L, 22L)
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
        assertThat(rig.reminders.recordClearBatches).isEmpty()
        assertThat(rig.reminders.cancelledCarePlanIds).isEmpty()
        assertThat(rig.pending.pending).isNull()
    }

    @Test
    fun reminderFailureAfterCommitIsClassifiedAndRecoveryFinishesPendingWork() = runTest {
        val rig = ClearCoordinatorRig(familyServerRetained = true)
        rig.reminders.recordClearFailuresRemaining = 1

        val failure = runCatching {
            rig.coordinator.clear(LocalDataClearScope.RecordsOnly)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalRecordsClearCommittedException::class.java)
        assertThat((failure as LocalRecordsClearCommittedException).familyServerRetained).isTrue()
        assertThat(rig.pending.pending?.calendarEventIds).containsExactly(11L, 12L)
        assertThat(rig.pending.deleteCount).isEqualTo(0)

        rig.coordinator.recoverPendingReminderCleanup()

        assertThat(rig.pending.pending).isNull()
        assertThat(rig.pending.deleteCount).isEqualTo(1)
        assertThat(rig.reminders.recordClearBatches)
            .containsExactly(setOf(11L, 12L), setOf(11L, 12L))
            .inOrder()
    }

    @Test
    fun carePlanReminderFailureSurvivesCoordinatorRecreationAndRecovery() = runTest {
        val rig = ClearCoordinatorRig(familyServerRetained = true)
        rig.reminders.carePlanFailuresRemaining = 1

        val failure = runCatching {
            rig.coordinator.clear(LocalDataClearScope.RecordsOnly)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalRecordsClearCommittedException::class.java)
        assertThat(rig.reminders.cancelledCarePlanIds).containsExactly(21L)

        rig.newCoordinator().recoverPendingReminderCleanup()

        assertThat(rig.reminders.cancelledCarePlanIds)
            .containsExactly(21L, 21L, 22L)
            .inOrder()
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
        assertThat(rig.pending.pending).isNull()
        assertThat(rig.reminders.recordClearBatches).containsExactly(setOf(11L, 12L))
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
        assertThat(rig.reminders.recordClearBatches).isEmpty()
        assertThat(rig.pending.deleteCount).isEqualTo(0)
    }
}

private class ClearCoordinatorRig(
    familyServerRetained: Boolean = false,
) {
    val pending = RecordingPendingReminderCleanupStore()
    val persistence = RecordingLocalDataClearPersistence(pending)
    val settings = RecordingLocalDataClearSettings()
    val reminders = RecordingClearReminderPort()
    val sync = RecordingClearSyncPort(familyServerRetained)
    val coordinator: LocalDataClearCoordinator = newCoordinator()

    fun newCoordinator(): LocalDataClearCoordinator =
        DefaultLocalDataClearCoordinator(
            persistence = persistence,
            settings = settings,
            syncPort = sync,
            reminderCleanup = reminders,
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
    ) {
        failure?.let { throw it }
        scopes += scope
        pendingStore.upsert(
            PendingReminderCleanup(
                operation = PendingReminderCleanupOperation.RECORDS_CLEAR,
                calendarEventIds = setOf(11L, 12L),
                carePlanIds = setOf(21L, 22L),
                familyServerRetained = familyServerRetained,
            ),
        )
    }
}

private class RecordingLocalDataClearSettings : LocalDataClearSettings {
    val scopes = mutableListOf<LocalDataClearScope>()
    var failure: Throwable? = null

    override suspend fun clear(scope: LocalDataClearScope) {
        scopes += scope
        failure?.let { throw it }
    }
}

private class RecordingPendingReminderCleanupStore : PendingReminderCleanupStore {
    var pending: PendingReminderCleanup? = null
    var loadFailure: Throwable? = null
    var deleteCount = 0

    override suspend fun load(
        operation: PendingReminderCleanupOperation,
    ): PendingReminderCleanup? {
        loadFailure?.let { throw it }
        return pending?.takeIf { it.operation == operation }
    }

    override suspend fun upsert(pending: PendingReminderCleanup) {
        val existing = this.pending?.takeIf { it.operation == pending.operation }
        this.pending = pending.copy(
            calendarEventIds = existing?.calendarEventIds.orEmpty() + pending.calendarEventIds,
            carePlanIds = existing?.carePlanIds.orEmpty() + pending.carePlanIds,
            familyServerRetained =
                existing?.familyServerRetained == true || pending.familyServerRetained,
        )
    }

    override suspend fun delete(operation: PendingReminderCleanupOperation) {
        deleteCount += 1
        if (pending?.operation == operation) pending = null
    }
}

private class RecordingClearSyncPort(
    private val familyServerRetained: Boolean,
) : SyncPort by NoOpSyncPort() {
    private val session = MutableStateFlow(
        SyncSession(familyId = if (familyServerRetained) "family" else ""),
    )
    var recordsClearCount = 0
    var allLocalDataClearCount = 0

    override fun session(): Flow<SyncSession> = session

    override suspend fun clearLocalRecords(
        clearLocal: suspend (onCommitted: () -> Unit) -> Unit,
    ): Result<Unit> {
        recordsClearCount += 1
        return executeClear(clearLocal)
    }

    override suspend fun clearAllLocalData(
        clearLocal: suspend (onCommitted: () -> Unit) -> Unit,
    ): Result<Unit> {
        allLocalDataClearCount += 1
        return executeClear(clearLocal)
    }

    private suspend fun executeClear(
        clearLocal: suspend (onCommitted: () -> Unit) -> Unit,
    ): Result<Unit> = runCatching {
        var committed = false
        try {
            clearLocal { committed = true }
            check(committed) { "domain transaction did not report commit" }
        } catch (error: Throwable) {
            if (committed) {
                throw LocalClearCommittedException(
                    familyServerRetained = familyServerRetained,
                    cause = error,
                )
            }
            throw error
        }
    }
}

private class RecordingClearReminderPort : ReminderCleanupPort {
    val recordClearBatches = mutableListOf<Set<Long>>()
    val cancelledCarePlanIds = mutableListOf<Long>()
    var recordClearFailuresRemaining = 0
    var carePlanFailuresRemaining = 0

    override suspend fun scheduleCalendar(event: CalendarEvent): Boolean = true
    override suspend fun cancelCalendar(eventId: Long) = Unit
    override suspend fun scheduleCarePlan(plan: CarePlan): Boolean = true
    override suspend fun cancelCarePlan(carePlanId: Long) {
        cancelledCarePlanIds += carePlanId
        if (carePlanFailuresRemaining > 0) {
            carePlanFailuresRemaining -= 1
            throw IllegalStateException("care-plan reminder cleanup failed")
        }
    }
    override suspend fun cancelCarePlanByClientUuid(clientUuid: String) = Unit
    override suspend fun cancelForRecordsClear(calendarEventIds: Collection<Long>) {
        recordClearBatches += calendarEventIds.toSet()
        if (recordClearFailuresRemaining > 0) {
            recordClearFailuresRemaining -= 1
            throw IllegalStateException("reminder cleanup failed")
        }
    }
    override suspend fun cancelForBabyDelete(calendarEventIds: Collection<Long>) = Unit
}
