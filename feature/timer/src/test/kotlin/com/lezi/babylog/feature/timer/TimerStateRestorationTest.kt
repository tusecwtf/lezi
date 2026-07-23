package com.lezi.babylog.feature.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                nowWall = 1_700_000_090_000L,
            ),
        )
    }

    @Test
    fun restore_usesWallClockWhenElapsedRealtimeRewindsAfterReboot() {
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
    fun restore_neverAddsNegativeOrUnknownTime() {
        assertEquals(
            0L,
            restoredRunningDelta(
                savedElapsed = 120_000L,
                savedWall = 1_700_000_090_000L,
                nowElapsed = 5_000L,
                nowWall = 1_700_000_000_000L,
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
    fun discardConfirmation_isOnlyNeededForAccumulatedOrRunningData() {
        assertFalse(TimerState().hasTimerData())
        assertTrue(TimerState(leftRunning = true).hasTimerData())
        assertTrue(TimerState(rightAccumMs = 1L).hasTimerData())
        assertTrue(TimerState(sessionStartedAt = 1_700_000_000_000L).hasTimerData())
    }
}
