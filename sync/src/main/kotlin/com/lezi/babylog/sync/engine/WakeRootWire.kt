package com.lezi.babylog.sync.engine

import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal enum class WakeRootWireShape {
    LocalMutation,
    Pull,
    StableRoot,
}

internal data class WakeRootWire(
    val sleepRecordClientUuid: String,
    val wakeTimestamp: Long,
    val note: String?,
    val withdrawn: Boolean,
    /** Null for client-authored mutation and unstamped Pull; StableRoot requires the stamp. */
    val observerMembershipId: String?,
    /** Pull carries updated_at in its envelope; mutation/proof carry it in the root. */
    val inlineUpdatedAt: Long?,
)

internal fun encodeWakeMutationRoot(wake: WakeObservationEntity): String = buildJsonObject {
    put("sleep_record_client_uuid", wake.sleepRecordClientUuid)
    put("wake_timestamp", wake.wakeTimestamp)
    if (wake.note == null) put("note", JsonNull) else put("note", wake.note)
    put("withdrawn", wake.withdrawn)
    put("updated_at", wake.updatedAt)
}.toString()

/** Single typed owner for Wake roots consumed by freeze, pull and terminal proof. */
internal fun decodeWakeRootWire(
    root: JsonObject,
    shape: WakeRootWireShape,
): WakeRootWire {
    root.requireWakeKeys(shape)
    val sleepUuid = root.requireWakeNonBlankString("sleep_record_client_uuid")
    val wakeTimestamp = root.requireWakeLong("wake_timestamp")
    require(wakeTimestamp >= 0) { "wake_observation.wake_timestamp 必须是非负整数" }
    val observer = when (shape) {
        WakeRootWireShape.LocalMutation -> null
        WakeRootWireShape.Pull ->
            if ("observer_membership_id" in root) {
                root.requireWakeNonBlankString("observer_membership_id")
            } else {
                null
            }
        WakeRootWireShape.StableRoot -> root.requireWakeNonBlankString("observer_membership_id")
    }
    val updatedAt = when (shape) {
        WakeRootWireShape.Pull -> null
        WakeRootWireShape.LocalMutation,
        WakeRootWireShape.StableRoot,
        -> root.requireWakeLong("updated_at").also {
            require(it >= 0) { "wake_observation.updated_at 必须是非负整数" }
        }
    }
    return WakeRootWire(
        sleepRecordClientUuid = sleepUuid,
        wakeTimestamp = wakeTimestamp,
        note = root.requireWakeNullableString("note"),
        withdrawn = root.requireWakeBoolean("withdrawn"),
        observerMembershipId = observer,
        inlineUpdatedAt = updatedAt,
    )
}

/** Shared source-fact resolver; existing Wake roots cannot drift to another Sleep. */
internal suspend fun resolveWakeReference(
    wire: WakeRootWire,
    recordDao: RecordDao,
    expectedSleepClientUuid: String? = null,
): RecordEntity? {
    if (expectedSleepClientUuid != null &&
        wire.sleepRecordClientUuid != expectedSleepClientUuid
    ) {
        return null
    }
    return recordDao.getByClientUuid(wire.sleepRecordClientUuid)
        ?.takeIf { sleep ->
            sleep.type == "sleep" && wire.wakeTimestamp >= sleep.timestamp
        }
}

private fun JsonObject.requireWakeKeys(shape: WakeRootWireShape) {
    val expected = setOf(
        "sleep_record_client_uuid",
        "wake_timestamp",
        "note",
        "withdrawn",
    ) + when (shape) {
        WakeRootWireShape.LocalMutation -> setOf("updated_at")
        WakeRootWireShape.Pull -> emptySet()
        WakeRootWireShape.StableRoot -> setOf("updated_at", "observer_membership_id")
    }
    val allowed = if (shape == WakeRootWireShape.Pull) {
        keys == expected || keys == expected + "observer_membership_id"
    } else {
        keys == expected
    }
    require(allowed) {
        "wake_observation current wire 字段不完整或包含未知字段: ${keys.sorted()}"
    }
}

private fun JsonObject.requireWakeNonBlankString(key: String): String {
    val primitive = get(key) as? JsonPrimitive
    require(
        primitive?.isString == true &&
            primitive.content.isNotBlank() &&
            primitive.content == primitive.content.trim(),
    ) {
        "wake_observation.$key 必须是无首尾空白的非空字符串"
    }
    return primitive.content
}

private fun JsonObject.requireWakeNullableString(key: String): String? {
    val value = get(key) ?: throw IllegalArgumentException("wake_observation 缺少 $key")
    if (value === JsonNull) return null
    val primitive = value as? JsonPrimitive
    require(primitive?.isString == true) {
        "wake_observation.$key 必须是字符串或 null"
    }
    return primitive.content
}

private fun JsonObject.requireWakeLong(key: String): Long {
    val primitive = get(key) as? JsonPrimitive
    require(primitive?.isString == false && primitive.longOrNull != null) {
        "wake_observation.$key 必须是整数"
    }
    return requireNotNull(primitive.longOrNull)
}

private fun JsonObject.requireWakeBoolean(key: String): Boolean {
    val primitive = get(key) as? JsonPrimitive
    require(primitive?.isString == false && primitive.booleanOrNull != null) {
        "wake_observation.$key 必须是布尔值"
    }
    return requireNotNull(primitive.booleanOrNull)
}
