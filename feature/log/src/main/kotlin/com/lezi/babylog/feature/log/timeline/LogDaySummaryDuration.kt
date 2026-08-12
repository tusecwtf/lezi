package com.lezi.babylog.feature.log.timeline

import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.formatRecordDuration
import com.lezi.babylog.designsystem.LeziTone
import com.lezi.babylog.domain.carelog.CareDayBounds
import com.lezi.babylog.domain.carelog.DailySummary
import com.lezi.babylog.domain.carelog.LongBound
import com.lezi.babylog.domain.carelog.SuspectedDuplicatePresentation
import com.lezi.babylog.domain.carelog.formatRange

/**
 * Compact historical duration for log day-summary chips.
 * One vocabulary with SummaryScreen nursing totals and sleep everywhere.
 */
internal fun logDaySummaryDuration(minutes: Long): String =
    formatRecordDuration(minutes)

internal fun logDaySummarySleepSpoken(minutes: Long): String =
    "睡眠 ${logDaySummaryDuration(minutes)}"

internal const val LOG_DAY_SUMMARY_COLUMN_COUNT = 4

/**
 * Record-home day glance: four equal columns.
 *
 * 奶量 is the single feeding cell and already uses [DailySummary.feedMl]
 * (formula + pumped feed + nursing ml). Nursing duration stays on the
 * timeline / day-chart legend, not as a fifth chip.
 */
internal data class LogDaySummaryColumn(
    val type: RecordType,
    val tone: LeziTone,
    val label: String,
    val compactLabel: String,
    val value: String,
    val compactValue: String,
    val spokenValue: String,
)

internal fun logDaySummaryColumns(
    summary: DailySummary,
    bounds: CareDayBounds?,
): List<LogDaySummaryColumn> {
    val feedValue = bounds?.feedMl?.let {
        SuspectedDuplicatePresentation.formatMetricBound(it, "ml")
    } ?: "${summary.feedMl}ml"
    val feedSpoken = bounds?.feedMl?.let {
        "奶量 ${SuspectedDuplicatePresentation.formatMetricBound(it, "毫升")}"
    } ?: "奶量 ${summary.feedMl}毫升"
    val sleepValue = bounds?.sleepMinutes?.let(::formatDaySummaryDurationBound)
        ?: logDaySummaryDuration(summary.sleepMinutes)
    val sleepSpoken = bounds?.sleepMinutes?.let {
        "睡眠 ${formatDaySummaryDurationBound(it)}"
    } ?: logDaySummarySleepSpoken(summary.sleepMinutes)
    val peeValue = bounds?.peeCount?.let {
        SuspectedDuplicatePresentation.formatMetricBound(it, "次")
    } ?: "${summary.peeCount}次"
    val peeSpoken = bounds?.peeCount?.let {
        "尿尿 ${SuspectedDuplicatePresentation.formatMetricBound(it, "次")}"
    } ?: "尿尿 ${summary.peeCount}次"
    val poopValue = bounds?.poopCount?.let {
        SuspectedDuplicatePresentation.formatMetricBound(it, "次")
    } ?: "${summary.poopCount}次"
    val poopSpoken = bounds?.poopCount?.let {
        "便便 ${SuspectedDuplicatePresentation.formatMetricBound(it, "次")}"
    } ?: "便便 ${summary.poopCount}次"
    return listOf(
        LogDaySummaryColumn(
            type = RecordType.FORMULA,
            tone = LeziTone.Blue,
            label = "奶量",
            compactLabel = "奶ml",
            value = feedValue,
            compactValue = bounds?.feedMl?.formatRange() ?: "${summary.feedMl}",
            spokenValue = feedSpoken,
        ),
        LogDaySummaryColumn(
            type = RecordType.SLEEP,
            tone = LeziTone.Yellow,
            label = "睡眠",
            compactLabel = "睡眠",
            value = sleepValue,
            compactValue = sleepValue,
            spokenValue = sleepSpoken,
        ),
        LogDaySummaryColumn(
            type = RecordType.PEE,
            tone = LeziTone.Cream,
            label = "尿尿",
            compactLabel = "尿",
            value = peeValue,
            compactValue = bounds?.peeCount?.formatRange() ?: "${summary.peeCount}",
            spokenValue = peeSpoken,
        ),
        LogDaySummaryColumn(
            type = RecordType.POOP,
            tone = LeziTone.Neutral,
            label = "便便",
            compactLabel = "便",
            value = poopValue,
            compactValue = bounds?.poopCount?.formatRange() ?: "${summary.poopCount}",
            spokenValue = poopSpoken,
        ),
    )
}

internal fun formatDaySummaryDurationBound(bound: LongBound): String =
    if (bound.min == bound.max) {
        logDaySummaryDuration(bound.min)
    } else {
        "${logDaySummaryDuration(bound.min)}–${logDaySummaryDuration(bound.max)}"
    }
