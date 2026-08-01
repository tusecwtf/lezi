package com.lezi.babylog.feature.log.timeline
import com.lezi.babylog.designsystem.TimelineWindowGeometry
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToLong
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

private const val MILLIS_PER_MINUTE = 60_000L
private const val DEFAULT_NEIGHBOR_PEEK_MINUTES = 90L

/** A half-open range on the elapsed-time axis. */
internal data class TimelineAxisOffsetRange(
    val startOffsetMs: Long,
    val endExclusiveOffsetMs: Long,
) {
    init {
        require(startOffsetMs >= 0L) { "axis range must start at or after zero" }
        require(endExclusiveOffsetMs >= startOffsetMs) {
            "axis range end must not precede its start"
        }
    }

    val durationMs: Long
        get() = endExclusiveOffsetMs - startOffsetMs

    operator fun contains(offsetMs: Long): Boolean =
        offsetMs >= startOffsetMs && offsetMs < endExclusiveOffsetMs
}

/** One local-clock hour tick mapped onto the elapsed-time axis. */
internal data class ThreeDayTimelineHourTick(
    val instantMs: Long,
    val offsetMs: Long,
    val localDateTime: LocalDateTime,
    val zoneOffset: ZoneOffset,
    val label: String,
)

/**
 * DST-safe geometry for the three local dates D-1, D, and D+1.
 *
 * Calendar construction happens exactly once, from four consecutive local
 * midnights. Everything else uses elapsed milliseconds from [windowStartMs],
 * so drawing, hit testing, interval clipping, and panning can share one axis.
 */
