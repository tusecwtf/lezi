package com.lezi.babylog.sync.engine
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.carePlanAllowsIntentOnlyFeed
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import com.lezi.babylog.sync.backend.SyncEntity

/**
 * Owns the Home-LAN wire representation. Device-local row ids and file paths
 * never leave this boundary.
 */
object SyncWireMapper {
    fun baby(
        entity: BabyEntity,
        avatarMediaUuid: String?,
    ): SyncEntity = SyncEntity(
        type = "baby",
        clientUuid = entity.clientUuid,
        payloadJson = buildJsonObject {
            val birthWeightGrams = entity.birthWeightGrams
            put("nickname", entity.nickname)
            // Room may hold legacy enum names (FEMALE/MALE/UNKNOWN) from older edit UI;
            // Home-LAN wire requires lowercase female|male|null only.
            val wireSex = normalizeBabySexForWire(entity.sex)
            if (wireSex == null) put("sex", JsonNull) else put("sex", wireSex)
            put("birthday", LocalDate.ofEpochDay(entity.birthdayEpochDay).toString())
            if (birthWeightGrams == null) {
                put("birth_weight_grams", JsonNull)
            } else {
                put("birth_weight_grams", birthWeightGrams)
            }
            if (avatarMediaUuid == null) {
                put("avatar_media_uuid", JsonNull)
            } else {
                put("avatar_media_uuid", avatarMediaUuid)
            }
        }.toString(),
        updatedAt = entity.updatedAt,
        deletedAt = entity.deletedAt,
    )

    /**
     * Map local baby sex storage onto the Home-LAN contract.
     * Accepts wire values, Kotlin enum names, and common Chinese UI labels.
     */
    internal fun normalizeBabySexForWire(raw: String?): String? =
        com.lezi.babylog.core.model.normalizeBabySex(raw)

    /**
     * Care-plan status wire values: pending|missed|completed|skipped.
     * Tolerates legacy Kotlin enum names (PENDING, …) if any row still has them.
     */
    internal fun normalizeCarePlanStatusForWire(raw: String): String {
        val normalized = raw.trim().lowercase()
        require(normalized in setOf("pending", "missed", "completed", "skipped")) {
            "care plan status 无效: $raw"
        }
        return normalized
    }

    /**
     * Media kind wire values: log|avatar. Commit capture does not route wake
     * media through this mapper. Disaster-restore export opts in with
     * [allowWake] so a wake row can reuse the same payload shape pull already
     * accepts (`record_client_uuid` carries the WakeObservation).
     */
    internal fun normalizeMediaKindForWire(raw: String, allowWake: Boolean = false): String {
        val normalized = raw.trim().lowercase()
        require(
            normalized == "log" ||
                normalized == "avatar" ||
                (allowWake && normalized == "wake"),
        ) {
            "media kind 无效: $raw"
        }
        return normalized
    }

    fun record(
        entity: RecordEntity,
        babyClientUuid: String,
        customItemClientUuid: String? = null,
    ): SyncEntity {
        val type = requireCurrentRecordType(entity.type, "record type")
        require(entity.schemaVersion == CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION) {
            "record schema_version 必须是 $CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION"
        }
        val localPayload = localPayloadForWire(
            raw = entity.payloadJson,
            type = type,
            localCustomItemId = null,
            customItemClientUuid = customItemClientUuid,
        )
        return SyncEntity(
        type = "record",
        clientUuid = entity.clientUuid,
        payloadJson = buildJsonObject {
            put("baby_client_uuid", babyClientUuid)
            put("type", entity.type)
            if (customItemClientUuid == null) {
                put("custom_item_client_uuid", JsonNull)
            } else {
                put("custom_item_client_uuid", customItemClientUuid)
            }
            put("timestamp", entity.timestamp)
            if (type == RecordType.SLEEP) {
                if (entity.effectiveWakeObservationClientUuid == null) {
                    put("effective_wake_observation_client_uuid", JsonNull)
                } else {
                    put(
                        "effective_wake_observation_client_uuid",
                        entity.effectiveWakeObservationClientUuid,
                    )
                }
            } else if (entity.endTimestamp == null) {
                put("end_timestamp", JsonNull)
            } else {
                put("end_timestamp", entity.endTimestamp)
            }
            if (entity.note == null) put("note", JsonNull) else put("note", entity.note)
            put("payload_json", localPayload)
            put("schema_version", entity.schemaVersion)
        }.toString(),
        updatedAt = entity.updatedAt,
        deletedAt = entity.deletedAt,
    )
    }

