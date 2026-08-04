package com.lezi.babylog.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 11 (ui-drawing-polish): menu icon weight, widget chrome token map,
 * and export preview motion — public seams only.
 */
class WeakSurfacesContractTest {

    @Test
    fun `menu icon tokens fix well and glyph sizes on the spacing grid`() {
        // Product literals: well 40dp (10×4), glyph = LeziSpacing.Lg (20dp).
        assertEquals(40f, LeziMenuIcon.WellSize.value, 0.001f)
        assertEquals(LeziSpacing.Lg, LeziMenuIcon.GlyphSize)
        assertEquals(20f, LeziMenuIcon.GlyphSize.value, 0.001f)
        val grid = LeziSpacing.Xxs.value
        assertEquals(0f, LeziMenuIcon.WellSize.value % grid, 0.001f)
        assertEquals(0f, LeziMenuIcon.GlyphSize.value % grid, 0.001f)
    }

    @Test
    fun `settings menu row consumes menu icon tokens and outlined glyphs`() {
        val settings = read(
            "feature/settings/src/main/kotlin/com/lezi/babylog/feature/settings/SettingsScreen.kt",
        )
        assertTrue(
            "menu well must size from LeziMenuIcon.WellSize",
            settings.contains("LeziMenuIcon.WellSize"),
        )
        assertTrue(
            "menu glyph must size from LeziMenuIcon.GlyphSize",
            settings.contains("LeziMenuIcon.GlyphSize") &&
                settings.contains("Modifier.size(LeziMenuIcon.GlyphSize)"),
        )
        // SettingsMenuRow body must not hard-code the well size.
        val menuRowBody = settings.substringAfter("internal fun SettingsMenuRow(")
            .substringBefore("\ninternal fun ")
            .ifEmpty {
                settings.substringAfter("internal fun SettingsMenuRow(")
            }
        assertFalse(
            "SettingsMenuRow must not hard-code 40.dp well",
            menuRowBody.contains(".size(40.dp)"),
        )
        // One weight language: outlined family for row glyphs (not mixed Filled).
        assertTrue(settings.contains("Icons.Outlined.Search"))
        assertTrue(settings.contains("Icons.Outlined.Upload"))
        assertTrue(settings.contains("Icons.Outlined.CalendarMonth"))
        assertTrue(settings.contains("Icons.Outlined.DeleteForever"))
        assertFalse(
            "menu rows must not keep Filled.Search (weight drift)",
            settings.contains("Icons.Filled.Search"),
        )
    }

    @Test
    fun `export preview motion routes through leziMotionMillis`() {
        val export = read(
            "feature/export/src/main/kotlin/com/lezi/babylog/feature/export/ExportScreen.kt",
        )
        assertTrue(export.contains("leziMotionMillis"))
        assertTrue(export.contains("LeziMotion.Base") || export.contains("LeziMotion.Fast"))
        assertTrue(export.contains("AnimatedVisibility"))
        assertTrue(
            "export busy presentation must be pure chrome helper, not inline dual busy",
            export.contains("exportActionChrome"),
        )
    }

    private fun read(relativePath: String): String =
        DesignsystemSourceFixtures.read(relativePath)
}
