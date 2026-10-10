package com.lezi.babylog.feature.summary

import com.lezi.babylog.core.model.FoodPayload
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.formatRecordDuration
import com.lezi.babylog.core.model.parseFoodAmountValue
import com.lezi.babylog.domain.carelog.CareAggregation
import com.lezi.babylog.domain.carelog.CareDay
import com.lezi.babylog.domain.carelog.CareDayBounds
import com.lezi.babylog.domain.carelog.CareRange
import com.lezi.babylog.domain.carelog.CareRangeBounds
import com.lezi.babylog.domain.carelog.IntBound
import com.lezi.babylog.domain.carelog.SuspectedDuplicateGroup
import com.lezi.babylog.domain.carelog.SuspectedDuplicatePresentation
import com.lezi.babylog.domain.carelog.SuspectedDuplicateProjection
import com.lezi.babylog.domain.carelog.formatRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

data class SummaryAggregationRequest(
    val records: List<Record>,
    val range: SummaryRange,
    val anchorDate: LocalDate,
    val showAvgSleep: Boolean,
    val comparePrevWeek: Boolean = false,
    val babyName: String,
    val zone: ZoneId,
    /** Source-role UUIDs excluded from ordinary stats (live for 来源详情). */
    val sourceRoleClientUuids: Set<String> = emptySet(),
    val evaluationTimeMillis: Long? = null,
)

/** Background calculation boundary used by the Summary presentation layer. */
class SummaryAggregationEngine(
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowMillis: () -> Long = { RecordTime.currentTimeMillis() },
) {
    internal suspend fun requiresMinuteTicks(request: SummaryAggregationRequest): Boolean =
        withContext(computationDispatcher) {
            val context = currentCoroutineContext()
            request.records.any {
                context.ensureActive()
                it.type == RecordType.SLEEP && it.endTimestamp == null && it.deletedAt == null
            }
        }

    suspend fun calculate(request: SummaryAggregationRequest): SummaryUi =
        withContext(computationDispatcher) {
            val now = request.evaluationTimeMillis ?: nowMillis()
            val rangeStart = request.range.startDate(request.anchorDate)
            val compareStart = if (
                request.range == SummaryRange.Week && request.comparePrevWeek
            ) {
                rangeStart.minusDays(7)
            } else {
                rangeStart
            }
            val windowStart = minOf(rangeStart, compareStart)
            val windowEnd = rangeStart.plusDays(request.range.dayCount.toLong())
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
            val food = aggregateFoodPanel(
                records = records,
                openGroups = projection.openGroups,
                rangeStart = rangeStart,
                dayCount = request.range.dayCount,
                zone = request.zone,
                now = now,
                anchorEnd = anchorEnd,
            )
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
                food = food,
            )
        }
}

/** Cancels an obsolete calculation before accepting the next immutable input snapshot. */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<SummaryAggregationRequest>.calculateLatest(
    engine: SummaryAggregationEngine,
    minuteTicks: Flow<Long> = summaryMinuteTicks(),
): Flow<SummaryUi> = flatMapLatest { request ->
    // Reuse the loaded bounded snapshot: ticks must not re-subscribe the database.
    // Eligibility is also a potentially large scan, so it shares the engine's
    // cancellable computation boundary rather than blocking the collecting UI.
    val timedRequests = if (engine.requiresMinuteTicks(request)) {
        minuteTicks.map { request.copy(evaluationTimeMillis = it) }
    } else {
        flowOf(request)
    }
    // Keep calculation inside this request's child: a newer snapshot cancels both
    // its old ticker and its in-flight calculation before scanning the new input.
    timedRequests.mapLatest(engine::calculate)
}

/** Cold: cancellation stops ticking; resume immediately samples the current wall clock. */
private fun summaryMinuteTicks(): Flow<Long> = flow {
    while (currentCoroutineContext().isActive) {
        val now = RecordTime.currentTimeMillis()
        emit(now)
        delay(60_000L - Math.floorMod(now, 60_000L))
    }
}

/** Rolling week/month windows end at the anchor date (default: today). */
internal fun SummaryRange.startDate(anchorDate: LocalDate): LocalDate =
    when (this) {
        SummaryRange.Day -> anchorDate
        SummaryRange.Week, SummaryRange.Month ->
            anchorDate.minusDays((dayCount - 1).toLong())
    }

private const val FOOD_PANEL_TOP_LANE_COUNT = 3
private const val FOOD_PANEL_MERGED_LANE_NAME = "其他"
private const val FOOD_PANEL_UNSPECIFIED_LANE_NAME = "未注明"

/**
 * Display label for the merged tail lane when a real ingredient is literally named 其他 —
 * keeps legend/chart lanes unambiguous without touching lane identity.
 */
private const val FOOD_PANEL_MERGED_LANE_COLLISION_NAME = "其余食材"

