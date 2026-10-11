package com.lezi.babylog.feature.summary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SummaryChartDailyAccessibilityDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun allChartKindsExposeThirtyOneSeparateDaysAndOneShortOverviewInBothTemplates() {
        val dates = List(31) { LocalDate.of(2026, 7, 1).plusDays(it.toLong()) }
        var template by mutableStateOf("warm")
        var kind by mutableStateOf("feed")
        compose.setContent {
            LeziTheme(visualStyle = template) {
                Box(Modifier.width(360.dp).testTag("daily_chart")) {
                    when (kind) {
                        "feed" -> MiniBarChart(
                            values = List(31) { if (it == 0) 120f else 0f },
                            dates = dates,
                            metricLabel = "喂养量",
                            color = Color.Blue,
                            dayHasRecords = List(31) { it == 0 || it == 30 },
                            accessibleValueFormatter = { "${it.toInt()}ml" },
                        )
                        "food" -> StackedFoodBarChart(
                            laneDayValues = listOf(List(31) { if (it == 0) 1.5f else 0f }),
                            laneNames = listOf("米粉"),
                            laneColors = listOf(Color.Blue),
                            unparseableDayCounts = List(31) { if (it == 30) 2 else 0 },
                            dates = dates,
                            dayHasRecords = List(31) { it == 0 || it == 2 || it == 30 },
                        )
                        "diaper" -> StackedDiaperBarChart(
                            pee = List(31) { if (it == 0) 2f else 0f },
                            poop = List(31) { if (it == 30) 1f else 0f },
                            dates = dates,
                            peeColor = Color.Blue,
                            poopColor = Color.Red,
                        )
                        "temperature" -> WeekLineChart(
                            title = "体温",
                            values = List(31) {
                                when (it) {
                                    0 -> 36.5f
                                    30 -> 37f
                                    else -> 0f
                                }
                            },
                            dates = dates,
                            color = Color.Red,
                        )
                    }
                }
            }
        }

        for (style in listOf("warm", "journal")) {
            for (chartKind in listOf("feed", "food", "diaper", "temperature")) {
                compose.runOnIdle {
                    template = style
                    kind = chartKind
                }
                val expectations = when (chartKind) {
                    "feed" -> listOf("喂养量", "7月1日 喂养量 120ml", "7月2日 喂养量 无记录", "7月31日 喂养量 0ml")
                    "food" -> listOf("辅食", "7月1日 米粉1.5 共1.5", "7月2日 辅食 无记录", "7月31日 辅食 未填量2条")
                    "diaper" -> listOf("尿布", "7月1日 尿2次 便0次 共2次", "7月2日 尿布 无记录", "7月31日 尿0次 便1次 共1次")
                    else -> listOf("体温", "7月1日 体温 36.5℃", "7月2日 体温 无记录", "7月31日 体温 37.0℃")
                }
                compose.onNodeWithContentDescription(
                    "${expectations[0]}，7月1日至7月31日，31天，可逐日查看",
                ).assertIsDisplayed()
                expectations.drop(1).forEach { description ->
                    compose.onNodeWithContentDescription(description).assertIsDisplayed()
                }
                if (chartKind == "food") {
                    compose.onNodeWithContentDescription("7月3日 共0").assertIsDisplayed()
                }

                val speaking = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)
                // Neither the drawn canvas nor axis text repeats the day data.
                compose.onAllNodes(speaking).assertCountEquals(32)
                compose.onAllNodes(speaking, useUnmergedTree = true).assertCountEquals(32)
                val nodes = compose.onAllNodes(speaking).fetchSemanticsNodes()
                val days = nodes.filter {
                    it.config[SemanticsProperties.ContentDescription].single().matches(Regex("7月\\d+日 .*"))
                }.sortedBy { it.config[SemanticsProperties.TraversalIndex] }
                assertEquals(31, days.size)
                val chartBounds = compose.onNodeWithTag("daily_chart").fetchSemanticsNode().boundsInRoot
                days.forEachIndexed { index, node ->
                    val bounds = node.boundsInRoot
                    assertFalse(node.config.isMergingSemanticsOfDescendants)
                    assertEquals(index.toFloat(), node.config[SemanticsProperties.TraversalIndex])
                    assertTrue(bounds.width > 0f && bounds.height > 0f)
                    assertTrue(bounds.left >= chartBounds.left && bounds.right <= chartBounds.right)
                    assertTrue(bounds.top >= chartBounds.top && bounds.bottom <= chartBounds.bottom)
                    if (index > 0) {
                        assertEquals(days[index - 1].boundsInRoot.right, bounds.left, 0.1f)
                    }
                }
                val overview = nodes.single { it !in days }
                assertEquals(-1f, overview.config[SemanticsProperties.TraversalIndex])
                assertFalse(overview.config.isMergingSemanticsOfDescendants)
            }
        }
    }
}
