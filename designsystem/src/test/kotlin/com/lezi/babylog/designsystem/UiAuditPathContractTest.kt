package com.lezi.babylog.designsystem

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Structural contracts for the 2026-08-04 UI-audit path: leftover closures and
 * the two regressions fixed after P3 (onboarding multi-root layout, bottom nav).
 * These assert against the shipped sources so a reintroduction fails the suite.
 */
class UiAuditPathContractTest {
    @Test
    fun `onboarding AnimatedContent wraps steps in a spaced Column`() {
        val source = read(
            "feature/onboarding/src/main/kotlin/com/lezi/babylog/feature/onboarding/OnboardingScreen.kt",
        )
        assertTrue(source.contains("AnimatedContent("))
        assertTrue(
            "multi-root step content must keep vertical spacedBy layout",
            source.contains("verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)"),
        )
        assertTrue(
            source.contains("AnimatedContent (which does not arrange multi-root content)"),
        )
    }

    @Test
    fun `bottom NavigationBarItem navigates through Material onClick`() {
        val source = read(
            "app/src/main/kotlin/com/lezi/babylog/MainActivity.kt",
        )
        assertTrue(source.contains("NavigationBarItem("))
        assertTrue(
            "short-press must use a non-empty Material onClick navigation owner",
            source.contains("onClick = { navigateToDestination() }"),
        )
        assertFalse(
            "empty onClick + combinedClickable must not reappear (breaks short-press nav)",
            Regex("""onClick\s*=\s*\{\s*\}""").containsMatchIn(
                source.substringAfter("NavigationBarItem(").substringBefore("icon ="),
            ),
        )
        assertTrue(
            "long-press baby cycle must not own short-press",
            source.contains("detectTapGestures(onLongPress = { onLongClick() })"),
        )
    }

    @Test
    fun `feature and app modules do not call raw Material buttons or chips`() {
        val banned = listOf(
            Regex("""(?<![A-Za-z])TextButton\("""),
            Regex("""(?<![A-Za-z])OutlinedButton\("""),
            Regex("""(?<![A-Za-z])FilterChip\("""),
            // Bare Button( — not LeziPrimaryButton / LeziSecondaryButton / LeziTextButton
            Regex("""(?<![A-Za-z.])Button\("""),
            Regex("""(?<![A-Za-z])OutlinedTextField\("""),
            Regex("""(?<![A-Za-z])IconButton\("""),
            Regex("""(?<![A-Za-z])DatePickerDialog\("""),
            // Bare Switch( — not LeziSwitch
            Regex("""(?<![A-Za-z.])Switch\("""),
        )
        val importBanned = listOf(
            "import androidx.compose.material3.TextButton",
            "import androidx.compose.material3.OutlinedButton",
            "import androidx.compose.material3.FilterChip",
            "import androidx.compose.material3.Button",
            "import androidx.compose.material3.OutlinedTextField",
            "import androidx.compose.material3.Switch",
            "import androidx.compose.material3.IconButton",
            "import androidx.compose.material3.DatePickerDialog",
        )
        val offenders = mutableListOf<String>()
        for (root in listOf("app/src/main", "feature", "core/ui/src/main")) {
            val dir = repositoryRoot().resolve(root)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter {
                    it.isFile &&
                        it.extension == "kt" &&
                        "src/main" in it.invariantSeparatorsPath
                }
                .forEach { file ->
                    val text = file.readText()
                    val rel = file.relativeTo(repositoryRoot()).path
                    for (imp in importBanned) {
                        if (imp in text) offenders += "$rel imports $imp"
                    }
                    for (rx in banned) {
                        if (rx.containsMatchIn(text)) {
                            offenders += "$rel matches ${rx.pattern}"
                        }
                    }
                }
        }
        assertTrue(
            "raw Material controls remain in product UI:\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
        // Designsystem owns the chrome.
        assertTrue(read("designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ActionStateComponents.kt")
            .contains("fun LeziTextButton("))
        assertTrue(read("designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ActionStateComponents.kt")
            .contains("fun LeziDestructiveButton("))
        assertTrue(read("designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ActionStateComponents.kt")
            .contains("fun LeziFilterChip("))
        assertTrue(read("designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ActionStateComponents.kt")
            .contains("fun LeziIconButton("))
        assertTrue(read("designsystem/src/main/kotlin/com/lezi/babylog/designsystem/LeziFormControls.kt")
            .contains("fun LeziTextField("))
        assertTrue(read("designsystem/src/main/kotlin/com/lezi/babylog/designsystem/LeziFormControls.kt")
            .contains("fun LeziSwitch("))
        assertTrue(read("designsystem/src/main/kotlin/com/lezi/babylog/designsystem/LeziFormControls.kt")
            .contains("fun LeziDatePickerDialog("))
    }

    @Test
    fun `product dialogs prefer LeziAlertDialog over raw Material dialog`() {
        val mainRoots = listOf(
            "app/src/main",
            "core/ui/src/main",
            "feature",
            "designsystem/src/main",
        )
        val offenders = mutableListOf<String>()
        val bareCall = Regex("""(?<![A-Za-z])AlertDialog\(""")
        for (root in mainRoots) {
            val dir = repositoryRoot().resolve(root)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter {
                    it.isFile &&
                        it.extension == "kt" &&
                        it.name != "LeziAlertDialog.kt" &&
                        "src/main" in it.invariantSeparatorsPath
                }
                .forEach { file ->
                    if (bareCall.containsMatchIn(file.readText())) {
                        offenders += file.relativeTo(repositoryRoot()).path
                    }
                }
        }
        assertTrue(
            "raw Material dialog call sites remain:\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
        assertTrue(
            repositoryRoot()
                .resolve("designsystem/src/main/kotlin/com/lezi/babylog/designsystem/LeziAlertDialog.kt")
                .readText()
                .contains("shape = LeziThemeExt.dialogShape"),
        )
    }

    @Test
    fun `hairline alpha is single-sourced through LeziAlphas`() {
        assertEquals(0.85f, LeziAlphas.Hairline)
        val components = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/Components.kt",
        )
        val recordRow = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/RecordRow.kt",
        )
        assertTrue(components.contains("leziHairlineColor()"))
        assertTrue(recordRow.contains("leziHairlineColor()"))
        assertFalse(components.contains("outline.copy(alpha = 0.85f)"))
        assertFalse(recordRow.contains("outline.copy(alpha = 0.85f)"))
    }

