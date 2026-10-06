package com.lezi.babylog.designsystem

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typography slot contracts (0.5.4 ticket 16, spec §M4):
 *
 * 1. `LeziTypography.material()` resolves **all fifteen** Material 3 slots onto
 *    the Lezi scale in every variant (template × elder) — no M3 baseline style
 *    may leak through an unmapped slot. The twelve previously shipped slots
 *    keep their exact values; ticket 16 only filled displayMedium,
 *    displaySmall and headlineLarge.
 * 2. The Metric numeral tier exists through the theme seam, including the
 *    timer grade (`MetricLg`, the Metric family at 28sp) that replaced the
 *    inline `28.sp` in the nursing timer.
 * 3. `design/tokens.json` stays in dual-write sync with the Compose authority.
 */
class TypographyMaterialMappingTest {

    @Test
    fun warmOffTypographyCoversAllFifteenSlotsWithTheirHistoricalValues() {
        val typography = LeziTypography.material()
        val scale = resolveLeziTypeScale(elder = false)

        // Display band collapses onto Display — displayMedium/displaySmall join
        // the pre-existing displayLarge mapping (ticket 16).
        assertEquals(scale.Display, typography.displayLarge)
        assertEquals(scale.Display, typography.displayMedium)
        assertEquals(scale.Display, typography.displaySmall)
        // Headline band collapses onto Title — headlineLarge joins the
        // pre-existing headlineMedium/headlineSmall mapping (ticket 16).
        assertEquals(scale.Title, typography.headlineLarge)
        assertEquals(scale.Title, typography.headlineMedium)
        assertEquals(scale.Title, typography.headlineSmall)
        assertEquals(scale.TitleSm, typography.titleLarge)
        assertEquals(scale.TitleSm, typography.titleMedium)
        assertEquals(scale.Label, typography.titleSmall)
        assertEquals(scale.Body, typography.bodyLarge)
        assertEquals(scale.Body, typography.bodyMedium)
        assertEquals(scale.Meta, typography.bodySmall)
        assertEquals(scale.Label, typography.labelLarge)
        assertEquals(scale.Label, typography.labelMedium)
        assertEquals(scale.Meta, typography.labelSmall)
    }

    @Test
    fun everyVariantResolvesEverySlotOntoTheLeziScale() {
        val offScale = resolveLeziTypeScale(elder = false)
        val elderScale = resolveLeziTypeScale(elder = true)
        val slots = listOf(
            "displayLarge", "displayMedium", "displaySmall",
            "headlineLarge", "headlineMedium", "headlineSmall",
            "titleLarge", "titleMedium", "titleSmall",
            "bodyLarge", "bodyMedium", "bodySmall",
            "labelLarge", "labelMedium", "labelSmall",
        )
        listOf(false, true).forEach { journal ->
            listOf(offScale to false, elderScale to true).forEach { (scale, elder) ->
                val typography = LeziTypography.material(journal = journal, elder = elder)
                val ramp = setOf(
                    scale.Display, scale.Hero, scale.Title, scale.TitleSm,
                    scale.Body, scale.BodyStrong, scale.Label, scale.LabelLg,
                    scale.Meta, scale.Micro, scale.Metric, scale.MetricSm,
                    scale.MetricLg, scale.ChipMetric, scale.Mono, scale.Eyebrow,
                ) + if (journal && !elder) {
                    // Journal keeps the display/headline band styles but swaps
                    // their family to sans (pre-existing rule, display/title
                    // variables only).
                    listOf(
                        scale.Display.copy(fontFamily = FontFamily.SansSerif),
                        scale.Title.copy(fontFamily = FontFamily.SansSerif),
                    )
                } else {
                    emptyList()
                }
                val resolved = listOf(
                    typography.displayLarge, typography.displayMedium, typography.displaySmall,
                    typography.headlineLarge, typography.headlineMedium, typography.headlineSmall,
                    typography.titleLarge, typography.titleMedium, typography.titleSmall,
                    typography.bodyLarge, typography.bodyMedium, typography.bodySmall,
                    typography.labelLarge, typography.labelMedium, typography.labelSmall,
                )
                resolved.zip(slots).forEach { (style, slot) ->
                    assertTrue(
                        "$slot (journal=$journal elder=$elder) must come from the Lezi scale",
                        style in ramp,
                    )
                }
            }
        }
    }

