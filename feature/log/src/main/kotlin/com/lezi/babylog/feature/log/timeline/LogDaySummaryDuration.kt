package com.lezi.babylog.feature.log.timeline

import com.lezi.babylog.core.model.formatRecordDuration

/**
 * Compact historical duration for log day-summary chips (nursing + sleep).
 * One vocabulary with SummaryScreen nursing totals and sleep everywhere.
 */
internal fun logDaySummaryDuration(minutes: Long): String =
    formatRecordDuration(minutes)

internal fun logDaySummaryNursingSpoken(minutes: Long): String =
    "母乳 ${logDaySummaryDuration(minutes)}"

internal fun logDaySummarySleepSpoken(minutes: Long): String =
    "睡眠 ${logDaySummaryDuration(minutes)}"
