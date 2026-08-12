package com.lezi.babylog.sync.engine

import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheEntity
import com.lezi.babylog.sync.session.FamilySessionReplica
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Room-backed reset boundary used until H27 gives causal transport dedicated Room 28 storage.
 * The row is written in the same Room transaction that requeues roots, so process death cannot
 * turn reset-derived pending rows into pre-existing user intent.
 */
internal class ReplicaResetReceiptJournal(
    private val cache: ConflictSnapshotCacheDao,
) {
    suspend fun load(): FamilySessionReplica.ResetReceipt? =
        cache.get(REPLICA_RESET_RECEIPT_KEY)?.let { row ->
            decodeReplicaResetReceipt(row.snapshotJson)
        }

    suspend fun replace(receipt: FamilySessionReplica.ResetReceipt) {
        cache.upsert(
            ConflictSnapshotCacheEntity(
                conflictId = REPLICA_RESET_RECEIPT_KEY,
                snapshotJson = encodeReplicaResetReceipt(receipt),
                cachedAt = receipt.roots.maxOfOrNull(FamilySessionReplica.ResetRoot::contentEpoch)
                    ?: 0L,
            ),
        )
    }

    suspend fun complete(receipt: FamilySessionReplica.ResetReceipt) {
        val current = load() ?: return
        require(current == receipt) { "replica reset receipt changed before completion" }
        cache.delete(REPLICA_RESET_RECEIPT_KEY)
    }
}

private const val REPLICA_RESET_RECEIPT_KEY = "replica-reset-receipt:current"
private const val REPLICA_RESET_RECEIPT_SCHEMA = 1

private fun encodeReplicaResetReceipt(receipt: FamilySessionReplica.ResetReceipt): String =
    buildJsonObject {
        put("schema", REPLICA_RESET_RECEIPT_SCHEMA)
        put("previous_family_id", receipt.previousFamilyId)
        put("previous_membership_id", receipt.previousMembershipId)
        put("previous_device_id", receipt.previousDeviceId)
        put("crossing_family_boundary", receipt.crossingFamilyBoundary)
        put(
            "recovery_target",
            receipt.recoveryTarget?.let { target ->
                buildJsonObject {
                    put("base_url", target.baseUrl)
                    put("family_id", target.familyId?.let(::JsonPrimitive) ?: JsonNull)
                    put("membership_id", target.membershipId?.let(::JsonPrimitive) ?: JsonNull)
                    put("device_id", target.deviceId?.let(::JsonPrimitive) ?: JsonNull)
                }
            } ?: JsonNull,
        )
        put(
            "roots",
            buildJsonArray {
                receipt.roots.forEach { root ->
                    add(
                        buildJsonObject {
                            put("entity_type", root.entityType)
                            put("client_uuid", root.clientUuid)
                            put("content_epoch", root.contentEpoch)
                            put("was_pending", root.wasPending)
                        },
                    )
                }
            },
        )
    }.toString()

private fun decodeReplicaResetReceipt(encoded: String): FamilySessionReplica.ResetReceipt {
    val root = Json.parseToJsonElement(encoded).jsonObject
    root.requireExactKeys(
        "schema",
        "previous_family_id",
        "previous_membership_id",
        "previous_device_id",
        "crossing_family_boundary",
        "recovery_target",
        "roots",
    )
    require(root.getValue("schema").jsonPrimitive.long == REPLICA_RESET_RECEIPT_SCHEMA.toLong()) {
        "unsupported replica reset receipt schema"
    }
    val target = root.getValue("recovery_target").let { value ->
        if (value is JsonNull) {
            null
        } else {
            value.jsonObject.also {
                it.requireExactKeys("base_url", "family_id", "membership_id", "device_id")
            }.let { targetRoot ->
                FamilySessionReplica.RecoveryTarget(
                    baseUrl = targetRoot.requiredString("base_url"),
                    familyId = targetRoot.optionalString("family_id"),
                    membershipId = targetRoot.optionalString("membership_id"),
                    deviceId = targetRoot.optionalString("device_id"),
                )
            }
        }
    }
    val roots = root.getValue("roots").jsonArray.map { value ->
        value.jsonObject.also {
            it.requireExactKeys("entity_type", "client_uuid", "content_epoch", "was_pending")
        }.let { resetRoot ->
            FamilySessionReplica.ResetRoot(
                entityType = resetRoot.requiredString("entity_type"),
                clientUuid = resetRoot.requiredString("client_uuid"),
                contentEpoch = resetRoot.getValue("content_epoch").jsonPrimitive.long,
                wasPending = resetRoot.getValue("was_pending").jsonPrimitive.boolean,
            )
        }
    }
    require(roots.map { it.entityType to it.clientUuid }.distinct().size == roots.size) {
        "replica reset receipt contains duplicate roots"
    }
    return FamilySessionReplica.ResetReceipt(
        previousFamilyId = root.rawString("previous_family_id"),
        previousMembershipId = root.rawString("previous_membership_id"),
        previousDeviceId = root.rawString("previous_device_id"),
        crossingFamilyBoundary = root.getValue("crossing_family_boundary").jsonPrimitive.boolean,
        recoveryTarget = target,
        roots = roots,
    )
}

private fun JsonObject.requireExactKeys(vararg expected: String) {
    require(keys == expected.toSet()) { "replica reset receipt fields are invalid" }
}

private fun JsonObject.requiredString(key: String): String =
    rawString(key).also {
        require(it.isNotBlank()) { "replica reset receipt $key is blank" }
    }

private fun JsonObject.rawString(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.optionalString(key: String): String? =
    getValue(key).let { if (it is JsonNull) null else it.jsonPrimitive.content }
