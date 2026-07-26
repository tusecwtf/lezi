package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            createdByMembershipId = "membership-a",
            payloadJson = """{"body":"好","photos":["/data/user/0/lezi/files/private.jpg"],"future":{"v":2}}""",
            schemaVersion = 2,
            updatedAt = 456,
        )

        val wire = SyncWireMapper.record(
            entity,
            babyClientUuid = "baby-uuid",
            createdByDeviceId = "device-a",
            includeMembershipAuthor = true,
        )
        val payload = Json.parseToJsonElement(wire.payloadJson).jsonObject

        assertThat(payload["baby_client_uuid"].toString()).isEqualTo("\"baby-uuid\"")
        assertThat(payload["created_by_device_id"].toString()).isEqualTo("\"device-a\"")
        assertThat(payload["created_by_membership_id"].toString()).isEqualTo("\"membership-a\"")
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
    fun recordOmitsMembershipAuthorForLegacyServerCapability() {
        val entity = RecordEntity(
            clientUuid = "record-uuid",
            babyId = 7,
            type = "pee",
            timestamp = 123,
            createdByUserId = 1,
            createdByMembershipId = "membership-a",
            createdByDeviceId = "device-a",
            updatedAt = 456,
        )

        val payload = Json.parseToJsonElement(
            SyncWireMapper.record(
                entity = entity,
                babyClientUuid = "baby-uuid",
                createdByDeviceId = "device-a",
                includeMembershipAuthor = false,
            ).payloadJson,
        ).jsonObject

        assertThat(payload["created_by_membership_id"]).isNull()
        assertThat(payload["created_by_device_id"]?.jsonPrimitive?.contentOrNull)
            .isEqualTo("device-a")
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
        assertThat(babyPayload).doesNotContain("sort_order")
        assertThat(babyPayload).contains("\"due_date\":null")
        assertThat(mediaPayload).contains("\"record_client_uuid\":\"record-uuid\"")
        assertThat(mediaPayload).doesNotContain("/private/photo.jpg")
    }

    @Test
    fun fulfillmentCandidateWireCarriesPlanRecordLinksAndConfirmTime() {
        val entity = FulfillmentCandidateEntity(
            clientUuid = "cand-uuid",
            carePlanClientUuid = "plan-uuid",
            recordClientUuid = "record-uuid",
            actualTimestamp = 1_700_000_000_100L,
            confirmedAt = 1_700_000_000_200L,
            updatedAt = 1_700_000_000_200L,
        )
        val wire = SyncWireMapper.fulfillmentCandidate(entity)
        assertThat(wire.type).isEqualTo("fulfillment_candidate")
        assertThat(wire.clientUuid).isEqualTo("cand-uuid")
        val payload = Json.parseToJsonElement(wire.payloadJson).jsonObject
        assertThat(payload["care_plan_client_uuid"]?.jsonPrimitive?.contentOrNull)
            .isEqualTo("plan-uuid")
        assertThat(payload["record_client_uuid"]?.jsonPrimitive?.contentOrNull)
            .isEqualTo("record-uuid")
        assertThat(payload["actual_timestamp"]?.jsonPrimitive?.contentOrNull)
            .isEqualTo("1700000000100")
        assertThat(payload["confirmed_at"]?.jsonPrimitive?.contentOrNull)
            .isEqualTo("1700000000200")
        assertThat(payload["submitter_membership_id"]?.toString()).isEqualTo("null")
        assertThat(payload["submitter_role"]?.toString()).isEqualTo("null")
    }
}
