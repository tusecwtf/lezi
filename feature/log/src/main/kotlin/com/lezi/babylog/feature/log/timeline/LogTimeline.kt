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
import java.time.ZoneId

internal data class TimelineLanes(
    val sleep: List<TimelineLaneSegment>,
    val feed: List<TimelineLaneSegment>,
    val care: List<TimelineLaneSegment>,
)

/** Open sleep older than this after last successful family sync draws an uncertain tail. */
internal const val OPEN_SLEEP_UNCERTAIN_AFTER_SYNC_MILLIS = 60 * 60 * 1000L

internal data class OpenSleepDisplaySpan(
    val certainEndMs: Long,
    val uncertainEndMs: Long? = null,
)

/**
 * Split an open sleep into a locally-certain head and an optional unsynced tail.
 *
 * Dashed only when the family is joined, the sleep already existed at
 * [lastSuccessAtMs], and that success is at least one hour stale.
 */
internal fun openSleepDisplaySpan(
    startMs: Long,
    nowMs: Long,
    familyJoined: Boolean,
    lastSuccessAtMs: Long?,
): OpenSleepDisplaySpan {
    val lastSuccess = lastSuccessAtMs
    if (!familyJoined ||
        lastSuccess == null ||
        startMs > lastSuccess ||
        nowMs - lastSuccess < OPEN_SLEEP_UNCERTAIN_AFTER_SYNC_MILLIS
    ) {
        return OpenSleepDisplaySpan(certainEndMs = nowMs)
    }
    val cutoff = lastSuccess.coerceIn(startMs, nowMs)
    if (cutoff >= nowMs) return OpenSleepDisplaySpan(certainEndMs = nowMs)
    return OpenSleepDisplaySpan(certainEndMs = cutoff, uncertainEndMs = nowMs)
}

/**
 * Build rail segments clipped to an absolute `[clipStartMs, clipEndExclusiveMs)`.
 *
 * Overnight sleep is one interval clipped only at the range ends — never at
 * midnight. List/summary ownership still uses natural-day aggregation elsewhere.
 */
internal fun buildTimelineLanes(
    records: List<Record>,
    clipStartMs: Long,
    clipEndExclusiveMs: Long,
    zoneId: ZoneId,
    nowMs: Long = System.currentTimeMillis(),
    familyJoined: Boolean = false,
    lastSuccessAtMs: Long? = null,
): TimelineLanes {
    val grid = LocalDayGrid(clipStartMs, clipEndExclusiveMs, zoneId)
    val sleep = mutableListOf<TimelineLaneSegment>()
    val feed = mutableListOf<TimelineLaneSegment>()
    val care = mutableListOf<TimelineLaneSegment>()

    fun clock(ms: Long): String = formatClock(ms, zoneId)

    for (r in records) {
        when (r.type) {
            RecordType.SLEEP -> {
                // Domain projection fills endTimestamp for provisional/effective/legacy ends.
                // Only truly open SleepStarts (no projected end) stay running.
                // A stale open sleep (> STALE_OPEN_SLEEP_MILLIS) accrues no minutes in
                // aggregation, but its start is still a physical fact: the rail keeps
                // rendering the dashed uncertain tail instead of dropping the segment
                // (a zero-length rawEnd clips to null and made the lane vanish).
                val open = r.endTimestamp == null
                val span = if (open) {
                    openSleepDisplaySpan(
                        startMs = r.timestamp,
                        nowMs = nowMs,
                        familyJoined = familyJoined,
                        lastSuccessAtMs = lastSuccessAtMs,
                    )
                } else {
                    null
                }
                val rawEnd = r.endTimestamp ?: (span?.uncertainEndMs ?: span?.certainEndMs ?: nowMs)
                val uncertain = span?.uncertainEndMs != null
                val clipped = grid.clipInterval(r.timestamp, rawEnd)
                if (clipped != null) {
                    val durationMin = ((rawEnd - r.timestamp) / 60_000L).coerceAtLeast(1)
                    val nap = (r.payload.payload as? SleepPayload)?.isNap == true
                    val title = if (nap) "午睡" else "睡眠"
                    val detail = buildString {
                        append(clock(r.timestamp))
                        append("–")
                        append(if (open) "进行中" else clock(rawEnd))
                        append(" · ")
                        append(formatRecordDuration(durationMin))
                        if (uncertain) {
                            append("（同步后未确认）")
                        } else if (open) {
                            append("（未结束）")
                        }
                    }
                    val uncertainFromMs = if (uncertain) {
                        span?.certainEndMs?.let { certainEnd ->
                            certainEnd.coerceIn(clipped.startMs, clipped.endExclusiveMs)
                                .takeIf { it < clipped.endExclusiveMs }
                        }
                    } else {
                        null
                    }
                    sleep += TimelineLaneSegment(
                        startMs = clipped.startMs,
                        endMs = clipped.endExclusiveMs,
                        colorRole = LeziRecordColorRole.Sleep,
                        title = title,
                        detail = detail,
                        isEvent = false,
                        dayChartCategoryKey = DayChartCategory.SLEEP.name,
                        uncertainFromMs = uncertainFromMs,
                    )
                }
            }
            RecordType.FORMULA, RecordType.NURSING, RecordType.PUMPED_FEED,
            RecordType.PUMP_EXPRESS,
            -> {
                if (!grid.contains(r.timestamp)) continue
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
                    startMs = r.timestamp,
                    endMs = r.timestamp,
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
                if (!grid.contains(r.timestamp)) continue
                val notePart = r.note?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                when (r.type) {
                    RecordType.PEE -> care += TimelineLaneSegment(
                        startMs = r.timestamp,
                        endMs = r.timestamp,
                        colorRole = LeziRecordColorRole.Pee,
                        title = "尿尿",
                        detail = "${clock(r.timestamp)}$notePart · 护理",
                        isEvent = true,
                        dayChartCategoryKey = DayChartCategory.PEE.name,
                    )
                    RecordType.POOP -> care += TimelineLaneSegment(
                        startMs = r.timestamp,
                        endMs = r.timestamp,
                        colorRole = LeziRecordColorRole.Poop,
                        title = "便便",
                        detail = "${clock(r.timestamp)}$notePart · 护理",
                        isEvent = true,
                        dayChartCategoryKey = DayChartCategory.POOP.name,
                    )
                    RecordType.BOTH_DIAPER -> {
                        // Two marks share one record; each mark maps to one day-chart type.
                        care += TimelineLaneSegment(
                            startMs = r.timestamp,
                            endMs = r.timestamp,
                            colorRole = LeziRecordColorRole.Pee,
                            title = "尿尿",
                            detail = "${clock(r.timestamp)}$notePart · 尿+便（尿）",
                            isEvent = true,
                            dayChartCategoryKey = DayChartCategory.PEE.name,
                        )
                        care += TimelineLaneSegment(
                            startMs = r.timestamp,
                            endMs = r.timestamp,
                            colorRole = LeziRecordColorRole.Poop,
                            title = "便便",
                            detail = "${clock(r.timestamp)}$notePart · 尿+便（便）",
                            isEvent = true,
                            dayChartCategoryKey = DayChartCategory.POOP.name,
                        )
                    }
                    else -> care += TimelineLaneSegment(
                        startMs = r.timestamp,
                        endMs = r.timestamp,
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
