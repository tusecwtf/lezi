package com.lezi.babylog.core.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class BabyMetaLineTest {
    @Test
    fun babyMetaLineUsesOneFormatForEverySurface() {
        assertEquals(
            "2024年3月5日",
            formatBabyBirthday(LocalDate.of(2024, 3, 5).toEpochDay()),
        )
        assertEquals("3.20kg", formatBirthWeightKg(3_200))
        assertEquals("3kg", formatBirthWeightKg(3_000))
        assertEquals(
            "2024年1月2日出生 · 出生体重 3.20kg",
            babyMetaLine(LocalDate.of(2024, 1, 2).toEpochDay(), 3_200),
        )
        assertEquals(
            "2024年1月2日出生",
            babyMetaLine(LocalDate.of(2024, 1, 2).toEpochDay(), null),
        )
    }
}
