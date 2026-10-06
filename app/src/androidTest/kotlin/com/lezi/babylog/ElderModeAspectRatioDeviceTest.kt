package com.lezi.babylog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.RecordSummaryStrip
import com.lezi.babylog.core.ui.RecordSummaryValue
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.RecordRow
import com.lezi.babylog.designsystem.TimelineRailCard
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeAspectRatioDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderRecordChromeFitsAspectRatiosFromFourteenToTwentyThreeByNine() {
        val today = LocalDate.of(2026, 8, 21)
        val selected = LocalDate.of(2026, 8, 19)
        var viewport by mutableStateOf(AspectViewports.first())
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_aspect_page"),
                    ) {
                        Column(Modifier.fillMaxSize()) {
                        AppHeaderBar(
                            babyName = "乐乐安安美美",
                            babyAge = "11月29天",
                            avatarPath = null,
                            sleeping = false,
                            selectedDate = selected,
                            today = today,
                            canCycleBaby = false,
                            canGoNext = selected.isBefore(today),
                            dark = false,
                            onCycleBaby = {},
                            onJumpSiblingSameDayAge = {},
                            onPreviousDate = {},
                            onNextDate = {},
                            onOpenDatePicker = {},
                            onSearch = {},
                            modifier = Modifier.testTag("elder_aspect_header"),
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clipToBounds()
                                .testTag("elder_aspect_list"),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                RecordSummaryStrip(
                                    values = listOf(
                                        RecordSummaryValue(RecordType.FORMULA, "180ml", "奶量"),
                                        RecordSummaryValue(RecordType.SLEEP, "12h20m", "睡眠"),
                                        RecordSummaryValue(RecordType.PEE, "3次", "尿尿"),
                                        RecordSummaryValue(RecordType.POOP, "2次", "便便"),
                                    ),
                                    onSelect = {},
                                    modifier = Modifier.testTag("elder_aspect_strip"),
                                )
                                TimelineRailCard(
                                    sleep = emptyList(),
                                    feed = emptyList(),
                                    care = emptyList(),
                                    recordCount = 4,
                                    nowMs = 24L * 60 * 60 * 1000 + 60L * 60 * 1000,
                                    viewportStartMs = 24L * 60 * 60 * 1000 - 90L * 60 * 1000,
                                    viewportDurationMs =
                                        24L * 60 * 60 * 1000 + 2 * 90L * 60 * 1000,
                                    modifier = Modifier.testTag("elder_aspect_rail"),
                                )
                                RecordRow(
                                    time = "15:41",
                                    title = "睡眠",
                                    summary = "进行中 · 妈妈",
                                    relative = "刚才",
                                    onClick = {},
                                    modifier = Modifier.testTag("elder_aspect_row"),
                                )
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 84.dp)
                                .testTag("elder_aspect_dock"),
                        )
                        NavigationBar(
                            modifier = Modifier.testTag("elder_aspect_nav"),
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        ) {
                            NavItems.forEach { (label, icon) ->
                                NavigationBarItem(
                                    selected = label == "记录",
                                    onClick = {},
                                    modifier = Modifier.testTag("elder_aspect_nav_$label"),
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) },
                                )
                            }
                        }
                        }
                    }
                }
            }
        }

        AspectViewports.forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val page = compose.onNodeWithTag("elder_aspect_page").getUnclippedBoundsInRoot()
            val header = compose.onNodeWithTag("elder_aspect_header").getBoundsInRoot()
            val list = compose.onNodeWithTag("elder_aspect_list").getBoundsInRoot()
            val dock = compose.onNodeWithTag("elder_aspect_dock").getBoundsInRoot()
            val nav = compose.onNodeWithTag("elder_aspect_nav").getBoundsInRoot()
            val label = next.name

            assertInside("$label header", header, page)
            assertInside("$label list", list, page)
            assertInside("$label dock", dock, page)
            assertInside("$label nav", nav, page)
            assertStacked("$label header vs list", header, list)
            assertStacked("$label list vs dock", list, dock)
            assertStacked("$label dock vs nav", dock, nav)
            assertTrue(
                "$label list slot collapsed: list=$list header=$header dock=$dock nav=$nav page=$page",
                list.height >= 48.dp,
            )
            val navItems = NavItems.map { (item, _) ->
                compose.onNodeWithTag("elder_aspect_nav_$item").getBoundsInRoot()
            }
            navItems.zipWithNext { left, right ->
                val gap = right.left - left.right
                assertTrue(
                    "$label nav overlap: $left vs $right gap=$gap",
                    gap.value + 0.5f >= 0f,
                )
            }
            if (next.height >= 800.dp && next.width >= 360.dp) {
                val strip = compose.onNodeWithTag("elder_aspect_strip").getBoundsInRoot()
                val rail = compose.onNodeWithTag("elder_aspect_rail").getBoundsInRoot()
                assertInside("$label strip on tall page", strip, page)
                assertInside("$label rail on tall page", rail, page)
                assertStacked("$label strip vs rail", strip, rail)
            }
        }
    }

    private fun assertInside(label: String, child: DpRect, parent: DpRect) {
        assertTrue(
            "$label overflows: $child vs $parent",
            child.left.value >= parent.left.value - 0.5f &&
                child.top.value >= parent.top.value - 0.5f &&
                child.right.value <= parent.right.value + 0.5f &&
                child.bottom.value <= parent.bottom.value + 0.5f,
        )
    }

    private fun assertStacked(label: String, above: DpRect, below: DpRect) {
        assertTrue(
            "$label overlap: $above vs $below",
            above.bottom.value <= below.top.value + 0.5f,
        )
    }

    private companion object {
        val NavItems = listOf(
            "记录" to Icons.Filled.GridView,
            "汇总" to Icons.Filled.BarChart,
            "成长" to Icons.AutoMirrored.Filled.ShowChart,
            "账户" to Icons.Filled.Person,
            "菜单" to Icons.Filled.MoreHoriz,
        )
        val AspectViewports = LeziDeviceViewports.styled()
    }
}
