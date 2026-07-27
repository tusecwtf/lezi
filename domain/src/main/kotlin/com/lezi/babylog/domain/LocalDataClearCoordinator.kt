package com.lezi.babylog.domain

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CalendarEventDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.LocalUserDao
import com.lezi.babylog.core.database.MembershipDao
import com.lezi.babylog.core.database.PendingReminderCleanup
import com.lezi.babylog.core.database.PendingReminderCleanupOperation
import com.lezi.babylog.core.database.PendingReminderCleanupStore
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.datastore.LocalClearSettingsSnapshot
import com.lezi.babylog.core.datastore.LocalClearSettingsFinish
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.sync.LocalClearCommittedException
import com.lezi.babylog.sync.LocalClearRecoveryScope
import com.lezi.babylog.sync.LocalClearWorkflow
import com.lezi.babylog.sync.SyncPort
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

enum class LocalDataClearScope {
    RecordsOnly,
    AllLocalData,
}

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

    /** Current rows protect post-v20-clear projections from legacy snapshot adoption. */
    suspend fun currentCarePlanClientUuids(): Set<String>
}

@Singleton
internal class DaoLocalDataClearPersistence @Inject constructor(
    private val babyDao: BabyDao,
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val calendarEventDao: CalendarEventDao,
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
        val calendarEventIds = allCalendarEventIds()
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
        calendarEventDao.deleteAll()
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
                operation = scope.pendingCleanupOperation,
                calendarEventIds = calendarEventIds.toSet(),
                carePlanIds = carePlanIds,
                systemCalendarProjections = systemCalendarProjections,
                settingsSnapshotCaptured = true,
                currentBabyId = settingsSnapshot.currentBabyId,
                nextFeedAt = settingsSnapshot.nextFeedAt,
                nextFeedEpoch = settingsSnapshot.nextFeedEpoch,
                familyServerRetained = familyServerRetained,
            ),
        )
    }

    override suspend fun currentCarePlanClientUuids(): Set<String> =
        carePlanDao.listAllIncludingDeleted().mapTo(linkedSetOf()) { it.clientUuid }

    private suspend fun allCalendarEventIds(): List<Long> =
        babyDao.listAllIncludingDeleted()
            .flatMap { baby -> calendarEventDao.listForBabyIncludingDeleted(baby.id) }
            .map { it.id }
            .distinct()
}

/** Internal settings adapter keeps the scope-specific key set out of orchestration. */
internal interface LocalDataClearSettings {
    suspend fun capture(): LocalClearSettingsSnapshot
    suspend fun finish(
        scope: LocalDataClearScope,
        snapshot: LocalClearSettingsSnapshot,
    ): LocalClearSettingsFinish
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

        val failure = executeThroughSyncBarrier(scope, workflow).exceptionOrNull()
            ?: return
        throwClearFailure(failure.asDomainLocalClearFailure())
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

    private suspend fun executeThroughSyncBarrier(
        scope: LocalDataClearScope,
        workflow: LocalClearWorkflow,
    ): Result<Unit> = when (scope) {
        LocalDataClearScope.RecordsOnly -> syncPort.clearLocalRecords(workflow)
        LocalDataClearScope.AllLocalData -> syncPort.clearAllLocalData(workflow)
    }

