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
    val sourceRecordClientUuid: String?,
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

/**
 * Care plan root wire shapes, mirroring [WakeRootWireShape]: the frozen local
 * mutation carries the emit set only (no server stamps), pull tolerates the
 * server stamp, and the stable root requires it. Decode is closed per shape —
 * unknown keys never skip and no shape dual-reads another.
 */
internal enum class CarePlanRootShape {
    /** Frozen local mutation root: inline revision, no server stamp. */
    LocalMutation,
    /** Incremental pull: revision travels in the SyncEntity envelope; the
     * server creator stamp is accepted present or absent. */
    Pull,
    /** Server stable root: inline revision, creator stamp required. */
    StableRoot,
}

/** Single typed owner for CarePlan payloads consumed by pull and causal terminal proof. */
internal fun decodeCarePlanWire(
    payload: JsonObject,
    shape: CarePlanRootShape = CarePlanRootShape.Pull,
    requireCanonicalIds: Boolean = false,
): CarePlanWire {
    payload.requireCarePlanKeys(shape)
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
    val sourceRecordUuid = payload.requireCarePlanNullableString("source_record_client_uuid")
    val completeFulfillment = fulfilledRecordUuid != null && fulfilledAt != null
    require((status == CarePlanStatus.COMPLETED.storageKey) == completeFulfillment) {
        "care plan completed 必须且仅能携带完整 fulfilled Record 关系"
    }
    if (requireCanonicalIds) {
        listOfNotNull(babyUuid, customItemUuid, fulfilledRecordUuid, sourceRecordUuid)
            .forEach { reference ->
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
        createdByMembershipId = when (shape) {
            CarePlanRootShape.StableRoot ->
                payload.requireCarePlanNullableString("created_by_membership_id")
                    .orEmpty().trim()
            CarePlanRootShape.Pull ->
                if ("created_by_membership_id" in payload) {
                    payload.requireCarePlanNullableString("created_by_membership_id")
                        .orEmpty().trim()
                } else {
                    ""
                }
            CarePlanRootShape.LocalMutation -> ""
        },
        fulfilledRecordClientUuid = fulfilledRecordUuid,
        fulfilledAt = fulfilledAt,
        sourceRecordClientUuid = sourceRecordUuid,
        inlineUpdatedAt = when (shape) {
            CarePlanRootShape.Pull -> null
            CarePlanRootShape.LocalMutation, CarePlanRootShape.StableRoot ->
                payload.requireCarePlanLong("updated_at").also {
                    require(it >= 0) { "care_plan.updated_at 必须是非负整数" }
                }
        },
    )
}

/** Named reference resolution for care plans: the miss names its gate. */
internal sealed interface CarePlanReferenceResult {
    data class Resolved(val references: CarePlanReferences) : CarePlanReferenceResult

    /** Plan baby is not local. */
    data class BabyMissing(val babyClientUuid: String) : CarePlanReferenceResult

    /** Custom-item plan waits until the definition is local. */
    data class CustomItemMissing(val customItemClientUuid: String) : CarePlanReferenceResult

    /** Completed plan links a fulfilled record that is not local. */
    data class FulfilledRecordMissing(val recordClientUuid: String) : CarePlanReferenceResult

    /** Fulfilled record belongs to a different baby than the plan. */
    data class FulfilledRecordBabyMismatch(
        val recordClientUuid: String,
        val recordBabyId: Long,
        val planBabyId: Long,
    ) : CarePlanReferenceResult
}

internal fun CarePlanReferenceResult.referencesOrNull(): CarePlanReferences? =
    (this as? CarePlanReferenceResult.Resolved)?.references

/** Pull-apply gate verdict for a care plan reference miss. */
internal fun CarePlanReferenceResult.toDeferredVerdict(): ApplyVerdict.Deferred? = when (this) {
    is CarePlanReferenceResult.Resolved -> null
    is CarePlanReferenceResult.BabyMissing -> applyDeferred(
        gate = DeferredGate.PlanBabyMissing,
        missingEntityType = "baby",
        missingClientUuid = babyClientUuid,
        localSnapshot = "baby=absent",
    )
    is CarePlanReferenceResult.CustomItemMissing -> applyDeferred(
        gate = DeferredGate.PlanCustomItemMissing,
        missingEntityType = "custom_item",
        missingClientUuid = customItemClientUuid,
        localSnapshot = "custom_item=absent",
    )
    is CarePlanReferenceResult.FulfilledRecordMissing -> applyDeferred(
        gate = DeferredGate.PlanFulfilledRecordMissing,
        missingEntityType = "record",
        missingClientUuid = recordClientUuid,
        localSnapshot = "fulfilled_record=absent",
    )
    is CarePlanReferenceResult.FulfilledRecordBabyMismatch -> applyDeferred(
        gate = DeferredGate.PlanFulfilledRecordBabyMismatch,
        missingEntityType = "record",
        missingClientUuid = recordClientUuid,
        localSnapshot = "fulfilled.babyId=$recordBabyId,plan.babyId=$planBabyId",
    )
}

/** Shared reference/domain owner for freeze, pull materialization and terminal proof. */
internal suspend fun resolveCarePlanReferences(
    wire: CarePlanWire,
    babyDao: BabyDao,
    customItemDao: CustomItemDao,
    recordDao: RecordDao,
): CarePlanReferenceResult {
    val baby = babyDao.getByClientUuid(wire.babyClientUuid)
        ?: return CarePlanReferenceResult.BabyMissing(wire.babyClientUuid)
    val customItem = wire.customItemClientUuid?.let { uuid ->
        customItemDao.getByClientUuid(uuid)
            ?: return CarePlanReferenceResult.CustomItemMissing(uuid)
    }
    val fulfilledRecord = wire.fulfilledRecordClientUuid?.let { uuid ->
        recordDao.getByClientUuid(uuid)
            ?: return CarePlanReferenceResult.FulfilledRecordMissing(uuid)
    }
    if (fulfilledRecord != null && fulfilledRecord.babyId != baby.id) {
        return CarePlanReferenceResult.FulfilledRecordBabyMismatch(
            recordClientUuid = wire.fulfilledRecordClientUuid.orEmpty(),
            recordBabyId = fulfilledRecord.babyId,
            planBabyId = baby.id,
        )
    }
    return CarePlanReferenceResult.Resolved(
        CarePlanReferences(
            baby = baby,
            customItem = customItem,
            fulfilledRecord = fulfilledRecord,
        ),
    )
}

private fun String.isCanonicalUuid(): Boolean =
    runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)

private fun JsonObject.requireCarePlanKeys(shape: CarePlanRootShape) {
    val emitSet = setOf(
        "baby_client_uuid",
        "type",
        "custom_item_client_uuid",
        "scheduled_at",
        "scheduled_zone_id",
        "note",
        "payload_json",
        "schema_version",
        "status",
        "fulfilled_record_client_uuid",
        "fulfilled_at",
        "source_record_client_uuid",
    )
    val expected = emitSet + when (shape) {
        CarePlanRootShape.LocalMutation -> setOf("updated_at")
        CarePlanRootShape.Pull ->
            if ("created_by_membership_id" in this) setOf("created_by_membership_id") else emptySet()
        CarePlanRootShape.StableRoot -> setOf("updated_at", "created_by_membership_id")
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
