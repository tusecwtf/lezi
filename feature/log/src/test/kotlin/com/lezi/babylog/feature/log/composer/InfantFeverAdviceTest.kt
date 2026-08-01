package com.lezi.babylog.feature.log.composer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

class InfantFeverAdviceTest {
    private val zone = ZoneOffset.UTC
    private val birthday = LocalDate.of(2026, 1, 31)

    @Test
    fun adviceUsesRecordDateAndStrictThreeMonthBoundary() {
        assertTrue(adviceAt(LocalDate.of(2026, 4, 29), 38.0))
        assertFalse(adviceAt(LocalDate.of(2026, 4, 30), 38.0))
        assertFalse(adviceAt(LocalDate.of(2026, 5, 1), 39.0))
    }

    @Test
    fun adviceRequiresThresholdAndCanBeDisabled() {
        assertFalse(adviceAt(LocalDate.of(2026, 2, 1), 37.9))
        assertFalse(adviceAt(LocalDate.of(2026, 2, 1), 38.0, enabled = false))
        assertFalse(
            shouldShowInfantFeverAdvice(
                birthdayEpochDay = null,
                recordTimestamp = timestamp(LocalDate.of(2026, 2, 1)),
                celsius = 38.0,
                enabled = true,
                zone = zone,
            ),
        )
    }

    private fun adviceAt(date: LocalDate, celsius: Double, enabled: Boolean = true): Boolean =
        shouldShowInfantFeverAdvice(
            birthdayEpochDay = birthday.toEpochDay(),
            recordTimestamp = timestamp(date),
            celsius = celsius,
            enabled = enabled,
            zone = zone,
        )

    private fun timestamp(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()
}
