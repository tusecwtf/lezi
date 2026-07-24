package com.lezi.babylog.feature.summary

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.DayBucket
import com.lezi.babylog.domain.WeekSummary
import com.lezi.babylog.domain.payloadDouble
import com.lezi.babylog.domain.payloadInt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal fun SummaryRange.startDate(anchorDate: LocalDate): LocalDate =
    anchorDate.minusDays((dayCount - 1).toLong())

internal fun buildSummaryUi(
    records: List<Record>,
    range: SummaryRange,
    anchorDate: LocalDate,
    showAvgSleep: Boolean,
    babyName: String,
    zone: ZoneId,
): SummaryUi {
    val rangeDays = summarizeDays(
        records = records,
        startDate = range.startDate(anchorDate),
        dayCount = range.dayCount,
        zone = zone,
    )
    val detailStart = anchorDate.minusDays(6)
    val detailDays = if (range == SummaryRange.Week) {
        rangeDays
    } else {
        summarizeDays(
            records = records,
            startDate = detailStart,
            dayCount = 7,
            zone = zone,
        )
    }
    val allTemperatures = rangeDays.flatMap { it.bucket.temps }
    val anchorDay = detailDays.lastOrNull()
    val chartWindows = ChartWindowTotals(
        dayFeedMl = anchorDay?.bucket?.feedMl ?: 0,
        dayNursingMin = anchorDay?.bucket?.nursingMin ?: 0L,
        dayFeedCount = anchorDay?.feedCount ?: 0,
        daySleepMin = anchorDay?.bucket?.sleepMin ?: 0L,
        daySleepSegments = anchorDay?.sleepSegments ?: 0,
        dayPee = anchorDay?.bucket?.pee ?: 0,
        dayPoop = anchorDay?.bucket?.poop ?: 0,
    )
    val totals = SummaryTotals(
        feedMl = rangeDays.sumOf { it.bucket.feedMl },
        nursingMin = rangeDays.sumOf { it.bucket.nursingMin },
        feedCount = rangeDays.sumOf { it.feedCount },
        sleepMin = rangeDays.sumOf { it.bucket.sleepMin },
        sleepSegments = rangeDays.sumOf { it.sleepSegments },
        pee = rangeDays.sumOf { it.bucket.pee },
        poop = rangeDays.sumOf { it.bucket.poop },
        tempAvg = allTemperatures.takeIf { it.isNotEmpty() }?.average(),
        tempDays = rangeDays.count { it.bucket.temps.isNotEmpty() },
        dayValuesFeed = rangeDays.map { it.bucket.feedMl.toFloat() },
        dayValuesSleep = rangeDays.map { it.bucket.sleepMin.toFloat() },
        dayValuesPee = rangeDays.map { it.bucket.pee.toFloat() },
        dayValuesPoop = rangeDays.map { it.bucket.poop.toFloat() },
        dayValuesDiaper = rangeDays.map { (it.bucket.pee + it.bucket.poop).toFloat() },
        dayValuesTemp = rangeDays.map { day ->
            day.bucket.temps.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
        },
        feedTimeBuckets = feedingTimeBuckets(
            records = records,
            date = anchorDate,
            zone = zone,
        ),
        chartWindows = chartWindows,
    )
    val empty = totals.feedCount == 0 &&
        totals.feedMl == 0 &&
        totals.nursingMin == 0L &&
        totals.sleepSegments == 0 &&
        totals.sleepMin == 0L &&
        totals.pee == 0 &&
        totals.poop == 0 &&
        totals.tempAvg == null
    return SummaryUi(
        range = range,
        anchorDate = anchorDate,
        totals = totals,
        week = WeekSummary(
            weekStart = detailStart,
            days = detailDays.map { it.bucket },
        ),
        showAvgSleep = showAvgSleep,
        empty = empty,
        babyName = babyName,
    )
}

private fun feedingTimeBuckets(
    records: List<Record>,
    date: LocalDate,
    zone: ZoneId,
): List<Float> {
    val buckets = MutableList(4) { 0f }
    records.asSequence()
        .filter { it.deletedAt == null }
        .filter {
            it.type == RecordType.FORMULA ||
                it.type == RecordType.NURSING ||
                it.type == RecordType.PUMPED_FEED
        }
        .map { Instant.ofEpochMilli(it.timestamp).atZone(zone) }
        .filter { it.toLocalDate() == date }
        .forEach { timestamp ->
            val bucket = (timestamp.hour / 6).coerceIn(0, 3)
            buckets[bucket] += 1f
        }
    return buckets
}

private data class DayStats(
    val bucket: DayBucket,
    val feedCount: Int,
    val sleepSegments: Int,
)

private fun summarizeDays(
    records: List<Record>,
    startDate: LocalDate,
    dayCount: Int,
    zone: ZoneId,
): List<DayStats> {
    val endDateExclusive = startDate.plusDays(dayCount.toLong())
    val recordsByDate = records
        .asSequence()
        .filter { it.deletedAt == null }
        .map { record ->
            Instant.ofEpochMilli(record.timestamp).atZone(zone).toLocalDate() to record
        }
        .filter { (date, _) -> !date.isBefore(startDate) && date.isBefore(endDateExclusive) }
        .groupBy({ it.first }, { it.second })

    return List(dayCount) { offset ->
        val date = startDate.plusDays(offset.toLong())
        summarizeDay(date, recordsByDate[date].orEmpty())
    }
}

private fun summarizeDay(date: LocalDate, records: List<Record>): DayStats {
    var feedMl = 0
    var nursingMin = 0L
    var feedCount = 0
    var sleepMin = 0L
    var sleepSegments = 0
    var pee = 0
    var poop = 0
    val temperatures = mutableListOf<Double>()

    records.forEach { record ->
        when (record.type) {
            RecordType.FORMULA, RecordType.PUMPED_FEED -> {
                feedMl += payloadInt(record.payloadJson, "amount_ml")
                feedCount += 1
            }
            RecordType.NURSING -> {
                nursingMin += payloadInt(record.payloadJson, "left_min")
                nursingMin += payloadInt(record.payloadJson, "right_min")
                feedMl += payloadInt(record.payloadJson, "amount_ml")
                feedCount += 1
            }
            RecordType.SLEEP -> {
                val end = record.endTimestamp
                if (end != null && end >= record.timestamp) {
                    sleepMin += (end - record.timestamp) / 60_000L
                    sleepSegments += 1
                }
            }
            RecordType.PEE -> pee += 1
            RecordType.POOP -> poop += 1
            RecordType.BOTH_DIAPER -> {
                pee += 1
                poop += 1
            }
            RecordType.TEMPERATURE -> {
                val value = payloadDouble(record.payloadJson, "celsius")
                    ?: payloadDouble(record.payloadJson, "value")
                if (value != null) temperatures += value
            }
            else -> Unit
        }
    }

    return DayStats(
        bucket = DayBucket(
            date = date,
            feedMl = feedMl,
            nursingMin = nursingMin,
            sleepMin = sleepMin,
            pee = pee,
            poop = poop,
            temps = temperatures,
        ),
        feedCount = feedCount,
        sleepSegments = sleepSegments,
    )
}
