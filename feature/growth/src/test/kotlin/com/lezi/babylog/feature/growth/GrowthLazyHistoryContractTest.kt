package com.lezi.babylog.feature.growth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ticket 08 (ui-drawing-polish): growth history list order helper.
 *
 * Lazy composition (LazyColumn / keys / animateItem) is implementation on the
 * growth route — not gated by product-less source-layout StructureTests
 * (AGENTS.md / tech.md §2.1). Edit/save/delete remains covered by
 * [GrowthMeasurementWriteCoordinatorTest].
 */
class GrowthLazyHistoryContractTest {

    @Test
    fun `history order helper is newest measuredAt first`() {
        val older = MeasurePoint(
            monthAge = 1f,
            value = 4f,
            recordId = 1L,
            measuredAt = 1_000L,
            note = null,
            referenceWarning = null,
        )
        val mid = MeasurePoint(
            monthAge = 1.5f,
            value = 4.5f,
            recordId = 3L,
            measuredAt = 1_500L,
            note = null,
            referenceWarning = null,
        )
        val newer = MeasurePoint(
            monthAge = 2f,
            value = 5f,
            recordId = 2L,
            measuredAt = 2_000L,
            note = null,
            referenceWarning = null,
        )

        assertEquals(
            listOf(2L, 3L, 1L),
            growthHistoryNewestFirst(listOf(older, mid, newer)).map(MeasurePoint::recordId),
        )
        assertEquals(emptyList<MeasurePoint>(), growthHistoryNewestFirst(emptyList()))
    }
}
