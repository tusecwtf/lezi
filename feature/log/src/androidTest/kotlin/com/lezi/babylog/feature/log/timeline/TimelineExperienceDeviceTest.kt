package com.lezi.babylog.feature.log.timeline

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziRecordColorRole
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.TimelineLaneSegment
import com.lezi.babylog.designsystem.TimelineRailCard
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineExperienceDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyRailStaysVisibleAcrossWarmAndJournalTemplates() {
        lateinit var setStyle: (String) -> Unit
        compose.setContent {
            var style by remember { mutableStateOf("warm") }
            setStyle = { style = it }
            LeziTheme(visualStyle = style) {
                TimelineRailCard(
                    sleep = emptyList(),
                    feed = emptyList(),
                    care = emptyList(),
                    recordCount = 0,
                    nowContentMinute = 1_500,
                    viewportStartMinutes = 1_440,
                    viewportDurationMinutes = 1_440,
                    titleSecondary = "时间轴",
                    modifier = Modifier.testTag("empty_timeline_rail"),
                )
            }
        }

        listOf("睡眠", "喂养", "护理").forEach { label ->
            compose.onNodeWithTag("timeline_lane_$label", useUnmergedTree = true)
                .assertExists()
        }
        val warm = compose.onNodeWithTag("empty_timeline_rail").captureToImage()

        compose.runOnIdle { setStyle("journal") }
        compose.waitForIdle()
        val journal = compose.onNodeWithTag("empty_timeline_rail").captureToImage()

        assertTrue(warm.width > 0 && warm.height > 0)
        assertTrue(journal.width > 0 && journal.height > 0)
    }

    @Test
    fun horizontalDragReportsDirectDistanceAndDoesNotTriggerMarkerTap() {
        val selected = AtomicReference<String?>(null)
        val reportedDelta = AtomicReference(0f)
        val panEnds = AtomicInteger(0)
        compose.setContent {
            LeziTheme(visualStyle = "warm") {
                TimelineRailCard(
                    sleep = emptyList(),
                    feed = emptyList(),
                    care = listOf(
                        TimelineLaneSegment(
                            startMinOfDay = 1_800,
                            endMinOfDay = 1_800,
                            colorRole = LeziRecordColorRole.Pee,
                            isEvent = true,
                            dayChartCategoryKey = "PEE",
                        ),
                    ),
                    recordCount = 1,
                    nowContentMinute = null,
                    selectedCategoryKey = null,
                    onCategorySelect = selected::set,
                    viewportStartMinutes = 1_440,
                    viewportDurationMinutes = 1_440,
                    onHorizontalPan = { pan -> reportedDelta.set(pan.cumulativeDeltaPx) },
                    onPanEnd = { panEnds.incrementAndGet() },
                    modifier = Modifier.testTag("interactive_timeline_rail"),
                )
            }
        }

        val careLane = compose.onNodeWithTag("timeline_lane_护理", useUnmergedTree = true)
        careLane.performTouchInput { swipeUp() }
        careLane.performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.4f, 0f))
            up()
        }
        compose.waitForIdle()

        assertNotEquals(0f, reportedDelta.get())
        assertNull(selected.get())
        assertEquals(1, panEnds.get())

        careLane.performTouchInput {
            click(Offset(width * 0.25f, height * 0.32f))
        }
        compose.waitForIdle()
        assertEquals("PEE", selected.get())
    }

    @Test
    fun disposingRailDuringHorizontalDragCancelsPreviewInsteadOfCommittingIt() {
        lateinit var hideRail: () -> Unit
        val reportedDelta = AtomicReference(0f)
        val panEnds = AtomicInteger(0)
        val panCancels = AtomicInteger(0)
        compose.setContent {
            var showRail by remember { mutableStateOf(true) }
            hideRail = { showRail = false }
            LeziTheme(visualStyle = "warm") {
                if (showRail) {
                    TimelineRailCard(
                        sleep = emptyList(),
                        feed = emptyList(),
                        care = emptyList(),
                        recordCount = 0,
                        nowContentMinute = null,
                        onHorizontalPan = { pan ->
                            reportedDelta.set(pan.cumulativeDeltaPx)
                        },
                        onPanEnd = { panEnds.incrementAndGet() },
                        onPanCancel = { panCancels.incrementAndGet() },
                        modifier = Modifier.testTag("cancellable_timeline_rail"),
                    )
                }
            }
        }

        compose.onNodeWithTag("timeline_lane_护理", useUnmergedTree = true)
            .performTouchInput {
                down(center)
                moveBy(Offset(-width * 0.4f, 0f))
            }
        compose.runOnIdle(hideRail)
        compose.waitForIdle()

        assertNotEquals(0f, reportedDelta.get())
        assertEquals(0, panEnds.get())
        assertEquals(1, panCancels.get())
    }

    @Test
    fun verticalIntentScrollsTheOuterList() {
        val scrollOffset = AtomicInteger(0)
        compose.setContent {
            val listState = rememberLazyListState()
            scrollOffset.set(listState.firstVisibleItemScrollOffset)
            LeziTheme(visualStyle = "journal") {
                LazyColumn(state = listState, modifier = Modifier.testTag("timeline_list")) {
                    item {
                        TimelineRailCard(
                            sleep = emptyList(),
                            feed = emptyList(),
                            care = emptyList(),
                            recordCount = 0,
                            nowContentMinute = null,
                            onHorizontalPan = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item { Spacer(Modifier.height(2_000.dp)) }
                }
            }
        }

        compose.onNodeWithTag("timeline_lane_护理", useUnmergedTree = true)
            .performTouchInput { swipeUp() }
        compose.waitForIdle()

        // Read through semantics-idle after the gesture; a positive scroll proves the rail
        // left vertical movement unconsumed for the LazyColumn.
        compose.runOnIdle { assertTrue(scrollOffset.get() > 0) }
    }
}
