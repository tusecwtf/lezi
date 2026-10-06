package com.lezi.babylog.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ElderModeDayLabelDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderDayBoundaryLabelsExistAndStayReadableAcrossConfigs() {
        var viewport by mutableStateOf(DayLabelViewports.first())
        setElderCrossingRail { viewport }

        DayLabelViewports.forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val node = compose.onNodeWithTag("timeline_day_$DAY_MS", useUnmergedTree = true)
            node.assertIsDisplayed()
            val bounds = node.getBoundsInRoot()
            assertTrue(
                "${next.name} day label should have a readable box, was $bounds",
                bounds.width.value >= 8f && bounds.height.value >= 8f,
            )
            compose.onNodeWithText("8/8", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("timeline_hour_$DAY_MS", useUnmergedTree = true)
                .assertDoesNotExist()
        }
    }

    private fun setElderCrossingRail(viewport: () -> LeziStyledDeviceViewport) {
        val d0 = DAY_MS
        compose.setContent {
            val current = viewport()
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds(),
                    ) {
                        TimelineRailCard(
                            sleep = emptyList(),
                            feed = emptyList(),
                            care = emptyList(),
                            recordCount = 4,
                            nowMs = d0 + 8 * HOUR_MS,
                            viewportStartMs = d0 - 6 * HOUR_MS,
                            viewportDurationMs = DAY_MS,
                            dayBoundariesMs = listOf(d0),
                            dayBoundaryLabels = listOf(d0 to "8/8"),
                            primaryRangeMs = d0 until (d0 + DAY_MS),
                            hourTicks = listOf(
                                d0 - 6 * HOUR_MS to "18:00",
                                d0 to "00:00",
                                d0 + 6 * HOUR_MS to "06:00",
                                d0 + 12 * HOUR_MS to "12:00",
                            ),
                            modifier = Modifier.testTag("elder_day_rail"),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60L * MINUTE_MS
        const val DAY_MS = 24L * HOUR_MS
        val DayLabelViewports = LeziDeviceViewports.styled()
    }
}
