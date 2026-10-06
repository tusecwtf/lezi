package com.lezi.babylog.feature.log.timeline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziTheme
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReturnToNowButtonDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 8, 8)
    private val nowMs = OffsetDateTime.parse("2026-08-08T08:00:00+08:00")
        .toInstant()
        .toEpochMilli()

    @Test
    fun browsingTodayShowsReturnToNowAndClickReattachesLive() {
        val browsingToday = leaveLiveAttachByTwoHours()
        assertEquals(TimelineInteractionMode.Browsing, browsingToday.mode)
        assertEquals(today, browsingToday.selectedDay)

        var attached: TimelineInteractionState? = null
        compose.setContent {
            var state by remember { mutableStateOf(browsingToday) }
            LeziTheme(visualStyle = "warm") {
                if (state.mode == TimelineInteractionMode.Browsing) {
                    LogTimelineReturnToNowButton(
                        selectedDay = state.selectedDay,
                        today = today,
                        onClick = {
                            state = TimelineInteraction.reduce(
                                state,
                                TimelineInteractionEvent.ReturnToNow(nowMs, shanghai),
                            ).state
                            attached = state
                        },
                    )
                }
            }
        }

        compose.onNodeWithTag(LOG_TIMELINE_RETURN_TO_NOW_TAG).assertIsDisplayed()
        compose.onNodeWithText("回到现在").assertIsDisplayed()
        compose.onNodeWithTag(LOG_TIMELINE_RETURN_TO_NOW_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(LOG_TIMELINE_RETURN_TO_NOW_TAG).assertDoesNotExist()
        compose.onNodeWithText("回到现在").assertDoesNotExist()
        val live = requireNotNull(attached)
        assertEquals(TimelineInteractionMode.LiveAttached, live.mode)
        assertEquals(today, live.selectedDay)
        assertEquals(nowMs - java.time.Duration.ofHours(24).toMillis(), live.viewport.startInstantMs)
        assertEquals(nowMs, live.viewport.endInstantMs)
    }

    @Test
    fun browsingYesterdayShowsReturnToTodayLabel() {
        val browsingYesterday = TimelineInteraction.reduce(
            dragLive(3.0 / 24.0 * 1_000.0),
            TimelineInteractionEvent.DragEnded(nowMs),
        ).state
        assertEquals(LocalDate.of(2026, 8, 7), browsingYesterday.selectedDay)

        compose.setContent {
            LeziTheme(visualStyle = "warm") {
                LogTimelineReturnToNowButton(
                    selectedDay = browsingYesterday.selectedDay,
                    today = today,
                    onClick = {},
                )
            }
        }

        compose.onNodeWithTag(LOG_TIMELINE_RETURN_TO_NOW_TAG).assertIsDisplayed()
        compose.onNodeWithText("返回今天").assertIsDisplayed()
    }

    @Test
    fun liveAttachedHidesReturnButton() {
        val live = TimelineInteraction.reduce(
            null,
            TimelineInteractionEvent.Initialize(
                selectedDay = today,
                babyId = null,
                nowMs = nowMs,
                zoneId = shanghai,
            ),
        ).state
        assertEquals(TimelineInteractionMode.LiveAttached, live.mode)

        compose.setContent {
            LeziTheme(visualStyle = "warm") {
                if (live.mode == TimelineInteractionMode.Browsing) {
                    LogTimelineReturnToNowButton(
                        selectedDay = live.selectedDay,
                        today = today,
                        onClick = {},
                    )
                }
            }
        }

        compose.onNodeWithTag(LOG_TIMELINE_RETURN_TO_NOW_TAG).assertDoesNotExist()
    }

    private fun leaveLiveAttachByTwoHours(): TimelineInteractionState =
        TimelineInteraction.reduce(
            dragLive(2.0 / 24.0 * 1_000.0),
            TimelineInteractionEvent.DragEnded(nowMs),
        ).state

    private fun dragLive(deltaPx: Double): TimelineInteractionState {
        val live = TimelineInteraction.reduce(
            null,
            TimelineInteractionEvent.Initialize(
                selectedDay = today,
                babyId = null,
                nowMs = nowMs,
                zoneId = shanghai,
            ),
        ).state
        val started = TimelineInteraction.reduce(
            live,
            TimelineInteractionEvent.DragStarted,
        ).state
        return TimelineInteraction.reduce(
            started,
            TimelineInteractionEvent.DragChanged(
                deltaPx = deltaPx,
                widthPx = 1_000.0,
                nowMs = nowMs,
            ),
        ).state
    }
}
