package com.lezi.babylog.sync

import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
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
            val dueDateEpochDay = entity.dueDateEpochDay
            val birthWeightGrams = entity.birthWeightGrams
            put("nickname", entity.nickname)
            if (entity.sex == null) put("sex", JsonNull) else put("sex", entity.sex)
            put("birthday", LocalDate.ofEpochDay(entity.birthdayEpochDay).toString())
            if (dueDateEpochDay == null) {
                put("due_date", JsonNull)
            } else {
                put("due_date", LocalDate.ofEpochDay(dueDateEpochDay).toString())
            }
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

    fun record(
        entity: RecordEntity,
        babyClientUuid: String,
        createdByDeviceId: String,
        includeMembershipAuthor: Boolean = false,
    ): SyncEntity = SyncEntity(
        type = "record",
        clientUuid = entity.clientUuid,
        payloadJson = buildJsonObject {
            put("baby_client_uuid", babyClientUuid)
            put("created_by_device_id", createdByDeviceId)
            if (includeMembershipAuthor && entity.createdByMembershipId.isNotBlank()) {
                put("created_by_membership_id", entity.createdByMembershipId)
            }
            put("type", entity.type)
            put("timestamp", entity.timestamp)
            if (entity.endTimestamp == null) {
                put("end_timestamp", JsonNull)
            } else {
                put("end_timestamp", entity.endTimestamp)
            }
            if (entity.note == null) put("note", JsonNull) else put("note", entity.note)
            put("payload_json", localPayloadForWire(entity.payloadJson))
            put("schema_version", entity.schemaVersion)
        }.toString(),
        updatedAt = entity.updatedAt,
        deletedAt = entity.deletedAt,
    )

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
            // Server re-stamps on first insert; send local membership for offline trails.
            if (entity.createdByMembershipId.isBlank()) {
                put("created_by_membership_id", JsonNull)
            } else {
                put("created_by_membership_id", entity.createdByMembershipId)
            }
        }.toString(),
        updatedAt = entity.updatedAt,
        deletedAt = entity.deletedAt,
    )

    /**
     * Care plan wire root for atomic bundles. Local reminder/calendar prefs and
     * [CarePlanEntity.sourceRecordClientUuid] stay device-local (not in allowlist).
     */
    fun carePlan(
        entity: CarePlanEntity,
        babyClientUuid: String,
        customItemClientUuid: String?,
    ): SyncEntity = SyncEntity(
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
            put("payload_json", localPayloadForWire(entity.payloadJson))
            put("schema_version", entity.schemaVersion)
            put("status", entity.status)
            if (entity.createdByMembershipId.isBlank()) {
                put("created_by_membership_id", JsonNull)
            } else {
                put("created_by_membership_id", entity.createdByMembershipId)
            }
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
        }.toString(),
        updatedAt = entity.updatedAt,
        deletedAt = entity.deletedAt,
    )

    /**
     * Fulfillment candidate legacy-push payload. Server re-stamps submitter
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
     * [carePlanClientUuid] (XOR); avatar uses [babyClientUuid] only.
     */
    fun media(
        entity: MediaAssetEntity,
        recordClientUuid: String?,
        babyClientUuid: String?,
        carePlanClientUuid: String? = null,
    ): SyncEntity = SyncEntity(
        type = "media",
        clientUuid = entity.clientUuid,
        payloadJson = buildJsonObject {
            put("kind", entity.kind)
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
            if (entity.kind != "avatar" || babyClientUuid == null) {
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
        val value = payload["payload_json"] ?: return "{}"
        return when (value) {
            is JsonObject -> value.toString()
            is JsonPrimitive -> {
                val raw = value.contentOrNull ?: return "{}"
                runCatching { Json.parseToJsonElement(raw).jsonObject.toString() }
                    .getOrDefault(raw)
            }
            else -> value.toString()
        }
    }

    fun carePlanSchemaVersion(payload: JsonObject): Int =
        payload["schema_version"]?.jsonPrimitive?.intOrNull ?: 1

    fun recordPayloadJson(payload: JsonObject): String {
        val value = payload["payload_json"] ?: return "{}"
        return when (value) {
            is JsonObject -> value.toString()
            is JsonPrimitive -> {
                val raw = value.contentOrNull ?: return "{}"
                runCatching { Json.parseToJsonElement(raw).jsonObject.toString() }
                    .getOrDefault(raw)
            }
            else -> value.toString()
        }
    }

    fun recordSchemaVersion(payload: JsonObject): Int =
        payload["schema_version"]?.jsonPrimitive?.intOrNull ?: 1

    fun birthdayEpochDay(payload: JsonObject): Long? =
        payload.string("birthday")
            ?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() }
            ?: payload.long("birthday_epoch_day")

    fun dueDateEpochDay(payload: JsonObject): Long? =
        payload.string("due_date")
            ?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() }
            ?: payload.long("due_date_epoch_day")

    private fun localPayloadForWire(raw: String): JsonObject {
        val parsed = runCatching { Json.parseToJsonElement(raw).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        // `photos` contains app-private paths. Media entities carry the portable
        // references and downloaded paths are restored after pull.
        return JsonObject(parsed - "photos")
    }
}

internal fun JsonObject.string(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull?.takeUnless { it == "null" }

internal fun JsonObject.long(key: String): Long? =
    get(key)?.jsonPrimitive?.longOrNull

internal fun JsonObject.element(key: String): JsonElement? = get(key)