    /**
     * Shared custom definition wire payload. Device layout (sortOrder / hide / slots)
     * must never leave this mapper.
     */
    fun customItem(entity: CustomItemEntity): SyncEntity = SyncEntity(
        type = "custom_item",
        clientUuid = entity.clientUuid,
        payloadJson = buildJsonObject {
            put("name", entity.name)
            put("icon_slot", entity.iconSlot)
        }.toString(),
        updatedAt = entity.updatedAt,
        deletedAt = entity.deletedAt,
    )

    /**
     * Care plan wire root for atomic bundles. Reminder/calendar prefs, dirty bits,
     * and root receipts stay device-local. [CarePlanEntity.sourceRecordClientUuid]
     * is a family wire key (explicit null when the plan was not converted).
     */
    fun carePlan(
        entity: CarePlanEntity,
        babyClientUuid: String,
        customItemClientUuid: String?,
    ): SyncEntity {
        val type = requireCurrentRecordType(entity.type, "care plan type")
        require(entity.schemaVersion == CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION) {
            "care plan schema_version 必须是 $CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION"
        }
        val localPayload = localPayloadForWire(
            raw = entity.payloadJson,
            type = type,
            localCustomItemId = entity.customItemId,
            customItemClientUuid = customItemClientUuid,
            allowIntentOnlyFeed = carePlanAllowsIntentOnlyFeed(type, entity.note),
        )
        return SyncEntity(
        type = "care_plan",
        clientUuid = entity.clientUuid,
        payloadJson = buildJsonObject {
            put("baby_client_uuid", babyClientUuid)
            put("type", entity.type)
            if (customItemClientUuid == null) {
                put("custom_item_client_uuid", JsonNull)
            } else {
                put("custom_item_client_uuid", customItemClientUuid)
            }
            put("scheduled_at", entity.scheduledAt)
            put("scheduled_zone_id", entity.scheduledZoneId)
            if (entity.note == null) put("note", JsonNull) else put("note", entity.note)
            put("payload_json", localPayload)
            put("schema_version", entity.schemaVersion)
            // Room should store storageKey (pending/…); accept legacy enum names.
            put("status", normalizeCarePlanStatusForWire(entity.status))
            // Creator is a server stamp. The corpus emit set (mutation_keys
            // minus stamp_rules.mutation_forbidden) keeps it off client plan
            // mutations; the family server stamps on first accept and still
            // ignores-and-restamps the copies 0.4.3 senders emit.
            if (entity.fulfilledRecordClientUuid == null) {
                put("fulfilled_record_client_uuid", JsonNull)
            } else {
                put("fulfilled_record_client_uuid", entity.fulfilledRecordClientUuid)
            }
            if (entity.fulfilledAt == null) {
                put("fulfilled_at", JsonNull)
            } else {
                put("fulfilled_at", entity.fulfilledAt)
            }
            if (entity.sourceRecordClientUuid == null) {
                put("source_record_client_uuid", JsonNull)
            } else {
                put("source_record_client_uuid", entity.sourceRecordClientUuid)
            }
        }.toString(),
        updatedAt = entity.updatedAt,
        deletedAt = entity.deletedAt,
    )
    }

