package com.lezi.babylog.feature.family

import com.lezi.babylog.core.ui.formatBabyBirthday
import com.lezi.babylog.core.ui.formatBirthWeightKg
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class FamilyOverviewFormattingTest {
    @Test
    fun familyOverviewUsesOneBirthdayAndWeightFormatForEveryBabyCard() {
        assertEquals(
            "2024年3月5日",
            formatBabyBirthday(LocalDate.of(2024, 3, 5).toEpochDay()),
        )
        assertEquals("3.20kg", formatBirthWeightKg(3_200))
        assertEquals("3kg", formatBirthWeightKg(3_000))
    }
}
