package com.lezi.gf.app.ui.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Month grid for day-jump calendar + 「返回今天」 helper.
 */
object MonthCalendarModel {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    data class Cell(
        val date: LocalDate?,
        val isCurrentMonth: Boolean,
        val isToday: Boolean,
        val isSelected: Boolean,
        val dayOfMonth: Int?,
    )

    data class MonthGrid(
        val yearMonth: YearMonth,
        val cells: List<Cell>, // always 6*7 = 42
        val weekStartsOnMonday: Boolean,
    )

    fun today(): LocalDate = LocalDate.now(zone)

    fun epochDayToLocalDate(epochDay: Long): LocalDate = LocalDate.ofEpochDay(epochDay)

    fun localDateToDayStartMs(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun dayStartMsToLocalDate(dayStartMs: Long): LocalDate =
        java.time.Instant.ofEpochMilli(dayStartMs).atZone(zone).toLocalDate()

    fun buildGrid(
        yearMonth: YearMonth,
        selected: LocalDate,
        today: LocalDate = today(),
        weekStartsOnMonday: Boolean = true,
    ): MonthGrid {
        val first = yearMonth.atDay(1)
        val firstDow = first.dayOfWeek
        val offset = if (weekStartsOnMonday) {
            (firstDow.value - DayOfWeek.MONDAY.value + 7) % 7
        } else {
            firstDow.value % 7 // Sunday=0 style: SUNDAY=7 → 0
        }
        val cells = mutableListOf<Cell>()
        val start = first.minusDays(offset.toLong())
        for (i in 0 until 42) {
            val d = start.plusDays(i.toLong())
            cells += Cell(
                date = d,
                isCurrentMonth = d.month == yearMonth.month,
                isToday = d == today,
                isSelected = d == selected,
                dayOfMonth = d.dayOfMonth,
            )
        }
        return MonthGrid(yearMonth, cells, weekStartsOnMonday)
    }

    fun isNotToday(selectedDayStartMs: Long, nowMs: Long): Boolean {
        val todayStart = localDateToDayStartMs(
            java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(),
        )
        return selectedDayStartMs != todayStart
    }

    fun todayStartMs(nowMs: Long): Long =
        localDateToDayStartMs(
            java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(),
        )
}