    /**
     * Fulfillment candidate push payload. Server re-stamps submitter
     * membership/role and confirmed_at; client sends plan/record links and the
     * local confirm trail (ignored as authority after first server accept).
     */
    fun fulfillmentCandidate(entity: FulfillmentCandidateEntity): SyncEntity = SyncEntity(
        type = "fulfillment_candidate",
        clientUuid = entity.clientUuid,
        payloadJson = buildJsonObject {
            put("care_plan_client_uuid", entity.carePlanClientUuid)
            put("record_client_uuid", entity.recordClientUuid)
            if (entity.actualTimestamp == null) {
                put("actual_timestamp", JsonNull)
            } else {
                put("actual_timestamp", entity.actualTimestamp)
            }
            // Server freezes these; send local values only as offline trails.
            if (entity.submitterMembershipId.isBlank()) {
                put("submitter_membership_id", JsonNull)
            } else {
                put("submitter_membership_id", entity.submitterMembershipId)
            }
            if (entity.submitterRole.isBlank()) {
                put("submitter_role", JsonNull)
            } else {
                put("submitter_role", entity.submitterRole)
            }
            put("confirmed_at", entity.confirmedAt)
        }.toString(),
        updatedAt = entity.updatedAt,
        deletedAt = entity.deletedAt,
    )

    /**
     * Media wire payload. Log media owns exactly one of [recordClientUuid] or
     * [carePlanClientUuid] (XOR); avatar uses [babyClientUuid] only. Wake media
     * reuses [recordClientUuid] for the WakeObservation and is emitted only when
     * [allowWake] is set (disaster-restore export).
     */
    fun media(
        entity: MediaAssetEntity,
        recordClientUuid: String?,
        babyClientUuid: String?,
        carePlanClientUuid: String? = null,
        allowWake: Boolean = false,
    ): SyncEntity = SyncEntity(
        type = "media",
        clientUuid = entity.clientUuid,
        payloadJson = buildJsonObject {
            val kind = normalizeMediaKindForWire(entity.kind, allowWake = allowWake)
            put("kind", kind)
            if (recordClientUuid == null) {
                put("record_client_uuid", JsonNull)
            } else {
                put("record_client_uuid", recordClientUuid)
            }
            if (carePlanClientUuid == null) {
                put("care_plan_client_uuid", JsonNull)
            } else {
                put("care_plan_client_uuid", carePlanClientUuid)
            }
            // Log media associates through portable record or care_plan id.
            // Avatar only carries baby_client_uuid.
            if (kind != "avatar" || babyClientUuid == null) {
                put("baby_client_uuid", JsonNull)
            } else {
                put("baby_client_uuid", babyClientUuid)
            }
            if (entity.mime == null) put("mime", JsonNull) else put("mime", entity.mime)
            if (entity.width == null) put("width", JsonNull) else put("width", entity.width)
            if (entity.height == null) put("height", JsonNull) else put("height", entity.height)
            put("byte_size", entity.byteSize)
        }.toString(),
        updatedAt = entity.updatedAt,
        deletedAt = entity.deletedAt,
    )

    fun carePlanPayloadJson(payload: JsonObject): String {
        return requireNotNull(payload["payload_json"] as? JsonObject) {
            "care plan payload_json 必须是对象"
        }.toString()
    }

    fun carePlanSchemaVersion(payload: JsonObject): Int =
        requireCurrentSchemaVersion(payload, "care plan")

    fun recordPayloadJson(payload: JsonObject): String {
        return requireNotNull(payload["payload_json"] as? JsonObject) {
            "record payload_json 必须是对象"
        }.toString()
    }

    fun recordSchemaVersion(payload: JsonObject): Int =
        requireCurrentSchemaVersion(payload, "record")

