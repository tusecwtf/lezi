package com.lezi.babylog.feature.timer

/** Shared m:ss formatter for the nursing timer UI and ongoing notification. */
internal fun formatTimerMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
