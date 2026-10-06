package com.lezi.babylog.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.unit.dp

import java.io.File
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
    fun `easing tokens pin the AAR emphasized decelerate and accelerate curves`() {
        // motion-polish ticket 02: values are the shipped
        // material-components-android 1.12.0 AAR literals (the m3.material.io
        // page once printed slightly different numbers; the AAR is authoritative).
        // CubicBezierEasing compares by control points, so this pins the curves.
        assertEquals(CubicBezierEasing(0.1f, 0.7f, 0.1f, 1f), LeziEasing.EmphasizedDecelerate)
        assertEquals(CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.2f), LeziEasing.EmphasizedAccelerate)
        // Both curves are endpoint-identity and monotonic — a settled enter
        // actually reaches the resting state.
        assertEquals(0f, LeziEasing.EmphasizedDecelerate.transform(0f), 1e-3f)
        assertEquals(1f, LeziEasing.EmphasizedDecelerate.transform(1f), 1e-3f)
        assertEquals(0f, LeziEasing.EmphasizedAccelerate.transform(0f), 1e-3f)
        assertEquals(1f, LeziEasing.EmphasizedAccelerate.transform(1f), 1e-3f)
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
            LeziDensity.Elder.cardPad,
            LeziDensity.Elder.topBarHorizontal,
            LeziDensity.Elder.sectionGap,
            LeziDensity.Elder.panelContent,
            LeziDensity.Elder.dockOuterHorizontal,
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
        assertEquals(20.dp, LeziDensity.Elder.cardPad)
        assertEquals(16.dp, LeziDensity.Elder.topBarHorizontal)
        assertEquals(20.dp, LeziDensity.Elder.sectionGap)
        assertEquals(20.dp, LeziDensity.Elder.panelContent)
        assertEquals(12.dp, LeziDensity.Elder.dockOuterHorizontal)
        assertTrue(LeziDensity.Elder.cardPad > LeziDensity.Warm.cardPad)
        assertTrue(LeziDensity.Elder.sectionGap > LeziDensity.Warm.sectionGap)
        assertEquals(LeziDensity.Warm.dockOuterHorizontal, LeziDensity.Elder.dockOuterHorizontal)
    }

    @Test
    fun `design tokens json density snapshot keeps dockOuterHorizontal parity with LeziDensity`() {
        // Dual-write contract (design/README): structural density roles live in both
        // Tokens.kt (Compose authority) and design/tokens.json (OD/agent snapshot).
        val json = repositoryRoot().resolve("design/tokens.json").readText()
        val densityBlock = json
            .substringAfter("\"density\"")
            .substringBefore("\"radius\"")
        val warmBlock = densityBlock
            .substringAfter("\"warm\"")
            .substringBefore("\"journal\"")
        val journalBlock = densityBlock
            .substringAfter("\"journal\"")
            .substringBefore("\"elder\"")
        val elderBlock = densityBlock
            .substringAfter("\"elder\"")
            .substringBeforeLast("}")

        val densityFields = listOf(
            "cardPad",
            "topBarHorizontal",
            "sectionGap",
            "panelContent",
            "dockOuterHorizontal",
        )
        for (field in densityFields) {
            assertTrue(
                "density.warm must declare $field (JSON↔LeziDensityScale parity)",
                warmBlock.contains("\"$field\""),
            )
            assertTrue(
                "density.journal must declare $field (JSON↔LeziDensityScale parity)",
                journalBlock.contains("\"$field\""),
            )
            assertTrue(
                "density.elder must declare $field (JSON↔LeziDensityScale parity)",
                elderBlock.contains("\"$field\""),
            )
        }
        // Product literals — match LeziDensity.Warm/Journal/Elder dockOuterHorizontal.
        assertTrue(
            "warm dockOuterHorizontal is space.sm (LeziSpacing.Sm)",
            Regex(""""dockOuterHorizontal"\s*:\s*"space\.sm"""").containsMatchIn(warmBlock),
        )
        assertTrue(
            "journal dockOuterHorizontal is full-bleed 0",
            Regex(""""dockOuterHorizontal"\s*:\s*0\b""").containsMatchIn(journalBlock),
        )
        assertTrue(
            "elder dockOuterHorizontal is space.sm (LeziSpacing.Sm)",
            Regex(""""dockOuterHorizontal"\s*:\s*"space\.sm"""").containsMatchIn(elderBlock),
        )
        assertTrue(
            "elder cardPad is space.lg (LeziSpacing.Lg)",
            Regex(""""cardPad"\s*:\s*"space\.lg"""").containsMatchIn(elderBlock),
        )
    }

    @Test
    fun `design tokens json mirrors elder touch and structure scales`() {
        val json = repositoryRoot().resolve("design/tokens.json").readText()
        val touchBlock = json.substringAfter("\"touchTarget\"").substringBefore("\"structure\"")
        val structureBlock = json.substringAfter("\"structure\"").substringBefore("\"radius\"")
        assertTrue(Regex(""""primary"\s*:\s*60\b""").containsMatchIn(touchBlock))
        assertTrue(Regex(""""dialogClose"\s*:\s*44\b""").containsMatchIn(touchBlock))
        assertTrue(Regex(""""dockCellMinHeight"\s*:\s*84\b""").containsMatchIn(structureBlock))
        assertTrue(Regex(""""summaryColumnCount"\s*:\s*2\b""").containsMatchIn(structureBlock))
        assertTrue(Regex(""""timeBarTrackMinHeight"\s*:\s*22\b""").containsMatchIn(structureBlock))
    }


    @Test
    fun `design tokens json type and icon snapshots cover Compose ramps`() {
        val json = repositoryRoot().resolve("design/tokens.json").readText()
        val typeBlock = json.substringAfter("\"type\"").substringBefore("\"icon\"")
        val iconBlock = json.substringAfter("\"icon\"")
        for (field in listOf(
            "hero", "titleSm", "bodyStrong", "labelLg", "eyebrow",
            "chipMetric", "micro", "metricSm",
        )) {
            assertTrue("type must declare $field", typeBlock.contains("\"$field\""))
        }
        for (field in listOf("glyph", "disc", "chip", "state", "menuWell", "menuGlyph")) {
            assertTrue("icon must declare $field", iconBlock.contains("\"$field\""))
        }
        assertTrue(Regex(""""glyph"\s*:\s*18\b""").containsMatchIn(iconBlock))
        assertTrue(Regex(""""disc"\s*:\s*32\b""").containsMatchIn(iconBlock))
        assertTrue(Regex(""""state"\s*:\s*32\b""").containsMatchIn(iconBlock))
    }

    @Test
    fun `design tokens json motion snapshot mirrors duration tiers and easing curves`() {
        // Dual-write contract: LeziMotion tiers and LeziEasing curves live in both
        // Tokens.kt (Compose authority) and design/tokens.json `motion` (OD snapshot).
        val json = repositoryRoot().resolve("design/tokens.json").readText()
        val motionBlock = json.substringAfter("\"motion\"").substringBefore("\"density\"")
        assertTrue(Regex(""""fast"\s*:\s*150\b""").containsMatchIn(motionBlock))
        assertTrue(Regex(""""base"\s*:\s*200\b""").containsMatchIn(motionBlock))
        assertTrue(Regex(""""emphasized"\s*:\s*300\b""").containsMatchIn(motionBlock))
        assertTrue(
            "motion.easing must pin the AAR decelerate (LeziEasing.EmphasizedDecelerate)",
            Regex(""""emphasizedDecelerate"\s*:\s*\[\s*0\.1\s*,\s*0\.7\s*,\s*0\.1\s*,\s*1\s*\]""")
                .containsMatchIn(motionBlock),
        )
        assertTrue(
            "motion.easing must pin the AAR accelerate (LeziEasing.EmphasizedAccelerate)",
            Regex(""""emphasizedAccelerate"\s*:\s*\[\s*0\.3\s*,\s*0\s*,\s*0\.8\s*,\s*0\.2\s*\]""")
                .containsMatchIn(motionBlock),
        )
    }

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
