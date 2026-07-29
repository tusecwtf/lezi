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
}
