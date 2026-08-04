package com.lezi.babylog.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Public token contracts for shared motion durations and template density scales.
 * Expected durations are product ms literals; density pads are [LeziSpacing] steps.
 */
class MotionDensityTokensTest {
    @Test
    fun `motion tokens expose three duration tiers in ascending order`() {
        assertEquals(150, LeziMotion.Fast)
        assertEquals(200, LeziMotion.Base)
        assertEquals(300, LeziMotion.Emphasized)
        assertTrue(LeziMotion.Fast < LeziMotion.Base)
        assertTrue(LeziMotion.Base < LeziMotion.Emphasized)
    }

    @Test
    fun `density tables keep warm more open than journal on spacing steps`() {
        // Warm more open, journal more compact — structural roles only.
        // Density encodes open-vs-compact policy via LeziSpacing grid steps.
        assertEquals(LeziSpacing.Md, LeziDensity.Warm.cardPad)
        assertEquals(LeziSpacing.Sm, LeziDensity.Warm.topBarHorizontal)
        assertEquals(LeziSpacing.Md, LeziDensity.Warm.sectionGap)
        assertEquals(LeziSpacing.Md, LeziDensity.Warm.panelContent)

        assertEquals(LeziSpacing.Sm, LeziDensity.Journal.cardPad)
        assertEquals(LeziSpacing.Xs, LeziDensity.Journal.topBarHorizontal)
        assertEquals(LeziSpacing.Xs, LeziDensity.Journal.sectionGap)
        assertEquals(LeziSpacing.Xs, LeziDensity.Journal.panelContent)

        assertTrue(LeziDensity.Warm.cardPad > LeziDensity.Journal.cardPad)
        assertTrue(LeziDensity.Warm.topBarHorizontal > LeziDensity.Journal.topBarHorizontal)
        assertTrue(LeziDensity.Warm.sectionGap > LeziDensity.Journal.sectionGap)
        assertTrue(LeziDensity.Warm.panelContent > LeziDensity.Journal.panelContent)

        // Every structural pad lands on the 4dp grid (multiples of Xxs).
        val gridStep = LeziSpacing.Xxs.value
        for (value in listOf(
            LeziDensity.Warm.cardPad,
            LeziDensity.Warm.topBarHorizontal,
            LeziDensity.Warm.sectionGap,
            LeziDensity.Warm.panelContent,
            LeziDensity.Journal.cardPad,
            LeziDensity.Journal.topBarHorizontal,
            LeziDensity.Journal.sectionGap,
            LeziDensity.Journal.panelContent,
        )) {
            assertEquals(
                "density value $value must be on the 4dp grid",
                0f,
                value.value % gridStep,
                0.001f,
            )
        }

        assertEquals(LeziDensity.Warm, LeziDensity.forStyle(LeziVisualStyle.Warm))
        assertEquals(LeziDensity.Journal, LeziDensity.forStyle(LeziVisualStyle.Journal))
    }
}
