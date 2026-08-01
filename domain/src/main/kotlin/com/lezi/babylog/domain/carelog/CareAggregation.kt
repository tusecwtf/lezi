package com.lezi.babylog.domain.carelog
import com.lezi.babylog.core.model.MilkPayload
import com.lezi.babylog.core.model.NursingPayload
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordPayload
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.TemperaturePayload
import com.lezi.babylog.core.model.UnknownPayload
import com.lezi.babylog.core.model.displayLabel
import com.lezi.babylog.core.model.payloadSummary
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The single in-process module that owns care-record window semantics.
 *
 * Callers provide one already baby-scoped record set and receive day buckets,
 * range totals, and widget facts from the same interface. Compose geometry and
 * storage queries deliberately stay outside this module.
 *
 * ## Aggregation clock
 *
 * All entry points share one [now] clock:
 * - **Point facts** (formula, pumped, nursing, diaper, temperature, …): counted
 *   only when `timestamp <= now`. A fulfilled plan may store `now + 5m` skew;
 *   that row is visible on the timeline immediately but stays out of counts,
 *   amounts, temperatures, and feed-time buckets until the clock reaches it.
 * - **Sleep intervals**: clip to `[start, min(end, now)]` (open end uses [now]).
 *   A sleep that starts after [now] contributes neither minutes nor segments.
 * - Boundary is inclusive at equality: `timestamp == now` counts.
 */
object CareAggregation {
    fun day(
        records: List<Record>,
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = RecordTime.currentTimeMillis(),
    ): CareDay {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return aggregateDay(records, date, start, end, zone, now)
    }

    fun range(
        records: List<Record>,
        startDate: LocalDate,
        dayCount: Int,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = RecordTime.currentTimeMillis(),
    ): CareRange {
        require(dayCount > 0) { "dayCount must be positive" }
        val days = List(dayCount) { offset ->
            day(
                records = records,
                date = startDate.plusDays(offset.toLong()),
                zone = zone,
                now = now,
            )
        }
        return CareRange(startDate = startDate, days = days)
    }