    @Test
    fun `stool palette tokens own light and dark fills for slots 1 to 7`() {
        for (slot in 1..7) {
            val light = LeziStoolPalette.fill(slot, darkTheme = false)
            val dark = LeziStoolPalette.fill(slot, darkTheme = true)
            assertTrue("slot $slot light fill must be opaque", light.alpha == 1f)
            assertTrue("slot $slot dark fill must be opaque", dark.alpha == 1f)
        }
        // Slot 7 black swatch: dark outline is lifted for contrast (not pure black).
        val blackOutlineLight = LeziStoolPalette.outline(7, darkTheme = false)
        val blackOutlineDark = LeziStoolPalette.outline(7, darkTheme = true)
        assertTrue(blackOutlineDark.red > blackOutlineLight.red ||
            blackOutlineDark.green > blackOutlineLight.green ||
            blackOutlineDark.blue > blackOutlineLight.blue)
    }

    @Test
    fun `SummaryMetric and RecordSummaryStrip document distinct roles`() {
        val summaryMetric = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/Components.kt",
        )
        val strip = read(
            "core/ui/src/main/kotlin/com/lezi/babylog/core/ui/RecordPresentation.kt",
        )
        assertTrue(summaryMetric.contains("Division of labor vs `RecordSummaryStrip`"))
        assertTrue(strip.contains("Division of labor vs `SummaryMetric`"))
    }

    @Test
    fun `growth chart strokes and dots use density-aware dp toPx`() {
        val source = read(
            "feature/growth/src/main/kotlin/com/lezi/babylog/feature/growth/GrowthScreen.kt",
        )
        assertTrue(source.contains("1.5.dp else 2.dp).toPx()"))
        assertTrue(source.contains("pointRadius"))
        assertTrue(source.contains("seriesStroke"))
        assertFalse(
            "raw pixel Stroke widths must not return",
            Regex("""Stroke\(if \(journal\) 1\.5f else 2f\)""").containsMatchIn(source),
        )
        assertTrue(source.contains("LeziSecondaryButton("))
        assertTrue(source.contains("确认删除"))
    }

    @Test
    fun `motion and density token symbols remain on the audit path`() {
        // Symbol presence only — exact values live in MotionDensityTokensTest.
        val tokens = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/Tokens.kt",
        )
        val theme = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/Theme.kt",
        )
        assertTrue("LeziMotion Fast tier", tokens.contains("object LeziMotion"))
        assertTrue(tokens.contains("const val Fast: Int = "))
        assertTrue(tokens.contains("const val Base: Int = "))
        assertTrue(tokens.contains("const val Emphasized: Int = "))
        assertTrue("LeziDensity tables", tokens.contains("object LeziDensity"))
        assertTrue(tokens.contains("data class LeziDensityScale"))
        assertTrue(tokens.contains("val Warm = LeziDensityScale("))
        assertTrue(tokens.contains("val Journal = LeziDensityScale("))
        assertTrue(tokens.contains("fun forStyle(style: LeziVisualStyle)"))
        assertTrue(
            "legacy structural spacing must point agents at LeziDensity",
            tokens.contains("prefer [LeziDensity.forStyle]"),
        )
        assertTrue(
            "style-derived density matches other LeziThemeExt chrome",
            theme.contains("val density: LeziDensityScale"),
        )
        assertTrue(theme.contains("LeziDensity.forStyle(visualStyle)"))
    }

    private fun read(relative: String): String =
        repositoryRoot().resolve(relative).readText()

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
