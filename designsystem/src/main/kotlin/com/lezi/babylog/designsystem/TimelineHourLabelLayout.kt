package com.lezi.babylog.designsystem

import kotlin.math.roundToInt

/** One measured hour tick before collision filtering. */
internal data class TimelineHourLabelCandidate(
    val index: Int,
    val instantMs: Long,
    val label: String,
    val widthPx: Int,
)

/** A tick that is safe to paint; [index] maps back to the measured child. */
internal data class TimelineHourLabelPlacement(
    val index: Int,
    val instantMs: Long,
    val label: String,
    val xPx: Int,
    val widthPx: Int,
)

private val ElderHourClockPrefix = Regex("""^(\d{2}):00(?=\s|$)""")

/**
 * Elder rail keeps the hour, drops the `:00` minutes suffix so 2.1× ticks fit
 * a ~360dp phone. DST offset suffixes stay: `02:00 -04:00` → `02 -04:00`.
 */
internal fun compactElderHourLabel(label: String): String =
    label.replaceFirst(ElderHourClockPrefix, "$1")

/** Day labels plus the hour ticks that still fit after they take priority. */
internal data class TimelineAxisLabelLayout(
    val dayLabels: List<TimelineHourLabelPlacement>,
    val hourLabels: List<TimelineHourLabelPlacement>,
)

/**
 * Places hour ticks left-to-right and **skips** any label whose desired box
 * would sit closer than [minGapPx] to an already-placed neighbor. Product
 * elder rails pass 8px at density 1 (`LeziSpacing.Xs`). Edge clamp matches
 * the historical rail (right-edge tick right-aligns; nothing draws past the
 * track).
 */
internal fun placeTimelineHourLabels(
    labels: List<TimelineHourLabelCandidate>,
    trackWidthPx: Int,
    viewportStartMs: Long,
    viewportDurationMs: Long,
    minGapPx: Int = 0,
): List<TimelineHourLabelPlacement> =
    placeTimelineAxisLabels(
        dayLabels = emptyList(),
        hourLabels = labels,
        trackWidthPx = trackWidthPx,
        viewportStartMs = viewportStartMs,
        viewportDurationMs = viewportDurationMs,
        minGapPx = minGapPx,
    ).hourLabels

/**
 * Places preformatted day-boundary labels first, then hour ticks. An hour
 * tick whose box would sit closer than [minGapPx] to a day label (or to
 * another placed hour tick) is hidden; the day label stays.
 */
internal fun placeTimelineAxisLabels(
    dayLabels: List<TimelineHourLabelCandidate>,
    hourLabels: List<TimelineHourLabelCandidate>,
    trackWidthPx: Int,
    viewportStartMs: Long,
    viewportDurationMs: Long,
    minGapPx: Int = 0,
): TimelineAxisLabelLayout {
    val placedDays = placeLabelsLeftToRight(
        labels = dayLabels,
        trackWidthPx = trackWidthPx,
        viewportStartMs = viewportStartMs,
        viewportDurationMs = viewportDurationMs,
        minGapPx = minGapPx,
        reserved = emptyList(),
    )
    val placedHours = placeLabelsLeftToRight(
        labels = hourLabels,
        trackWidthPx = trackWidthPx,
        viewportStartMs = viewportStartMs,
        viewportDurationMs = viewportDurationMs,
        minGapPx = minGapPx,
        reserved = placedDays,
    )
    return TimelineAxisLabelLayout(
        dayLabels = placedDays,
        hourLabels = placedHours,
    )
}

private fun placeLabelsLeftToRight(
    labels: List<TimelineHourLabelCandidate>,
    trackWidthPx: Int,
    viewportStartMs: Long,
    viewportDurationMs: Long,
    minGapPx: Int,
    reserved: List<TimelineHourLabelPlacement>,
): List<TimelineHourLabelPlacement> {
    if (trackWidthPx <= 0) return emptyList()
    val duration = viewportDurationMs.coerceAtLeast(1).toFloat()
    val placed = ArrayList<TimelineHourLabelPlacement>(labels.size)
    val blockers = reserved.toMutableList()
    labels
        .sortedWith(compareBy<TimelineHourLabelCandidate> { it.instantMs }.thenBy { it.index })
        .forEach { candidate ->
            if (candidate.widthPx <= 0) return@forEach
            val fraction =
                (candidate.instantMs - viewportStartMs).toFloat() / duration
            if (fraction < -0.02f || fraction > 1.02f) return@forEach
            val xPx = desiredHourLabelXPx(fraction, candidate.widthPx, trackWidthPx)
            if (overlapsExisting(xPx, candidate.widthPx, blockers, minGapPx)) return@forEach
            val placement = TimelineHourLabelPlacement(
                index = candidate.index,
                instantMs = candidate.instantMs,
                label = candidate.label,
                xPx = xPx,
                widthPx = candidate.widthPx,
            )
            placed += placement
            blockers += placement
        }
    return placed
}

private fun overlapsExisting(
    xPx: Int,
    widthPx: Int,
    existing: List<TimelineHourLabelPlacement>,
    minGapPx: Int,
): Boolean {
    val left = xPx
    val right = xPx + widthPx
    return existing.any { other ->
        left < other.xPx + other.widthPx + minGapPx &&
            right + minGapPx > other.xPx
    }
}

internal fun desiredHourLabelXPx(
    fraction: Float,
    widthPx: Int,
    trackWidthPx: Int,
): Int {
    val clamped = fraction.coerceIn(0f, 1f)
    val x = trackWidthPx * clamped
    return (x - widthPx * clamped)
        .roundToInt()
        .coerceIn(0, (trackWidthPx - widthPx).coerceAtLeast(0))
}
