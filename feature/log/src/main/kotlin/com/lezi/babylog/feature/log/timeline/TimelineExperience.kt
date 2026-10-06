package com.lezi.babylog.feature.log.timeline

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.DayChartCategories
import com.lezi.babylog.domain.carelog.DayChartCategory

/** Margin so ticks and day-boundary lines just outside the visible slice still exist. */
private const val RAIL_FRAME_MARGIN_MS = 3L * 60 * 60 * 1000

/** Absolute rail frame: viewport instants plus ZoneId-derived overlays. */
internal data class TimelineRailFrame(
    val viewportStartMs: Long,
    val viewportDurationMs: Long,
    val dayBoundariesMs: List<Long>,
    val dayBoundaryLabels: List<Pair<Long, String>>,
    val hourTicks: List<Pair<Long, String>>,
    val nowMs: Long?,
    val primaryRangeMs: LongRange,
)

/**
 * Build the rail frame from the absolute viewport. Changing [selectedDay] only
 * updates [TimelineRailFrame.primaryRangeMs] (neighbor dimming); the viewport
 * instants and therefore drawn pixels stay put.
 */
internal fun TimelineInteractionState.toTimelineRailFrame(nowMs: Long): TimelineRailFrame {
    val gridStart = viewport.startInstantMs - RAIL_FRAME_MARGIN_MS
    val gridEnd = viewport.endInstantMs + RAIL_FRAME_MARGIN_MS
    val grid = LocalDayGrid(gridStart, gridEnd, zoneId)
    val primaryStart = selectedDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val primaryEnd = selectedDay.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val showNowLine = nowMs in viewport.startInstantMs..viewport.endInstantMs
    val midnights = grid.localMidnightEpochMillis.filter { midnight ->
        midnight in gridStart..gridEnd
    }
    return TimelineRailFrame(
        viewportStartMs = viewport.startInstantMs,
        viewportDurationMs = viewport.durationMs,
        dayBoundariesMs = midnights,
        dayBoundaryLabels = midnights.map { midnight ->
            midnight to grid.monthDayLabel(midnight)
        },
        hourTicks = grid.hourTicks().map { tick -> tick.instantMs to tick.label },
        nowMs = nowMs.takeIf { showNowLine },
        primaryRangeMs = primaryStart until primaryEnd,
    )
}

/** Keep an active cross-day category visible even when the target day has no match. */
internal fun timelineLegendCategories(
    records: List<Record>,
    selected: DayChartCategory?,
): List<DayChartCategory> {
    val visible = DayChartCategories.legendCategories(records).toMutableSet()
    selected?.let(visible::add)
    return DayChartCategory.entries.filter(visible::contains)
}

/** Summary chips remain clearable while an active cross-day category is empty. */
internal fun timelineSelectableSummaryTypes(
    records: List<Record>,
    selected: DayChartCategory?,
): Set<RecordType> = timelineLegendCategories(records, selected)
    .mapNotNull(::summaryRecordType)
    .toSet()

internal data class TimelineRecordsEmptyState(
    val title: String,
    val message: String,
    val testTag: String,
)

internal fun timelineRecordsEmptyState(
    selected: DayChartCategory?,
): TimelineRecordsEmptyState = if (selected == null) {
    TimelineRecordsEmptyState(
        title = "还没有记录",
        message = "点下方快捷入口添加第一条记录",
        testTag = "log_records_empty",
    )
} else {
    TimelineRecordsEmptyState(
        title = "这一天没有${selected.label}记录",
        message = "${selected.label}筛选仍在生效，可换日继续比较或再次点按取消",
        testTag = "log_records_category_empty",
    )
}
