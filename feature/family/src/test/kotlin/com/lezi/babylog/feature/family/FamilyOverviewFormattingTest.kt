package com.lezi.babylog.feature.family

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class FamilyOverviewFormattingTest {
    @Test
    fun fractionalBirthWeightUsesStableDecimalSeparator() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)

            assertThat(formatBirthWeightKg(3_250)).isEqualTo("3.25kg")
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun wholeKilogramsDoNotShowTrailingDecimals() {
        assertThat(formatBirthWeightKg(3_000)).isEqualTo("3kg")
    }
}
