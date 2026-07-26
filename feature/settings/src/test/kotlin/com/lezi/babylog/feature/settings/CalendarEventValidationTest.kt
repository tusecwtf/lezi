package com.lezi.babylog.feature.settings

import java.time.Instant
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEventValidationTest {
    @Test
    fun titleBoundaryAllowsFortyCodePointsAndRejectsFortyOne() {
        val now = Instant.parse("2026-07-26T00:00:00Z").toEpochMilli()
        val eventAt = now + 60_000

        assertNull(calendarEventError("宝".repeat(40), eventAt, null, now))
        assertTrue(
            calendarEventError("宝".repeat(41), eventAt, null, now)
                .orEmpty()
                .contains("最多"),
        )
    }
}
