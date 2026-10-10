package com.lezi.babylog.sync.disasterrecovery

import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.sync.engine.decodeFrozenMediaSpoolManifest
import com.lezi.babylog.sync.media.ImmutableMediaSpoolRecovery
import com.lezi.babylog.sync.media.ImmutableMediaSpool
import com.lezi.babylog.sync.media.SyncMediaFileStore
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Reclaims artifacts after a completed authority switch or a durable local retirement decision.
 * Replay controls must be retired first; live sole-copy bytes remain owned until verified replacement.
 * This is never the authority switch's commit point.
 */
internal class RestoreArtifactRetirement(
    private val cache: ConflictSnapshotCacheDao,
    private val media: MediaAssetDao,
    private val files: SyncMediaFileStore,
    private val spool: ImmutableMediaSpool,
    private val transactions: DatabaseTransactionRunner,
) {
    /** Caller has durably retired replay controls. Never reads the full snapshot or photo rows. */
    suspend fun compactSnapshot(expectedSnapshotKey: String? = null) {
        transactions.run {
            // Read the latest owner inside the transaction, not a copy from a prior file pass.
            val journal = cache.getTransportJournal(KEY) ?: return@run
            val value = Json.parseToJsonElement(journal.payloadJson).jsonObject
            require(value["format"]?.jsonPrimitive?.int == 1)
            val snapshotKey = value.getValue("snapshot_key").jsonPrimitive.content
            val prefix = RestoreSnapshotJournal.key("")
            if (!snapshotKey.startsWith(prefix) || snapshotKey.length == prefix.length) return@run
            if (expectedSnapshotKey != null && snapshotKey != expectedSnapshotKey) return@run
            cache.deleteTransportJournal(snapshotKey)
            // Empty is no snapshot reference in the existing format; preserve every current group.
            cache.putTransportJournal(KEY,
                JsonObject(value + ("snapshot_key" to JsonPrimitive(""))).toString(), journal.contentEpoch)
        }
    }

    suspend fun reclaim() {
        compactSnapshot()
        val journal = cache.getTransportJournal(KEY) ?: return
        val value = Json.parseToJsonElement(journal.payloadJson).jsonObject
        require(value["format"]?.jsonPrimitive?.int == 1)
        val snapshotKey = value.getValue("snapshot_key").jsonPrimitive.content
        val ids = value.getValue("groups").jsonArray.map { it.jsonPrimitive.content }
        val rows = transactions.run { media.listAllIncludingDeleted().associateBy { it.clientUuid } }
        val protectedTerminalRows = cache.listRestoreTerminalSpoolSeals()
        val protectedTerminalIds = protectedTerminalRows.mapTo(hashSetOf()) { RestoreTerminalSpoolSeal.decode(it).group.mutationId }
        val replacementRows = cache.listFrozenMediaSpoolManifests().filter { row ->
            decodeFrozenMediaSpoolManifest(row.payloadJson).mutationId !in ids &&
                decodeFrozenMediaSpoolManifest(row.payloadJson).mutationId !in protectedTerminalIds
        }
        val replacementGroups = replacementRows.mapNotNull { row ->
            val manifest = decodeFrozenMediaSpoolManifest(row.payloadJson)
            val recovered = spool.recoverGroup(manifest.mutationId) as? ImmutableMediaSpoolRecovery.Complete
            manifest.takeIf { recovered?.group == it }
        }
        // These groups have their own guarded deletion owner, including partial-unlink restart.
        val terminalRows = protectedTerminalRows
        val terminalIds = terminalRows.mapTo(hashSetOf()) { RestoreTerminalSpoolSeal.decode(it).group.mutationId }
        val ready = mutableListOf<String>()
        for (id in ids) {
            if (id in terminalIds) continue
            val manifest = cache.getFrozenMediaSpoolManifest(id)
            if (manifest == null) { ready += id; continue }
            val group = decodeFrozenMediaSpoolManifest(manifest.payloadJson)
            // Only plain quarantine manifests may be reclaimed. A new bound operation owns itself.
            require(Json.parseToJsonElement(manifest.payloadJson).jsonObject["contract"]?.jsonPrimitive?.content
                != "causal-media-settlement-v1")
            val hasReplacement = withContext(Dispatchers.IO) {
                group.items.all { item ->
                    val row = rows[item.mediaUuid]?.takeIf { it.deletedAt == null }
                    if (row == null) true else if (replacementGroups.any { replacement ->
                            replacement.items.any { it.copy(slot = item.slot) == item }
                        }) true else {
                        val file = files.readableFile(row.localUri)
                        if (file == null || file.length() != item.byteSize) false else {
                            val digest = MessageDigest.getInstance("SHA-256")
                            file.inputStream().buffered().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count > 0) digest.update(buffer, 0, count)
                                }
                            }
                            digest.digest().joinToString("") { "%02x".format(it) } == item.sha256
                        }
                    }
                }
            }
            if (hasReplacement) ready += id
        }
        val retired = transactions.run {
            // Domain writes stay allowed during the file pass. Revalidate before dropping evidence.
            if (media.listAllIncludingDeleted().associateBy { it.clientUuid } != rows ||
                cache.getTransportJournal(KEY) != journal || cache.listRestoreTerminalSpoolSeals() != terminalRows || replacementRows.any { row ->
                    cache.getTransportJournal(row.journalKey) != row
                }) return@run emptyList<String>()
            ready.forEach {
                cache.deleteFrozenMediaSpoolManifest(it)
                cache.deleteTransportJournal("media-freeze-capture-v1:$it")
                cache.deleteTransportJournal("media-preparation-sources-v1:$it")
            }
            val remaining = ids - ready.toSet()
            if (remaining.isEmpty()) {
                if (snapshotKey.isNotEmpty()) cache.deleteTransportJournal(snapshotKey)
                cache.deleteTransportJournal(KEY)
            } else {
                cache.putTransportJournal(KEY, encode(snapshotKey, remaining), 0)
            }
            ready.toList()
        }
        // An interrupted unlink is an unreferenced spool, reclaimed by ordinary spool recovery.
        retired.forEach { spool.discardGroup(it) }
    }

    companion object {
        const val KEY = "restore-artifact-retirement-v1:current"
        fun encode(snapshotKey: String, groupIds: List<String>): String = buildJsonObject {
            put("format", 1); put("snapshot_key", snapshotKey)
            put("groups", JsonArray(groupIds.distinct().sorted().map(::JsonPrimitive)))
        }.toString()
    }
}
