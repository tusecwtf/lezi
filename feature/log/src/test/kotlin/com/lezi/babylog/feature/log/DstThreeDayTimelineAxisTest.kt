package com.lezi.babylog.feature.log

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DstThreeDayTimelineAxisTest {
    private val newYork = ZoneId.of("America/New_York")
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun springTransitionWindowUsesFourMidnightsAnd4260Minutes() {
        val axis = ThreeDayTimelineAxis(LocalDate.of(2026, 3, 8), newYork)

        assertEquals(4, axis.localMidnightEpochMillis.size)
        assertEquals(4_260, axis.contentDurationMinutes)
        assertEquals(
            listOf(0L, minutes(1_440), minutes(2_820), minutes(4_260)),
            axis.localMidnightOffsetsMs,
        )
        assertEquals(
            TimelineAxisOffsetRange(minutes(1_440), minutes(2_820)),
            axis.primaryRange,
        )
        assertEquals(minutes(1_560), axis.defaultViewportDurationMs)
        assertEquals(minutes(1_350), axis.defaultViewportStartMs)
    }

    @Test
    fun fallTransitionWindowUsesFourMidnightsAnd4380Minutes() {
        val axis = ThreeDayTimelineAxis(LocalDate.of(2026, 11, 1), newYork)

        assertEquals(4_380, axis.contentDurationMinutes)
        assertEquals(
            listOf(0L, minutes(1_440), minutes(2_940), minutes(4_380)),
            axis.localMidnightOffsetsMs,
        )
        assertEquals(
            TimelineAxisOffsetRange(minutes(1_440), minutes(2_940)),
            axis.primaryRange,
        )
        assertEquals(minutes(1_680), axis.defaultViewportDurationMs)
        assertEquals(minutes(1_350), axis.defaultViewportStartMs)
    }

    @Test
    fun shanghaiWindowRemains4320Minutes() {
        val axis = ThreeDayTimelineAxis(LocalDate.of(2026, 3, 8), shanghai)

        assertEquals(4_320, axis.contentDurationMinutes)
        assertEquals(
            listOf(0L, minutes(1_440), minutes(2_880), minutes(4_320)),
            axis.localMidnightOffsetsMs,
        )
        assertEquals(minutes(1_620), axis.defaultViewportDurationMs)
    }

    @Test
    fun transitionInPreviousDayMovesPrimaryStartWithoutChangingPrimaryDayLength() {
        val spring = ThreeDayTimelineAxis(LocalDate.of(2026, 3, 9), newYork)
        val fall = ThreeDayTimelineAxis(LocalDate.of(2026, 11, 2), newYork)

        assertEquals(minutes(1_380), spring.primaryRange.startOffsetMs)
        assertEquals(minutes(1_440), spring.primaryRange.durationMs)
        assertEquals(minutes(1_500), fall.primaryRange.startOffsetMs)
        assertEquals(minutes(1_440), fall.primaryRange.durationMs)
    }

    @Test
    fun instantAndOffsetRoundTripIsExactAndHalfOpen() {
        val axis = ThreeDayTimelineAxis(LocalDate.of(2026, 3, 8), newYork)
        val representativeInstants = axis.localMidnightEpochMillis.dropLast(1) + listOf(
            localInstantMs(2026, 3, 8, 1, 30, ZoneOffset.ofHours(-5)),
            localInstantMs(2026, 3, 8, 3, 30, ZoneOffset.ofHours(-4)),
        )

        representativeInstants.forEach { instantMs ->
            val offsetMs = requireNotNull(axis.instantToOffsetMs(instantMs))
            assertEquals(instantMs, axis.offsetMsToInstant(offsetMs))
        }
        assertNull(axis.instantToOffsetMs(axis.windowStartMs - 1L))
        assertNull(axis.instantToOffsetMs(axis.windowEndExclusiveMs))
        assertNull(axis.offsetMsToInstant(-1L))
        assertNull(axis.offsetMsToInstant(axis.contentDurationMs))
    }

    @Test
    fun springGapHasNo0230AxisPosition() {
        val axis = ThreeDayTimelineAxis(LocalDate.of(2026, 3, 8), newYork)
        val missing = LocalDateTime.of(2026, 3, 8, 2, 30)

        assertTrue(axis.localDateTimeToOffsetsMs(missing).isEmpty())
        assertFalse(
            axis.hourTicks(stepHours = 1).any {
                it.localDateTime == LocalDateTime.of(2026, 3, 8, 2, 0)
            },
        )
    }

    @Test
    fun fallOverlapMapsBoth0130InstantsAndDisambiguatesHourLabels() {
        val axis = ThreeDayTimelineAxis(LocalDate.of(2026, 11, 1), newYork)
        val repeated = LocalDateTime.of(2026, 11, 1, 1, 30)
        val offsets = axis.localDateTimeToOffsetsMs(repeated)

        assertEquals(2, offsets.size)
        assertEquals(minutes(60), offsets[1] - offsets[0])
        offsets.forEach { offsetMs ->
            val instantMs = requireNotNull(axis.offsetMsToInstant(offsetMs))
            assertEquals(offsetMs, axis.instantToOffsetMs(instantMs))
        }

        val repeatedLabels = axis.hourTicks(stepHours = 1)
            .filter { it.localDateTime == LocalDateTime.of(2026, 11, 1, 1, 0) }
            .map { it.label }
        assertEquals(listOf("01:00 -04:00", "01:00 -05:00"), repeatedLabels)
    }

    @Test
    fun crossDstSleepIntervalsUseElapsedTimeNotLocalClockSubtraction() {
        val springAxis = ThreeDayTimelineAxis(LocalDate.of(2026, 3, 8), newYork)
        val springSleep = requireNotNull(
            springAxis.clipIntervalToOffsets(
                startInstantMs = localInstantMs(2026, 3, 8, 1, 30, ZoneOffset.ofHours(-5)),
                endExclusiveInstantMs = localInstantMs(2026, 3, 8, 3, 30, ZoneOffset.ofHours(-4)),
            ),
        )
        assertEquals(minutes(60), springSleep.durationMs)

        val fallAxis = ThreeDayTimelineAxis(LocalDate.of(2026, 11, 1), newYork)
        val fallSleep = requireNotNull(
            fallAxis.clipIntervalToOffsets(
                startInstantMs = localInstantMs(2026, 11, 1, 1, 30, ZoneOffset.ofHours(-4)),
                endExclusiveInstantMs = localInstantMs(2026, 11, 1, 1, 30, ZoneOffset.ofHours(-5)),
            ),
        )
        assertEquals(minutes(60), fallSleep.durationMs)
    }

    @Test
    fun intervalClippingCanRepresentTheExclusiveWindowEnd() {
        val axis = ThreeDayTimelineAxis(LocalDate.of(2026, 3, 8), newYork)
        val clipped = requireNotNull(
            axis.clipIntervalToOffsets(
                startInstantMs = axis.windowStartMs - minutes(30),
                endExclusiveInstantMs = axis.windowEndExclusiveMs + minutes(30),
            ),
        )

        assertEquals(axis.contentRange, clipped)
        assertEquals(axis.contentDurationMs, clipped.endExclusiveOffsetMs)
    }

    @Test
    fun centerClampAndPanUseTheDynamicContentDuration() {
        val axis = ThreeDayTimelineAxis(LocalDate.of(2026, 3, 8), newYork)
        val viewport = axis.defaultViewportDurationMs

        assertEquals(0L, axis.centeredViewportStartMs(1L, viewport))
        assertEquals(
            axis.contentDurationMs - viewport,
            axis.centeredViewportStartMs(axis.contentDurationMs - 1L, viewport),
        )
        assertEquals(
            0L,
            axis.panViewportStartMs(
                currentStartMs = minutes(5),
                totalDeltaPx = 50_000.0,
                axisLengthPx = 1_000.0,
                viewportDurationMs = viewport,
            ),
        )
        assertEquals(
            axis.contentDurationMs - viewport,
            axis.panViewportStartMs(
                currentStartMs = axis.contentDurationMs - viewport - minutes(5),
                totalDeltaPx = -50_000.0,
                axisLengthPx = 1_000.0,
                viewportDurationMs = viewport,
            ),
        )
        assertEquals(
            axis.defaultViewportStartMs - viewport / 10L,
            axis.panViewportStartMs(
                currentStartMs = axis.defaultViewportStartMs,
                totalDeltaPx = 100.0,
                axisLengthPx = 1_000.0,
                viewportDurationMs = viewport,
            ),
        )
    }

    private fun minutes(value: Long): Long = value * 60_000L

    private fun localInstantMs(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        offset: ZoneOffset,
    ): Long = LocalDateTime.of(year, month, day, hour, minute)
        .toInstant(offset)
        .toEpochMilli()
}
