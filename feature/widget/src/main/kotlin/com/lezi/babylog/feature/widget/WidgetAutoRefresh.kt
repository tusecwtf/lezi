package com.lezi.babylog.feature.widget

import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.localdata.LocalDataClearInProgressException
import com.lezi.babylog.domain.localdata.LocalDataEpochInvalidatedException
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Coalesces every source that can change the persisted widget summary. */
internal class WidgetRefreshCoordinator(
    private val babyChanges: Flow<Unit>,
    private val configuredBabyIds: Flow<List<Long>>,
    private val recordChanges: (babyId: Long, date: LocalDate) -> Flow<Unit>,
    private val refreshAll: suspend () -> Unit,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun observe(dates: Flow<LocalDate>) {
        combine(babyChanges, configuredBabyIds, dates) { _, babyIds, date ->
            babyIds.distinct() to date
        }.flatMapLatest { (babyIds, date) ->
            val recordFlows = babyIds.map { babyId -> recordChanges(babyId, date) }
            merge(flowOf(Unit), *recordFlows.toTypedArray())
        }.conflate().collect {
            try {
                refreshAll()
            } catch (_: LocalDataClearInProgressException) {
                // Destructive clear won this event. Keep the process observer alive;
                // the clear path redraws unconfigured state and later facts retry.
            } catch (_: LocalDataEpochInvalidatedException) {
                // Data changed generation after summary load. Drop this stale event
                // and continue observing instead of reviving pre-clear content.
            }
        }
    }
}

/** Application-lifetime observer covering local writes, sync writes, profile changes and midnight. */
@Singleton
class CareWidgetAutoRefresh @Inject constructor(
    private val careLog: CareLog,
    private val controller: CareWidgetRefreshController,
) {
    private val started = AtomicBoolean(false)
    private val zone = ZoneId.systemDefault()

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        val coordinator = WidgetRefreshCoordinator(
            babyChanges = careLog.observeBabies().map { Unit },
            configuredBabyIds = controller.observeConfiguredBabyIds(),
            recordChanges = { babyId, date ->
                careLog.observeDayRecords(babyId, date, zone).map { Unit }
            },
            refreshAll = controller::refreshAll,
        )
        scope.launch { coordinator.observe(midnightDates(zone)) }
    }
}

internal fun midnightDates(
    zone: ZoneId,
    now: () -> ZonedDateTime = { ZonedDateTime.now(zone) },
): Flow<LocalDate> = flow {
    var emitted = now().toLocalDate()
    emit(emitted)
    while (currentCoroutineContext().isActive) {
        val current = now()
        val nextMidnight = current.toLocalDate().plusDays(1).atStartOfDay(zone)
        delay(Duration.between(current, nextMidnight).toMillis().coerceAtLeast(1_000L))
        val currentDate = now().toLocalDate()
        if (currentDate != emitted) {
            emitted = currentDate
            emit(currentDate)
        }
    }
}
