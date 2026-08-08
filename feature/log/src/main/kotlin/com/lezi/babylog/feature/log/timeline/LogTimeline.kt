package com.lezi.babylog.feature.log.timeline
import com.lezi.babylog.core.model.MilkPayload
import com.lezi.babylog.core.model.NursingPayload
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SleepPayload
import com.lezi.babylog.core.model.formatRecordDuration
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.designsystem.LeziRecordColorRole
import com.lezi.babylog.designsystem.TimelineLaneSegment
import com.lezi.babylog.domain.carelog.DayChartCategories
import com.lezi.babylog.domain.carelog.DayChartCategory
import com.lezi.babylog.domain.carelog.formatClock
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

internal data class TimelineLanes(
    val sleep: List<TimelineLaneSegment>,
    val feed: List<TimelineLaneSegment>,
    val care: List<TimelineLaneSegment>,
)

/**
 * Build rail segments on the calendar-derived continuous [axis].
 *
 * Overnight sleep is one unclipped-at-midnight interval clipped only to the
 * three-local-day window ends. List/summary ownership still uses natural-day aggregation
 * elsewhere — this function only produces geometry.
 */
internal fun buildTimelineLanes(
    records: List<Record>,
    axis: ThreeDayTimelineAxis,
    nowMs: Long = System.currentTimeMillis(),
): TimelineLanes {
    val sleep = mutableListOf<TimelineLaneSegment>()
    val feed = mutableListOf<TimelineLaneSegment>()
    val care = mutableListOf<TimelineLaneSegment>()
    fun offsetToStartMinute(offsetMs: Long): Int = (offsetMs / 60_000L).toInt()

    fun offsetToEndExclusiveMinute(offsetMs: Long): Int =
        ((offsetMs + 59_999L) / 60_000L).toInt()
            .coerceAtMost(axis.contentDurationMinutes)

    fun clock(ms: Long): String = formatClock(ms, axis.zoneId)

    for (r in records) {
        when (r.type) {
            RecordType.SLEEP -> {
                val open = r.endTimestamp == null
                val rawEnd = r.endTimestamp ?: nowMs
                // Clip only to the three-local-day window — do not split at midnight.
                val clipped = axis.clipIntervalToOffsets(r.timestamp, rawEnd)
                if (clipped != null) {
                    val startMin = offsetToStartMinute(clipped.startOffsetMs)
                    val endMin = offsetToEndExclusiveMinute(clipped.endExclusiveOffsetMs)
                        .coerceAtLeast(startMin + 1)
                    val durationMin = ((rawEnd - r.timestamp) / 60_000L).coerceAtLeast(1)
                    val nap = (r.payload.payload as? SleepPayload)?.isNap == true
                    val title = if (nap) "午睡" else "睡眠"
                    val detail = buildString {
                        append(clock(r.timestamp))
                        append("–")
                        append(if (open) "进行中" else clock(rawEnd))
                        append(" · ")
                        append(formatRecordDuration(durationMin))
                        if (open) append("（未结束）")
                    }
                    sleep += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = endMin,
                        colorRole = LeziRecordColorRole.Sleep,
                        title = title,
                        detail = detail,
                        isEvent = false,
                        dayChartCategoryKey = DayChartCategory.SLEEP.name,
                    )
                }
            }
            RecordType.FORMULA, RecordType.NURSING, RecordType.PUMPED_FEED,
            RecordType.PUMP_EXPRESS,
            -> {
                val startMin = axis.instantToContentMinute(r.timestamp) ?: continue
                val title = r.type.presentation.label
                val detail = buildString {
                    append(clock(r.timestamp))
                    when (r.type) {
                        RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS -> {
                            val ml = (r.payload.payload as? MilkPayload)?.amountMl ?: 0
                            if (ml > 0) append(" · ${ml}ml")
                        }
                        RecordType.NURSING -> {
                            val payload = r.payload.payload as? NursingPayload
                            val left = payload?.leftMinutes ?: 0
                            val right = payload?.rightMinutes ?: 0
                            if (left + right > 0) append(" · 左${left}分/右${right}分")
                        }
                        else -> Unit
                    }
                    r.note?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                }
                feed += TimelineLaneSegment(
                    startMinOfDay = startMin,
                    endMinOfDay = startMin,
                    colorRole = r.type.presentation.colorRole,
                    title = title,
                    detail = detail,
                    isEvent = true,
                    // 吸奶 draws on the feed rail but is not a day-chart type (not 奶).
                    dayChartCategoryKey = dayChartCategoryKeyForRecordType(r.type),
                )
            }
            RecordType.PEE, RecordType.POOP, RecordType.BOTH_DIAPER, RecordType.BATH,
            RecordType.TEMPERATURE, RecordType.MEDICINE,
            -> {
                val startMin = axis.instantToContentMinute(r.timestamp) ?: continue
                val notePart = r.note?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                when (r.type) {
                    RecordType.PEE -> care += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = startMin,
                        colorRole = LeziRecordColorRole.Pee,
                        title = "尿尿",
                        detail = "${clock(r.timestamp)}$notePart · 护理",
                        isEvent = true,
                        dayChartCategoryKey = DayChartCategory.PEE.name,
                    )
                    RecordType.POOP -> care += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = startMin,
                        colorRole = LeziRecordColorRole.Poop,
                        title = "便便",
                        detail = "${clock(r.timestamp)}$notePart · 护理",
                        isEvent = true,
                        dayChartCategoryKey = DayChartCategory.POOP.name,
                    )
                    RecordType.BOTH_DIAPER -> {
                        // Two marks share one record; each mark maps to one day-chart type.
                        care += TimelineLaneSegment(
                            startMinOfDay = startMin,
                            endMinOfDay = startMin,
                            colorRole = LeziRecordColorRole.Pee,
                            title = "尿尿",
                            detail = "${clock(r.timestamp)}$notePart · 尿+便（尿）",
                            isEvent = true,
                            dayChartCategoryKey = DayChartCategory.PEE.name,
                        )
                        care += TimelineLaneSegment(
                            startMinOfDay = startMin,
                            endMinOfDay = startMin,
                            colorRole = LeziRecordColorRole.Poop,
                            title = "便便",
                            detail = "${clock(r.timestamp)}$notePart · 尿+便（便）",
                            isEvent = true,
                            dayChartCategoryKey = DayChartCategory.POOP.name,
                        )
                    }
                    else -> care += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = startMin,
                        colorRole = r.type.presentation.colorRole,
                        title = r.type.presentation.label,
                        detail = "${clock(r.timestamp)}$notePart · 护理",
                        isEvent = true,
                        dayChartCategoryKey = null,
                    )
                }
            }
            else -> Unit
        }
    }
    return TimelineLanes(sleep, feed, care)
}

