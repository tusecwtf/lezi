package com.lezi.babylog.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineHourLabelLayoutTest {
    @Test
    fun compactElderHourLabelDropsMinutesAndKeepsDstOffset() {
        assertEquals("00", compactElderHourLabel("00:00"))
        assertEquals("06", compactElderHourLabel("06:00"))
        assertEquals("00", compactElderHourLabel("00"))
        assertEquals("02 -04:00", compactElderHourLabel("02:00 -04:00"))
    }

    @Test
    fun wideClockLabelsSkipNeighborsInsteadOfOverlapping() {
        val labels = sixHourClockCandidates(widthPx = 60)
        val placed = placeTimelineHourLabels(
            labels = labels,
            trackWidthPx = 246,
            viewportStartMs = 0L,
            viewportDurationMs = DAY_MS,
            minGapPx = ElderHourLabelMinGapPx,
        )

        assertTrue(placed.size >= 2)
        assertTrue(
            placed.zipWithNext().all { (left, right) ->
                left.xPx + left.widthPx + ElderHourLabelMinGapPx <= right.xPx
            },
        )
        assertEquals("00:00", placed.first().label)
    }

    @Test
    fun eightPxMinGapSkipsNeighborOnlyFivePxAway() {
        val labels = listOf(
            TimelineHourLabelCandidate(0, instantMs = 0L, label = "00", widthPx = 40),
            TimelineHourLabelCandidate(1, instantMs = 405L * MINUTE_MS, label = "07", widthPx = 40),
        )
        val placed = placeTimelineHourLabels(
            labels = labels,
            trackWidthPx = 200,
            viewportStartMs = 0L,
            viewportDurationMs = DAY_MS,
            minGapPx = ElderHourLabelMinGapPx,
        )

        assertEquals(listOf(0L), placed.map { it.instantMs })
    }

    @Test
    fun compactElderWidthsKeepAllSixHourTicksOnNarrowTrack() {
        val labels = listOf(0, 6, 12, 18, 24).mapIndexed { index, hour ->
            val clock = (hour % 24).toString().padStart(2, '0') + ":00"
            TimelineHourLabelCandidate(
                index = index,
                instantMs = hour * HOUR_MS,
                label = compactElderHourLabel(clock),
                widthPx = 26,
            )
        }
        val placed = placeTimelineHourLabels(
            labels = labels,
            trackWidthPx = 246,
            viewportStartMs = 0L,
            viewportDurationMs = DAY_MS,
            minGapPx = ElderHourLabelMinGapPx,
        )

        assertEquals(
            listOf(0L, 6L, 12L, 18L, 24L).map { it * HOUR_MS },
            placed.map { it.instantMs },
        )
    }

    @Test
    fun compactSixHourTicksKeepMinGapOnPhoneAndLandscapeTracks() {
        val tracks = listOf(80, 160, 200, 246, 280, 320, 360, 412, 480, 640, 800)
        tracks.forEach { track ->
            val placed = placeTimelineHourLabels(
                labels = sixHourClockCandidates(widthPx = 26),
                trackWidthPx = track,
                viewportStartMs = 0L,
                viewportDurationMs = DAY_MS,
                minGapPx = ElderHourLabelMinGapPx,
            )
            assertTrue("track $track should keep at least one tick", placed.isNotEmpty())
            assertTrue(
                "track $track min gap",
                placed.zipWithNext().all { (left, right) ->
                    left.xPx + left.widthPx + ElderHourLabelMinGapPx <= right.xPx
                },
            )
            if (track >= 246) {
                assertEquals(
                    "track $track should keep all six-hour ticks",
                    listOf(0L, 6L, 12L, 18L, 24L).map { it * HOUR_MS },
                    placed.map { it.instantMs },
                )
            }
        }
    }

    @Test
    fun wideDstTicksNeverOverlapAcrossTrackWidths() {
        val tracks = listOf(80, 160, 246, 320, 412, 640)
        tracks.forEach { track ->
            val placed = placeTimelineHourLabels(
                labels = sixHourClockCandidates(widthPx = 72),
                trackWidthPx = track,
                viewportStartMs = 0L,
                viewportDurationMs = DAY_MS,
                minGapPx = ElderHourLabelMinGapPx,
            )
            assertTrue("DST track $track should keep at least one tick", placed.isNotEmpty())
            assertTrue(
                "DST track $track min gap",
                placed.zipWithNext().all { (left, right) ->
                    left.xPx + left.widthPx + ElderHourLabelMinGapPx <= right.xPx
                },
            )
        }
    }

    @Test
    fun edgeClampDoesNotStackTwoLabelsAtZero() {
        val labels = listOf(
            TimelineHourLabelCandidate(0, instantMs = 0L, label = "00:00", widthPx = 80),
            TimelineHourLabelCandidate(1, instantMs = HOUR_MS, label = "01:00", widthPx = 80),
        )
        val placed = placeTimelineHourLabels(
            labels = labels,
            trackWidthPx = 100,
            viewportStartMs = 0L,
            viewportDurationMs = 6L * HOUR_MS,
            minGapPx = 0,
        )

        assertEquals(1, placed.size)
        assertEquals(0L, placed.single().instantMs)
    }

    @Test
    fun overlappingHourTickHidesInsteadOfDayLabel() {
        val midnight = 12L * HOUR_MS
        val layout = placeTimelineAxisLabels(
            dayLabels = listOf(
                TimelineHourLabelCandidate(0, instantMs = midnight, label = "8/8", widthPx = 36),
            ),
            hourLabels = listOf(
                TimelineHourLabelCandidate(0, instantMs = midnight, label = "00:00", widthPx = 40),
                TimelineHourLabelCandidate(1, instantMs = 18L * HOUR_MS, label = "18:00", widthPx = 40),
            ),
            trackWidthPx = 360,
            viewportStartMs = 0L,
            viewportDurationMs = DAY_MS,
            minGapPx = ElderHourLabelMinGapPx,
        )

        assertEquals(listOf("8/8"), layout.dayLabels.map { it.label })
        assertEquals(listOf(midnight), layout.dayLabels.map { it.instantMs })
        assertEquals(listOf("18:00"), layout.hourLabels.map { it.label })
        assertTrue(layout.hourLabels.none { it.instantMs == midnight })
    }

    @Test
    fun dayLabelWinsWhenAWiderEarlierHourTickWouldCoverIt() {
        val midnight = 12L * HOUR_MS
        val layout = placeTimelineAxisLabels(
            dayLabels = listOf(
                TimelineHourLabelCandidate(0, instantMs = midnight, label = "9/11", widthPx = 40),
            ),
            hourLabels = listOf(
                TimelineHourLabelCandidate(0, instantMs = 11L * HOUR_MS, label = "11:00", widthPx = 80),
            ),
            trackWidthPx = 240,
            viewportStartMs = 0L,
            viewportDurationMs = DAY_MS,
            minGapPx = ElderHourLabelMinGapPx,
        )

        assertEquals(listOf("9/11"), layout.dayLabels.map { it.label })
        assertTrue(layout.hourLabels.isEmpty())
        assertTrue(
            layout.dayLabels.zip(layout.hourLabels).all { (day, hour) ->
                day.xPx + day.widthPx + ElderHourLabelMinGapPx <= hour.xPx ||
                    hour.xPx + hour.widthPx + ElderHourLabelMinGapPx <= day.xPx
            },
        )
    }

    private companion object {
        const val ElderHourLabelMinGapPx = 8
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60L * MINUTE_MS
        const val DAY_MS = 24L * HOUR_MS
    }

    private fun sixHourClockCandidates(widthPx: Int): List<TimelineHourLabelCandidate> =
        listOf(0, 6, 12, 18, 24).mapIndexed { index, hour ->
            TimelineHourLabelCandidate(
                index = index,
                instantMs = hour * HOUR_MS,
                label = (hour % 24).toString().padStart(2, '0') + ":00",
                widthPx = widthPx,
            )
        }
}
