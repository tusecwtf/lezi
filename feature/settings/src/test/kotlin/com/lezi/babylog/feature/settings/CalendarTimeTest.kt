package com.lezi.babylog.feature.settings

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarTimeTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(
        LocalDate.of(2026, 7, 23),
        LocalTime.of(10, 30),
        zone,
    )

    @Test
    fun historicalAnchorDefaultsToTomorrow() {
        val result = defaultCalendarEventAt(
            initialDate = LocalDate.of(2026, 7, 1),
            zone = zone,
            now = now,
        )

        assertEquals(now.plusDays(1).withSecond(0).withNano(0).toInstant().toEpochMilli(), result)
    }

    @Test
    fun validationRequiresFutureEventAndOrderedReminder() {
        val nowMillis = now.toInstant().toEpochMilli()
        val event = now.plusHours(2).toInstant().toEpochMilli()

        assertTrue(calendarEventError("", event, null, nowMillis)!!.isNotBlank())
        assertTrue(calendarEventError("复诊", nowMillis, null, nowMillis)!!.isNotBlank())
        assertTrue(calendarEventError("复诊", event, event, nowMillis)!!.isNotBlank())
        assertNull(
            calendarEventError(
                title = "复诊",
                eventAt = event,
                remindAt = now.plusHours(1).toInstant().toEpochMilli(),
                now = nowMillis,
            ),
        )
    }

    @Test
    fun changingEventKeepsReminderLeadTimeOrFallsBackToOneHour() {
        val event = now.plusDays(3).toInstant().toEpochMilli()
        val reminder = now.plusDays(3).minusHours(2).toInstant().toEpochMilli()
        val changedEvent = now.plusDays(5).toInstant().toEpochMilli()

        assertEquals(
            changedEvent - 2 * 60 * 60_000L,
            reminderAfterEventChange(event, changedEvent, reminder),
        )
        assertEquals(
            changedEvent - 60 * 60_000L,
            reminderAfterEventChange(event, changedEvent, changedEvent),
        )
    }
}
