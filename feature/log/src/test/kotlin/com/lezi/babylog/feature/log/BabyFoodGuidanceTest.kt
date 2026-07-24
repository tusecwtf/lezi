package com.lezi.babylog.feature.log

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BabyFoodGuidanceTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun monthAgeUsesCompletedMonthsOnLocalDate() {
        val birthday = LocalDate.of(2025, 1, 15).toEpochDay()
        val at = ZonedDateTime.of(2025, 7, 14, 10, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        // 2025-01-15 → 2025-07-14 is not yet 6 full months
        assertEquals(5, monthAgeAt(birthday, at, zone))

        val sixMonths = ZonedDateTime.of(2025, 7, 15, 9, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        assertEquals(6, monthAgeAt(birthday, sixMonths, zone))
    }

    @Test
    fun stageResolvesIronCerealBandAtSixMonths() {
        val stage = resolveBabyFoodStage(6)
        assertEquals("m6_7", stage.id)
        assertTrue(stage.suggestions.contains("高铁米粉"))
    }

    @Test
    fun stageBeforeSixHasNoSolidSuggestions() {
        val stage = resolveBabyFoodStage(4)
        assertEquals("pre6", stage.id)
        assertTrue(stage.suggestions.isEmpty())
    }

    @Test
    fun guidanceBundlesAgeLabelAndStage() {
        val birthday = LocalDate.of(2024, 1, 1).toEpochDay()
        val at = ZonedDateTime.of(2025, 1, 1, 12, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val guidance = babyFoodGuidanceAt(birthday, at, zone)
        assertEquals(12, guidance.monthAge)
        assertEquals("约 12 月龄", guidance.ageLabel)
        assertEquals("m12_plus", guidance.stage.id)
    }

    @Test
    fun stagesCoverZeroThroughOpenEndWithoutGaps() {
        for (month in 0..36) {
            val stage = resolveBabyFoodStage(month)
            assertTrue(
                "month $month should map to a stage",
                month >= stage.minMonthInclusive &&
                    (stage.maxMonthExclusive == null || month < stage.maxMonthExclusive),
            )
        }
    }
}
