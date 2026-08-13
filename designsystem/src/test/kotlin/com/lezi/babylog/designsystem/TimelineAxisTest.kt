package com.lezi.babylog.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineAxisTest {
    @Test
    fun dynamicWindowGeometryUsesDstMidnightsInsteadOfFixedDays() {
        val springForward = TimelineWindowGeometry(
            contentDurationMinutes = 4_260,
            primaryStartMinutes = 1_440,
            primaryEndExclusiveMinutes = 2_820,
            dayBoundaryMinutes = listOf(1_440, 2_820),
        )

        assertFalse(springForward.isPrimaryMinute(1_439))
        assertTrue(springForward.isPrimaryMinute(1_440))
        assertTrue(springForward.isPrimaryMinute(2_819))
        assertFalse(springForward.isPrimaryMinute(2_820))
        assertEquals(listOf(1_440, 2_820), springForward.dayBoundaryMinutes)
        assertEquals(2_700, springForward.maxViewportStart(viewportDurationMinutes = 1_560))
    }

    @Test(expected = IllegalArgumentException::class)
    fun dynamicWindowGeometryRejectsSyntheticTwentyFourHourBoundary() {
        TimelineWindowGeometry(
            contentDurationMinutes = 4_260,
            primaryStartMinutes = 1_440,
            primaryEndExclusiveMinutes = 2_820,
            dayBoundaryMinutes = listOf(1_440, 2_880),
        )
    }

    @Test
    fun threeDayContentIs72Hours() {
        assertEquals(24 * 60, TimelineAxis.MINUTES_PER_DAY)
        assertEquals(3, TimelineAxis.THREE_DAY_CONTENT_DAYS)
        assertEquals(72 * 60, TimelineAxis.THREE_DAY_CONTENT_MINUTES)
    }

    @Test
    fun defaultViewportIsPrimaryDayWithNeighborPeeks() {
        val start = TimelineAxis.defaultViewportStartMinutes()
        val duration = TimelineAxis.defaultViewportDurationMinutes()

        assertEquals(
            TimelineAxis.PRIMARY_DAY_START_MINUTES - TimelineAxis.NEIGHBOR_PEEK_MINUTES,
            start,
        )
        assertEquals(
            TimelineAxis.MINUTES_PER_DAY + 2 * TimelineAxis.NEIGHBOR_PEEK_MINUTES,
            duration,
        )
        // Left peek reaches into D−1; right edge peeks into D+1.
        assertTrue(start < TimelineAxis.PRIMARY_DAY_START_MINUTES)
        assertTrue(start + duration > TimelineAxis.PRIMARY_DAY_START_MINUTES + TimelineAxis.MINUTES_PER_DAY)
        // Fully contained in the 72h content axis.
        assertTrue(start >= 0)
        assertTrue(start + duration <= TimelineAxis.THREE_DAY_CONTENT_MINUTES)
    }

    @Test
    fun clampViewportStartDoesNotExposeOutsideContent() {
        val duration = TimelineAxis.DEFAULT_VIEWPORT_MINUTES
        assertEquals(0, TimelineAxis.clampViewportStart(-100, duration))
        assertEquals(
            TimelineAxis.THREE_DAY_CONTENT_MINUTES - duration,
            TimelineAxis.clampViewportStart(10_000, duration),
        )
        assertEquals(200, TimelineAxis.clampViewportStart(200, duration))
    }

    @Test
    fun contentMinuteMapsThroughViewportToAxisPixels() {
        val viewportStart = TimelineAxis.defaultViewportStartMinutes()
        val duration = TimelineAxis.defaultViewportDurationMinutes()
        val axis = 1620f // 1px per content minute of the default viewport

        val primaryMidnightPx = TimelineAxis.contentMinuteToAxisPx(
            contentMinute = TimelineAxis.PRIMARY_DAY_START_MINUTES,
            axisLengthPx = axis,
            viewportStartMinutes = viewportStart,
            viewportDurationMinutes = duration,
        )
        assertEquals(TimelineAxis.NEIGHBOR_PEEK_MINUTES.toFloat(), primaryMidnightPx, 0.001f)

        val roundTrip = TimelineAxis.axisPxToContentMinute(
            axisPx = primaryMidnightPx,
            axisLengthPx = axis,
            viewportStartMinutes = viewportStart,
            viewportDurationMinutes = duration,
        )
        assertEquals(TimelineAxis.PRIMARY_DAY_START_MINUTES, roundTrip)
    }

    @Test
    fun primaryDayRangeCoversDOnly() {
        val d0 = TimelineAxis.PRIMARY_DAY_START_MINUTES
        val dEnd = TimelineAxis.PRIMARY_DAY_END_MINUTES
        assertFalse(TimelineAxis.isPrimaryDayContentMinute(d0 - 1))
        assertTrue(TimelineAxis.isPrimaryDayContentMinute(d0))
        assertTrue(TimelineAxis.isPrimaryDayContentMinute(d0 + 12 * 60))
        assertFalse(TimelineAxis.isPrimaryDayContentMinute(dEnd))
        assertFalse(TimelineAxis.isPrimaryDayContentMinute(dEnd + 30))
    }

    @Test
    fun dayBoundaryMinutesAreInteriorMidnights() {
        assertEquals(
            listOf(
                TimelineAxis.PRIMARY_DAY_START_MINUTES,
                TimelineAxis.PRIMARY_DAY_END_MINUTES,
            ),
            TimelineAxis.dayBoundaryContentMinutes(),
        )
        // Single-day content has no interior boundary.
        assertTrue(TimelineAxis.dayBoundaryContentMinutes(contentDurationMinutes = 1440).isEmpty())
    }

    @Test
    fun sleepPaintSlicesSplitAtDayBounds() {
        val d0 = TimelineAxis.PRIMARY_DAY_START_MINUTES
        // Overnight sleep: D−1 22:00 → D 06:00
        val slices = sleepPaintSlices(d0 - 120, d0 + 360)
        assertEquals(2, slices.size)
        assertEquals(d0 - 120, slices[0].startMin)
        assertEquals(d0, slices[0].endMin)
        assertEquals(d0, slices[1].startMin)
        assertEquals(d0 + 360, slices[1].endMin)
        assertFalse(TimelineAxis.isPrimaryDayContentMinute(slices[0].startMin))
        assertTrue(TimelineAxis.isPrimaryDayContentMinute(slices[1].startMin))
    }

    @Test
    fun sleepPaintSlicesUseDynamicDstBoundaries() {
        val slices = sleepPaintSlices(
            visStart = 2_760,
            visEnd = 2_900,
            dayBoundaryMinutes = listOf(1_440, 2_820),
        )

        assertEquals(
            listOf(
                SleepPaintSlice(startMin = 2_760, endMin = 2_820),
                SleepPaintSlice(startMin = 2_820, endMin = 2_900),
            ),
            slices,
        )
    }

    @Test
    fun contentMinuteIfInWindow_nullOutsideAndMapsInside() {
        val windowStart = 1_000_000_000_000L
        val windowEnd = windowStart + TimelineAxis.THREE_DAY_CONTENT_MINUTES * 60_000L

        assertNull(
            TimelineAxis.contentMinuteIfInWindow(
                nowMs = windowStart - 1,
                windowStartMs = windowStart,
                windowEndMs = windowEnd,
            ),
        )
        assertNull(
            TimelineAxis.contentMinuteIfInWindow(
                nowMs = windowEnd,
                windowStartMs = windowStart,
                windowEndMs = windowEnd,
            ),
        )
        assertEquals(
            0,
            TimelineAxis.contentMinuteIfInWindow(
                nowMs = windowStart,
                windowStartMs = windowStart,
                windowEndMs = windowEnd,
            ),
        )
        // 36h into the window → primary-day noon.
        val atPrimaryNoon = windowStart + (TimelineAxis.PRIMARY_DAY_START_MINUTES + 12 * 60) * 60_000L
        assertEquals(
            TimelineAxis.PRIMARY_DAY_START_MINUTES + 12 * 60,
            TimelineAxis.contentMinuteIfInWindow(
                nowMs = atPrimaryNoon,
                windowStartMs = windowStart,
                windowEndMs = windowEnd,
            ),
        )
    }

    @Test
    fun panViewportStartMovesOppositeFingerAndClamps() {
        val duration = TimelineAxis.DEFAULT_VIEWPORT_MINUTES
        val axis = duration.toFloat() // 1px per content minute
        val mid = TimelineAxis.defaultViewportStartMinutes()

        // Finger right (+px) reveals earlier content → start decreases.
        assertEquals(
            mid - 30,
            TimelineAxis.panViewportStart(
                currentStartMinutes = mid,
                deltaPx = 30f,
                axisLengthPx = axis,
                viewportDurationMinutes = duration,
            ),
        )
        // Finger left reveals later content → start increases.
        assertEquals(
            mid + 45,
            TimelineAxis.panViewportStart(
                currentStartMinutes = mid,
                deltaPx = -45f,
                axisLengthPx = axis,
                viewportDurationMinutes = duration,
            ),
        )

        // Cannot pan past content origin.
        assertEquals(
            0,
            TimelineAxis.panViewportStart(
                currentStartMinutes = 10,
                deltaPx = 10_000f,
                axisLengthPx = axis,
                viewportDurationMinutes = duration,
            ),
        )
        // Cannot pan past content end (viewport fully inside 72h).
        val maxStart = TimelineAxis.THREE_DAY_CONTENT_MINUTES - duration
        assertEquals(
            maxStart,
            TimelineAxis.panViewportStart(
                currentStartMinutes = maxStart - 5,
                deltaPx = -10_000f,
                axisLengthPx = axis,
                viewportDurationMinutes = duration,
            ),
        )
        assertTrue(
            TimelineAxis.panViewportStart(
                currentStartMinutes = mid,
                deltaPx = -10_000f,
                axisLengthPx = axis,
                viewportDurationMinutes = duration,
            ) + duration <= TimelineAxis.THREE_DAY_CONTENT_MINUTES,
        )
    }
}
