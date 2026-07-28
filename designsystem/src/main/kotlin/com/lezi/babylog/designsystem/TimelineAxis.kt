package com.lezi.babylog.designsystem

import kotlin.math.roundToInt

/**
 * Shared geometry for the record-page day rail.
 *
 * Content is a continuous multi-day axis in **minutes from the window origin**
 * (D−1 00:00 when using the three-day window). The drawable canvas maps a
 * **viewport** slice of that content; horizontal pan only changes the viewport
 * start (clamped), never the content origin or selected day D.
 */
object TimelineAxis {
    const val MINUTES_PER_DAY: Int = 24 * 60

    /** D−1 | D | D+1 inclusive span. */
    const val THREE_DAY_CONTENT_DAYS: Int = 3
    const val THREE_DAY_CONTENT_MINUTES: Int = THREE_DAY_CONTENT_DAYS * MINUTES_PER_DAY

    /**
     * Minutes of each neighbor day shown beside primary day D in the default
     * (non-gesture) viewport. Shared so dimming, now-centering, and pan clamps
     * do not invent divergent peeks.
     */
    const val NEIGHBOR_PEEK_MINUTES: Int = 90

    /** Default visible span: full primary day plus left/right neighbor peeks. */
    const val DEFAULT_VIEWPORT_MINUTES: Int =
        MINUTES_PER_DAY + 2 * NEIGHBOR_PEEK_MINUTES

    /** Content-minute where primary day D starts (after a full D−1). */
    const val PRIMARY_DAY_START_MINUTES: Int = MINUTES_PER_DAY

    /** Content-minute where primary day D ends / D+1 starts. */
    const val PRIMARY_DAY_END_MINUTES: Int = PRIMARY_DAY_START_MINUTES + MINUTES_PER_DAY

    /**
     * Default viewport left edge for non-today days: slightly before D 00:00
     * so both neighbors peek. Today re-centers on wall-clock now via
     * [todayCenteredViewportStartMinutes].
     */
    fun defaultViewportStartMinutes(
        peekMinutes: Int = NEIGHBOR_PEEK_MINUTES,
    ): Int = (PRIMARY_DAY_START_MINUTES - peekMinutes).coerceAtLeast(0)

    fun defaultViewportDurationMinutes(
        peekMinutes: Int = NEIGHBOR_PEEK_MINUTES,
    ): Int = MINUTES_PER_DAY + 2 * peekMinutes

    /** Clamp a viewport start so the full [viewportDurationMinutes] stays inside content. */
    fun clampViewportStart(
        startMinutes: Int,
        viewportDurationMinutes: Int = DEFAULT_VIEWPORT_MINUTES,
        contentDurationMinutes: Int = THREE_DAY_CONTENT_MINUTES,
    ): Int {
        val maxStart = (contentDurationMinutes - viewportDurationMinutes).coerceAtLeast(0)
        return startMinutes.coerceIn(0, maxStart)
    }

    /**
     * True when [contentMinute] falls on primary day D
     * `[PRIMARY_DAY_START_MINUTES, PRIMARY_DAY_END_MINUTES)`.
     * Neighbor peeks (D−1 / D+1) return false so marks can dim.
     */
    fun isPrimaryDayContentMinute(contentMinute: Int): Boolean =
        contentMinute in PRIMARY_DAY_START_MINUTES until PRIMARY_DAY_END_MINUTES

    /**
     * Unlabeled midnight day-boundary content minutes inside the content axis
     * (D 00:00 and D+1 00:00 for the default 72h window). End-of-window
     * midnights are omitted — they are edges, not interior separators.
     */
    fun dayBoundaryContentMinutes(
        contentDurationMinutes: Int = THREE_DAY_CONTENT_MINUTES,
    ): List<Int> = buildList {
        var m = MINUTES_PER_DAY
        while (m < contentDurationMinutes) {
            add(m)
            m += MINUTES_PER_DAY
        }
    }

    /**
     * Map wall-clock [nowMs] into content minutes for a window starting at
     * [windowStartMs]. Returns null when now is outside
     * `[windowStartMs, windowEndMs)` so callers hide the now line.
     */
    fun contentMinuteIfInWindow(
        nowMs: Long,
        windowStartMs: Long,
        windowEndMs: Long,
    ): Int? {
        if (nowMs < windowStartMs || nowMs >= windowEndMs) return null
        return ((nowMs - windowStartMs) / 60_000L).toInt()
    }

    /**
     * Viewport start that centers [nowContentMinute] in the visible span,
     * clamped so the full viewport stays inside content (no blank outside).
     */
    fun todayCenteredViewportStartMinutes(
        nowContentMinute: Int,
        viewportDurationMinutes: Int = DEFAULT_VIEWPORT_MINUTES,
        contentDurationMinutes: Int = THREE_DAY_CONTENT_MINUTES,
    ): Int {
        val idealStart = nowContentMinute - viewportDurationMinutes / 2
        return clampViewportStart(
            startMinutes = idealStart,
            viewportDurationMinutes = viewportDurationMinutes,
            contentDurationMinutes = contentDurationMinutes,
        )
    }

    /**
     * Map a content-axis minute into a pixel along an axis of [axisLengthPx]
     * that shows [viewportDurationMinutes] starting at [viewportStartMinutes].
     */
    fun contentMinuteToAxisPx(
        contentMinute: Int,
        axisLengthPx: Float,
        viewportStartMinutes: Int,
        viewportDurationMinutes: Int,
    ): Float {
        val duration = viewportDurationMinutes.coerceAtLeast(1).toFloat()
        return (contentMinute - viewportStartMinutes) / duration * axisLengthPx
    }

    /**
     * Inverse of [contentMinuteToAxisPx]: pixel → content minute (not clamped).
     */
    fun axisPxToContentMinute(
        axisPx: Float,
        axisLengthPx: Float,
        viewportStartMinutes: Int,
        viewportDurationMinutes: Int,
    ): Int {
        val width = axisLengthPx.coerceAtLeast(1f)
        val duration = viewportDurationMinutes.coerceAtLeast(1)
        return viewportStartMinutes + ((axisPx / width) * duration).toInt()
    }

    /**
     * Viewport start after a horizontal finger pan of [deltaPx] (positive =
     * finger moved right). Content follows the finger: drag right reveals
     * earlier times (start decreases). Result is clamped so the full viewport
     * stays inside the content window — never leaves the 72h span.
     *
     * Prefer accumulating [deltaPx] from drag origin and calling this each
     * frame so clamp stays stable under recomposition.
     */
    fun panViewportStart(
        currentStartMinutes: Int,
        deltaPx: Float,
        axisLengthPx: Float,
        viewportDurationMinutes: Int = DEFAULT_VIEWPORT_MINUTES,
        contentDurationMinutes: Int = THREE_DAY_CONTENT_MINUTES,
    ): Int {
        val width = axisLengthPx.coerceAtLeast(1f)
        val duration = viewportDurationMinutes.coerceAtLeast(1).toFloat()
        // Finger right (+) → start decreases (earlier content enters from left).
        val deltaMinutes = -deltaPx / width * duration
        val roundedDelta = deltaMinutes.roundToInt()
        return clampViewportStart(
            startMinutes = currentStartMinutes + roundedDelta,
            viewportDurationMinutes = viewportDurationMinutes,
            contentDurationMinutes = contentDurationMinutes,
        )
    }
}
