package com.lezi.babylog.sync.backend

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.sync.engine.SyncWireMapper
import com.lezi.babylog.sync.engine.decodeWakeRootWire
import com.lezi.babylog.sync.engine.encodeWakeMutationRoot
import com.lezi.babylog.sync.engine.parseMediaWire
import com.lezi.babylog.sync.engine.parseRecordWire
import com.lezi.babylog.sync.engine.WakeRootWireShape
import com.lezi.babylog.sync.session.CAPABILITY_CAUSAL_SYNC_V2
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class ConflictV2GoldenCorpusTest {
    @Test
    fun sharedCorpusPublishesTheFrozenContractCases() {
        val schema = resource("conflict-v2-golden.schema.json").jsonObject
        val corpus = resource("conflict-v2-golden.json").jsonObject

        assertThat(schema.getValue("\$id").jsonPrimitive.content)
            .isEqualTo(corpus.getValue("\$schema").jsonPrimitive.content)
        assertThat(corpus.getValue("contract").jsonPrimitive.content)
            .isEqualTo("lezi.conflict-v2-golden")
        assertThat(corpus.getValue("version").jsonPrimitive.content).isEqualTo("2")

        val release = corpus.getValue("release").jsonObject
        assertThat(release.getValue("android").jsonPrimitive.content).isEqualTo("0.4.0")
        assertThat(release.getValue("version_code").jsonPrimitive.content).isEqualTo("21")
        assertThat(release.getValue("room").jsonPrimitive.content).isEqualTo("28")
        assertThat(release.getValue("server_schema").jsonPrimitive.content).isEqualTo("13")
        assertThat(corpus.getValue("capability").jsonObject.getValue("key").jsonPrimitive.content)
            .isEqualTo(CAPABILITY_CAUSAL_SYNC_V2)
        assertThat(REQUIRED_CAUSAL_WIRE_CAPABILITIES)
            .containsExactly(CAPABILITY_CAUSAL_SYNC_V2)

        assertThat(schema.getValue("\$defs").jsonObject)
            .containsKey("snapshotPage")
        assertThat(corpus).containsKey("cases")

        val commit = corpus.getValue("cases").jsonArray
            .single { it.jsonObject.getValue("id").jsonPrimitive.content == "commit-exact-replay-marker" }
            .jsonObject
            .getValue("expect")
            .jsonObject
        assertThat(commit.stringList("batch_keys")).containsExactly("generation", "results").inOrder()
        assertThat(commit.stringList("unit_required_keys")).containsExactly(
            "status",
            "mutation_id",
            "request_hash",
            "replay",
            "stable",
        ).inOrder()
        assertThat(commit.stringList("unit_optional_keys")).containsExactly(
            "branch_version_id",
            "conflict_id",
        ).inOrder()
        assertThat(commit.stringList("stable_keys")).containsExactly(
            "version_id",
            "root",
            "media",
            "deleted",
            "deleted_at",
        ).inOrder()
    }

    @Test
    fun sharedCorpusPublishesSleepWakeClosedSetAndMediaRoles() {
        val schema = resource("conflict-v2-golden.schema.json").jsonObject
        val corpus = resource("conflict-v2-golden.json").jsonObject
        val table = corpus.getValue("dataflow_01_sleep_wake").jsonObject

        assertThat(schema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
            .contains("dataflow_01_sleep_wake")
        assertThat(schema.getValue("properties").jsonObject)
            .containsKey("dataflow_01_sleep_wake")

        val sleep = table.getValue("sleep_record").jsonObject
        assertThat(sleep.stringList("mutation_keys")).containsExactly(
            "baby_client_uuid",
            "type",
            "custom_item_client_uuid",
            "timestamp",
            "note",
            "payload_json",
            "schema_version",
            "updated_at",
            "effective_wake_observation_client_uuid",
        ).inOrder()
        assertThat(sleep.stringList("stable_keys")).containsExactly(
            "baby_client_uuid",
            "type",
            "custom_item_client_uuid",
            "timestamp",
            "note",
            "payload_json",
            "schema_version",
            "updated_at",
            "effective_wake_observation_client_uuid",
            "created_by_membership_id",
        ).inOrder()
        assertThat(sleep.stringList("forbidden_keys")).containsExactly("end_timestamp")
        assertThat(sleep.getValue("effective_wake").jsonPrimitive.content)
            .isEqualTo("required_nullable")

        val wake = table.getValue("wake_observation").jsonObject
        assertThat(wake.stringList("mutation_keys")).containsExactly(
            "sleep_record_client_uuid",
            "wake_timestamp",
            "note",
            "withdrawn",
            "updated_at",
        ).inOrder()
        assertThat(wake.stringList("stable_keys")).containsExactly(
            "sleep_record_client_uuid",
            "wake_timestamp",
            "note",
            "withdrawn",
            "updated_at",
            "observer_membership_id",
        ).inOrder()
        assertThat(wake.stringList("forbidden_keys")).containsExactly(
            "end_timestamp",
            "deleted",
            "deleted_at",
        )

        val roles = table.getValue("media_roles").jsonArray.map { it.jsonObject }
        assertThat(roles.map { it.getValue("role").jsonPrimitive.content })
            .containsExactly("wake", "log", "plan", "avatar")
            .inOrder()
        assertThat(roles.single { it.getValue("role").jsonPrimitive.content == "wake" }.let {
            listOf(
                it.getValue("owner_entity_type").jsonPrimitive.content,
                it.getValue("kind").jsonPrimitive.content,
                it.getValue("owner_wire_field").jsonPrimitive.content,
                it.getValue("max_items").jsonPrimitive.content,
            )
        }).containsExactly("wake_observation", "wake", "record_client_uuid", "3").inOrder()
        assertThat(roles.single { it.getValue("role").jsonPrimitive.content == "log" }
            .getValue("max_items").jsonPrimitive.content).isEqualTo("3")
        assertThat(roles.single { it.getValue("role").jsonPrimitive.content == "plan" }
            .getValue("max_items").jsonPrimitive.content).isEqualTo("3")
        assertThat(roles.single { it.getValue("role").jsonPrimitive.content == "avatar" }
            .getValue("max_items").jsonPrimitive.content).isEqualTo("1")
    }

    @Test
    fun dataflow02CarePlanClosedSetPinsSourceFulfillmentPairAndLocalOnlyKeys() {
        val table = resource("conflict-v2-golden.json").jsonObject
            .getValue("dataflow_02_care_plan")
            .jsonObject
        assertThat(table.getValue("entity_type").jsonPrimitive.content).isEqualTo("care_plan")

        val mutation = table.stringList("mutation_keys")
        val stable = table.stringList("stable_keys")
        val stamps = table.getValue("stamp_rules").jsonObject
        val forbidden = stamps.stringList("mutation_forbidden")
        val stableRequired = stamps.stringList("stable_required")
        // Emit-set semantics: the client mutation emit set is the corpus
        // mutation closure minus stamp_rules.mutation_forbidden, so the stable
        // closure is exactly the emit set plus the stable_required stamps.
        assertThat(mutation.toSet().intersect(forbidden.toSet())).isEmpty()
        assertThat(stable.toSet() - mutation.toSet())
            .containsExactlyElementsIn(stableRequired)
        assertThat(mutation).contains("source_record_client_uuid")
        assertThat(table.stringList("nullable_keys")).contains(
            "source_record_client_uuid",
        )
        assertThat(forbidden).contains("created_by_membership_id")
        assertThat(stableRequired).contains("created_by_membership_id")

        val pair = table.getValue("fulfillment_pair").jsonObject
        assertThat(pair.getValue("completed_requires_full_pair").toString()).isEqualTo("true")
        assertThat(pair.getValue("pending_requires_empty_pair").toString()).isEqualTo("true")
        assertThat(pair.getValue("skipped_requires_empty_pair").toString()).isEqualTo("true")

        val notOnWire = table.stringList("not_on_family_wire")
        assertThat(notOnWire).containsAtLeast(
            "system_calendar_event_id",
            "system_calendar_reminder_ready",
            "system_calendar_projection_pending",
            "sync_dirty",
        )
        assertThat(mutation.intersect(notOnWire.toSet())).isEmpty()
        assertThat(stable.intersect(notOnWire.toSet())).isEmpty()

        val encoded = Json.parseToJsonElement(
            SyncWireMapper.carePlan(
                CarePlanEntity(
                    clientUuid = "plan-corpus",
                    babyId = 1,
                    type = "formula",
                    scheduledAt = 1_000,
                    scheduledZoneId = "Asia/Shanghai",
                    payloadJson = """{"amount_ml":120}""",
                    updatedAt = 1,
                ),
                babyClientUuid = "baby-corpus",
                customItemClientUuid = null,
            ).payloadJson,
        ).jsonObject
        assertThat(encoded.keys).containsExactlyElementsIn(
            mutation.filter { it != "updated_at" },
        )
        assertThat(encoded.keys.intersect(notOnWire.toSet())).isEmpty()
    }

    @Test
    fun dataflow03PortableIdentityPinsPayloadKeysBirthWeightAndStampModes() {
        val corpus = resource("conflict-v2-golden.json").jsonObject
        val section = corpus.getValue("dataflow_03_portable_identity").jsonObject

        val tables = section.getValue("typed_payload_keys").jsonObject
        assertThat(tables.keys).containsExactlyElementsIn(RecordType.entries.map { it.key })
        tables.forEach { (typeKey, spec) ->
            val type = requireNotNull(RecordType.fromKey(typeKey))
            val schema = RecordPayloadCodec.payloadKeySchema(type)
            val table = spec.jsonObject
            assertThat(schema.required).containsExactlyElementsIn(table.stringList("required"))
            assertThat(schema.optional).containsExactlyElementsIn(table.stringList("optional"))
            val forbidden = if ("forbidden_on_portable" in table) {
                table.stringList("forbidden_on_portable")
            } else {
                emptyList()
            }
            assertThat(schema.localOnly).containsExactlyElementsIn(forbidden)
            forbidden.forEach { key ->
                assertThat(schema.portable).doesNotContain(key)
            }
        }

        val baby = section.getValue("baby_closed_set").jsonObject
        assertThat(baby.stringList("business_keys")).containsExactly(
            "nickname",
            "sex",
            "birthday",
            "birth_weight_grams",
            "avatar_media_uuid",
        ).inOrder()
        assertThat(baby.getValue("stamp_key").jsonPrimitive.content)
            .isEqualTo("created_by_membership_id")

        val stamps = section.getValue("stamp_fields").jsonObject
        assertThat(stamps.getValue("mutation").jsonPrimitive.content).isEqualTo("forbid")
        assertThat(stamps.getValue("stable").jsonPrimitive.content).isEqualTo("require")
        assertThat(stamps.getValue("snapshot").jsonPrimitive.content).isEqualTo("require")
        assertThat(stamps.getValue("server_on_mutation").jsonPrimitive.content)
            .isEqualTo("ignore_and_restamp")
        assertThat(stamps.stringList("keys")).containsExactly(
            "created_by_membership_id",
            "observer_membership_id",
        ).inOrder()

        val samples = section.getValue("samples").jsonObject
        val portableCustom = samples.getValue("portable_custom_payload").jsonObject
        assertThat(portableCustom.keys).doesNotContain("custom_item_id")
        val babyMutation = samples.getValue("baby_mutation").jsonObject
        assertThat(babyMutation.keys).contains("birth_weight_grams")
        assertThat(babyMutation.keys).doesNotContain("created_by_membership_id")
        val babyStable = samples.getValue("baby_stable").jsonObject
        assertThat(babyStable.keys).contains("birth_weight_grams")
        assertThat(babyStable.keys).contains("created_by_membership_id")
    }

    @Test
    fun dataflow01WakeShapesMatchLocalMutationPullAndStableDecoders() {
        val section = resource("conflict-v2-golden.json").jsonObject
            .getValue("dataflow_01_sleep_wake")
            .jsonObject
        val wakeTable = section.getValue("wake_observation").jsonObject
        val shapes = section.getValue("wake_shapes").jsonObject

        // The three-shape table and the root closure table are one contract:
        // a one-sided corpus edit must redden this test, not just one of them.
        assertThat(shapes.stringList("local_mutation_keys").toSet())
            .containsExactlyElementsIn(wakeTable.stringList("mutation_keys"))
        assertThat(shapes.stringList("stable_root_keys").toSet())
            .containsExactlyElementsIn(wakeTable.stringList("stable_keys"))

        val wake = WakeObservationEntity(
            clientUuid = "wake-corpus",
            sleepRecordClientUuid = "sleep-corpus",
            wakeTimestamp = 2,
            note = null,
            withdrawn = false,
            updatedAt = 5,
        )
        val mutationRoot = Json.parseToJsonElement(encodeWakeMutationRoot(wake)).jsonObject
        assertThat(mutationRoot.keys)
            .containsExactlyElementsIn(shapes.stringList("local_mutation_keys"))
        assertThat(mutationRoot.keys).doesNotContain("observer_membership_id")

        fun pullRoot(withStamp: Boolean) = buildJsonObject {
            put("sleep_record_client_uuid", "sleep-corpus")
            put("wake_timestamp", 2)
            put("note", JsonNull)
            put("withdrawn", false)
            if (withStamp) put("observer_membership_id", "member-a")
        }
        val pullKeys = shapes.stringList("pull_keys")
        assertThat(pullRoot(withStamp = false).keys).containsExactlyElementsIn(pullKeys)
        assertThat(decodeWakeRootWire(pullRoot(false), WakeRootWireShape.Pull).observerMembershipId)
            .isNull()
        assertThat(decodeWakeRootWire(pullRoot(true), WakeRootWireShape.Pull).observerMembershipId)
            .isEqualTo("member-a")
        // Pull is closed: an inline revision key is an unknown field on this shape.
        assertThat(
            runCatching {
                decodeWakeRootWire(
                    JsonObject(pullRoot(false) + ("updated_at" to JsonPrimitive(5))),
                    WakeRootWireShape.Pull,
                )
            }.isFailure,
        ).isTrue()

        val stableRoot = JsonObject(pullRoot(true) + ("updated_at" to JsonPrimitive(5)))
        assertThat(stableRoot.keys)
            .containsExactlyElementsIn(shapes.stringList("stable_root_keys"))
        assertThat(decodeWakeRootWire(stableRoot, WakeRootWireShape.StableRoot).observerMembershipId)
            .isEqualTo("member-a")
        // Stable root without the observer stamp must not decode.
        assertThat(
            runCatching {
                decodeWakeRootWire(
                    JsonObject(pullRoot(false) + ("updated_at" to JsonPrimitive(5))),
                    WakeRootWireShape.StableRoot,
                )
            }.isFailure,
        ).isTrue()
    }

    @Test
    fun dataflow04CandidateEmitSetCarriesTrailKeysTheServerRestamps() {
        val section = resource("conflict-v2-golden.json").jsonObject
            .getValue("dataflow_04_fulfillment_candidate")
            .jsonObject
        val mutation = section.stringList("mutation_keys")

        assertThat(section.getValue("publish_channel").jsonPrimitive.content)
            .isEqualTo("atomic_bundle_only")
        val stamps = section.getValue("stamp_rules").jsonObject
        assertThat(stamps.stringList("mutation_trail")).containsExactly(
            "submitter_membership_id",
            "submitter_role",
            "confirmed_at",
        ).inOrder()
        assertThat(stamps.getValue("server_on_mutation").jsonPrimitive.content)
            .isEqualTo("ignore_and_restamp")
        assertThat(stamps.stringList("stable_required")).containsExactly(
            "submitter_membership_id",
            "submitter_role",
            "confirmed_at",
        ).inOrder()

        val encoded = Json.parseToJsonElement(
            SyncWireMapper.fulfillmentCandidate(
                FulfillmentCandidateEntity(
                    clientUuid = "candidate-corpus",
                    carePlanClientUuid = "plan-corpus",
                    recordClientUuid = "record-corpus",
                    actualTimestamp = 1,
                    confirmedAt = 2,
                    updatedAt = 3,
                ),
            ).payloadJson,
        ).jsonObject
        assertThat(encoded.keys).containsExactlyElementsIn(mutation.filter { it != "updated_at" })
    }

    @Test
    fun dataflow05CustomItemRootSharesNameIconAndKeepsLayoutLocal() {
        val section = resource("conflict-v2-golden.json").jsonObject
            .getValue("dataflow_05_custom_item")
            .jsonObject
        val mutation = section.stringList("mutation_keys")
        val stable = section.stringList("stable_keys")
        val stamps = section.getValue("stamp_rules").jsonObject

        assertThat(stamps.getValue("mutation_stamp_optional").jsonPrimitive.content)
            .isEqualTo("created_by_membership_id")
        assertThat(stamps.stringList("stable_required"))
            .containsExactly("created_by_membership_id")
        assertThat(stable.toSet() - mutation.toSet())
            .containsExactlyElementsIn(stamps.stringList("stable_required"))

        val notOnWire = section.stringList("not_on_family_wire")
        assertThat(notOnWire).containsExactly("sortOrder", "hide", "slots").inOrder()
        assertThat(mutation.intersect(notOnWire.toSet())).isEmpty()
        assertThat(stable.intersect(notOnWire.toSet())).isEmpty()
        assertThat(section.getValue("max_undeleted").jsonPrimitive.content).isEqualTo("10")

        val encoded = Json.parseToJsonElement(
            SyncWireMapper.customItem(
                CustomItemEntity(
                    clientUuid = "item-corpus",
                    familyId = 1,
                    name = "抚触",
                    iconSlot = 2,
                    updatedAt = 1,
                    createdByMembershipId = "member-a",
                ),
            ).payloadJson,
        ).jsonObject
        assertThat(encoded.keys).containsExactlyElementsIn(mutation.filter { it != "updated_at" })
        assertThat(encoded.keys).doesNotContain("created_by_membership_id")
    }

    @Test
    fun dataflow06MediaClosuresPinManifestEntityKeysAndWakeWart() {
        val section = resource("conflict-v2-golden.json").jsonObject
            .getValue("dataflow_06_media")
            .jsonObject
        val entityKeys = section.stringList("entity_keys")

        assertThat(section.stringList("manifest_item_keys")).containsExactly(
            "media_uuid",
            "role",
            "sha256",
            "byte_size",
            "mime",
            "width",
            "height",
        ).inOrder()
        assertThat(section.getValue("entity_has_content_identity").jsonPrimitive.content)
            .isEqualTo("false")
        assertThat(section.getValue("wake_download_without_expectation").jsonPrimitive.content)
            .isEqualTo("fail_closed")

        val logMedia = buildJsonObject {
            put("kind", "log")
            put("record_client_uuid", "record-corpus")
            put("care_plan_client_uuid", JsonNull)
            put("baby_client_uuid", JsonNull)
            put("mime", "image/jpeg")
            put("width", JsonNull)
            put("height", JsonNull)
            put("byte_size", 10)
        }
        assertThat(logMedia.keys).containsExactlyElementsIn(entityKeys).inOrder()
        val logWire = parseMediaWire(logMedia)
        assertThat(logWire.kind).isEqualTo("log")
        assertThat(logWire.recordClientUuid).isEqualTo("record-corpus")

        // Wake ownership reuses the record-UUID wire field for the WakeObservation.
        val wakeMedia = buildJsonObject {
            put("kind", "wake")
            put("record_client_uuid", "wake-observation-corpus")
            put("care_plan_client_uuid", JsonNull)
            put("baby_client_uuid", JsonNull)
            put("mime", "image/jpeg")
            put("width", JsonNull)
            put("height", JsonNull)
            put("byte_size", 10)
        }
        val wakeWire = parseMediaWire(wakeMedia)
        assertThat(wakeWire.wakeObservationClientUuid).isEqualTo("wake-observation-corpus")

        val drifted = JsonObject(logMedia + ("sha256" to JsonPrimitive("0".repeat(64))))
        assertThat(runCatching { parseMediaWire(drifted) }.isFailure).isTrue()
    }

    @Test
    fun dataflow07RecordNonSleepClosureMatchesRecordCodec() {
        val section = resource("conflict-v2-golden.json").jsonObject
            .getValue("dataflow_07_record_non_sleep")
            .jsonObject
        val mutation = section.stringList("mutation_keys")
        val stable = section.stringList("stable_keys")
        val stamps = section.getValue("stamp_rules").jsonObject

        assertThat(section.getValue("shape").jsonPrimitive.content)
            .isEqualTo("end_timestamp_null_injected")
        assertThat(stamps.stringList("mutation_forbidden"))
            .containsExactly("created_by_membership_id")
        assertThat(mutation.toSet().intersect(stamps.stringList("mutation_forbidden").toSet()))
            .isEmpty()
        assertThat(stable.toSet() - mutation.toSet())
            .containsExactlyElementsIn(stamps.stringList("stable_required"))

        val emitted = Json.parseToJsonElement(
            SyncWireMapper.record(
                RecordEntity(
                    clientUuid = "record-corpus",
                    babyId = 1,
                    type = "formula",
                    timestamp = 1,
                    payloadJson = """{"amount_ml":60}""",
                    schemaVersion = 2,
                    updatedAt = 1,
                ),
                babyClientUuid = "baby-corpus",
            ).payloadJson,
        ).jsonObject
        assertThat(emitted.keys).containsExactlyElementsIn(mutation.filter { it != "updated_at" })
        assertThat(emitted.keys).doesNotContain("created_by_membership_id")

        val stableRoot = buildJsonObject {
            put("baby_client_uuid", "baby-corpus")
            put("created_by_membership_id", "member-a")
            put("type", "formula")
            put("custom_item_client_uuid", JsonNull)
            put("timestamp", 1)
            put("end_timestamp", JsonNull)
            put("note", JsonNull)
            put("payload_json", buildJsonObject { put("amount_ml", 60) })
            put("schema_version", 2)
        }
        assertThat(stableRoot.keys)
            .containsExactlyElementsIn(stable.filter { it != "updated_at" })
        val wire = parseRecordWire(stableRoot)
        assertThat(wire.type).isEqualTo(RecordType.FORMULA)

        val drifted = JsonObject(stableRoot + ("alias" to JsonPrimitive("A")))
        assertThat(runCatching { parseRecordWire(drifted) }.isFailure).isTrue()
    }

    private fun kotlinx.serialization.json.JsonObject.stringList(key: String) =
        getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun resource(name: String) = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource(name)) {
            "Missing shared contract fixture: $name"
        }.readText(),
    )
}
