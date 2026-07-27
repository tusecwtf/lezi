package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.DayChartCategories
import com.lezi.babylog.domain.DayChartCategory
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wiring seams for day-chart type filter on the log page:
 * segment keys, key decoding, and tip count = filterRecords size (not mark count).
 */
class DayChartFilterWiringTest {

    @Test
    fun filterContext_changeClearsSelection_withoutRestoringPerBabyState() {
        val day = LocalDate.of(2026, 7, 27)
        val babyA = DayChartFilterContext(babyId = 101, day = day)
        val babyB = DayChartFilterContext(babyId = 202, day = day)
        var state = DayChartFilterState(context = babyA)

        state = reduceDayChartFilter(state, DayChartFilterAction.Select("PEE"))
        assertEquals(DayChartCategory.PEE, state.selection)

        state = reduceDayChartFilter(state, DayChartFilterAction.ChangeContext(babyB))
        assertNull(state.selection)

        state = reduceDayChartFilter(state, DayChartFilterAction.Select("PEE"))
        state = reduceDayChartFilter(state, DayChartFilterAction.ChangeContext(babyA))
        assertNull(state.selection)

        state = reduceDayChartFilter(state, DayChartFilterAction.Select("POOP"))
        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.ChangeContext(babyA.copy(day = day.plusDays(1))),
        )
        assertNull(state.selection)

        state = reduceDayChartFilter(state, DayChartFilterAction.Select("MILK"))
        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.ChangeContext(DayChartFilterContext(babyId = null, day = day)),
        )
        assertNull(state.selection)
    }

    @Test
    fun filterRefresh_sameContextPreservesExistingCategory_thenClearsWhenItDisappears() {
        val context = DayChartFilterContext(
            babyId = 101,
            day = LocalDate.of(2026, 7, 27),
        )
        var state = DayChartFilterState(context = context)
        state = reduceDayChartFilter(state, DayChartFilterAction.Select("PEE"))

        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.RefreshRecords(listOf(stubRecord(1, RecordType.PEE))),
        )
        assertEquals(DayChartCategory.PEE, state.selection)

        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.RefreshRecords(listOf(stubRecord(2, RecordType.POOP))),
        )
        assertNull(state.selection)
    }

    @Test
    fun dayChartCategoryKey_mapsSingleCategoryTypes() {
        assertEquals(DayChartCategory.MILK.name, dayChartCategoryKeyForRecordType(RecordType.FORMULA))
        assertEquals(DayChartCategory.MILK.name, dayChartCategoryKeyForRecordType(RecordType.PUMPED_FEED))
        assertEquals(DayChartCategory.NURSING.name, dayChartCategoryKeyForRecordType(RecordType.NURSING))
        assertEquals(DayChartCategory.SLEEP.name, dayChartCategoryKeyForRecordType(RecordType.SLEEP))
        assertEquals(DayChartCategory.PEE.name, dayChartCategoryKeyForRecordType(RecordType.PEE))
        assertEquals(DayChartCategory.POOP.name, dayChartCategoryKeyForRecordType(RecordType.POOP))
    }

    @Test
    fun dayChartCategoryKey_pumpExpressAndOtherAreNotSelectable() {
        assertNull(dayChartCategoryKeyForRecordType(RecordType.PUMP_EXPRESS))
        assertNull(dayChartCategoryKeyForRecordType(RecordType.TEMPERATURE))
        assertNull(dayChartCategoryKeyForRecordType(RecordType.MEDICINE))
        assertNull(dayChartCategoryKeyForRecordType(RecordType.BATH))
    }

    @Test
    fun dayChartCategoryKey_bothDiaperIsNotASingleKey() {
        // BOTH_DIAPER becomes two lane marks (PEE + POOP) in buildLanes; singleOrNull is null.
        assertNull(dayChartCategoryKeyForRecordType(RecordType.BOTH_DIAPER))
        assertEquals(
            setOf(DayChartCategory.PEE, DayChartCategory.POOP),
            DayChartCategories.categoriesOf(RecordType.BOTH_DIAPER),
        )
    }

    @Test
    fun resolveDayChartSelection_decodesKeyAndClear() {
        assertNull(resolveDayChartSelection(null))
        assertEquals(DayChartCategory.MILK, resolveDayChartSelection("MILK"))
        assertEquals(DayChartCategory.NURSING, resolveDayChartSelection("NURSING"))
        assertEquals(DayChartCategory.SLEEP, resolveDayChartSelection("SLEEP"))
        assertEquals(DayChartCategory.PEE, resolveDayChartSelection("PEE"))
        assertEquals(DayChartCategory.POOP, resolveDayChartSelection("POOP"))
        assertNull(resolveDayChartSelection("NOT_A_CATEGORY"))
    }

    @Test
    fun tipCount_usesFilterRecordsSize_notMarkCount_forBothDiaper() {
        // One BOTH_DIAPER record → two care marks, but tip count must be 1 under PEE or POOP.
        val both = stubRecord(1, RecordType.BOTH_DIAPER)
        val pee = stubRecord(2, RecordType.PEE)
        val records = listOf(both, pee)
        assertEquals(2, DayChartCategories.filterRecords(records, DayChartCategory.PEE).size)
        assertEquals(1, DayChartCategories.filterRecords(records, DayChartCategory.POOP).size)
        assertEquals(0, DayChartCategories.filterRecords(records, DayChartCategory.MILK).size)
    }

    @Test
    fun legendLabels_areDayChartTypeNames_notRailNames() {
        val labels = DayChartCategory.entries.map { it.label }
        assertEquals(listOf("奶", "母乳", "睡眠", "尿", "便"), labels)
        // Physical rail names must not be used as day-chart legend labels.
        assert(!labels.contains("喂养"))
        assert(!labels.contains("护理"))
    }

    private fun stubRecord(id: Long, type: RecordType) = com.lezi.babylog.core.model.Record(
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
}
