package com.lezi.babylog.feature.log.timeline

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.DayChartCategory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineExperienceProjectionTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun absoluteViewportProjectsOntoRebasedAxisWithoutChangingItsInstants() {
        val day = LocalDate.of(2026, 8, 8)
        val nowMs = Instant.parse("2026-08-08T00:00:00Z").toEpochMilli()
        val state = TimelineInteraction.reduce(
            null,
            TimelineInteractionEvent.Initialize(day, 7L, nowMs, shanghai),
        ).state

        val presentation = state.toTimelinePresentation(nowMs)

        assertEquals(1_440, presentation.viewportDurationMinutes)
        assertEquals(1_440 - 16 * 60, presentation.viewportStartMinutes)
        assertEquals(presentation.axis.instantToContentMinute(nowMs), presentation.nowContentMinute)
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

        val presentation = state.toTimelinePresentation(nowMs)

        assertEquals(23 * 60, presentation.viewportDurationMinutes)
        assertEquals(null, presentation.nowContentMinute)
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
