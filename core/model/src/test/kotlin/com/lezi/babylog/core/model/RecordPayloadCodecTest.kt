package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordPayloadCodecTest {
    @Test
    fun everyRecordTypeDecodesToItsMatchingTypedPayload() {
        val documents = RecordType.entries.associateWith { type ->
            RecordPayloadCodec.decode(type, validV1Json(type), 1)
        }

        assertThat(documents).hasSize(RecordType.entries.size)
        documents.forEach { (type, document) ->
            assertThat(document.type).isEqualTo(type)
            assertThat(document.payload.type).isEqualTo(type)
            assertThat(document.isUnknown).isFalse()
        }
    }

    @Test
    fun versionOneExtensionsSurviveAnEditedVersionTwoWrite() {
        val source = RecordPayloadCodec.decode(
            RecordType.FORMULA,
            """{"amount_ml":120,"future":{"v":2},"photos":["a.jpg"]}""",
            1,
        )
        val edited = source.copy(
            payload = MilkPayload(RecordType.FORMULA, amountMl = 135),
            schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )

        val encoded = RecordPayloadCodec.encode(edited)
        val roundTrip = RecordPayloadCodec.decode(
            RecordType.FORMULA,
            encoded,
            CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )

        assertThat(encoded).contains("\"amount_ml\":135")
        assertThat(encoded).contains("\"future\":{\"v\":2}")
        assertThat(encoded).contains("\"photos\":[\"a.jpg\"]")
        assertThat(roundTrip.extensions.keys).containsExactly("future", "photos")
    }

    @Test
    fun unknownScalarObjectAndArrayFieldsSurviveTypedEdit() {
        val source = RecordPayloadCodec.decode(
            RecordType.SLEEP,
            """{"is_nap":true,"scalar":"kept","object":{"v":2},"array":[1,2]}""",
            1,
        )
        val edited = source.copy(
            payload = SleepPayload(isNap = false, anomaly = true),
            schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )

        val encoded = RecordPayloadCodec.encode(edited)
        val roundTrip = RecordPayloadCodec.decode(
            RecordType.SLEEP,
            encoded,
            CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )

        assertThat(encoded).contains("\"is_nap\":false")
        assertThat(encoded).contains("\"anomaly_flag\":true")
        assertThat(encoded).contains("\"scalar\":\"kept\"")
        assertThat(encoded).contains("\"object\":{\"v\":2}")
        assertThat(encoded).contains("\"array\":[1,2]")
        assertThat(roundTrip.extensions.keys)
            .containsExactly("scalar", "object", "array")
    }

    @Test
    fun malformedAndFuturePayloadsRemainOpaqueAndByteStable() {
        val malformed = """{"amount_ml":"""
        val future = """{"new_shape":[1,2,3]}"""

        val malformedDocument = RecordPayloadCodec.decode(RecordType.FORMULA, malformed, 1)
        val futureDocument = RecordPayloadCodec.decode(RecordType.FORMULA, future, 99)

        assertThat(malformedDocument.payload).isInstanceOf(UnknownPayload::class.java)
        assertThat(futureDocument.payload).isInstanceOf(UnknownPayload::class.java)
        assertThat(RecordPayloadCodec.encode(malformedDocument)).isEqualTo(malformed)
        assertThat(RecordPayloadCodec.encode(futureDocument)).isEqualTo(future)
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
    fun localPhotoPathsReplicaRoundTripsAndClears() {
        assertThat(localPhotoPaths("""{"body":"x","photos":["a.jpg","b.jpg"]}"""))
            .containsExactly("a.jpg", "b.jpg")
            .inOrder()
        assertThat(localPhotoPaths("""{"amount_ml":120}""")).isEmpty()
        assertThat(MAX_RECORD_PHOTOS).isEqualTo(3)

        val withPhotos = withLocalPhotoPaths("""{"pee_amount":2}""", listOf("p1.jpg", "p1.jpg", ""))
        assertThat(withPhotos).contains("\"photos\"")
        assertThat(localPhotoPaths(withPhotos)).containsExactly("p1.jpg")

        val cleared = withLocalPhotoPaths(withPhotos, emptyList())
        assertThat(cleared).doesNotContain("photos")
        assertThat(cleared).contains("\"pee_amount\":2")
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

    private fun validV1Json(type: RecordType): String = when (type) {
        RecordType.NURSING -> """{"left_min":1,"right_min":2}"""
        RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS ->
            """{"amount_ml":120}"""
        RecordType.PEE -> """{"pee_amount":2}"""
        RecordType.POOP ->
            """{"stool_amount":3,"stool_consistency":3,"stool_color":2}"""
        RecordType.BOTH_DIAPER ->
            """{"pee_amount":2,"stool_amount":3,"stool_consistency":3,"stool_color":2}"""
        RecordType.SLEEP -> """{"is_nap":false}"""
        RecordType.TEMPERATURE -> """{"celsius":36.8}"""
        RecordType.MEMO, RecordType.DIARY -> """{"body":"正文"}"""
        RecordType.BATH, RecordType.WALK -> "{}"
        RecordType.COUGH, RecordType.RASH, RecordType.VOMIT, RecordType.INJURY ->
            """{"severity":2}"""
        RecordType.MEDICINE -> """{"name":"药"}"""
        RecordType.HOSPITAL -> """{"reason":"复诊"}"""
        RecordType.OTHER -> """{"title":"其他"}"""
        RecordType.HEIGHT, RecordType.HEAD, RecordType.CHEST, RecordType.FOOT_SIZE ->
            """{"value":66.5,"unit":"cm"}"""
        RecordType.WEIGHT -> """{"value":6350,"unit":"g"}"""
        RecordType.BABY_FOOD, RecordType.SNACK, RecordType.DRINK ->
            """{"content":"内容"}"""
        RecordType.VACCINE -> """{"name":"乙肝"}"""
        RecordType.CUSTOM -> """{"title":"自定义"}"""
    }
}
