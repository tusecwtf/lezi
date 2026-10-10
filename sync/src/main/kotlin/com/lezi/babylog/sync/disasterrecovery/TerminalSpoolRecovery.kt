package com.lezi.babylog.sync.disasterrecovery

import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.frozenMediaSpoolCacheKey
import com.lezi.babylog.sync.engine.decodeCausalMediaSettlementOrNull
import com.lezi.babylog.sync.engine.decodeFrozenMediaSpoolManifest
import com.lezi.babylog.sync.media.ImmutableMediaSpool
import com.lezi.babylog.sync.media.ImmutableMediaSpoolRecovery
import com.lezi.babylog.sync.media.TerminalRetirementSpool

/** Opaque partial-file recovery is permitted only while the exact plain Room owner survives. */
internal suspend fun terminalSealsForSpoolRecovery(cache: ConflictSnapshotCacheDao): List<RestoreTerminalSpoolSeal> {
    val seals = cache.listRestoreTerminalSpoolSeals().map(RestoreTerminalSpoolSeal::decode)
    val deleting = seals.filter { it.deleting }
    if (deleting.isNotEmpty()) {
        val frozen = cache.listFrozenMediaSpoolManifests().associateBy { it.journalKey }
        deleting.forEach { seal ->
            val row = frozen[frozenMediaSpoolCacheKey(seal.group.mutationId)]
            require(row != null && row.contentEpoch == 0L &&
                decodeCausalMediaSettlementOrNull(row.payloadJson) == null &&
                decodeFrozenMediaSpoolManifest(row.payloadJson) == seal.group) {
                "terminal spool deleting seal lost its exact plain Room owner"
            }
        }
    }
    return seals
}

internal suspend fun ImmutableMediaSpool.recoverRetainingTerminalSeals(
    cache: ConflictSnapshotCacheDao,
    retained: Set<String>,
): Map<String, ImmutableMediaSpoolRecovery> {
    val seals = terminalSealsForSpoolRecovery(cache)
    val ids = retained + seals.map { it.group.mutationId }
    val deleting = seals.filter { it.deleting }
    return if (deleting.isEmpty()) recoverAndSweep(ids) else {
        requireNotNull(this as? TerminalRetirementSpool) { "terminal spool deleting owner is unavailable" }
            .recoverAndSweepRetainingOpaque(ids, deleting.map { it.group })
    }
}
