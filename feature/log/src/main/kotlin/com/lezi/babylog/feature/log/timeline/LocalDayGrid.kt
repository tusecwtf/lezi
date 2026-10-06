package com.lezi.babylog.feature.log.timeline

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A half-open absolute interval. */
internal data class TimelineInstantRange(
    val startMs: Long,
    val endExclusiveMs: Long,
) {
    init {
        require(endExclusiveMs >= startMs) {
            "instant range end must not precede its start"
        }
    }

    val durationMs: Long
        get() = endExclusiveMs - startMs

    operator fun contains(instantMs: Long): Boolean =
        instantMs >= startMs && instantMs < endExclusiveMs
}

/** One local-clock hour tick mapped onto an absolute instant. */
internal data class LocalDayGridHourTick(
    val instantMs: Long,
    val localDateTime: LocalDateTime,
    val zoneOffset: ZoneOffset,
    val label: String,
)

/**
 * DST-safe local-day geometry for an arbitrary absolute `[start, end)`.
 *
 * Calendar construction happens from the local midnights that cover the range.
 * Hour ticks skip DST gaps and emit two offset-labeled ticks for repeated
 * local times. There is no selected-day origin, peek, or three-day window.
 */
internal class LocalDayGrid(
    val startMs: Long,
    val endExclusiveMs: Long,
    val zoneId: ZoneId,
) {
    init {
        require(endExclusiveMs > startMs) { "grid range must be positive" }
    }

    val durationMs: Long = endExclusiveMs - startMs

    /**
     * Local midnights covering the range: the midnight at or before [startMs],
     * then every later midnight through the first midnight at or after
     * [endExclusiveMs].
     */
    val localMidnightEpochMillis: List<Long> = buildList {
        var day = Instant.ofEpochMilli(startMs).atZone(zoneId).toLocalDate()
        if (day.atStartOfDay(zoneId).toInstant().toEpochMilli() > startMs) {
            day = day.minusDays(1)
        }
        while (true) {
            val midnight = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
            add(midnight)
            if (midnight >= endExclusiveMs) break
            day = day.plusDays(1)
        }
    }

    /** Midnights strictly inside `(start, end)` — the lines the rail can draw. */
    val dayBoundariesMs: List<Long> =
        localMidnightEpochMillis.filter { it > startMs && it < endExclusiveMs }

    /**
     * Preformatted 「月/日」 for each [dayBoundariesMs] midnight, using this
     * grid's [zoneId]. The date is the local day that **starts** there
     * (`8/8`, `9/11` — no leading zeros).
     */
    fun dayBoundaryLabels(): List<Pair<Long, String>> =
        dayBoundariesMs.map { midnightMs -> midnightMs to monthDayLabel(midnightMs) }

    /** `M/d` in [zoneId] for the civil day that starts at [instantMs]. */
    fun monthDayLabel(instantMs: Long): String {
        val date = Instant.ofEpochMilli(instantMs).atZone(zoneId).toLocalDate()
        return "${date.monthValue}/${date.dayOfMonth}"
    }

    fun contains(instantMs: Long): Boolean =
        instantMs >= startMs && instantMs < endExclusiveMs

    /**
     * Maps a local date-time to every valid instant inside this range.
     *
     * A DST gap returns no positions. A repeated local time returns both
     * instants, ordered, instead of silently selecting one offset.
     */
    fun localDateTimeToInstants(localDateTime: LocalDateTime): List<Long> =
        zoneId.rules.getValidOffsets(localDateTime)
            .map { offset -> localDateTime.toInstant(offset).toEpochMilli() }
            .sorted()
            .filter(::contains)

    /** Clips an absolute interval to this range and preserves an exclusive end. */
    fun clipInterval(
        startInstantMs: Long,
        endExclusiveInstantMs: Long,
    ): TimelineInstantRange? {
        if (endExclusiveInstantMs <= startInstantMs) return null
        val clippedStart = maxOf(startInstantMs, startMs)
        val clippedEnd = minOf(endExclusiveInstantMs, endExclusiveMs)
        if (clippedEnd <= clippedStart) return null
        return TimelineInstantRange(
            startMs = clippedStart,
            endExclusiveMs = clippedEnd,
        )
    }

    /**
     * Returns local-clock hour ticks inside this range. Invalid gap hours are
     * absent; repeated hours produce two ticks whose labels include UTC offsets.
     */
    fun hourTicks(stepHours: Int = 6): List<LocalDayGridHourTick> {
        require(stepHours in 1..24) { "hour tick step must be between 1 and 24" }
        val firstDay = Instant.ofEpochMilli(startMs).atZone(zoneId).toLocalDate().let { day ->
            if (day.atStartOfDay(zoneId).toInstant().toEpochMilli() > startMs) {
                day.minusDays(1)
            } else {
                day
            }
        }
        val lastDay = Instant.ofEpochMilli(endExclusiveMs - 1).atZone(zoneId).toLocalDate()
        val candidates = buildList {
            var date = firstDay
            while (!date.isAfter(lastDay)) {
                for (hour in 0..23 step stepHours) {
                    val localDateTime = date.atTime(hour, 0)
                    zoneId.rules.getValidOffsets(localDateTime).forEach { zoneOffset ->
                        val instantMs = localDateTime.toInstant(zoneOffset).toEpochMilli()
                        if (instantMs in startMs until endExclusiveMs) {
                            add(
                                HourTickCandidate(
                                    instantMs = instantMs,
                                    localDateTime = localDateTime,
                                    zoneOffset = zoneOffset,
                                ),
                            )
                        }
                    }
                }
                date = date.plusDays(1)
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
            LocalDayGridHourTick(
                instantMs = candidate.instantMs,
                localDateTime = candidate.localDateTime,
                zoneOffset = candidate.zoneOffset,
                label = label,
            )
        }
    }
}

private data class HourTickCandidate(
    val instantMs: Long,
    val localDateTime: LocalDateTime,
    val zoneOffset: ZoneOffset,
)