    fun birthdayEpochDay(payload: JsonObject): Long =
        requireNotNull(
            payload.string("birthday")
                ?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() },
        ) {
            "baby birthday 必须是 ISO-8601 日期"
        }

    internal fun localPayloadFromWire(
        type: RecordType,
        payload: JsonObject,
        customItemId: Long?,
        allowIntentOnlyFeed: Boolean = false,
    ): String {
        require("photos" !in payload && "custom_item_id" !in payload) {
            "payload_json 包含设备本地字段"
        }
        val local = JsonObject(
            if (type == RecordType.CUSTOM) {
                require(customItemId != null && customItemId > 0) {
                    "custom type 缺少本地定义"
                }
                payload + ("custom_item_id" to JsonPrimitive(customItemId))
            } else {
                require(customItemId == null) { "built-in type 不得引用自定义定义" }
                payload
            },
        )
        return requireStrictCurrentPayload(
            type,
            local.toString(),
            allowIntentOnlyFeed = allowIntentOnlyFeed,
        ).toString()
    }

    /**
     * Validates the portable payload nested inside a canonical root without
     * inventing the device-local custom-item id that Room adds on materialization.
     */
    internal fun requireCurrentTransportPayload(
        type: RecordType,
        payload: JsonObject,
        allowIntentOnlyFeed: Boolean = false,
    ): JsonObject {
        require("photos" !in payload && "custom_item_id" !in payload) {
            "payload_json 包含设备本地字段"
        }
        val canonical = requireStrictCurrentPayload(
            type = type,
            raw = payload.toString(),
            allowIntentOnlyFeed = allowIntentOnlyFeed,
        )
        require(canonical == payload) { "payload_json 不是 current canonical shape" }
        return payload
    }

    private fun localPayloadForWire(
        raw: String,
        type: RecordType,
        localCustomItemId: Long?,
        customItemClientUuid: String?,
        allowIntentOnlyFeed: Boolean = false,
    ): JsonObject {
        val parsed = requireStrictCurrentPayload(
            type,
            raw,
            allowIntentOnlyFeed = allowIntentOnlyFeed,
        )
        val payloadCustomItemId = parsed["custom_item_id"]
            ?.jsonPrimitive
            ?.longOrNull
            ?.takeIf { it > 0L }
        if (type == RecordType.CUSTOM) {
            require(!customItemClientUuid.isNullOrBlank()) {
                "custom type requires custom_item_client_uuid"
            }
            val expectedLocalId = localCustomItemId ?: payloadCustomItemId
            require(expectedLocalId != null && payloadCustomItemId == expectedLocalId) {
                "custom payload requires matching positive custom_item_id"
            }
        } else {
            require(customItemClientUuid == null && localCustomItemId == null) {
                "custom_item_client_uuid is only valid for custom type"
            }
        }
        // Device-local photo paths and Room ids never cross the wire. Media rows
        // and the portable root custom-item UUID are their sole wire identities.
        return JsonObject(parsed - "photos" - "custom_item_id")
    }

    private fun requireStrictCurrentPayload(
        type: RecordType,
        raw: String,
        allowIntentOnlyFeed: Boolean = false,
    ): JsonObject {
        val parsed = runCatching { Json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw IllegalArgumentException("payload_json 必须是 JSON 对象", it) }
        val document = RecordPayloadCodec.decode(
            type = type,
            payloadJson = parsed.toString(),
            schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )
        require(!document.isUnknown) { "payload_json 不是 current typed payload" }
        val errors = RecordPayloadCodec.validate(
            document.payload,
            allowIntentOnlyFeed = allowIntentOnlyFeed,
        )
        require(errors.isEmpty()) { "payload_json 无效: ${errors.joinToString()}" }
        val canonical = Json.parseToJsonElement(RecordPayloadCodec.encode(document)).jsonObject
        require(canonical == parsed) { "payload_json 不是 current canonical shape" }
        return canonical
    }

    private fun requireCurrentSchemaVersion(payload: JsonObject, context: String): Int {
        val version = payload["schema_version"]?.jsonPrimitive?.intOrNull
        require(version == CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION) {
            "$context schema_version 必须是 $CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION"
        }
        return version
    }

    internal fun requireCurrentRecordType(raw: String?, context: String): RecordType =
        requireNotNull(raw?.let(RecordType::fromKey)) {
            "$context 不是 current RecordType"
        }
}

internal fun JsonObject.string(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull?.takeUnless { it == "null" }

internal fun JsonObject.long(key: String): Long? =
    get(key)?.jsonPrimitive?.longOrNull

internal fun JsonObject.element(key: String): JsonElement? = get(key)
