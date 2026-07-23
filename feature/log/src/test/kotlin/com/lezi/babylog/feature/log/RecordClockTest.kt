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
    fun earlierSleepClockResolvesToNextDay() {
        val start = ZonedDateTime.of(
            LocalDate.of(2026, 7, 22),
            LocalTime.of(23, 30),
            zone,
        )

        val end = resolveSleepEnd(start, LocalTime.of(6, 15))

        assertEquals(LocalDate.of(2026, 7, 23), end!!.toLocalDate())
        assertEquals(LocalTime.of(6, 15), end.toLocalTime())
        assertNull(resolveSleepEnd(start, LocalTime.of(23, 30)))
    }
}
