package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.DayChartCategories
import com.lezi.babylog.domain.DayChartCategory
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature-only wiring for day-chart filter state on the log page.
 * Category mapping lives in domain [com.lezi.babylog.domain.DayChartCategories].
 */
class DayChartFilterWiringTest {

    private val dayRecordsWithPee = listOf(stubRecord(1, RecordType.PEE))
    private val dayRecordsWithMilkAndPee = listOf(
        stubRecord(1, RecordType.FORMULA),
        stubRecord(2, RecordType.PEE),
    )

    @Test
    fun filterContext_changeClearsSelection_withoutRestoringPerBabyState() {
        val day = LocalDate.of(2026, 7, 27)
        val babyA = DayChartFilterContext(babyId = 101, day = day)
        val babyB = DayChartFilterContext(babyId = 202, day = day)
        var state = DayChartFilterState(context = babyA)

        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.Select("PEE", dayRecordsWithPee),
        )
        assertEquals(DayChartCategory.PEE, state.selection)

        state = reduceDayChartFilter(state, DayChartFilterAction.ChangeContext(babyB))
        assertNull(state.selection)

        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.Select("PEE", dayRecordsWithPee),
        )
        state = reduceDayChartFilter(state, DayChartFilterAction.ChangeContext(babyA))
        assertNull(state.selection)

        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.Select("POOP", listOf(stubRecord(3, RecordType.POOP))),
        )
        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.ChangeContext(babyA.copy(day = day.plusDays(1))),
        )
        assertNull(state.selection)

        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.Select("MILK", listOf(stubRecord(4, RecordType.FORMULA))),
        )
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
        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.Select("PEE", dayRecordsWithPee),
        )

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
    fun resolveDayChartSelection_decodesKeyAndClear() {
        assertNull(resolveDayChartSelection(null))
        assertEquals(DayChartCategory.MILK, resolveDayChartSelection("MILK"))
        assertEquals(DayChartCategory.PEE, resolveDayChartSelection("PEE"))
        assertNull(resolveDayChartSelection("NOT_A_CATEGORY"))
    }

    @Test
    fun summaryTypes_mapToTheSameFiveDayChartCategories() {
        assertEquals(DayChartCategory.MILK, summaryDayChartCategory(RecordType.FORMULA))
        assertEquals(DayChartCategory.MILK, summaryDayChartCategory(RecordType.PUMPED_FEED))
        assertEquals(DayChartCategory.NURSING, summaryDayChartCategory(RecordType.NURSING))
        assertEquals(DayChartCategory.SLEEP, summaryDayChartCategory(RecordType.SLEEP))
        assertEquals(DayChartCategory.PEE, summaryDayChartCategory(RecordType.PEE))
        assertEquals(DayChartCategory.POOP, summaryDayChartCategory(RecordType.POOP))
        assertNull(summaryDayChartCategory(RecordType.TEMPERATURE))
    }

    @Test
    fun summarySelection_togglesThroughSharedReducer_andNoDataIsNoOp() {
        val records = listOf(stubRecord(1, RecordType.FORMULA), stubRecord(2, RecordType.PEE))
        var state = DayChartFilterState(
            DayChartFilterContext(babyId = 1, day = LocalDate.of(2026, 7, 27)),
        )

        state = reduceSummaryDayChartSelection(state, RecordType.FORMULA, records)
        assertEquals(DayChartCategory.MILK, state.selection)
        state = reduceSummaryDayChartSelection(state, RecordType.FORMULA, records)
        assertNull(state.selection)
        state = reduceSummaryDayChartSelection(state, RecordType.POOP, records)
        assertNull(state.selection)
        state = reduceSummaryDayChartSelection(state, RecordType.PEE, records)
        assertEquals(DayChartCategory.PEE, state.selection)
    }

    @Test
    fun select_a2BlocksCategoryAbsentOnDayD_evenIfNeighborRailHasIt() {
        // Day D has milk only; tapping PEE (e.g. a D−1 mark) must not enter filter.
        val dayD = listOf(stubRecord(1, RecordType.FORMULA))
        var state = DayChartFilterState(
            DayChartFilterContext(babyId = 1, day = LocalDate.of(2026, 7, 27)),
        )

        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.Select("PEE", dayRecords = dayD),
        )
        assertNull(state.selection)

        // Existing filter is preserved when switching to an absent category.
        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.Select("MILK", dayRecords = dayD),
        )
        assertEquals(DayChartCategory.MILK, state.selection)
        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.Select("POOP", dayRecords = dayD),
        )
        assertEquals(DayChartCategory.MILK, state.selection)
    }

    @Test
    fun select_categoryPresentOnDayD_commits_andClearPathsWork() {
        val dayD = dayRecordsWithMilkAndPee
        var state = DayChartFilterState(
            DayChartFilterContext(babyId = 1, day = LocalDate.of(2026, 7, 27)),
        )

        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.Select("PEE", dayRecords = dayD),
        )
        assertEquals(DayChartCategory.PEE, state.selection)

        // List stays on D only.
        val filtered = DayChartCategories.filterRecords(dayD, state.selection)
        assertEquals(listOf(2L), filtered.map { it.id })

        // Blank / re-tap clear (null) is never blocked by A2.
        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.Select(null, dayRecords = dayD),
        )
        assertNull(state.selection)

        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.Select("MILK", dayRecords = dayD),
        )
        assertEquals(DayChartCategory.MILK, state.selection)
        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.Select(null, dayRecords = emptyList()),
        )
        assertNull(state.selection)
    }

    @Test
    fun select_onceCommitted_selectedKeyMatchesMarksAcrossThreeDaysForHighlight() {
        // Rail marks from D−1|D|D+1 share the same dayChartCategoryKey string;
        // page selection is one key — designsystem lights every matching mark.
        val dayD = listOf(stubRecord(1, RecordType.PEE))
        var state = DayChartFilterState(
            DayChartFilterContext(babyId = 1, day = LocalDate.of(2026, 7, 27)),
        )
        state = reduceDayChartFilter(
            state,
            DayChartFilterAction.Select("PEE", dayRecords = dayD),
        )
        val selectedKey = state.selection?.name
        assertEquals("PEE", selectedKey)

        val railKeysAcross72h = listOf("PEE", "PEE", "MILK", "PEE")
        val highlighted = railKeysAcross72h.map { key ->
            selectedKey != null && key == selectedKey
        }
        assertEquals(listOf(true, true, false, true), highlighted)
        // Neighbor-only keys never commit when absent on D.
        assertTrue(DayChartCategories.isPresentOnDay(DayChartCategory.PEE, dayD))
        assertTrue(!DayChartCategories.isPresentOnDay(DayChartCategory.MILK, dayD))
    }

    @Test
    fun legendAndReconcileStayOnDayD_notRailUnion() {
        val dayD = listOf(stubRecord(1, RecordType.FORMULA))
        // Neighbor-only sleep must not appear in D legend / keep sticky filter.
        assertEquals(
            listOf(DayChartCategory.MILK),
            DayChartCategories.legendCategories(dayD),
        )
        assertNull(
            DayChartCategories.reconcileSelection(DayChartCategory.SLEEP, dayD),
        )
        assertEquals(
            DayChartCategory.MILK,
            DayChartCategories.reconcileSelection(DayChartCategory.MILK, dayD),
        )
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
