package com.lezi.babylog.feature.log.timeline

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.DayChartCategory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineExperienceProjectionTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun absoluteViewportProjectsWithoutRebasingOntoAWorkAxis() {
        val day = LocalDate.of(2026, 8, 8)
        val nowMs = Instant.parse("2026-08-08T00:00:00Z").toEpochMilli()
        val state = TimelineInteraction.reduce(
            null,
            TimelineInteractionEvent.Initialize(day, 7L, nowMs, shanghai),
        ).state

        val frame = state.toTimelineRailFrame(nowMs)

        assertEquals(state.viewport.startInstantMs, frame.viewportStartMs)
        assertEquals(state.viewport.durationMs, frame.viewportDurationMs)
        assertEquals(nowMs, frame.nowMs)
        assertEquals(
            day.atStartOfDay(shanghai).toInstant().toEpochMilli() until
                day.plusDays(1).atStartOfDay(shanghai).toInstant().toEpochMilli(),
            frame.primaryRangeMs,
        )
        val todayStart = day.atStartOfDay(shanghai).toInstant().toEpochMilli()
        assertEquals("8/8", frame.dayBoundaryLabels.single { it.first == todayStart }.second)
        assertTrue(frame.nowMs !in frame.dayBoundaryLabels.map { it.first })
    }

    @Test
    fun changingSelectedDayKeepsTheSameViewportInstants() {
        val day = LocalDate.of(2026, 8, 7)
        val nowMs = Instant.parse("2026-08-08T10:00:00Z").toEpochMilli()
        val state = TimelineInteraction.reduce(
            null,
            TimelineInteractionEvent.Initialize(day, 7L, nowMs, shanghai),
        ).state
        val before = state.toTimelineRailFrame(nowMs)
        val after = state.copy(selectedDay = day.minusDays(1)).toTimelineRailFrame(nowMs)

        assertEquals(before.viewportStartMs, after.viewportStartMs)
        assertEquals(before.viewportDurationMs, after.viewportDurationMs)
        assertEquals(before.nowMs, after.nowMs)
        assertEquals(before.dayBoundaryLabels, after.dayBoundaryLabels)
        assertNotEquals(before.primaryRangeMs, after.primaryRangeMs)
    }

    @Test
    fun railFrameFormatsDayBoundaryLabelsForTheDayThatStartsAtMidnight() {
        val day = LocalDate.of(2026, 9, 11)
        val nowMs = Instant.parse("2026-09-13T12:00:00Z").toEpochMilli()
        val state = TimelineInteraction.reduce(
            null,
            TimelineInteractionEvent.Initialize(day, 7L, nowMs, shanghai),
        ).state

        val frame = state.toTimelineRailFrame(nowMs)
        val start = day.atStartOfDay(shanghai).toInstant().toEpochMilli()
        val next = day.plusDays(1).atStartOfDay(shanghai).toInstant().toEpochMilli()

        assertEquals(
            frame.dayBoundariesMs,
            frame.dayBoundaryLabels.map { it.first },
        )
        assertEquals("9/11", frame.dayBoundaryLabels.single { it.first == start }.second)
        assertEquals("9/12", frame.dayBoundaryLabels.single { it.first == next }.second)
        assertTrue(frame.nowMs == null || frame.nowMs !in frame.dayBoundaryLabels.map { it.first })
    }

    @Test
    fun historicalDstViewportUsesNaturalDayDurationAndHidesNowOutsideViewport() {
        val zone = ZoneId.of("America/New_York")
        val selectedDay = LocalDate.of(2026, 3, 8)
        val nowMs = Instant.parse("2026-03-10T12:00:00Z").toEpochMilli()
        val state = TimelineInteraction.reduce(
            null,
            TimelineInteractionEvent.Initialize(selectedDay, 7L, nowMs, zone),
        ).state

        val frame = state.toTimelineRailFrame(nowMs)

        assertEquals(23L * 60 * 60 * 1000, frame.viewportDurationMs)
        assertEquals(null, frame.nowMs)
    }

    @Test
    fun selectedCategoryRemainsVisibleAndClearableOnAnEmptyTargetDay() {
        val records = listOf(record(1L, RecordType.POOP))

        assertEquals(
            listOf(DayChartCategory.PEE, DayChartCategory.POOP),
            timelineLegendCategories(records, DayChartCategory.PEE),
        )
        assertEquals(
            setOf(RecordType.PEE, RecordType.POOP),
            timelineSelectableSummaryTypes(records, DayChartCategory.PEE),
        )
        assertEquals(
            TimelineRecordsEmptyState(
                title = "这一天没有尿记录",
                message = "尿筛选仍在生效，可换日继续比较或再次点按取消",
                testTag = "log_records_category_empty",
            ),
            timelineRecordsEmptyState(DayChartCategory.PEE),
        )
    }

    @Test
    fun unfilteredEmptyDayUsesTheGeneralEmptyState() {
        assertEquals(
            TimelineRecordsEmptyState(
                title = "还没有记录",
                message = "点下方快捷入口添加第一条记录",
                testTag = "log_records_empty",
            ),
            timelineRecordsEmptyState(null),
        )
    }

    private fun record(id: Long, type: RecordType) = Record(
        id = id,
        clientUuid = "record-$id",
        babyId = 7L,
        type = type,
        timestamp = 1_700_000_000_000L + id,
        endTimestamp = null,
        payloadJson = "{}",
        updatedAt = 1_700_000_000_000L + id,
        deletedAt = null,
    )
}
