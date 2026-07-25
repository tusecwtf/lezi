package com.lezi.babylog.feature.summary

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.domain.CareAggregation
import com.lezi.babylog.domain.CareDay
import com.lezi.babylog.domain.WeekSummary
import com.lezi.babylog.domain.weekStartFor
import java.time.LocalDate
import java.time.ZoneId

internal fun SummaryRange.startDate(anchorDate: LocalDate, weekStartDay: Int): LocalDate =
    when (this) {
        SummaryRange.Day -> anchorDate
        SummaryRange.Week -> weekStartFor(anchorDate, weekStartDay)
        SummaryRange.Month -> anchorDate.minusDays((dayCount - 1).toLong())
    }

internal fun buildSummaryUi(
    records: List<Record>,
    range: SummaryRange,
    anchorDate: LocalDate,
    weekStartDay: Int = 1,
    showAvgSleep: Boolean,
    comparePrevWeek: Boolean = false,
    babyName: String,
    zone: ZoneId,
): SummaryUi {
    val now = System.currentTimeMillis()
    val anchorEnd = anchorDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val visibleRecords = records.filter { it.timestamp < anchorEnd }
    val rangeStart = range.startDate(anchorDate, weekStartDay)
    val rangeSummary = CareAggregation.range(
        records = visibleRecords,
        startDate = rangeStart,
        dayCount = range.dayCount,
        zone = zone,
        now = now,
    )
    val detailStart = weekStartFor(anchorDate, weekStartDay)
    val detailSummary = CareAggregation.range(
        records = visibleRecords,
        startDate = detailStart,
        dayCount = 7,
        zone = zone,
        now = now,
    )
    val allTemperatures = rangeSummary.temperatures
    val anchorDay = CareAggregation.day(visibleRecords, anchorDate, zone, now)
    val chartWindows = ChartWindowTotals(
        dayFeedMl = anchorDay.bucket.feedMl,
        dayNursingMin = anchorDay.bucket.nursingMin,
        dayFeedCount = anchorDay.feedCount,
        daySleepMin = anchorDay.bucket.sleepMin,
        daySleepSegments = anchorDay.sleepSegments,
        dayPee = anchorDay.bucket.pee,
        dayPoop = anchorDay.bucket.poop,
    )
    val totals = SummaryTotals(
        feedMl = rangeSummary.feedMl,
        nursingMin = rangeSummary.nursingMinutes,
        feedCount = rangeSummary.feedCount,
        sleepMin = rangeSummary.sleepMinutes,
        sleepSegments = rangeSummary.sleepSegments,
        pee = rangeSummary.peeCount,
        poop = rangeSummary.poopCount,
        tempAvg = allTemperatures.takeIf { it.isNotEmpty() }?.average(),
        tempDays = rangeSummary.days.count { it.bucket.temps.isNotEmpty() },
        dayValuesFeed = rangeSummary.days.map { it.bucket.feedMl.toFloat() },
        dayValuesSleep = rangeSummary.days.map { it.bucket.sleepMin.toFloat() },
        dayValuesPee = rangeSummary.days.map { it.bucket.pee.toFloat() },
        dayValuesPoop = rangeSummary.days.map { it.bucket.poop.toFloat() },
        dayValuesDiaper = rangeSummary.days.map { (it.bucket.pee + it.bucket.poop).toFloat() },
        dayValuesTemp = rangeSummary.days.map { day ->
            day.bucket.temps.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
        },
        feedTimeBuckets = anchorDay.feedTimeBuckets,
        chartWindows = chartWindows,
    )
    val previousWeekTotals = if (range == SummaryRange.Week && comparePrevWeek) {
        val previous = CareAggregation.range(
            records = visibleRecords,
            startDate = rangeStart.minusDays(7),
            dayCount = 7,
            zone = zone,
            now = now,
        )
        SummaryTotals(
            feedMl = previous.feedMl,
            nursingMin = previous.nursingMinutes,
            feedCount = previous.feedCount,
            sleepMin = previous.sleepMinutes,
            sleepSegments = previous.sleepSegments,
            pee = previous.peeCount,
            poop = previous.poopCount,
        ).takeUnless {
            it.feedMl == 0 &&
                it.nursingMin == 0L &&
                it.feedCount == 0 &&
                it.sleepMin == 0L &&
                it.sleepSegments == 0 &&
                it.pee == 0 &&
                it.poop == 0
        }
    } else {
        null
    }
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
        rangeStartDate = rangeStart,
        totals = totals,
        previousWeekTotals = previousWeekTotals,
        week = WeekSummary(
            weekStart = detailStart,
            days = detailSummary.days.map(CareDay::bucket),
        ),
        showAvgSleep = showAvgSleep,
        comparePrevWeek = comparePrevWeek,
        empty = empty,
        babyName = babyName,
    )
}
