package com.lezi.babylog.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordSummaryTest {
    @Test
    fun milkSummaryIncludesOptionalPreparedAmountAndDuration() {
        assertEquals(
            "120ml · 冲调量150ml · 耗时 8m",
            milkRecord(
                MilkPayload(
                    type = RecordType.FORMULA,
                    amountMl = 120,
                    preparedMl = 150,
                    durationMinutes = 8,
                ),
            ).payloadSummary(),
        )
        assertEquals(
            "120ml · 耗时 1h5m",
            milkRecord(
                MilkPayload(
                    type = RecordType.FORMULA,
                    amountMl = 120,
                    durationMinutes = 65,
                ),
            ).payloadSummary(),
        )
    }

    @Test
    fun milkSummaryKeepsTheCompactLegacyAmountWhenOptionalFieldsAreAbsent() {
        assertEquals(
            "120ml",
            milkRecord(MilkPayload(RecordType.PUMPED_FEED, amountMl = 120)).payloadSummary(),
        )
    }

    private fun milkRecord(payload: MilkPayload): Record = Record(
        clientUuid = "record-1",
        babyId = 1L,
        type = payload.type,
        timestamp = 1L,
        payloadJson = RecordPayloadCodec.encode(
            RecordPayloadDocument(
                type = payload.type,
                payload = payload,
                schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
            ),
        ),
        updatedAt = 1L,
    )
}
