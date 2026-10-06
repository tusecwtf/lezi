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

        val slow = drag(initial, deltaPx = 500.0, widthPx = 1_000.0, now)
        val fast = drag(initial, deltaPx = 500.0, widthPx = 1_000.0, now)
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
    fun fullWidthDragUsesDstViewportDuration() {
        val now = instant("2026-12-01T12:00:00-05:00")
        val cases = listOf(
            NaturalDayCase(LocalDate.of(2026, 3, 8), hours(23)),
            NaturalDayCase(LocalDate.of(2026, 11, 1), hours(25)),
        )

        cases.forEach { case ->
            val initial = initialize(case.day, now, newYork)
            val dragged = drag(
                initial,
                deltaPx = 1_000.0,
                widthPx = 1_000.0,
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
    fun consecutiveDeltasBeyondOneWidthKeepMovingViewportBackward() {
        val now = instant("2026-08-08T18:00:00+08:00")
        val initial = initialize(LocalDate.of(2026, 8, 6), now)
        val first = drag(initial, deltaPx = 1_000.0, widthPx = 1_000.0, nowMs = now)
        val second = TimelineInteraction.reduce(
            first,
            TimelineInteractionEvent.DragChanged(
                deltaPx = 1_000.0,
                widthPx = 1_000.0,
                nowMs = now,
            ),
        ).state

        assertEquals(
            initial.viewport.startInstantMs - hours(24),
            first.viewport.startInstantMs,
        )
        assertEquals(
            initial.viewport.startInstantMs - hours(48),
            second.viewport.startInstantMs,
        )
    }

    @Test
    fun multiDayFlingLandsOnMaxShareDay() {
        val now = instant("2026-08-08T18:00:00+08:00")
        val dayD = LocalDate.of(2026, 8, 6)
        val initial = initialize(dayD, now)
        val flung = drag(initial, deltaPx = 3_200.0, widthPx = 1_000.0, nowMs = now)
        val ended = TimelineInteraction.reduce(
            flung,
            TimelineInteractionEvent.DragEnded(now),
        )

        assertEquals(LocalDate.of(2026, 8, 3), ended.state.selectedDay)
        assertEquals(flung.viewport, ended.state.viewport)
        assertEquals(
            TimelineInteractionEffect.CommitSelectedDay(LocalDate.of(2026, 8, 3)),
            ended.effect,
        )
    }

    @Test
    fun nowBoundaryConsumesZeroPixels() {
        val now = instant("2026-08-08T18:00:00+08:00")
        val initial = initialize(LocalDate.of(2026, 8, 8), now)
        val started = TimelineInteraction.reduce(
            initial,
            TimelineInteractionEvent.DragStarted,
        ).state
        val blocked = TimelineInteraction.reduce(
            started,
            TimelineInteractionEvent.DragChanged(
                deltaPx = -500.0,
                widthPx = 1_000.0,
                nowMs = now,
            ),
        )

        assertEquals(0f, blocked.consumedPx, 0f)
        assertEquals(now, blocked.state.viewport.endInstantMs)
        assertEquals(initial.viewport, blocked.state.viewport)
    }

    @Test
    fun liveMorningTwoHourDragStaysOnTodayAsBrowsing() {
        val now = instant("2026-08-08T08:00:00+08:00")
        val today = LocalDate.of(2026, 8, 8)
        val initial = initialize(today, now)
        val twoHours = drag(initial, 2.0 / 24.0 * 1_000.0, 1_000.0, now)
        val stayed = TimelineInteraction.reduce(
            twoHours,
            TimelineInteractionEvent.DragEnded(now),
        )

        assertEquals(TimelineInteractionMode.Browsing, stayed.state.mode)
        assertEquals(today, stayed.state.selectedDay)
        assertEquals(twoHours.viewport, stayed.state.viewport)
        assertNull(stayed.effect)
    }

    @Test
    fun liveMorningDragPastTodayShareSelectsYesterdayWithoutMovingViewport() {
        val now = instant("2026-08-08T08:00:00+08:00")
        val initial = initialize(LocalDate.of(2026, 8, 8), now)
        val beforeSix = drag(initial, 3.0 / 24.0 * 1_000.0, 1_000.0, now)
        assertTrue(beforeSix.viewport.endInstantMs < instant("2026-08-08T06:00:00+08:00"))

        val historical = TimelineInteraction.reduce(
            beforeSix,
            TimelineInteractionEvent.DragEnded(now),
        )

        assertEquals(TimelineInteractionMode.Browsing, historical.state.mode)
        assertEquals(LocalDate.of(2026, 8, 7), historical.state.selectedDay)
        assertEquals(beforeSix.viewport, historical.state.viewport)
        assertEquals(
            TimelineInteractionEffect.CommitSelectedDay(LocalDate.of(2026, 8, 7)),
            historical.effect,
        )
    }

    @Test
    fun historicalDaySettlesToPreviousWhenItsShareDropsBelowSticky() {
        val now = instant("2026-08-08T18:00:00+08:00")
        val dayD = LocalDate.of(2026, 8, 6)
        val initial = initialize(dayD, now)
        val dragged = drag(initial, 800.0, 1_000.0, now)
        val ended = TimelineInteraction.reduce(
            dragged,
            TimelineInteractionEvent.DragEnded(now),
        )

        assertEquals(LocalDate.of(2026, 8, 5), ended.state.selectedDay)
        assertEquals(dragged.viewport, ended.state.viewport)
        assertEquals(
            TimelineInteractionEffect.CommitSelectedDay(LocalDate.of(2026, 8, 5)),
            ended.effect,
        )
    }

    @Test
    fun reachingNowBoundaryReattachesLiveViewport() {
        val now = instant("2026-08-08T08:00:00+08:00")
        val today = LocalDate.of(2026, 8, 8)
        val initial = initialize(today, now)
        val browsing = TimelineInteraction.reduce(
            drag(initial, 2.0 / 24.0 * 1_000.0, 1_000.0, now),
            TimelineInteractionEvent.DragEnded(now),
        ).state
        val atNow = drag(browsing, -5_000.0, 1_000.0, now)
        val attached = TimelineInteraction.reduce(
            atNow,
            TimelineInteractionEvent.DragEnded(now),
        )

        assertEquals(TimelineInteractionMode.LiveAttached, attached.state.mode)
        assertEquals(today, attached.state.selectedDay)
        assertEquals(now - hours(24), attached.state.viewport.startInstantMs)
        assertEquals(now, attached.state.viewport.endInstantMs)
        assertNull(attached.effect)
    }

    @Test
    fun returnToNowReattachesLiveAndCommitsOnlyWhenDayChanged() {
        val now = instant("2026-08-08T08:00:00+08:00")
        val today = LocalDate.of(2026, 8, 8)
        val live = initialize(today, now)
        val browsingToday = TimelineInteraction.reduce(
            drag(live, 2.0 / 24.0 * 1_000.0, 1_000.0, now),
            TimelineInteractionEvent.DragEnded(now),
        ).state
        val fromToday = TimelineInteraction.reduce(
            browsingToday,
            TimelineInteractionEvent.ReturnToNow(now, shanghai),
        )
        assertEquals(TimelineInteractionMode.LiveAttached, fromToday.state.mode)
        assertEquals(today, fromToday.state.selectedDay)
        assertEquals(now - hours(24), fromToday.state.viewport.startInstantMs)
        assertEquals(now, fromToday.state.viewport.endInstantMs)
        assertNull(fromToday.effect)

        val yesterday = TimelineInteraction.reduce(
            drag(live, 3.0 / 24.0 * 1_000.0, 1_000.0, now),
            TimelineInteractionEvent.DragEnded(now),
        ).state
        val fromYesterday = TimelineInteraction.reduce(
            yesterday,
            TimelineInteractionEvent.ReturnToNow(now, shanghai),
        )
        assertEquals(TimelineInteractionMode.LiveAttached, fromYesterday.state.mode)
        assertEquals(today, fromYesterday.state.selectedDay)
        assertEquals(now - hours(24), fromYesterday.state.viewport.startInstantMs)
        assertEquals(now, fromYesterday.state.viewport.endInstantMs)
        assertEquals(
            TimelineInteractionEffect.CommitSelectedDay(today),
            fromYesterday.effect,
        )
    }

    @Test
    fun historyCanEnterTodayWithoutAttachingThenAttachOnlyAtNow() {
        val now = instant("2026-08-08T21:00:00+08:00")
        val yesterday = initialize(LocalDate.of(2026, 8, 7), now)
        val halfWidth = drag(yesterday, -500.0, 1_000.0, now)
        val stillYesterday = TimelineInteraction.reduce(
            halfWidth,
            TimelineInteractionEvent.DragEnded(now),
        )

        assertEquals(LocalDate.of(2026, 8, 7), stillYesterday.state.selectedDay)
        assertEquals(TimelineInteractionMode.Browsing, stillYesterday.state.mode)
        assertEquals(halfWidth.viewport, stillYesterday.state.viewport)

        val intoToday = drag(yesterday, -800.0, 1_000.0, now)
        val browsingToday = TimelineInteraction.reduce(
            intoToday,
            TimelineInteractionEvent.DragEnded(now),
        )

        assertEquals(LocalDate.of(2026, 8, 8), browsingToday.state.selectedDay)
        assertEquals(TimelineInteractionMode.Browsing, browsingToday.state.mode)
        assertEquals(intoToday.viewport, browsingToday.state.viewport)
        assertEquals(
            TimelineInteractionEffect.CommitSelectedDay(LocalDate.of(2026, 8, 8)),
            browsingToday.effect,
        )

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
        val dragNow = instant("2026-08-08T21:00:00+08:00")
        val releaseNow = dragNow + Duration.ofSeconds(1).toMillis()
        val yesterday = initialize(LocalDate.of(2026, 8, 7), dragNow)
        val browsingToday = TimelineInteraction.reduce(
            drag(yesterday, -800.0, 1_000.0, dragNow),
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
        val atThreshold = drag(initial, 750.0, 1_000.0, now)
        val kept = TimelineInteraction.reduce(
            atThreshold,
            TimelineInteractionEvent.DragEnded(now),
        )
        assertEquals(LocalDate.of(2026, 8, 7), kept.state.selectedDay)
        assertEquals(atThreshold.viewport, kept.state.viewport)
        assertNull(kept.effect)

        val farther = drag(initial, 1_000.0, 1_000.0, now)
        val result = TimelineInteraction.reduce(
            farther,
            TimelineInteractionEvent.DragEnded(now),
        )

        assertEquals(LocalDate.of(2026, 8, 6), result.state.selectedDay)
        assertEquals(farther.viewport, result.state.viewport)
        assertEquals(
            LocalDate.of(2026, 8, 5).atStartOfDay(shanghai).toInstant().toEpochMilli(),
            result.state.workWindow.startInstantMs,
        )
        assertTrue(result.state.viewport.startInstantMs >= result.state.workWindow.startInstantMs)
        assertTrue(result.state.viewport.endInstantMs <= result.state.workWindow.endInstantMs)
    }

    @Test
    fun dstFallBackDayShareUsesRealElapsedDuration() {
        val now = instant("2026-12-01T12:00:00-05:00")
        val day = LocalDate.of(2026, 11, 1)
        val initial = initialize(day, now, newYork)
        assertEquals(hours(25), initial.viewport.durationMs)
        val covering = LocalDayGrid(
            startMs = day.minusDays(1).atStartOfDay(newYork).toInstant().toEpochMilli(),
            endExclusiveMs = day.plusDays(2).atStartOfDay(newYork).toInstant().toEpochMilli(),
            zoneId = newYork,
        )
        assertEquals(hours(73), covering.durationMs)

        val keepOrigin = drag(initial, -720.0, 1_000.0, now)
        val kept = TimelineInteraction.reduce(
            keepOrigin,
            TimelineInteractionEvent.DragEnded(now),
        )
        assertEquals(day, kept.state.selectedDay)
        assertEquals(keepOrigin.viewport, kept.state.viewport)
        assertNull(kept.effect)

        val nov1Start = day.atStartOfDay(newYork).toInstant().toEpochMilli()
        val nov2Start = day.plusDays(1).atStartOfDay(newYork).toInstant().toEpochMilli()
        assertEquals(hours(25), nov2Start - nov1Start)
        val realOverlap = minOf(keepOrigin.viewport.endInstantMs, nov2Start) -
            maxOf(keepOrigin.viewport.startInstantMs, nov1Start)
        assertTrue(realOverlap.toDouble() / keepOrigin.viewport.durationMs >= STICKY_DAY_SHARE)
        val syntheticOverlap = minOf(keepOrigin.viewport.endInstantMs, nov1Start + hours(24)) -
            maxOf(keepOrigin.viewport.startInstantMs, nov1Start)
        assertTrue(syntheticOverlap.toDouble() / keepOrigin.viewport.durationMs < STICKY_DAY_SHARE)

        val dropOrigin = drag(initial, 760.0, 1_000.0, now)
        val changed = TimelineInteraction.reduce(
            dropOrigin,
            TimelineInteractionEvent.DragEnded(now),
        )
        assertEquals(LocalDate.of(2026, 10, 31), changed.state.selectedDay)
        assertEquals(dropOrigin.viewport, changed.state.viewport)
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
    fun endingAnIncompleteLiveGestureAfterMidnightStaysAtReleasedViewport() {
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

        assertEquals(TimelineInteractionMode.Browsing, ended.state.mode)
        assertEquals(LocalDate.of(2026, 8, 8), ended.state.selectedDay)
        assertEquals(initial.viewport, ended.state.viewport)
        assertNull(ended.effect)
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
        deltaPx: Double,
        widthPx: Double,
        nowMs: Long,
    ): TimelineInteractionState {
        val started = TimelineInteraction.reduce(
            state,
            TimelineInteractionEvent.DragStarted,
        ).state
        return TimelineInteraction.reduce(
            started,
            TimelineInteractionEvent.DragChanged(
                deltaPx = deltaPx,
                widthPx = widthPx,
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
