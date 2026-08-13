package com.lezi.babylog.feature.settings.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM contract for empty-day chrome (replaces [CalendarEmptyDayStateTest] device suite).
 * Date scheduleability policy remains in [CalendarMonthStateTest].
 */
class CalendarEmptyDayChromeTest {
    @Test
    fun browseOnlyDayOmitsScheduleActionAndUsesBrowseCopy() {
        assertNull(calendarEmptyDayActionLabel(canScheduleSelectedDate = false))
        assertEquals(
            "该日期仅供查看；护理计划只能安排在未来时刻。",
            calendarEmptyDayMessage(canScheduleSelectedDate = false),
        )
    }

    @Test
    fun scheduleableDayKeepsScheduleActionAndInviteCopy() {
        assertEquals(
            "安排护理",
            calendarEmptyDayActionLabel(canScheduleSelectedDate = true),
        )
        assertEquals(
            "可选择具体记录项目安排护理",
            calendarEmptyDayMessage(canScheduleSelectedDate = true),
        )
    }

    @Test
    fun capabilityToggleSwitchesActionAndMessageTogether() {
        assertEquals("安排护理", calendarEmptyDayActionLabel(true))
        assertNull(calendarEmptyDayActionLabel(false))
        assertNotEquals(
            calendarEmptyDayMessage(true),
            calendarEmptyDayMessage(false),
        )
    }
}
