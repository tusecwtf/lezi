package com.lezi.babylog.feature.summary

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.formatRecordDuration
import com.lezi.babylog.domain.carelog.CareAggregation
import com.lezi.babylog.domain.carelog.CareDay
import com.lezi.babylog.domain.carelog.CareDayBounds
import com.lezi.babylog.domain.carelog.CareRange
import com.lezi.babylog.domain.carelog.CareRangeBounds
import com.lezi.babylog.domain.carelog.IntBound
import com.lezi.babylog.domain.carelog.SuspectedDuplicatePresentation
import com.lezi.babylog.domain.carelog.SuspectedDuplicateProjection
import com.lezi.babylog.domain.carelog.WeekSummary
import com.lezi.babylog.domain.carelog.weekStartFor
import com.lezi.babylog.domain.carelog.formatRange
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
    /** Source-role UUIDs excluded from ordinary stats (live for 来源详情). */
    val sourceRoleClientUuids: Set<String> = emptySet(),
)

/** Background calculation boundary used by the Summary presentation layer. */
class SummaryAggregationEngine(
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowMillis: () -> Long = { RecordTime.currentTimeMillis() },
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
            // The projection owns cancellation, source filtering, and open grouping.
            // Anchor-end exclusion is shared with the ordinary aggregation window.
            val projection = SuspectedDuplicateProjection.project(
                records = request.records,
                startDate = rangeStart,
                dayCount = request.range.dayCount,
                zone = request.zone,
                now = now,
                sourceRoleClientUuids = request.sourceRoleClientUuids,
                factEndExclusive = anchorEnd,
            )
            val records = projection.projectedRecords
            val window = CareAggregation.window(
                records = records,
                startDate = windowStart,
                dayCount = ChronoUnit.DAYS.between(windowStart, windowEnd).toInt(),
                zone = request.zone,
                now = now,
                recordStartBefore = anchorEnd,
            )
            // Bounds only when open groups exist — avoids N× full scans on common path.
            val rangeBounds = if (projection.openGroups.isEmpty()) {
                null
            } else {
                projection.bounds
            }
            val anchorBounds = if (projection.openGroups.isEmpty()) {
                null
            } else {
                projection.bounds.day(request.anchorDate)
            }
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
                rangeBounds = rangeBounds,
                anchorBounds = anchorBounds,
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

internal suspend fun buildSummaryUi(
    records: List<Record>,
    range: SummaryRange,
    anchorDate: LocalDate,
    weekStartDay: Int = 1,
    showAvgSleep: Boolean,
    comparePrevWeek: Boolean = false,
    babyName: String,
    zone: ZoneId,
    sourceRoleClientUuids: Set<String> = emptySet(),
): SummaryUi {
    val now = RecordTime.currentTimeMillis()
    val anchorEnd = anchorDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val rangeStart = range.startDate(anchorDate, weekStartDay)
    val detailStart = weekStartFor(anchorDate, weekStartDay)
    val projection = SuspectedDuplicateProjection.project(
        records = records,
        startDate = rangeStart,
        dayCount = range.dayCount,
        zone = zone,
        now = now,
        sourceRoleClientUuids = sourceRoleClientUuids,
        factEndExclusive = anchorEnd,
    )
    val projected = projection.projectedRecords.filter { it.timestamp < anchorEnd }
    val rangeBounds = if (projection.openGroups.isEmpty()) {
        null
    } else {
        projection.bounds
    }
    val anchorBounds = if (projection.openGroups.isEmpty()) {
        null
    } else {
        projection.bounds.day(anchorDate)
    }
    val rangeSummary = CareAggregation.range(
        records = projected,
        startDate = rangeStart,
        dayCount = range.dayCount,
        zone = zone,
        now = now,
    )
    val detailSummary = CareAggregation.range(
        records = projected,
        startDate = detailStart,
        dayCount = 7,
        zone = zone,
        now = now,
    )
    val anchorDay = CareAggregation.day(projected, anchorDate, zone, now)
    val previousWeek = if (range == SummaryRange.Week && comparePrevWeek) {
        CareAggregation.range(
            records = projected,
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
        rangeBounds = rangeBounds,
        anchorBounds = anchorBounds,
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
    rangeBounds: CareRangeBounds? = null,
    anchorBounds: CareDayBounds? = null,
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
        dayFeedMlLabel = anchorBounds?.feedMl?.let {
            SuspectedDuplicatePresentation.formatMetricBound(it, "ml")
        },
        dayFeedCountLabel = anchorBounds?.feedCount?.formatRange(),
        dayNursingMinLabel = anchorBounds?.nursingMinutes?.formatRange(),
        daySleepMinLabel = anchorBounds?.sleepMinutes?.let(::formatMinuteBound),
        daySleepSegmentsLabel = anchorBounds?.sleepSegments?.formatRange(),
        dayPeeLabel = anchorBounds?.peeCount?.formatRange(),
        dayPoopLabel = anchorBounds?.poopCount?.formatRange(),
        dayDiaperLabel = anchorBounds?.let {
            IntBound(
                min = it.peeCount.min + it.poopCount.min,
                max = it.peeCount.max + it.poopCount.max,
            ).formatRange()
        },
    )
    val rb = rangeBounds
    val totals = SummaryTotals(
        feedMl = rb?.feedMl?.max ?: rangeSummary.feedMl,
        feedMlMin = rb?.feedMl?.min ?: rangeSummary.feedMl,
        feedMlMax = rb?.feedMl?.max ?: rangeSummary.feedMl,
        nursingMin = rb?.nursingMinutes?.max ?: rangeSummary.nursingMinutes,
        nursingMinMin = rb?.nursingMinutes?.min ?: rangeSummary.nursingMinutes,
        nursingMinMax = rb?.nursingMinutes?.max ?: rangeSummary.nursingMinutes,
        feedCount = rb?.feedCount?.max ?: rangeSummary.feedCount,
        feedCountMin = rb?.feedCount?.min ?: rangeSummary.feedCount,
        feedCountMax = rb?.feedCount?.max ?: rangeSummary.feedCount,
        sleepMin = rb?.sleepMinutes?.max ?: rangeSummary.sleepMinutes,
        sleepSegments = rb?.sleepSegments?.max ?: rangeSummary.sleepSegments,
        pee = rb?.peeCount?.max ?: rangeSummary.peeCount,
        peeMin = rb?.peeCount?.min ?: rangeSummary.peeCount,
        peeMax = rb?.peeCount?.max ?: rangeSummary.peeCount,
        poop = rb?.poopCount?.max ?: rangeSummary.poopCount,
        poopMin = rb?.poopCount?.min ?: rangeSummary.poopCount,
        poopMax = rb?.poopCount?.max ?: rangeSummary.poopCount,
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
        hasDuplicateUncertainty = rb?.hasUncertainty == true ||
            anchorBounds?.hasUncertainty == true,
        feedMlLabel = rb?.feedMl?.let {
            SuspectedDuplicatePresentation.formatMetricBound(it, "ml")
        },
        feedCountLabel = rb?.feedCount?.let {
            SuspectedDuplicatePresentation.formatMetricBound(it)
        },
        nursingMinLabel = rb?.nursingMinutes?.let(::formatMinuteBound),
        sleepMinLabel = rb?.sleepMinutes?.let(::formatMinuteBound),
        sleepSegmentsLabel = rb?.sleepSegments?.formatRange(),
        peeLabel = rb?.peeCount?.let { SuspectedDuplicatePresentation.formatMetricBound(it) },
        poopLabel = rb?.poopCount?.let { SuspectedDuplicatePresentation.formatMetricBound(it) },
        diaperLabel = rb?.let {
            SuspectedDuplicatePresentation.formatMetricBound(
                IntBound(
                    min = it.peeCount.min + it.poopCount.min,
                    max = it.peeCount.max + it.poopCount.max,
                ),
                "次",
            )
        },
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

private fun formatMinuteBound(bound: com.lezi.babylog.domain.carelog.LongBound): String =
    if (bound.min == bound.max) {
        formatRecordDuration(bound.min)
    } else {
        "${formatRecordDuration(bound.min)}–${formatRecordDuration(bound.max)}"
    }
