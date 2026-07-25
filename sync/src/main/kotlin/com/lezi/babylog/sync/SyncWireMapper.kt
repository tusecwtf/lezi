package com.lezi.babylog.sync

import com.lezi.babylog.core.database.BabyEntity
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
    ): SyncEntity = SyncEntity(
        type = "record",
        clientUuid = entity.clientUuid,
        payloadJson = buildJsonObject {
            put("baby_client_uuid", babyClientUuid)
            put("created_by_device_id", createdByDeviceId)
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

    fun media(
        entity: MediaAssetEntity,
        recordClientUuid: String?,
        babyClientUuid: String?,
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
            // A log media row is associated through its portable Record id.
            // Sending a second baby reference would duplicate that ownership
            // and can become stale after an explicit profile merge.
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
