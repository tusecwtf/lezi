package com.lezi.babylog.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TimelineDstDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun springForwardGeometrySharesDrawingHitAndPanCoordinates() {
        val geometry = TimelineWindowGeometry(
            contentDurationMinutes = 4_260,
            primaryStartMinutes = 1_440,
            primaryEndExclusiveMinutes = 2_820,
            dayBoundaryMinutes = listOf(1_440, 2_820),
        )
        val viewportDuration = 1_560
        val initialViewportStart = 1_350
        val selected = AtomicReference<String?>(null)
        val reportedViewport = AtomicInteger(initialViewportStart)

        compose.setContent {
            var viewportStart by remember { mutableIntStateOf(initialViewportStart) }
            LeziTheme(visualStyle = "warm") {
                TimelineRailCard(
                    sleep = emptyList(),
                    feed = emptyList(),
                    care = listOf(
                        TimelineLaneSegment(
                            startMinOfDay = geometry.primaryEndExclusiveMinutes,
                            endMinOfDay = geometry.primaryEndExclusiveMinutes,
                            colorRole = LeziRecordColorRole.Pee,
                            title = "尿尿",
                            detail = "DST boundary",
                            isEvent = true,
                            dayChartCategoryKey = "PEE",
                        ),
                    ),
                    recordCount = 1,
                    nowContentMinute = geometry.primaryStartMinutes + 90,
                    viewportStartMinutes = viewportStart,
                    viewportDurationMinutes = viewportDuration,
                    windowGeometry = geometry,
                    hourLabels = emptyList(),
                    onCategorySelect = selected::set,
                    onHorizontalPan = { pan ->
                        val next = TimelineAxis.panViewportStart(
                            currentStartMinutes = initialViewportStart,
                            deltaPx = pan.cumulativeDeltaPx,
                            axisLengthPx = pan.axisLengthPx,
                            viewportDurationMinutes = viewportDuration,
                            contentDurationMinutes = geometry.contentDurationMinutes,
                        )
                        viewportStart = next
                        reportedViewport.set(next)
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
            (geometry.primaryEndExclusiveMinutes - initialViewportStart).toFloat() /
                viewportDuration
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
        assertTrue(reportedViewport.get() <= geometry.maxViewportStart(viewportDuration))

        val screenshot = compose.onNodeWithTag("dst_timeline_rail").captureToImage()
        assertTrue(screenshot.width > 0)
        assertTrue(screenshot.height > 0)
    }
}
