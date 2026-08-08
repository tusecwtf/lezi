package com.lezi.babylog.feature.summary

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.IntBound
import com.lezi.babylog.domain.carelog.SuspectedDuplicateBounds
import com.lezi.babylog.domain.carelog.SuspectedDuplicateGrouping
import com.lezi.babylog.domain.carelog.SuspectedDuplicatePresentation
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Summary bounds copy for unresolved suspected-duplicate groups (ticket 07).
 */
class SummaryDuplicateBoundsTest {

    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2024, 6, 1)
    private val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val now = dayStart + 12 * 60 * 60 * 1000L

    @Test
    fun unresolvedPair_showsFormulaMlRangeNotSingleWinner() {
        val a = formula("a", dayStart + 3_600_000L, 100, "m1")
        val b = formula("b", dayStart + 3_600_000L + 60_000L, 120, "m2")
        val groups = SuspectedDuplicateGrouping.group(listOf(a, b))
        val bounds = SuspectedDuplicateBounds.day(listOf(a, b), groups, day, zone, now)
        assertEquals(IntBound(100, 220), bounds.formulaMl)
        assertTrue(bounds.hasUncertainty)
        assertEquals(
            "100–220ml",
            SuspectedDuplicatePresentation.formatMetricBound(bounds.formulaMl, "ml"),
        )
    }

    @Test
    fun resolvedDisplayOnly_exactSingleValue() {
        val display = formula("display", dayStart + 3_600_000L, 100, "m1")
        val source = formula("source", dayStart + 3_600_000L + 1_000L, 120, "m2")
        val projected = SuspectedDuplicateBounds.filterDisplayProjection(
            listOf(display, source),
            sourceRoleClientUuids = setOf("source"),
        )
        val bounds = SuspectedDuplicateBounds.day(projected, emptyList(), day, zone, now)
        assertEquals(IntBound(100, 100), bounds.formulaMl)
        assertEquals(
            "100ml",
            SuspectedDuplicatePresentation.formatMetricBound(bounds.formulaMl, "ml"),
        )
    }

    private fun formula(uuid: String, ts: Long, ml: Int, membership: String): Record = Record(
        id = 1,
        clientUuid = uuid,
        babyId = 1,
        type = RecordType.FORMULA,
        timestamp = ts,
        payloadJson = """{"amount_ml":$ml}""",
        updatedAt = ts,
        createdByMembershipId = membership,
    )
}
