package com.lezi.babylog.feature.family

import com.lezi.babylog.core.model.Sex
import com.lezi.babylog.core.ui.babyMetaLine
import com.lezi.babylog.core.ui.formatBabyBirthday
import com.lezi.babylog.core.ui.formatBirthWeightKg
import com.lezi.babylog.domain.carelog.babyAgeLabel
import com.lezi.babylog.feature.family.overview.familyCurrentBabyMeta
import com.lezi.babylog.feature.family.overview.familyListBabyMeta
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Family overview meta assembly: age + [babyMetaLine] + sex/dup flags.
 * Birthday/weight tokens must match core helpers (no inline re-format).
 */
class FamilyOverviewFormattingTest {
    private val day = LocalDate.of(2024, 3, 5)
    private val epoch = day.toEpochDay()
    private val today = LocalDate.of(2024, 9, 5)

    @Test
    fun currentCardMetaUsesBabyMetaLinePlusSexAndAge() {
        val expectedMeta = babyMetaLine(epoch, 3_200)
        val expectedAge = babyAgeLabel(epoch, today)
        val line = familyCurrentBabyMeta(
            birthdayEpochDay = epoch,
            birthWeightGrams = 3_200,
            sex = Sex.FEMALE,
            today = today,
        )
        assertTrue(line.startsWith(expectedMeta))
        assertTrue(line.contains("女宝"))
        assertTrue(line.endsWith(expectedAge))
        assertEquals(
            "$expectedMeta · 女宝 · $expectedAge",
            line,
        )
        // Token equality with shared helpers (not a reimplemented pattern).
        assertEquals("2024年3月5日", formatBabyBirthday(epoch))
        assertEquals("3.20kg", formatBirthWeightKg(3_200))
    }

    @Test
    fun listCardMetaUsesAgeThenBabyMetaLineAndDupFlag() {
        val expectedMeta = babyMetaLine(epoch, null)
        val expectedAge = babyAgeLabel(epoch, today)
        assertEquals(
            "$expectedAge · $expectedMeta",
            familyListBabyMeta(
                birthdayEpochDay = epoch,
                birthWeightGrams = null,
                nicknameDuplicate = false,
                today = today,
            ),
        )
        assertEquals(
            "$expectedAge · $expectedMeta · 昵称重复",
            familyListBabyMeta(
                birthdayEpochDay = epoch,
                birthWeightGrams = null,
                nicknameDuplicate = true,
                today = today,
            ),
        )
    }
}