    /**
     * Partitions one immutable record snapshot into natural-day buckets in one source pass.
     * Cancellation is checked between records and sleep-day clips.
     */
    suspend fun window(
        records: List<Record>,
        startDate: LocalDate,
        dayCount: Int,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = RecordTime.currentTimeMillis(),
        recordStartBefore: Long = Long.MAX_VALUE,
    ): CareAggregationWindow {
        require(dayCount > 0) { "dayCount must be positive" }
        val dayStarts = LongArray(dayCount + 1) { offset ->
            startDate.plusDays(offset.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        val days = Array(dayCount) { offset ->
            MutableCareDay(startDate.plusDays(offset.toLong()))
        }
        val coroutineContext = currentCoroutineContext()

        records.forEach { record ->
            coroutineContext.ensureActive()
            if (record.deletedAt != null || record.timestamp >= recordStartBefore) {
                return@forEach
            }
            val payload = record.payload.payload
            if (payload is UnknownPayload) return@forEach
            if (record.type == RecordType.SLEEP) {
                val intervalEnd = sleepIntervalEnd(record, now)
                if (intervalEnd <= dayStarts.first() || record.timestamp >= dayStarts.last()) {
                    return@forEach
                }
                days.indices.forEach { index ->
                    coroutineContext.ensureActive()
                    days[index].addSleep(
                        record = record,
                        dayStart = dayStarts[index],
                        dayEnd = dayStarts[index + 1],
                        intervalEnd = intervalEnd,
                    )
                }
                return@forEach
            }
            if (!pointFactOccurred(record, now)) return@forEach

            val date = Instant.ofEpochMilli(record.timestamp).atZone(zone).toLocalDate()
            val index = ChronoUnit.DAYS.between(startDate, date).toInt()
            if (index in days.indices) {
                days[index].addPoint(record, payload, zone)
            }
        }
        return CareAggregationWindow(
            startDate = startDate,
            days = days.map(MutableCareDay::freeze),
        )
    }

    fun week(
        records: List<Record>,
        weekStart: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = RecordTime.currentTimeMillis(),
    ): WeekSummary {
        val days = range(records, weekStart, 7, zone, now).days.map(CareDay::bucket)
        return WeekSummary(weekStart = weekStart, days = days)
    }

    fun widget(
        records: List<Record>,
        babyName: String,
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = RecordTime.currentTimeMillis(),
    ): WidgetSummaryDto {
        val day = day(records, date, zone, now)
        val latest = records.asSequence()
            .filter { it.deletedAt == null && pointFactOccurred(it, now) }
            .maxByOrNull(Record::timestamp)
        return WidgetSummaryDto(
            babyName = babyName,
            feedMl = day.bucket.feedMl,
            sleepMin = day.bucket.sleepMin,
            pee = day.bucket.pee,
            poop = day.bucket.poop,
            lastLabel = latest?.let {
                listOf(
                    it.displayLabel(),
                    it.payloadSummary(),
                    formatClock(it.timestamp, zone),
                ).filter(String::isNotBlank).joinToString(" · ")
            },
        )
    }

    private fun aggregateDay(
        records: List<Record>,
        date: LocalDate,
        dayStart: Long,
        dayEnd: Long,
        zone: ZoneId,
        now: Long,
    ): CareDay {
        var formulaMl = 0
        var pumpedFeedMl = 0
        var nursingMl = 0
        var nursingMin = 0L
        var feedCount = 0
        var sleepMin = 0L
        var sleepSegments = 0
        var pee = 0
        var poop = 0
        val temperatures = mutableListOf<Double>()
        val feedTimeBuckets = MutableList(4) { 0f }

        records.forEach { record ->
            if (record.deletedAt != null) return@forEach
            val payload = record.payload.payload
            if (payload is UnknownPayload) return@forEach
            if (record.type == RecordType.SLEEP) {
                val intervalEnd = sleepIntervalEnd(record, now)
                val clippedStart = maxOf(record.timestamp, dayStart)
                val clippedEnd = minOf(intervalEnd, dayEnd)
                if (clippedEnd > clippedStart) {
                    sleepMin += (clippedEnd - clippedStart) / MINUTE_MILLIS
                    // Count a physical sleep once: only on the day the interval
                    // starts. Cross-day clips still add minutes to each day.
                    if (clippedStart == record.timestamp) {
                        sleepSegments += 1
                    }
                }
                return@forEach
            }
            if (!pointFactOccurred(record, now)) return@forEach
            if (record.timestamp < dayStart || record.timestamp >= dayEnd) return@forEach

            when (record.type) {
                RecordType.FORMULA -> {
                    formulaMl += (payload as? MilkPayload)?.amountMl ?: 0
                    feedCount += 1
                    feedTimeBuckets.incrementFor(record.timestamp, zone)
                }
                RecordType.PUMPED_FEED -> {
                    pumpedFeedMl += (payload as? MilkPayload)?.amountMl ?: 0
                    feedCount += 1
                    feedTimeBuckets.incrementFor(record.timestamp, zone)
                }
                RecordType.NURSING -> {
                    val nursing = payload as? NursingPayload
                    nursingMl += nursing?.amountMl ?: 0
                    nursingMin += nursing?.leftMinutes ?: 0
                    nursingMin += nursing?.rightMinutes ?: 0
                    feedCount += 1
                    feedTimeBuckets.incrementFor(record.timestamp, zone)
                }
                RecordType.PEE -> pee += 1
                RecordType.POOP -> poop += 1
                RecordType.BOTH_DIAPER -> {
                    pee += 1
                    poop += 1
                }
                RecordType.TEMPERATURE -> {
                    val value = (payload as? TemperaturePayload)?.celsius
                    if (value != null) temperatures += value
                }
                else -> Unit
            }
        }

        return CareDay(
            bucket = DayBucket(
                date = date,
                feedMl = formulaMl + pumpedFeedMl + nursingMl,
                nursingMin = nursingMin,
                sleepMin = sleepMin,
                pee = pee,
                poop = poop,
                temps = temperatures,
            ),
            formulaMl = formulaMl,
            pumpedFeedMl = pumpedFeedMl,
            nursingMl = nursingMl,
            feedCount = feedCount,
            sleepSegments = sleepSegments,
            feedTimeBuckets = feedTimeBuckets,
        )
    }
}

data class CareAggregationWindow(
    val startDate: LocalDate,
    val days: List<CareDay>,
) {
    fun day(date: LocalDate): CareDay {
        val index = ChronoUnit.DAYS.between(startDate, date).toInt()
        require(index in days.indices) { "date is outside the aggregation window" }
        return days[index]
    }

    fun range(rangeStart: LocalDate, dayCount: Int): CareRange {
        require(dayCount > 0) { "dayCount must be positive" }
        val first = ChronoUnit.DAYS.between(startDate, rangeStart).toInt()
        val end = first + dayCount
        require(first >= 0 && end <= days.size) { "range is outside the aggregation window" }
        return CareRange(rangeStart, days.subList(first, end))
    }
}

private class MutableCareDay(
    private val date: LocalDate,
) {
    private var formulaMl = 0
    private var pumpedFeedMl = 0
    private var nursingMl = 0
    private var nursingMin = 0L
    private var feedCount = 0
    private var sleepMin = 0L
    private var sleepSegments = 0
    private var pee = 0
    private var poop = 0
    private val temperatures = mutableListOf<Double>()
    private val feedTimeBuckets = MutableList(4) { 0f }

    fun addSleep(record: Record, dayStart: Long, dayEnd: Long, intervalEnd: Long) {
        val clippedStart = maxOf(record.timestamp, dayStart)
        val clippedEnd = minOf(intervalEnd, dayEnd)
        if (clippedEnd <= clippedStart) return
        sleepMin += (clippedEnd - clippedStart) / MINUTE_MILLIS
        if (clippedStart == record.timestamp) sleepSegments += 1
    }

    fun addPoint(record: Record, payload: RecordPayload, zone: ZoneId) {
        when (record.type) {
            RecordType.FORMULA -> {
                formulaMl += (payload as? MilkPayload)?.amountMl ?: 0
                feedCount += 1
                feedTimeBuckets.incrementFor(record.timestamp, zone)
            }
            RecordType.PUMPED_FEED -> {
                pumpedFeedMl += (payload as? MilkPayload)?.amountMl ?: 0
                feedCount += 1
                feedTimeBuckets.incrementFor(record.timestamp, zone)
            }
            RecordType.NURSING -> {
                val nursing = payload as? NursingPayload
                nursingMl += nursing?.amountMl ?: 0
                nursingMin += nursing?.leftMinutes ?: 0
                nursingMin += nursing?.rightMinutes ?: 0
                feedCount += 1
                feedTimeBuckets.incrementFor(record.timestamp, zone)
            }
            RecordType.PEE -> pee += 1
            RecordType.POOP -> poop += 1
            RecordType.BOTH_DIAPER -> {
                pee += 1
                poop += 1
            }
            RecordType.TEMPERATURE -> {
                val value = (payload as? TemperaturePayload)?.celsius
                if (value != null) temperatures += value
            }
            else -> Unit
        }
    }

    fun freeze(): CareDay = CareDay(
        bucket = DayBucket(
            date = date,
            feedMl = formulaMl + pumpedFeedMl + nursingMl,
            nursingMin = nursingMin,
            sleepMin = sleepMin,
            pee = pee,
            poop = poop,
            temps = temperatures.toList(),
        ),
        formulaMl = formulaMl,
        pumpedFeedMl = pumpedFeedMl,
        nursingMl = nursingMl,
        feedCount = feedCount,
        sleepSegments = sleepSegments,
        feedTimeBuckets = feedTimeBuckets.toList(),
    )
}

data class CareDay(
    val bucket: DayBucket,
    val formulaMl: Int,
    val pumpedFeedMl: Int,
    val nursingMl: Int,
    val feedCount: Int,
    val sleepSegments: Int,
    val feedTimeBuckets: List<Float>,
) {
    fun toDailySummary(): DailySummary = DailySummary(
        sleepMinutes = bucket.sleepMin,
        peeCount = bucket.pee,
        poopCount = bucket.poop,
        formulaMl = formulaMl,
        nursingMinutes = bucket.nursingMin,
        pumpedFeedMl = pumpedFeedMl,
        feedMl = bucket.feedMl,
    )
}

data class CareRange(
    val startDate: LocalDate,
    val days: List<CareDay>,
) {
    val feedMl: Int get() = days.sumOf { it.bucket.feedMl }
    val nursingMinutes: Long get() = days.sumOf { it.bucket.nursingMin }
    val feedCount: Int get() = days.sumOf(CareDay::feedCount)
    val sleepMinutes: Long get() = days.sumOf { it.bucket.sleepMin }
    val sleepSegments: Int get() = days.sumOf(CareDay::sleepSegments)
    val peeCount: Int get() = days.sumOf { it.bucket.pee }
    val poopCount: Int get() = days.sumOf { it.bucket.poop }
    val temperatures: List<Double> get() = days.flatMap { it.bucket.temps }
}

data class DailySummary(
    val sleepMinutes: Long = 0,
    val peeCount: Int = 0,
    val poopCount: Int = 0,
    val formulaMl: Int = 0,
    val nursingMinutes: Long = 0,
    val pumpedFeedMl: Int = 0,
    val feedMl: Int = 0,
)

data class DayBucket(
    val date: LocalDate,
    val feedMl: Int = 0,
    val nursingMin: Long = 0,
    val sleepMin: Long = 0,
    val pee: Int = 0,
    val poop: Int = 0,
    val temps: List<Double> = emptyList(),
)

data class WeekSummary(
    val weekStart: LocalDate,
    val days: List<DayBucket>,
) {
    val totalFeedMl: Int get() = days.sumOf(DayBucket::feedMl)
    val totalNursingMin: Long get() = days.sumOf(DayBucket::nursingMin)
    val totalSleepMin: Long get() = days.sumOf(DayBucket::sleepMin)
    val totalPee: Int get() = days.sumOf(DayBucket::pee)
    val totalPoop: Int get() = days.sumOf(DayBucket::poop)
    val avgSleepMin: Long get() = totalSleepMin / days.size.coerceAtLeast(1)
}

data class WidgetSummaryDto(
    val babyName: String,
    val feedMl: Int,
    val sleepMin: Long,
    val pee: Int,
    val poop: Int,
    val lastLabel: String?,
)

/** weekStartSetting: 1 = Monday, 7 = Sunday (ISO). */
fun weekStartFor(day: LocalDate, weekStartSetting: Int): LocalDate {
    val first = if (weekStartSetting == 7) DayOfWeek.SUNDAY else DayOfWeek.MONDAY
    return day.with(TemporalAdjusters.previousOrSame(first))
}

private const val MINUTE_MILLIS = 60_000L

/** Closed or open sleep end, never past the aggregation clock. */
private fun sleepIntervalEnd(record: Record, now: Long): Long =
    minOf(record.endTimestamp ?: now, now)

/**
 * Point-fact eligibility under the aggregation clock: inclusive at equality.
 * KDoc on [CareAggregation] / PRD own the semantic; call sites share this predicate.
 */
private fun pointFactOccurred(record: Record, now: Long): Boolean =
    record.timestamp <= now

private fun MutableList<Float>.incrementFor(timestamp: Long, zone: ZoneId) {
    val hour = Instant.ofEpochMilli(timestamp).atZone(zone).hour
    val index = (hour / 6).coerceIn(0, lastIndex)
    this[index] += 1f
}
