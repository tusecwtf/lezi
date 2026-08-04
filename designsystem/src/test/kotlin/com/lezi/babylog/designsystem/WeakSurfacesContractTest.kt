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
        assertTrue(export.contains("AnimatedVisibility"))
        assertTrue(
            "export busy presentation must be pure chrome helper, not inline dual busy",
            export.contains("exportActionChrome"),
        )
        // Design notes: enter Base, exit Fast — both tiers required; tween durations
        // must close over the two leziMotionMillis captures (not a bare ms exit).
        assertTrue(
            "preview enter must capture leziMotionMillis(LeziMotion.Base)",
            export.contains("previewEnterMs = leziMotionMillis(LeziMotion.Base)"),
        )
        assertTrue(
            "preview exit must capture leziMotionMillis(LeziMotion.Fast)",
            export.contains("previewExitMs = leziMotionMillis(LeziMotion.Fast)"),
        )
        assertTrue(
            "enter tweens must use previewEnterMs",
            export.contains("durationMillis = previewEnterMs"),
        )
        assertTrue(
            "exit tweens must use previewExitMs",
            export.contains("durationMillis = previewExitMs"),
        )
        assertTrue(export.contains("LeziMotion.Base") && export.contains("LeziMotion.Fast"))
    }

    @Test
    fun `care widget and config consume WidgetChrome via shared source fixtures`() {
        val widget = read(
            "feature/widget/src/main/kotlin/com/lezi/babylog/feature/widget/CareWidget.kt",
        )
        val config = read(
            "feature/widget/src/main/kotlin/com/lezi/babylog/feature/widget/WidgetConfigurationActivity.kt",
        )
        assertTrue(widget.contains("WidgetChrome.padHorizontal"))
        assertTrue(widget.contains("WidgetChrome.padVertical"))
        assertTrue(widget.contains("WidgetChrome.stackGap"))
        assertTrue(widget.contains("WidgetChrome.actionGap"))
        assertTrue(widget.contains("WidgetChrome.actionPadHorizontal"))
        assertTrue(widget.contains("WidgetChrome.actionPadVertical"))
        assertTrue(widget.contains("WidgetChrome.titleFontSize"))
        assertTrue(widget.contains("WidgetChrome.bodyFontSize"))
        assertFalse(
            "Glance content must not hard-code 12.dp horizontal pad",
            widget.contains("horizontal = 12.dp"),
        )
        assertFalse(
            "Glance title must not hard-code 14.sp (use Label token size)",
            widget.contains("fontSize = 14.sp"),
        )
        assertTrue(
            "config titles use LeziTypography",
            config.contains("LeziTypography.Title") || config.contains("LeziTypography.TitleSm"),
        )
        assertTrue(
            config.contains("WidgetChrome.configRowMinHeight") ||
                config.contains("LeziSpacing.Touch"),
        )
        assertTrue(config.contains("LeziPrimaryButton"))
    }

    private fun read(relativePath: String): String =
        DesignsystemSourceFixtures.read(relativePath)
}
