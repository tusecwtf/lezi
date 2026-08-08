package com.lezi.babylog.feature.log.timeline

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.designsystem.LeziRecordColorRole
import com.lezi.babylog.domain.carelog.DayChartCategories
import com.lezi.babylog.domain.carelog.DayChartCategory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature mappings around the single [TimelineInteraction] filter owner. */
class DayChartFilterWiringTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val nowMs = Instant.parse("2026-08-08T10:00:00Z").toEpochMilli()

    @Test
    fun resolveDayChartSelectionDecodesKeyAndClear() {
        assertNull(resolveDayChartSelection(null))
        assertEquals(DayChartCategory.MILK, resolveDayChartSelection("MILK"))
        assertEquals(DayChartCategory.PEE, resolveDayChartSelection("PEE"))
        assertNull(resolveDayChartSelection("NOT_A_CATEGORY"))
    }

    @Test
    fun summaryTypesMapToTheSameFiveDayChartCategories() {
        assertEquals(DayChartCategory.MILK, summaryDayChartCategory(RecordType.FORMULA))
        assertEquals(DayChartCategory.MILK, summaryDayChartCategory(RecordType.PUMPED_FEED))
        assertEquals(DayChartCategory.NURSING, summaryDayChartCategory(RecordType.NURSING))
        assertEquals(DayChartCategory.SLEEP, summaryDayChartCategory(RecordType.SLEEP))
        assertEquals(DayChartCategory.PEE, summaryDayChartCategory(RecordType.PEE))
        assertEquals(DayChartCategory.POOP, summaryDayChartCategory(RecordType.POOP))
        assertNull(summaryDayChartCategory(RecordType.TEMPERATURE))
    }

    @Test
    fun legendCategoriesUseRecordSemanticRoles() {
        assertEquals(
            LeziRecordColorRole.Milk,
            dayChartLegendColorRole(DayChartCategory.MILK),
        )
        assertEquals(
            LeziRecordColorRole.Nursing,
            dayChartLegendColorRole(DayChartCategory.NURSING),
        )
        assertEquals(
            LeziRecordColorRole.Sleep,
            dayChartLegendColorRole(DayChartCategory.SLEEP),
        )
        assertEquals(LeziRecordColorRole.Pee, dayChartLegendColorRole(DayChartCategory.PEE))
        assertEquals(LeziRecordColorRole.Poop, dayChartLegendColorRole(DayChartCategory.POOP))
    }

    @Test
    fun summaryTapUsesTimelineInteractionForA2ToggleAndFiltering() {
        val records = listOf(record(1, RecordType.FORMULA), record(2, RecordType.PEE))
        var state = initialize()

        state = selectSummary(state, RecordType.FORMULA, records)
        assertEquals(DayChartCategory.MILK, state.filter.selection)
        state = selectSummary(state, RecordType.FORMULA, records)
        assertNull(state.filter.selection)

        state = selectSummary(state, RecordType.POOP, records)
        assertNull(state.filter.selection)
        state = selectSummary(state, RecordType.PEE, records)
        assertEquals(DayChartCategory.PEE, state.filter.selection)
        assertEquals(
            listOf(2L),
            DayChartCategories.filterRecords(records, state.filter.selection).map(Record::id),
        )
    }

    @Test
    fun selectedKeyHighlightsMatchingMarksAcrossTheWorkWindow() {
        val dayRecords = listOf(record(1, RecordType.PEE))
        val state = TimelineInteraction.reduce(
            initialize(),
            TimelineInteractionEvent.SelectCategory("PEE", dayRecords),
        ).state
        val selectedKey = state.filter.selection?.name

        assertEquals("PEE", selectedKey)
        assertEquals(
            listOf(true, true, false, true),
            listOf("PEE", "PEE", "MILK", "PEE").map(selectedKey::equals),
        )
        assertTrue(DayChartCategories.isPresentOnDay(DayChartCategory.PEE, dayRecords))
    }

    private fun selectSummary(
        state: TimelineInteractionState,
        type: RecordType,
        records: List<Record>,
    ): TimelineInteractionState {
        val category = summaryDayChartCategory(type) ?: return state
        val key = category.name.takeUnless { category == state.filter.selection }
        return TimelineInteraction.reduce(
            state,
            TimelineInteractionEvent.SelectCategory(key, records),
        ).state
    }

    private fun initialize(): TimelineInteractionState = TimelineInteraction.reduce(
        null,
        TimelineInteractionEvent.Initialize(
            selectedDay = LocalDate.of(2026, 8, 8),
            babyId = 1L,
            nowMs = nowMs,
            zoneId = zone,
        ),
    ).state

    private fun record(id: Long, type: RecordType) = Record(
        id = id,
        clientUuid = "record-$id",
        babyId = 1L,
        type = type,
        timestamp = 1_700_000_000_000L + id,
        endTimestamp = null,
        payloadJson = "{}",
        updatedAt = 1_700_000_000_000L + id,
        deletedAt = null,
    )
}
