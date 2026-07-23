package com.lezi.babylog.feature.growth

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class GrowthTimeTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun selectedHistoricalDateUsesCurrentLocalClock() {
        val now = ZonedDateTime.of(
            LocalDate.of(2026, 7, 23),
            LocalTime.of(14, 37, 42),
            zone,
        )

        val timestamp = timestampOnGrowthDate(
            date = LocalDate.of(2026, 7, 20),
            zone = zone,
            now = now,
        )

        assertEquals(
            ZonedDateTime.of(
                LocalDate.of(2026, 7, 20),
                LocalTime.of(14, 37),
                zone,
            ),
            Instant.ofEpochMilli(timestamp).atZone(zone),
        )
    }

    @Test
    fun futureDateIsClampedToToday() {
        val now = ZonedDateTime.of(
            LocalDate.of(2026, 7, 23),
            LocalTime.of(9, 5),
            zone,
        )

        val timestamp = timestampOnGrowthDate(
            date = LocalDate.of(2026, 7, 30),
            zone = zone,
            now = now,
        )

        assertEquals(now, Instant.ofEpochMilli(timestamp).atZone(zone))
    }
}
