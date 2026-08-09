package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

class SuspectedDuplicateGroupingTest {

    @Test
    fun whitelistContainsBathAndNursing_excludesSleepGrowthDiaryHospitalVaccineCustom() {
        assertThat(SuspectedDuplicateGrouping.isWhitelistType(RecordType.BATH)).isTrue()
        assertThat(SuspectedDuplicateGrouping.isWhitelistType(RecordType.NURSING)).isTrue()
        assertThat(SuspectedDuplicateGrouping.isWhitelistType(RecordType.FORMULA)).isTrue()
        assertThat(SuspectedDuplicateGrouping.isWhitelistType(RecordType.MEDICINE)).isTrue()
        assertThat(SuspectedDuplicateGrouping.isWhitelistType(RecordType.SLEEP)).isFalse()
        assertThat(SuspectedDuplicateGrouping.isWhitelistType(RecordType.HEIGHT)).isFalse()
        assertThat(SuspectedDuplicateGrouping.isWhitelistType(RecordType.WEIGHT)).isFalse()
        assertThat(SuspectedDuplicateGrouping.isWhitelistType(RecordType.DIARY)).isFalse()
        assertThat(SuspectedDuplicateGrouping.isWhitelistType(RecordType.HOSPITAL)).isFalse()
        assertThat(SuspectedDuplicateGrouping.isWhitelistType(RecordType.VACCINE)).isFalse()
        assertThat(SuspectedDuplicateGrouping.isWhitelistType(RecordType.CUSTOM)).isFalse()
        assertThat(SuspectedDuplicateGrouping.isWhitelistWireKey("sleep")).isFalse()
        assertThat(SuspectedDuplicateGrouping.isWhitelistWireKey("formula")).isTrue()
    }

    @Test
    fun windowIsThirtyMinutesInclusive() {
        assertThat(SuspectedDuplicateGrouping.WINDOW_MS).isEqualTo(1_800_000L)
    }

