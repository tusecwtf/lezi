package com.lezi.babylog.feature.timer

/**
 * Durable transitions for the notification's own 「暂停/继续」 actions (0.5.4 ticket 12).
 *
 * The service is the writer: it freezes the running side at tap time so the durable
 * session, the notification, and any live [TimerViewModel] all agree without the app
 * being open. The app-side pause path (which stops the FGS) is deliberately unchanged;
 * a notification-side pause keeps the foreground service alive so 「已暂停」 and
 * 「继续」 stay actionable on the lock screen.
 */

/**
 * Freeze the currently counting side into a PAUSED durable session.
 * Returns null when no single side is running (stale/duplicate tap) — callers keep the
 * previous notification instead of pretending a pause happened.
 */
internal fun TimerState.pausedByNotificationAction(frozenAtElapsed: Long): TimerState? {
    if (!leftRunning && !rightRunning) return null
    if (leftRunning && rightRunning) return null
    val side = if (leftRunning) "L" else "R"
    return copy(
        leftRunning = false,
        rightRunning = false,
        leftAccumMs = leftMs(frozenAtElapsed),
        rightAccumMs = rightMs(frozenAtElapsed),
        leftStartedElapsed = null,
        rightStartedElapsed = null,
        lastSide = side,
        serviceState = TimerServiceState.PAUSED,
        requestedSide = null,
        serviceFailure = null,
    )
}

/**
 * Restart the side that was paused from the notification, anchored at [startedAtElapsed]
 * so the app's `leftMs/rightMs` clock and the service snapshot share one basis.
 * Returns null unless the durable session is the PAUSED state this action produced.
 */
internal fun TimerState.resumedByNotificationAction(
    side: String,
    startedAtElapsed: Long,
): TimerState? {
    if (leftRunning || rightRunning) return null
    if (serviceState != TimerServiceState.PAUSED) return null
    val restarted = when (side) {
        "L" -> copy(leftRunning = true, leftStartedElapsed = startedAtElapsed, lastSide = "L")
        "R" -> copy(rightRunning = true, rightStartedElapsed = startedAtElapsed, lastSide = "R")
        else -> return null
    }
    return restarted.copy(
        serviceState = TimerServiceState.RUNNING,
        requestedSide = null,
        serviceFailure = null,
    )
}
