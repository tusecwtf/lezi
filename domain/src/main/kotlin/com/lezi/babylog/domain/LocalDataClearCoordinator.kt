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
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.sync.LocalClearCommittedException
import com.lezi.babylog.sync.SyncPort
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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
    suspend fun recoverPendingReminderCleanup()
}

/** Shared guard for calendar/reminder mutations and local clear operations. */
@Singleton
class CalendarReminderMutationGuard @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

/** Internal persistence seam; the production adapter owns the single Room transaction. */
internal interface LocalDataClearPersistence {
    suspend fun clear(
        scope: LocalDataClearScope,
        familyServerRetained: Boolean,
    )
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
    ) = transactionRunner.run {
        val calendarEventIds = allCalendarEventIds()
        val carePlanIds = carePlanDao.listAllIncludingDeleted().map { it.id }.toSet()

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
                operation = PendingReminderCleanupOperation.RECORDS_CLEAR,
                calendarEventIds = calendarEventIds.toSet(),
                carePlanIds = carePlanIds,
                familyServerRetained = familyServerRetained,
            ),
        )
    }

    private suspend fun allCalendarEventIds(): List<Long> =
        babyDao.listAllIncludingDeleted()
            .flatMap { baby -> calendarEventDao.listForBabyIncludingDeleted(baby.id) }
            .map { it.id }
            .distinct()
}

/** Internal settings adapter keeps the scope-specific key set out of orchestration. */
internal interface LocalDataClearSettings {
    suspend fun clear(scope: LocalDataClearScope)
}

@Singleton
internal class StoreLocalDataClearSettings @Inject constructor(
    private val settings: SettingsStore,
) : LocalDataClearSettings {
    override suspend fun clear(scope: LocalDataClearScope) {
        if (scope == LocalDataClearScope.AllLocalData) {
            settings.setCurrentBabyId(null)
        }
        settings.clearNextFeedAt()
        settings.setSystemCalendarEventMapJson("{}")
    }
}

@Singleton
internal class DefaultLocalDataClearCoordinator @Inject constructor(
    private val persistence: LocalDataClearPersistence,
    private val settings: LocalDataClearSettings,
    private val syncPort: SyncPort,
    private val reminderCleanup: ReminderCleanupPort,
    private val pendingReminderCleanupStore: PendingReminderCleanupStore,
    private val mutationGuard: CalendarReminderMutationGuard,
) : LocalDataClearCoordinator {
    override suspend fun clear(scope: LocalDataClearScope) = mutationGuard.withLock {
        val familyServerRetained = syncPort.session().first().familyId.isNotBlank()
        var failure: Throwable? = null
        try {
            executeThroughSyncBarrier(scope) { onCommitted ->
                persistence.clear(scope, familyServerRetained)
                onCommitted()
                settings.clear(scope)
            }.getOrThrow()
        } catch (error: Throwable) {
            failure = error.asDomainLocalClearFailure()
        }

        failure = withContext(NonCancellable) {
            finishPendingReminderCleanup(failure)
        }
        throwClearFailure(failure)
    }

    override suspend fun recoverPendingReminderCleanup() = mutationGuard.withLock {
        val failure = withContext(NonCancellable) {
            finishPendingReminderCleanup(initialFailure = null)
        }
        throwClearFailure(failure)
    }

    private suspend fun executeThroughSyncBarrier(
        scope: LocalDataClearScope,
        clearLocal: suspend (onCommitted: () -> Unit) -> Unit,
    ): Result<Unit> = when (scope) {
        LocalDataClearScope.RecordsOnly -> syncPort.clearLocalRecords(clearLocal)
        LocalDataClearScope.AllLocalData -> syncPort.clearAllLocalData(clearLocal)
    }

    private suspend fun finishPendingReminderCleanup(
        initialFailure: Throwable?,
    ): Throwable? {
        val operation = PendingReminderCleanupOperation.RECORDS_CLEAR
        val pending = pendingReminderCleanupStore.load(operation)
            ?: return initialFailure
        return try {
            reminderCleanup.cancelForRecordsClear(pending.calendarEventIds)
            pending.carePlanIds.forEach { reminderCleanup.cancelCarePlan(it) }
            pendingReminderCleanupStore.delete(operation)
            initialFailure
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (reminderError: Throwable) {
            when (initialFailure) {
                is LocalRecordsClearCommittedException -> initialFailure.apply {
                    addSuppressed(reminderError)
                }
                else -> LocalRecordsClearCommittedException(
                    familyServerRetained = pending.familyServerRetained,
                    cause = initialFailure ?: reminderError,
                ).also { classified ->
                    if (initialFailure != null) classified.addSuppressed(reminderError)
                }
            }
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
