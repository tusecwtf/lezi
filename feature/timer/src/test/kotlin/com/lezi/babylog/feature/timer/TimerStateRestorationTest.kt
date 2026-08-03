package com.lezi.babylog.feature.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerStateRestorationTest {
    @Test
    fun restore_rejectsUnversionedSnapshot() {
        val raw = """{
            "babyId":42,
            "completionClientUuid":"old-session",
            "leftAccumMs":60000,
            "sessionStartedAt":1700000000000
        }""".trimIndent()

        val restored = TimerState.fromJson(
            raw = raw,
            nowElapsed = 120_000L,
            nowWall = 1_700_000_010_000L,
            nowBootCount = 12L,
        )

        assertEquals(TimerState(), restored)
    }

    @Test
    fun restore_rejectsNonCurrentSchemaVersion() {
        val raw = TimerState(
            babyId = 42L,
            completionClientUuid = "timer-session-42",
            leftAccumMs = 60_000L,
        ).toJson(savedElapsed = 120_000L, savedWall = 1_700_000_010_000L)
            .replace("\"schemaVersion\":1", "\"schemaVersion\":2")

        assertEquals(
            TimerState(),
            TimerState.fromJson(
                raw = raw,
                nowElapsed = 120_000L,
                nowWall = 1_700_000_010_000L,
                nowBootCount = 12L,
            ),
        )
    }

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
    fun restore_withoutBootIdentityUsesTheAvailableClockPair() {
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
    fun restore_usesWallWhenBootIdentityBecomesAvailable() {
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
    fun restore_usesWallWhenPersistedBootIdentityBecomesUnavailable() {
        assertEquals(
            90_000L,
            restoredRunningDelta(
                savedElapsed = 120_000L,
                savedWall = 1_700_000_000_000L,
                nowElapsed = 900_000L,
                nowWall = 1_700_000_090_000L,
                savedBootCount = 12L,
                nowBootCount = null,
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
    fun roundTrip_preservesCarePlanIdBinding() {
        val state = TimerState(
            babyId = 7L,
            completionClientUuid = "uuid-1",
            carePlanId = 42L,
            leftAccumMs = 1_000L,
            sessionStartedAt = 1_700_000_000_000L,
            order = "L",
        )
        val raw = state.toJson(
            savedElapsed = 10_000L,
            savedWall = 1_700_000_000_000L,
            savedBootCount = 3L,
        )
        val restored = TimerState.fromJson(
            raw = raw,
            nowElapsed = 10_000L,
            nowWall = 1_700_000_000_000L,
            nowBootCount = 3L,
        )
        assertEquals(42L, restored.carePlanId)
        assertEquals(7L, restored.babyId)
        assertEquals("uuid-1", restored.completionClientUuid)
        assertTrue(restored.hasTimerData())
    }

    @Test
    fun roundTrip_missingCarePlanIdStaysNull() {
        val state = TimerState(
            babyId = 1L,
            completionClientUuid = "timer-session-1",
            leftAccumMs = 500L,
        )
        val restored = TimerState.fromJson(
            state.toJson(
                savedElapsed = 1_000L,
                savedWall = 1_700_000_000_000L,
                savedBootCount = 1L,
            ),
            nowElapsed = 1_000L,
            nowWall = 1_700_000_000_000L,
            nowBootCount = 1L,
        )
        assertNull(restored.carePlanId)
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

    @Test
    fun snapshotRoundTripPreservesBoundBaby() {
        val original = TimerState(
            babyId = 42L,
            completionClientUuid = "timer-session-42",
            leftAccumMs = 60_000L,
            sessionStartedAt = 1_700_000_000_000L,
            order = "L",
        )

        val restored = TimerState.fromJson(
            raw = original.toJson(
                savedElapsed = 120_000L,
                savedWall = 1_700_000_010_000L,
                savedBootCount = 12L,
            ),
            nowElapsed = 120_000L,
            nowWall = 1_700_000_010_000L,
            nowBootCount = 12L,
        )

        assertEquals(42L, restored.babyId)
        assertEquals("timer-session-42", restored.completionClientUuid)
        assertEquals(60_000L, restored.leftAccumMs)
        assertEquals("L", restored.order)
    }

    @Test
    fun currentActiveSnapshotWithoutCompletionIdIsRejected() {
        val raw = TimerState(
            babyId = 42L,
            leftAccumMs = 60_000L,
            sessionStartedAt = 1_700_000_000_000L,
        ).toJson(
            savedElapsed = 120_000L,
            savedWall = 1_700_000_010_000L,
            savedBootCount = 12L,
        )

        assertEquals(
            TimerState(),
            TimerState.fromJson(
                raw = raw,
                nowElapsed = 120_000L,
                nowWall = 1_700_000_010_000L,
                nowBootCount = 12L,
            ),
        )
    }

    @Test
    fun foregroundNotificationAdvancesOnlyTheRunningSide() {
        val snapshot = NursingNotificationSnapshot(
            leftMs = 15_000L,
            rightMs = 20_000L,
            leftRunning = true,
            rightRunning = false,
            capturedElapsed = 100_000L,
        )

        assertEquals(17_500L to 20_000L, snapshot.at(102_500L))
        assertEquals(15_000L to 20_000L, snapshot.at(99_000L))
    }
}