/**
 * Aggregates the 辅食 panel from the engine's post-projection record set.
 *
 * Mirrors the ordinary point-event window semantics: deleted rows, future facts, and
 * records at or after the anchor-day end are excluded, and each record lands on the
 * natural day of its timestamp. Lane identity is the exact trimmed content string;
 * amounts follow the shared digit-prefix convention, so records without a parsable
 * amount stay in the counts (and in 未填量) but contribute nothing to the bars.
 * An unresolved suspected-duplicate group contributes its per-day lower bound to
 * the bars and its range lower/upper bounds to the panel total, not the sum of
 * every member.
 */
internal suspend fun aggregateFoodPanel(
    records: List<Record>,
    openGroups: List<SuspectedDuplicateGroup>,
    rangeStart: LocalDate,
    dayCount: Int,
    zone: ZoneId,
    now: Long,
    anchorEnd: Long,
): SummaryFoodPanel? {
    val context = currentCoroutineContext()
    val foodGroups = openGroups.filter { it.recordType == RecordType.BABY_FOOD }
    val groupedUuids = HashSet<String>()
    foodGroups.forEach { group ->
        context.ensureActive()
        group.memberClientUuids.forEach { uuid ->
            context.ensureActive()
            groupedUuids += uuid
        }
    }
    var recordCount = 0
    val base = FoodSlice(dayCount)
    val slicesByUuid = HashMap<String, FoodSlice>()
    records.forEach { record ->
        context.ensureActive()
        val counted = countedFoodSlice(
            record = record,
            dayCount = dayCount,
            rangeStart = rangeStart,
            zone = zone,
            now = now,
            anchorEnd = anchorEnd,
        ) ?: return@forEach
        recordCount += 1
        slicesByUuid[record.clientUuid] = counted
        if (record.clientUuid !in groupedUuids) {
            base.addSlice(counted)
        }
    }
    if (recordCount == 0) return null
    var amountMin = base.totalAmount
    var amountMax = base.totalAmount
    val drawn = FoodSlice(dayCount)
    drawn.addSlice(base)
    foodGroups.forEach { group ->
        context.ensureActive()
        val choices = foodChoices(group, slicesByUuid, dayCount)
        if (choices.isEmpty()) return@forEach
        val rangeMin = choices.minWith(compareBy({ it.totalAmount }, { it.tieKey }))
        amountMin += rangeMin.totalAmount
        amountMax += choices.maxOf { it.totalAmount }
        for (dayIndex in 0 until dayCount) {
            context.ensureActive()
            val dayChoice = choices.minWith(compareBy({ it.dayAmount(dayIndex) }, { it.tieKey }))
            drawn.addDayFrom(dayIndex, dayChoice)
        }
    }
    val unparseableCount = drawn.unparseableCount
    val unparseableDayCounts = drawn.unparseableByDay
    val laneDayTotals = HashMap<String, DoubleArray>()
    for (dayIndex in 0 until dayCount) {
        context.ensureActive()
        drawn.lanesByDay[dayIndex].forEach { (lane, amount) ->
            laneDayTotals.getOrPut(lane) { DoubleArray(dayCount) }[dayIndex] += amount
        }
    }
    val rankedLanes = laneDayTotals.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, DoubleArray>> { it.value.sum() }
                .thenBy { it.key },
        )
    val topLanes = rankedLanes.take(FOOD_PANEL_TOP_LANE_COUNT).map { (name, dayTotals) ->
        SummaryFoodLane(
            name = name.ifBlank { FOOD_PANEL_UNSPECIFIED_LANE_NAME },
            dayValues = dayTotals.toList(),
            total = dayTotals.sum(),
        )
    }
    val mergedDayTotals = DoubleArray(dayCount)
    rankedLanes.drop(FOOD_PANEL_TOP_LANE_COUNT).forEach { (_, dayTotals) ->
        for (dayIndex in 0 until dayCount) {
            mergedDayTotals[dayIndex] += dayTotals[dayIndex]
        }
    }
    val lanes = topLanes + if (rankedLanes.size > FOOD_PANEL_TOP_LANE_COUNT) {
        listOf(
            SummaryFoodLane(
                name = if (topLanes.any { it.name == FOOD_PANEL_MERGED_LANE_NAME }) {
                    FOOD_PANEL_MERGED_LANE_COLLISION_NAME
                } else {
                    FOOD_PANEL_MERGED_LANE_NAME
                },
                dayValues = mergedDayTotals.toList(),
                total = mergedDayTotals.sum(),
            ),
        )
    } else {
        emptyList()
    }
    return SummaryFoodPanel(
        recordCount = recordCount,
        kindCount = laneDayTotals.size,
        lanes = lanes,
        unparseableCount = unparseableCount,
        unparseableDayCounts = unparseableDayCounts.toList(),
        amountMin = amountMin,
        amountMax = amountMax,
    )
}

/**
 * One legal 辅食 interpretation: a single group member, or every member counted
 * independently. The sum choice sorts after member UUIDs so a tied lower bound
 * stays a single member instead of the in-group sum.
 */
private class FoodSlice(dayCount: Int, val tieKey: String = "") {
    val lanesByDay: List<MutableMap<String, Double>> = List(dayCount) { linkedMapOf() }
    val unparseableByDay: IntArray = IntArray(dayCount)
    var totalAmount: Double = 0.0
    var unparseableCount: Int = 0

