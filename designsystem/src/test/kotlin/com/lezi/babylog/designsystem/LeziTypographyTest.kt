package com.lezi.babylog.designsystem

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class LeziTypographyTest {
    @Test
    fun materialTypographyMapsEveryUsedHeadingToTheLeziRamp() {
        val typography = LeziTypography.material()

        assertEquals(LeziTypography.Title, typography.headlineSmall)
        assertEquals(20.sp, typography.headlineSmall.fontSize)
        assertEquals(LeziTypography.Label, typography.titleSmall)
        assertEquals(13.sp, typography.titleSmall.fontSize)
    }

    @Test
    fun namedChipAndCatalogStylesStayOnTheDeclaredRamp() {
        assertEquals(13.sp, LeziTypography.ChipMetric.fontSize)
        assertEquals(16.sp, LeziTypography.ChipMetric.lineHeight)
        assertEquals(14.sp, LeziTypography.LabelLg.fontSize)
        assertEquals(18.sp, LeziTypography.LabelLg.lineHeight)
        assertEquals(20.sp, LeziTypography.MetricSm.fontSize)
        assertEquals(26.sp, LeziTypography.MetricSm.lineHeight)
        assertEquals(LeziTypography.Metric.fontFamily, LeziTypography.ChipMetric.fontFamily)
        assertEquals(LeziTypography.Metric.fontFamily, LeziTypography.MetricSm.fontFamily)
    }
}
