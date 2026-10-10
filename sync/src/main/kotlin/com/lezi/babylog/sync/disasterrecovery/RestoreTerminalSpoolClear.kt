package com.lezi.babylog.sync.disasterrecovery

import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.database.PendingReplicaCleanup
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.sync.media.CausalMediaRole
import kotlinx.serialization.json.*

internal data class TerminalSpoolClearCapture internal constructor(
    val scope: LocalDataClearScope,
    internal val seals: List<RestoreTerminalSpoolSeal>,
) {
    val mediaClientUuids: Set<String> get() = seals.flatMapTo(linkedSetOf()) { seal -> seal.group.items.map { it.mediaUuid } }
}

/** Binds explicit local clear to exact pre-clear seals as well as the existing captured item set. */
internal object RestoreTerminalSpoolClear {
    const val KEY = "restore-terminal-spool-clear-v1:current"
    suspend fun capture(cache: ConflictSnapshotCacheDao, scope: LocalDataClearScope): TerminalSpoolClearCapture =
        TerminalSpoolClearCapture(scope, cache.listRestoreTerminalSpoolSeals().map(RestoreTerminalSpoolSeal::decode)
            .filter { seal -> scope == LocalDataClearScope.AllLocalData ||
                (seal.rootType != "baby" && seal.group.items.none { it.role == CausalMediaRole.Avatar }) })

    suspend fun bind(cache: ConflictSnapshotCacheDao, pending: PendingReplicaCleanup, capture: TerminalSpoolClearCapture) {
        require(capture.scope == pending.scope && pending.mediaClientUuids.containsAll(capture.mediaClientUuids))
        capture.seals.forEach { seal ->
            val current = cache.getTransportJournal(seal.key)?.let(RestoreTerminalSpoolSeal::decode)
            require(current == seal) { "terminal spool clear capture changed" }
        }
        cache.putTransportJournal(KEY, buildJsonObject {
            put("contract", "restore_terminal_spool_clear_v1")
            put("pending_digest", pendingDigest(pending))
            put("seals", JsonObject(capture.seals.associate { it.key to JsonPrimitive(sealDigest(it)) }))
        }.toString(), 0)
    }

    suspend fun matches(cache: ConflictSnapshotCacheDao, pending: PendingReplicaCleanup, seal: RestoreTerminalSpoolSeal): Boolean {
        val row = cache.getTransportJournal(KEY) ?: return false
        val value = Json.parseToJsonElement(row.payloadJson).jsonObject
        require(row.contentEpoch == 0L && value.keys == setOf("contract", "pending_digest", "seals"))
        require(value["contract"] == JsonPrimitive("restore_terminal_spool_clear_v1"))
        return value["pending_digest"] == JsonPrimitive(pendingDigest(pending)) &&
            value.getValue("seals").jsonObject[seal.key] == JsonPrimitive(sealDigest(seal))
    }

    private fun sealDigest(seal: RestoreTerminalSpoolSeal) = terminalEvidenceDigest(seal.copy(deleting = false).encode())
    private fun pendingDigest(pending: PendingReplicaCleanup) = terminalEvidenceDigest(buildJsonObject {
        put("scope", pending.scope.name); put("family", pending.familyId); put("generation", pending.pullGeneration)
        put("ids", JsonArray(pending.mediaClientUuids.sorted().map(::JsonPrimitive)))
        put("paths", JsonArray(pending.localMediaPaths.sorted().map(::JsonPrimitive)))
    }.toString())
}