    fun add(dayIndex: Int, lane: String, amount: Double?) {
        val dayLanes = lanesByDay[dayIndex]
        if (amount == null) {
            dayLanes.putIfAbsent(lane, 0.0)
            unparseableByDay[dayIndex] += 1
            unparseableCount += 1
        } else {
            dayLanes[lane] = (dayLanes[lane] ?: 0.0) + amount
            totalAmount += amount
        }
    }

    fun addSlice(other: FoodSlice) {
        totalAmount += other.totalAmount
        unparseableCount += other.unparseableCount
        lanesByDay.indices.forEach { dayIndex ->
            unparseableByDay[dayIndex] += other.unparseableByDay[dayIndex]
            other.lanesByDay[dayIndex].forEach { (lane, amount) ->
                val dayLanes = lanesByDay[dayIndex]
                dayLanes[lane] = (dayLanes[lane] ?: 0.0) + amount
            }
        }
    }

    fun addDayFrom(dayIndex: Int, other: FoodSlice) {
        unparseableByDay[dayIndex] += other.unparseableByDay[dayIndex]
        unparseableCount += other.unparseableByDay[dayIndex]
        other.lanesByDay[dayIndex].forEach { (lane, amount) ->
            val dayLanes = lanesByDay[dayIndex]
            dayLanes[lane] = (dayLanes[lane] ?: 0.0) + amount
        }
    }

    fun dayAmount(dayIndex: Int): Double = lanesByDay[dayIndex].values.sum()
}

private fun countedFoodSlice(
    record: Record,
    dayCount: Int,
    rangeStart: LocalDate,
    zone: ZoneId,
    now: Long,
    anchorEnd: Long,
): FoodSlice? {
    if (record.deletedAt != null || record.type != RecordType.BABY_FOOD) return null
    if (record.timestamp >= anchorEnd || record.timestamp > now) return null
    val payload = record.payload.payload
    if (payload !is FoodPayload) return null
    val dayIndex = ChronoUnit.DAYS.between(
        rangeStart,
        Instant.ofEpochMilli(record.timestamp).atZone(zone).toLocalDate(),
    ).toInt()
    if (dayIndex !in 0 until dayCount) return null
    return FoodSlice(dayCount, record.clientUuid).also { slice ->
        slice.add(dayIndex, payload.content.trim(), parseFoodAmountValue(payload.amount))
    }
}

private fun foodChoices(
    group: SuspectedDuplicateGroup,
    slicesByUuid: Map<String, FoodSlice>,
    dayCount: Int,
): List<FoodSlice> {
    if (group.memberClientUuids.isEmpty()) return emptyList()
    val members = group.memberClientUuids.map { uuid ->
        slicesByUuid[uuid] ?: FoodSlice(dayCount, uuid)
    }
    if (members.size == 1) return members
    val summed = FoodSlice(dayCount, tieKey = "\uFFFF")
    members.forEach(summed::addSlice)
    return members + summed
}

private fun assembleSummaryUi(
    range: SummaryRange,
    anchorDate: LocalDate,
    rangeStart: LocalDate,
    rangeSummary: CareRange,
    anchorDay: CareDay,
    previousWeek: CareRange?,
    showAvgSleep: Boolean,
    comparePrevWeek: Boolean,
    babyName: String,
    rangeBounds: CareRangeBounds? = null,
    anchorBounds: CareDayBounds? = null,
    food: SummaryFoodPanel? = null,
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
        // Unresolved groups: bar height is the per-day lower bound. Titles keep min–max.
        dayValuesFeed = rangeSummary.days.mapIndexed { index, day ->
            (rb?.days?.getOrNull(index)?.feedMl?.min ?: day.bucket.feedMl).toFloat()
        },
        dayValuesSleep = rangeSummary.days.mapIndexed { index, day ->
            (rb?.days?.getOrNull(index)?.sleepMinutes?.min ?: day.bucket.sleepMin).toFloat()
        },
        dayValuesPee = rangeSummary.days.mapIndexed { index, day ->
            (rb?.days?.getOrNull(index)?.peeCount?.min ?: day.bucket.pee).toFloat()
        },
        dayValuesPoop = rangeSummary.days.mapIndexed { index, day ->
            (rb?.days?.getOrNull(index)?.poopCount?.min ?: day.bucket.poop).toFloat()
        },
        dayValuesDiaper = rangeSummary.days.mapIndexed { index, day ->
            val bound = rb?.days?.getOrNull(index)
            if (bound == null) {
                (day.bucket.pee + day.bucket.poop).toFloat()
            } else {
                (bound.peeCount.min + bound.poopCount.min).toFloat()
            }
        },
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
        showAvgSleep = showAvgSleep,
        comparePrevWeek = comparePrevWeek,
        empty = empty,
        babyName = babyName,
        food = food,
    )
}

private fun formatMinuteBound(bound: com.lezi.babylog.domain.carelog.LongBound): String =
    if (bound.min == bound.max) {
        formatRecordDuration(bound.min)
    } else {
        "${formatRecordDuration(bound.min)}–${formatRecordDuration(bound.max)}"
    }
