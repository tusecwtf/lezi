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
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziRecordColorRole
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.TimelineLaneSegment
import com.lezi.babylog.designsystem.TimelineRailCard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
                    nowMs = 1_500L * MINUTE_MS,
                    viewportStartMs = 1_440L * MINUTE_MS,
                    viewportDurationMs = 1_440L * MINUTE_MS,
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
                            startMs = 1_800L * MINUTE_MS,
                            endMs = 1_800L * MINUTE_MS,
                            colorRole = LeziRecordColorRole.Pee,
                            isEvent = true,
                            dayChartCategoryKey = "PEE",
                        ),
                    ),
                    recordCount = 1,
                    nowMs = null,
                    selectedCategoryKey = null,
                    onCategorySelect = selected::set,
                    viewportStartMs = 1_440L * MINUTE_MS,
                    viewportDurationMs = 1_440L * MINUTE_MS,
                    onHorizontalPan = { pan ->
                        reportedDelta.set(pan.deltaPx)
                        pan.deltaPx
                    },
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
    fun disposingRailDuringHorizontalDragSettlesTheViewport() {
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
                        nowMs = null,
                        onHorizontalPan = { pan ->
                            reportedDelta.set(pan.deltaPx)
                            pan.deltaPx
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
        assertEquals(1, panEnds.get())
        assertEquals(0, panCancels.get())
    }

    @Test
    fun futureClampAndFilterNavigationMatchAcrossWarmAndJournalTemplates() {
        val day = LocalDate.of(2026, 8, 8)
        val zone = ZoneId.of("Asia/Shanghai")
        val nowMs = Instant.parse("2026-08-08T10:00:00Z").toEpochMilli()
        val initial = TimelineInteraction.reduce(
            null,
            TimelineInteractionEvent.Initialize(day, 7L, nowMs, zone),
        ).state
        val stateSnapshot = AtomicReference(initial)
        val selectedCategory = AtomicReference<String?>(null)
        lateinit var resetTemplate: (String) -> Unit
        compose.setContent {
            var style by remember { mutableStateOf("warm") }
            var interaction by remember { mutableStateOf(initial) }
            stateSnapshot.set(interaction)
            resetTemplate = { nextStyle ->
                style = nextStyle
                interaction = initial
                selectedCategory.set(null)
            }
            val frame = interaction.toTimelineRailFrame(nowMs)
            LeziTheme(visualStyle = style) {
                TimelineRailCard(
                    sleep = emptyList(),
                    feed = emptyList(),
                    care = listOf(
                        TimelineLaneSegment(
                            startMs = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                            endMs = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                            colorRole = LeziRecordColorRole.Pee,
                            isEvent = true,
                            dayChartCategoryKey = "PEE",
                        ),
                    ),
                    recordCount = 1,
                    nowMs = frame.nowMs,
                    viewportStartMs = frame.viewportStartMs,
                    viewportDurationMs = frame.viewportDurationMs,
                    dayBoundariesMs = frame.dayBoundariesMs,
                    dayBoundaryLabels = frame.dayBoundaryLabels,
                    primaryRangeMs = frame.primaryRangeMs,
                    hourTicks = frame.hourTicks,
                    onCategorySelect = selectedCategory::set,
                    onHorizontalPan = { pan ->
                        if (interaction.drag == null) {
                            interaction = TimelineInteraction.reduce(
                                interaction,
                                TimelineInteractionEvent.DragStarted,
                            ).state
                        }
                        val result = TimelineInteraction.reduce(
                            interaction,
                            TimelineInteractionEvent.DragChanged(
                                deltaPx = pan.deltaPx.toDouble(),
                                widthPx = pan.axisLengthPx.toDouble(),
                                nowMs = nowMs,
                            ),
                        )
                        interaction = result.state
                        result.consumedPx
                    },
                    onPanEnd = {
                        interaction = TimelineInteraction.reduce(
                            interaction,
                            TimelineInteractionEvent.DragEnded(nowMs),
                        ).state
                    },
                )
            }
        }

        val outcomes = listOf("warm", "journal").map { style ->
            compose.runOnIdle { resetTemplate(style) }
            val careLane = compose.onNodeWithTag("timeline_lane_护理", useUnmergedTree = true)
            careLane.performTouchInput {
                down(center)
                moveBy(Offset(-width * 0.6f, 0f))
                up()
            }
            careLane.performTouchInput {
                click(Offset(width * 0.75f, height * 0.32f))
            }
            compose.waitForIdle()
            stateSnapshot.get() to selectedCategory.get()
        }

        outcomes.forEach { (state, category) ->
            assertEquals(day, state.selectedDay)
            assertEquals(nowMs, state.viewport.endInstantMs)
            assertNull(state.drag)
            assertEquals("PEE", category)
        }
        assertEquals(outcomes.first(), outcomes.last())
    }

    @Test
    fun changingSelectedDayKeepsDrawnViewportStart() {
        val today = LocalDate.of(2026, 8, 8)
        val zone = ZoneId.of("Asia/Shanghai")
        val nowMs = Instant.parse("2026-08-08T00:00:00Z").toEpochMilli()
        val initial = TimelineInteraction.reduce(
            null,
            TimelineInteractionEvent.Initialize(today, 7L, nowMs, zone),
        ).state
        val drawn = AtomicLong()
        val drawnAtCommit = AtomicLong()
        val stateSnapshot = AtomicReference(initial)
        compose.setContent {
            var interaction by remember { mutableStateOf(initial) }
            stateSnapshot.set(interaction)
            val frame = interaction.toTimelineRailFrame(nowMs)
            LeziTheme(visualStyle = "warm") {
                TimelineRailCard(
                    sleep = emptyList(),
                    feed = emptyList(),
                    care = emptyList(),
                    recordCount = 0,
                    nowMs = frame.nowMs,
                    viewportStartMs = frame.viewportStartMs,
                    viewportDurationMs = frame.viewportDurationMs,
                    dayBoundariesMs = frame.dayBoundariesMs,
                    dayBoundaryLabels = frame.dayBoundaryLabels,
                    primaryRangeMs = frame.primaryRangeMs,
                    hourTicks = frame.hourTicks,
                    onDrawnViewportStart = drawn::set,
                    onHorizontalPan = { pan ->
                        if (interaction.drag == null) {
                            interaction = TimelineInteraction.reduce(
                                interaction,
                                TimelineInteractionEvent.DragStarted,
                            ).state
                        }
                        val result = TimelineInteraction.reduce(
                            interaction,
                            TimelineInteractionEvent.DragChanged(
                                deltaPx = pan.deltaPx.toDouble(),
                                widthPx = pan.axisLengthPx.toDouble(),
                                nowMs = nowMs,
                            ),
                        )
                        interaction = result.state
                        result.consumedPx
                    },
                    onPanEnd = {
                        drawnAtCommit.set(drawn.get())
                        interaction = TimelineInteraction.reduce(
                            interaction,
                            TimelineInteractionEvent.DragEnded(nowMs),
                        ).state
                    },
                    modifier = Modifier.testTag("zero_shift_timeline_rail"),
                )
            }
        }

        compose.waitForIdle()
        val careLane = compose.onNodeWithTag("timeline_lane_护理", useUnmergedTree = true)
        careLane.performTouchInput {
            down(center)
            // 3h of a 24h live window: yesterday share wins, but the viewport is
            // not yesterday's natural day — old minute rebase would have swept.
            moveBy(Offset(width * 3f / 24f, 0f))
            up()
        }
        compose.waitForIdle()

        val committed = stateSnapshot.get()
        assertEquals(LocalDate.of(2026, 8, 7), committed.selectedDay)
        assertEquals(drawnAtCommit.get(), drawn.get())
        assertEquals(committed.viewport.startInstantMs, drawn.get())
        assertNotEquals(
            committed.selectedDay.atStartOfDay(zone).toInstant().toEpochMilli(),
            committed.viewport.startInstantMs,
        )
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
                            nowMs = null,
                            onHorizontalPan = { 0f },
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

        compose.runOnIdle { assertTrue(scrollOffset.get() > 0) }
    }

    @Test
    fun horizontalFlingReportsDistanceAndSettlesWithPanEnd() {
        val reportedDelta = AtomicReference(0f)
        val panEnds = AtomicInteger(0)
        compose.setContent {
            LeziTheme(visualStyle = "warm") {
                TimelineRailCard(
                    sleep = emptyList(),
                    feed = emptyList(),
                    care = emptyList(),
                    recordCount = 0,
                    nowMs = null,
                    onHorizontalPan = { pan ->
                        reportedDelta.set(pan.deltaPx)
                        pan.deltaPx
                    },
                    onPanEnd = { panEnds.incrementAndGet() },
                    modifier = Modifier.testTag("fling_timeline_rail"),
                )
            }
        }

        compose.onNodeWithTag("timeline_lane_护理", useUnmergedTree = true)
            .performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertNotEquals(0f, reportedDelta.get())
        assertEquals(1, panEnds.get())
    }

    @Test
    fun flingAcrossTwoDaysSelectsTheFartherDay() {
        val day = LocalDate.of(2026, 8, 6)
        val zone = ZoneId.of("Asia/Shanghai")
        val nowMs = Instant.parse("2026-08-08T10:00:00Z").toEpochMilli()
        val initial = TimelineInteraction.reduce(
            null,
            TimelineInteractionEvent.Initialize(day, 7L, nowMs, zone),
        ).state
        val stateSnapshot = AtomicReference(initial)
        compose.setContent {
            var interaction by remember { mutableStateOf(initial) }
            stateSnapshot.set(interaction)
            val frame = interaction.toTimelineRailFrame(nowMs)
            LeziTheme(visualStyle = "warm") {
                TimelineRailCard(
                    sleep = emptyList(),
                    feed = emptyList(),
                    care = emptyList(),
                    recordCount = 0,
                    nowMs = frame.nowMs,
                    viewportStartMs = frame.viewportStartMs,
                    viewportDurationMs = frame.viewportDurationMs,
                    dayBoundariesMs = frame.dayBoundariesMs,
                    dayBoundaryLabels = frame.dayBoundaryLabels,
                    primaryRangeMs = frame.primaryRangeMs,
                    hourTicks = frame.hourTicks,
                    onHorizontalPan = { pan ->
                        if (interaction.drag == null) {
                            interaction = TimelineInteraction.reduce(
                                interaction,
                                TimelineInteractionEvent.DragStarted,
                            ).state
                        }
                        val result = TimelineInteraction.reduce(
                            interaction,
                            TimelineInteractionEvent.DragChanged(
                                deltaPx = pan.deltaPx.toDouble(),
                                widthPx = pan.axisLengthPx.toDouble(),
                                nowMs = nowMs,
                            ),
                        )
                        interaction = result.state
                        result.consumedPx
                    },
                    onPanEnd = {
                        interaction = TimelineInteraction.reduce(
                            interaction,
                            TimelineInteractionEvent.DragEnded(nowMs),
                        ).state
                    },
                    modifier = Modifier.testTag("multi_day_fling_rail"),
                )
            }
        }

        compose.onNodeWithTag("timeline_lane_护理", useUnmergedTree = true)
            .performTouchInput {
                swipe(
                    start = center,
                    end = center.copy(x = center.x + width * 2.2f),
                    durationMillis = 80,
                )
            }
        compose.waitForIdle()

        val committed = stateSnapshot.get()
        assertTrue(!committed.selectedDay.isAfter(day.minusDays(2)))
        assertEquals(committed.viewport.durationMs, initial.viewport.durationMs)
        assertTrue(committed.viewport.endInstantMs <= nowMs)
    }

    @Test
    fun flingIntoNowStopsWithoutGoingFuture() {
        val today = LocalDate.of(2026, 8, 8)
        val zone = ZoneId.of("Asia/Shanghai")
        val nowMs = Instant.parse("2026-08-08T10:00:00Z").toEpochMilli()
        val initial = TimelineInteraction.reduce(
            null,
            TimelineInteractionEvent.Initialize(today, 7L, nowMs, zone),
        ).state
        val stateSnapshot = AtomicReference(initial)
        compose.setContent {
            var interaction by remember { mutableStateOf(initial) }
            stateSnapshot.set(interaction)
            val frame = interaction.toTimelineRailFrame(nowMs)
            LeziTheme(visualStyle = "warm") {
                TimelineRailCard(
                    sleep = emptyList(),
                    feed = emptyList(),
                    care = emptyList(),
                    recordCount = 0,
                    nowMs = frame.nowMs,
                    viewportStartMs = frame.viewportStartMs,
                    viewportDurationMs = frame.viewportDurationMs,
                    dayBoundariesMs = frame.dayBoundariesMs,
                    dayBoundaryLabels = frame.dayBoundaryLabels,
                    primaryRangeMs = frame.primaryRangeMs,
                    hourTicks = frame.hourTicks,
                    onHorizontalPan = { pan ->
                        if (interaction.drag == null) {
                            interaction = TimelineInteraction.reduce(
                                interaction,
                                TimelineInteractionEvent.DragStarted,
                            ).state
                        }
                        val result = TimelineInteraction.reduce(
                            interaction,
                            TimelineInteractionEvent.DragChanged(
                                deltaPx = pan.deltaPx.toDouble(),
                                widthPx = pan.axisLengthPx.toDouble(),
                                nowMs = nowMs,
                            ),
                        )
                        interaction = result.state
                        result.consumedPx
                    },
                    onPanEnd = {
                        interaction = TimelineInteraction.reduce(
                            interaction,
                            TimelineInteractionEvent.DragEnded(nowMs),
                        ).state
                    },
                    modifier = Modifier.testTag("now_boundary_fling_rail"),
                )
            }
        }

        compose.onNodeWithTag("timeline_lane_护理", useUnmergedTree = true)
            .performTouchInput { swipeLeft() }
        compose.waitForIdle()

        val committed = stateSnapshot.get()
        assertEquals(today, committed.selectedDay)
        assertEquals(nowMs, committed.viewport.endInstantMs)
        assertTrue(committed.viewport.endInstantMs <= nowMs)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
