package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordPayloadCodecTest {
    @Test
    fun newFactAndPlanModelsDefaultToCurrentPayloadSchema() {
        val record = Record(
            clientUuid = "record",
            babyId = 1,
            type = RecordType.PEE,
            timestamp = 1_000,
            updatedAt = 1_000,
        )
        val plan = CarePlan(
            clientUuid = "plan",
            babyId = 1,
            type = RecordType.PEE,
            scheduledAt = 2_000,
            scheduledZoneId = "Asia/Shanghai",
            updatedAt = 1_000,
        )

        assertThat(record.schemaVersion).isEqualTo(CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION)
        assertThat(plan.schemaVersion).isEqualTo(CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION)
    }

    @Test
    fun everyRecordTypeDecodesToItsMatchingTypedPayload() {
        val documents = RecordType.entries.associateWith { type ->
            RecordPayloadCodec.decode(
                type,
                validV2Json(type),
                CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
            )
        }

        assertThat(documents).hasSize(RecordType.entries.size)
        documents.forEach { (type, document) ->
            assertThat(document.type).isEqualTo(type)
            assertThat(document.payload.type).isEqualTo(type)
            assertThat(document.isUnknown).isFalse()
        }
    }

    @Test
    fun versionOnePayloadRemainsOpaqueAndByteStable() {
        val rawJson = """{"amount_ml":120,"future":{"v":1}}"""

        val document = RecordPayloadCodec.decode(RecordType.FORMULA, rawJson, 1)

        assertThat(document.payload).isInstanceOf(UnknownPayload::class.java)
        assertThat(document.schemaVersion).isEqualTo(1)
        assertThat(RecordPayloadCodec.encode(document)).isEqualTo(rawJson)
    }

    @Test
    fun currentUnknownFieldsFailClosedAndRemainByteStable() {
        val rawJson =
            """{"amount_ml":120,"future":{"v":2},"photos":["a.jpg"]}"""
        val document = RecordPayloadCodec.decode(
            RecordType.FORMULA,
            rawJson,
            CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )

        assertThat(document.payload).isInstanceOf(UnknownPayload::class.java)
        assertThat(RecordPayloadCodec.encode(document)).isEqualTo(rawJson)
    }

    @Test
    fun removedTemperatureAliasAndDiaryPhotoFieldFailClosed() {
        val temperature = RecordPayloadCodec.decode(
            RecordType.TEMPERATURE,
            """{"value":36.8}""",
            CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )
        val diary = RecordPayloadCodec.decode(
            RecordType.DIARY,
            """{"body":"正文","photos":["a.jpg"]}""",
            CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )

        assertThat(temperature.payload).isInstanceOf(UnknownPayload::class.java)
        assertThat(diary.payload).isInstanceOf(UnknownPayload::class.java)
    }

    @Test
    fun missingRequiredCurrentFieldsFailClosed() {
        val missing = listOf(
            RecordType.NURSING,
            RecordType.FORMULA,
            RecordType.PUMPED_FEED,
            RecordType.SLEEP,
            RecordType.TEMPERATURE,
            RecordType.DIARY,
            RecordType.MEDICINE,
            RecordType.HOSPITAL,
            RecordType.HEIGHT,
            RecordType.BABY_FOOD,
            RecordType.VACCINE,
            RecordType.CUSTOM,
        )

        missing.forEach { type ->
            val document = RecordPayloadCodec.decode(
                type,
                "{}",
                CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
            )
            assertThat(document.payload).isInstanceOf(UnknownPayload::class.java)
        }
    }

    @Test
    fun malformedAndFuturePayloadsRemainOpaqueAndByteStable() {
        val malformed = """{"amount_ml":"""
        val future = """{"new_shape":[1,2,3]}"""

        val malformedDocument = RecordPayloadCodec.decode(
            RecordType.FORMULA,
            malformed,
            CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )
        val futureDocument = RecordPayloadCodec.decode(RecordType.FORMULA, future, 99)

        assertThat(malformedDocument.payload).isInstanceOf(UnknownPayload::class.java)
        assertThat(futureDocument.payload).isInstanceOf(UnknownPayload::class.java)
        assertThat(RecordPayloadCodec.encode(malformedDocument)).isEqualTo(malformed)
        assertThat(RecordPayloadCodec.encode(futureDocument)).isEqualTo(future)
    }

    @Test
    fun currentCustomPayloadRequiresConcreteDefinitionIdentity() {
        val bare = """{"title":"仅标题"}"""

        val document = RecordPayloadCodec.decode(
            RecordType.CUSTOM,
            bare,
            CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )

        assertThat(document.payload).isInstanceOf(UnknownPayload::class.java)
        assertThat(RecordPayloadCodec.encode(document)).isEqualTo(bare)
    }

    @Test
    fun documentRejectsMismatchedRecordType() {
        val failure = runCatching {
            RecordPayloadDocument(
                type = RecordType.PEE,
                payload = MilkPayload(RecordType.FORMULA, 120),
                schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun typedValidationOwnsMilkAndMeasurementBounds() {
        assertThat(
            RecordPayloadCodec.validate(
                MilkPayload(
                    type = RecordType.FORMULA,
                    amountMl = 120,
                    preparedMl = 1_000,
                    durationMinutes = 1_441,
                ),
            ),
        ).containsExactly(
            "冲调量需在 0–999 ml 之间",
            "时长需在 0–1440 分钟之间",
        ).inOrder()

        assertThat(
            RecordPayloadCodec.validate(
                MeasurementPayload(
                    type = RecordType.WEIGHT,
                    value = 101_000.0,
                    unit = "g",
                ),
            ),
        ).containsExactly("体重需在 0–100 kg 之间")
    }

    private fun validV2Json(type: RecordType): String = when (type) {
        RecordType.NURSING ->
            """{"left_min":1,"right_min":2,"order":"LR","record_mode":"end"}"""
        RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS ->
            """{"amount_ml":120}"""
        RecordType.PEE -> """{"pee_amount":2}"""
        RecordType.POOP ->
            """{"stool_amount":3,"stool_consistency":3,"stool_color":2}"""
        RecordType.BOTH_DIAPER ->
            """{"pee_amount":2,"stool_amount":3,"stool_consistency":3,"stool_color":2}"""
        RecordType.SLEEP -> """{"anomaly_flag":false}"""
        RecordType.TEMPERATURE -> """{"celsius":36.8}"""
        RecordType.DIARY -> """{"body":"正文"}"""
        RecordType.BATH, RecordType.WALK -> "{}"
        RecordType.COUGH, RecordType.RASH, RecordType.VOMIT, RecordType.INJURY ->
            """{"severity":2}"""
        RecordType.MEDICINE -> """{"name":"药"}"""
        RecordType.HOSPITAL -> """{"reason":"复诊"}"""
        RecordType.HEIGHT, RecordType.HEAD, RecordType.CHEST, RecordType.FOOT_SIZE ->
            """{"value":66.5,"unit":"cm"}"""
        RecordType.WEIGHT -> """{"value":6350,"unit":"g"}"""
        RecordType.BABY_FOOD, RecordType.SNACK, RecordType.DRINK ->
            """{"content":"内容"}"""
        RecordType.VACCINE -> """{"name":"乙肝"}"""
        RecordType.CUSTOM -> """{"title":"自定义","custom_item_id":1}"""
    }
}
