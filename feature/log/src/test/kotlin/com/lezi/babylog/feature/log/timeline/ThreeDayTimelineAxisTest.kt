package com.lezi.babylog.feature.log.timeline
import com.lezi.babylog.designsystem.TimelineAxis
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

class ThreeDayTimelineAxisTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun contentWindowCoversDMinus1ThroughDPlus1() {
        val day = LocalDate.of(2026, 7, 22)
        val axis = ThreeDayTimelineAxis(day, zone)

        assertEquals(day, axis.selectedDay)
        assertEquals(TimelineAxis.THREE_DAY_CONTENT_MINUTES, axis.contentDurationMinutes)
        assertEquals(
            day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            axis.windowStartMs,
        )
        assertEquals(
            day.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli(),
            axis.windowEndExclusiveMs,
        )
    }

    @Test
    fun changingSelectedDayReanchorsAllFourMidnights() {
        val day = LocalDate.of(2026, 7, 22)
        val next = day.plusDays(1)
        val first = ThreeDayTimelineAxis(day, zone)
        val second = ThreeDayTimelineAxis(next, zone)

        assertEquals(first.primaryStartMs, second.windowStartMs)
        assertEquals(first.windowStartMs + 24L * 60L * 60_000L, second.windowStartMs)
        assertEquals(first.windowEndExclusiveMs + 24L * 60L * 60_000L, second.windowEndExclusiveMs)
        assertEquals(TimelineAxis.THREE_DAY_CONTENT_MINUTES, second.contentDurationMinutes)
    }

    @Test
    fun defaultViewportIsPrimaryDayWithSharedNeighborPeeks() {
        val axis = ThreeDayTimelineAxis(LocalDate.of(2026, 7, 22), zone)
        val start = axis.defaultViewportStartMinutes
        val duration = axis.defaultViewportDurationMinutes

        assertEquals(TimelineAxis.NEIGHBOR_PEEK_MINUTES, axis.primaryStartMinutes - start)
        assertTrue(start <= axis.primaryStartMinutes)
        assertTrue(start + duration >= axis.primaryEndExclusiveMinutes)
    }

    @Test
    fun nowContentMinuteIsHalfOpenAndUsesAxisOrigin() {
        val day = LocalDate.of(2026, 7, 22)
        val axis = ThreeDayTimelineAxis(day, zone)

        assertNull(axis.instantToContentMinute(axis.windowStartMs - 1L))
        assertNull(axis.instantToContentMinute(axis.windowEndExclusiveMs))

        val noonOnD = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(axis.primaryStartMinutes + 12 * 60, axis.instantToContentMinute(noonOnD))

        val nowOnDPlus1 = day.plusDays(1).atTime(9, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(
            axis.primaryEndExclusiveMinutes + 9 * 60 + 30,
            axis.instantToContentMinute(nowOnDPlus1),
        )
    }

    @Test
    fun centeredViewportGeometryClampsToDynamicWindow() {
        val axis = ThreeDayTimelineAxis(LocalDate.of(2026, 7, 22), zone)
        val duration = axis.defaultViewportDurationMinutes
        val noon = axis.primaryStartMinutes + 12 * 60

        assertEquals(noon - duration / 2, axis.centeredViewportStartMinutes(noon, duration))
        assertEquals(0, axis.centeredViewportStartMinutes(5, duration))
        assertEquals(
            axis.contentDurationMinutes - duration,
            axis.centeredViewportStartMinutes(axis.contentDurationMinutes - 10, duration),
        )
    }

    @Test
    fun panClampsToAxisWithoutDependingOnSelectedDayMutation() {
        val axis = ThreeDayTimelineAxis(LocalDate.of(2026, 7, 22), zone)
        val duration = axis.defaultViewportDurationMinutes
        val width = duration.toFloat()
        val mid = axis.defaultViewportStartMinutes

        assertEquals(mid - 60, axis.panViewportStartMinutes(mid, 60f, width, duration))
        assertEquals(0, axis.panViewportStartMinutes(5, 50_000f, width, duration))
        val maxStart = axis.contentDurationMinutes - duration
        assertEquals(
            maxStart,
            axis.panViewportStartMinutes(maxStart - 10, -50_000f, width, duration),
        )
        assertTrue(maxStart + duration <= axis.contentDurationMinutes)
    }
}
