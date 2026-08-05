package com.lezi.babylog.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Runtime journal/warm color policy. Source-string / PRD text scans removed
 * (test-redundancy Wave 1 / StructureTest ban).
 */
class JournalThemeAndDockPolicyContractTest {
    @Test
    fun `journal baby theme drives primary and header accent in light and dark`() {
        val babyThemeArgb = 0xFF4A7D67.toInt()

        listOf(false, true).forEach { darkTheme ->
            val warm = resolveLeziColorScheme(
                darkTheme = darkTheme,
                style = LeziVisualStyle.Warm,
                babyThemeArgb = babyThemeArgb,
            )
            val journal = resolveLeziColorScheme(
                darkTheme = darkTheme,
                style = LeziVisualStyle.Journal,
                babyThemeArgb = babyThemeArgb,
            )
            val journalWithoutBaby = resolveLeziColorScheme(
                darkTheme = darkTheme,
                style = LeziVisualStyle.Journal,
                babyThemeArgb = null,
            )
            val journalExtended = resolveLeziExtendedColors(
                darkTheme = darkTheme,
                style = LeziVisualStyle.Journal,
                babyThemeArgb = babyThemeArgb,
                fallbackAccent = journal.primary,
            )

            assertEquals(warm.primary, journal.primary)
            assertEquals(warm.onPrimary, journal.onPrimary)
            assertEquals(journal.primary, journalExtended.babyAccent)
            assertNotEquals(journalWithoutBaby.primary, journal.primary)
        }
    }
}
