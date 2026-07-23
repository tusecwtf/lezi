package com.lezi.babylog.feature.log

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordClockTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun newRecordUsesSelectedHistoricalDateAndCurrentClock() {
        val now = ZonedDateTime.of(
            LocalDate.of(2026, 7, 23),
            LocalTime.of(14, 26, 41),
            zone,
        )

        val timestamp = timestampOnDate(LocalDate.of(2026, 7, 17), zone, now)
        val result = java.time.Instant.ofEpochMilli(timestamp).atZone(zone)

        assertEquals(LocalDate.of(2026, 7, 17), result.toLocalDate())
        assertEquals(LocalTime.of(14, 26), result.toLocalTime())
    }

    @Test
    fun futureInitialDateIsClampedToToday() {
        val now = ZonedDateTime.of(
            LocalDate.of(2026, 7, 23),
            LocalTime.NOON,
            zone,
        )

        val timestamp = timestampOnDate(LocalDate.of(2026, 7, 30), zone, now)

        assertEquals(now.withSecond(0).withNano(0).toInstant().toEpochMilli(), timestamp)
    }

    @Test
    fun movingIntervalStartKeepsItsDuration() {
        val oldStart = 1_000_000L
        val oldEnd = oldStart + 45 * 60_000L
        val newStart = oldStart - 24 * 60 * 60_000L

        assertEquals(
            newStart + 45 * 60_000L,
            shiftStartPreservingDuration(oldStart, oldEnd, newStart),
        )
        assertNull(shiftStartPreservingDuration(oldStart, null, newStart))
    }
}
