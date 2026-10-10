package com.lezi.babylog.sync.media

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.database.causal.CausalTransportJournalEntity
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.core.database.causal.conflictSnapshotStageCacheKey
import com.lezi.babylog.core.database.causal.terminalReceiptCacheKey
import com.lezi.babylog.sync.disasterrecovery.recoverRetainingTerminalSeals
import com.lezi.babylog.sync.engine.decodeCausalMediaSettlementOrNull
import com.lezi.babylog.sync.engine.decodeFrozenMediaSpoolManifest
import com.lezi.babylog.sync.sourcerelation.SOURCE_RELATION_COMMAND_JOURNAL_KEY

/** Scope-aware bridge between Room's clear transaction and private immutable media copies. */
internal class ScopedMediaSpoolClear(
    private val babyDao: BabyDao,
    private val cache: ConflictSnapshotCacheDao,
    private val summaries: ConflictSummaryDao,
    private val spool: ImmutableMediaSpool,
) {
    /**
     * Must run inside the same Room transaction as [clearRoom]. RecordsOnly keeps
     * baby/avatar facts, so it must keep their exact transport and conflict evidence
     * too. This is an in-memory save/restore within that transaction, never a second
     * durable journal or an independently committed deletion/recreation window.
     * Both scopes preserve compact restore-file ownership until the file lifecycle
     * can reclaim its inventory after the committed media-row cleanup.
     * An unresolved source command also keeps its original outcome evidence;
     * clearing local facts does not authorize abandoning that remote command.
     */
    suspend fun preserveRetainedEvidence(
        scope: LocalDataClearScope,
        clearRoom: suspend () -> Unit,
    ) {
        val terminalSeals = cache.listRestoreTerminalSpoolSeals()
        val sealedGroups = terminalSeals.map { row ->
            val seal = com.lezi.babylog.sync.disasterrecovery.RestoreTerminalSpoolSeal.decode(row)
            requireNotNull(cache.getFrozenMediaSpoolManifest(seal.group.mutationId))
        }
        val retainedJournals = cache.listRestoreFileOwners() + terminalSeals + sealedGroups +
            listOfNotNull(cache.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY))
        if (scope == LocalDataClearScope.AllLocalData) {
            clearRoom()
            retainedJournals.forEach { row ->
                cache.putTransportJournal(row.journalKey, row.payloadJson, row.contentEpoch)
            }
            return
        }
        val babies = babyDao.listAllIncludingDeleted()
        val babyUuids = babies.mapTo(hashSetOf()) { it.clientUuid }
        val babyMutationIds = babies.mapNotNullTo(hashSetOf()) { it.mutationId }
        val journals = linkedMapOf<String, CausalTransportJournalEntity>()
        fun retain(row: CausalTransportJournalEntity?) {
            if (row != null) journals[row.journalKey] = row
        }
        retainedJournals.forEach(::retain)
        for (baby in babies) {
            retain(cache.getFrozenMutation("baby", baby.clientUuid))
            retain(cache.getTransportJournal(terminalReceiptCacheKey("baby", baby.clientUuid)))
        }
        for (row in cache.listFrozenMediaSpoolManifests()) {
            val settlement = decodeCausalMediaSettlementOrNull(row.payloadJson)
            val retained = if (settlement != null) {
                settlement.binding.entityType == "baby" && settlement.binding.clientUuid in babyUuids
            } else {
                // Freeze may have committed its manifest before binding its settlement.
                decodeFrozenMediaSpoolManifest(row.payloadJson).mutationId in babyMutationIds
            }
            if (retained) retain(row)
        }
        val conflicts = babies.flatMap { summaries.listForRoot("baby", it.clientUuid) }
            .distinctBy { it.conflictId }
        val details = conflicts.mapNotNull { cache.get(it.conflictId) }
        for (conflict in conflicts) {
            retain(cache.getTransportJournal(conflictSnapshotStageCacheKey(conflict.conflictId)))
        }
        clearRoom()
        journals.values.forEach { row ->
            cache.putTransportJournal(row.journalKey, row.payloadJson, row.contentEpoch)
        }
        conflicts.forEach { summaries.upsert(it) }
        details.forEach { cache.upsert(it) }
    }

    /** Same final Room transaction that removes the exact pending-clear marker. */
    suspend fun retireCommittedClearBinding() {
        cache.deleteTransportJournal(com.lezi.babylog.sync.disasterrecovery.RestoreTerminalSpoolClear.KEY)
    }

    /** After Room committed, before retiring the existing durable clear marker. */
    suspend fun sweepAfterCommittedClear() {
        val retained = cache.listFrozenMediaSpoolManifests().mapTo(linkedSetOf()) { row ->
            decodeFrozenMediaSpoolManifest(row.payloadJson).mutationId
        }
        // A partially frozen avatar can own durable sidecars before the Room manifest
        // exists. Its unchanged baby mutation column already supplies that identity.
        babyDao.listAllIncludingDeleted().mapNotNullTo(retained) { it.mutationId }
        spool.recoverRetainingTerminalSeals(cache, retained)
    }
}
