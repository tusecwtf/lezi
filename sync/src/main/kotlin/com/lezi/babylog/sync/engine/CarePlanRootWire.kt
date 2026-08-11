package com.lezi.babylog.sync.engine

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.RecordType
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class CarePlanWire(
    val babyClientUuid: String,
    val type: RecordType,
    val customItemClientUuid: String?,
    val scheduledAt: Long,
    val scheduledZoneId: String,
    val note: String?,
    val payload: JsonObject,
    val schemaVersion: Int,
    val status: String,
    val createdByMembershipId: String,
    val fulfilledRecordClientUuid: String?,
    val fulfilledAt: Long?,
    val inlineUpdatedAt: Long?,
)

internal data class CarePlanReferences(
    val baby: BabyEntity,
    val customItem: CustomItemEntity?,
    val fulfilledRecord: RecordEntity?,
)

internal data class CarePlanCalendarDisposition(
    val reminderReady: Boolean,
    val projectionPending: Boolean,
)

/** One owner for invalidating the device-local calendar projection after shared revision apply. */
internal fun carePlanCalendarDisposition(
    existing: CarePlanEntity?,
    babyId: Long,
    type: String,
    customItemId: Long?,
    scheduledAt: Long,
    scheduledZoneId: String,
    note: String?,
    payloadJson: String,
    schemaVersion: Int,
    status: String,
    deleted: Boolean,
): CarePlanCalendarDisposition {
    val terminal = deleted || status == "completed" || status == "skipped"
    val existingTerminal = existing?.let {
        it.deletedAt != null || it.status == "completed" || it.status == "skipped"
    }
    val visibleRevisionChanged = existing != null && (
        existing.babyId != babyId ||
            existing.type != type ||
            existing.customItemId != customItemId ||
            existing.scheduledAt != scheduledAt ||
            existing.scheduledZoneId != scheduledZoneId ||
            existing.note != note ||
            existing.payloadJson != payloadJson ||
            existing.schemaVersion != schemaVersion ||
            existingTerminal != terminal
        )
    val needsReconciliation = terminal || visibleRevisionChanged
    val hasSideEffectEvidence = existing?.let {
        it.systemCalendarEventId != null ||
            it.systemCalendarReminderReady ||
            it.systemCalendarProjectionPending
    } == true
    return CarePlanCalendarDisposition(
        reminderReady = if (needsReconciliation) {
            false
        } else {
            existing?.systemCalendarReminderReady ?: false
        },
        projectionPending = if (needsReconciliation) {
            hasSideEffectEvidence
        } else {
            existing?.systemCalendarProjectionPending ?: false
        },
    )
}

/** Single typed owner for CarePlan payloads consumed by pull and causal terminal proof. */
internal fun decodeCarePlanWire(
    payload: JsonObject,
    updatedAtLocation: RootUpdatedAtLocation = RootUpdatedAtLocation.SeparateEnvelope,
    requireCanonicalIds: Boolean = false,
): CarePlanWire {
    payload.requireCarePlanKeys(updatedAtLocation)
    val babyUuid = payload.requireCarePlanNonBlankString("baby_client_uuid")
    val type = SyncWireMapper.requireCurrentRecordType(
        payload.requireCarePlanNonBlankString("type"),
        "care plan type",
    )
    val customItemUuid = payload.requireCarePlanNullableString("custom_item_client_uuid")
    require((type == RecordType.CUSTOM) == (customItemUuid != null)) {
        if (type == RecordType.CUSTOM) {
            "care plan type custom requires custom_item_client_uuid"
        } else {
            "care plan custom_item_client_uuid is only valid for type custom"
        }
    }
    val zone = payload.requireCarePlanNonBlankString("scheduled_zone_id")
    require(runCatching { ZoneId.of(zone) }.isSuccess) { "care plan scheduled_zone_id 无效" }
    val status = payload.requireCarePlanNonBlankString("status")
    require(status in CarePlanStatus.entries.map(CarePlanStatus::storageKey)) {
        "care plan status 无效"
    }
    val nested = payload["payload_json"] as? JsonObject
        ?: throw IllegalArgumentException("care_plan.payload_json 必须是对象")
    require("photos" !in nested && "custom_item_id" !in nested) {
        "care plan payload_json 包含设备本地字段"
    }
    val scheduledAt = payload.requireCarePlanLong("scheduled_at")
    require(scheduledAt >= 0) { "care plan scheduled_at 无效" }
    val fulfilledRecordUuid = payload.requireCarePlanNullableString("fulfilled_record_client_uuid")
    val fulfilledAt = payload.requireCarePlanNullableLong("fulfilled_at")
    require(fulfilledAt == null || fulfilledAt >= 0) { "care plan fulfilled_at 无效" }
    val completeFulfillment = fulfilledRecordUuid != null && fulfilledAt != null
    require((status == CarePlanStatus.COMPLETED.storageKey) == completeFulfillment) {
        "care plan completed 必须且仅能携带完整 fulfilled Record 关系"
    }
    if (requireCanonicalIds) {
        listOfNotNull(babyUuid, customItemUuid, fulfilledRecordUuid).forEach { reference ->
            require(reference.isCanonicalUuid()) { "care_plan reference 必须是规范 UUID" }
        }
    }
    return CarePlanWire(
        babyClientUuid = babyUuid,
        type = type,
        customItemClientUuid = customItemUuid,
        scheduledAt = scheduledAt,
        scheduledZoneId = zone,
        note = payload.requireCarePlanNullableString("note"),
        payload = nested,
        schemaVersion = SyncWireMapper.carePlanSchemaVersion(payload),
        status = status,
        createdByMembershipId = payload.requireCarePlanNullableString(
            "created_by_membership_id",
        ).orEmpty().trim(),
        fulfilledRecordClientUuid = fulfilledRecordUuid,
        fulfilledAt = fulfilledAt,
        inlineUpdatedAt = when (updatedAtLocation) {
            RootUpdatedAtLocation.SeparateEnvelope -> null
            RootUpdatedAtLocation.InlineStableRoot ->
                payload.requireCarePlanLong("updated_at").also {
                    require(it >= 0) { "care_plan.updated_at 必须是非负整数" }
                }
        },
    )
}

