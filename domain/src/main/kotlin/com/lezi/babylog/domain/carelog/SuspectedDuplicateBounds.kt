package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordTime
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unresolved suspected-duplicate aggregate bounds (ADR-0021).
 *
 * Allowed interpretations for each open group are: each single-member display
 * selection, plus all members independent. Metrics come from [CareAggregation]
 * over the full interpretation record set (whole CareDay vectors), then min/max
 * across interpretations. Component-wise mixing of different sources is avoided.
 *
 * Resolved groups: pass only display versions (filter source-role UUIDs) so
 * min equals max.
 */
object SuspectedDuplicateBounds {

    fun day(
        records: List<Record>,
        openGroups: List<SuspectedDuplicateGroup>,
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = RecordTime.currentTimeMillis(),
    ): CareDayBounds {
        val live = records.filter { it.deletedAt == null }
        val byUuid = live.associateBy { it.clientUuid }
        val groupedUuids = openGroups.flatMap { it.memberClientUuids }.toSet()
        val independents = live.filter { it.clientUuid !in groupedUuids }

        val interpretationSets = buildInterpretationSets(openGroups, byUuid, independents)
        require(interpretationSets.isNotEmpty()) { "interpretation sets must be non-empty" }

        val days = interpretationSets.map { set ->
            CareAggregation.day(set, date, zone, now)
        }
        return CareDayBounds(
            date = date,
            formulaMl = IntBound(days.minOf { it.formulaMl }, days.maxOf { it.formulaMl }),
            pumpedFeedMl = IntBound(days.minOf { it.pumpedFeedMl }, days.maxOf { it.pumpedFeedMl }),
            nursingMl = IntBound(days.minOf { it.nursingMl }, days.maxOf { it.nursingMl }),
            nursingMinutes = LongBound(
                days.minOf { it.bucket.nursingMin },
                days.maxOf { it.bucket.nursingMin },
            ),
            feedCount = IntBound(days.minOf { it.feedCount }, days.maxOf { it.feedCount }),
            feedMl = IntBound(
                days.minOf { it.bucket.feedMl },
                days.maxOf { it.bucket.feedMl },
            ),
            peeCount = IntBound(days.minOf { it.bucket.pee }, days.maxOf { it.bucket.pee }),
            poopCount = IntBound(days.minOf { it.bucket.poop }, days.maxOf { it.bucket.poop }),
            sleepMinutes = LongBound(
                days.minOf { it.bucket.sleepMin },
                days.maxOf { it.bucket.sleepMin },
            ),
            sleepSegments = IntBound(
                days.minOf { it.sleepSegments },
                days.maxOf { it.sleepSegments },
            ),
            temperaturesMin = days.minByOrNull { it.bucket.temps.size }?.bucket?.temps.orEmpty(),
            temperaturesMax = days.maxByOrNull { it.bucket.temps.size }?.bucket?.temps.orEmpty(),
        )
    }

    /**
     * Multi-day bounds by composing per-day interpretation mins/maxes.
     */
    fun range(
        records: List<Record>,
        openGroups: List<SuspectedDuplicateGroup>,
        startDate: LocalDate,
        dayCount: Int,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = RecordTime.currentTimeMillis(),
    ): CareRangeBounds {
        require(dayCount > 0) { "dayCount must be positive" }
        val days = List(dayCount) { offset ->
            day(
                records = records,
                openGroups = openGroups,
                date = startDate.plusDays(offset.toLong()),
                zone = zone,
                now = now,
            )
        }
        return CareRangeBounds(startDate = startDate, days = days)
    }

    /**
     * Records for ordinary single-value aggregation after resolution:
     * keep display UUIDs and independents; drop source UUIDs of relations.
     */
    fun filterDisplayProjection(
        records: List<Record>,
        sourceRoleClientUuids: Set<String>,
    ): List<Record> = records.filter { it.clientUuid !in sourceRoleClientUuids }