internal class ThreeDayTimelineAxis(
    val selectedDay: LocalDate,
    val zoneId: ZoneId,
) {
    val localMidnightEpochMillis: List<Long> = listOf(
        selectedDay.minusDays(1),
        selectedDay,
        selectedDay.plusDays(1),
        selectedDay.plusDays(2),
    ).map { day -> day.atStartOfDay(zoneId).toInstant().toEpochMilli() }

    val windowStartMs: Long = localMidnightEpochMillis[0]
    val primaryStartMs: Long = localMidnightEpochMillis[1]
    val primaryEndExclusiveMs: Long = localMidnightEpochMillis[2]
    val windowEndExclusiveMs: Long = localMidnightEpochMillis[3]

    init {
        require(localMidnightEpochMillis.zipWithNext().all { (start, end) -> end > start }) {
            "local midnight instants must be strictly increasing"
        }
    }

    val contentDurationMs: Long = windowEndExclusiveMs - windowStartMs
    val contentDurationMinutes: Int = (contentDurationMs / MILLIS_PER_MINUTE).toInt()

    val localMidnightOffsetsMs: List<Long> =
        localMidnightEpochMillis.map { instantMs -> instantMs - windowStartMs }

    val interiorDayBoundaryOffsetsMs: List<Long> = localMidnightOffsetsMs.subList(1, 3)

    val localMidnightOffsetsMinutes: List<Int> =
        localMidnightOffsetsMs.map { offsetMs -> (offsetMs / MILLIS_PER_MINUTE).toInt() }

    val primaryStartMinutes: Int = localMidnightOffsetsMinutes[1]
    val primaryEndExclusiveMinutes: Int = localMidnightOffsetsMinutes[2]

    val windowGeometry: TimelineWindowGeometry = TimelineWindowGeometry(
        contentDurationMinutes = contentDurationMinutes,
        primaryStartMinutes = primaryStartMinutes,
        primaryEndExclusiveMinutes = primaryEndExclusiveMinutes,
        dayBoundaryMinutes = localMidnightOffsetsMinutes.subList(1, 3),
    )

    val contentRange: TimelineAxisOffsetRange = TimelineAxisOffsetRange(
        startOffsetMs = 0L,
        endExclusiveOffsetMs = contentDurationMs,
    )

    val primaryRange: TimelineAxisOffsetRange = TimelineAxisOffsetRange(
        startOffsetMs = primaryStartMs - windowStartMs,
        endExclusiveOffsetMs = primaryEndExclusiveMs - windowStartMs,
    )

    val defaultViewportDurationMs: Long =
        (primaryRange.durationMs + 2L * DEFAULT_NEIGHBOR_PEEK_MINUTES * MILLIS_PER_MINUTE)
            .coerceAtMost(contentDurationMs)

    val defaultViewportStartMs: Long
        get() = clampViewportStartMs(
            requestedStartMs = primaryRange.startOffsetMs -
                DEFAULT_NEIGHBOR_PEEK_MINUTES * MILLIS_PER_MINUTE,
            viewportDurationMs = defaultViewportDurationMs,
        )

    val defaultViewportDurationMinutes: Int
        get() = (defaultViewportDurationMs / MILLIS_PER_MINUTE).toInt()

    val defaultViewportStartMinutes: Int
        get() = (defaultViewportStartMs / MILLIS_PER_MINUTE).toInt()

    /** Maps an instant inside [windowStartMs, windowEndExclusiveMs) to the axis. */
    fun instantToOffsetMs(instantMs: Long): Long? =
        if (instantMs >= windowStartMs && instantMs < windowEndExclusiveMs) {
            instantMs - windowStartMs
        } else {
            null
        }

    fun instantToContentMinute(instantMs: Long): Int? =
        instantToOffsetMs(instantMs)?.let { offsetMs ->
            (offsetMs / MILLIS_PER_MINUTE).toInt()
        }

    /** Maps an axis point inside [contentRange] back to its exact instant. */
    fun offsetMsToInstant(offsetMs: Long): Long? =
        if (offsetMs in contentRange) windowStartMs + offsetMs else null

    /**
     * Maps a local date-time to every valid axis position.
     *
     * A DST gap returns no positions. A repeated local time returns both
     * positions, ordered by instant, instead of silently selecting one offset.
     */
    fun localDateTimeToOffsetsMs(localDateTime: LocalDateTime): List<Long> =
        zoneId.rules.getValidOffsets(localDateTime)
            .map { offset -> localDateTime.toInstant(offset).toEpochMilli() }
            .sorted()
            .mapNotNull(::instantToOffsetMs)

    /** Clips an absolute interval to the window and preserves an exclusive end boundary. */
    fun clipIntervalToOffsets(
        startInstantMs: Long,
        endExclusiveInstantMs: Long,
    ): TimelineAxisOffsetRange? {
        if (endExclusiveInstantMs <= startInstantMs) return null
        val clippedStart = maxOf(startInstantMs, windowStartMs)
        val clippedEnd = minOf(endExclusiveInstantMs, windowEndExclusiveMs)
        if (clippedEnd <= clippedStart) return null
        return TimelineAxisOffsetRange(
            startOffsetMs = clippedStart - windowStartMs,
            endExclusiveOffsetMs = clippedEnd - windowStartMs,
        )
    }

    fun isPrimaryOffset(offsetMs: Long): Boolean = offsetMs in primaryRange

    fun clampViewportStartMs(
        requestedStartMs: Long,
        viewportDurationMs: Long = defaultViewportDurationMs,
    ): Long {
        val duration = normalizedViewportDurationMs(viewportDurationMs)
        return requestedStartMs.coerceIn(0L, contentDurationMs - duration)
    }

    fun centeredViewportStartMs(
        focusOffsetMs: Long,
        viewportDurationMs: Long = defaultViewportDurationMs,
    ): Long {
        val duration = normalizedViewportDurationMs(viewportDurationMs)
        val focus = focusOffsetMs.coerceIn(0L, contentDurationMs)
        return clampViewportStartMs(focus - duration / 2L, duration)
    }

    fun centeredViewportStartMinutes(
        focusContentMinute: Int,
        viewportDurationMinutes: Int = defaultViewportDurationMinutes,
    ): Int {
        return (centeredViewportStartMs(
            focusOffsetMs = focusContentMinute.toLong() * MILLIS_PER_MINUTE,
            viewportDurationMs = viewportDurationMinutes.toLong() * MILLIS_PER_MINUTE,
        ) / MILLIS_PER_MINUTE).toInt()
    }

    /** Positive finger movement reveals earlier content; negative reveals later content. */
    fun panViewportStartMs(
        currentStartMs: Long,
        totalDeltaPx: Double,
        axisLengthPx: Double,
        viewportDurationMs: Long = defaultViewportDurationMs,
    ): Long {
        val duration = normalizedViewportDurationMs(viewportDurationMs)
        val current = clampViewportStartMs(currentStartMs, duration)
        if (!totalDeltaPx.isFinite() || !axisLengthPx.isFinite() || axisLengthPx <= 0.0) {
            return current
        }
        val deltaMs = totalDeltaPx / axisLengthPx * duration.toDouble()
        val maxStart = contentDurationMs - duration
        return (current.toDouble() - deltaMs)
            .coerceIn(0.0, maxStart.toDouble())
            .roundToLong()
    }

    fun panViewportStartMinutes(
        currentStartMinutes: Int,
        totalDeltaPx: Float,
        axisLengthPx: Float,
        viewportDurationMinutes: Int = defaultViewportDurationMinutes,
    ): Int {
        return (panViewportStartMs(
            currentStartMs = currentStartMinutes.toLong() * MILLIS_PER_MINUTE,
            totalDeltaPx = totalDeltaPx.toDouble(),
            axisLengthPx = axisLengthPx.toDouble(),
            viewportDurationMs = viewportDurationMinutes.toLong() * MILLIS_PER_MINUTE,
        ) / MILLIS_PER_MINUTE).toInt()
    }

    /**
     * Returns local-clock hour ticks. Invalid gap hours are absent; repeated
     * hours produce two ticks whose labels include their UTC offsets.
     */
    fun hourTicks(stepHours: Int = 6): List<ThreeDayTimelineHourTick> {
        require(stepHours in 1..24) { "hour tick step must be between 1 and 24" }
        val candidates = buildList {
            for (dayOffset in -1L..1L) {
                val date = selectedDay.plusDays(dayOffset)
                for (hour in 0..23 step stepHours) {
                    val localDateTime = date.atTime(hour, 0)
                    zoneId.rules.getValidOffsets(localDateTime).forEach { zoneOffset ->
                        val instantMs = localDateTime.toInstant(zoneOffset).toEpochMilli()
                        val offsetMs = instantToOffsetMs(instantMs) ?: return@forEach
                        add(
                            HourTickCandidate(
                                instantMs = instantMs,
                                offsetMs = offsetMs,
                                localDateTime = localDateTime,
                                zoneOffset = zoneOffset,
                            ),
                        )
                    }
                }
            }
        }.sortedBy(HourTickCandidate::instantMs)
        val occurrenceCount = candidates.groupingBy(HourTickCandidate::localDateTime).eachCount()
        return candidates.map { candidate ->
            val clockLabel = candidate.localDateTime.hour.toString().padStart(2, '0') + ":00"
            val label = if (occurrenceCount.getValue(candidate.localDateTime) > 1) {
                "$clockLabel ${candidate.zoneOffset.id}"
            } else {
                clockLabel
            }
            ThreeDayTimelineHourTick(
                instantMs = candidate.instantMs,
                offsetMs = candidate.offsetMs,
                localDateTime = candidate.localDateTime,
                zoneOffset = candidate.zoneOffset,
                label = label,
            )
        }
    }

    fun hourLabels(stepHours: Int = 6): List<Pair<Int, String>> =
        hourTicks(stepHours).map { tick ->
            (tick.offsetMs / MILLIS_PER_MINUTE).toInt() to tick.label
        }

    private fun normalizedViewportDurationMs(requestedDurationMs: Long): Long {
        require(requestedDurationMs > 0L) { "viewport duration must be positive" }
        return requestedDurationMs.coerceAtMost(contentDurationMs)
    }
}

private data class HourTickCandidate(
    val instantMs: Long,
    val offsetMs: Long,
    val localDateTime: LocalDateTime,
    val zoneOffset: ZoneOffset,
)
