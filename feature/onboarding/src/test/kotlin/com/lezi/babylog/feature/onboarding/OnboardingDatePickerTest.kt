package com.lezi.babylog.feature.onboarding

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingDatePickerTest {
    @Test
    fun `birthday round trip follows Material date picker UTC contract`() {
        val birthday = LocalDate.of(2026, 7, 26)
        val epochDay = birthday.toEpochDay()

        val pickerMillis = epochDay.toDatePickerMillis()

        assertEquals(
            birthday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            pickerMillis,
        )
        assertEquals(epochDay, pickerMillis.datePickerMillisToEpochDay())
    }
}
