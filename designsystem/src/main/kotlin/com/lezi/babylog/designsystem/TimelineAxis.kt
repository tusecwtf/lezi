package com.lezi.babylog.designsystem

/**
 * Pixel mapping for the record-page rail.
 *
 * The rail has no calendar, selected day, or work-axis origin. Callers pass an
 * absolute viewport `[start, start+duration)` and instants; pixels are
 * `(t − start) / duration × width`.
 */
object TimelineAxis {
    /** Fallback visible span for previews that omit an explicit duration. */
    const val DEFAULT_VIEWPORT_DURATION_MS: Long = 24L * 60 * 60 * 1000

    /**
     * Map absolute [instantMs] onto an axis of [axisLengthPx] that shows
     * [viewportDurationMs] starting at [viewportStartMs].
     */
    fun instantToAxisPx(
        instantMs: Long,
        axisLengthPx: Float,
        viewportStartMs: Long,
        viewportDurationMs: Long,
    ): Float {
        val duration = viewportDurationMs.coerceAtLeast(1).toFloat()
        return (instantMs - viewportStartMs).toFloat() / duration * axisLengthPx
    }

    /**
     * Inverse of [instantToAxisPx]: pixel → absolute instant (not clamped).
     */
    fun axisPxToInstant(
        axisPx: Float,
        axisLengthPx: Float,
        viewportStartMs: Long,
        viewportDurationMs: Long,
    ): Long {
        val width = axisLengthPx.coerceAtLeast(1f)
        val duration = viewportDurationMs.coerceAtLeast(1)
        return viewportStartMs + ((axisPx / width) * duration).toLong()
    }
}

/**
 * Thin rail overlay: midnight separators and the selected-day range used to dim
 * neighbor marks. Does not describe a content window or work-axis origin.
 */
data class TimelineWindowGeometry(
    val dayBoundariesMs: List<Long> = emptyList(),
    val primaryRangeMs: LongRange = LongRange.EMPTY,
) {

    /** True when [instantMs] is on the selected day; an empty range dims nobody. */
    fun isPrimaryInstant(instantMs: Long): Boolean =
        primaryRangeMs.isEmpty() || instantMs in primaryRangeMs
}

/** Neighbor day-boundary date labels use the same soft alpha as dimmed marks. */
internal const val NEIGHBOR_DAY_LABEL_ALPHA = 0.42f
