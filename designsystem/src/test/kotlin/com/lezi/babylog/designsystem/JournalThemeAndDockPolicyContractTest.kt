package com.lezi.babylog.designsystem

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalThemeAndDockPolicyContractTest {
    @Test
    fun `journal baby theme drives primary and header accent in light and dark`() {
        val babyThemeArgb = 0xFF4A7D67.toInt()

        listOf(false, true).forEach { darkTheme ->
            val warm = resolveLeziColorScheme(
                darkTheme = darkTheme,
                style = LeziVisualStyle.Warm,
                babyThemeArgb = babyThemeArgb,
            )
            val journal = resolveLeziColorScheme(
                darkTheme = darkTheme,
                style = LeziVisualStyle.Journal,
                babyThemeArgb = babyThemeArgb,
            )
            val journalWithoutBaby = resolveLeziColorScheme(
                darkTheme = darkTheme,
                style = LeziVisualStyle.Journal,
                babyThemeArgb = null,
            )
            val journalExtended = resolveLeziExtendedColors(
                darkTheme = darkTheme,
                style = LeziVisualStyle.Journal,
                babyThemeArgb = babyThemeArgb,
                fallbackAccent = journal.primary,
            )

            assertEquals(warm.primary, journal.primary)
            assertEquals(warm.onPrimary, journal.onPrimary)
            assertEquals(journal.primary, journalExtended.babyAccent)
            assertNotEquals(journalWithoutBaby.primary, journal.primary)
        }
    }

    @Test
    fun `written theme and handedness policy matches the settings surface`() {
        val root = repositoryRoot()
        val prd = root.resolve("docs/prd/ui.md").readText()
        val context = root.resolve("CONTEXT.md").readText()
        val settings = root.resolve(
            "feature/settings/src/main/kotlin/com/lezi/babylog/feature/settings/SettingsScreen.kt",
        ).readText()

        assertTrue(
            prd.contains(
                "warm 与 journal 的顶栏宝宝强调、主 CTA 与选中态均使用当前宝宝主题色；" +
                    "journal 珊瑚只作为无宝宝主题时的模板默认色，不是 CTA 例外。",
            ),
        )
        assertTrue(prd.contains("绝对左右序"))
        assertTrue(prd.contains("不**随惯用手整体镜像"))
        assertTrue(context.contains("不再随惯用手整体镜像"))
        assertTrue(
            settings.contains(
                "影响圆盘调时与表单靠边；首页常用坞按你编排的左右序，不镜像。",
            ),
        )
        assertEquals(1, Regex("镜像").findAll(settings).count())
    }

    @Test
    fun `retired preview twins stay absent from the production design system`() {
        val root = repositoryRoot()
        val components = root.resolve(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/Components.kt",
        ).readText()
        val pageComponents = root.resolve(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/PageComponents.kt",
        ).readText()
        val previews = root.resolve(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ComponentPreviews.kt",
        ).readText()
        val primaryButton = root.resolve(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/" +
                "ActionStateComponents.kt",
        ).readText()

        assertFalse(components.contains("fun JournalSummaryStrip("))
        assertFalse(pageComponents.contains("fun AppContextRow("))
        assertFalse(previews.contains("JournalSummaryStrip("))
        assertTrue(
            primaryButton.contains(
                "LeziPrimaryButtonMode.Enabled -> MaterialTheme.colorScheme.primary",
            ),
        )
        // Flat primary: no warm float elevation and no journal hard-edge strip.
        assertTrue(primaryButton.contains("shadowElevation = LeziElevation.None"))
        assertFalse(primaryButton.contains("JournalHardEdge"))
        assertFalse(primaryButton.contains("ButtonWarm"))
        assertFalse(primaryButton.contains("hardEdge"))
        assertFalse(primaryButton.contains("hardShadow"))
    }

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
