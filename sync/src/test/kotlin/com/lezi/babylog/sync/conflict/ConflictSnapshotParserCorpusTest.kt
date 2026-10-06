package com.lezi.babylog.sync.conflict

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ConflictSnapshotParserCorpusTest {
    @Test
    fun adr0022GoldenSnapshotPagesUseTheSameProductionCodec() {
        val raw = requireNotNull(javaClass.getResourceAsStream("/conflict-v2-golden.json"))
            .bufferedReader().use { it.readText() }
        val case = Json.parseToJsonElement(raw).jsonObject["cases"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["id"]!!.jsonPrimitive.contentOrNull == "snapshot-two-pages" }
        val pages = case["input"]!!.jsonObject["pages"]!!.jsonArray
            .map { ConflictSnapshotCodec.decode(it.toString()) }

        assertThat(pages).hasSize(2)
        assertThat(pages.first().complete).isFalse()
        assertThat(pages.last().complete).isTrue()
        assertThat(pages.flatMap { it.branches }.map { it.versionId })
            .containsExactly("b1", "b2").inOrder()
    }

    @Test
    fun babySnapshotRequiresBirthWeightAndStamp() {
        val withWeight =
            """{"nickname":"安安","sex":null,"birthday":null,"birth_weight_grams":3200,"avatar_media_uuid":null,"updated_at":100,"created_by_membership_id":"member-a"}"""
        val missingWeight =
            """{"nickname":"安安","sex":null,"birthday":null,"avatar_media_uuid":null,"updated_at":100,"created_by_membership_id":"member-a"}"""
        val missingStamp =
            """{"nickname":"安安","sex":null,"birthday":null,"birth_weight_grams":3200,"avatar_media_uuid":null,"updated_at":100}"""

        val baby = ConflictSnapshotCodec.decode(snapshot("baby", withWeight)).stable.root
            as ConflictRoot.Baby
        assertThat(baby.birthWeightGrams).isEqualTo(3200)
        assertThat(baby.createdByMembershipId).isEqualTo("member-a")
        assertThat(runCatching { ConflictSnapshotCodec.decode(snapshot("baby", missingWeight)) }.isFailure)
            .isTrue()
        assertThat(runCatching { ConflictSnapshotCodec.decode(snapshot("baby", missingStamp)) }.isFailure)
            .isTrue()
    }

    @Test
    fun fiveCanonicalRootKindsMapToTypedDomainRoots() {
        val cases = listOf(
            "baby" to (
                """{"nickname":"安安","sex":null,"birthday":null,"birth_weight_grams":null,"avatar_media_uuid":null,"updated_at":100,"created_by_membership_id":"member-a"}""" to
                    ConflictRoot.Baby::class.java
                ),
            "record" to (
                """{"baby_client_uuid":"$BABY_UUID","type":"formula","custom_item_client_uuid":null,"timestamp":100,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":60},"schema_version":2,"updated_at":100,"created_by_membership_id":"member-a"}""" to
                    ConflictRoot.Record::class.java
                ),
            "care_plan" to (
                """{"baby_client_uuid":"$BABY_UUID","type":"formula","scheduled_at":100,"scheduled_zone_id":"Asia/Shanghai","note":null,"payload_json":{"amount_ml":60},"schema_version":2,"status":"pending","fulfilled_record_client_uuid":null,"fulfilled_at":null,"source_record_client_uuid":null,"custom_item_client_uuid":null,"updated_at":100,"created_by_membership_id":"member-a"}""" to
                    ConflictRoot.CarePlan::class.java
                ),
            "custom_item" to (
                """{"name":"散步","icon_slot":3,"updated_at":100,"created_by_membership_id":"member-a"}""" to
                    ConflictRoot.CustomItem::class.java
                ),
            "wake_observation" to (
                """{"sleep_record_client_uuid":"$RECORD_UUID","wake_timestamp":120,"note":null,"withdrawn":false,"updated_at":120,"observer_membership_id":"member-a"}""" to
                    ConflictRoot.WakeObservation::class.java
                ),
        )

        cases.forEach { (entityType, expectation) ->
            val (root, rootClass) = expectation
            val parsed = ConflictSnapshotCodec.decode(snapshot(entityType, root))
            assertThat(parsed.stable.root).isInstanceOf(rootClass)
            assertThat(parsed.entityType.wireName).isEqualTo(entityType)
        }
    }

    @Test
    fun convertedCarePlanDailyFreezeShapeDecodesInConflictSnapshot() {
        val freezeRoot = """{"baby_client_uuid":"$BABY_UUID","type":"formula","custom_item_client_uuid":null,"scheduled_at":100,"scheduled_zone_id":"Asia/Shanghai","note":null,"payload_json":{"amount_ml":60},"schema_version":2,"status":"pending","created_by_membership_id":"member-a","fulfilled_record_client_uuid":null,"fulfilled_at":null,"source_record_client_uuid":"$RECORD_UUID","updated_at":100}"""
        val parsed = ConflictSnapshotCodec.decode(snapshot("care_plan", freezeRoot))
        val plan = parsed.stable.root as ConflictRoot.CarePlan
        assertThat(plan.sourceRecordClientUuid).isEqualTo(RECORD_UUID)
        assertThat(parsed.conflicting.single().path).isEqualTo("/note")
        assertThat(parsed.conflicting.single().candidates.map { it.choiceId })
            .containsExactly("choice-aaaaaaaaa", "choice-0000000001")
            .inOrder()
    }

    @Test
    fun unknownMissingWrongTypeAndForeignRootFailClosed() {
        val baby = """{"nickname":"安安","sex":null,"birthday":null,"birth_weight_grams":null,"avatar_media_uuid":null,"updated_at":100,"created_by_membership_id":"member-a"}"""
        val malformed = listOf(
            baby.dropLast(1) + ",\"alias\":\"A\"}",
            baby.replace("\"birthday\":null,", ""),
            baby.replace("\"updated_at\":100", "\"updated_at\":\"100\""),
            """{"name":"散步","icon_slot":3,"updated_at":100,"created_by_membership_id":"member-a"}""",
        )

        malformed.forEach { root ->
            val failure = runCatching {
                ConflictSnapshotCodec.decode(snapshot("baby", root))
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun nestedRecordPayloadUsesTheCanonicalTypedPayloadContract() {
        val roots = listOf(
            recordRoot("{\"amount_ml\":60,\"server_only\":true}"),
            recordRoot("{\"amount_ml\":0}"),
        )

        roots.forEach { root ->
            assertThat(
                runCatching {
                    ConflictSnapshotCodec.decode(snapshot("record", root))
                }.exceptionOrNull(),
            ).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun transportIdentifiersPointersAndCollectionsFailClosedPerCanonicalDimension() {
        val valid = snapshot(
            "baby",
            """{"nickname":"安安","sex":null,"birthday":null,"birth_weight_grams":null,"avatar_media_uuid":null,"updated_at":100,"created_by_membership_id":"member-a"}""",
        )
        val invalid = listOf(
            valid.replace(CONFLICT_UUID, "not-a-uuid"),
            valid.replace(RECORD_UUID, "00000000-0000-0000-0000-00000000000A"),
            valid.replace("a".repeat(43), "!".repeat(43)),
            valid.replace("\"continuation\":null", "\"continuation\":\"bad token!\"")
                .replace("\"complete\":true", "\"complete\":false"),
            valid.replace("choice-aaaaaaaaa", "choice-a"),
            valid.replace("/note", "/bad~pointer"),
            snapshot(
                "baby",
                """{"nickname":"安安","sex":null,"birthday":null,"birth_weight_grams":null,"avatar_media_uuid":null,"updated_at":100,"created_by_membership_id":"member-a"}""",
                candidateCount = 1,
            ),
            snapshot(
                "baby",
                """{"nickname":"安安","sex":null,"birthday":null,"birth_weight_grams":null,"avatar_media_uuid":null,"updated_at":100,"created_by_membership_id":"member-a"}""",
                candidateCount = 66,
            ),
            snapshot(
                "baby",
                """{"nickname":"安安","sex":null,"birthday":null,"birth_weight_grams":null,"avatar_media_uuid":null,"updated_at":100,"created_by_membership_id":"member-a"}""",
                sourceCount = 66,
            ),
        )

        invalid.forEach { raw ->
            assertThat(runCatching { ConflictSnapshotCodec.decode(raw) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun rootDomainValuesFailClosedPerCanonicalDimension() {
        val baby = """{"nickname":"安安","sex":null,"birthday":null,"birth_weight_grams":null,"avatar_media_uuid":null,"updated_at":100,"created_by_membership_id":"member-a"}"""
        val carePlan = """{"baby_client_uuid":"$BABY_UUID","type":"formula","scheduled_at":100,"scheduled_zone_id":"Asia/Shanghai","note":null,"payload_json":{"amount_ml":60},"schema_version":2,"status":"pending","fulfilled_record_client_uuid":null,"fulfilled_at":null,"source_record_client_uuid":null,"custom_item_client_uuid":null,"updated_at":100,"created_by_membership_id":"member-a"}"""
        val cases = listOf(
            "baby" to baby.replace("\"sex\":null", "\"sex\":\"unknown\""),
            "baby" to baby.replace("\"birthday\":null", "\"birthday\":\"2026-02-30\""),
            "baby" to baby.replace("安安", " ${"安".repeat(20)}"),
            "care_plan" to carePlan.replace("Asia/Shanghai", "Mars/Olympus"),
            "care_plan" to carePlan.replace("\"status\":\"pending\"", "\"status\":\"done\""),
            "custom_item" to """{"name":" ${"项".repeat(40)}","icon_slot":8,"updated_at":100,"created_by_membership_id":"member-a"}""",
            "wake_observation" to """{"sleep_record_client_uuid":"bad","wake_timestamp":-1,"note":null,"withdrawn":false,"updated_at":120,"observer_membership_id":"member-a"}""",
        )

        cases.forEach { (entityType, root) ->
            assertThat(
                runCatching { ConflictSnapshotCodec.decode(snapshot(entityType, root)) }
                    .exceptionOrNull(),
            ).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private fun recordRoot(payload: String): String =
        """{"baby_client_uuid":"$BABY_UUID","type":"formula","custom_item_client_uuid":null,"timestamp":100,"end_timestamp":null,"note":null,"payload_json":$payload,"schema_version":2,"updated_at":100,"created_by_membership_id":"member-a"}"""

    private fun snapshot(
        entityType: String,
        root: String,
        candidateCount: Int = 2,
        sourceCount: Int = 1,
    ): String {
        val sources = (0 until sourceCount).joinToString(separator = ",") { index ->
            val mutation = "00000000-0000-0000-0000-${(100 + index).toString().padStart(12, '0')}"
            """{"version_id":"source-$index","mutation_id":"$mutation","actor_id":"member-a","device_id":"device-a","received_at":100}"""
        }
        val secondCandidate = (1 until candidateCount).joinToString(separator = "") { index ->
            val choice = "choice-${index.toString().padStart(10, '0')}"
            """,{"choice_id":"$choice","outcome":{"op":"set","value":$index},"sources":[$sources]}"""
        }
        return """{"contract":"conflict_snapshot_v2","conflict_id":"$CONFLICT_UUID","entity_type":"$entityType","client_uuid":"$RECORD_UUID","snapshot_token":"${"a".repeat(43)}","expires_at":2000000,"stable":{"version_id":"v1","base_version":null,"root":$root,"media":[],"deleted":false,"mutation_id":"$MUTATION_UUID","actor_id":"member-a","device_id":"device-a","received_at":100},"branches":[],"conflicting":[{"path":"/note","candidates":[{"choice_id":"choice-aaaaaaaaa","outcome":{"op":"set","value":null},"sources":[$sources]}$secondCandidate]}],"auto_merged":[],"page_index":0,"continuation":null,"complete":true}"""
    }

    private companion object {
        const val CONFLICT_UUID = "00000000-0000-0000-0000-000000000001"
        const val BABY_UUID = "00000000-0000-0000-0000-000000000002"
        const val RECORD_UUID = "00000000-0000-0000-0000-000000000003"
        const val MUTATION_UUID = "00000000-0000-0000-0000-000000000004"
    }
}
