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

// Shared harness extracted for ticket 08.


internal const val TIMER_JSON_OLD =
    """{"schemaVersion":1,"completionClientUuid":"session-old","leftRunning":true,"serviceState":"RUNNING"}"""
internal const val TIMER_JSON_NEW =
    """{"schemaVersion":1,"completionClientUuid":"session-new","leftRunning":true,"serviceState":"RUNNING"}"""
internal const val TIMER_JSON_PAUSED =
    """{"schemaVersion":1,"completionClientUuid":"session-old","leftRunning":false,"serviceState":"PAUSED"}"""
internal const val TIMER_JSON_FAILED =
    """{"schemaVersion":1,"completionClientUuid":"session-old","leftRunning":false,"serviceState":"FAILED","requestedSide":"L","serviceFailure":"RESTRICTED"}"""

internal class ClearCoordinatorRig(
    familyServerRetained: Boolean = false,
) {
    val pending = RecordingPendingReminderCleanupStore()
    val persistence = RecordingLocalDataClearPersistence(pending)
    val settings = RecordingLocalDataClearSettings()
    val reminders = RecordingClearReminderPort()
    val timer = RecordingNursingTimerCleanupPort()
    val systemCalendar = RecordingSystemCalendarPort()
    val widgets = RecordingWidgetCleanupPort()
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
            widgetCleanup = widgets,
            pendingReminderCleanupStore = pending,
            mutationGuard = CalendarReminderMutationGuard(),
            localDataMutationEpoch = LocalDataMutationEpoch(),
        )
}

internal class RecordingWidgetCleanupPort : WidgetCleanupPort {
    var calls = 0
    var failuresRemaining = 0

    override suspend fun clearAllWidgetState() {
        calls += 1
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            error("widget cleanup failed")
        }
    }
}

internal class RecordingLocalDataClearPersistence(
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

internal class RecordingLocalDataClearSettings : LocalDataClearSettings {
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

internal class RecordingNursingTimerCleanupPort : NursingTimerCleanupPort {
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

internal class RecordingPendingReminderCleanupStore : PendingReminderCleanupStore {
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

internal class RecordingSystemCalendarPort : SystemCalendarPort {
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

internal class RecordingClearSyncPort(
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

internal class RecordingClearReminderPort : ReminderCleanupPort {
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
