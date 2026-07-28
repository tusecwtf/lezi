package com.lezi.babylog.feature.log

import com.lezi.babylog.designsystem.TimelineAxis
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeDayTimelineAxisTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun contentWindowCoversDMinus1ThroughDPlus1() {
        val d = LocalDate.of(2026, 7, 22)
        val window = threeDayContentWindow(d, zone)

        assertEquals(d, window.selectedDay)
        assertEquals(TimelineAxis.THREE_DAY_CONTENT_MINUTES, window.contentDurationMinutes)

        val expectedStart = d.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val expectedEnd = d.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expectedStart, window.startMs)
        assertEquals(expectedEnd, window.endMs)
    }

    @Test
    fun changingSelectedDayReanchorsWindow() {
        val d = LocalDate.of(2026, 7, 22)
        val next = d.plusDays(1)
        val a = threeDayContentWindow(d, zone)
        val b = threeDayContentWindow(next, zone)

        // New origin is previous D 00:00 (= old primary-day start).
        assertEquals(
            d.atStartOfDay(zone).toInstant().toEpochMilli(),
            b.startMs,
        )
        assertEquals(a.startMs + 24L * 60L * 60_000L, b.startMs)
        assertEquals(a.endMs + 24L * 60L * 60_000L, b.endMs)
        assertEquals(TimelineAxis.THREE_DAY_CONTENT_MINUTES, b.contentDurationMinutes)
    }

    @Test
    fun defaultViewportIsDPrimaryWithSharedPeekConstant() {
        val start = defaultThreeDayViewportStartMinutes()
        val duration = defaultThreeDayViewportDurationMinutes()

        assertEquals(TimelineAxis.defaultViewportStartMinutes(), start)
        assertEquals(TimelineAxis.defaultViewportDurationMinutes(), duration)
        assertEquals(TimelineAxis.NEIGHBOR_PEEK_MINUTES, TimelineAxis.PRIMARY_DAY_START_MINUTES - start)

        // Primary day D fully inside the viewport.
        val dStart = TimelineAxis.PRIMARY_DAY_START_MINUTES
        val dEnd = dStart + TimelineAxis.MINUTES_PER_DAY
        assertTrue(start <= dStart)
        assertTrue(start + duration >= dEnd)
    }

    @Test
    fun nowContentMinute_nullOutsideWindow_mapsInside() {
        val d = LocalDate.of(2026, 7, 22)
        val window = threeDayContentWindow(d, zone)

        // Before D−1 00:00 and at/after D+1 24:00 → no now line.
        assertNull(nowContentMinuteInWindow(window.startMs - 1, window))
        assertNull(nowContentMinuteInWindow(window.endMs, window))

        // Mid primary day D → content minute relative to window origin.
        val noonOnD = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(
            TimelineAxis.PRIMARY_DAY_START_MINUTES + 12 * 60,
            nowContentMinuteInWindow(noonOnD, window),
        )

        // When D is yesterday relative to "now" on D+1, now is still in window.
        val nowOnDPlus1 = d.plusDays(1).atTime(9, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(
            TimelineAxis.PRIMARY_DAY_END_MINUTES + 9 * 60 + 30,
            nowContentMinuteInWindow(nowOnDPlus1, window),
        )
    }

    @Test
    fun todayViewportCentersOnNowAndClamps() {
        val duration = defaultThreeDayViewportDurationMinutes()
        val noon = TimelineAxis.PRIMARY_DAY_START_MINUTES + 12 * 60
        val start = todayThreeDayViewportStartMinutes(noon, duration)
        assertEquals(noon - duration / 2, start)

        // Early morning near window origin clamps left edge.
        assertEquals(0, todayThreeDayViewportStartMinutes(5, duration))
        // Late on D+1 clamps right edge.
        val late = TimelineAxis.THREE_DAY_CONTENT_MINUTES - 10
        assertEquals(
            TimelineAxis.THREE_DAY_CONTENT_MINUTES - duration,
            todayThreeDayViewportStartMinutes(late, duration),
        )
    }

    @Test
    fun initialViewport_todayCentersNow_nonTodayUsesPeek() {
        val duration = defaultThreeDayViewportDurationMinutes()
        val today = LocalDate.of(2026, 7, 22)
        val noonMs = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

        val todayStart = initialThreeDayViewportStartMinutes(
            selectedDay = today,
            today = today,
            nowMs = noonMs,
            zone = zone,
            viewportDurationMinutes = duration,
        )
        val noonContent = TimelineAxis.PRIMARY_DAY_START_MINUTES + 12 * 60
        assertEquals(noonContent - duration / 2, todayStart)

        val history = today.minusDays(3)
        val historyStart = initialThreeDayViewportStartMinutes(
            selectedDay = history,
            today = today,
            nowMs = noonMs,
            zone = zone,
            viewportDurationMinutes = duration,
        )
        assertEquals(defaultThreeDayViewportStartMinutes(), historyStart)
    }

    @Test
    fun panDoesNotLeave72hAndIsIndependentOfSelectedDay() {
        val duration = defaultThreeDayViewportDurationMinutes()
        val axis = duration.toFloat()
        val mid = defaultThreeDayViewportStartMinutes()

        // Finger right → earlier content.
        assertEquals(
            mid - 60,
            threeDayViewportStartAfterPan(
                currentStartMinutes = mid,
                deltaPx = 60f,
                axisLengthPx = axis,
                viewportDurationMinutes = duration,
            ),
        )
        // Hard clamp at both ends of the 72h window.
        assertEquals(
            0,
            threeDayViewportStartAfterPan(
                currentStartMinutes = 5,
                deltaPx = 50_000f,
                axisLengthPx = axis,
                viewportDurationMinutes = duration,
            ),
        )
        val maxStart = TimelineAxis.THREE_DAY_CONTENT_MINUTES - duration
        assertEquals(
            maxStart,
            threeDayViewportStartAfterPan(
                currentStartMinutes = maxStart - 10,
                deltaPx = -50_000f,
                axisLengthPx = axis,
                viewportDurationMinutes = duration,
            ),
        )
        // Pan only mutates viewport start — selected day D is never an input.
        assertTrue(maxStart + duration <= TimelineAxis.THREE_DAY_CONTENT_MINUTES)
    }
}
