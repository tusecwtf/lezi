package com.lezi.babylog

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class AppHeaderTest {
    private val today = LocalDate.of(2026, 7, 23)

    @Test
    fun futureDateIsClampedToToday() {
        assertThat(clampSelectedDate(today.plusDays(5), today)).isEqualTo(today)
        assertThat(clampSelectedDate(today.minusDays(5), today)).isEqualTo(today.minusDays(5))
    }

    @Test
    fun siblingSameDayAgePreservesWholeDayOffset() {
        val currentBirth = LocalDate.of(2025, 12, 31)
        val siblingBirth = LocalDate.of(2024, 2, 29)
        val selected = currentBirth.plusDays(75)

        assertThat(
            siblingSameDayAgeDate(
                currentBirthdayEpochDay = currentBirth.toEpochDay(),
                siblingBirthdayEpochDay = siblingBirth.toEpochDay(),
                selectedDate = selected,
                today = today,
            ),
        ).isEqualTo(siblingBirth.plusDays(75))
    }

    @Test
    fun siblingSameDayAgeClampsFutureTargetToToday() {
        val currentBirth = LocalDate.of(2025, 1, 1)
        val siblingBirth = LocalDate.of(2026, 6, 1)

        assertThat(
            siblingSameDayAgeDate(
                currentBirthdayEpochDay = currentBirth.toEpochDay(),
                siblingBirthdayEpochDay = siblingBirth.toEpochDay(),
                selectedDate = currentBirth.plusDays(100),
                today = today,
            ),
        ).isEqualTo(today)
    }

    @Test
    fun nextSiblingCyclesAndSingleBabyIsNoOp() {
        assertThat(nextSiblingId(listOf(11L, 22L, 33L), 22L)).isEqualTo(33L)
        assertThat(nextSiblingId(listOf(11L, 22L, 33L), 33L)).isEqualTo(11L)
        assertThat(nextSiblingId(listOf(11L), 11L)).isNull()
        assertThat(nextSiblingId(emptyList(), null)).isNull()
    }

    @Test
    fun compactSecondaryDateIsWeekdayOnly() {
        assertThat(headerSecondaryDateLabel(today)).isEqualTo("7月23日 · 周四")
        assertThat(headerSecondaryDateLabel(today, compact = true)).isEqualTo("周四")
    }
}
