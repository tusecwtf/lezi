package com.lezi.babylog.feature.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-state coverage for L/R toggle transitions and the lost-side race that
 * motivated serializing [TimerViewModel.toggleLeft]/[TimerViewModel.toggleRight]
 * behind a mutex (ISS-003 / F-C-003).
 */
class TimerToggleSerializationTest {
    @Test
    fun toggleLeft_startsLeftAndAssignsBabyWhenNeeded() {
        val next = TimerState().withToggleLeft(
            nowElapsed = 1_000L,
            nowWall = 1_700_000_000_000L,
            babyIdForStart = 7L,
        )

        assertNotNull(next)
        assertEquals(7L, next!!.babyId)
        assertTrue(next.leftRunning)
        assertFalse(next.rightRunning)
        assertEquals(1_000L, next.leftStartedElapsed)
        assertEquals(1_700_000_000_000L, next.sessionStartedAt)
        assertEquals("L", next.order)
        assertEquals("L", next.lastSide)
    }

    @Test
    fun toggleLeft_withoutBabyAbortsStart() {
        assertNull(
            TimerState().withToggleLeft(
                nowElapsed = 1_000L,
                nowWall = 1_700_000_000_000L,
                babyIdForStart = null,
            ),
        )
    }

    @Test
    fun toggleLeft_pausesRunningRightAndFreezesItsAccum() {
        val runningRight = TimerState(
            babyId = 1L,
            rightRunning = true,
            rightAccumMs = 30_000L,
            rightStartedElapsed = 100_000L,
            sessionStartedAt = 1_700_000_000_000L,
            order = "R",
            lastSide = "R",
        )

        val next = runningRight.withToggleLeft(
            nowElapsed = 160_000L,
            nowWall = 1_700_000_060_000L,
        )

        assertNotNull(next)
        assertFalse(next!!.rightRunning)
        assertEquals(90_000L, next.rightAccumMs)
        assertNull(next.rightStartedElapsed)
        assertTrue(next.leftRunning)
        assertEquals(160_000L, next.leftStartedElapsed)
        assertEquals("RL", next.order)
        assertEquals("L", next.lastSide)
    }

    @Test
    fun toggleRight_pausesRunningLeftAndBuildsOrderLR() {
        val runningLeft = TimerState(
            babyId = 1L,
            leftRunning = true,
            leftAccumMs = 10_000L,
            leftStartedElapsed = 50_000L,
            sessionStartedAt = 1_700_000_000_000L,
            order = "L",
            lastSide = "L",
        )

        val next = runningLeft.withToggleRight(
            nowElapsed = 110_000L,
            nowWall = 1_700_000_060_000L,
        )

        assertNotNull(next)
        assertFalse(next!!.leftRunning)
        assertEquals(70_000L, next.leftAccumMs)
        assertTrue(next.rightRunning)
        assertEquals("LR", next.order)
        assertEquals("R", next.lastSide)
    }

    @Test
    fun sequentialLeftThenRight_preservesBothSides() {
        // Night-feed pattern: start L, later switch to R — both sides kept.
        val startedLeft = TimerState().withToggleLeft(
            nowElapsed = 0L,
            nowWall = 1_700_000_000_000L,
            babyIdForStart = 1L,
        )!!
        val switchedToRight = startedLeft.withToggleRight(
            nowElapsed = 60_000L,
            nowWall = 1_700_000_060_000L,
        )!!

        assertEquals(60_000L, switchedToRight.leftAccumMs)
        assertFalse(switchedToRight.leftRunning)
        assertTrue(switchedToRight.rightRunning)
        assertEquals(0L, switchedToRight.rightAccumMs)
        assertEquals("LR", switchedToRight.order)
    }

    @Test
    fun concurrentReadModifyWriteWithoutSerialRead_losesOneSide() {
        // Documents the pre-fix race: two toggles snapshot the same empty
        // state and each writes independently; last writer wins and the other
        // side's start is dropped. ViewModel now holds a mutex so each toggle
        // re-reads after the previous write.
        val base = TimerState()
        val leftOnly = base.withToggleLeft(
            nowElapsed = 0L,
            nowWall = 1_700_000_000_000L,
            babyIdForStart = 1L,
        )!!
        val rightOnly = base.withToggleRight(
            nowElapsed = 0L,
            nowWall = 1_700_000_000_000L,
            babyIdForStart = 1L,
        )!!

        // Last writer = right: left never started.
        assertTrue(rightOnly.rightRunning)
        assertFalse(rightOnly.leftRunning)
        assertEquals(0L, rightOnly.leftAccumMs)
        // And the discarded left-only write would have been the opposite.
        assertTrue(leftOnly.leftRunning)
        assertFalse(leftOnly.rightRunning)
    }

    @Test
    fun serializedToggles_reReadLatestState_keepBothSides() {
        // Same concurrent intent as above, but each step re-reads the latest
        // state (mutex-equivalent serialization) so L then R is not lost.
        var state = TimerState()
        state = state.withToggleLeft(
            nowElapsed = 0L,
            nowWall = 1_700_000_000_000L,
            babyIdForStart = 1L,
        )!!
        state = state.withToggleRight(
            nowElapsed = 1L,
            nowWall = 1_700_000_000_001L,
        )!!

        assertEquals(1L, state.leftAccumMs)
        assertFalse(state.leftRunning)
        assertTrue(state.rightRunning)
        assertEquals("LR", state.order)
        assertEquals(1L, state.babyId)
    }

    @Test
    fun toggleStopsRunningSide_freezesAccum() {
        val running = TimerState(
            babyId = 1L,
            leftRunning = true,
            leftAccumMs = 5_000L,
            leftStartedElapsed = 10_000L,
            sessionStartedAt = 1_700_000_000_000L,
            order = "L",
        )
        val stopped = running.withToggleLeft(
            nowElapsed = 40_000L,
            nowWall = 1_700_000_030_000L,
        )!!

        assertFalse(stopped.leftRunning)
        assertEquals(35_000L, stopped.leftAccumMs)
        assertNull(stopped.leftStartedElapsed)
        assertEquals("L", stopped.lastSide)
    }
}
