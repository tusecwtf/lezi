package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordTime
import java.time.LocalDate
import java.time.ZoneId

/**
 * Polynomial bounds implementation used behind [SuspectedDuplicateProjection].
 *
 * Every record is interpreted once through [CareAggregation]. For each open
 * group we retain only its member vectors and their full-set sum; interpretations
 * are never materialized. Additive metric extrema compose per group. Temperature
 * average uses a count-indexed dynamic program over the same vectors.
 *
 * For r target records, c total group choices, d days, and t temperature facts,
 * time is O((r+c)*d + t*c) and auxiliary space is O(r*d + t).
 * The implementation checks the caller-provided cancellation hook between records,
 * days, groups, choices, and dynamic-program states.
 */
object SuspectedDuplicateBounds {
    fun day(
        records: List<Record>,
        openGroups: List<SuspectedDuplicateGroup>,
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = RecordTime.currentTimeMillis(),
    ): CareDayBounds = range(
        records = records,
        openGroups = openGroups,
        startDate = date,
        dayCount = 1,
        zone = zone,
        now = now,
    ).days.single()

    fun range(
        records: List<Record>,
        openGroups: List<SuspectedDuplicateGroup>,
        startDate: LocalDate,
        dayCount: Int,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = RecordTime.currentTimeMillis(),
    ): CareRangeBounds = range(
        records = records,
        openGroups = openGroups,
        startDate = startDate,
        dayCount = dayCount,
        zone = zone,
        now = now,
        checkActive = {},
    )

    internal fun range(
        records: List<Record>,
        openGroups: List<SuspectedDuplicateGroup>,
        startDate: LocalDate,
        dayCount: Int,
        zone: ZoneId,
        now: Long,
        checkActive: () -> Unit,
        factEndExclusive: Long = Long.MAX_VALUE,
    ): CareRangeBounds {
        require(dayCount > 0) { "dayCount must be positive" }
        val liveByUuid = HashMap<String, Record>(records.size)
        records.forEach { record ->
            checkActive()
            if (record.deletedAt == null) liveByUuid[record.clientUuid] = record
        }
        val groupedUuids = linkedSetOf<String>()
        openGroups.forEach { group ->
            checkActive()
            group.memberClientUuids.forEach { uuid ->
                checkActive()
                groupedUuids += uuid
            }
        }
        val fixed = MutableMetricWindow(dayCount)
        records.forEach { record ->
            checkActive()
            if (record.clientUuid !in groupedUuids) {
                fixed.add(
                    record.metricWindow(startDate, dayCount, zone, now, factEndExclusive, checkActive),
                    checkActive,
                )
            }
        }
        val groupChoices = mutableListOf<List<MetricWindow>>()
        openGroups.forEach { group ->
            checkActive()
            val members = mutableListOf<MetricWindow>()
            group.memberClientUuids.forEach { uuid ->
                checkActive()
                liveByUuid[uuid]?.let { record ->
                    members += record.metricWindow(startDate, dayCount, zone, now, factEndExclusive, checkActive)
                }
            }
            if (members.isNotEmpty()) {
                val choices = buildList<MetricWindow> {
                    addAll(members)
                    if (members.size > 1) {
                        add(MetricWindow.sum(members, dayCount, checkActive))
                    }
                }
                groupChoices.add(choices)
            }
        }
        val fixedWindow = fixed.freeze()
        val days = List(dayCount) { dayIndex ->
            checkActive()
            boundsFor(
                date = startDate.plusDays(dayIndex.toLong()),
                fixed = fixedWindow.days[dayIndex],
                choices = groupChoices.transformChoices(checkActive) { it.days[dayIndex] },
                checkActive = checkActive,
            )
        }
        val totals = boundsFor(
            date = startDate,
            fixed = fixedWindow.total(checkActive),
            choices = groupChoices.transformChoices(checkActive) { it.total(checkActive) },
            checkActive = checkActive,
        )
        return CareRangeBounds(
            startDate = startDate,
            days = days,
            formulaMl = totals.formulaMl,
            pumpedFeedMl = totals.pumpedFeedMl,
            nursingMl = totals.nursingMl,
            nursingMinutes = totals.nursingMinutes,
            feedCount = totals.feedCount,
            feedMl = totals.feedMl,
            peeCount = totals.peeCount,
            poopCount = totals.poopCount,
            sleepMinutes = totals.sleepMinutes,
            sleepSegments = totals.sleepSegments,
            temperatureCount = totals.temperatureCount,
            temperatureAverage = totals.temperatureAverage,
        )
    }

