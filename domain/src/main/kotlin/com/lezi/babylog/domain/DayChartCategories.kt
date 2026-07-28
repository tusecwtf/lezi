package com.lezi.babylog.domain

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType

/**
 * Day-view chart categories used for time-bar visibility, legend, and type filtering.
 *
 * Distinct from physical rails (sleep / feed / care) and from [CareAggregation] totals:
 * only these five rhythm types participate in the day chart navigation model.
 */
enum class DayChartCategory(val label: String) {
    MILK("奶"),
    NURSING("母乳"),
    SLEEP("睡眠"),
    PEE("尿"),
    POOP("便"),
}

/**
 * Pure day-chart category model: classification, show/hide, legend, list filter,
 * and selection reduce. No Android / Compose dependency.
 */
object DayChartCategories {
    fun categoriesOf(type: RecordType): Set<DayChartCategory> = when (type) {
        RecordType.FORMULA, RecordType.PUMPED_FEED -> setOf(DayChartCategory.MILK)
        RecordType.NURSING -> setOf(DayChartCategory.NURSING)
        RecordType.SLEEP -> setOf(DayChartCategory.SLEEP)
        RecordType.PEE -> setOf(DayChartCategory.PEE)
        RecordType.POOP -> setOf(DayChartCategory.POOP)
        RecordType.BOTH_DIAPER -> setOf(DayChartCategory.PEE, DayChartCategory.POOP)
        else -> emptySet()
    }

    fun categoriesOf(record: Record): Set<DayChartCategory> =
        if (record.deletedAt != null) emptySet() else categoriesOf(record.type)

    /** True when the day has at least one record that maps to a day-chart category. */
    fun shouldShowDayChart(records: Iterable<Record>): Boolean =
        records.any { categoriesOf(it).isNotEmpty() }

    /**
     * Day-chart categories present in [records], in fixed enum order
     * (only categories that appear that day).
     */
    fun legendCategories(records: Iterable<Record>): List<DayChartCategory> {
        val present = LinkedHashSet<DayChartCategory>()
        for (record in records) {
            present += categoriesOf(record)
        }
        return DayChartCategory.entries.filter { it in present }
    }

    /**
     * Unfiltered when [selected] is null; otherwise records whose category set
     * intersects [selected] (e.g. BOTH_DIAPER appears under both PEE and POOP).
     */
    fun filterRecords(
        records: List<Record>,
        selected: DayChartCategory?,
    ): List<Record> {
        if (selected == null) return records
        return records.filter { selected in categoriesOf(it) }
    }

    /** Tap same category clears; tap another switches. */
    fun toggleSelection(
        selected: DayChartCategory?,
        tapped: DayChartCategory,
    ): DayChartCategory? = if (selected == tapped) null else tapped

    /**
     * True when [category] appears among day-chart types in [dayRecords]
     * (selected day **D** only — not the 72h rail union).
     */
    fun isPresentOnDay(
        category: DayChartCategory,
        dayRecords: Iterable<Record>,
    ): Boolean = category in legendCategories(dayRecords)

    /**
     * A2 selection gate for day-chart filter commits.
     *
     * - [proposed] null always clears (blank tap / re-tap same type / legend deselect).
     * - Non-null [proposed] commits only when that category appears in [dayRecords] (day D).
     * - Otherwise keep [current] unchanged (neighbor-only marks do not enter an empty filter).
     *
     * List / legend / reconcile stay on D; once committed, the rail highlights the same key
     * across the 72h window via `selectedCategoryKey`.
     */
    fun commitSelection(
        current: DayChartCategory?,
        proposed: DayChartCategory?,
        dayRecords: Iterable<Record>,
    ): DayChartCategory? {
        if (proposed == null) return null
        return if (isPresentOnDay(proposed, dayRecords)) proposed else current
    }

    /**
     * Keep [selected] only while that category still appears in [records];
     * otherwise clear (avoids a zero-row sticky filter).
     */
    fun reconcileSelection(
        selected: DayChartCategory?,
        records: Iterable<Record>,
    ): DayChartCategory? {
        if (selected == null) return null
        return if (selected in legendCategories(records).toSet()) selected else null
    }
}
