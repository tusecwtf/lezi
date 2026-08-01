package com.lezi.babylog.feature.settings.calendar

import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.RecordType
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarMonthStateTest {
    @Test
    fun savedSelectedDateRestoresTheBrowsedMonthAcrossRecreation() {
        val today = LocalDate.of(2026, 7, 27)

        val restored = restoreCalendarMonthState(
            selectedDateEpochDay = LocalDate.of(2026, 9, 12).toEpochDay(),
            today = today,
        )

        assertEquals(YearMonth.of(2026, 9), restored.visibleMonth)
        assertEquals(LocalDate.of(2026, 9, 12), restored.selectedDate)
        assertEquals(today, restored.today)
    }

    @Test
    fun scheduleTargetStaysOnTodayOrFutureSelectionAndRejectsPastDates() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 7, 27, 10, 30, 45, 0, zone)

        assertNull(
            calendarDefaultCarePlanTimestamp(
                selectedDate = LocalDate.of(2026, 7, 26),
                zone = zone,
                now = now,
            ),
        )
        assertEquals(
            ZonedDateTime.of(2026, 7, 27, 10, 45, 0, 0, zone).toInstant().toEpochMilli(),
            calendarDefaultCarePlanTimestamp(
                selectedDate = LocalDate.of(2026, 7, 27),
                zone = zone,
                now = now,
            ),
        )
        assertEquals(
            ZonedDateTime.of(2026, 8, 2, 10, 30, 0, 0, zone).toInstant().toEpochMilli(),
            calendarDefaultCarePlanTimestamp(
                selectedDate = LocalDate.of(2026, 8, 2),
                zone = zone,
                now = now,
            ),
        )
        assertEquals(
            ZonedDateTime.of(2026, 7, 27, 23, 59, 0, 0, zone).toInstant().toEpochMilli(),
            calendarDefaultCarePlanTimestamp(
                selectedDate = LocalDate.of(2026, 7, 27),
                zone = zone,
                now = ZonedDateTime.of(2026, 7, 27, 23, 50, 30, 0, zone),
            ),
        )
        assertNull(
            calendarDefaultCarePlanTimestamp(
                selectedDate = LocalDate.of(2026, 7, 27),
                zone = zone,
                now = ZonedDateTime.of(2026, 7, 27, 23, 59, 30, 0, zone),
            ),
        )
    }

    @Test
    fun initialDateOwnsSelectionAndMonthNavigationClampsDayAtMonthBoundary() {
        val initial = CalendarMonthState.initial(
            initialDate = LocalDate.of(2024, 1, 31),
            today = LocalDate.of(2024, 1, 15),
        )

        assertEquals(YearMonth.of(2024, 1), initial.visibleMonth)
        assertEquals(LocalDate.of(2024, 1, 31), initial.selectedDate)

        val february = initial.nextMonth()
        assertEquals(YearMonth.of(2024, 2), february.visibleMonth)
        assertEquals(LocalDate.of(2024, 2, 29), february.selectedDate)

        val january = february.previousMonth()
        assertEquals(YearMonth.of(2024, 1), january.visibleMonth)
        assertEquals(LocalDate.of(2024, 1, 29), january.selectedDate)
    }

    @Test
    fun monthGridStartsOnMondayAndKeepsCrossMonthFillDisabled() {
        val cells = calendarMonthCells(
            visibleMonth = YearMonth.of(2024, 3),
            today = LocalDate.of(2024, 3, 10),
        )

        assertEquals(42, cells.size)
        assertEquals(LocalDate.of(2024, 2, 26), cells.first().date)
        assertEquals(LocalDate.of(2024, 4, 7), cells.last().date)
        assertEquals(LocalDate.of(2024, 3, 1), cells[4].date)
        assertEquals(1, cells.first().date.dayOfWeek.value)
        assertEquals(7, cells[6].date.dayOfWeek.value)
        assertFalse(cells.first().isEnabled)
        assertTrue(cells[4].isEnabled)
        assertTrue(cells.single { it.date == LocalDate.of(2024, 3, 10) }.isToday)
    }

    @Test
    fun selectingEnabledDayUpdatesSelectionButOverflowFillCannotJumpMonth() {
        val initial = CalendarMonthState.initial(
            initialDate = LocalDate.of(2024, 3, 15),
            today = LocalDate.of(2024, 3, 10),
        )

        val selected = initial.selectDate(LocalDate.of(2024, 3, 20))
        assertEquals(LocalDate.of(2024, 3, 20), selected.selectedDate)

        val overflowIgnored = selected.selectDate(LocalDate.of(2024, 4, 1))
        assertEquals(selected, overflowIgnored)
    }

    @Test
    fun selectedDayFiltersCarePlansUsingDeviceZoneAcrossDst() {
        val newYork = ZoneId.of("America/New_York")
        val selected = LocalDate.of(2024, 3, 10)
        val earlyPlanAt = ZonedDateTime.of(2024, 3, 10, 1, 30, 0, 0, newYork)
            .toInstant()
            .toEpochMilli()
        val planAt = ZonedDateTime.of(2024, 3, 10, 23, 30, 0, 0, newYork)
            .toInstant()
            .toEpochMilli()
        val nextDayAt = ZonedDateTime.of(2024, 3, 11, 0, 15, 0, 0, newYork)
            .toInstant()
            .toEpochMilli()
        val items = listOf(
            CalendarDayItem.Plan(plan(id = 2L, scheduledAt = planAt)),
            CalendarDayItem.Plan(plan(id = 1L, scheduledAt = earlyPlanAt)),
            CalendarDayItem.Plan(plan(id = 3L, scheduledAt = nextDayAt)),
        )

        val newYorkItems = calendarItemsForDate(items, selected, newYork)
        assertEquals(
            listOf(
                CalendarDayItem.Plan::class,
                CalendarDayItem.Plan::class,
            ),
            newYorkItems.map { it::class },
        )
        assertEquals(listOf(earlyPlanAt, planAt), newYorkItems.map { it.sortAt })

        // 23:30 after the spring-forward transition is already March 11 in Shanghai.
        val shanghaiItems = calendarItemsForDate(items, selected, ZoneId.of("Asia/Shanghai"))
        assertEquals(listOf(earlyPlanAt), shanghaiItems.map { it.sortAt })
    }

    @Test
    fun visibleMonthQueryWindowUsesLocalMidnightsAcrossDst() {
        val newYork = ZoneId.of("America/New_York")

        val window = calendarMonthWindow(YearMonth.of(2024, 3), newYork)

        assertEquals(
            ZonedDateTime.of(2024, 3, 1, 0, 0, 0, 0, newYork).toInstant().toEpochMilli(),
            window.startInclusive,
        )
        assertEquals(
            ZonedDateTime.of(2024, 4, 1, 0, 0, 0, 0, newYork).toInstant().toEpochMilli(),
            window.endExclusive,
        )
        assertEquals(31L * 24 * 60 * 60_000L - 60 * 60_000L, window.endExclusive - window.startInclusive)
    }

    private fun plan(id: Long, scheduledAt: Long) = CarePlan(
        id = id,
        clientUuid = "plan-$id",
        babyId = 1L,
        type = RecordType.BATH,
        scheduledAt = scheduledAt,
        scheduledZoneId = "America/New_York",
        updatedAt = scheduledAt,
    )

}
