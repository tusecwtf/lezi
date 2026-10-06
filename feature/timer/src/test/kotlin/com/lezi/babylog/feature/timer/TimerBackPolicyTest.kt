package com.lezi.babylog.feature.timer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimerBackPolicyTest {
    @Test
    fun runningTimerLeavesIntentionallyInBackground() {
        val running = TimerState(
            completionClientUuid = "session",
            babyId = 1L,
            leftRunning = true,
            leftStartedElapsed = 1L,
            sessionStartedAt = 1L,
        )
        assertThat(timerBackDecision(running, TimerCompletionUiState()))
            .isEqualTo(TimerBackDecision.LeaveRunningInBackground)
    }

    @Test
    fun accumulatedPausedTimerLeavesPausedWithoutClaimingBackgroundExecution() {
        val accumulated = TimerState(
            completionClientUuid = "session",
            babyId = 1L,
            leftRunning = false,
            leftAccumMs = 60_000L,
            sessionStartedAt = 1L,
        )

        assertThat(timerBackDecision(accumulated, TimerCompletionUiState()))
            .isEqualTo(TimerBackDecision.LeavePaused)
    }

    @Test
    fun savingAndNextFeedBlockBothBackEntrancesUntilDurableWorkFinishes() {
        assertThat(
            timerBackDecision(
                TimerState(),
                TimerCompletionUiState(draft = sampleTimerDraft(), saving = true),
            ),
        ).isEqualTo(TimerBackDecision.BlockSaving)
        assertThat(
            timerBackDecision(
                TimerState(),
                TimerCompletionUiState(
                    pendingNextFeed = TimerPendingNextFeed(babyId = 1L, suggestedAt = 2L),
                ),
            ),
        ).isEqualTo(TimerBackDecision.BlockNextFeed)
    }

    @Test
    fun emptyTimerLeavesNormally() {
        assertThat(timerBackDecision(TimerState(), TimerCompletionUiState()))
            .isEqualTo(TimerBackDecision.Leave)
    }
}

private fun sampleTimerDraft() = NursingCompletionDraft(
    leftMinutes = "1",
    rightMinutes = "0",
    order = "L",
    amountMl = "",
    note = "",
    startedAt = 1L,
    endedAt = 2L,
    capturedAt = 2L,
)
