package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import org.junit.Test

class DayChartCategoriesTest {
    private val baseTs = 1_700_000_000_000L

    @Test
    fun categoriesOf_mapsFiveDayChartTypes() {
        assertThat(DayChartCategories.categoriesOf(RecordType.FORMULA))
            .containsExactly(DayChartCategory.MILK)
        assertThat(DayChartCategories.categoriesOf(RecordType.PUMPED_FEED))
            .containsExactly(DayChartCategory.MILK)
        assertThat(DayChartCategories.categoriesOf(RecordType.NURSING))
            .containsExactly(DayChartCategory.NURSING)
        assertThat(DayChartCategories.categoriesOf(RecordType.SLEEP))
            .containsExactly(DayChartCategory.SLEEP)
        assertThat(DayChartCategories.categoriesOf(RecordType.PEE))
            .containsExactly(DayChartCategory.PEE)
        assertThat(DayChartCategories.categoriesOf(RecordType.POOP))
            .containsExactly(DayChartCategory.POOP)
    }

    @Test
    fun categoriesOf_bothDiaperBelongsToPeeAndPoop() {
        assertThat(DayChartCategories.categoriesOf(RecordType.BOTH_DIAPER))
            .containsExactly(DayChartCategory.PEE, DayChartCategory.POOP)
    }

    @Test
    fun categoriesOf_pumpExpressAndNonRhythmTypesAreEmpty() {
        val nonDayChart = listOf(
            RecordType.PUMP_EXPRESS,
            RecordType.TEMPERATURE,
            RecordType.MEDICINE,
            RecordType.BATH,
            RecordType.DIARY,
            RecordType.CUSTOM,
            RecordType.HEIGHT,
            RecordType.WEIGHT,
            RecordType.WALK,
        )
        for (type in nonDayChart) {
            assertThat(DayChartCategories.categoriesOf(type)).isEmpty()
        }
    }

    @Test
    fun categoriesOf_softDeletedRecordIsEmpty() {
        val live = rec(1, RecordType.FORMULA)
        val deleted = rec(2, RecordType.FORMULA, deleted = baseTs)
        assertThat(DayChartCategories.categoriesOf(live))
            .containsExactly(DayChartCategory.MILK)
        assertThat(DayChartCategories.categoriesOf(deleted)).isEmpty()
    }

    @Test
    fun shouldShowDayChart_falseWhenEmpty() {
        assertThat(DayChartCategories.shouldShowDayChart(emptyList())).isFalse()
    }

    @Test
    fun shouldShowDayChart_falseWhenOnlyNonDayChartTypes() {
        val records = listOf(
            rec(1, RecordType.PUMP_EXPRESS),
            rec(2, RecordType.TEMPERATURE),
            rec(3, RecordType.MEDICINE),
            rec(4, RecordType.BATH),
        )
        assertThat(DayChartCategories.shouldShowDayChart(records)).isFalse()
    }

    @Test
    fun shouldShowDayChart_trueForEachDayChartTypeAlone() {
        val representatives = listOf(
            RecordType.FORMULA,
            RecordType.PUMPED_FEED,
            RecordType.NURSING,
            RecordType.SLEEP,
            RecordType.PEE,
            RecordType.POOP,
            RecordType.BOTH_DIAPER,
        )
        for (type in representatives) {
            assertThat(DayChartCategories.shouldShowDayChart(listOf(rec(1, type)))).isTrue()
        }
    }

    @Test
    fun shouldShowDayChart_trueForOpenSleep() {
        val openSleep = rec(1, RecordType.SLEEP, end = null)
        assertThat(DayChartCategories.shouldShowDayChart(listOf(openSleep))).isTrue()
        assertThat(DayChartCategories.categoriesOf(openSleep))
            .containsExactly(DayChartCategory.SLEEP)
    }

    @Test
    fun shouldShowDayChart_ignoresSoftDeletedDayChartRecords() {
        val records = listOf(
            rec(1, RecordType.FORMULA, deleted = baseTs),
            rec(2, RecordType.TEMPERATURE),
        )
        assertThat(DayChartCategories.shouldShowDayChart(records)).isFalse()
    }

    /**
     * Three-day rail visibility: callers feed the D−1|D|D+1 union.
     * D alone empty must not hide the rail when a neighbor has a day-chart type.
     */
    @Test
    fun shouldShowDayChart_threeDayUnion_neighborOnlyShowsRail() {
        val dayDRecords = emptyList<Record>()
        val railRecords = listOf(rec(1, RecordType.PEE)) // neighbor-only day-chart type
        assertThat(DayChartCategories.shouldShowDayChart(dayDRecords)).isFalse()
        assertThat(DayChartCategories.shouldShowDayChart(railRecords)).isTrue()
    }

