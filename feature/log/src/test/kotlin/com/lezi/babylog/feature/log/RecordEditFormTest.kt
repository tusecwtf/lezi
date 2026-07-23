package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordEditFormTest {
    @Test
    fun formulaRoundTripPreservesPreparedAmountAndDuration() {
        val form = EditForm.fromRecord(
            record(
                RecordType.FORMULA,
                """{"amount_ml":120,"prepared_ml":135,"duration_min":12}""",
            ),
        )

        assertEquals(120, form.amountMl)
        assertEquals(135, form.preparedMl)
        assertEquals(12, form.feedingDurationMin)
        assertEquals(
            """{"amount_ml":120,"prepared_ml":135,"duration_min":12}""",
            form.toPayload(),
        )
    }

    @Test
    fun sleepRoundTripPreservesNapAndAnomalyFlags() {
        val form = EditForm.fromRecord(
            record(RecordType.SLEEP, """{"is_nap":true,"anomaly_flag":true}"""),
        )

        assertTrue(form.isNap)
        assertTrue(form.anomalyFlag)
        assertEquals("""{"is_nap":true,"anomaly_flag":true}""", form.toPayload())
    }

    @Test
    fun textRoundTripPreservesBodyAndPhotoUris() {
        val form = EditForm.fromRecord(
            record(
                RecordType.DIARY,
                """{"body":"第一次翻身","photos":["content://one","content://two"]}""",
            ),
        )

        assertEquals("第一次翻身", form.body)
        assertEquals(listOf("content://one", "content://two"), form.photoUris)
        assertEquals(
            """{"body":"第一次翻身","photos":["content://one","content://two"]}""",
            form.toPayload(),
        )
    }

    @Test
    fun unsupportedFullEditorFieldsKeepTypedQuickRecordPayload() {
        val payload = """{"severity":3,"description":"夜间连续"}"""
        val form = EditForm.fromRecord(record(RecordType.COUGH, payload))

        assertEquals(payload, form.toPayload())
    }

    private fun record(type: RecordType, payload: String) = Record(
        id = 9L,
        clientUuid = "record-9",
        babyId = 2L,
        type = type,
        timestamp = 10_000L,
        createdByUserId = 1L,
        payloadJson = payload,
        updatedAt = 10_000L,
    )
}
