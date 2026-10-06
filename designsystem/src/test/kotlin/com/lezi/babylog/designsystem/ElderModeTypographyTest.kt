package com.lezi.babylog.designsystem

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElderModeTypographyTest {
    @Test
    fun offScaleIsTheDeclaredRampPixelForPixel() {
        val scale = resolveLeziTypeScale(elder = false)

        assertEquals(LeziTypography.Display, scale.Display)
        assertEquals(LeziTypography.Hero, scale.Hero)
        assertEquals(LeziTypography.Title, scale.Title)
        assertEquals(LeziTypography.TitleSm, scale.TitleSm)
        assertEquals(LeziTypography.Body, scale.Body)
        assertEquals(LeziTypography.BodyStrong, scale.BodyStrong)
        assertEquals(LeziTypography.Label, scale.Label)
        assertEquals(LeziTypography.LabelLg, scale.LabelLg)
        assertEquals(LeziTypography.Meta, scale.Meta)
        assertEquals(LeziTypography.Micro, scale.Micro)
        assertEquals(LeziTypography.Metric, scale.Metric)
        assertEquals(LeziTypography.MetricSm, scale.MetricSm)
        assertEquals(LeziTypography.MetricLg, scale.MetricLg)
        assertEquals(LeziTypography.ChipMetric, scale.ChipMetric)
        assertEquals(LeziTypography.Mono, scale.Mono)
        assertEquals(LeziTypography.Eyebrow, scale.Eyebrow)

        assertEquals(FontFamily.Serif, scale.Display.fontFamily)
        assertEquals(FontFamily.Serif, scale.Hero.fontFamily)
        assertEquals(FontFamily.Serif, scale.Title.fontFamily)
        assertEquals(FontFamily.Monospace, scale.Metric.fontFamily)
        assertEquals(FontFamily.Monospace, scale.ChipMetric.fontFamily)
        assertEquals((-0.4).sp, scale.Display.letterSpacing)
        assertEquals((-0.2).sp, scale.Title.letterSpacing)
    }

    @Test
    fun elderScaleIsSansWithFifteenLineHeightAndZeroNegativeTracking() {
        val scale = resolveLeziTypeScale(elder = true)
        val styles = listOf(
            scale.Display,
            scale.Hero,
            scale.Title,
            scale.TitleSm,
            scale.Body,
            scale.BodyStrong,
            scale.Label,
            scale.LabelLg,
            scale.Meta,
            scale.Micro,
            scale.Metric,
            scale.MetricSm,
            scale.MetricLg,
            scale.ChipMetric,
            scale.Mono,
            scale.Eyebrow,
        )

        styles.forEach { style ->
            assertEquals(FontFamily.SansSerif, style.fontFamily)
            assertTrue(
                "${style.fontSize} lineHeight ${style.lineHeight}",
                style.lineHeight.value + 0.001f >= style.fontSize.value * 1.5f,
            )
        }

        assertEquals(28.sp, scale.Display.fontSize)
        assertEquals(42.sp, scale.Display.lineHeight)
        assertEquals(0.sp, scale.Display.letterSpacing)

        assertEquals(34.sp, scale.Hero.fontSize)
        assertEquals(51.sp, scale.Hero.lineHeight)
        assertEquals(0.sp, scale.Hero.letterSpacing)

        assertEquals(20.sp, scale.Title.fontSize)
        assertEquals(30.sp, scale.Title.lineHeight)
        assertEquals(0.sp, scale.Title.letterSpacing)

        assertEquals(24.sp, scale.Body.lineHeight)
        assertEquals(16.sp, scale.Micro.lineHeight)
        assertEquals(19.5.sp, scale.ChipMetric.lineHeight)
        assertEquals(21.sp, scale.Mono.lineHeight)
        assertEquals(33.sp, scale.Metric.lineHeight)
        assertEquals(30.sp, scale.MetricSm.lineHeight)
        assertEquals(42.sp, scale.MetricLg.lineHeight)

        assertEquals(0.2.sp, scale.Label.letterSpacing)
        assertEquals(0.6.sp, scale.Eyebrow.letterSpacing)
    }

    @Test
    fun elderChipMetricOutgrowsTheHistoricalEighteenSpWell() {
        val off = resolveLeziTypeScale(elder = false)
        val elder = resolveLeziTypeScale(elder = true)
        assertEquals(16.sp, off.ChipMetric.lineHeight)
        assertEquals(19.5.sp, elder.ChipMetric.lineHeight)
        assertTrue(elder.ChipMetric.lineHeight.value > 18f)
        assertEquals(FontFamily.SansSerif, elder.ChipMetric.fontFamily)
        assertEquals(FontFamily.Monospace, off.ChipMetric.fontFamily)
    }

    @Test
    fun materialJournalOffKeepsSansTitlesAtDeclaredTracking() {
        val typography = LeziTypography.material(journal = true)

        assertEquals(FontFamily.SansSerif, typography.displayLarge.fontFamily)
        assertEquals(32.sp, typography.displayLarge.lineHeight)
        assertEquals((-0.4).sp, typography.displayLarge.letterSpacing)
        assertEquals(FontFamily.SansSerif, typography.headlineSmall.fontFamily)
        assertEquals(26.sp, typography.headlineSmall.lineHeight)
        assertEquals((-0.2).sp, typography.headlineSmall.letterSpacing)
        assertEquals(LeziTypography.TitleSm, typography.titleLarge)
    }

    @Test
    fun materialElderMatchesResolvedScaleOnBothTemplates() {
        val scale = resolveLeziTypeScale(elder = true)

        listOf(false, true).forEach { journal ->
            val typography = LeziTypography.material(journal = journal, elder = true)
            assertEquals(scale.Display, typography.displayLarge)
            assertEquals(scale.Display, typography.displayMedium)
            assertEquals(scale.Display, typography.displaySmall)
            assertEquals(scale.Title, typography.headlineLarge)
            assertEquals(scale.Title, typography.headlineMedium)
            assertEquals(scale.Title, typography.headlineSmall)
            assertEquals(scale.TitleSm, typography.titleLarge)
            assertEquals(scale.Body, typography.bodyLarge)
            assertEquals(scale.Meta, typography.bodySmall)
            assertEquals(FontFamily.SansSerif, typography.headlineSmall.fontFamily)
            assertEquals(0.sp, typography.headlineSmall.letterSpacing)
        }
    }
}
