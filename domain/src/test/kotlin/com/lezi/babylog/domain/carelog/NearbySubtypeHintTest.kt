package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import org.junit.Test

class NearbySubtypeHintTest {
    @Test
    fun peeAndBothDiaper_hintEachOtherWithoutGrouping() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("pee", RecordType.PEE, t0),
            record("both", RecordType.BOTH_DIAPER, t0 + 60_000L),
        )
        assertThat(SuspectedDuplicateGrouping.group(records)).isEmpty()
        val hints = NearbySubtypeHint.hints(records)
        assertThat(hints["pee"]).isEqualTo("附近还有尿+便")
        assertThat(hints["both"]).isEqualTo("附近还有尿尿")
    }

    @Test
    fun formulaAndNursing_hintEachOther() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("formula", RecordType.FORMULA, t0),
            record("nursing", RecordType.NURSING, t0 + 5_000L),
        )
        assertThat(SuspectedDuplicateGrouping.group(records)).isEmpty()
        assertThat(NearbySubtypeHint.hints(records)["formula"]).isEqualTo("附近还有母乳")
    }

    @Test
    fun hiddenSource_doesNotHint() {
        val t0 = 1_700_000_000_000L
        val records = listOf(
            record("pee", RecordType.PEE, t0),
            record("both", RecordType.BOTH_DIAPER, t0 + 1_000L),
        )
        assertThat(NearbySubtypeHint.hints(records, hiddenClientUuids = setOf("both"))).isEmpty()
    }

    private fun record(
        clientUuid: String,
        type: RecordType,
        timestamp: Long,
    ): Record = Record(
        id = clientUuid.hashCode().toLong().and(0x7fff_ffffL),
        clientUuid = clientUuid,
        babyId = 1,
        type = type,
        timestamp = timestamp,
        payloadJson = "{}",
        updatedAt = timestamp,
        createdByMembershipId = "m1",
    )
}
