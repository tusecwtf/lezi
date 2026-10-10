package com.lezi.babylog.feature.log.composer

import com.lezi.babylog.core.model.*
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.MilkPayload
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordPayloadDocument
import com.lezi.babylog.core.model.RecordType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal val QuickRecordSaveCommand.payloadJsonForTest: String
    get() = RecordPayloadCodec.encode(
        RecordPayloadDocument(type, payload(), CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION),
    )

class TypedCareCommandSerializationTest {
    @Test
    fun newCommandsRestoreTypedPayloadWithoutRawJson() {
        val original = QuickRecordDraft.create(RecordType.FORMULA, 1_700_000_000_000L)
            .copy(amountMl = 135).toSaveCommand()
        val bytes = ByteArrayOutputStream().also { buffer ->
            ObjectOutputStream(buffer).use { it.writeObject(original) }
        }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use {
            it.readObject() as QuickRecordSaveCommand
        }
        assertNull(restored.payloadJson)
        assertEquals(MilkPayload(RecordType.FORMULA, amountMl = 135), restored.payload())
        assertEquals(original.timestamp, restored.timestamp)
    }

    @Test
    fun actualPreTypedSerializedCommandRestoresThroughLegacyAdapter() {
        val encoded = requireNotNull(javaClass.classLoader!!.getResourceAsStream("legacy-care-command-v1.b64"))
            .bufferedReader().use { it.readText().trim() }
        val restored = ObjectInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use {
            it.readObject() as QuickRecordSaveCommand
        }
        assertEquals(MilkPayload(RecordType.FORMULA, amountMl = 135), restored.payload())
        assertEquals(1_700_000_000_000L, restored.timestamp)
        assertEquals("legacy note", restored.note)
    }

    @Test
    fun everyPayloadVariantRestoresItsTypedFields() {
        val payloads = listOf(
            NursingPayload(2, 3, "LR", 15, "start"),
            MilkPayload(RecordType.FORMULA, 120, 135, 8),
            PeePayload(1), StoolPayload(2, 3, 4), BothDiaperPayload(1, 2, 3, 4),
            SleepPayload(true, true), TemperaturePayload(37.2),
            TextPayload(RecordType.DIARY, "日记"), EmptyPayload(RecordType.BATH),
            SymptomPayload(RecordType.COUGH, 2, "描述"),
            MedicinePayload("名称", "剂量"), HospitalPayload("原因", "建议"),
            MeasurementPayload(RecordType.WEIGHT, 6350.0, "g"),
            FoodPayload(RecordType.BABY_FOOD, "南瓜", "50g"),
            VaccinePayload("名称", "批号"), CustomPayload("项目", "内容", 42, 3),
            UnknownPayload(RecordType.BATH, "{}", 99, "synthetic future version"),
        )
        for (payload in payloads) {
            val command = QuickRecordSaveCommand(
                existingRecordId = null, type = payload.type, timestamp = 100,
                endTimestamp = null, note = null, typedPayload = payload,
            )
            val bytes = ByteArrayOutputStream().also { buffer ->
                ObjectOutputStream(buffer).use { it.writeObject(command) }
            }.toByteArray()
            val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use {
                it.readObject() as QuickRecordSaveCommand
            }
            assertEquals(payload, restored.payload())
            assertNull(restored.payloadJson)
        }
    }
}
