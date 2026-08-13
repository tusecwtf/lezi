package com.lezi.babylog.feature.log.timeline

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.DayChartCategories
import com.lezi.babylog.domain.carelog.DayChartCategory

private const val MILLIS_PER_MINUTE = 60_000L

/** Rendering projection of the interaction state's absolute viewport onto its work axis. */
internal data class TimelinePresentation(
    val axis: ThreeDayTimelineAxis,
    val viewportStartMinutes: Int,
    val viewportDurationMinutes: Int,
    val nowContentMinute: Int?,
)

internal fun TimelineInteractionState.toTimelinePresentation(nowMs: Long): TimelinePresentation {
    val axis = ThreeDayTimelineAxis(selectedDay, zoneId)
    val viewportStartMinutes =
        ((viewport.startInstantMs - axis.windowStartMs) / MILLIS_PER_MINUTE).toInt()
    val viewportDurationMinutes = (viewport.durationMs / MILLIS_PER_MINUTE).toInt()
        .coerceAtLeast(1)
    val showNowLine = nowMs in viewport.startInstantMs..viewport.endInstantMs
    return TimelinePresentation(
        axis = axis,
        viewportStartMinutes = viewportStartMinutes,
        viewportDurationMinutes = viewportDurationMinutes,
        nowContentMinute = axis.instantToContentMinute(nowMs).takeIf { showNowLine },
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
