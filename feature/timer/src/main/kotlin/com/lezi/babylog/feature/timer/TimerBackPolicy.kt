package com.lezi.babylog.feature.timer

internal enum class TimerBackDecision {
    Leave,
    LeaveRunningInBackground,
    LeavePaused,
    BlockSaving,
    BlockNextFeed,
}

/**
 * Product choice B: back leaves a live timer running; only Discard destroys it.
 * A user-paused session stays paused and durable, with separate honest feedback.
 */
internal fun timerBackDecision(
    timer: TimerState,
    completion: TimerCompletionUiState,
): TimerBackDecision = when {
    completion.saving || completion.pendingExit -> TimerBackDecision.BlockSaving
    completion.pendingNextFeed != null -> TimerBackDecision.BlockNextFeed
    timer.leftRunning || timer.rightRunning -> TimerBackDecision.LeaveRunningInBackground
    timer.hasTimerData() -> TimerBackDecision.LeavePaused
    else -> TimerBackDecision.Leave
}
