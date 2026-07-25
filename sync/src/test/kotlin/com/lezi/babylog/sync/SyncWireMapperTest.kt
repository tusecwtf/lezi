package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class SyncWireMapperTest {
    @Test
    fun recordUsesPortableBabyIdAndObjectPayloadWithoutLocalPhotoPaths() {
        val entity = RecordEntity(
            clientUuid = "record-uuid",
            babyId = 7,
            type = "diary",
            timestamp = 123,
            note = "今天",
            createdByUserId = 1,
            payloadJson = """{"body":"好","photos":["/data/user/0/lezi/files/private.jpg"],"future":{"v":2}}""",
            schemaVersion = 2,
            updatedAt = 456,
        )

        val wire = SyncWireMapper.record(
            entity,
            babyClientUuid = "baby-uuid",
            createdByDeviceId = "device-a",
        )
        val payload = Json.parseToJsonElement(wire.payloadJson).jsonObject

        assertThat(payload["baby_client_uuid"].toString()).isEqualTo("\"baby-uuid\"")
        assertThat(payload["created_by_device_id"].toString()).isEqualTo("\"device-a\"")
        assertThat(payload["baby_id"]).isNull()
        assertThat(payload["schema_version"].toString()).isEqualTo("2")
        assertThat(payload["payload_json"]).isInstanceOf(
            kotlinx.serialization.json.JsonObject::class.java,
        )
        assertThat(payload["payload_json"].toString()).contains("\"body\":\"好\"")
        assertThat(payload["payload_json"].toString()).contains("\"future\":{\"v\":2}")
        assertThat(payload["payload_json"].toString()).doesNotContain("private.jpg")
    }

    @Test
    fun pulledRecordAcceptsObjectAndLegacyStringPayloads() {
        val objectPayload = Json.parseToJsonElement(
            """{"payload_json":{"amount_ml":120},"schema_version":2}""",
        ).jsonObject
        val legacyPayload = Json.parseToJsonElement(
            """{"payload_json":"{\"amount_ml\":90}"}""",
        ).jsonObject

        assertThat(SyncWireMapper.recordPayloadJson(objectPayload))
            .isEqualTo("""{"amount_ml":120}""")
        assertThat(SyncWireMapper.recordSchemaVersion(objectPayload)).isEqualTo(2)
        assertThat(SyncWireMapper.recordPayloadJson(legacyPayload))
            .isEqualTo("""{"amount_ml":90}""")
    }

    @Test
    fun babyAndMediaNeverExposeLocalIdsOrPaths() {
        val baby = BabyEntity(
            id = 42,
            familyId = 9,
            nickname = "年年",
            birthdayEpochDay = 20_000,
            themeColorArgb = 0,
            clientUuid = "baby-uuid",
            updatedAt = 100,
        )
        val media = MediaAssetEntity(
            id = 8,
            recordId = 11,
            clientUuid = "media-uuid",
            kind = "log",
            localUri = "/private/photo.jpg",
            mime = "image/jpeg",
            byteSize = 12,
            createdAt = 100,
        )

        val babyPayload = SyncWireMapper.baby(baby, "avatar-uuid").payloadJson
        val mediaPayload = SyncWireMapper.media(
            media,
            recordClientUuid = "record-uuid",
            babyClientUuid = "baby-uuid",
        ).payloadJson

        assertThat(babyPayload).doesNotContain("\"id\"")
        assertThat(babyPayload).doesNotContain("familyId")
        assertThat(mediaPayload).contains("\"record_client_uuid\":\"record-uuid\"")
        assertThat(mediaPayload).doesNotContain("/private/photo.jpg")
    }
}