    @Test
    fun journalVariantSwapsOnlyTheDisplayAndHeadlineBandFamily() {
        val typography = LeziTypography.material(journal = true)
        val scale = resolveLeziTypeScale(elder = false)

        assertEquals(scale.Display.copy(fontFamily = FontFamily.SansSerif), typography.displayLarge)
        assertEquals(scale.Display.copy(fontFamily = FontFamily.SansSerif), typography.displayMedium)
        assertEquals(scale.Display.copy(fontFamily = FontFamily.SansSerif), typography.displaySmall)
        assertEquals(scale.Title.copy(fontFamily = FontFamily.SansSerif), typography.headlineLarge)
        assertEquals(scale.Title.copy(fontFamily = FontFamily.SansSerif), typography.headlineMedium)
        assertEquals(scale.Title.copy(fontFamily = FontFamily.SansSerif), typography.headlineSmall)
        // The remaining nine slots are family-untouched.
        assertEquals(scale.TitleSm, typography.titleLarge)
        assertEquals(scale.Label, typography.titleSmall)
        assertEquals(scale.Body, typography.bodyLarge)
        assertEquals(scale.Meta, typography.labelSmall)
    }

    @Test
    fun metricTierExistsThroughTheThemeSeamWithTheTimerGradeAt28Sp() {
        // The Metric numeral family: summary KPI (22), compact KPI (20) and the
        // timer grade (28, ticket 16) share one mono bold face.
        assertEquals(22.sp, LeziTypography.Metric.fontSize)
        assertEquals(20.sp, LeziTypography.MetricSm.fontSize)
        assertEquals(28.sp, LeziTypography.MetricLg.fontSize)
        assertEquals(32.sp, LeziTypography.MetricLg.lineHeight)
        assertEquals(FontWeight.Bold, LeziTypography.MetricLg.fontWeight)
        assertEquals(FontFamily.Monospace, LeziTypography.MetricLg.fontFamily)
        assertEquals(LeziTypography.Metric.fontFamily, LeziTypography.MetricLg.fontFamily)
        // Consumed via LeziThemeExt.typography, so the resolved off scale and
        // the elder overlay must both carry the tier.
        assertEquals(LeziTypography.MetricLg, resolveLeziTypeScale(elder = false).MetricLg)
        val elder = resolveLeziTypeScale(elder = true)
        assertEquals(FontFamily.SansSerif, elder.MetricLg.fontFamily)
        assertEquals(28.sp, elder.MetricLg.fontSize)
        assertEquals(42.sp, elder.MetricLg.lineHeight)
    }

    @Test
    fun designTokensJsonTypeSnapshotCoversTheMetricFamily() {
        // Dual-write contract (design/README): Tokens.kt is the Compose
        // authority; design/tokens.json mirrors it for OD/agents.
        val json = repositoryRoot().resolve("design/tokens.json").readText()
        val typeBlock = json.substringAfter("\"type\"").substringBefore("\"icon\"")
        assertTrue(
            "type.metric must mirror LeziTypography.Metric (22/28 bold)",
            Regex(""""metric"\s*:\s*\{\s*"sizeSp"\s*:\s*22\s*,\s*"lineHeightSp"\s*:\s*28\s*,\s*"weight"\s*:\s*700\s*\}""")
                .containsMatchIn(typeBlock),
        )
        assertTrue(
            "type.metricSm must mirror LeziTypography.MetricSm (20/26 bold)",
            Regex(""""metricSm"\s*:\s*\{\s*"sizeSp"\s*:\s*20\s*,\s*"lineHeightSp"\s*:\s*26\s*,\s*"weight"\s*:\s*700\s*\}""")
                .containsMatchIn(typeBlock),
        )
        assertTrue(
            "type.metricLg must mirror LeziTypography.MetricLg (28/32 bold, ticket 16)",
            Regex(""""metricLg"\s*:\s*\{\s*"sizeSp"\s*:\s*28\s*,\s*"lineHeightSp"\s*:\s*32\s*,\s*"weight"\s*:\s*700\s*\}""")
                .containsMatchIn(typeBlock),
        )
    }

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