/**
 * Opaque key for [TimelineLaneSegment.dayChartCategoryKey], aligned with
 * [DayChartCategory.name]. Non day-chart types (e.g. 吸奶) return null.
 * BOTH_DIAPER is handled as two segments in [buildTimelineLanes], not here.
 */
internal fun dayChartCategoryKeyForRecordType(type: RecordType): String? =
    DayChartCategories.categoriesOf(type).singleOrNull()?.name

/**
 * Map a timeline/legend callback key into page-level [DayChartCategory].
 * The designsystem already applies toggle/clear (same key → null, other key → that key);
 * this only decodes the opaque key string.
 */
internal fun resolveDayChartSelection(selectedKey: String?): DayChartCategory? {
    if (selectedKey == null) return null
    return DayChartCategory.entries.firstOrNull { it.name == selectedKey }
}

/** Summary-strip categories intentionally match the timeline/legend filter. */
internal fun summaryDayChartCategory(type: RecordType): DayChartCategory? = when (type) {
    RecordType.FORMULA, RecordType.PUMPED_FEED -> DayChartCategory.MILK
    RecordType.NURSING -> DayChartCategory.NURSING
    RecordType.SLEEP -> DayChartCategory.SLEEP
    RecordType.PEE -> DayChartCategory.PEE
    RecordType.POOP -> DayChartCategory.POOP
    else -> null
}

internal fun summaryRecordType(category: DayChartCategory?): RecordType? = when (category) {
    DayChartCategory.MILK -> RecordType.FORMULA
    DayChartCategory.NURSING -> RecordType.NURSING
    DayChartCategory.SLEEP -> RecordType.SLEEP
    DayChartCategory.PEE -> RecordType.PEE
    DayChartCategory.POOP -> RecordType.POOP
    null -> null
}

/** Legend swatch colors for day-chart categories (feature owns labels/keys; designsystem stays free of domain). */
internal fun dayChartLegendColorRole(
    category: DayChartCategory,
): LeziRecordColorRole = when (category) {
    DayChartCategory.SLEEP -> LeziRecordColorRole.Sleep
    DayChartCategory.MILK -> LeziRecordColorRole.Milk
    DayChartCategory.NURSING -> LeziRecordColorRole.Nursing
    DayChartCategory.PEE -> LeziRecordColorRole.Pee
    DayChartCategory.POOP -> LeziRecordColorRole.Poop
}