    @Test
    fun sameMembershipMultiDevice_doesNotGroup() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("a", RecordType.FORMULA, t0, "m-same"),
            record("b", RecordType.FORMULA, t0 + 5 * 60_000L, "m-same"),
            record("c", RecordType.FORMULA, t0 + 10 * 60_000L, "m-same"),
        )
        assertThat(SuspectedDuplicateGrouping.group(records)).isEmpty()
    }

    @Test
    fun differentExactTypes_doNotGroupEvenWithinWindow() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("a", RecordType.FORMULA, t0, "m1"),
            record("b", RecordType.PUMPED_FEED, t0 + 1_000L, "m2"),
            record("c", RecordType.PEE, t0 + 2_000L, "m1"),
            record("d", RecordType.POOP, t0 + 3_000L, "m2"),
        )
        assertThat(SuspectedDuplicateGrouping.group(records)).isEmpty()
    }

    @Test
    fun nonWhitelistTypes_neverGroup() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("a", RecordType.SLEEP, t0, "m1"),
            record("b", RecordType.SLEEP, t0 + 1_000L, "m2"),
            record("c", RecordType.DIARY, t0, "m1"),
            record("d", RecordType.DIARY, t0 + 1_000L, "m2"),
            record("e", RecordType.CUSTOM, t0, "m1"),
            record("f", RecordType.CUSTOM, t0 + 1_000L, "m2"),
        )
        assertThat(SuspectedDuplicateGrouping.group(records)).isEmpty()
    }

    @Test
    fun differentBabies_doNotGroup() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("a", RecordType.FORMULA, t0, "m1", babyId = 1),
            record("b", RecordType.FORMULA, t0 + 1_000L, "m2", babyId = 2),
        )
        assertThat(SuspectedDuplicateGrouping.group(records)).isEmpty()
    }

    @Test
    fun transitiveChain_mergesConnectedComponent() {
        // A--29min--B--29min--C with different authors; A and C are 58min apart.
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("a", RecordType.NURSING, t0, "m1"),
            record("b", RecordType.NURSING, t0 + 29 * 60_000L, "m2"),
            record("c", RecordType.NURSING, t0 + 58 * 60_000L, "m3"),
        )
        val groups = SuspectedDuplicateGrouping.group(records)
        assertThat(groups).hasSize(1)
        assertThat(groups.single().memberClientUuids).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun crossDayBoundaryWithinWindow_stillGroups() {
        // Local midnight-ish: two timestamps straddle a calendar day but Δ < 30min.
        val almostMidnight = 1_704_067_140_000L // arbitrary epoch near a day boundary
        val records = listOf(
            record("a", RecordType.BATH, almostMidnight, "m1"),
            record("b", RecordType.BATH, almostMidnight + 10 * 60_000L, "m2"),
        )
        assertThat(SuspectedDuplicateGrouping.group(records)).hasSize(1)
    }

    @Test
    fun deletedRecords_excludedFromGrouping() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("a", RecordType.FORMULA, t0, "m1"),
            record("b", RecordType.FORMULA, t0 + 1_000L, "m2").copy(deletedAt = t0),
        )
        assertThat(SuspectedDuplicateGrouping.group(records)).isEmpty()
    }

    @Test
    fun blankMembership_excluded() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("a", RecordType.FORMULA, t0, ""),
            record("b", RecordType.FORMULA, t0 + 1_000L, "m2"),
        )
        assertThat(SuspectedDuplicateGrouping.group(records)).isEmpty()
    }

    @Test
    fun excludedSourceRelationMembers_doNotFormOpenGroup() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("display", RecordType.FORMULA, t0, "m1"),
            record("source", RecordType.FORMULA, t0 + 1_000L, "m2"),
            record("fresh", RecordType.FORMULA, t0 + 2_000L, "m3"),
        )
        // Resolved pair excluded; fresh alone cannot group.
        val groups = SuspectedDuplicateGrouping.group(
            records,
            excludedClientUuids = setOf("display", "source"),
        )
        assertThat(groups).isEmpty()
    }

    @Test
    fun fulfillmentAuthoredWhitelist_stillGroupsWithPeer() {
        // Plan fulfillment writes ordinary whitelist records; they participate.
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("fulfillment-r", RecordType.FORMULA, t0, "m-member"),
            record("hand-entry", RecordType.FORMULA, t0 + 5_000L, "m-owner"),
        )
        assertThat(SuspectedDuplicateGrouping.group(records)).hasSize(1)
    }

    @Test
    fun pureProjection_doesNotDependOnNoteAmountOrPhotos() {
        val t0 = 1_700_000_000_000L
        val a = record("a", RecordType.FORMULA, t0, "m1").copy(
            note = "different note",
            payloadJson = """{"amount_ml":50}""",
        )
        val b = record("b", RecordType.FORMULA, t0 + 1_000L, "m2").copy(
            note = "other",
            payloadJson = """{"amount_ml":200}""",
        )
        assertThat(SuspectedDuplicateGrouping.group(listOf(a, b))).hasSize(1)
    }

    @Test
    fun groupIdIsDeterministicFromSortedMembers() {
        val members = listOf("uuid-b", "uuid-a")
        val sorted = members.sorted()
        val id1 = SuspectedDuplicateGrouping.deterministicGroupId(sorted)
        val id2 = SuspectedDuplicateGrouping.deterministicGroupId(sorted)
        assertThat(id1).isEqualTo(id2)
        assertThat(id1).hasLength(32)
    }

    private fun record(
        clientUuid: String,
        type: RecordType,
        timestamp: Long,
        membershipId: String,
        babyId: Long = 1L,
    ): Record = Record(
        id = clientUuid.hashCode().toLong().and(0x7fff_ffffL),
        clientUuid = clientUuid,
        babyId = babyId,
        type = type,
        timestamp = timestamp,
        payloadJson = "{}",
        updatedAt = timestamp,
        createdByMembershipId = membershipId,
    )
}

@RunWith(Parameterized::class)
class SuspectedDuplicateWindowEdgeCases(
    private val deltaMinutes: Long,
    private val expectGroup: Boolean,
) {
    @Test
    fun windowEdge() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            Record(
                id = 1,
                clientUuid = "a",
                babyId = 1,
                type = RecordType.TEMPERATURE,
                timestamp = t0,
                payloadJson = "{}",
                updatedAt = t0,
                createdByMembershipId = "m1",
            ),
            Record(
                id = 2,
                clientUuid = "b",
                babyId = 1,
                type = RecordType.TEMPERATURE,
                timestamp = t0 + deltaMinutes * 60_000L,
                payloadJson = "{}",
                updatedAt = t0,
                createdByMembershipId = "m2",
            ),
        )
        val groups = SuspectedDuplicateGrouping.group(records)
        if (expectGroup) {
            assertThat(groups).hasSize(1)
        } else {
            assertThat(groups).isEmpty()
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "delta={0}min expectGroup={1}")
        fun data(): Collection<Array<Any>> = listOf(
            arrayOf(29L, true),
            arrayOf(30L, true),
            arrayOf(31L, false),
        )
    }
}