    fun filterDisplayProjection(
        records: List<Record>,
        sourceRoleClientUuids: Set<String>,
    ): List<Record> = records.filter { it.clientUuid !in sourceRoleClientUuids }

    private fun boundsFor(
        date: LocalDate,
        fixed: MetricVector,
        choices: List<List<MetricVector>>,
        checkActive: () -> Unit,
    ): CareDayBounds {
        fun intBound(value: (MetricVector) -> Int): IntBound {
            var min = value(fixed)
            var max = min
            choices.forEach { group ->
                checkActive()
                var groupMin = Int.MAX_VALUE
                var groupMax = Int.MIN_VALUE
                group.forEach { choice ->
                    checkActive()
                    val metric = value(choice)
                    groupMin = minOf(groupMin, metric)
                    groupMax = maxOf(groupMax, metric)
                }
                min += groupMin
                max += groupMax
            }
            return IntBound(min, max)
        }
        fun longBound(value: (MetricVector) -> Long): LongBound {
            var min = value(fixed)
            var max = min
            choices.forEach { group ->
                checkActive()
                var groupMin = Long.MAX_VALUE
                var groupMax = Long.MIN_VALUE
                group.forEach { choice ->
                    checkActive()
                    val metric = value(choice)
                    groupMin = minOf(groupMin, metric)
                    groupMax = maxOf(groupMax, metric)
                }
                min += groupMin
                max += groupMax
            }
            return LongBound(min, max)
        }
        val temperature = temperatureBounds(fixed, choices, checkActive)
        return CareDayBounds(
            date = date,
            formulaMl = intBound(MetricVector::formulaMl),
            pumpedFeedMl = intBound(MetricVector::pumpedFeedMl),
            nursingMl = intBound(MetricVector::nursingMl),
            nursingMinutes = longBound(MetricVector::nursingMinutes),
            feedCount = intBound(MetricVector::feedCount),
            feedMl = intBound(MetricVector::feedMl),
            peeCount = intBound(MetricVector::peeCount),
            poopCount = intBound(MetricVector::poopCount),
            sleepMinutes = longBound(MetricVector::sleepMinutes),
            sleepSegments = intBound(MetricVector::sleepSegments),
            temperatureCount = temperature.count,
            temperatureAverage = temperature.average,
        )
    }

    private fun temperatureBounds(
        fixed: MetricVector,
        choices: List<List<MetricVector>>,
        checkActive: () -> Unit,
    ): TemperatureBounds {
        var sumsByCount = mutableMapOf(
            fixed.temperatureCount to DoubleExtrema(
                fixed.temperatureSum,
                fixed.temperatureSum,
            ),
        )
        choices.forEach { group ->
            checkActive()
            val next = mutableMapOf<Int, DoubleExtrema>()
            sumsByCount.forEach { (baseCount, baseSums) ->
                checkActive()
                group.forEach { choice ->
                    checkActive()
                    val count = baseCount + choice.temperatureCount
                    val minSum = baseSums.min + choice.temperatureSum
                    val maxSum = baseSums.max + choice.temperatureSum
                    next.compute(count) { _, previous ->
                        DoubleExtrema(
                            min = minOf(previous?.min ?: minSum, minSum),
                            max = maxOf(previous?.max ?: maxSum, maxSum),
                        )
                    }
                }
            }
            sumsByCount = next
        }
        val count = IntBound(
            min = sumsByCount.keys.minOrNull() ?: fixed.temperatureCount,
            max = sumsByCount.keys.maxOrNull() ?: fixed.temperatureCount,
        )
        val nonEmpty = sumsByCount.filterKeys { it > 0 }
        val average = if (nonEmpty.isEmpty()) {
            null
        } else {
            DoubleBound(
                min = nonEmpty.minOf { (countValue, sums) -> sums.min / countValue },
                max = nonEmpty.maxOf { (countValue, sums) -> sums.max / countValue },
            )
        }
        return TemperatureBounds(count, average)
    }
}

data class IntBound(val min: Int, val max: Int) {
    val isExact: Boolean get() = min == max
}

data class LongBound(val min: Long, val max: Long) {
    val isExact: Boolean get() = min == max
}

