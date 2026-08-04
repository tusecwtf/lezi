package com.lezi.babylog.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Public token contracts for shared motion durations and template density scales.
 * Expected durations are product ms literals; density pads are [LeziSpacing] steps.
 *
 * [LeziMotion.nonEssentialMillis] is the pure policy seam exercised here.
 * Compose [leziMotionMillis] / [leziMotionDurationScale] apply that policy to a
 * live [LeziMotion.systemAnimatorDurationScale] read (device smoke in androidTest).
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
    fun `nonEssentialMillis is instant under reduce-motion and keeps token otherwise`() {
        // Product policy: non-essential shell transitions become instant when the
        // system motion duration scale is ≤ 0 (reduce-motion / animations off).
        // Expected values are ticket literals, not recomputed from helpers.
        assertEquals(0, LeziMotion.nonEssentialMillis(LeziMotion.Base, motionDurationScale = 0f))
        assertEquals(0, LeziMotion.nonEssentialMillis(LeziMotion.Fast, motionDurationScale = -1f))
        assertEquals(0, LeziMotion.nonEssentialMillis(LeziMotion.Emphasized, motionDurationScale = 0f))
        assertEquals(150, LeziMotion.nonEssentialMillis(LeziMotion.Fast, motionDurationScale = 1f))
        assertEquals(200, LeziMotion.nonEssentialMillis(LeziMotion.Base, motionDurationScale = 1f))
        assertEquals(300, LeziMotion.nonEssentialMillis(LeziMotion.Emphasized, motionDurationScale = 1f))
        // Do not pre-scale by partial factors — Compose animation clock owns that.
        assertEquals(200, LeziMotion.nonEssentialMillis(LeziMotion.Base, motionDurationScale = 0.5f))
    }

    @Test
    fun `systemAnimatorDurationScale seam feeds the same nonEssentialMillis policy`() {
        // Thin testable path for what leziMotionMillis does after reading Settings:
        // scale → nonEssentialMillis(token, scale). Stub scales 0/1 without Android
        // Settings (instrumented smoke covers the ContentResolver read separately).
        fun resolveFromScale(tokenMs: Int, scale: Float): Int =
            LeziMotion.nonEssentialMillis(tokenMs = tokenMs, motionDurationScale = scale)

        assertEquals(0, resolveFromScale(LeziMotion.Base, scale = 0f))
        assertEquals(0, resolveFromScale(LeziMotion.Fast, scale = 0f))
        assertEquals(0, resolveFromScale(LeziMotion.Emphasized, scale = 0f))
        assertEquals(LeziMotion.Fast, resolveFromScale(LeziMotion.Fast, scale = 1f))
        assertEquals(LeziMotion.Base, resolveFromScale(LeziMotion.Base, scale = 1f))
        assertEquals(LeziMotion.Emphasized, resolveFromScale(LeziMotion.Emphasized, scale = 1f))
    }

    @Test
    fun `density tables keep warm more open than journal on spacing steps`() {
        // Warm more open, journal more compact — structural roles only.
        // Density encodes open-vs-compact policy via LeziSpacing grid steps.
        assertEquals(LeziSpacing.Md, LeziDensity.Warm.cardPad)
        assertEquals(LeziSpacing.Sm, LeziDensity.Warm.topBarHorizontal)
        assertEquals(LeziSpacing.Md, LeziDensity.Warm.sectionGap)
        assertEquals(LeziSpacing.Md, LeziDensity.Warm.panelContent)
        assertEquals(LeziSpacing.Sm, LeziDensity.Warm.dockOuterHorizontal)

        assertEquals(LeziSpacing.Sm, LeziDensity.Journal.cardPad)
        assertEquals(LeziSpacing.Xs, LeziDensity.Journal.topBarHorizontal)
        assertEquals(LeziSpacing.Xs, LeziDensity.Journal.sectionGap)
        assertEquals(LeziSpacing.Xs, LeziDensity.Journal.panelContent)
        // Journal quick-dock shell is product full-bleed.
        assertEquals(0f, LeziDensity.Journal.dockOuterHorizontal.value, 0.001f)

        assertTrue(LeziDensity.Warm.cardPad > LeziDensity.Journal.cardPad)
        assertTrue(LeziDensity.Warm.topBarHorizontal > LeziDensity.Journal.topBarHorizontal)
        assertTrue(LeziDensity.Warm.sectionGap > LeziDensity.Journal.sectionGap)
        assertTrue(LeziDensity.Warm.panelContent > LeziDensity.Journal.panelContent)
        assertTrue(LeziDensity.Warm.dockOuterHorizontal > LeziDensity.Journal.dockOuterHorizontal)

        // Every structural pad lands on the 4dp grid (multiples of Xxs).
        val gridStep = LeziSpacing.Xxs.value
        for (value in listOf(
            LeziDensity.Warm.cardPad,
            LeziDensity.Warm.topBarHorizontal,
            LeziDensity.Warm.sectionGap,
            LeziDensity.Warm.panelContent,
            LeziDensity.Warm.dockOuterHorizontal,
            LeziDensity.Journal.cardPad,
            LeziDensity.Journal.topBarHorizontal,
            LeziDensity.Journal.sectionGap,
            LeziDensity.Journal.panelContent,
            LeziDensity.Journal.dockOuterHorizontal,
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
