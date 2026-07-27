package com.lezi.babylog

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Test

class AppHeaderTest {
    private val today = LocalDate.of(2026, 7, 23)

    @Test
    fun futureDateIsClampedToToday() {
        assertThat(clampSelectedDate(today.plusDays(5), today)).isEqualTo(today)
        assertThat(clampSelectedDate(today.minusDays(5), today)).isEqualTo(today.minusDays(5))
    }

    @Test
    fun calendarGridStartsOnMondayAndAlwaysContainsSixWeeks() {
        val cells = calendarMonthCells(YearMonth.of(2026, 7))

        assertThat(cells).hasSize(42)
        assertThat(cells.indexOf(LocalDate.of(2026, 7, 1))).isEqualTo(2)
        assertThat(cells.filterNotNull()).hasSize(31)
        assertThat(cells.last()).isNull()
    }

    @Test
    fun leapFebruaryContainsAllDates() {
        val cells = calendarMonthCells(YearMonth.of(2024, 2))

        assertThat(cells.filterNotNull()).hasSize(29)
        assertThat(cells).contains(LocalDate.of(2024, 2, 29))
    }
}
