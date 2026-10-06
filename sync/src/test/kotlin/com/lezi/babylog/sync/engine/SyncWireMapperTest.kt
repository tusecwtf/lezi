package com.lezi.babylog.sync.engine
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanEntity
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
    fun carePlanAllowsIntentOnlyFeedButRecordStillRequiresAFactAmount() {
        val plan = CarePlanEntity(
            clientUuid = "next-feed-plan",
            babyId = 7,
            type = "formula",
            scheduledAt = 1_000,
            scheduledZoneId = "Asia/Shanghai",
            note = "[[lezi:next-feed:v1]]",
            payloadJson = """{"amount_ml":0}""",
            createdByMembershipId = "membership-a",
            updatedAt = 456,
        )

        val nextFeedWire = Json.parseToJsonElement(
            SyncWireMapper.carePlan(plan, "baby-uuid", null).payloadJson,
        ).jsonObject
        assertThat(nextFeedWire.keys).contains("source_record_client_uuid")
        assertThat(nextFeedWire["source_record_client_uuid"].toString()).isEqualTo("null")
        assertThat(SyncWireMapper.carePlan(plan, "baby-uuid", null).payloadJson)
            .contains("\"amount_ml\":0")
        assertThat(
            runCatching {
                SyncWireMapper.carePlan(plan.copy(note = "手动计划"), "baby-uuid", null)
            }.isFailure,
        ).isTrue()
        assertThat(
            runCatching {
                SyncWireMapper.record(
                    RecordEntity(
                        clientUuid = "invalid-fact",
                        babyId = 7,
                        type = "formula",
                        timestamp = 1_000,
                        payloadJson = """{"amount_ml":0}""",
                        updatedAt = 456,
                    ),
                    "baby-uuid",
                )
            }.isFailure,
        ).isTrue()
    }

    @Test
    fun recordUsesPortableBabyIdAndCurrentTypedObjectPayload() {
        val entity = RecordEntity(
            clientUuid = "record-uuid",
            babyId = 7,
            type = "diary",
            timestamp = 123,
            note = "今天",
            createdByMembershipId = "membership-a",
            payloadJson = """{"body":"好"}""",
            schemaVersion = 2,
            updatedAt = 456,
        )

        val wire = SyncWireMapper.record(
            entity,
            babyClientUuid = "baby-uuid",
        )
        val payload = Json.parseToJsonElement(wire.payloadJson).jsonObject

        assertThat(payload.keys).containsExactly(
            "baby_client_uuid",
            "type",
            "custom_item_client_uuid",
            "timestamp",
            "end_timestamp",
            "note",
            "payload_json",
            "schema_version",
        )
        assertThat(payload["baby_client_uuid"].toString()).isEqualTo("\"baby-uuid\"")
        assertThat(payload.keys).doesNotContain("created_by_membership_id")
        assertThat(payload["custom_item_client_uuid"].toString()).isEqualTo("null")
        assertThat(payload["schema_version"].toString()).isEqualTo("2")
        assertThat(payload["payload_json"]).isInstanceOf(
            kotlinx.serialization.json.JsonObject::class.java,
        )
        assertThat(payload["payload_json"].toString()).contains("\"body\":\"好\"")
    }

    @Test
    fun sleepRecordUsesCausalShapeOnly() {
        val entity = RecordEntity(
            clientUuid = "sleep-uuid",
            babyId = 7,
            type = "sleep",
            timestamp = 100,
            endTimestamp = 200,
            createdByMembershipId = "membership-a",
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            schemaVersion = 2,
            updatedAt = 456,
            effectiveWakeObservationClientUuid = null,
        )

        val payload = Json.parseToJsonElement(
            SyncWireMapper.record(entity, "baby-uuid").payloadJson,
        ).jsonObject

        assertThat(payload.keys).containsExactly(
            "baby_client_uuid",
            "type",
            "custom_item_client_uuid",
            "timestamp",
            "note",
            "payload_json",
            "schema_version",
            "effective_wake_observation_client_uuid",
        )
        assertThat(payload).doesNotContainKey("end_timestamp")
        assertThat(payload.keys).doesNotContain("created_by_membership_id")
        assertThat(payload["effective_wake_observation_client_uuid"].toString()).isEqualTo("null")
    }

    @Test
    fun customRecordUsesPortableRootReferenceAndNeverLeaksLocalId() {
        val entity = RecordEntity(
            clientUuid = "record-custom",
            babyId = 7,
            type = "custom",
            timestamp = 123,
            createdByMembershipId = "membership-a",
            payloadJson =
                """{"title":"体操","detail":"十分钟","custom_item_id":7,"icon_slot":2}""",
            schemaVersion = 2,
            updatedAt = 456,
        )

        val payload = Json.parseToJsonElement(
            SyncWireMapper.record(
                entity,
                babyClientUuid = "baby-uuid",
                customItemClientUuid = "custom-definition-uuid",
            ).payloadJson,
        ).jsonObject

        assertThat(payload["custom_item_client_uuid"]?.jsonPrimitive?.contentOrNull)
            .isEqualTo("custom-definition-uuid")
        assertThat(payload["payload_json"].toString()).doesNotContain("custom_item_id")
        assertThat(payload.keys).doesNotContain("created_by_membership_id")
        assertThat(payload.keys).contains("custom_item_client_uuid")
        assertThat(
            SyncWireMapper.requireCurrentTransportPayload(
                type = com.lezi.babylog.core.model.RecordType.CUSTOM,
                payload = payload["payload_json"]!!.jsonObject,
            ).toString(),
        ).doesNotContain("custom_item_id")
        assertThat(
            SyncWireMapper.localPayloadFromWire(
                type = com.lezi.babylog.core.model.RecordType.CUSTOM,
                payload = payload["payload_json"]!!.jsonObject,
                customItemId = 19,
            ),
        ).contains("\"custom_item_id\":19")
    }

    @Test
    fun mutationRootsOmitServerStampFields() {
        val plan = CarePlanEntity(
            clientUuid = "plan-uuid",
            babyId = 7,
            type = "formula",
            scheduledAt = 1_000,
            scheduledZoneId = "Asia/Shanghai",
            payloadJson = """{"amount_ml":60}""",
            createdByMembershipId = "membership-a",
            updatedAt = 456,
        )
        val item = com.lezi.babylog.core.database.CustomItemEntity(
            clientUuid = "item-uuid",
            familyId = 1,
            name = "抚触",
            iconSlot = 2,
            updatedAt = 456,
            createdByMembershipId = "membership-a",
        )

        val planPayload = Json.parseToJsonElement(
            SyncWireMapper.carePlan(plan, "baby-uuid", null).payloadJson,
        ).jsonObject
        val itemPayload = Json.parseToJsonElement(
            SyncWireMapper.customItem(item).payloadJson,
        ).jsonObject

        assertThat(planPayload.keys).doesNotContain("created_by_membership_id")
        assertThat(itemPayload.keys).doesNotContain("created_by_membership_id")
    }

    @Test
    fun customCarePlanUsesFamilyDefinitionUuidAndNeverLeaksLocalId() {
        val plan = CarePlanEntity(
            clientUuid = "plan-custom",
            babyId = 7,
            type = "custom",
            customItemId = 7,
            scheduledAt = 1_000,
            scheduledZoneId = "Asia/Shanghai",
            payloadJson = """{"title":"抚触","detail":"十分钟","custom_item_id":7,"icon_slot":2}""",
            createdByMembershipId = "membership-a",
            updatedAt = 456,
        )
        val payload = Json.parseToJsonElement(
            SyncWireMapper.carePlan(plan, "baby-uuid", "custom-definition-uuid").payloadJson,
        ).jsonObject

        assertThat(payload["custom_item_client_uuid"]?.jsonPrimitive?.contentOrNull)
            .isEqualTo("custom-definition-uuid")
        assertThat(payload["payload_json"].toString()).doesNotContain("custom_item_id")
    }

    @Test
    fun recordMapperRejectsCustomReferenceMismatchAndNonCanonicalPayloadAlias() {
        val builtIn = RecordEntity(
            clientUuid = "record-built-in",
            babyId = 7,
            type = "temperature",
            timestamp = 123,
            createdByMembershipId = "membership-a",
            payloadJson = """{"celsius":36.5}""",
            updatedAt = 456,
        )
        assertThat(runCatching {
            SyncWireMapper.record(builtIn, "baby", "custom-definition")
        }.isFailure).isTrue()

        assertThat(runCatching {
            SyncWireMapper.record(
                builtIn.copy(payloadJson = """{"value":36.5}"""),
                "baby",
            )
        }.isFailure).isTrue()
        assertThat(runCatching {
            SyncWireMapper.record(
                builtIn.copy(
                    type = "diary",
                    payloadJson = """{"body":"好","photos":["private.jpg"]}""",
                ),
                "baby",
            )
        }.isFailure).isTrue()
    }

    @Test
    fun pulledRecordRequiresCurrentObjectPayload() {
        val objectPayload = Json.parseToJsonElement(
            """{"payload_json":{"amount_ml":120},"schema_version":2}""",
        ).jsonObject
        assertThat(SyncWireMapper.recordPayloadJson(objectPayload))
            .isEqualTo("""{"amount_ml":120}""")
        assertThat(SyncWireMapper.recordSchemaVersion(objectPayload)).isEqualTo(2)
    }

    @Test
    fun pulledRecordRejectsMissingOrNonCurrentSchema() {
        listOf(
            """{"payload_json":{"amount_ml":120}}""",
            """{"payload_json":{"amount_ml":120},"schema_version":1}""",
            """{"payload_json":{"amount_ml":120},"schema_version":3}""",
        ).forEach { raw ->
            val payload = Json.parseToJsonElement(raw).jsonObject
            assertThat(runCatching { SyncWireMapper.recordSchemaVersion(payload) }.isFailure)
                .isTrue()
        }
    }

    @Test
    fun carePlanWireAlwaysEmitsSourceKeyAndOmitsCalendarDirtyFields() {
        val converted = CarePlanEntity(
            clientUuid = "converted-plan",
            babyId = 7,
            type = "formula",
            scheduledAt = 1_000,
            scheduledZoneId = "Asia/Shanghai",
            note = "[[lezi:next-feed:v1]]",
            payloadJson = """{"amount_ml":0}""",
            createdByMembershipId = "membership-a",
            sourceRecordClientUuid = "00000000-0000-4000-8000-000000000301",
            updatedAt = 456,
            syncDirty = true,
            systemCalendarEventId = "calendar-local",
            systemCalendarReminderReady = true,
            systemCalendarProjectionPending = true,
        )
        val payload = Json.parseToJsonElement(
            SyncWireMapper.carePlan(converted, "baby-uuid", null).payloadJson,
        ).jsonObject

        assertThat(payload["source_record_client_uuid"]?.jsonPrimitive?.contentOrNull)
            .isEqualTo("00000000-0000-4000-8000-000000000301")
        assertThat(payload.keys).containsAtLeast(
            "source_record_client_uuid",
            "fulfilled_record_client_uuid",
            "fulfilled_at",
            "status",
        )
        assertThat(payload.keys).containsNoneOf(
            "system_calendar_event_id",
            "system_calendar_reminder_ready",
            "system_calendar_projection_pending",
            "sync_dirty",
            "family_published_updated_at",
        )
    }

    @Test
    fun pulledCarePlanRequiresCurrentObjectPayload() {
        val stringPayload = Json.parseToJsonElement(
            """{"payload_json":"{\"amount_ml\":120}"}""",
        ).jsonObject

        assertThat(runCatching { SyncWireMapper.carePlanPayloadJson(stringPayload) }.isFailure)
            .isTrue()
    }

    @Test
    fun pulledCarePlanRejectsMissingOrNonCurrentSchema() {
        listOf(
            """{"payload_json":{}}""",
            """{"payload_json":{},"schema_version":1}""",
            """{"payload_json":{},"schema_version":3}""",
        ).forEach { raw ->
            val payload = Json.parseToJsonElement(raw).jsonObject
            assertThat(runCatching { SyncWireMapper.carePlanSchemaVersion(payload) }.isFailure)
                .isTrue()
        }
    }

    @Test
    fun pulledBabyRejectsRemovedEpochDayAlias() {
        val removedAlias = Json.parseToJsonElement(
            """{"birthday_epoch_day":20000}""",
        ).jsonObject

        assertThat(runCatching { SyncWireMapper.birthdayEpochDay(removedAlias) }.isFailure)
            .isTrue()
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
            clientUuid = "10000000-0000-4000-8000-000000000005",
            kind = "log",
            localUri = "/private/photo.jpg",
            mime = "image/jpeg",
            byteSize = 12,
            createdAt = 100,
        )

        val babyPayload = Json.parseToJsonElement(
            SyncWireMapper.baby(baby, "avatar-uuid").payloadJson,
        ).jsonObject
        val mediaPayload = SyncWireMapper.media(
            media,
            recordClientUuid = "record-uuid",
            babyClientUuid = "baby-uuid",
        ).payloadJson

        assertThat(babyPayload.keys).containsExactly(
            "nickname",
            "sex",
            "birthday",
            "birth_weight_grams",
            "avatar_media_uuid",
        )
        assertThat(mediaPayload).contains("\"record_client_uuid\":\"record-uuid\"")
        assertThat(mediaPayload).doesNotContain("/private/photo.jpg")
    }

    @Test
    fun babySexNormalizesLegacyEnumNamesToWireContract() {
        fun sexOf(stored: String?): String? {
            val entity = BabyEntity(
                familyId = 1,
                nickname = "年年",
                sex = stored,
                birthdayEpochDay = 20_000,
                themeColorArgb = 0,
                clientUuid = "baby-uuid",
                updatedAt = 1,
            )
            val payload = Json.parseToJsonElement(
                SyncWireMapper.baby(entity, avatarMediaUuid = null).payloadJson,
            ).jsonObject
            return payload["sex"]?.let { element ->
                if (element.toString() == "null") null else element.jsonPrimitive.contentOrNull
            }
        }

        assertThat(sexOf("female")).isEqualTo("female")
        assertThat(sexOf("male")).isEqualTo("male")
        assertThat(sexOf("FEMALE")).isEqualTo("female")
        assertThat(sexOf("MALE")).isEqualTo("male")
        assertThat(sexOf("UNKNOWN")).isNull()
        assertThat(sexOf("女")).isEqualTo("female")
        assertThat(sexOf(null)).isNull()
        assertThat(SyncWireMapper.normalizeBabySexForWire("男宝")).isEqualTo("male")
    }

    @Test
    fun carePlanStatusAndMediaKindNormalizeLegacyCase() {
        assertThat(SyncWireMapper.normalizeCarePlanStatusForWire("PENDING"))
            .isEqualTo("pending")
        assertThat(SyncWireMapper.normalizeCarePlanStatusForWire("completed"))
            .isEqualTo("completed")
        assertThat(SyncWireMapper.normalizeMediaKindForWire("LOG")).isEqualTo("log")
        assertThat(SyncWireMapper.normalizeMediaKindForWire("avatar")).isEqualTo("avatar")
        assertThat(SyncWireMapper.normalizeMediaKindForWire("wake", allowWake = true)).isEqualTo("wake")
        assertThat(
            runCatching { SyncWireMapper.normalizeMediaKindForWire("wake") }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            runCatching { SyncWireMapper.normalizeCarePlanStatusForWire("done") }
                .exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            runCatching { SyncWireMapper.normalizeMediaKindForWire("photo") }
                .exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
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
