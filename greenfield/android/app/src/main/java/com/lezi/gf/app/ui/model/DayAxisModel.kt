package com.lezi.gf.app.ui.model

/**
 * Continuous three-local-day timeline axis (PRD ui.md §3).
 * Window = [D−1, D+2) midnight; pan moves viewport only (does not change selected day D).
 */
object DayAxisModel {
    const val DAY_MS: Long = 86_400_000L
    /** Default viewport shows ~one day with slight neighbor peek (~26h). */
    const val DEFAULT_VIEWPORT_MS: Long = 26L * 3_600_000L

    data class Window(
        val selectedDayStartMs: Long,
        val windowStartMs: Long,
        val windowEndMs: Long,
        val viewportStartMs: Long,
        val viewportDurationMs: Long = DEFAULT_VIEWPORT_MS,
    ) {
        val viewportEndMs: Long get() = viewportStartMs + viewportDurationMs
        val windowDurationMs: Long get() = windowEndMs - windowStartMs
    }

    fun threeDayWindowStart(selectedDayStartMs: Long): Long = selectedDayStartMs - DAY_MS

    fun threeDayWindowEnd(selectedDayStartMs: Long): Long = selectedDayStartMs + 2 * DAY_MS

    /**
     * Default viewport: if selected day is "today" (contains now), center on now;
     * otherwise center on selected day noon. Always clamped into the 3-day window.
     */
    fun defaultViewportStart(
        selectedDayStartMs: Long,
        nowMs: Long,
        viewportDurationMs: Long = DEFAULT_VIEWPORT_MS,
    ): Long {
        val windowStart = threeDayWindowStart(selectedDayStartMs)
        val windowEnd = threeDayWindowEnd(selectedDayStartMs)
        val isToday = nowMs >= selectedDayStartMs && nowMs < selectedDayStartMs + DAY_MS
        val idealCenter = if (isToday) nowMs else selectedDayStartMs + DAY_MS / 2
        val idealStart = idealCenter - viewportDurationMs / 2
        return clampViewport(idealStart, windowStart, windowEnd, viewportDurationMs)
    }

    fun buildWindow(
        selectedDayStartMs: Long,
        nowMs: Long,
        viewportStartMs: Long? = null,
        viewportDurationMs: Long = DEFAULT_VIEWPORT_MS,
    ): Window {
        val windowStart = threeDayWindowStart(selectedDayStartMs)
        val windowEnd = threeDayWindowEnd(selectedDayStartMs)
        val start = viewportStartMs?.let {
            clampViewport(it, windowStart, windowEnd, viewportDurationMs)
        } ?: defaultViewportStart(selectedDayStartMs, nowMs, viewportDurationMs)
        return Window(
            selectedDayStartMs = selectedDayStartMs,
            windowStartMs = windowStart,
            windowEndMs = windowEnd,
            viewportStartMs = start,
            viewportDurationMs = viewportDurationMs,
        )
    }

    fun pan(window: Window, deltaMs: Long): Window {
        val next = clampViewport(
            window.viewportStartMs + deltaMs,
            window.windowStartMs,
            window.windowEndMs,
            window.viewportDurationMs,
        )
        return window.copy(viewportStartMs = next)
    }

    fun clampViewport(
        viewportStartMs: Long,
        windowStartMs: Long,
        windowEndMs: Long,
        viewportDurationMs: Long,
    ): Long {
        val maxStart = (windowEndMs - viewportDurationMs).coerceAtLeast(windowStartMs)
        return viewportStartMs.coerceIn(windowStartMs, maxStart)
    }

    /** Fraction 0..1 of mark timestamp within current viewport (may be outside). */
    fun fractionInViewport(timestampMs: Long, window: Window): Float {
        val rel = (timestampMs - window.viewportStartMs).toDouble() / window.viewportDurationMs
        return rel.toFloat()
    }

    fun midnightOffsetsInViewport(window: Window): List<Float> {
        val midnights = listOf(
            window.windowStartMs + DAY_MS, // D
            window.windowStartMs + 2 * DAY_MS, // D+1
        )
        return midnights.map { fractionInViewport(it, window) }
    }

    fun isNowVisible(nowMs: Long, window: Window): Boolean =
        nowMs >= window.viewportStartMs && nowMs < window.viewportEndMs

    fun dayLabelOffset(dayStartMs: Long, todayStartMs: Long): Int =
        ((dayStartMs - todayStartMs) / DAY_MS).toInt()
}
