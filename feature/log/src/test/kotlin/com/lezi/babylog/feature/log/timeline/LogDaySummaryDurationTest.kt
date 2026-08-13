package com.lezi.babylog.feature.log.timeline

import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.designsystem.LeziTone
import com.lezi.babylog.domain.carelog.CareDayBounds
import com.lezi.babylog.domain.carelog.DailySummary
import com.lezi.babylog.domain.carelog.IntBound
import com.lezi.babylog.domain.carelog.LongBound
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Day-summary glance is four columns. Nursing duration stays off the strip;
 * milk volume is the single feeding cell using feedMl (formula + pumped +
 * nursing ml). Sleep still shares formatRecordDuration with other totals.
 */
class LogDaySummaryDurationTest {
    @Test
    fun nursingAndSleepDayTotalsShareFormatRecordDuration() {
        assertEquals("0m", logDaySummaryDuration(0))
        assertEquals("45m", logDaySummaryDuration(45))
        assertEquals("1h5m", logDaySummaryDuration(65))
        assertEquals("2h", logDaySummaryDuration(120))
        assertEquals(logDaySummaryDuration(65), logDaySummaryDuration(65))
    }

    @Test
    fun spokenSleepEmbedsTheSameDurationToken() {
        assertEquals("睡眠 1h5m", logDaySummarySleepSpoken(65))
        assertEquals("睡眠 0m", logDaySummarySleepSpoken(0))
    }

    @Test
    fun glanceMergesEveryFeedMlIntoFourColumns() {
        val columns = logDaySummaryColumns(
            DailySummary(
                sleepMinutes = 65,
                peeCount = 2,
                poopCount = 1,
                formulaMl = 120,
                nursingMinutes = 45,
                pumpedFeedMl = 50,
                feedMl = 200,
            ),
            bounds = null,
        )

        assertEquals(LOG_DAY_SUMMARY_COLUMN_COUNT, columns.size)
        assertEquals(
            listOf("奶量", "睡眠", "尿尿", "便便"),
            columns.map(LogDaySummaryColumn::label),
        )
        assertEquals(
            listOf("奶ml", "睡眠", "尿", "便"),
            columns.map(LogDaySummaryColumn::compactLabel),
        )
        assertEquals(
            listOf(RecordType.FORMULA, RecordType.SLEEP, RecordType.PEE, RecordType.POOP),
            columns.map(LogDaySummaryColumn::type),
        )
        assertEquals(
            listOf(LeziTone.Blue, LeziTone.Yellow, LeziTone.Cream, LeziTone.Neutral),
            columns.map(LogDaySummaryColumn::tone),
        )
        assertEquals(listOf("200ml", "1h5m", "2次", "1次"), columns.map(LogDaySummaryColumn::value))
        assertEquals(listOf("200", "1h5m", "2", "1"), columns.map(LogDaySummaryColumn::compactValue))
        assertEquals("奶量 200毫升", columns.first().spokenValue)
        assertFalse(columns.any { it.label == "母乳" || it.compactLabel == "母乳" })
        assertFalse(columns.any { it.value.contains("45") })
    }

    @Test
    fun glanceUsesMergedFeedBoundsNotNursingMinutes() {
        val columns = logDaySummaryColumns(
            DailySummary(nursingMinutes = 45, feedMl = 200),
            bounds = exactBounds(feedMl = IntBound(180, 220)),
        )

        assertEquals(LOG_DAY_SUMMARY_COLUMN_COUNT, columns.size)
        assertEquals("180–220ml", columns.first().value)
        assertEquals("180–220", columns.first().compactValue)
        assertEquals("奶量 180–220毫升", columns.first().spokenValue)
        assertFalse(columns.any { it.label == "母乳" })
    }

    private fun exactBounds(
        feedMl: IntBound = IntBound(0, 0),
    ): CareDayBounds {
        val zeroInt = IntBound(0, 0)
        val zeroLong = LongBound(0, 0)
        return CareDayBounds(
            date = LocalDate.of(2026, 8, 13),
            formulaMl = zeroInt,
            pumpedFeedMl = zeroInt,
            nursingMl = zeroInt,
            nursingMinutes = LongBound(45, 45),
            feedCount = zeroInt,
            feedMl = feedMl,
            peeCount = zeroInt,
            poopCount = zeroInt,
            sleepMinutes = zeroLong,
            sleepSegments = zeroInt,
            temperatureCount = zeroInt,
            temperatureAverage = null,
        )
    }
}
