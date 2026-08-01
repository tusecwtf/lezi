package com.lezi.babylog.domain

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.LocalUserDao
import com.lezi.babylog.core.database.MembershipDao
import com.lezi.babylog.core.database.PendingReminderCleanup
import com.lezi.babylog.core.database.PendingReminderCleanupStore
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.datastore.LocalClearSettingsSnapshot
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.sync.LocalClearWorkflow
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.localClearCommittedFailure
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

typealias LocalDataClearScope = com.lezi.babylog.core.database.LocalDataClearScope

/** Deep module for local clear transactions, replica barriers, and recoverable cleanup. */
interface LocalDataClearCoordinator {
    suspend fun clear(scope: LocalDataClearScope)
    /** @return the widest previously committed clear resumed to completion. */
    suspend fun recoverPendingReminderCleanup(): LocalDataClearScope?
}

/** Shared guard for calendar/reminder mutations and local clear operations. */
@Singleton
class CalendarReminderMutationGuard @Inject constructor() {
    private val mutex = Mutex()
    @Volatile private var owner: Any? = null

    suspend fun <T> withLock(block: suspend () -> T): T {
        val token: Any = currentCoroutineContext()[Job] ?: currentCoroutineContext()
        if (owner === token) {
            return block()
        }
        mutex.lock(token)
        owner = token
        return try {
            block()
        } finally {
            owner = null
            mutex.unlock(token)
        }
    }
}

/** Internal persistence seam; the production adapter owns the single Room transaction. */
internal interface LocalDataClearPersistence {
    suspend fun clear(
        scope: LocalDataClearScope,
        familyServerRetained: Boolean,
        settingsSnapshot: LocalClearSettingsSnapshot,
    )

}

@Singleton
internal class DaoLocalDataClearPersistence @Inject constructor(
    private val babyDao: BabyDao,
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val customItemDao: CustomItemDao,
    private val localUserDao: LocalUserDao,
    private val familyDao: FamilyDao,
    private val membershipDao: MembershipDao,
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val pendingReminderCleanupStore: PendingReminderCleanupStore,
    private val transactionRunner: DatabaseTransactionRunner,
) : LocalDataClearPersistence {
    override suspend fun clear(
        scope: LocalDataClearScope,
        familyServerRetained: Boolean,
        settingsSnapshot: LocalClearSettingsSnapshot,
    ) = transactionRunner.run {
        val carePlans = carePlanDao.listAllIncludingDeleted()
        val carePlanIds = carePlans.map { it.id }.toSet()
        val systemCalendarProjections = linkedMapOf<String, String?>().apply {
            putAll(settingsSnapshot.systemCalendarProjections)
            carePlans.filter { plan ->
                plan.systemCalendarEventId != null ||
                    plan.systemCalendarProjectionPending ||
                    plan.systemCalendarReminderReady
            }.forEach { plan ->
                putIfAbsent(plan.clientUuid, plan.systemCalendarEventId)
            }
        }

        recordDao.deleteAll()
        fulfillmentCandidateDao.deleteAll()
        carePlanDao.deleteAll()
        if (scope == LocalDataClearScope.AllLocalData) {
            customItemDao.deleteAll()
            babyDao.deleteAll()
            membershipDao.deleteAll()
            familyDao.deleteAll()
            localUserDao.deleteAll()
        }
        pendingReminderCleanupStore.upsert(
            PendingReminderCleanup(
                scope = scope,
                carePlanIds = carePlanIds,
                systemCalendarProjections = systemCalendarProjections,
                currentBabyId = settingsSnapshot.currentBabyId,
                nursingTimerJson = settingsSnapshot.nursingTimerJson,
                nursingTimerSessionToken = settingsSnapshot.nursingTimerSessionToken,
                familyServerRetained = familyServerRetained,
            ),
        )
    }

}

