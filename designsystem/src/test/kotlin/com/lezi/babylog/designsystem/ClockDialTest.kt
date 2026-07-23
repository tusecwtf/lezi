package com.lezi.babylog.designsystem

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockDialTest {
    @Test
    fun fiveMinuteStepSnapsToClosestValidTick() {
        assertEquals(ClockTick(8, 0), snapClock(8, 2, 5))
        assertEquals(ClockTick(8, 5), snapClock(8, 3, 5))
        assertEquals(ClockTick(12, 0), snapClock(11, 58, 5))
        assertEquals(ClockTick(23, 55), snapClock(23, 58, 5))
    }

    @Test
    fun unsupportedStepFallsBackToOneMinute() {
        assertEquals(ClockTick(8, 17), snapClock(8, 17, 3))
    }

    @Test
    fun mergeClockPreservesDateAndZone() {
        val zone = ZoneId.of("Asia/Shanghai")
        val source = ZonedDateTime.of(
            LocalDate.of(2026, 7, 23),
            LocalTime.of(8, 17, 42),
            zone,
        )

        val merged = mergeClock(source, hour = 23, minute = 58, step = 5)

        assertEquals(source.toLocalDate(), merged!!.toLocalDate())
        assertEquals(zone, merged.zone)
        assertEquals(LocalTime.of(23, 55), merged.toLocalTime())
    }

    @Test
    fun mergeDateAndClockUsesCalendarDateAndPreservesZone() {
        val zone = ZoneId.of("Asia/Shanghai")
        val source = ZonedDateTime.of(
            LocalDate.of(2026, 7, 23),
            LocalTime.of(8, 17),
            zone,
        )

        val merged = mergeDateAndClock(
            value = source,
            date = LocalDate.of(2026, 7, 20),
            hour = 23,
            minute = 58,
            step = 5,
        )

        assertEquals(LocalDate.of(2026, 7, 20), merged!!.toLocalDate())
        assertEquals(LocalTime.of(23, 55), merged.toLocalTime())
        assertEquals(zone, merged.zone)
    }

    @Test
    fun dstGapIsRejected() {
        val zone = ZoneId.of("America/New_York")
        val source = ZonedDateTime.of(
            LocalDate.of(2026, 3, 8),
            LocalTime.of(1, 30),
            zone,
        )

        assertNull(mergeClock(source, hour = 2, minute = 30, step = 1))
    }

    @Test
    fun dstOverlapPreservesPreferredOffset() {
        val zone = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 11, 1)
        val time = LocalTime.of(1, 30)

        val daylight = resolveLeziLocalDateTime(date, time, zone, ZoneOffset.ofHours(-4))
        val standard = resolveLeziLocalDateTime(date, time, zone, ZoneOffset.ofHours(-5))

        assertEquals(ZoneOffset.ofHours(-4), daylight!!.offset)
        assertEquals(ZoneOffset.ofHours(-5), standard!!.offset)
    }
}
