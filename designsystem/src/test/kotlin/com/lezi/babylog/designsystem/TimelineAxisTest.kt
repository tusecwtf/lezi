package com.lezi.babylog.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineAxisTest {
    @Test
    fun instantMapsThroughViewportToAxisPixels() {
        val viewportStart = 1_000_000_000_000L
        val duration = 1_620L * MINUTE_MS
        val axis = 1_620f
        val peek = 90L * MINUTE_MS
        val primaryMidnight = viewportStart + peek

        val primaryMidnightPx = TimelineAxis.instantToAxisPx(
            instantMs = primaryMidnight,
            axisLengthPx = axis,
            viewportStartMs = viewportStart,
            viewportDurationMs = duration,
        )
        assertEquals(90f, primaryMidnightPx, 0.001f)

        val roundTrip = TimelineAxis.axisPxToInstant(
            axisPx = primaryMidnightPx,
            axisLengthPx = axis,
            viewportStartMs = viewportStart,
            viewportDurationMs = duration,
        )
        assertEquals(primaryMidnight, roundTrip)
    }

    @Test
    fun emptyPrimaryRangeDimsNobody() {
        val geometry = TimelineWindowGeometry(
            dayBoundariesMs = listOf(1_440L * MINUTE_MS),
        )
        assertTrue(geometry.isPrimaryInstant(0L))
        assertTrue(geometry.isPrimaryInstant(2_000L * MINUTE_MS))
    }

    @Test
    fun primaryRangeIsHalfOpenOnTheSelectedDay() {
        val start = 1_440L * MINUTE_MS
        val end = 2_820L * MINUTE_MS
        val geometry = TimelineWindowGeometry(
            dayBoundariesMs = listOf(start, end),
            primaryRangeMs = start until end,
        )
        assertFalse(geometry.isPrimaryInstant(start - 1))
        assertTrue(geometry.isPrimaryInstant(start))
        assertTrue(geometry.isPrimaryInstant(end - 1))
        assertFalse(geometry.isPrimaryInstant(end))
    }

    @Test
    fun unsortedDayBoundariesDoNotThrow() {
        val geometry = TimelineWindowGeometry(
            dayBoundariesMs = listOf(2_000L, 1_000L, 1_000L),
        )
        assertEquals(listOf(2_000L, 1_000L, 1_000L), geometry.dayBoundariesMs)
    }

    @Test
    fun sleepPaintSlicesSplitAtDayBounds() {
        val midnight = 1_440L * MINUTE_MS
        val slices = sleepPaintSlices(
            visStart = midnight - 120L * MINUTE_MS,
            visEnd = midnight + 360L * MINUTE_MS,
            dayBoundariesMs = listOf(midnight),
        )
        assertEquals(2, slices.size)
        assertEquals(midnight - 120L * MINUTE_MS, slices[0].startMs)
        assertEquals(midnight, slices[0].endMs)
        assertEquals(midnight, slices[1].startMs)
        assertEquals(midnight + 360L * MINUTE_MS, slices[1].endMs)
    }

    @Test
    fun sleepPaintSlicesUseAbsoluteDstBoundaries() {
        val slices = sleepPaintSlices(
            visStart = 2_760L * MINUTE_MS,
            visEnd = 2_900L * MINUTE_MS,
            dayBoundariesMs = listOf(1_440L * MINUTE_MS, 2_820L * MINUTE_MS),
        )
        assertEquals(
            listOf(
                SleepPaintSlice(startMs = 2_760L * MINUTE_MS, endMs = 2_820L * MINUTE_MS),
                SleepPaintSlice(startMs = 2_820L * MINUTE_MS, endMs = 2_900L * MINUTE_MS),
            ),
            slices,
        )
    }

    @Test
    fun sleepPaintSlicesSplitAtUncertainCutoff() {
        val slices = sleepPaintSlices(
            visStart = 1_000L * MINUTE_MS,
            visEnd = 1_400L * MINUTE_MS,
            dayBoundariesMs = emptyList(),
            uncertainFromMs = 1_200L * MINUTE_MS,
        )
        assertEquals(
            listOf(
                SleepPaintSlice(
                    startMs = 1_000L * MINUTE_MS,
                    endMs = 1_200L * MINUTE_MS,
                    uncertain = false,
                ),
                SleepPaintSlice(
                    startMs = 1_200L * MINUTE_MS,
                    endMs = 1_400L * MINUTE_MS,
                    uncertain = true,
                ),
            ),
            slices,
        )
    }

    @Test
    fun liveFollowTickDoesNotCarryASettleJump() {
        val now = 1_700_000_000_000L
        assertEquals(0f, settleViewportJumpMs(now, now + MINUTE_MS), 0f)
        assertEquals(0f, settleViewportJumpMs(now, now + LIVE_FOLLOW_SNAP_MS), 0f)
    }

    @Test
    fun returnToNowJumpCarriesTheDrawnTargetGap() {
        val browsingStart = 1_700_000_000_000L
        val liveStart = browsingStart + 6L * 60 * MINUTE_MS
        assertEquals(
            (browsingStart - liveStart).toFloat(),
            settleViewportJumpMs(browsingStart, liveStart),
            0f,
        )
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
