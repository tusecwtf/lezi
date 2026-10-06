package com.lezi.babylog.domain.carelog
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class BabyAgeLabelTest {
    @Test
    fun newbornShowsZeroMonthsAndZeroDays() {
        val today = LocalDate.of(2026, 7, 24)

        assertThat(babyAgeLabel(today.toEpochDay(), today))
            .isEqualTo("0个月0天")
    }

    @Test
    fun babyUnderOneYearShowsCompleteMonthsAndRemainingDays() {
        val birth = LocalDate.of(2026, 1, 10)
        val today = LocalDate.of(2026, 7, 24)

        assertThat(babyAgeLabel(birth.toEpochDay(), today))
            .isEqualTo("6个月14天")
    }

    @Test
    fun monthEndBirthdayUsesAdjustedCalendarAnniversary() {
        val birth = LocalDate.of(2026, 1, 31)
        val today = LocalDate.of(2026, 2, 28)

        assertThat(babyAgeLabel(birth.toEpochDay(), today))
            .isEqualTo("1个月0天")
    }

    @Test
    fun exactFirstBirthdaySwitchesToYearsAndMonths() {
        val birth = LocalDate.of(2025, 7, 24)
        val today = LocalDate.of(2026, 7, 24)

        assertThat(babyAgeLabel(birth.toEpochDay(), today))
            .isEqualTo("1岁0个月")
    }

    @Test
    fun babyOlderThanOneYearOmitsRemainingDays() {
        val birth = LocalDate.of(2024, 1, 10)
        val today = LocalDate.of(2026, 7, 24)

        assertThat(babyAgeLabel(birth.toEpochDay(), today))
            .isEqualTo("2岁6个月")
    }

    @Test
    fun olderBabyMonthEndBirthdayUsesAdjustedCalendarAnniversary() {
        val birth = LocalDate.of(2025, 1, 31)
        val today = LocalDate.of(2026, 2, 28)

        assertThat(babyAgeLabel(birth.toEpochDay(), today))
            .isEqualTo("1岁1个月")
    }

    @Test
    fun leapDayBirthdayUsesAdjustedCalendarAnniversary() {
        val birth = LocalDate.of(2024, 2, 29)
        val today = LocalDate.of(2025, 2, 28)

        assertThat(babyAgeLabel(birth.toEpochDay(), today))
            .isEqualTo("1岁0个月")
    }

    @Test
    fun futureBirthdayStillShowsUnborn() {
        val today = LocalDate.of(2026, 7, 24)
        val birth = today.plusDays(1)

        assertThat(babyAgeLabel(birth.toEpochDay(), today))
            .isEqualTo("未出生")
    }

    @Test
    fun shortLabelNewbornShowsZeroMonthsAndZeroDays() {
        val today = LocalDate.of(2026, 7, 24)

        assertThat(babyAgeShortLabel(today.toEpochDay(), today))
            .isEqualTo("0月0天")
    }

    @Test
    fun shortLabelBabyUnderOneYearDropsTheCounterWord() {
        val birth = LocalDate.of(2026, 1, 10)
        val today = LocalDate.of(2026, 7, 24)

        assertThat(babyAgeShortLabel(birth.toEpochDay(), today))
            .isEqualTo("6月14天")
    }

    @Test
    fun shortLabelExactFirstBirthdaySwitchesToYearsAndMonths() {
        val birth = LocalDate.of(2025, 7, 24)
        val today = LocalDate.of(2026, 7, 24)

        assertThat(babyAgeShortLabel(birth.toEpochDay(), today))
            .isEqualTo("1岁0月")
    }

    @Test
    fun shortLabelOlderBabyOmitsRemainingDays() {
        val birth = LocalDate.of(2024, 1, 10)
        val today = LocalDate.of(2026, 7, 24)

        assertThat(babyAgeShortLabel(birth.toEpochDay(), today))
            .isEqualTo("2岁6月")
    }

    @Test
    fun shortLabelLeapDayBirthdayUsesAdjustedCalendarAnniversary() {
        val birth = LocalDate.of(2024, 2, 29)
        val today = LocalDate.of(2025, 2, 28)

        assertThat(babyAgeShortLabel(birth.toEpochDay(), today))
            .isEqualTo("1岁0月")
    }

    @Test
    fun shortLabelFutureBirthdayStillShowsUnborn() {
        val today = LocalDate.of(2026, 7, 24)
        val birth = today.plusDays(1)

        assertThat(babyAgeShortLabel(birth.toEpochDay(), today))
            .isEqualTo("未出生")
    }
}
