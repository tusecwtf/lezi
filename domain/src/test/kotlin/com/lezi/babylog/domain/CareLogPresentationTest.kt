package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

class CareLogPresentationTest {
    @Test
    fun relativeTimeLabelKeepsFrozenBoundariesAndClampsFutureTimes() {
        val now = 2 * 24 * 60 * 60 * 1_000L

        assertThat(CareLogPresentation.relativeTimeLabel(timestamp = now + 1L, now = now))
            .isEqualTo("刚刚")
        assertThat(CareLogPresentation.relativeTimeLabel(timestamp = now - 60_000L, now = now))
            .isEqualTo("1 分钟前")
        assertThat(CareLogPresentation.relativeTimeLabel(timestamp = now - 3_600_000L, now = now))
            .isEqualTo("1 小时前")
        assertThat(CareLogPresentation.relativeTimeLabel(timestamp = now - 86_400_000L, now = now))
            .isEqualTo("1 天前")
    }

    @Test
    fun formatClockUsesTheSuppliedZone() {
        assertThat(CareLogPresentation.formatClock(timestamp = 0L, zone = ZoneId.of("UTC")))
            .isEqualTo("00:00")
        assertThat(
            CareLogPresentation.formatClock(
                timestamp = 0L,
                zone = ZoneId.of("Asia/Shanghai"),
            ),
        ).isEqualTo("08:00")
    }

    @Test
    fun babyAgeLabelUsesCalendarAnniversariesAndRejectsFutureBirthdays() {
        val monthEndBirth = LocalDate.of(2026, 1, 31)
        val adjustedAnniversary = LocalDate.of(2026, 2, 28)

        assertThat(
            CareLogPresentation.babyAgeLabel(
                birthdayEpochDay = monthEndBirth.toEpochDay(),
                today = adjustedAnniversary,
            ),
        ).isEqualTo("1个月0天")
        assertThat(
            CareLogPresentation.babyAgeLabel(
                birthdayEpochDay = adjustedAnniversary.plusDays(1).toEpochDay(),
                today = adjustedAnniversary,
            ),
        ).isEqualTo("未出生")
    }

    @Test
    fun amountCandidatesInsertsAnOffGridLastValueOnceInStableOrder() {
        assertThat(CareLogPresentation.amountCandidates(step = 50, lastMl = 125))
            .containsExactly(30, 80, 125, 130, 180, 230, 280)
            .inOrder()
        assertThat(CareLogPresentation.amountCandidates(step = 50, lastMl = 130))
            .containsExactly(30, 80, 130, 180, 230, 280)
            .inOrder()
    }

    @Test
    fun amountCenterIndexPrefersExactThenNearestAndDefaultsAround120() {
        val candidates = listOf(30, 80, 130, 180)

        assertThat(CareLogPresentation.amountCenterIndex(emptyList(), lastMl = 80)).isEqualTo(0)
        assertThat(CareLogPresentation.amountCenterIndex(candidates, lastMl = null)).isEqualTo(2)
        assertThat(CareLogPresentation.amountCenterIndex(candidates, lastMl = 80)).isEqualTo(1)
        assertThat(CareLogPresentation.amountCenterIndex(candidates, lastMl = 121)).isEqualTo(2)
    }
}
