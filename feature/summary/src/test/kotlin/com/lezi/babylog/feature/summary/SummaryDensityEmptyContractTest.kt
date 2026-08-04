package com.lezi.babylog.feature.summary

import com.lezi.babylog.designsystem.LeziDensity
import com.lezi.babylog.designsystem.LeziMotion
import com.lezi.babylog.designsystem.LeziSpacing
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 07 (ui-drawing-polish): summary chart cards consume density tables;
 * chart marks stay density-converted; empty range language is distinct from
 * calculating; range/content transitions prefer motion tokens.
 *
 * Public seams (source + token policy), not private Compose trees.
 */
class SummaryDensityEmptyContractTest {

    @Test
    fun `chart card structural pads use density not legacy CardPad alone`() {
        val summary = readSummaryScreen()

        assertTrue(
            "chart/KPI panels must pad with density.cardPad",
            summary.contains("density.cardPad") ||
                summary.contains("LeziThemeExt.density.cardPad"),
        )
        assertTrue(
            "section spacing must use density.sectionGap",
            summary.contains("density.sectionGap") ||
                summary.contains("LeziThemeExt.density.sectionGap"),
        )
        // Compact warm metric cards must not keep legacy off-grid CardPad vertical.
        assertFalse(
            "CompactMetricCard must leave legacy LeziSpacing.CardPad",
            summary.contains("vertical = LeziSpacing.CardPad"),
        )
        assertFalse(
            "default surface pad must not be the only structural pad (legacy CardPad)",
            summary.contains("PaddingValues(LeziSpacing.CardPad)"),
        )
        // Journal week grid structural pad should route through density roles.
        assertTrue(
            "JournalWeekGrid content pad must come from density panel/card role",
            summary.contains("density.panelContent") ||
                summary.contains("LeziThemeExt.density.panelContent") ||
                (summary.contains("JournalWeekGrid") &&
                    (summary.contains("density.cardPad") ||
                        summary.contains("LeziThemeExt.density.cardPad"))),
        )
        // Warm open > journal compact — product literals (ticket 01).
        assertTrue(LeziDensity.Warm.cardPad > LeziDensity.Journal.cardPad)
        assertTrue(LeziDensity.Warm.sectionGap > LeziDensity.Journal.sectionGap)
        assertTrue(LeziDensity.Warm.panelContent > LeziDensity.Journal.panelContent)
        assertTrue(LeziSpacing.Touch.value >= 48f)
    }

    @Test
    fun `chart mark radii and strokes use density-aware dp toPx`() {
        val summary = readSummaryScreen()

        // Line chart marks: same density-aware pattern as growth chart contract.
        assertTrue(summary.contains("1.5.dp else 2.dp).toPx()"))
        assertTrue(summary.contains("2.dp else 3.dp).toPx()") || summary.contains("dotRadius"))
        assertTrue(summary.contains("2.dp.toPx()"))
        assertTrue(summary.contains("3.dp.toPx()") || summary.contains("feedDotRadius"))
        assertFalse(
            "raw pixel Stroke widths must not return on summary charts",
            Regex("""Stroke\(if \(journal\) 1\.5f else 2f\)""").containsMatchIn(summary),
        )
        assertFalse(
            "raw float circle radii for line dots must not return",
            Regex("""drawCircle\([^)]*,\s*(if \(journal\) )?[23]f\b""").containsMatchIn(summary),
        )
        // Bar corner radii convert from dp.
        assertTrue(
            summary.contains("CornerRadius(2.dp.toPx()") ||
                summary.contains("2.dp.toPx(), 2.dp.toPx()"),
        )
        // Slot geometry helper remains pure (aggregation/layout unit seam).
        assertTrue(summary.contains("fun calculateBarSlotLayout"))
    }

    @Test
    fun `empty chart range is not calculating and uses distinct copy`() {
        val summary = readSummaryScreen()

        // Calculating owns Loading + spinner-backed StateContainer path.
        assertTrue(summary.contains("StateKind.Loading"))
        assertTrue(summary.contains("\"正在计算汇总\""))
        assertTrue(summary.contains("\"正在整理护理记录，请稍候。\""))
        assertTrue(summary.contains("summary_calculating"))
        assertTrue(summary.contains("ui.calculating"))

        // Empty chart branches: Empty kind + fixed empty copy, not calculating title.
        assertTrue(summary.contains("StateKind.Empty"))
        assertTrue(summary.contains("\"范围内暂无喂养记录\""))
        assertTrue(summary.contains("\"范围内暂无已完成睡眠记录\""))
        assertTrue(summary.contains("\"范围内暂无尿布记录\""))
        assertTrue(summary.contains("summary_chart_empty_feed"))
        assertTrue(summary.contains("summary_chart_empty_sleep"))
        assertTrue(summary.contains("summary_chart_empty_diaper"))

        // Empty titles must not reuse calculating wording.
        assertFalse(
            "empty chart title must not say 正在计算",
            Regex("""StateKind\.Empty[\s\S]{0,200}正在计算""").containsMatchIn(summary),
        )
        // Default SummaryUi still starts calculating (engine contract elsewhere).
        assertTrue(LeziMotion.Base == 200)
        assertTrue(LeziMotion.Fast == 150)
    }

    @Test
    fun `range and calculating transitions prefer motion tokens`() {
        val summary = readSummaryScreen()

        assertTrue(
            "summary must import/use leziMotionMillis for non-essential transitions",
            summary.contains("leziMotionMillis"),
        )
        assertTrue(
            "range/content motion must reference LeziMotion tiers",
            summary.contains("LeziMotion.Base") && summary.contains("LeziMotion.Fast"),
        )
        assertTrue(
            "Crossfade calculating transition must pass tween animationSpec",
            summary.contains("Crossfade(") &&
                summary.contains("animationSpec = tween("),
        )
        assertTrue(
            "range AnimatedContent must use fadeIn/Out with tween durations",
            summary.contains("summary_range_content") &&
                summary.contains("fadeIn(animationSpec = tween(") &&
                summary.contains("fadeOut(animationSpec = tween("),
        )
        // No bare magic-ms tween for the range crossfade (token-backed only).
        assertFalse(
            "range transition must not hard-code tween(durationMillis = 200)",
            Regex(
                """summary_range_content[\s\S]{0,400}durationMillis\s*=\s*200\b""",
            ).containsMatchIn(summary),
        )
    }

    private fun readSummaryScreen(): String =
        repositoryRoot()
            .resolve(
                "feature/summary/src/main/kotlin/com/lezi/babylog/feature/summary/SummaryScreen.kt",
            )
            .readText()

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
