package com.lezi.babylog.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ElderModeStructureDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun journalL3DoesNotClipRecordChrome() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LeziTheme(visualStyle = "journal", elderMode = "l3") {
                    Column(Modifier.fillMaxWidth()) {
                        LeziDetailTopBar(
                            title = "乐乐睡觉中",
                            onBack = {},
                            modifier = Modifier.testTag("elder_top_bar"),
                        )
                        SummaryMetric(
                            value = "12h20m",
                            label = "睡眠",
                            modifier = Modifier.testTag("elder_chip"),
                        )
                        TimelineRailCard(
                            sleep = emptyList(),
                            feed = emptyList(),
                            care = emptyList(),
                            recordCount = 4,
                            nowMs = 60L * 60_000,
                            modifier = Modifier.testTag("elder_time_bar"),
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("elder_top_bar").assertHeightIsAtLeast(76.dp)
        compose.onNodeWithTag("elder_chip").assertHeightIsAtLeast(82.dp)
        listOf("elder_top_bar", "elder_chip", "elder_time_bar").forEach { tag ->
            val node = compose.onNodeWithTag(tag)
            val clipped = node.getBoundsInRoot()
            val unclipped = node.getUnclippedBoundsInRoot()
            assertEquals(tag, unclipped.width.value, clipped.width.value, 0.5f)
            assertEquals(tag, unclipped.height.value, clipped.height.value, 0.5f)
        }
    }

    @Test
    fun l3PlusMaxFontScaleDoesNotClipRecordChrome() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LeziTheme(elderMode = "l3") {
                    Column(Modifier.fillMaxWidth()) {
                        LeziDetailTopBar(
                            title = "乐乐睡觉中",
                            onBack = {},
                            modifier = Modifier.testTag("elder_top_bar"),
                        )
                        SummaryMetric(
                            value = "12h20m",
                            label = "睡眠",
                            modifier = Modifier.testTag("elder_chip"),
                        )
                        TimelineRailCard(
                            sleep = emptyList(),
                            feed = emptyList(),
                            care = emptyList(),
                            recordCount = 4,
                            nowMs = 60L * 60_000,
                            modifier = Modifier.testTag("elder_time_bar"),
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("elder_top_bar").assertHeightIsAtLeast(76.dp)
        compose.onNodeWithTag("elder_chip").assertHeightIsAtLeast(82.dp)
        compose.onNodeWithTag("elder_time_bar").assertExists()
        listOf("elder_top_bar", "elder_chip", "elder_time_bar").forEach { tag ->
            val node = compose.onNodeWithTag(tag)
            val clipped = node.getBoundsInRoot()
            val unclipped = node.getUnclippedBoundsInRoot()
            assertEquals(tag, unclipped.width.value, clipped.width.value, 0.5f)
            assertEquals(tag, unclipped.height.value, clipped.height.value, 0.5f)
        }
    }
}
