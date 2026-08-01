package com.lezi.babylog.feature.settings.calendar

import com.lezi.babylog.core.model.RecordTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

internal data class CalendarMonthCell(
    val date: LocalDate,
    val isEnabled: Boolean,
    val isToday: Boolean,
)

internal data class CalendarMonthWindow(
    val startInclusive: Long,
    val endExclusive: Long,
)

/** Epoch bounds for one local calendar month; never assumes every day is 24 hours. */
internal fun calendarMonthWindow(
    visibleMonth: YearMonth,
    zone: ZoneId,
): CalendarMonthWindow = CalendarMonthWindow(
    startInclusive = visibleMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    endExclusive = visibleMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
)

/** A stable six-week, Monday-first grid; overflow days are visible but disabled. */
internal fun calendarMonthCells(
    visibleMonth: YearMonth,
    today: LocalDate,
): List<CalendarMonthCell> {
    val firstOfMonth = visibleMonth.atDay(1)
    val gridStart = firstOfMonth.minusDays((firstOfMonth.dayOfWeek.value - 1).toLong())
    return List(CALENDAR_MONTH_CELL_COUNT) { index ->
        val date = gridStart.plusDays(index.toLong())
        CalendarMonthCell(
            date = date,
            isEnabled = YearMonth.from(date) == visibleMonth,
            isToday = date == today,
        )
    }
}

/** Items belonging to one device-local calendar date, preserving chronological order. */
internal fun calendarItemsForDate(
    items: List<CalendarDayItem>,
    date: LocalDate,
    zone: ZoneId,
): List<CalendarDayItem> = items
    .asSequence()
    .filter { item ->
        Instant.ofEpochMilli(item.sortAt).atZone(zone).toLocalDate() == date
    }
    .sortedBy(CalendarDayItem::sortAt)
    .toList()

internal fun calendarItemCountsByDate(
    items: List<CalendarDayItem>,
    zone: ZoneId,
): Map<LocalDate, Int> = items.groupingBy { item ->
    Instant.ofEpochMilli(item.sortAt).atZone(zone).toLocalDate()
}.eachCount()

/** Restore the complete month selection from the saveable epoch-day primitive. */
internal fun restoreCalendarMonthState(
    selectedDateEpochDay: Long,
    today: LocalDate,
): CalendarMonthState = CalendarMonthState.initial(
    initialDate = LocalDate.ofEpochDay(selectedDateEpochDay),
    today = today,
)

/**
 * Default a new care plan to the selected day without silently crossing dates.
 * Past dates, and the last minute of today, remain browse-only.
 */
internal fun calendarDefaultCarePlanTimestamp(
    selectedDate: LocalDate,
    zone: ZoneId,
    now: ZonedDateTime = ZonedDateTime.now(zone),
): Long? {
    val today = now.toLocalDate()
    if (selectedDate.isBefore(today)) return null
    val candidate = if (selectedDate == today) {
        val preferred = now
            .plusMinutes(DEFAULT_CARE_PLAN_EDIT_MARGIN_MINUTES)
            .withSecond(0)
            .withNano(0)
        preferred.takeIf { it.toLocalDate() == selectedDate }
            ?: selectedDate.atTime(LocalTime.of(23, 59)).atZone(zone)
    } else {
        Instant.ofEpochMilli(
            RecordTime.defaultFutureEventTimestamp(selectedDate, zone, now),
        ).atZone(zone)
    }
    return candidate
        .takeIf { it.isAfter(now) && it.toLocalDate() == selectedDate }
        ?.toInstant()
        ?.toEpochMilli()
}

private const val DEFAULT_CARE_PLAN_EDIT_MARGIN_MINUTES = 15L

internal object CalendarUiTags {
    const val MonthGrid = "calendar_month_grid"
    const val PreviousMonth = "calendar_previous_month"
    const val NextMonth = "calendar_next_month"
    const val ScheduleCare = "calendar_schedule_care"
    const val SelectedDayItems = "calendar_selected_day_items"

    fun day(date: LocalDate): String = "calendar_day_$date"
}

/** Pure selection/navigation state for the Lezi month calendar. */
internal data class CalendarMonthState(
    val visibleMonth: YearMonth,
    val selectedDate: LocalDate,
    val today: LocalDate,
) {
    fun previousMonth(): CalendarMonthState = moveMonth(-1)

    fun nextMonth(): CalendarMonthState = moveMonth(1)

    fun selectDate(date: LocalDate): CalendarMonthState =
        if (YearMonth.from(date) == visibleMonth) copy(selectedDate = date) else this

    private fun moveMonth(delta: Long): CalendarMonthState {
        val target = visibleMonth.plusMonths(delta)
        val selectedDay = selectedDate.dayOfMonth.coerceAtMost(target.lengthOfMonth())
        return copy(
            visibleMonth = target,
            selectedDate = target.atDay(selectedDay),
        )
    }

    companion object {
        fun initial(initialDate: LocalDate, today: LocalDate): CalendarMonthState =
            CalendarMonthState(
                visibleMonth = YearMonth.from(initialDate),
                selectedDate = initialDate,
                today = today,
            )
    }
}

private const val CALENDAR_MONTH_CELL_COUNT = 6 * 7
