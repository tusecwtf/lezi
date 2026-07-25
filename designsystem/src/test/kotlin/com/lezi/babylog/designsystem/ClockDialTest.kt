package com.lezi.babylog.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockDialTest {
    @Test
    fun twelveHourLabelUsesChineseAmPm() {
        assertEquals("上午 12:00", formatClockTime12h(0, 0))
        assertEquals("上午 8:05", formatClockTime12h(8, 5))
        assertEquals("下午 12:00", formatClockTime12h(12, 0))
        assertEquals("下午 10:00", formatClockTime12h(22, 0))
    }

    @Test
    fun applyClockPeriodAddsOrSubtractsTwelveWithinDay() {
        // 下午 = +12 when morning; 上午 = -12 when afternoon
        assertEquals(22, applyClockPeriod(10, wantAm = false))
        assertEquals(10, applyClockPeriod(22, wantAm = true))
        assertEquals(15, applyClockPeriod(3, wantAm = false))
        assertEquals(3, applyClockPeriod(15, wantAm = true))
        // already in half-day → no-op
        assertEquals(15, applyClockPeriod(15, wantAm = false))
        assertEquals(3, applyClockPeriod(3, wantAm = true))
    }

    @Test
    fun applyClockPeriodUsesStandardWallClockAtMidnightAndNoon() {
        // 0:00 midnight 上午 ↔ 12:00 noon 下午
        assertEquals(12, applyClockPeriod(0, wantAm = false))
        assertEquals(0, applyClockPeriod(12, wantAm = true))
        assertEquals(0, applyClockPeriod(0, wantAm = true))
        assertEquals(12, applyClockPeriod(12, wantAm = false))
        // never 24
        assertEquals(12, applyClockPeriod(0, wantAm = false))
        assertTrue(applyClockPeriod(11, wantAm = false) in 0..23)
        assertTrue(applyClockPeriod(23, wantAm = true) in 0..23)
    }

    @Test
    fun isClockAmMatchesWallClockHalfDay() {
        assertTrue(isClockAm(0))
        assertTrue(isClockAm(11))
        assertTrue(!isClockAm(12))
        assertTrue(!isClockAm(23))
    }

}