    @Test
    fun shouldShowDayChart_threeDayUnion_allEmptyHidesRail() {
        assertThat(DayChartCategories.shouldShowDayChart(emptyList())).isFalse()
    }

    @Test
    fun shouldShowDayChart_threeDayUnion_onlyNonDayChartAcrossDaysHidesRail() {
        val railRecords = listOf(
            rec(1, RecordType.PUMP_EXPRESS),
            rec(2, RecordType.TEMPERATURE),
            rec(3, RecordType.MEDICINE),
        )
        assertThat(DayChartCategories.shouldShowDayChart(railRecords)).isFalse()
    }

    @Test
    fun shouldShowDayChart_threeDayUnion_dHasTypeShowsRail() {
        val railRecords = listOf(
            rec(1, RecordType.FORMULA),
            rec(2, RecordType.TEMPERATURE),
        )
        assertThat(DayChartCategories.shouldShowDayChart(railRecords)).isTrue()
    }

    @Test
    fun shouldShowDayChart_threeDayUnion_softDeletedNeighborDoesNotShow() {
        val railRecords = listOf(
            rec(1, RecordType.PEE, deleted = baseTs),
            rec(2, RecordType.BATH),
        )
        assertThat(DayChartCategories.shouldShowDayChart(railRecords)).isFalse()
    }

    @Test
    fun legendCategories_onlyPresentInEnumOrder() {
        val records = listOf(
            rec(1, RecordType.POOP),
            rec(2, RecordType.NURSING),
            rec(3, RecordType.PUMP_EXPRESS),
            rec(4, RecordType.FORMULA),
            rec(5, RecordType.TEMPERATURE),
        )
        assertThat(DayChartCategories.legendCategories(records)).containsExactly(
            DayChartCategory.MILK,
            DayChartCategory.NURSING,
            DayChartCategory.POOP,
        ).inOrder()
    }

    @Test
    fun legendCategories_bothDiaperAddsPeeAndPoop() {
        assertThat(DayChartCategories.legendCategories(listOf(rec(1, RecordType.BOTH_DIAPER))))
            .containsExactly(DayChartCategory.PEE, DayChartCategory.POOP)
            .inOrder()
    }

    @Test
    fun legendCategories_emptyWhenNoDayChartTypes() {
        assertThat(
            DayChartCategories.legendCategories(
                listOf(rec(1, RecordType.PUMP_EXPRESS), rec(2, RecordType.MEDICINE)),
            ),
        ).isEmpty()
    }

    @Test
    fun filterRecords_nullSelectedReturnsAll() {
        val records = listOf(
            rec(1, RecordType.FORMULA),
            rec(2, RecordType.TEMPERATURE),
            rec(3, RecordType.PUMP_EXPRESS),
        )
        assertThat(DayChartCategories.filterRecords(records, selected = null))
            .isEqualTo(records)
    }

    @Test
    fun filterRecords_nursingKeepsOnlyNursing() {
        val records = listOf(
            rec(1, RecordType.NURSING),
            rec(2, RecordType.FORMULA),
            rec(3, RecordType.PUMPED_FEED),
            rec(4, RecordType.TEMPERATURE),
        )
        assertThat(DayChartCategories.filterRecords(records, DayChartCategory.NURSING).map { it.id })
            .containsExactly(1L)
    }

    @Test
    fun filterRecords_milkKeepsFormulaAndPumpedFeedNotPumpExpress() {
        val records = listOf(
            rec(1, RecordType.FORMULA),
            rec(2, RecordType.PUMPED_FEED),
            rec(3, RecordType.PUMP_EXPRESS),
            rec(4, RecordType.NURSING),
        )
        assertThat(DayChartCategories.filterRecords(records, DayChartCategory.MILK).map { it.id })
            .containsExactly(1L, 2L)
            .inOrder()
    }

    @Test
    fun filterRecords_peeIncludesPeeAndBothDiaper() {
        val records = listOf(
            rec(1, RecordType.PEE),
            rec(2, RecordType.POOP),
            rec(3, RecordType.BOTH_DIAPER),
            rec(4, RecordType.BATH),
        )
        assertThat(DayChartCategories.filterRecords(records, DayChartCategory.PEE).map { it.id })
            .containsExactly(1L, 3L)
            .inOrder()
    }

    @Test
    fun filterRecords_poopIncludesPoopAndBothDiaper() {
        val records = listOf(
            rec(1, RecordType.PEE),
            rec(2, RecordType.POOP),
            rec(3, RecordType.BOTH_DIAPER),
        )
        assertThat(DayChartCategories.filterRecords(records, DayChartCategory.POOP).map { it.id })
            .containsExactly(2L, 3L)
            .inOrder()
    }