/** Shared reference/domain owner for freeze, pull materialization and terminal proof. */
internal suspend fun resolveCarePlanReferences(
    wire: CarePlanWire,
    babyDao: BabyDao,
    customItemDao: CustomItemDao,
    recordDao: RecordDao,
): CarePlanReferences? {
    val baby = babyDao.getByClientUuid(wire.babyClientUuid) ?: return null
    val customItem = wire.customItemClientUuid?.let { uuid ->
        customItemDao.getByClientUuid(uuid) ?: return null
    }
    val fulfilledRecord = wire.fulfilledRecordClientUuid?.let { uuid ->
        recordDao.getByClientUuid(uuid) ?: return null
    }
    if (fulfilledRecord != null && fulfilledRecord.babyId != baby.id) return null
    return CarePlanReferences(
        baby = baby,
        customItem = customItem,
        fulfilledRecord = fulfilledRecord,
    )
}

private fun String.isCanonicalUuid(): Boolean =
    runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)

private fun JsonObject.requireCarePlanKeys(location: RootUpdatedAtLocation) {
    val expected = setOf(
        "baby_client_uuid",
        "type",
        "custom_item_client_uuid",
        "scheduled_at",
        "scheduled_zone_id",
        "note",
        "payload_json",
        "schema_version",
        "status",
        "created_by_membership_id",
        "fulfilled_record_client_uuid",
        "fulfilled_at",
    ) + when (location) {
        RootUpdatedAtLocation.SeparateEnvelope -> emptySet()
        RootUpdatedAtLocation.InlineStableRoot -> setOf("updated_at")
    }
    require(keys == expected) {
        "care_plan current wire 字段不完整或包含未知字段: ${keys.sorted()}"
    }
}

private fun JsonObject.requireCarePlanNonBlankString(key: String): String {
    val primitive = get(key) as? JsonPrimitive
    require(primitive?.isString == true && primitive.content.isNotBlank()) {
        "care_plan.$key 必须是非空字符串"
    }
    return primitive.content
}

private fun JsonObject.requireCarePlanNullableString(key: String): String? {
    val value = get(key) ?: throw IllegalArgumentException("care_plan 缺少 $key")
    if (value === JsonNull) return null
    val primitive = value as? JsonPrimitive
    require(primitive?.isString == true) { "care_plan.$key 必须是字符串或 null" }
    return primitive.content
}

private fun JsonObject.requireCarePlanLong(key: String): Long {
    val primitive = get(key) as? JsonPrimitive
    require(primitive?.isString == false && primitive.longOrNull != null) {
        "care_plan.$key 必须是整数"
    }
    return requireNotNull(primitive.longOrNull)
}

private fun JsonObject.requireCarePlanNullableLong(key: String): Long? {
    val value = get(key) ?: throw IllegalArgumentException("care_plan 缺少 $key")
    if (value === JsonNull) return null
    val primitive = value as? JsonPrimitive
    require(primitive?.isString == false && primitive.longOrNull != null) {
        "care_plan.$key 必须是整数或 null"
    }
    return primitive.longOrNull
}
