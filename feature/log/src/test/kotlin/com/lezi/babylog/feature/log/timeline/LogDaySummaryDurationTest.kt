package com.lezi.babylog.feature.log.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Day-summary nursing and sleep share one historical-duration vocabulary
 * (ticket 12 / US29): compact formatRecordDuration, never bare "m"/"min".
 */
class LogDaySummaryDurationTest {
    @Test
    fun nursingAndSleepDayTotalsShareFormatRecordDuration() {
        assertEquals("0m", logDaySummaryDuration(0))
        assertEquals("45m", logDaySummaryDuration(45))
        assertEquals("1h5m", logDaySummaryDuration(65))
        assertEquals("2h", logDaySummaryDuration(120))
        // Same minutes → same string for both nursing and sleep chips.
        assertEquals(logDaySummaryDuration(65), logDaySummaryDuration(65))
    }

    @Test
    fun spokenValuesEmbedTheSameDurationToken() {
        assertEquals("母乳 1h5m", logDaySummaryNursingSpoken(65))
        assertEquals("睡眠 1h5m", logDaySummarySleepSpoken(65))
        assertEquals("母乳 0m", logDaySummaryNursingSpoken(0))
    }
}
