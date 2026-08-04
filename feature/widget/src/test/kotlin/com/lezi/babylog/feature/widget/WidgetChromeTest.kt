package com.lezi.babylog.feature.widget

import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 11 — widget chrome spacing/type align with Lezi tokens within Glance limits.
 */
class WidgetChromeTest {

    @Test
    fun padsAndTypeMapToSpacingAndTypographySteps() {
        assertEquals(LeziSpacing.Sm, WidgetChrome.padHorizontal)
        assertEquals(LeziSpacing.Xs, WidgetChrome.padVertical)
        assertEquals(LeziSpacing.Xxs, WidgetChrome.stackGap)
        assertEquals(LeziSpacing.Xxs, WidgetChrome.actionGap)
        assertEquals(LeziSpacing.Sm, WidgetChrome.actionPadHorizontal)
        assertEquals(LeziSpacing.Md, WidgetChrome.actionPadVertical)
        assertEquals(LeziSpacing.Touch, WidgetChrome.configRowMinHeight)
        assertEquals(LeziTypography.Label.fontSize, WidgetChrome.titleFontSize)
        assertEquals(LeziTypography.Meta.fontSize, WidgetChrome.bodyFontSize)
        // 4dp grid for every structural pad.
        val grid = LeziSpacing.Xxs.value
        for (value in listOf(
            WidgetChrome.padHorizontal,
            WidgetChrome.padVertical,
            WidgetChrome.stackGap,
            WidgetChrome.actionGap,
            WidgetChrome.actionPadHorizontal,
            WidgetChrome.actionPadVertical,
            WidgetChrome.configRowMinHeight,
        )) {
            assertEquals(0f, value.value % grid, 0.001f)
        }
    }

    @Test
    fun careWidgetAndConfigConsumeWidgetChrome() {
        val widget = read(
            "feature/widget/src/main/kotlin/com/lezi/babylog/feature/widget/CareWidget.kt",
        )
        val config = read(
            "feature/widget/src/main/kotlin/com/lezi/babylog/feature/widget/WidgetConfigurationActivity.kt",
        )
        assertTrue(widget.contains("WidgetChrome.padHorizontal"))
        assertTrue(widget.contains("WidgetChrome.padVertical"))
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
        assertTrue(config.contains("WidgetChrome.configRowMinHeight") || config.contains("LeziSpacing.Touch"))
        assertTrue(config.contains("LeziPrimaryButton"))
    }

    private fun read(relative: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            val f = File(dir, relative)
            if (f.isFile) return f.readText()
            dir = dir.parentFile ?: error("missing $relative")
        }
        error("missing $relative")
    }
}