/** Internal settings adapter keeps the scope-specific key set out of orchestration. */
internal interface LocalDataClearSettings {
    suspend fun capture(): LocalClearSettingsSnapshot
    suspend fun finish(
        scope: LocalDataClearScope,
        snapshot: LocalClearSettingsSnapshot,
    )
}

@Singleton
internal class StoreLocalDataClearSettings @Inject constructor(
    private val settings: SettingsStore,
) : LocalDataClearSettings {
    override suspend fun capture(): LocalClearSettingsSnapshot =
        settings.captureLocalClearSettings()

    override suspend fun finish(
        scope: LocalDataClearScope,
        snapshot: LocalClearSettingsSnapshot,
    ) = settings.finishLocalClearSettings(
        snapshot = snapshot,
        clearCurrentBabyId = scope == LocalDataClearScope.AllLocalData,
    )
}

@Singleton
internal class DefaultLocalDataClearCoordinator @Inject constructor(
    private val persistence: LocalDataClearPersistence,
    private val settings: LocalDataClearSettings,
    private val syncPort: SyncPort,
    private val reminderCleanup: ReminderCleanupPort,
    private val nursingTimerCleanup: NursingTimerCleanupPort,
    private val systemCalendar: SystemCalendarPort,
    private val pendingReminderCleanupStore: PendingReminderCleanupStore,
    private val mutationGuard: CalendarReminderMutationGuard,
) : LocalDataClearCoordinator {
    override suspend fun clear(scope: LocalDataClearScope) {
        var capturedSettings: LocalClearSettingsSnapshot? = null
        val workflow = object : LocalClearWorkflow {
            override suspend fun <T> withLocalExclusion(block: suspend () -> T): T =
                mutationGuard.withLock {
                    capturedSettings = settings.capture()
                    block()
                }

            override suspend fun clearRoom() {
                persistence.clear(
                    scope = scope,
                    familyServerRetained = syncPort.session().first().familyId.isNotBlank(),
                    settingsSnapshot = checkNotNull(capturedSettings) {
                        "Local clear settings must be captured under exclusion"
                    },
                )
            }

            override suspend fun finishCommitted() {
                val finish = withContext(NonCancellable) {
                    finishPendingLocalClear(initialFailure = null)
                }
                throwClearFailure(finish.failure)
            }
        }

        val failure = syncPort.clearLocalData(scope, workflow).exceptionOrNull()
            ?: return
        throwClearFailure(failure)
    }

    override suspend fun recoverPendingReminderCleanup(): LocalDataClearScope? =
        mutationGuard.withLock {
            val finish = withContext(NonCancellable) {
                finishPendingLocalClear(initialFailure = null)
            }
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancellation: CancellationException) {
                finish.failure
                    ?.takeUnless { it === cancellation }
                    ?.let(cancellation::addSuppressed)
                throw cancellation
            }
            throwClearFailure(finish.failure)
            finish.recoveredScope
        }

    private suspend fun finishPendingLocalClear(
        initialFailure: Throwable?,
    ): PendingLocalClearFinish {
        var failure = initialFailure
        var recoveredScope: LocalDataClearScope? = null
        LocalDataClearScope.entries.forEach { scope ->
            val loaded = pendingReminderCleanupStore.load(scope) ?: return@forEach
            recoveredScope = LocalDataClearScope.widest(recoveredScope, loaded.scope)
            var operationFailed = false
            fun recordFailure(cleanupError: Throwable) {
                operationFailed = true
                failure = mergeCommittedCleanupFailure(
                    initialFailure = failure,
                    cleanupError = cleanupError,
                    familyServerRetained = loaded.familyServerRetained,
                )
            }
            suspend fun attempt(block: suspend () -> Unit) {
                try {
                    block()
                } catch (cleanupError: Throwable) {
                    recordFailure(cleanupError)
                }
            }

            val settingsSnapshot = LocalClearSettingsSnapshot(
                currentBabyId = loaded.currentBabyId,
                systemCalendarProjections = loaded.systemCalendarProjections.mapNotNull {
                    (clientUuid, eventId) -> eventId?.let { clientUuid to it }
                }.toMap(),
                nursingTimerJson = loaded.nursingTimerJson,
                nursingTimerSessionToken = loaded.nursingTimerSessionToken,
            )
            loaded.systemCalendarProjections.forEach { (clientUuid, eventId) ->
                attempt { deleteSystemCalendarProjection(clientUuid, eventId) }
            }
            // Stop FGS for the captured session before DataStore CAS remove so a
            // still-running old service cannot keep an ongoing notification after
            // the durable clear epoch is finalized.
            attempt {
                nursingTimerCleanup.stopCapturedSession(loaded.nursingTimerSessionToken)
            }
            attempt {
                settings.finish(
                    loaded.scope,
                    settingsSnapshot,
                )
            }
            loaded.carePlanIds.forEach { carePlanId ->
                attempt { reminderCleanup.cancelCarePlan(carePlanId) }
            }
            if (!operationFailed) {
                attempt { pendingReminderCleanupStore.delete(scope) }
            }
        }
        return PendingLocalClearFinish(recoveredScope = recoveredScope, failure = failure)
    }

    private suspend fun deleteSystemCalendarProjection(
        clientUuid: String,
        knownEventId: String?,
    ) {
        if (knownEventId != null) {
            val deleted = systemCalendar.deleteEvent(knownEventId, clientUuid)
            val confirmedAbsent = !deleted &&
                systemCalendar.eventState(knownEventId, clientUuid) ==
                SystemCalendarEventState.ABSENT
            check(deleted || confirmedAbsent) {
                "无法删除系统日历副本，请恢复日历权限后重试"
            }
        }
        val lookup = systemCalendar.findOwnedEvent(clientUuid)
        when (lookup) {
            SystemCalendarOwnedEventLookup.Absent -> return
            SystemCalendarOwnedEventLookup.Unavailable -> error(
                "无法查询系统日历副本，请恢复日历权限后重试",
            )
            is SystemCalendarOwnedEventLookup.Found -> lookup.eventIds.forEach { eventId ->
                val deleted = systemCalendar.deleteEvent(eventId, clientUuid)
                val confirmedAbsent = !deleted &&
                    systemCalendar.eventState(eventId, clientUuid) ==
                    SystemCalendarEventState.ABSENT
                check(deleted || confirmedAbsent) {
                    "无法删除系统日历副本，请恢复日历权限后重试"
                }
            }
        }
        check(systemCalendar.findOwnedEvent(clientUuid) == SystemCalendarOwnedEventLookup.Absent) {
            "系统日历副本尚未完全删除，请重试"
        }
    }

    private fun mergeCommittedCleanupFailure(
        initialFailure: Throwable?,
        cleanupError: Throwable,
        familyServerRetained: Boolean,
    ): Throwable {
        if (cleanupError.cancellationCauseOrNull() != null) {
            return localClearCommittedFailure(
                familyServerRetained = familyServerRetained,
                cause = cleanupError,
            ).also { classified ->
                if (initialFailure != null) classified.addSuppressed(initialFailure)
            }
        }
        return when (initialFailure) {
            is LocalRecordsClearCommittedException -> initialFailure.apply {
                addSuppressed(cleanupError)
            }
            else -> localClearCommittedFailure(
                familyServerRetained = familyServerRetained,
                cause = initialFailure ?: cleanupError,
            ).also { classified ->
                if (initialFailure != null) classified.addSuppressed(cleanupError)
            }
        }
    }

    private fun throwClearFailure(failure: Throwable?) {
        if (failure == null) return
        failure.cancellationCauseOrNull()?.let { throw it }
        throw failure
    }

    private fun Throwable.cancellationCauseOrNull(): CancellationException? {
        var current: Throwable? = this
        while (current != null) {
            if (current is CancellationException) return current
            current = current.cause
        }
        return null
    }
}

private data class PendingLocalClearFinish(
    val recoveredScope: LocalDataClearScope?,
    val failure: Throwable?,
)
