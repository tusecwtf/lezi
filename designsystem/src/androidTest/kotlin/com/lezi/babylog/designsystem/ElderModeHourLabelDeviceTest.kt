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
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ElderModeHourLabelDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderProductClockLabelsKeepMinGapAcrossConfigs() {
        var viewport by mutableStateOf(HourLabelViewports.first())
        setElderRail { viewport }

        HourLabelViewports.forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val bounds = productHourInstants.mapNotNull { instant ->
                runCatching {
                    compose.onNodeWithTag("timeline_hour_$instant").getBoundsInRoot()
                }.getOrNull()
            }
            val minTicks = if (next.width >= 360.dp) 3 else 2
            assertTrue(
                "${next.name} should keep at least $minTicks hour ticks, was ${bounds.size}",
                bounds.size >= minTicks,
            )
            bounds.sortedBy { it.left }.zipWithNext { left, right ->
                val gap = right.left - left.right
                assertTrue(
                    "${next.name} hour labels gap $gap < $ElderHourLabelMinGap: $left vs $right",
                    gap.value + 0.5f >= ElderHourLabelMinGap.value,
                )
            }
        }
    }

    private fun setElderRail(viewport: () -> LeziStyledDeviceViewport) {
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
                            nowMs = d0 + HOUR_MS,
                            viewportStartMs = d0 - 90L * MINUTE_MS,
                            viewportDurationMs = DAY_MS + 2 * 90L * MINUTE_MS,
                            hourTicks = productHourInstants.map { instant ->
                                val hour = (((instant - d0) / HOUR_MS).mod(24L)).toInt()
                                instant to hour.toString().padStart(2, '0') + ":00"
                            },
                            modifier = Modifier.testTag("elder_hour_rail"),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        val ElderHourLabelMinGap = 8.dp
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60L * MINUTE_MS
        const val DAY_MS = 24L * HOUR_MS
        val productHourInstants = listOf(
            DAY_MS,
            DAY_MS + 6 * HOUR_MS,
            DAY_MS + 12 * HOUR_MS,
            DAY_MS + 18 * HOUR_MS,
            DAY_MS + 24 * HOUR_MS,
        )
        val HourLabelViewports = LeziDeviceViewports.styled()
    }
}
