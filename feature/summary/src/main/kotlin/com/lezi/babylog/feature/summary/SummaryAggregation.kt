package com.lezi.babylog.feature.summary

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.domain.CareAggregation
import com.lezi.babylog.domain.CareDay
import com.lezi.babylog.domain.CareRange
import com.lezi.babylog.domain.WeekSummary
import com.lezi.babylog.domain.weekStartFor
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

data class SummaryAggregationRequest(
    val records: List<Record>,
    val range: SummaryRange,
    val anchorDate: LocalDate,
    val weekStartDay: Int = 1,
    val showAvgSleep: Boolean,
    val comparePrevWeek: Boolean = false,
    val babyName: String,
    val zone: ZoneId,
)

/** Background calculation boundary used by the Summary presentation layer. */
class SummaryAggregationEngine(
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun calculate(request: SummaryAggregationRequest): SummaryUi =
        withContext(computationDispatcher) {
            val now = nowMillis()
            val rangeStart = request.range.startDate(
                request.anchorDate,
                request.weekStartDay,
            )
            val detailStart = weekStartFor(request.anchorDate, request.weekStartDay)
            val compareStart = if (
                request.range == SummaryRange.Week && request.comparePrevWeek
            ) {
                rangeStart.minusDays(7)
            } else {
                rangeStart
            }
            val windowStart = minOf(rangeStart, detailStart, compareStart)
            val windowEnd = maxOf(
                rangeStart.plusDays(request.range.dayCount.toLong()),
                detailStart.plusDays(7),
            )
            val anchorEnd = request.anchorDate.plusDays(1)
                .atStartOfDay(request.zone)
                .toInstant()
                .toEpochMilli()
            val window = CareAggregation.window(
                records = request.records,
                startDate = windowStart,
                dayCount = ChronoUnit.DAYS.between(windowStart, windowEnd).toInt(),
                zone = request.zone,
                now = now,
                recordStartBefore = anchorEnd,
            )
            assembleSummaryUi(
                range = request.range,
                anchorDate = request.anchorDate,
                rangeStart = rangeStart,
                rangeSummary = window.range(rangeStart, request.range.dayCount),
                detailStart = detailStart,
                detailSummary = window.range(detailStart, 7),
                anchorDay = window.day(request.anchorDate),
                previousWeek = if (
                    request.range == SummaryRange.Week && request.comparePrevWeek
                ) {
                    window.range(rangeStart.minusDays(7), 7)
                } else {
                    null
                },
                showAvgSleep = request.showAvgSleep,
                comparePrevWeek = request.comparePrevWeek,
                babyName = request.babyName,
            )
        }
}

/** Cancels an obsolete calculation before accepting the next immutable input snapshot. */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<SummaryAggregationRequest>.calculateLatest(
    engine: SummaryAggregationEngine,
): Flow<SummaryUi> = mapLatest(engine::calculate)

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
    val anchorDay = CareAggregation.day(visibleRecords, anchorDate, zone, now)
    val previousWeek = if (range == SummaryRange.Week && comparePrevWeek) {
        CareAggregation.range(
            records = visibleRecords,
            startDate = rangeStart.minusDays(7),
            dayCount = 7,
            zone = zone,
            now = now,
        )
    } else {
        null
    }
    return assembleSummaryUi(
        range = range,
        anchorDate = anchorDate,
        rangeStart = rangeStart,
        rangeSummary = rangeSummary,
        detailStart = detailStart,
        detailSummary = detailSummary,
        anchorDay = anchorDay,
        previousWeek = previousWeek,
        showAvgSleep = showAvgSleep,
        comparePrevWeek = comparePrevWeek,
        babyName = babyName,
    )
}

private fun assembleSummaryUi(
    range: SummaryRange,
    anchorDate: LocalDate,
    rangeStart: LocalDate,
    rangeSummary: CareRange,
    detailStart: LocalDate,
    detailSummary: CareRange,
    anchorDay: CareDay,
    previousWeek: CareRange?,
    showAvgSleep: Boolean,
    comparePrevWeek: Boolean,
    babyName: String,
): SummaryUi {
    val allTemperatures = rangeSummary.temperatures
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
    val previousWeekTotals = previousWeek?.let { previous ->
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
        calculating = false,
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
