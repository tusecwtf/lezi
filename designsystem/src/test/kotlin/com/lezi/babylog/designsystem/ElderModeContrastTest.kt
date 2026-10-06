package com.lezi.babylog.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElderModeContrastTest {
    @Test
    fun ensureContrastLiftsAFailingGrayToTheTextFloor() {
        val gray = Color(0xFFB0B0B0)
        val paper = Color(0xFFFAFAFA)
        assertTrue(contrastRatio(gray, paper) < 4.5f)

        val fixed = ensureContrast(gray, listOf(paper))
        assertTrue(contrastRatio(fixed, paper) + 0.001f >= 4.5f)
    }

    @Test
    fun ensureContrastLeavesACompliantColorUnchanged() {
        val ink = Color(0xFF1E2C3C)
        val paper = Color(0xFFFEFCF9)
        assertEquals(ink, ensureContrast(ink, listOf(paper)))
    }

    @Test
    fun offSchemesStayBitIdenticalWhenElderIsFalse() {
        LeziVisualStyle.entries.forEach { style ->
            listOf(false, true).forEach { darkTheme ->
                val implicit = resolveLeziColorScheme(darkTheme, style, babyThemeArgb = null)
                val explicit = resolveLeziColorScheme(
                    darkTheme,
                    style,
                    babyThemeArgb = null,
                    elder = false,
                )
                assertEquals("$style dark=$darkTheme scheme", implicit, explicit)
            }
        }
    }

    @Test
    fun elderAuxiliaryTextMeetsFourPointFiveOnSurfaces() {
        LeziVisualStyle.entries.forEach { style ->
            listOf(false, true).forEach { darkTheme ->
                val scheme = resolveLeziColorScheme(
                    darkTheme,
                    style,
                    babyThemeArgb = null,
                    elder = true,
                )
                val label = "$style dark=$darkTheme"
                assertTrue(
                    "$label onSurfaceVariant vs background",
                    contrastRatio(scheme.onSurfaceVariant, scheme.background) + 0.001f >= 4.5f,
                )
                assertTrue(
                    "$label onSurfaceVariant vs surface",
                    contrastRatio(scheme.onSurfaceVariant, scheme.surface) + 0.001f >= 4.5f,
                )
                assertTrue(
                    "$label onSurfaceVariant vs surfaceVariant",
                    contrastRatio(scheme.onSurfaceVariant, scheme.surfaceVariant) + 0.001f >= 4.5f,
                )
                assertTrue(
                    "$label onSurface vs background",
                    contrastRatio(scheme.onSurface, scheme.background) + 0.001f >= 4.5f,
                )
            }
        }
    }

    @Test
    fun elderDoesNotRecolorPrimaryOrSemanticRoles() {
        LeziVisualStyle.entries.forEach { style ->
            listOf(false, true).forEach { darkTheme ->
                val off = resolveLeziColorScheme(darkTheme, style, babyThemeArgb = null)
                val elder = resolveLeziColorScheme(
                    darkTheme,
                    style,
                    babyThemeArgb = null,
                    elder = true,
                )
                val label = "$style dark=$darkTheme"
                assertEquals("$label primary", off.primary, elder.primary)
                assertEquals("$label error", off.error, elder.error)
                assertEquals("$label onSurface", off.onSurface, elder.onSurface)
                assertEquals("$label background", off.background, elder.background)
            }
        }
    }

}
