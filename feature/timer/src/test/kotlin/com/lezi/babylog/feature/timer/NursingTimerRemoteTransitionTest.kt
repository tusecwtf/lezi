package com.lezi.babylog.feature.timer

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/**
 * Durable transitions behind the notification's own 「暂停/继续」 actions (ticket 12).
 * The service persists these without the app open; a live ViewModel mirrors them via
 * the runtime epoch, so the frozen accum and resume anchor must be exact.
 */
class NursingTimerRemoteTransitionTest {
    @After
    fun tearDown() {
        NursingTimerServiceRuntime.clear()
    }

    private fun runningLeft(
        accumMs: Long = 1_000L,
        startedAt: Long = 5_000L,
    ) = TimerState(
        completionClientUuid = "session-token",
        leftRunning = true,
        leftAccumMs = accumMs,
        leftStartedElapsed = startedAt,
        serviceState = TimerServiceState.RUNNING,
        lastSide = "L",
    )

    @Test
    fun pauseFreezesRunningSideAtFrozenBasis() {
        val paused = runningLeft(accumMs = 1_000L, startedAt = 5_000L)
            .pausedByNotificationAction(frozenAtElapsed = 7_000L)!!
        assertThat(paused.leftRunning).isFalse()
        assertThat(paused.rightRunning).isFalse()
        assertThat(paused.leftAccumMs).isEqualTo(3_000L)
        assertThat(paused.rightAccumMs).isEqualTo(0L)
        assertThat(paused.leftStartedElapsed).isNull()
        assertThat(paused.rightStartedElapsed).isNull()
        assertThat(paused.serviceState).isEqualTo(TimerServiceState.PAUSED)
        assertThat(paused.requestedSide).isNull()
        assertThat(paused.serviceFailure).isNull()
        assertThat(paused.lastSide).isEqualTo("L")
        // Session identity survives so completion/继续 flows keep working.
        assertThat(paused.completionClientUuid).isEqualTo("session-token")
    }

    @Test
    fun pauseKeepsOppositeAccumAndSessionFields() {
        val paused = TimerState(
            completionClientUuid = "session-token",
            rightRunning = true,
            rightAccumMs = 2_500L,
            rightStartedElapsed = 10_000L,
            leftAccumMs = 4_000L,
            sessionStartedAt = 1_000L,
            order = "LR",
            serviceState = TimerServiceState.RUNNING,
        ).pausedByNotificationAction(frozenAtElapsed = 12_000L)!!
        assertThat(paused.rightAccumMs).isEqualTo(4_500L)
        assertThat(paused.leftAccumMs).isEqualTo(4_000L)
        assertThat(paused.lastSide).isEqualTo("R")
        assertThat(paused.sessionStartedAt).isEqualTo(1_000L)
        assertThat(paused.order).isEqualTo("LR")
    }

    @Test
    fun pauseIsRejectedWithoutExactlyOneRunningSide() {
        val pausedState = TimerState(
            completionClientUuid = "session-token",
            serviceState = TimerServiceState.PAUSED,
        )
        assertThat(pausedState.pausedByNotificationAction(frozenAtElapsed = 1L)).isNull()
        // Defensive both-running guard (app transitions never produce this).
        assertThat(
            runningLeft().copy(rightRunning = true, rightStartedElapsed = 1L)
                .pausedByNotificationAction(frozenAtElapsed = 2L),
        ).isNull()
    }

    @Test
    fun continueRestartsPausedSideAnchoredAtNow() {
        val paused = TimerState(
            completionClientUuid = "session-token",
            leftAccumMs = 3_000L,
            rightAccumMs = 1_000L,
            lastSide = "L",
            serviceState = TimerServiceState.PAUSED,
        )
        val resumed = paused.resumedByNotificationAction(
            side = "L",
            startedAtElapsed = 9_000L,
        )!!
        assertThat(resumed.leftRunning).isTrue()
        assertThat(resumed.rightRunning).isFalse()
        assertThat(resumed.leftStartedElapsed).isEqualTo(9_000L)
        assertThat(resumed.leftMs(nowElapsed = 11_000L)).isEqualTo(5_000L)
        assertThat(resumed.rightAccumMs).isEqualTo(1_000L)
        assertThat(resumed.serviceState).isEqualTo(TimerServiceState.RUNNING)
        assertThat(resumed.requestedSide).isNull()
        assertThat(resumed.serviceFailure).isNull()
        assertThat(resumed.lastSide).isEqualTo("L")
        assertThat(resumed.completionClientUuid).isEqualTo("session-token")
    }

    @Test
    fun continueRestartsRightSideToo() {
        val resumed = TimerState(
            completionClientUuid = "session-token",
            leftAccumMs = 1_000L,
            serviceState = TimerServiceState.PAUSED,
        ).resumedByNotificationAction(side = "R", startedAtElapsed = 4_000L)!!
        assertThat(resumed.rightRunning).isTrue()
        assertThat(resumed.leftRunning).isFalse()
        assertThat(resumed.rightStartedElapsed).isEqualTo(4_000L)
        assertThat(resumed.lastSide).isEqualTo("R")
    }

    @Test
    fun continueIsRejectedForUnknownSideNonPausedOrRunningSessions() {
        val paused = TimerState(
            completionClientUuid = "session-token",
            serviceState = TimerServiceState.PAUSED,
        )
        assertThat(paused.resumedByNotificationAction(side = "X", startedAtElapsed = 1L)).isNull()
        val recoverable = paused.copy(serviceState = TimerServiceState.RECOVERABLE)
        assertThat(
            recoverable.resumedByNotificationAction(side = "L", startedAtElapsed = 1L),
        ).isNull()
        val running = paused.copy(
            leftRunning = true,
            leftStartedElapsed = 1L,
            serviceState = TimerServiceState.RUNNING,
        )
        assertThat(running.resumedByNotificationAction(side = "L", startedAtElapsed = 2L)).isNull()
    }

    @Test
    fun remoteAdjustmentWitnessStartsAtZeroAndIncrements() {
        val before = NursingTimerServiceRuntime.remoteTimerAdjustments.value
        NursingTimerServiceRuntime.noteRemoteTimerAdjustment()
        NursingTimerServiceRuntime.noteRemoteTimerAdjustment()
        assertThat(NursingTimerServiceRuntime.remoteTimerAdjustments.value)
            .isEqualTo(before + 2L)
    }
}
