package com.lezi.babylog.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test
    fun warmBabyAccentsAlwaysResolveReadablePrimaryContent() {
        val palette = listOf(
            0xFF007BAE,
            0xFFAA442B,
            0xFF2F8F6B,
            0xFF7A5CFF,
            0xFFE09F3E,
            0xFFD4578C,
            0xFF4C6A92,
            0xFF5B8C5A,
        )

        palette.forEach { argb ->
            listOf(false, true).forEach { dark ->
                val scheme = resolveLeziColorScheme(
                    darkTheme = dark,
                    style = LeziVisualStyle.Warm,
                    babyThemeArgb = argb.toInt(),
                )

                assertReadable(
                    foreground = scheme.onPrimary,
                    background = scheme.primary,
                    context = "baby accent ${argb.toString(16)} dark=$dark",
                )
            }
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