    private suspend fun finishPendingLocalClear(
        initialFailure: Throwable?,
    ): PendingLocalClearFinish {
        var failure = initialFailure
        var recoveredScope: LocalDataClearScope? = null
        PendingReminderCleanupOperation.entries.forEach { operation ->
            val loaded = pendingReminderCleanupStore.load(operation) ?: return@forEach
            recoveredScope = recoveredScope.include(operation.localClearScope)
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

            var settingsSnapshotReady = loaded.settingsSnapshotCaptured
            var pending = loaded
            if (!settingsSnapshotReady) {
                attempt {
                    pending = ensureDurableSettingsSnapshot(loaded)
                    settingsSnapshotReady = true
                }
            }
            val settingsSnapshot = LocalClearSettingsSnapshot(
                currentBabyId = pending.currentBabyId,
                nextFeedAt = pending.nextFeedAt,
                systemCalendarProjections = pending.systemCalendarProjections.mapNotNull {
                    (clientUuid, eventId) -> eventId?.let { clientUuid to it }
                }.toMap(),
                nextFeedEpoch = pending.nextFeedEpoch,
            )
            pending.systemCalendarProjections.forEach { (clientUuid, eventId) ->
                attempt { deleteSystemCalendarProjection(clientUuid, eventId) }
            }
            var settingsFinish: LocalClearSettingsFinish? = null
            if (settingsSnapshotReady) {
                attempt {
                    settingsFinish = settings.finish(
                        operation.localClearScope,
                        settingsSnapshot,
                    )
                }
            }
            attempt {
                reminderCleanup.cancelForRecordsClear(
                    calendarEventIds = pending.calendarEventIds,
                    cancelNextFeed = settingsFinish?.cancelNextFeedAlarm == true,
                )
            }
            pending.carePlanIds.forEach { carePlanId ->
                attempt { reminderCleanup.cancelCarePlan(carePlanId) }
            }
            if (!operationFailed) {
                attempt { pendingReminderCleanupStore.delete(operation) }
            }
        }
        return PendingLocalClearFinish(recoveredScope = recoveredScope, failure = failure)
    }

    /**
     * Room v20 rows have no settings epoch. Only orphaned calendar-map entries
     * can be attributed to that old clear; current feed and current-plan entries
     * may have been created after the failure and must survive the upgrade.
     */
    private suspend fun ensureDurableSettingsSnapshot(
        pending: PendingReminderCleanup,
    ): PendingReminderCleanup {
        if (pending.settingsSnapshotCaptured) return pending
        val snapshot = settings.capture()
        val currentCarePlanClientUuids = persistence.currentCarePlanClientUuids()
        val orphanedLegacyProjections = snapshot.systemCalendarProjections.filterKeys {
            it !in currentCarePlanClientUuids
        }
        return pending.copy(
            systemCalendarProjections =
                pending.systemCalendarProjections + orphanedLegacyProjections,
            settingsSnapshotCaptured = true,
            currentBabyId = snapshot.currentBabyId,
            nextFeedAt = null,
            nextFeedEpoch = null,
        ).also { pendingReminderCleanupStore.upsert(it) }
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

    private fun Throwable.asDomainLocalClearFailure(): Throwable =
        if (this is LocalClearCommittedException) {
            LocalRecordsClearCommittedException(
                familyServerRetained = familyServerRetained,
                cause = this,
            )
        } else {
            this
        }

    private fun mergeCommittedCleanupFailure(
        initialFailure: Throwable?,
        cleanupError: Throwable,
        familyServerRetained: Boolean,
    ): Throwable {
        if (cleanupError.cancellationCauseOrNull() != null) {
            return LocalRecordsClearCommittedException(
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
            else -> LocalRecordsClearCommittedException(
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

private fun LocalDataClearScope?.include(other: LocalDataClearScope): LocalDataClearScope = when {
    this == LocalDataClearScope.AllLocalData || other == LocalDataClearScope.AllLocalData ->
        LocalDataClearScope.AllLocalData
    else -> LocalDataClearScope.RecordsOnly
}

private val LocalDataClearScope.pendingCleanupOperation: PendingReminderCleanupOperation
    get() = when (this) {
        LocalDataClearScope.RecordsOnly -> PendingReminderCleanupOperation.RECORDS_CLEAR
        LocalDataClearScope.AllLocalData ->
            PendingReminderCleanupOperation.ALL_LOCAL_DATA_CLEAR
    }

private val PendingReminderCleanupOperation.localClearScope: LocalDataClearScope
    get() = when (this) {
        PendingReminderCleanupOperation.RECORDS_CLEAR -> LocalDataClearScope.RecordsOnly
        PendingReminderCleanupOperation.ALL_LOCAL_DATA_CLEAR ->
            LocalDataClearScope.AllLocalData
    }