data class DoubleBound(val min: Double, val max: Double) {
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
    val temperatureCount: IntBound,
    val temperatureAverage: DoubleBound?,
) {
    val hasUncertainty: Boolean
        get() = !formulaMl.isExact ||
            !pumpedFeedMl.isExact ||
            !nursingMl.isExact ||
            !nursingMinutes.isExact ||
            !feedCount.isExact ||
            !feedMl.isExact ||
            !peeCount.isExact ||
            !poopCount.isExact ||
            !sleepMinutes.isExact ||
            !sleepSegments.isExact ||
            !temperatureCount.isExact ||
            temperatureAverage?.isExact == false

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
    val temperatureCount: IntBound,
    val temperatureAverage: DoubleBound?,
) {
    val hasUncertainty: Boolean
        get() = !formulaMl.isExact ||
            !pumpedFeedMl.isExact ||
            !nursingMl.isExact ||
            !nursingMinutes.isExact ||
            !feedCount.isExact ||
            !feedMl.isExact ||
            !peeCount.isExact ||
            !poopCount.isExact ||
            !sleepMinutes.isExact ||
            !sleepSegments.isExact ||
            !temperatureCount.isExact ||
            temperatureAverage?.isExact == false

    fun day(date: LocalDate): CareDayBounds =
        days.first { it.date == date }
}

fun IntBound.formatRange(): String =
    if (isExact) min.toString() else "$min–$max"

fun LongBound.formatRange(): String =
    if (isExact) min.toString() else "$min–$max"

private data class TemperatureBounds(
    val count: IntBound,
    val average: DoubleBound?,
)

private data class DoubleExtrema(val min: Double, val max: Double)

private data class MetricVector(
    val formulaMl: Int = 0,
    val pumpedFeedMl: Int = 0,
    val nursingMl: Int = 0,
    val nursingMinutes: Long = 0,
    val feedCount: Int = 0,
    val feedMl: Int = 0,
    val peeCount: Int = 0,
    val poopCount: Int = 0,
    val sleepMinutes: Long = 0,
    val sleepSegments: Int = 0,
    val temperatureSum: Double = 0.0,
    val temperatureCount: Int = 0,
) {
    operator fun plus(other: MetricVector): MetricVector = MetricVector(
        formulaMl = formulaMl + other.formulaMl,
        pumpedFeedMl = pumpedFeedMl + other.pumpedFeedMl,
        nursingMl = nursingMl + other.nursingMl,
        nursingMinutes = nursingMinutes + other.nursingMinutes,
        feedCount = feedCount + other.feedCount,
        feedMl = feedMl + other.feedMl,
        peeCount = peeCount + other.peeCount,
        poopCount = poopCount + other.poopCount,
        sleepMinutes = sleepMinutes + other.sleepMinutes,
        sleepSegments = sleepSegments + other.sleepSegments,
        temperatureSum = temperatureSum + other.temperatureSum,
        temperatureCount = temperatureCount + other.temperatureCount,
    )
}

private data class MetricWindow(val days: List<MetricVector>) {
    fun total(checkActive: () -> Unit): MetricVector = days.fold(MetricVector()) { total, day ->
        checkActive()
        total + day
    }

    companion object {
        fun sum(
            windows: List<MetricWindow>,
            dayCount: Int,
            checkActive: () -> Unit,
        ): MetricWindow = MetricWindow(
            List(dayCount) { index ->
                windows.fold(MetricVector()) { total, window ->
                    checkActive()
                    total + window.days[index]
                }
            },
        )
    }
}

private class MutableMetricWindow(dayCount: Int) {
    private val days = MutableList(dayCount) { MetricVector() }

    fun add(window: MetricWindow, checkActive: () -> Unit) {
        days.indices.forEach { index ->
            checkActive()
            days[index] = days[index] + window.days[index]
        }
    }

    fun freeze(): MetricWindow = MetricWindow(days.toList())
}

private fun List<List<MetricWindow>>.transformChoices(
    checkActive: () -> Unit,
    transform: (MetricWindow) -> MetricVector,
): List<List<MetricVector>> = map { group ->
    checkActive()
    group.map { choice ->
        checkActive()
        transform(choice)
    }
}

private fun Record.metricWindow(
    startDate: LocalDate,
    dayCount: Int,
    zone: ZoneId,
    now: Long,
    factEndExclusive: Long,
    checkActive: () -> Unit,
): MetricWindow = MetricWindow(
    CareAggregation.range(listOf(this), startDate, dayCount, zone, now, factEndExclusive).days.map { day ->
        checkActive()
        MetricVector(
            formulaMl = day.formulaMl,
            pumpedFeedMl = day.pumpedFeedMl,
            nursingMl = day.nursingMl,
            nursingMinutes = day.bucket.nursingMin,
            feedCount = day.feedCount,
            feedMl = day.bucket.feedMl,
            peeCount = day.bucket.pee,
            poopCount = day.bucket.poop,
            sleepMinutes = day.bucket.sleepMin,
            sleepSegments = day.sleepSegments,
            temperatureSum = day.bucket.temps.sum(),
            temperatureCount = day.bucket.temps.size,
        )
    },
)
