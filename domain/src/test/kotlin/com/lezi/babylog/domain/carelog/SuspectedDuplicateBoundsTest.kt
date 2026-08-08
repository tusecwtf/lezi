package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Test

class SuspectedDuplicateBoundsTest {

    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2024, 6, 1)
    private val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val now = dayStart + 20 * 60 * 60 * 1000L

    @Test
    fun noOpenGroups_minEqualsMaxAndMatchesOrdinaryDay() {
        val records = listOf(
            formula("a", dayStart + 3_600_000L, 100, "m1"),
            formula("b", dayStart + 7_200_000L, 120, "m1"),
        )
        val bounds = SuspectedDuplicateBounds.day(records, emptyList(), day, zone, now)
        val ordinary = CareAggregation.day(records, day, zone, now)
        assertThat(bounds.formulaMl.min).isEqualTo(ordinary.formulaMl)
        assertThat(bounds.formulaMl.max).isEqualTo(ordinary.formulaMl)
        assertThat(bounds.feedCount.min).isEqualTo(2)
        assertThat(bounds.feedCount.max).isEqualTo(2)
        assertThat(bounds.hasUncertainty).isFalse()
    }

    @Test
    fun unresolvedFormulaPair_formulaMlAndFeedCountSpanSingleThroughBoth() {
        // Two cross-member formulas 100ml + 120ml in one open group.
        val records = listOf(
            formula("a", dayStart + 3_600_000L, 100, "m-owner"),
            formula("b", dayStart + 3_600_000L + 5 * 60_000L, 120, "m-member"),
        )
        val groups = SuspectedDuplicateGrouping.group(records)
        assertThat(groups).hasSize(1)
        val bounds = SuspectedDuplicateBounds.day(records, groups, day, zone, now)
        // min: one display source → min(100,120)=100, feedCount=1
        // max: both independent → 220, feedCount=2
        assertThat(bounds.formulaMl).isEqualTo(IntBound(100, 220))
        assertThat(bounds.feedMl).isEqualTo(IntBound(100, 220))
        assertThat(bounds.feedCount).isEqualTo(IntBound(1, 2))
        assertThat(bounds.hasUncertainty).isTrue()
        assertThat(bounds.formulaMl.formatRange()).isEqualTo("100–220")
    }

    @Test
    fun resolvedDisplayOnly_collapsesToSingleValueWithoutDeletingSourceRows() {
        val display = formula("display", dayStart + 3_600_000L, 100, "m1")
        val source = formula("source", dayStart + 3_600_000L + 1_000L, 120, "m2")
        val all = listOf(display, source)
        // Projection for stats drops source-role UUID; raw rows remain live.
        val projected = SuspectedDuplicateBounds.filterDisplayProjection(
            all,
            sourceRoleClientUuids = setOf("source"),
        )
        assertThat(projected.map { it.clientUuid }).containsExactly("display")
        val bounds = SuspectedDuplicateBounds.day(projected, emptyList(), day, zone, now)
        assertThat(bounds.formulaMl).isEqualTo(IntBound(100, 100))
        assertThat(source.deletedAt).isNull()
    }

    @Test
    fun peePair_countBoundsOneToTwo() {
        val records = listOf(
            pee("a", dayStart + 1_000L, "m1"),
            pee("b", dayStart + 2_000L, "m2"),
        )
        val groups = SuspectedDuplicateGrouping.group(records)
        val bounds = SuspectedDuplicateBounds.day(records, groups, day, zone, now)
        assertThat(bounds.peeCount).isEqualTo(IntBound(1, 2))
    }

    @Test
    fun independentPlusGroup_composesDeterministically() {
        // Independent is >30min away so it cannot join the suspected pair.
        val independent = formula("solo", dayStart + 1_000L, 50, "m1")
        val g1 = formula("g1", dayStart + 2 * 60 * 60_000L, 100, "m1")
        val g2 = formula("g2", dayStart + 2 * 60 * 60_000L + 1_000L, 100, "m2")
        val records = listOf(independent, g1, g2)
        val groups = SuspectedDuplicateGrouping.group(records)
        assertThat(groups).hasSize(1)
        assertThat(groups.single().memberClientUuids).containsExactly("g1", "g2").inOrder()
        val bounds = SuspectedDuplicateBounds.day(records, groups, day, zone, now)
        // fixed 50 + min(100,100)=100 → 150; fixed 50 + 200 → 250
        assertThat(bounds.formulaMl).isEqualTo(IntBound(150, 250))
        assertThat(bounds.feedCount).isEqualTo(IntBound(2, 3))
    }

    @Test
    fun groupDoesNotMutateRecordsOrFulfillmentState() {
        val records = listOf(
            formula("a", dayStart + 1_000L, 80, "m1"),
            formula("b", dayStart + 2_000L, 90, "m2"),
        )
        val before = records.map { it.copy() }
        val groups = SuspectedDuplicateGrouping.group(records)
        SuspectedDuplicateBounds.day(records, groups, day, zone, now)
        assertThat(records).isEqualTo(before)
        assertThat(records.all { it.deletedAt == null }).isTrue()
        assertThat(records.all { !it.syncDirty || it.syncDirty }).isTrue()
    }

    private fun formula(
        uuid: String,
        ts: Long,
        ml: Int,
        membership: String,
    ): Record = Record(
        id = uuid.hashCode().toLong().and(0xffffL),
        clientUuid = uuid,
        babyId = 1,
        type = RecordType.FORMULA,
        timestamp = ts,
        payloadJson = """{"amount_ml":$ml}""",
        updatedAt = ts,
        createdByMembershipId = membership,
    )

    private fun pee(uuid: String, ts: Long, membership: String): Record = Record(
        id = uuid.hashCode().toLong().and(0xffffL),
        clientUuid = uuid,
        babyId = 1,
        type = RecordType.PEE,
        timestamp = ts,
        payloadJson = """{"pee_amount":2}""",
        updatedAt = ts,
        createdByMembershipId = membership,
    )
}
