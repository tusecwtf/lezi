package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.DayChartCategory
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Feature-only wiring for day-chart filter state on the log page.
 * Category mapping lives in domain [com.lezi.babylog.domain.DayChartCategories].
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
    fun resolveDayChartSelection_decodesKeyAndClear() {
        assertNull(resolveDayChartSelection(null))
        assertEquals(DayChartCategory.MILK, resolveDayChartSelection("MILK"))
        assertEquals(DayChartCategory.PEE, resolveDayChartSelection("PEE"))
        assertNull(resolveDayChartSelection("NOT_A_CATEGORY"))
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
