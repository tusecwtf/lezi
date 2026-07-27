package com.lezi.babylog.core.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/** Scope of a crash-recoverable local replica cleanup hand-off. */
enum class PendingReplicaCleanupScope {
    RECORDS_ONLY,
    ALL_LOCAL,
}

/**
 * Durable snapshot written in the same Room transaction as the domain clear.
 *
 * File paths and media ids are the exact pre-clear set. Recovery therefore
 * remains idempotent without deleting media created after a process restart.
 */
data class PendingReplicaCleanup(
    val scope: PendingReplicaCleanupScope,
    val familyId: String,
    val pullGeneration: String,
    val mediaClientUuids: Set<String>,
    val localMediaPaths: Set<String>,
)

interface PendingReplicaCleanupStore {
    suspend fun load(): PendingReplicaCleanup?
    suspend fun stage(pending: PendingReplicaCleanup)
    suspend fun delete()
}

class CorruptPendingReplicaCleanupException internal constructor(
    detail: String,
) : IllegalStateException("Corrupt pending replica cleanup: $detail")

internal class RoomPendingReplicaCleanupStore(
    private val dao: PendingReplicaCleanupDao,
) : PendingReplicaCleanupStore {
    override suspend fun load(): PendingReplicaCleanup? =
        dao.get(OPERATION_KEY)?.toSnapshot()

    override suspend fun stage(pending: PendingReplicaCleanup) {
        require(pending.mediaClientUuids.none(String::isBlank)) {
            "Pending replica cleanup media ids must not be blank"
        }
        require(pending.localMediaPaths.none(String::isBlank)) {
            "Pending replica cleanup media paths must not be blank"
        }
        dao.insert(
            PendingReplicaCleanupEntity(
                operation = OPERATION_KEY,
                scope = pending.scope.storageKey,
                familyId = pending.familyId,
                pullGeneration = pending.pullGeneration,
                mediaClientUuidsJson = encodeStringSet(pending.mediaClientUuids),
                localMediaPathsJson = encodeStringSet(pending.localMediaPaths),
            ),
        )
    }

    override suspend fun delete() {
        dao.delete(OPERATION_KEY)
    }

    private fun PendingReplicaCleanupEntity.toSnapshot(): PendingReplicaCleanup {
        if (operation != OPERATION_KEY) {
            throw CorruptPendingReplicaCleanupException("unknown operation")
        }
        return PendingReplicaCleanup(
            scope = when (scope) {
                PendingReplicaCleanupScope.RECORDS_ONLY.storageKey ->
                    PendingReplicaCleanupScope.RECORDS_ONLY
                PendingReplicaCleanupScope.ALL_LOCAL.storageKey ->
                    PendingReplicaCleanupScope.ALL_LOCAL
                else -> throw CorruptPendingReplicaCleanupException("unknown scope")
            },
            familyId = familyId,
            pullGeneration = pullGeneration,
            mediaClientUuids = decodeStringSet(mediaClientUuidsJson, "media ids"),
            localMediaPaths = decodeStringSet(localMediaPathsJson, "media paths"),
        )
    }
}

private val PendingReplicaCleanupScope.storageKey: String
    get() = when (this) {
        PendingReplicaCleanupScope.RECORDS_ONLY -> "records_only"
        PendingReplicaCleanupScope.ALL_LOCAL -> "all_local"
    }

private fun encodeStringSet(values: Set<String>): String =
    buildJsonArray {
        values.sorted().forEach { add(JsonPrimitive(it)) }
    }.toString()

private fun decodeStringSet(raw: String, field: String): Set<String> {
    val array = try {
        Json.parseToJsonElement(raw) as? JsonArray
            ?: throw CorruptPendingReplicaCleanupException("$field is not an array")
    } catch (error: CorruptPendingReplicaCleanupException) {
        throw error
    } catch (_: Exception) {
        throw CorruptPendingReplicaCleanupException("invalid $field JSON")
    }
    return array.mapTo(linkedSetOf()) { element ->
        val primitive = element as? JsonPrimitive
            ?: throw CorruptPendingReplicaCleanupException("$field contains a non-string")
        if (!primitive.isString || primitive.content.isBlank()) {
            throw CorruptPendingReplicaCleanupException("$field contains an invalid string")
        }
        primitive.content
    }
}

private const val OPERATION_KEY = "local_replica_clear"
