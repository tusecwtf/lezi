package com.lezi.babylog.feature.widget

import com.lezi.babylog.domain.localdata.LocalDataClearInProgressException
import com.lezi.babylog.domain.localdata.LocalDataEpochInvalidatedException
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRefreshCoordinatorTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun clearEpochRacesDropOnlyTheirEventAndObserverKeepsRefreshing() = runTest {
        val babies = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        val configuredBabies = MutableStateFlow(listOf(42L))
        val dates = MutableStateFlow(LocalDate.of(2026, 8, 3))
        val records = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var refreshAttempts = 0
        val coordinator = WidgetRefreshCoordinator(
            babyChanges = babies,
            configuredBabyIds = configuredBabies,
            recordChanges = { _, _ -> records },
            refreshAll = {
                refreshAttempts += 1
                when (refreshAttempts) {
                    1 -> throw LocalDataClearInProgressException()
                    2 -> throw LocalDataEpochInvalidatedException()
                }
            },
        )

        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.observe(dates)
        }
        runCurrent()
        assertEquals(1, refreshAttempts)

        records.tryEmit(Unit)
        runCurrent()
        assertEquals(2, refreshAttempts)

        records.tryEmit(Unit)
        runCurrent()
        assertEquals(3, refreshAttempts)
        assertTrue(observer.isActive)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun refreshesForRecordBabyConfigurationAndMidnightChanges() = runTest {
        val babies = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        val configuredBabies = MutableStateFlow(listOf(42L))
        val dates = MutableStateFlow(LocalDate.of(2026, 7, 26))
        val records = mutableMapOf<Long, MutableSharedFlow<Unit>>()
        var refreshCount = 0
        val coordinator = WidgetRefreshCoordinator(
            babyChanges = babies,
            configuredBabyIds = configuredBabies,
            recordChanges = { babyId, _ ->
                records.getOrPut(babyId) { MutableSharedFlow(extraBufferCapacity = 1) }
            },
            refreshAll = { refreshCount += 1 },
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.observe(dates)
        }
        runCurrent()
        val initialCount = refreshCount

        records.getValue(42L).tryEmit(Unit)
        runCurrent()
        assertTrue(refreshCount > initialCount)
        val afterRecord = refreshCount

        babies.emit(Unit)
        runCurrent()
        assertTrue(refreshCount > afterRecord)
        val afterBaby = refreshCount

        dates.value = dates.value.plusDays(1)
        runCurrent()
        assertTrue(refreshCount > afterBaby)
        val afterMidnight = refreshCount

        configuredBabies.value = listOf(42L, 99L)
        runCurrent()
        records.getValue(99L).tryEmit(Unit)
        runCurrent()
        assertTrue(refreshCount > afterMidnight)
    }
}