    @Test
    fun isPresentOnDay_trueOnlyForCategoriesInDayRecords() {
        val dayD = listOf(rec(1, RecordType.FORMULA), rec(2, RecordType.PEE))
        assertThat(DayChartCategories.isPresentOnDay(DayChartCategory.MILK, dayD)).isTrue()
        assertThat(DayChartCategories.isPresentOnDay(DayChartCategory.PEE, dayD)).isTrue()
        assertThat(DayChartCategories.isPresentOnDay(DayChartCategory.POOP, dayD)).isFalse()
        assertThat(DayChartCategories.isPresentOnDay(DayChartCategory.SLEEP, emptyList())).isFalse()
    }

    @Test
    fun commitSelection_a2BlocksCategoryAbsentOnDay_allowsClear() {
        // D has milk only; PEE exists only on a neighbor day in the 72h rail.
        val dayD = listOf(rec(1, RecordType.FORMULA))
        assertThat(
            DayChartCategories.commitSelection(
                current = null,
                proposed = DayChartCategory.PEE,
                dayRecords = dayD,
            ),
        ).isNull()
        assertThat(
            DayChartCategories.commitSelection(
                current = DayChartCategory.MILK,
                proposed = DayChartCategory.PEE,
                dayRecords = dayD,
            ),
        ).isEqualTo(DayChartCategory.MILK)
        // Clear always succeeds (blank / re-tap / legend deselect).
        assertThat(
            DayChartCategories.commitSelection(
                current = DayChartCategory.MILK,
                proposed = null,
                dayRecords = dayD,
            ),
        ).isNull()
        assertThat(
            DayChartCategories.commitSelection(
                current = DayChartCategory.MILK,
                proposed = null,
                dayRecords = emptyList(),
            ),
        ).isNull()
    }

    @Test
    fun commitSelection_categoryPresentOnDayCommits() {
        val dayD = listOf(rec(1, RecordType.FORMULA), rec(2, RecordType.PEE))
        assertThat(
            DayChartCategories.commitSelection(
                current = null,
                proposed = DayChartCategory.PEE,
                dayRecords = dayD,
            ),
        ).isEqualTo(DayChartCategory.PEE)
        assertThat(
            DayChartCategories.commitSelection(
                current = DayChartCategory.PEE,
                proposed = DayChartCategory.MILK,
                dayRecords = dayD,
            ),
        ).isEqualTo(DayChartCategory.MILK)
    }

    @Test
    fun commitSelection_bothDiaperMakesPeeAndPoopSelectableOnDay() {
        val dayD = listOf(rec(1, RecordType.BOTH_DIAPER))
        assertThat(
            DayChartCategories.commitSelection(null, DayChartCategory.PEE, dayD),
        ).isEqualTo(DayChartCategory.PEE)
        assertThat(
            DayChartCategories.commitSelection(null, DayChartCategory.POOP, dayD),
        ).isEqualTo(DayChartCategory.POOP)
        assertThat(
            DayChartCategories.commitSelection(null, DayChartCategory.MILK, dayD),
        ).isNull()
    }

    @Test
    fun reconcileSelection_clearsWhenCategoryGone_keepsWhenStillPresent() {
        val withMilk = listOf(rec(1, RecordType.FORMULA), rec(2, RecordType.PEE))
        val withoutMilk = listOf(rec(2, RecordType.PEE), rec(3, RecordType.TEMPERATURE))

        assertThat(
            DayChartCategories.reconcileSelection(DayChartCategory.MILK, withMilk),
        ).isEqualTo(DayChartCategory.MILK)
        assertThat(
            DayChartCategories.reconcileSelection(DayChartCategory.MILK, withoutMilk),
        ).isNull()
        assertThat(
            DayChartCategories.reconcileSelection(null, withMilk),
        ).isNull()
    }

    @Test
    fun reconcileSelection_bothDiaperKeepsPeeAndPoopSelection() {
        val onlyBoth = listOf(rec(1, RecordType.BOTH_DIAPER))
        assertThat(DayChartCategories.reconcileSelection(DayChartCategory.PEE, onlyBoth))
            .isEqualTo(DayChartCategory.PEE)
        assertThat(DayChartCategories.reconcileSelection(DayChartCategory.POOP, onlyBoth))
            .isEqualTo(DayChartCategory.POOP)
        assertThat(DayChartCategories.reconcileSelection(DayChartCategory.MILK, onlyBoth))
            .isNull()
    }

    private fun rec(
        id: Long,
        type: RecordType,
        ts: Long = baseTs + id,
        end: Long? = null,
        deleted: Long? = null,
    ) = Record(
        id = id,
        clientUuid = "u$id",
        babyId = 1,
        type = type,
        timestamp = ts,
        endTimestamp = end,
        payloadJson = "{}",
        updatedAt = ts,
        deletedAt = deleted,
    )
}
