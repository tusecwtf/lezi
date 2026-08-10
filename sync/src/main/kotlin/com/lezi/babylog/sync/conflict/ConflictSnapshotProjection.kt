package com.lezi.babylog.sync.conflict

import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheEntity
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.core.database.causal.ConflictSummaryEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * One owner for lossless snapshot persistence and its list/root summary.
 * H05 promotes only a complete first page; H08 will extend this seam with staged pages.
 */
class ConflictSnapshotProjection(
    private val summaries: ConflictSummaryDao,
    private val snapshots: ConflictSnapshotCacheDao,
    private val transactions: DatabaseTransactionRunner,
) {
    suspend fun replaceComplete(snapshot: ConflictSnapshot) {
        require(snapshot.pageIndex == 0 && snapshot.complete && snapshot.continuation == null) {
            "H05 只接受单页 complete ConflictSnapshot"
        }
        val snapshotJson = ConflictSnapshotCodec.encode(snapshot)
        require(ConflictSnapshotCodec.decode(snapshotJson) == snapshot) {
            "ConflictSnapshot 不是 canonical typed snapshot"
        }
        transactions.run {
            removeOtherSnapshotsForRoot(snapshot)
            snapshots.upsert(
                ConflictSnapshotCacheEntity(
                    conflictId = snapshot.conflictId,
                    snapshotJson = snapshotJson,
                    cachedAt = maxOf(
                        snapshot.stable.receivedAt,
                        snapshot.branches.maxOfOrNull { it.receivedAt } ?: Long.MIN_VALUE,
                    ),
                ),
            )
            summaries.upsert(snapshot.toSummary())
        }
    }

    suspend fun read(conflictId: String): ConflictSnapshot? {
        val row = snapshots.get(conflictId) ?: return null
        if (row.legacyStableRootSentinel != "{}" ||
            row.legacyBaseRootSentinel != null ||
            row.legacyConflictPathsSentinel != "[]"
        ) return null
        return runCatching { ConflictSnapshotCodec.decode(row.snapshotJson) }
            .getOrNull()
            ?.takeIf {
                it.conflictId == conflictId && it.pageIndex == 0 &&
                    it.complete && it.continuation == null
            }
    }

    suspend fun clearRoot(entityType: ConflictRootType, clientUuid: String) {
        transactions.run {
            summaries.listForRoot(entityType.wireName, clientUuid).forEach { summary ->
                snapshots.delete(summary.conflictId)
                summaries.delete(summary.conflictId)
            }
        }
    }

    suspend fun clear(conflictId: String) {
        transactions.run {
            snapshots.delete(conflictId)
            summaries.delete(conflictId)
        }
    }

    private suspend fun removeOtherSnapshotsForRoot(snapshot: ConflictSnapshot) {
        summaries.listForRoot(snapshot.entityType.wireName, snapshot.clientUuid)
            .filter { it.conflictId != snapshot.conflictId }
            .forEach { stale ->
                snapshots.delete(stale.conflictId)
                summaries.delete(stale.conflictId)
            }
    }
}

private fun ConflictSnapshot.toSummary(): ConflictSummaryEntity =
    ConflictSummaryEntity(
        conflictId = conflictId,
        entityType = entityType.wireName,
        clientUuid = clientUuid,
        baseVersionId = stable.baseVersion,
        stableVersionId = stable.versionId,
        status = "open",
        kind = if (branches.isEmpty()) "tombstone_restore" else "concurrent",
        branchVersionIdsJson = JsonArray(branchVersionIds.map(::JsonPrimitive)).toString(),
        updatedAt = maxOf(
            stable.receivedAt,
            branches.maxOfOrNull { it.receivedAt } ?: Long.MIN_VALUE,
        ),
    )
