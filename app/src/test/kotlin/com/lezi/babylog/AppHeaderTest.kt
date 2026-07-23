package com.lezi.babylog

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Test

class AppHeaderTest {
    private val today = LocalDate.of(2026, 7, 23)

    @Test
    fun primaryDateLabelDistinguishesTodayYesterdayAndHistoricalDates() {
        assertThat(headerPrimaryDateLabel(today, today)).isEqualTo("今天")
        assertThat(headerPrimaryDateLabel(today.minusDays(1), today)).isEqualTo("昨天")
        assertThat(headerPrimaryDateLabel(LocalDate.of(2026, 6, 24), today))
            .isEqualTo("6月24日")
    }

    @Test
    fun babyPrimaryLabelAnnouncesActiveSleepAndKeepsNormalLabelOtherwise() {
        assertThat(headerBabyPrimaryLabel("年年", sleeping = true))
            .isEqualTo("年年睡觉中")
        assertThat(headerBabyPrimaryLabel("年年", sleeping = false))
            .isEqualTo("年年")
        assertThat(headerBabyPrimaryLabel("", sleeping = true))
            .isEqualTo("乐记睡觉中")
    }

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

    @Test
    fun selectedDateContentKeepsSmallTextContrastAcrossBothThemes() {
        listOf(
            Color(0xFFEA7C8F),
            Color(0xFF007BAE),
        ).forEach { background ->
            val foreground = calendarContentColor(background)
            val lighter = maxOf(background.luminance(), foreground.luminance())
            val darker = minOf(background.luminance(), foreground.luminance())
            val contrast = (lighter + 0.05f) / (darker + 0.05f)

            assertThat(contrast).isAtLeast(4.5f)
        }
    }
}