    /**
     * Cartesian product of per-group choices: each singleton member OR full set.
     */
    private fun buildInterpretationSets(
        openGroups: List<SuspectedDuplicateGroup>,
        byUuid: Map<String, Record>,
        independents: List<Record>,
    ): List<List<Record>> {
        if (openGroups.isEmpty()) {
            return listOf(independents)
        }
        var sets: List<List<Record>> = listOf(independents)
        for (group in openGroups) {
            val members = group.memberClientUuids.mapNotNull { byUuid[it] }
            if (members.isEmpty()) continue
            val choices = buildList {
                members.forEach { add(listOf(it)) }
                if (members.size > 1) add(members)
            }
            sets = sets.flatMap { base ->
                choices.map { choice -> base + choice }
            }
        }
        return sets.ifEmpty { listOf(independents) }
    }
}

data class IntBound(val min: Int, val max: Int) {
    val isExact: Boolean get() = min == max
}

data class LongBound(val min: Long, val max: Long) {
    val isExact: Boolean get() = min == max
}

data class CareDayBounds(
    val date: LocalDate,
    val formulaMl: IntBound,
    val pumpedFeedMl: IntBound,
    val nursingMl: IntBound,
    val nursingMinutes: LongBound,
    val feedCount: IntBound,
    val feedMl: IntBound,
    val peeCount: IntBound,
    val poopCount: IntBound,
    val sleepMinutes: LongBound,
    val sleepSegments: IntBound,
    val temperaturesMin: List<Double>,
    val temperaturesMax: List<Double>,
) {
    val hasUncertainty: Boolean
        get() = !formulaMl.isExact ||
            !pumpedFeedMl.isExact ||
            !nursingMl.isExact ||
            !nursingMinutes.isExact ||
            !feedCount.isExact ||
            !feedMl.isExact ||
            !peeCount.isExact ||
            !poopCount.isExact

    /** Exact projection for callers that still need a single DailySummary. */
    fun toDailySummaryPreferMax(): DailySummary = DailySummary(
        sleepMinutes = sleepMinutes.max,
        peeCount = peeCount.max,
        poopCount = poopCount.max,
        formulaMl = formulaMl.max,
        nursingMinutes = nursingMinutes.max,
        pumpedFeedMl = pumpedFeedMl.max,
        feedMl = feedMl.max,
    )
}

data class CareRangeBounds(
    val startDate: LocalDate,
    val days: List<CareDayBounds>,
) {
    val feedMl: IntBound
        get() = IntBound(days.sumOf { it.feedMl.min }, days.sumOf { it.feedMl.max })
    val formulaMl: IntBound
        get() = IntBound(days.sumOf { it.formulaMl.min }, days.sumOf { it.formulaMl.max })
    val nursingMinutes: LongBound
        get() = LongBound(days.sumOf { it.nursingMinutes.min }, days.sumOf { it.nursingMinutes.max })
    val feedCount: IntBound
        get() = IntBound(days.sumOf { it.feedCount.min }, days.sumOf { it.feedCount.max })
    val sleepMinutes: LongBound
        get() = LongBound(days.sumOf { it.sleepMinutes.min }, days.sumOf { it.sleepMinutes.max })
    val sleepSegments: IntBound
        get() = IntBound(days.sumOf { it.sleepSegments.min }, days.sumOf { it.sleepSegments.max })
    val peeCount: IntBound
        get() = IntBound(days.sumOf { it.peeCount.min }, days.sumOf { it.peeCount.max })
    val poopCount: IntBound
        get() = IntBound(days.sumOf { it.poopCount.min }, days.sumOf { it.poopCount.max })
    val hasUncertainty: Boolean get() = days.any { it.hasUncertainty }
}

/** Format a bound for summary UI: single value or "min–max". */
fun IntBound.formatRange(): String =
    if (isExact) min.toString() else "$min–$max"

fun LongBound.formatRange(): String =
    if (isExact) min.toString() else "$min–$max"
