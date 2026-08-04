package com.lezi.babylog.designsystem

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 06 (ui-drawing-polish): record high-frequency chrome consumes
 * warm-open / journal-compact [LeziDensity] tables; empty day language is
 * distinct from loading.
 *
 * Public seams (source + token policy), not private Compose trees.
 */
class RecordDensityEmptyContractTest {

    @Test
    fun `top bar structural inset uses density not legacy TopBarHorizontal`() {
        val page = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/PageComponents.kt",
        )
        val header = read(
            "app/src/main/kotlin/com/lezi/babylog/AppHeader.kt",
        )

        assertTrue(
            "detail top bar must pad with density.topBarHorizontal",
            page.contains("LeziThemeExt.density.topBarHorizontal") ||
                page.contains("density.topBarHorizontal"),
        )
        assertFalse(
            "detail/brand top bars must leave legacy TopBarHorizontal",
            page.contains("LeziSpacing.TopBarHorizontal"),
        )
        assertTrue(
            "record-tab AppHeaderBar must pad with density.topBarHorizontal",
            header.contains("LeziThemeExt.density.topBarHorizontal") ||
                header.contains("density.topBarHorizontal"),
        )
        assertFalse(
            "AppHeaderBar must not use legacy TopBarHorizontal for structural inset",
            header.contains("LeziSpacing.TopBarHorizontal"),
        )
    }

    @Test
    fun `timeline rail chrome uses density panel and section roles without off-grid pads`() {
        val timeline = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/TimelineComponents.kt",
        )

        assertTrue(
            "TimelineRailCard content pad must come from density.panelContent",
            timeline.contains("density.panelContent") ||
                timeline.contains("LeziThemeExt.density.panelContent"),
        )
        assertTrue(
            "TimelineRailCard section gap must come from density.sectionGap",
            timeline.contains("density.sectionGap") ||
                timeline.contains("LeziThemeExt.density.sectionGap"),
        )
        assertFalse(
            "warm content pad 18.dp is off-grid and not a density role",
            timeline.contains("PaddingValues(18.dp)"),
        )
        assertFalse(
            "journal content pad 10.dp is off-grid and not a density role",
            timeline.contains("PaddingValues(10.dp)"),
        )
        // Off-grid journal section gap that density must replace.
        assertFalse(
            "sectionGap = 6.dp is off-grid; use density.sectionGap",
            Regex("""sectionGap\s*=\s*if\s*\(journal\)\s*6\.dp""").containsMatchIn(timeline),
        )
        // Hit-testing and axis contracts stay density-correct via dp→px helpers.
        assertTrue(timeline.contains("EVENT_HIT_RADIUS"))
        assertTrue(timeline.contains("toPx()"))
    }

    @Test
    fun `confirm reason card structural pad uses density cardPad`() {
        val confirm = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ConfirmChrome.kt",
        )
        assertTrue(
            confirm.contains("density.cardPad") ||
                confirm.contains("LeziThemeExt.density.cardPad"),
        )
        assertFalse(
            "legacy off-grid reason-card pad must not remain",
            confirm.contains("horizontal = 14.dp, vertical = 10.dp"),
        )
    }

    @Test
    fun `empty state mark is not a spinner and differs from loading color`() {
        val state = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ActionStateComponents.kt",
        )
        val marks = read(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/PlaceholderMarks.kt",
        )

        // Loading alone owns CircularProgressIndicator inside StateContainer.
        assertTrue(state.contains("if (kind == StateKind.Loading)"))
        assertTrue(state.contains("CircularProgressIndicator("))
        // Empty draws a ring mark, never a spinner branch.
        assertTrue(marks.contains("StateKind.Empty -> drawCircle("))
        assertTrue(marks.contains("style = Stroke("))
        // Empty arm of markColor must be onSurfaceVariant (not co-presence with any other branch).
        assertTrue(
            "empty markColor arm must map StateKind.Empty -> onSurfaceVariant",
            Regex(
                """StateKind\.Empty\s*->\s*MaterialTheme\.colorScheme\.onSurfaceVariant""",
            ).containsMatchIn(state),
        )
        assertTrue(
            "loading markColor arm must map StateKind.Loading -> primary",
            Regex(
                """StateKind\.Loading\s*->\s*MaterialTheme\.colorScheme\.primary""",
            ).containsMatchIn(state),
        )
        // Empty/loading chrome shell uses density cardPad (not legacy CardPad default alone).
        assertTrue(
            "StateContainer LeziCard must pass density.cardPad contentPadding",
            state.contains("LeziThemeExt.density.cardPad") ||
                state.contains("density.cardPad"),
        )
    }

    @Test
    fun `log day empty branch uses Empty kind and fixed empty copy not loading`() {
        val list = read(
            "feature/log/src/main/kotlin/com/lezi/babylog/feature/log/timeline/LogTimelineList.kt",
        )
        val dock = read(
            "feature/log/src/main/kotlin/com/lezi/babylog/feature/log/dock/QuickDockChrome.kt",
        )

        assertTrue(list.contains("StateKind.Empty"))
        assertTrue(list.contains("\"还没有记录\""))
        assertTrue(list.contains("\"点下方快捷入口添加第一条记录\""))
        assertTrue(list.contains("StateKind.Loading"))
        assertTrue(list.contains("\"加载中\""))
        // Distinct test tags so empty vs loading remain addressable.
        assertTrue(list.contains("log_records_empty"))
        assertTrue(list.contains("log_records_loading"))
        // Warm list section spacing + dock outer inset consume density roles.
        assertTrue(
            list.contains("density.sectionGap") ||
                list.contains("LeziThemeExt.density.sectionGap"),
        )
        assertTrue(
            "quick dock outer inset must use density.dockOuterHorizontal (no local style branch)",
            dock.contains("density.dockOuterHorizontal") ||
                dock.contains("LeziThemeExt.density.dockOuterHorizontal"),
        )
        assertFalse(
            "dock must not hard-code journal full-bleed via if (isJournal)",
            Regex(
                """if\s*\(\s*LeziThemeExt\.isJournal\s*\)\s*\{\s*0\.dp""",
            ).containsMatchIn(dock) ||
                Regex("""if\s*\(.*isJournal.*\)\s*0\.dp""").containsMatchIn(dock),
        )
    }

    @Test
    fun `warm density remains strictly more open than journal on every role`() {
        // Product literals — same policy as MotionDensityTokensTest / ticket 01.
        assertTrue(LeziDensity.Warm.cardPad > LeziDensity.Journal.cardPad)
        assertTrue(LeziDensity.Warm.topBarHorizontal > LeziDensity.Journal.topBarHorizontal)
        assertTrue(LeziDensity.Warm.sectionGap > LeziDensity.Journal.sectionGap)
        assertTrue(LeziDensity.Warm.panelContent > LeziDensity.Journal.panelContent)
        assertTrue(LeziDensity.Warm.dockOuterHorizontal > LeziDensity.Journal.dockOuterHorizontal)
        assertTrue(LeziSpacing.Touch.value >= 48f)
    }

    private fun read(relativePath: String): String =
        DesignsystemSourceFixtures.read(relativePath)
}
