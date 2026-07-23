package com.lezi.babylog.feature.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerStateRestorationTest {
    @Test
    fun restore_usesElapsedRealtimeWhileDeviceStaysBooted() {
        assertEquals(
            45_000L,
            restoredRunningDelta(
                savedElapsed = 120_000L,
                savedWall = 1_700_000_000_000L,
                nowElapsed = 165_000L,
                nowWall = 1_699_999_910_000L,
                savedBootCount = 12L,
                nowBootCount = 12L,
            ),
        )
    }

    @Test
    fun restore_usesWallClockAfterRebootEvenWhenNewUptimeIsLarger() {
        assertEquals(
            90_000L,
            restoredRunningDelta(
                savedElapsed = 120_000L,
                savedWall = 1_700_000_000_000L,
                nowElapsed = 900_000L,
                nowWall = 1_700_000_090_000L,
                savedBootCount = 12L,
                nowBootCount = 13L,
            ),
        )
    }

    @Test
    fun restore_legacySnapshotKeepsConservativeUptimeFallback() {
        assertEquals(
            45_000L,
            restoredRunningDelta(
                savedElapsed = 120_000L,
                savedWall = 1_700_000_000_000L,
                nowElapsed = 165_000L,
                nowWall = 1_700_000_090_000L,
            ),
        )
        assertEquals(
            90_000L,
            restoredRunningDelta(
                savedElapsed = 120_000L,
                savedWall = 1_700_000_000_000L,
                nowElapsed = 5_000L,
                nowWall = 1_700_000_090_000L,
            ),
        )
    }

    @Test
    fun restore_legacySnapshotUsesWallWhenCurrentBootIdentityIsAvailable() {
        assertEquals(
            90_000L,
            restoredRunningDelta(
                savedElapsed = 120_000L,
                savedWall = 1_700_000_000_000L,
                nowElapsed = 900_000L,
                nowWall = 1_700_000_090_000L,
                savedBootCount = null,
                nowBootCount = 13L,
            ),
        )
    }

    @Test
    fun restore_neverAddsNegativeWallOrUnknownTime() {
        assertEquals(
            0L,
            restoredRunningDelta(
                savedElapsed = 120_000L,
                savedWall = 1_700_000_090_000L,
                nowElapsed = 900_000L,
                nowWall = 1_700_000_000_000L,
                savedBootCount = 12L,
                nowBootCount = 13L,
            ),
        )
        assertEquals(
            0L,
            restoredRunningDelta(
                savedElapsed = null,
                savedWall = null,
                nowElapsed = 5_000L,
                nowWall = 1_700_000_000_000L,
            ),
        )
    }

    @Test
    fun bootCountRead_isSafeAndRejectsInvalidValues() {
        assertEquals(12L, safeBootCount { 12 })
        assertNull(safeBootCount { -1 })
        assertNull(safeBootCount { throw SecurityException("denied") })
    }

    @Test
    fun discardConfirmation_isOnlyNeededForAccumulatedOrRunningData() {
        assertFalse(TimerState().hasTimerData())
        assertTrue(TimerState(leftRunning = true).hasTimerData())
        assertTrue(TimerState(rightAccumMs = 1L).hasTimerData())
        assertTrue(TimerState(sessionStartedAt = 1_700_000_000_000L).hasTimerData())
    }
}
