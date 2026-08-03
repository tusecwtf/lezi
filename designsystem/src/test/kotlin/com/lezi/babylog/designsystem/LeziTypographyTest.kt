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
}
