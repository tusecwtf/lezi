package com.lezi.babylog.feature.log.timeline

import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.DayChartCategory
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineInteractionTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun initializationUsesLiveElapsedDayAndHistoricalNaturalDays() {
        val liveNow = instant("2026-08-08T08:00:00+08:00")
        val live = initialize(
            selectedDay = LocalDate.of(2026, 8, 8),
            nowMs = liveNow,
        )

        assertEquals(TimelineInteractionMode.LiveAttached, live.mode)
        assertEquals(LocalDate.of(2026, 8, 8), live.selectedDay)
        assertEquals(liveNow - hours(24), live.viewport.startInstantMs)
        assertEquals(liveNow, live.viewport.endInstantMs)

        val cases = listOf(
            NaturalDayCase(LocalDate.of(2026, 3, 8), hours(23)),
            NaturalDayCase(LocalDate.of(2026, 11, 1), hours(25)),
        )
        cases.forEach { case ->
            val state = initialize(
                selectedDay = case.day,
                nowMs = instant("2026-12-01T12:00:00-05:00"),
                zoneId = newYork,
            )
            assertEquals(TimelineInteractionMode.Browsing, state.mode)
            assertEquals(case.durationMs, state.viewport.durationMs)
            assertEquals(
                case.day.atStartOfDay(newYork).toInstant().toEpochMilli(),
                state.viewport.startInstantMs,
            )
            assertEquals(
                case.day.plusDays(1).atStartOfDay(newYork).toInstant().toEpochMilli(),
                state.viewport.endInstantMs,
            )
        }
    }

    @Test
    fun liveClockMovesElapsedViewportAndAdvancesSelectedDayAtMidnight() {
        val beforeMidnight = instant("2026-08-08T23:59:00+08:00")
        val afterMidnight = instant("2026-08-09T00:01:00+08:00")
        val initial = initialize(LocalDate.of(2026, 8, 8), beforeMidnight)

        val result = TimelineInteraction.reduce(
            initial,
            TimelineInteractionEvent.ClockAdvanced(afterMidnight, shanghai),
        )

        assertEquals(LocalDate.of(2026, 8, 9), result.state.selectedDay)
        assertEquals(afterMidnight - hours(24), result.state.viewport.startInstantMs)
        assertEquals(afterMidnight, result.state.viewport.endInstantMs)
        assertEquals(
            TimelineInteractionEffect.CommitSelectedDay(LocalDate.of(2026, 8, 9)),
            result.effect,
        )
    }

    @Test
    fun browsingClockAcrossMidnightKeepsSelectedDayAndAbsoluteViewport() {
        val beforeMidnight = instant("2026-08-08T23:59:00+08:00")
        val afterMidnight = instant("2026-08-09T00:01:00+08:00")
        val initial = initialize(LocalDate.of(2026, 8, 7), beforeMidnight)

        val advanced = TimelineInteraction.reduce(
            initial,
            TimelineInteractionEvent.ClockAdvanced(afterMidnight, shanghai),
        )

        assertEquals(TimelineInteractionMode.Browsing, advanced.state.mode)
        assertEquals(LocalDate.of(2026, 8, 7), advanced.state.selectedDay)
        assertEquals(initial.viewport, advanced.state.viewport)
        assertNull(advanced.effect)
    }

    @Test
    fun directDragUsesCumulativeDistanceNotReleaseVelocityAndStopsImmediately() {
        val now = instant("2026-08-08T18:00:00+08:00")
        val initial = initialize(LocalDate.of(2026, 8, 7), now)

        val slow = drag(initial, cumulativeDeltaPx = 500.0, effectiveWidthPx = 1_000.0, now)
        val fast = drag(initial, cumulativeDeltaPx = 500.0, effectiveWidthPx = 1_000.0, now)
        assertEquals(initial.viewport.startInstantMs - hours(12), slow.viewport.startInstantMs)
        assertEquals(slow.viewport, fast.viewport)

        val ended = TimelineInteraction.reduce(
            slow,
            TimelineInteractionEvent.DragEnded(now),
        ).state
        val afterClock = TimelineInteraction.reduce(
            ended,
            TimelineInteractionEvent.ClockAdvanced(now + hours(1), shanghai),
        ).state
        assertEquals(ended.viewport, afterClock.viewport)
        assertNull(afterClock.drag)
    }

    @Test
    fun fullWidthDragUsesDstViewportDurationAndOneGestureCannotSkipTwoDays() {
        val now = instant("2026-12-01T12:00:00-05:00")
        val cases = listOf(
            NaturalDayCase(LocalDate.of(2026, 3, 8), hours(23)),
            NaturalDayCase(LocalDate.of(2026, 11, 1), hours(25)),
        )

        cases.forEach { case ->
            val initial = initialize(case.day, now, newYork)
            val dragged = drag(
                initial,
                cumulativeDeltaPx = 8_000.0,
                effectiveWidthPx = 1_000.0,
                nowMs = now,
            )
            assertEquals(
                initial.viewport.startInstantMs - case.durationMs,
                dragged.viewport.startInstantMs,
            )
            val ended = TimelineInteraction.reduce(
                dragged,
                TimelineInteractionEvent.DragEnded(now),
            )
            assertEquals(case.day.minusDays(1), ended.state.selectedDay)
            assertEquals(
                TimelineInteractionEffect.CommitSelectedDay(case.day.minusDays(1)),
                ended.effect,
            )
        }
    }

    @Test
    fun livePastTrialReturnsToNowUntilRightEdgeCrossesTodayMidnight() {
        val now = instant("2026-08-08T08:00:00+08:00")
        val initial = initialize(LocalDate.of(2026, 8, 8), now)

        val incomplete = drag(initial, 250.0, 1_000.0, now)
        val returned = TimelineInteraction.reduce(
            incomplete,
            TimelineInteractionEvent.DragEnded(now),
        )
        assertEquals(initial.viewport, returned.state.viewport)
        assertEquals(TimelineInteractionMode.LiveAttached, returned.state.mode)
        assertNull(returned.effect)

        val crossed = drag(initial, 500.0, 1_000.0, now)
        val historical = TimelineInteraction.reduce(
            crossed,
            TimelineInteractionEvent.DragEnded(now),
        )
        assertEquals(TimelineInteractionMode.Browsing, historical.state.mode)
        assertEquals(LocalDate.of(2026, 8, 7), historical.state.selectedDay)
        assertEquals(crossed.viewport, historical.state.viewport)
        assertEquals(
            TimelineInteractionEffect.CommitSelectedDay(LocalDate.of(2026, 8, 7)),
            historical.effect,
        )
    }

    @Test
    fun historyCanEnterTodayWithoutAttachingThenAttachOnlyAtNow() {
        val now = instant("2026-08-08T18:00:00+08:00")
        val yesterday = initialize(LocalDate.of(2026, 8, 7), now)
        val intoToday = drag(yesterday, -500.0, 1_000.0, now)
        val browsingToday = TimelineInteraction.reduce(
            intoToday,
            TimelineInteractionEvent.DragEnded(now),
        )

        assertEquals(LocalDate.of(2026, 8, 8), browsingToday.state.selectedDay)
        assertEquals(TimelineInteractionMode.Browsing, browsingToday.state.mode)
        assertEquals(instant("2026-08-08T12:00:00+08:00"), browsingToday.state.viewport.endInstantMs)

        val atNow = drag(browsingToday.state, -250.0, 1_000.0, now)
        val attached = TimelineInteraction.reduce(
            atNow,
            TimelineInteractionEvent.DragEnded(now),
        )
        assertEquals(TimelineInteractionMode.LiveAttached, attached.state.mode)
        assertEquals(now - hours(24), attached.state.viewport.startInstantMs)
        assertEquals(now, attached.state.viewport.endInstantMs)
        assertNull(attached.effect)
    }

    @Test
    fun reachingNowDuringDragStillAttachesWhenReleaseSamplesALaterClock() {
        val dragNow = instant("2026-08-08T18:00:00+08:00")
        val releaseNow = dragNow + Duration.ofSeconds(1).toMillis()
        val yesterday = initialize(LocalDate.of(2026, 8, 7), dragNow)
        val browsingToday = TimelineInteraction.reduce(
            drag(yesterday, -500.0, 1_000.0, dragNow),
            TimelineInteractionEvent.DragEnded(dragNow),
        ).state
        val reachedNow = drag(browsingToday, -250.0, 1_000.0, dragNow)

        val attached = TimelineInteraction.reduce(
            reachedNow,
            TimelineInteractionEvent.DragEnded(releaseNow),
        ).state

        assertEquals(TimelineInteractionMode.LiveAttached, attached.mode)
        assertEquals(releaseNow, attached.viewport.endInstantMs)
    }

    @Test
    fun forwardInputClampsAtNowAndCannotSelectTheFuture() {
        val now = instant("2026-08-08T18:00:00+08:00")
        val initial = initialize(LocalDate.of(2026, 8, 8), now)
        val forward = drag(initial, -5_000.0, 1_000.0, now)
        val ended = TimelineInteraction.reduce(
            forward,
            TimelineInteractionEvent.DragEnded(now),
        )

        assertEquals(now, forward.viewport.endInstantMs)
        assertEquals(LocalDate.of(2026, 8, 8), ended.state.selectedDay)
        assertEquals(TimelineInteractionMode.LiveAttached, ended.state.mode)
        assertNull(ended.effect)
    }

    @Test
    fun dateEffectRebuildsWorkWindowWithoutChangingAbsoluteViewport() {
        val now = instant("2026-08-08T18:00:00+08:00")
        val initial = initialize(LocalDate.of(2026, 8, 7), now)
        val dragged = drag(initial, 750.0, 1_000.0, now)
        val result = TimelineInteraction.reduce(
            dragged,
            TimelineInteractionEvent.DragEnded(now),
        )

        assertEquals(LocalDate.of(2026, 8, 6), result.state.selectedDay)
        assertEquals(dragged.viewport, result.state.viewport)
        assertEquals(
            LocalDate.of(2026, 8, 5).atStartOfDay(shanghai).toInstant().toEpochMilli(),
            result.state.workWindow.startInstantMs,
        )
        assertTrue(result.state.viewport.startInstantMs >= result.state.workWindow.startInstantMs)
        assertTrue(result.state.viewport.endInstantMs <= result.state.workWindow.endInstantMs)
    }

    @Test
    fun cancelRestoresGestureOriginAndRetainedRestoreKeepsBrowsingViewport() {
        val now = instant("2026-08-08T18:00:00+08:00")
        val initial = initialize(LocalDate.of(2026, 8, 7), now)
        val dragged = drag(initial, 400.0, 1_000.0, now)
        val cancelled = TimelineInteraction.reduce(
            dragged,
            TimelineInteractionEvent.DragCancelled(now),
        ).state
        assertEquals(initial, cancelled)

        val retained = TimelineInteraction.reduce(
            null,
            TimelineInteractionEvent.Restore(cancelled, now + hours(3), shanghai),
        ).state
        assertEquals(TimelineInteractionMode.Browsing, retained.mode)
        assertEquals(initial.viewport, retained.viewport)

        val coldToday = initialize(LocalDate.of(2026, 8, 8), now + hours(3))
        assertEquals(TimelineInteractionMode.LiveAttached, coldToday.mode)
        assertEquals(now + hours(3), coldToday.viewport.endInstantMs)
    }

    @Test
    fun cancellingLiveGestureAfterMidnightResumesAtTheNewNow() {
        val beforeMidnight = instant("2026-08-08T23:59:00+08:00")
        val afterMidnight = instant("2026-08-09T00:01:00+08:00")
        val initial = initialize(LocalDate.of(2026, 8, 8), beforeMidnight)
        val dragged = drag(initial, 100.0, 1_000.0, beforeMidnight)

        val cancelled = TimelineInteraction.reduce(
            dragged,
            TimelineInteractionEvent.DragCancelled(afterMidnight),
        )

        assertEquals(TimelineInteractionMode.LiveAttached, cancelled.state.mode)
        assertEquals(LocalDate.of(2026, 8, 9), cancelled.state.selectedDay)
        assertEquals(afterMidnight, cancelled.state.viewport.endInstantMs)
        assertEquals(
            TimelineInteractionEffect.CommitSelectedDay(LocalDate.of(2026, 8, 9)),
            cancelled.effect,
        )
    }

    @Test
    fun endingAnIncompleteLiveGestureAfterMidnightAdvancesToTheNewToday() {
        val beforeMidnight = instant("2026-08-08T23:59:00+08:00")
        val afterMidnight = instant("2026-08-09T00:01:00+08:00")
        val initial = initialize(LocalDate.of(2026, 8, 8), beforeMidnight)
        val started = TimelineInteraction.reduce(
            initial,
            TimelineInteractionEvent.DragStarted,
        ).state

        val ended = TimelineInteraction.reduce(
            started,
            TimelineInteractionEvent.DragEnded(afterMidnight),
        )

        assertEquals(TimelineInteractionMode.LiveAttached, ended.state.mode)
        assertEquals(LocalDate.of(2026, 8, 9), ended.state.selectedDay)
        assertEquals(afterMidnight, ended.state.viewport.endInstantMs)
        assertEquals(
            TimelineInteractionEffect.CommitSelectedDay(LocalDate.of(2026, 8, 9)),
            ended.effect,
        )
    }

    @Test
    fun filterLifecyclePreservesSameBabyAcrossDaysAndRefreshButClearsForBabyChange() {
        val now = instant("2026-08-08T18:00:00+08:00")
        val initial = initialize(LocalDate.of(2026, 8, 7), now, babyId = 1L)
        val selected = TimelineInteraction.reduce(
            initial,
            TimelineInteractionEvent.SelectCategory(
                categoryKey = "PEE",
                dayRecords = listOf(stubRecord(1, RecordType.PEE)),
            ),
        ).state
        assertEquals(DayChartCategory.PEE, selected.filter.selection)
        assertEquals(TimelineFilterResult.Matching, selected.filter.result)

        val nextDay = TimelineInteraction.reduce(
            selected,
            TimelineInteractionEvent.ExternalDaySelected(
                selectedDay = LocalDate.of(2026, 8, 8),
                nowMs = now,
                zoneId = shanghai,
            ),
        ).state
        assertEquals(DayChartCategory.PEE, nextDay.filter.selection)
        assertEquals(TimelineFilterResult.AwaitingRecords, nextDay.filter.result)

        val empty = TimelineInteraction.reduce(
            nextDay,
            TimelineInteractionEvent.RecordsRefreshed(
                listOf(stubRecord(2, RecordType.POOP)),
            ),
        ).state
        assertEquals(DayChartCategory.PEE, empty.filter.selection)
        assertEquals(TimelineFilterResult.Empty, empty.filter.result)

        val changedBaby = TimelineInteraction.reduce(
            empty,
            TimelineInteractionEvent.BabyChanged(2L),
        ).state
        assertNull(changedBaby.filter.selection)
        assertEquals(TimelineFilterResult.AllRecords, changedBaby.filter.result)
    }

    @Test
    fun filterA2RejectsAbsentNewCategoryAndRepeatTapClears() {
        val now = instant("2026-08-08T18:00:00+08:00")
        val initial = initialize(LocalDate.of(2026, 8, 8), now)
        val absent = TimelineInteraction.reduce(
            initial,
            TimelineInteractionEvent.SelectCategory(
                categoryKey = "PEE",
                dayRecords = listOf(stubRecord(1, RecordType.POOP)),
            ),
        ).state
        assertNull(absent.filter.selection)

        val selected = TimelineInteraction.reduce(
            absent,
            TimelineInteractionEvent.SelectCategory(
                categoryKey = "PEE",
                dayRecords = listOf(stubRecord(2, RecordType.PEE)),
            ),
        ).state
        val cleared = TimelineInteraction.reduce(
            selected,
            TimelineInteractionEvent.SelectCategory(
                categoryKey = null,
                dayRecords = emptyList(),
            ),
        ).state
        assertNull(cleared.filter.selection)
        assertEquals(TimelineFilterResult.AllRecords, cleared.filter.result)
    }

    private fun initialize(
        selectedDay: LocalDate,
        nowMs: Long,
        zoneId: ZoneId = shanghai,
        babyId: Long? = null,
    ): TimelineInteractionState = TimelineInteraction.reduce(
        null,
        TimelineInteractionEvent.Initialize(
            selectedDay = selectedDay,
            babyId = babyId,
            nowMs = nowMs,
            zoneId = zoneId,
        ),
    ).state

    private fun drag(
        state: TimelineInteractionState,
        cumulativeDeltaPx: Double,
        effectiveWidthPx: Double,
        nowMs: Long,
    ): TimelineInteractionState {
        val started = TimelineInteraction.reduce(
            state,
            TimelineInteractionEvent.DragStarted,
        ).state
        return TimelineInteraction.reduce(
            started,
            TimelineInteractionEvent.DragChanged(
                cumulativeDeltaPx = cumulativeDeltaPx,
                effectiveWidthPx = effectiveWidthPx,
                nowMs = nowMs,
            ),
        ).state
    }

    private fun instant(value: String): Long = OffsetDateTime.parse(value).toInstant().toEpochMilli()

    private fun hours(value: Long): Long = Duration.ofHours(value).toMillis()

    private fun stubRecord(
        id: Long,
        type: RecordType,
    ) = com.lezi.babylog.core.model.Record(
        id = id,
        clientUuid = "u$id",
        babyId = 1,
        type = type,
        timestamp = 1_700_000_000_000L + id,
        endTimestamp = null,
        payloadJson = "{}",
        updatedAt = 1_700_000_000_000L + id,
        deletedAt = null,
    )

    private data class NaturalDayCase(
        val day: LocalDate,
        val durationMs: Long,
    )
}
