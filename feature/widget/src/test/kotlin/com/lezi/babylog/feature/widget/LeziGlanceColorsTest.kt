package com.lezi.babylog.feature.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.glance.color.ColorProviders
import androidx.glance.color.DayNightColorProvider
import androidx.glance.unit.ColorProvider
import com.lezi.babylog.designsystem.LeziColors
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for 0.5.4 ticket 17 (spec M5): the Glance palette is mapped from
 * [LeziColors] — the single color source — instead of the default `GlanceTheme`
 * (dynamic/baseline system colors). Every slot the widget renders is a Glance
 * day/night pair resolved by the system UI mode at render time, so the widget
 * follows system dark theme; the in-app journal/warm style switch and the baby
 * theme accent deliberately do not reach the widget (system-level surface).
 */
class LeziGlanceColorsTest {

    private fun colors(): ColorProviders = leziGlanceColors()

    private fun day(provider: ColorProvider): Color =
        (provider as DayNightColorProvider).component1()

    private fun night(provider: ColorProvider): Color =
        (provider as DayNightColorProvider).component2()

    @Test
    fun `rendered slots map from the LeziColors single source`() {
        val colors = colors()
        // Root canvas + title text.
        assertEquals(LeziColors.Bg, day(colors.background))
        assertEquals(LeziColors.DarkBg, night(colors.background))
        assertEquals(LeziColors.Fg, day(colors.onBackground))
        assertEquals(LeziColors.DarkFg, night(colors.onBackground))
        // Summary lines.
        assertEquals(LeziColors.Muted, day(colors.onSurfaceVariant))
        assertEquals(LeziColors.DarkMuted, night(colors.onSurfaceVariant))
        // Quick-action chips.
        assertEquals(LeziColors.SkySoft, day(colors.primaryContainer))
        assertEquals(LeziColors.DarkSkySoft, night(colors.primaryContainer))
        assertEquals(LeziColors.Fg, day(colors.onPrimaryContainer))
        assertEquals(LeziColors.DarkFg, night(colors.onPrimaryContainer))
    }

    @Test
    fun `brand and surface slots map from the LeziColors single source`() {
        val colors = colors()
        assertEquals(LeziColors.Accent, day(colors.primary))
        assertEquals(LeziColors.DarkAccent, night(colors.primary))
        assertEquals(LeziColors.Surface, day(colors.surface))
        assertEquals(LeziColors.DarkSurface, night(colors.surface))
        assertEquals(LeziColors.Fg, day(colors.onSurface))
        assertEquals(LeziColors.DarkFg, night(colors.onSurface))
    }

    @Test
    fun `every rendered slot switches between day and night with the system mode`() {
        val colors = colors()
        listOf(
            colors.background,
            colors.onBackground,
            colors.onSurfaceVariant,
            colors.primaryContainer,
            colors.onPrimaryContainer,
        ).forEach { slot ->
            assertNotEquals("slot $slot must not be a fixed color", day(slot), night(slot))
        }
    }

    @Test
    fun `rendered text keeps four point five contrast on its ground in both modes`() {
        val colors = colors()
        val grounds = listOf(
            "background/onBackground" to (colors.background to colors.onBackground),
            "background/onSurfaceVariant" to (colors.background to colors.onSurfaceVariant),
            "primaryContainer/onPrimaryContainer" to (colors.primaryContainer to colors.onPrimaryContainer),
        )
        grounds.forEach { (label, pair) ->
            val (ground, ink) = pair
            assertTrue(
                "$label day",
                contrastRatio(ink = day(ink), ground = day(ground)) + 1e-4f >= 4.5f,
            )
            assertTrue(
                "$label night",
                contrastRatio(ink = night(ink), ground = night(ground)) + 1e-4f >= 4.5f,
            )
        }
    }

    private fun contrastRatio(ink: Color, ground: Color): Float {
        val lighter = max(ink.luminance(), ground.luminance())
        val darker = min(ink.luminance(), ground.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
