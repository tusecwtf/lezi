package com.lezi.gf.care

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.kernel.FixedClock
import com.lezi.gf.kernel.GfResult
import org.junit.Test

class PayloadValidationAndMergeTest {
    @Test
    fun rejectInvalidPeeTempSleepMilk() {
        assertThat(PayloadValidation.validate("pee", """{"pee_amount":9}""")).isNotNull()
        assertThat(PayloadValidation.validate("pee", """{"pee_amount":2}""")).isNull()
        assertThat(PayloadValidation.validate("temperature", """{"celsius":50}""")).isNotNull()
        assertThat(PayloadValidation.validate("temperature", """{"celsius":36.6}""")).isNull()
        assertThat(
            PayloadValidation.validate(
                "sleep",
                """{"open":false,"start_ms":2000,"end_ms":1000}""",
            ),
        ).isNotNull()
        assertThat(
            PayloadValidation.validate(
                "sleep",
                """{"open":false,"start_ms":1000,"end_ms":2000,"duration_minutes":1}""",
            ),
        ).isNull()
        assertThat(PayloadValidation.validate("formula", """{"amount_ml":-5}""")).isNotNull()
        assertThat(PayloadValidation.validate("formula", """{"amount_ml":80}""")).isNull()
        assertThat(PayloadValidation.validate("not_a_type", "{}")).isNotNull()
    }

    @Test
    fun careServiceConfirmCreateUsesPayloadValidation() {
        val care = CareService()
        val bad = care.openComposer(RecordType.PEE, "b").copy(
            payloadJson = """{"pee_amount":99}""",
            dirty = true,
        )
        assertThat(care.confirmCreate(bad)).isInstanceOf(GfResult.Err::class.java)
        val ok = care.openComposer(RecordType.PEE, "b").copy(
            payloadJson = """{"pee_amount":2}""",
            dirty = true,
        )
        assertThat(care.confirmCreate(ok)).isInstanceOf(GfResult.Ok::class.java)
    }

    @Test
    fun lwwMergeRecordsByUpdatedAt() {
        val a = CareRecord(
            clientUuid = "same",
            babyClientUuid = "b",
            typeKey = "pee",
            timestampMs = 1,
            note = "local",
            updatedAtMs = 100,
        )
        val b = CareRecord(
            clientUuid = "same",
            babyClientUuid = "b",
            typeKey = "pee",
            timestampMs = 1,
            note = "remote",
            updatedAtMs = 200,
        )
        val merged = EntityMerge.mergeRecords(listOf(a), listOf(b))
        assertThat(merged).hasSize(1)
        assertThat(merged[0].note).isEqualTo("remote")
        // older remote must not win
        val kept = EntityMerge.mergeRecords(listOf(b), listOf(a))
        assertThat(kept[0].note).isEqualTo("remote")
        assertThat(kept[0].updatedAtMs).isEqualTo(200)
    }

    @Test
    fun equalUpdatedAtPrefersIncoming() {
        val local = CareRecord("u", "b", "pee", 1, note = "L", updatedAtMs = 50)
        val inc = CareRecord("u", "b", "pee", 1, note = "I", updatedAtMs = 50)
        val w = EntityMerge.pickByUpdatedAt(local, inc, { it.updatedAtMs })
        assertThat(w.note).isEqualTo("I")
    }

    @Test
    fun openSleepDoesNotCountUntilWake() {
        val clock = FixedClock(1_700_000_000_000L)
        val care = CareService(clock = clock)
        care.confirmCreate(
            care.openComposer(RecordType.SLEEP, "b").copy(
                payloadJson = care.fallAsleepPayload(clock.nowEpochMs()),
                dirty = true,
            ),
        )
        val day = CareAggregation.dayStartMs(clock.nowEpochMs())
        assertThat(care.daySummary("b", day).sleepMinutes).isEqualTo(0)
    }
}
