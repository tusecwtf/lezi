package com.lezi.babylog.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ThemeContrastTest {
    @Test
    fun warmBabyAccentsAlwaysResolveReadablePrimaryContent() {
        LeziBabyTheme.PaletteArgb.forEach { argb ->
            listOf(false, true).forEach { dark ->
                val scheme = resolveLeziColorScheme(
                    darkTheme = dark,
                    style = LeziVisualStyle.Warm,
                    babyThemeArgb = argb,
                )

                assertReadable(
                    foreground = scheme.onPrimary,
                    background = scheme.primary,
                    context = "baby accent ${Integer.toHexString(argb)} dark=$dark",
                )
            }
        }
    }

    @Test
    fun babyThemeColorsShareConsistentLightness() {
        val lightnesses = LeziBabyTheme.PaletteArgb.map { argb ->
            val c = normalizeBabyThemeColor(Color(argb))
            rgbToHsl(c.red, c.green, c.blue).third
        }
        lightnesses.forEach { l ->
            assertTrue("lightness $l off band", abs(l - BabyThemeLightness) < 0.02f)
        }
        val span = lightnesses.max() - lightnesses.min()
        assertTrue("lightness span $span too wide", span < 0.02f)
    }

    @Test
    fun babyAccentDrivesHeaderColorForBothTemplates() {
        val raw = 0xFFE09F3E.toInt()
        LeziVisualStyle.entries.forEach { style ->
            val scheme = resolveLeziColorScheme(false, style, raw)
            val ext = resolveLeziExtendedColors(false, style, raw, scheme.primary)
            val expected = normalizeBabyThemeColor(Color(raw))
            assertTrue(
                "babyAccent for $style drifted",
                abs(expected.red - ext.babyAccent.red) < 0.02f &&
                    abs(expected.green - ext.babyAccent.green) < 0.02f &&
                    abs(expected.blue - ext.babyAccent.blue) < 0.02f,
            )
            assertReadable(
                readableContentColor(ext.babyAccent),
                ext.babyAccent,
                "header on babyAccent $style",
            )
        }
    }

    @Test
    fun everyThemeDefinesReadableSemanticColorPairs() {
        LeziVisualStyle.entries.forEach { style ->
            listOf(false, true).forEach { dark ->
                val scheme = resolveLeziColorScheme(
                    darkTheme = dark,
                    style = style,
                    babyThemeArgb = null,
                )
                semanticPairs(scheme).forEach { (name, colors) ->
                    assertReadable(
                        foreground = colors.first,
                        background = colors.second,
                        context = "$style dark=$dark $name",
                    )
                }
            }
        }
    }

    @Test
    fun darkExtendedDangerTextIsReadableOnBackground() {
        LeziVisualStyle.entries.forEach { style ->
            val scheme = resolveLeziColorScheme(true, style, null)
            val extended = resolveLeziExtendedColors(true, style, null, scheme.primary)

            assertReadable(extended.danger, scheme.background, "$style dark danger")
        }
    }

    private fun semanticPairs(scheme: ColorScheme) = mapOf(
        "primary" to (scheme.onPrimary to scheme.primary),
        "primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
        "secondary" to (scheme.onSecondary to scheme.secondary),
        "secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
        "tertiary" to (scheme.onTertiary to scheme.tertiary),
        "tertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
        "error" to (scheme.onError to scheme.error),
        "errorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
        "background" to (scheme.onBackground to scheme.background),
        "surface" to (scheme.onSurface to scheme.surface),
        "surfaceVariant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
    )

    private fun assertReadable(foreground: Color, background: Color, context: String) {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        val ratio = (lighter + 0.05f) / (darker + 0.05f)
        assertTrue("$context contrast was $ratio", ratio >= 4.5f)
    }
}
