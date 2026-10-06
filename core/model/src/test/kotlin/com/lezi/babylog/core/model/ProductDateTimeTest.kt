package com.lezi.babylog.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductDateTimeTest {
    @Test
    fun formatsChineseProductClocks() {
        val zone = ZoneOffset.UTC
        val at = Instant.parse("2026-07-30T04:35:00Z").toEpochMilli()

        assertEquals("7月30日 04:35", ProductDateTime.monthDayTime(at, zone))
        assertEquals("2026年7月30日 04:35", ProductDateTime.yearMonthDayTime(at, zone))
        assertEquals("2026年7月30日", ProductDateTime.yearMonthDay(LocalDate.of(2026, 7, 30)))
    }
}
