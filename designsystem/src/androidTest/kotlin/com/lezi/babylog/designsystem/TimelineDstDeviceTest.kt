package com.lezi.babylog.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToLong

class TimelineDstDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun springForwardGeometrySharesDrawingHitAndPanCoordinates() {
        val primaryStart = 1_440L * MINUTE_MS
        val primaryEnd = 2_820L * MINUTE_MS
        val contentDuration = 4_260L * MINUTE_MS
        val viewportDuration = 1_560L * MINUTE_MS
        val initialViewportStart = 1_350L * MINUTE_MS
        val maxStart = contentDuration - viewportDuration
        val selected = AtomicReference<String?>(null)
        val reportedViewport = AtomicLong(initialViewportStart)

        compose.setContent {
            var viewportStart by remember { mutableLongStateOf(initialViewportStart) }
            LeziTheme(visualStyle = "warm") {
                TimelineRailCard(
                    sleep = emptyList(),
                    feed = emptyList(),
                    care = listOf(
                        TimelineLaneSegment(
                            startMs = primaryEnd,
                            endMs = primaryEnd,
                            colorRole = LeziRecordColorRole.Pee,
                            title = "尿尿",
                            detail = "DST boundary",
                            isEvent = true,
                            dayChartCategoryKey = "PEE",
                        ),
                    ),
                    recordCount = 1,
                    nowMs = primaryStart + 90L * MINUTE_MS,
                    viewportStartMs = viewportStart,
                    viewportDurationMs = viewportDuration,
                    dayBoundariesMs = listOf(primaryStart, primaryEnd),
                    primaryRangeMs = primaryStart until primaryEnd,
                    hourTicks = emptyList(),
                    onCategorySelect = selected::set,
                    onHorizontalPan = { pan ->
                        val width = pan.axisLengthPx.coerceAtLeast(1f)
                        val previous = viewportStart
                        val deltaMs = (-pan.deltaPx / width * viewportDuration).roundToLong()
                        val next = (previous + deltaMs).coerceIn(0L, maxStart)
                        viewportStart = next
                        reportedViewport.set(next)
                        val appliedMs = next - previous
                        -(appliedMs.toDouble() / viewportDuration * width).toFloat()
                    },
                    modifier = Modifier.testTag("dst_timeline_rail"),
                )
            }
        }

        val careLane = compose.onNodeWithTag(
            "timeline_lane_护理",
            useUnmergedTree = true,
        )
        val markerFraction =
            (primaryEnd - initialViewportStart).toFloat() / viewportDuration
        careLane.performTouchInput {
            click(Offset(width * markerFraction, height * 0.32f))
        }
        compose.waitForIdle()
        assertEquals("PEE", selected.get())

        careLane.performTouchInput {
            swipeLeft()
        }
        compose.waitForIdle()
        assertTrue(reportedViewport.get() > initialViewportStart)
        assertTrue(reportedViewport.get() <= maxStart)

        val screenshot = compose.onNodeWithTag("dst_timeline_rail").captureToImage()
        assertTrue(screenshot.width > 0)
        assertTrue(screenshot.height > 0)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
