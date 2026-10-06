package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

class SuspectedDuplicateGroupingTest {

    @Test
    fun allRecordTypesParticipate() {
        RecordType.entries.forEach { type ->
            assertThat(SuspectedDuplicateGrouping.participates(type)).isTrue()
            assertThat(SuspectedDuplicateGrouping.participatesWireKey(type.key)).isTrue()
        }
        assertThat(SuspectedDuplicateGrouping.participatesWireKey("not-a-record")).isFalse()
    }

    @Test
    fun windowIsThirtyMinutesInclusive() {
        assertThat(SuspectedDuplicateGrouping.WINDOW_MS).isEqualTo(1_800_000L)
    }

    @Test
    fun sameMembershipMultiDevice_groups() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("a", RecordType.FORMULA, t0, "m-same"),
            record("b", RecordType.FORMULA, t0 + 5 * 60_000L, "m-same"),
            record("c", RecordType.FORMULA, t0 + 10 * 60_000L, "m-same"),
        )
        assertThat(SuspectedDuplicateGrouping.group(records).single().memberClientUuids)
            .containsExactly("a", "b", "c")
    }

    @Test
    fun differentExactTypes_doNotGroupEvenWithinWindow() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("a", RecordType.FORMULA, t0, "m1"),
            record("b", RecordType.PUMPED_FEED, t0 + 1_000L, "m2"),
            record("c", RecordType.PEE, t0 + 2_000L, "m1"),
            record("d", RecordType.POOP, t0 + 3_000L, "m2"),
            record("e", RecordType.PEE, t0 + 4_000L, "m2"),
            record("f", RecordType.BOTH_DIAPER, t0 + 5_000L, "m1"),
        )
        val groups = SuspectedDuplicateGrouping.group(records)
        assertThat(groups).hasSize(1)
        assertThat(groups.single().memberClientUuids).containsExactly("c", "e")
    }

    @Test
    fun sleepAndCustomWithSameItem_group() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("a", RecordType.SLEEP, t0, "m1"),
            record("b", RecordType.SLEEP, t0 + 1_000L, "m2"),
            record(
                "c",
                RecordType.CUSTOM,
                t0,
                "m1",
                payloadJson = """{"title":"抚触","custom_item_id":3}""",
            ),
            record(
                "d",
                RecordType.CUSTOM,
                t0 + 1_000L,
                "m2",
                payloadJson = """{"title":"抚触","custom_item_id":3}""",
            ),
        )
        val groups = SuspectedDuplicateGrouping.group(records)
        assertThat(groups.map { it.memberClientUuids }).containsExactly(
            listOf("a", "b"),
            listOf("c", "d"),
        )
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
    fun sameAuthorNeighborsConnectWithoutCrossAuthorBridge() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("a1", RecordType.FORMULA, t0, "author-a"),
            record("a2", RecordType.FORMULA, t0 + 20 * 60_000L, "author-a"),
            record("b", RecordType.FORMULA, t0 + 10 * 60_000L, "author-b"),
        )

        assertThat(SuspectedDuplicateGrouping.group(records).single().memberClientUuids)
            .containsExactly("a1", "a2", "b")
    }

    @Test
    fun sparseUnionMatchesExhaustivePairGraphOracle() {
        val base = 1_700_000_000_000L
        val authorCount = 3
        val timestampChoices = longArrayOf(0L, 30L, 31L, 60L)
        for (size in 2..5) {
            val authorAssignments = intPower(authorCount, size)
            val timestampAssignments = intPower(timestampChoices.size, size)
            repeat(authorAssignments) { authorCode ->
                repeat(timestampAssignments) { timestampCode ->
                    val records = List(size) { index ->
                        record(
                            clientUuid = "r$index",
                            type = RecordType.FORMULA,
                            timestamp = base + timestampChoices[
                                digit(timestampCode, timestampChoices.size, index).toInt()
                            ] * 60_000L,
                            membershipId = "m${digit(authorCode, authorCount, index)}",
                        )
                    }
                    val expected = pairGraphComponents(records)
                        .filter { it.size > 1 }
                        .map(List<String>::sorted)
                        .sortedBy { it.joinToString("\u0000") }
                    val actual = SuspectedDuplicateGrouping.group(records)
                        .map(SuspectedDuplicateGroup::memberClientUuids)
                        .sortedBy { it.joinToString("\u0000") }
                    assertThat(actual).isEqualTo(expected)
                }
            }
        }
    }

    @Test
    fun crossDayBoundaryWithinWindow_stillGroups() {
        val almostMidnight = 1_704_067_140_000L
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
        val groups = SuspectedDuplicateGrouping.group(
            records,
            excludedClientUuids = setOf("display", "source"),
        )
        assertThat(groups).isEmpty()
    }

    @Test
    fun fulfillmentAuthoredRecords_stillGroupsWithPeer() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("fulfillment-r", RecordType.FORMULA, t0, "m-member"),
            record("hand-entry", RecordType.FORMULA, t0 + 5_000L, "m-owner"),
        )
        assertThat(SuspectedDuplicateGrouping.group(records)).hasSize(1)
    }

    @Test
    fun differentMedicineNames_doNotGroup() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record(
                "ibu",
                RecordType.MEDICINE,
                t0,
                "m1",
                payloadJson = """{"name":"布洛芬"}""",
            ),
            record(
                "ace",
                RecordType.MEDICINE,
                t0 + 1_000L,
                "m2",
                payloadJson = """{"name":"对乙酰氨基酚"}""",
            ),
        )
        assertThat(SuspectedDuplicateGrouping.group(records)).isEmpty()
    }

    @Test
    fun medicineNameShard_isNormalized() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record(
                "a",
                RecordType.MEDICINE,
                t0,
                "m1",
                payloadJson = """{"name":"  Ibuprofen  "}""",
            ),
            record(
                "b",
                RecordType.MEDICINE,
                t0 + 1_000L,
                "m2",
                payloadJson = """{"name":"ibuprofen"}""",
            ),
        )
        assertThat(SuspectedDuplicateGrouping.group(records)).hasSize(1)
    }

    @Test
    fun emptyMedicineNames_doNotMergeWithEachOther() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("a", RecordType.MEDICINE, t0, "m1", payloadJson = """{"name":"  "}"""),
            record("b", RecordType.MEDICINE, t0 + 1_000L, "m2", payloadJson = """{"name":""}"""),
        )
        assertThat(SuspectedDuplicateGrouping.group(records)).isEmpty()
    }

    @Test
    fun differentCustomItems_doNotGroup() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record(
                "a",
                RecordType.CUSTOM,
                t0,
                "m1",
                payloadJson = """{"title":"抚触","custom_item_id":1}""",
            ),
            record(
                "b",
                RecordType.CUSTOM,
                t0 + 1_000L,
                "m2",
                payloadJson = """{"title":"游泳","custom_item_id":2}""",
            ),
        )
        assertThat(SuspectedDuplicateGrouping.group(records)).isEmpty()
    }

    @Test
    fun peeDoesNotGroupWithBothDiaper() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("a", RecordType.PEE, t0, "m1"),
            record("b", RecordType.BOTH_DIAPER, t0 + 1_000L, "m2"),
        )
        assertThat(SuspectedDuplicateGrouping.group(records)).isEmpty()
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
        payloadJson: String = "{}",
    ): Record = Record(
        id = clientUuid.hashCode().toLong().and(0x7fff_ffffL),
        clientUuid = clientUuid,
        babyId = babyId,
        type = type,
        timestamp = timestamp,
        payloadJson = payloadJson,
        updatedAt = timestamp,
        createdByMembershipId = membershipId,
    )

    private fun pairGraphComponents(records: List<Record>): List<List<String>> {
        val neighbors = Array(records.size) { mutableListOf<Int>() }
        for (left in records.indices) {
            for (right in left + 1 until records.size) {
                if (
                    kotlin.math.abs(records[left].timestamp - records[right].timestamp) <=
                    SuspectedDuplicateGrouping.WINDOW_MS
                ) {
                    neighbors[left] += right
                    neighbors[right] += left
                }
            }
        }
        val seen = BooleanArray(records.size)
        return records.indices.mapNotNull { start ->
            if (seen[start]) return@mapNotNull null
            val queue = ArrayDeque<Int>()
            val component = mutableListOf<String>()
            seen[start] = true
            queue += start
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component += records[current].clientUuid
                neighbors[current].forEach { neighbor ->
                    if (!seen[neighbor]) {
                        seen[neighbor] = true
                        queue += neighbor
                    }
                }
            }
            component
        }
    }

    private fun digit(value: Int, radix: Int, position: Int): Long {
        var remaining = value
        repeat(position) { remaining /= radix }
        return (remaining % radix).toLong()
    }

    private fun intPower(base: Int, exponent: Int): Int {
        var result = 1
        repeat(exponent) { result *= base }
        return result
    }
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
