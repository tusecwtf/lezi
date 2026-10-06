package com.lezi.babylog.core.database.causal

import androidx.room.Dao
import com.lezi.babylog.core.database.queryInChunks
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Canonical closed-member-set fingerprint embedded in PULL_SUMMARY mutation ids
 * (`pull-$relationId:$fingerprint`). The format is frozen by the 0.4.7 reader.
 */
internal fun sourceRelationMemberSetFingerprint(memberIds: Set<String>): String {
    val canonical = memberIds.sorted().joinToString(separator = "") { memberId ->
        val bytes = memberId.toByteArray(Charsets.UTF_8)
        "${bytes.size}:$memberId"
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}

@Dao
interface WakeObservationDao {
    @Query("SELECT * FROM wake_observations WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): WakeObservationEntity?

    @Query("SELECT * FROM wake_observations WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<WakeObservationEntity>

    suspend fun get(ids: List<Long>): List<WakeObservationEntity> =
        queryInChunks(ids, ::getByIds)

    @Query("SELECT * FROM wake_observations WHERE clientUuid = :uuid LIMIT 1")
    suspend fun getByClientUuid(uuid: String): WakeObservationEntity?

    @Query(
        """
        SELECT * FROM wake_observations
        WHERE sleepRecordClientUuid = :sleepRecordClientUuid
        ORDER BY wakeTimestamp ASC, clientUuid ASC
        """,
    )
    suspend fun listForSleep(sleepRecordClientUuid: String): List<WakeObservationEntity>

    /** Full history including tombstones; census reconcile reads live rows from this. */
    @Query("SELECT * FROM wake_observations ORDER BY id ASC")
    suspend fun listAllIncludingDeleted(): List<WakeObservationEntity>

    /**
     * Non-withdrawn live observations for provisional/effective sleep projection.
     * History including withdrawn/tombstoned uses [listForSleep].
     */
    @Query(
        """
        SELECT * FROM wake_observations
        WHERE sleepRecordClientUuid = :sleepRecordClientUuid
          AND deletedAt IS NULL
          AND withdrawn = 0
        ORDER BY wakeTimestamp ASC, clientUuid ASC
        """,
    )
    suspend fun listActiveForSleep(sleepRecordClientUuid: String): List<WakeObservationEntity>

    @Query("SELECT * FROM wake_observations WHERE syncDirty = 1 ORDER BY id ASC")
    suspend fun listPendingSync(): List<WakeObservationEntity>

    @Query(
        """
        SELECT * FROM wake_observations
        WHERE openConflictId IS NOT NULL
        ORDER BY id ASC
        """,
    )
    suspend fun listOpenConflicts(): List<WakeObservationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WakeObservationEntity): Long

    @Update
    suspend fun update(entity: WakeObservationEntity)

    @Query("DELETE FROM wake_observations")
    suspend fun deleteAll()

    /**
     * Freeze a dirty content epoch for reconcile/commit. Same local revision reuses
     * [WakeObservationEntity.mutationId]; a newer edit mints [newMutationId].
     */
    @Transaction
    suspend fun freezeDirtyEpoch(
        clientUuid: String,
        contentEpoch: Long,
        newMutationId: String,
    ): WakeObservationEntity? {
        val current = getByClientUuid(clientUuid) ?: return null
        // Free function (not this method): package-level freezeDirtyEpoch.
        val next = com.lezi.babylog.core.database.causal.freezeDirtyEpoch(
            current = current.toCausalMutationState(),
            contentEpoch = contentEpoch,
            newMutationId = newMutationId,
        ) ?: return null
        val cols = next.toAppliedColumns()
        val written = current.copy(
            updatedAt = contentEpoch,
            baseVersion = cols.baseVersion,
            mutationId = cols.mutationId,
            syncDirty = cols.syncDirty,
            openConflictId = cols.openConflictId,
            localBranchVersionId = cols.localBranchVersionId,
        )
        update(written)
        return written
    }

    /** Freeze one no-media WakeObservation envelope at its captured fact epoch. */
    @Transaction
    suspend fun freezeCommitFirstEpoch(
        clientUuid: String,
        contentEpoch: Long,
        newMutationId: String,
    ): WakeObservationEntity? {
        val current = getByClientUuid(clientUuid) ?: return null
        val next = com.lezi.babylog.core.database.causal.freezeCommitFirstEpoch(
            current = current.toCausalMutationState(),
            contentEpoch = contentEpoch,
            newMutationId = newMutationId,
        ) ?: return null
        val cols = next.toAppliedColumns()
        val written = current.copy(
            baseVersion = cols.baseVersion,
            mutationId = cols.mutationId,
            syncDirty = cols.syncDirty,
            openConflictId = cols.openConflictId,
            localBranchVersionId = cols.localBranchVersionId,
        )
        update(written)
        return written
    }

    /** Settle a WakeObservation terminal while preserving a newer observed fact. */
    @Transaction
    suspend fun settleCommitFirstAcceptedOrMerged(
        clientUuid: String,
        expectedMutationId: String,
        expectedContentEpoch: Long,
        newBaseVersion: String,
    ): CommitFirstSettlementEpoch? {
        val current = getByClientUuid(clientUuid) ?: return null
        val settled = com.lezi.babylog.core.database.causal.settleCommitFirstAcceptedOrMerged(
            current = current.toCausalMutationState(),
            expectedMutationId = expectedMutationId,
            expectedContentEpoch = expectedContentEpoch,
            newBaseVersion = newBaseVersion,
        ) ?: return null
        val cols = settled.state.toAppliedColumns()
        update(
            current.copy(
                baseVersion = cols.baseVersion,
                mutationId = cols.mutationId,
                syncDirty = cols.syncDirty,
                openConflictId = cols.openConflictId,
                localBranchVersionId = cols.localBranchVersionId,
                familyPublishedUpdatedAt = expectedContentEpoch,
            ),
        )
        return settled.epoch
    }

    /** Same epoch policy as accepted/merged for a durable WakeObservation branch. */
    @Transaction
    suspend fun settleCommitFirstBranched(
        clientUuid: String,
        expectedMutationId: String,
        expectedContentEpoch: Long,
        conflictId: String,
        branchVersionId: String,
        stableBaseVersion: String,
    ): CommitFirstSettlementEpoch? {
        val current = getByClientUuid(clientUuid) ?: return null
        val settled = com.lezi.babylog.core.database.causal.settleCommitFirstBranched(
            current = current.toCausalMutationState(),
            expectedMutationId = expectedMutationId,
            expectedContentEpoch = expectedContentEpoch,
            conflictId = conflictId,
            branchVersionId = branchVersionId,
            stableBaseVersion = stableBaseVersion,
        ) ?: return null
        val cols = settled.state.toAppliedColumns()
        update(
            current.copy(
                baseVersion = cols.baseVersion,
                mutationId = cols.mutationId,
                syncDirty = cols.syncDirty,
                openConflictId = cols.openConflictId,
                localBranchVersionId = cols.localBranchVersionId,
            ),
        )
        return settled.epoch
    }

    /** Exact CAS accepted/merged ack for the frozen mutation epoch. */
    @Transaction
    suspend fun acknowledgeCausalAcceptedOrMerged(
        clientUuid: String,
        expectedMutationId: String,
        expectedContentEpoch: Long,
        newBaseVersion: String,
    ): Boolean {
        val current = getByClientUuid(clientUuid) ?: return false
        val next = com.lezi.babylog.core.database.causal.acknowledgeAcceptedOrMerged(
            current = current.toCausalMutationState(),
            expectedMutationId = expectedMutationId,
            expectedContentEpoch = expectedContentEpoch,
            newBaseVersion = newBaseVersion,
        ) ?: return false
        val cols = next.toAppliedColumns()
        update(
            current.copy(
                baseVersion = cols.baseVersion,
                mutationId = cols.mutationId,
                syncDirty = cols.syncDirty,
                openConflictId = cols.openConflictId,
                localBranchVersionId = cols.localBranchVersionId,
                familyPublishedUpdatedAt = expectedContentEpoch,
            ),
        )
        return true
    }

    /** Atomic `branched` receipt: pending → unresolved conflict (no infinite resend). */
    @Transaction
    suspend fun acknowledgeCausalBranched(
        clientUuid: String,
        expectedMutationId: String,
        expectedContentEpoch: Long,
        conflictId: String,
        branchVersionId: String,
        stableBaseVersion: String,
    ): Boolean {
        val current = getByClientUuid(clientUuid) ?: return false
        val next = com.lezi.babylog.core.database.causal.acknowledgeBranched(
            current = current.toCausalMutationState(),
            expectedMutationId = expectedMutationId,
            expectedContentEpoch = expectedContentEpoch,
            conflictId = conflictId,
            branchVersionId = branchVersionId,
            stableBaseVersion = stableBaseVersion,
        ) ?: return false
        val cols = next.toAppliedColumns()
        update(
            current.copy(
                baseVersion = cols.baseVersion,
                mutationId = cols.mutationId,
                syncDirty = cols.syncDirty,
                openConflictId = cols.openConflictId,
                localBranchVersionId = cols.localBranchVersionId,
            ),
        )
        return true
    }
}

internal fun WakeObservationEntity.toCausalMutationState(): CausalRootMutationState =
    CausalRootMutationState(
        baseVersion = baseVersion,
        mutationId = mutationId,
        contentEpoch = updatedAt,
        syncDirty = syncDirty,
        openConflictId = openConflictId,
        localBranchVersionId = localBranchVersionId,
    )

@Dao
interface ConflictSummaryDao {
    @Query(
        """
        WITH canonical AS (
            SELECT summary.*
            FROM conflict_summaries summary
            WHERE summary.status = 'open'
              AND summary.branchVersionIdsJson != '[]'
              AND NOT EXISTS (
                  SELECT 1
                  FROM conflict_summaries newer
                  WHERE newer.status = 'open'
                    AND newer.branchVersionIdsJson != '[]'
                    AND newer.entityType = summary.entityType
                    AND newer.clientUuid = summary.clientUuid
                    AND (
                        newer.updatedAt > summary.updatedAt OR
                        (newer.updatedAt = summary.updatedAt AND newer.conflictId < summary.conflictId)
                    )
              )
        ), root_projection AS (
            SELECT summary.conflictId, summary.entityType, summary.clientUuid, summary.updatedAt,
                   baby.nickname AS localTitle, baby.nickname AS babyLabel,
                   NULL AS localActorId,
                   CASE WHEN baby.id IS NULL THEN NULL ELSE baby.deletedAt IS NOT NULL END AS localTombstone,
                   (SELECT COUNT(*) FROM media_assets media
                    WHERE media.babyId = baby.id AND media.kind = 'avatar' AND media.deletedAt IS NULL)
                       AS localMediaCount
            FROM canonical summary
            LEFT JOIN babies baby ON baby.clientUuid = summary.clientUuid
            WHERE summary.entityType = 'baby'
            UNION ALL
            SELECT summary.conflictId, summary.entityType, summary.clientUuid, summary.updatedAt,
                   record.type AS localTitle, baby.nickname AS babyLabel,
                   record.createdByMembershipId AS localActorId,
                   CASE WHEN record.id IS NULL THEN NULL ELSE record.deletedAt IS NOT NULL END AS localTombstone,
                   (SELECT COUNT(*) FROM media_assets media
                    WHERE media.recordId = record.id AND media.deletedAt IS NULL) AS localMediaCount
            FROM canonical summary
            LEFT JOIN records record ON record.clientUuid = summary.clientUuid
            LEFT JOIN babies baby ON baby.id = record.babyId
            WHERE summary.entityType = 'record'
            UNION ALL
            SELECT summary.conflictId, summary.entityType, summary.clientUuid, summary.updatedAt,
                   carePlan.type AS localTitle, baby.nickname AS babyLabel,
                   carePlan.createdByMembershipId AS localActorId,
                   CASE WHEN carePlan.id IS NULL THEN NULL ELSE carePlan.deletedAt IS NOT NULL END AS localTombstone,
                   (SELECT COUNT(*) FROM media_assets media
                    WHERE media.carePlanId = carePlan.id AND media.deletedAt IS NULL) AS localMediaCount
            FROM canonical summary
            LEFT JOIN care_plans carePlan ON carePlan.clientUuid = summary.clientUuid
            LEFT JOIN babies baby ON baby.id = carePlan.babyId
            WHERE summary.entityType = 'care_plan'
            UNION ALL
            SELECT summary.conflictId, summary.entityType, summary.clientUuid, summary.updatedAt,
                   item.name AS localTitle, NULL AS babyLabel,
                   item.createdByMembershipId AS localActorId,
                   CASE WHEN item.id IS NULL THEN NULL ELSE item.deletedAt IS NOT NULL END AS localTombstone,
                   0 AS localMediaCount
            FROM canonical summary
            LEFT JOIN custom_items item ON item.clientUuid = summary.clientUuid
            WHERE summary.entityType = 'custom_item'
            UNION ALL
            SELECT summary.conflictId, summary.entityType, summary.clientUuid, summary.updatedAt,
                   NULL AS localTitle, baby.nickname AS babyLabel,
                   wake.observerMembershipId AS localActorId,
                   CASE WHEN wake.id IS NULL THEN NULL ELSE wake.deletedAt IS NOT NULL END AS localTombstone,
                   (SELECT COUNT(*) FROM media_assets media
                    WHERE media.wakeObservationId = wake.id AND media.deletedAt IS NULL) AS localMediaCount
            FROM canonical summary
            LEFT JOIN wake_observations wake ON wake.clientUuid = summary.clientUuid
            LEFT JOIN records sleep ON sleep.clientUuid = wake.sleepRecordClientUuid
            LEFT JOIN babies baby ON baby.id = sleep.babyId
            WHERE summary.entityType = 'wake_observation'
        )
        SELECT projection.*,
               CASE
                   WHEN cache.stableRootJson = '{}'
                    AND cache.baseRootJson IS NULL
                    AND cache.conflictPathsJson = '[]'
                   THEN cache.branchesJson
                   ELSE NULL
               END AS snapshotJson
        FROM root_projection projection
        LEFT JOIN conflict_detail_cache cache ON cache.conflictId = projection.conflictId
        ORDER BY projection.updatedAt DESC, projection.conflictId ASC
        """,
    )
    fun observeInboxProjection(): Flow<List<ConflictInboxProjectionRow>>

    @Query("SELECT * FROM conflict_summaries WHERE conflictId = :conflictId LIMIT 1")
    suspend fun get(conflictId: String): ConflictSummaryEntity?

    @Query(
        """
        SELECT * FROM conflict_summaries
        WHERE entityType = :entityType AND clientUuid = :clientUuid
        ORDER BY updatedAt DESC
        """,
    )
    suspend fun listForRoot(entityType: String, clientUuid: String): List<ConflictSummaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConflictSummaryEntity)

    @Query("DELETE FROM conflict_summaries WHERE conflictId = :conflictId")
    suspend fun delete(conflictId: String)

    @Query("DELETE FROM conflict_summaries")
    suspend fun deleteAll()
}

@Dao
interface ConflictSnapshotCacheDao {
    @Query("SELECT * FROM conflict_detail_cache WHERE conflictId = :conflictId LIMIT 1")
    suspend fun get(conflictId: String): ConflictSnapshotCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConflictSnapshotCacheEntity)

    @Query("DELETE FROM conflict_detail_cache WHERE conflictId = :conflictId")
    suspend fun delete(conflictId: String)

    /** Removes both the promoted snapshot and any resumable H08 page evidence. */
    @Transaction
    suspend fun deleteConflictState(conflictId: String) {
        delete(conflictId)
        deleteTransportJournal(conflictSnapshotStageCacheKey(conflictId))
    }

    @Query("DELETE FROM conflict_detail_cache")
    suspend fun deleteAll()

    @Query("SELECT * FROM causal_transport_journal WHERE journalKey = :journalKey LIMIT 1")
    suspend fun getTransportJournal(journalKey: String): CausalTransportJournalEntity?

    @Query(
        "INSERT OR REPLACE INTO causal_transport_journal(journalKey, payloadJson, contentEpoch) " +
            "VALUES(:journalKey, :payloadJson, :contentEpoch)",
    )
    suspend fun putTransportJournal(journalKey: String, payloadJson: String, contentEpoch: Long)

    @Query("DELETE FROM causal_transport_journal WHERE journalKey = :journalKey")
    suspend fun deleteTransportJournal(journalKey: String)

    @Query("DELETE FROM causal_transport_journal")
    suspend fun deleteAllTransportJournals()

    /** Durable Room 28 transport storage for one immutable commit-first mutation envelope. */
    suspend fun getFrozenMutation(
        entityType: String,
        clientUuid: String,
    ): CausalTransportJournalEntity? =
        getTransportJournal(frozenMutationCacheKey(entityType, clientUuid))

    suspend fun putFrozenMutation(
        entityType: String,
        clientUuid: String,
        canonicalEnvelopeJson: String,
        contentEpoch: Long,
    ) {
        putTransportJournal(
            journalKey = frozenMutationCacheKey(entityType, clientUuid),
            payloadJson = canonicalEnvelopeJson,
            contentEpoch = contentEpoch,
        )
    }

    suspend fun deleteFrozenMutation(entityType: String, clientUuid: String) {
        deleteTransportJournal(frozenMutationCacheKey(entityType, clientUuid))
    }

    @Query(
        "SELECT * FROM causal_transport_journal WHERE journalKey >= '" +
            FROZEN_MEDIA_SPOOL_KEY_PREFIX + "' AND journalKey < '" +
            FROZEN_MEDIA_SPOOL_KEY_RANGE_END + "' ORDER BY journalKey ASC",
    )
    suspend fun listFrozenMediaSpoolManifests(): List<CausalTransportJournalEntity>

    suspend fun getFrozenMediaSpoolManifest(mutationId: String): CausalTransportJournalEntity? =
        getTransportJournal(frozenMediaSpoolCacheKey(mutationId))

    suspend fun putFrozenMediaSpoolManifest(
        mutationId: String,
        canonicalManifestJson: String,
        contentEpoch: Long,
    ) {
        putTransportJournal(
            journalKey = frozenMediaSpoolCacheKey(mutationId),
            payloadJson = canonicalManifestJson,
            contentEpoch = contentEpoch,
        )
    }

    suspend fun deleteFrozenMediaSpoolManifest(mutationId: String) {
        deleteTransportJournal(frozenMediaSpoolCacheKey(mutationId))
    }
    suspend fun getTerminalReceipt(entityType: String, clientUuid: String): TerminalRejectionReceipt? =
        getTransportJournal(terminalReceiptCacheKey(entityType, clientUuid))?.let {
            runCatching { decodeTerminalReceipt(it.payloadJson) }.getOrNull()
        }

    suspend fun putTerminalReceipt(receipt: TerminalRejectionReceipt) {
        putTransportJournal(
            journalKey = terminalReceiptCacheKey(receipt.entityType, receipt.clientUuid),
            payloadJson = encodeTerminalReceipt(receipt),
            contentEpoch = receipt.contentEpoch,
        )
    }

    suspend fun deleteTerminalReceipt(entityType: String, clientUuid: String) {
        deleteTransportJournal(terminalReceiptCacheKey(entityType, clientUuid))
    }

    @Query(
        "SELECT * FROM causal_transport_journal WHERE journalKey >= '" +
            TERMINAL_RECEIPT_KEY_PREFIX + "' AND journalKey < '" +
            TERMINAL_RECEIPT_KEY_RANGE_END + "' ORDER BY contentEpoch ASC, journalKey ASC",
    )
    suspend fun listTerminalReceiptJournals(): List<CausalTransportJournalEntity>

    suspend fun listTerminalReceipts(): List<TerminalRejectionReceipt> =
        listTerminalReceiptJournals().mapNotNull {
            runCatching { decodeTerminalReceipt(it.payloadJson) }.getOrNull()
        }
    @Query(
        "SELECT * FROM causal_transport_journal WHERE journalKey >= '" +
            TERMINAL_RECEIPT_KEY_PREFIX + "' AND journalKey < '" +
            TERMINAL_RECEIPT_KEY_RANGE_END + "' ORDER BY contentEpoch ASC, journalKey ASC",
    )
    fun observeTerminalReceiptJournals(): Flow<List<CausalTransportJournalEntity>>


    @Query(
        "SELECT * FROM causal_transport_journal WHERE journalKey >= '" +
            PULL_DIAGNOSTIC_KEY_PREFIX + "' AND journalKey < '" +
            PULL_DIAGNOSTIC_KEY_RANGE_END + "' ORDER BY contentEpoch ASC, journalKey ASC",
    )
    suspend fun listPullDiagnosticJournals(): List<CausalTransportJournalEntity>

    suspend fun listPullDiagnostics(): List<PullDiagnosticReceipt> =
        listPullDiagnosticJournals().mapNotNull {
            runCatching { decodePullDiagnosticReceipt(it.payloadJson) }.getOrNull()
        }
    @Query(
        "SELECT * FROM causal_transport_journal WHERE journalKey >= '" +
            PULL_DIAGNOSTIC_KEY_PREFIX + "' AND journalKey < '" +
            PULL_DIAGNOSTIC_KEY_RANGE_END + "' ORDER BY contentEpoch ASC, journalKey ASC",
    )
    fun observePullDiagnosticJournals(): Flow<List<CausalTransportJournalEntity>>


    suspend fun getPullDiagnostic(entityType: String, clientUuid: String): PullDiagnosticReceipt? =
        getTransportJournal(pullDiagnosticCacheKey(entityType, clientUuid))?.let {
            runCatching { decodePullDiagnosticReceipt(it.payloadJson) }.getOrNull()
        }

    suspend fun putPullDiagnostic(diagnostic: PullDiagnosticReceipt) {
        putTransportJournal(
            journalKey = pullDiagnosticCacheKey(diagnostic.entityType, diagnostic.clientUuid),
            payloadJson = encodePullDiagnosticReceipt(diagnostic),
            contentEpoch = diagnostic.recordedAt,
        )
    }

    suspend fun deletePullDiagnostic(entityType: String, clientUuid: String) {
        deleteTransportJournal(pullDiagnosticCacheKey(entityType, clientUuid))
    }

    @Query(
        "DELETE FROM causal_transport_journal WHERE journalKey >= '" +
            PULL_DIAGNOSTIC_KEY_PREFIX + "' AND journalKey < '" +
            PULL_DIAGNOSTIC_KEY_RANGE_END + "'",
    )
    suspend fun deleteAllPullDiagnostics()

    @Query(
        "DELETE FROM causal_transport_journal WHERE journalKey >= '" +
            TERMINAL_RECEIPT_KEY_PREFIX + "' AND journalKey < '" +
            TERMINAL_RECEIPT_KEY_RANGE_END + "'",
    )
    suspend fun deleteAllTerminalReceipts()

    suspend fun getDismissedEntityJournal(
        entityType: String,
        clientUuid: String,
    ): CausalTransportJournalEntity? =
        getTransportJournal(dismissedEntityCacheKey(entityType, clientUuid))

    suspend fun putDismissedEntityJournal(
        entityType: String,
        clientUuid: String,
        contentEpoch: Long,
    ) {
        putTransportJournal(
            journalKey = dismissedEntityCacheKey(entityType, clientUuid),
            payloadJson = """{"entity_type":"$entityType","client_uuid":"$clientUuid"}""",
            contentEpoch = contentEpoch,
        )
    }

    @Query(
        "SELECT * FROM causal_transport_journal WHERE journalKey >= '" +
            DISMISSED_ENTITY_KEY_PREFIX + "' AND journalKey < '" +
            DISMISSED_ENTITY_KEY_RANGE_END + "' ORDER BY journalKey ASC",
    )
    suspend fun listDismissedEntityJournals(): List<CausalTransportJournalEntity>

    @Query(
        "SELECT * FROM causal_transport_journal WHERE journalKey >= '" +
            DISMISSED_ENTITY_KEY_PREFIX + "' AND journalKey < '" +
            DISMISSED_ENTITY_KEY_RANGE_END + "' ORDER BY journalKey ASC",
    )
    fun observeDismissedEntityJournals(): Flow<List<CausalTransportJournalEntity>>
}

const val CONFLICT_SNAPSHOT_STAGE_KEY_PREFIX = "conflict-page-stage:"

fun conflictSnapshotStageCacheKey(conflictId: String): String =
    "$CONFLICT_SNAPSHOT_STAGE_KEY_PREFIX$conflictId"

const val FROZEN_MUTATION_KEY_PREFIX = "frozen-mutation:"

fun frozenMutationCacheKey(entityType: String, clientUuid: String): String =
    "$FROZEN_MUTATION_KEY_PREFIX$entityType:$clientUuid"

const val FROZEN_MEDIA_SPOOL_KEY_PREFIX = "frozen-media-spool:"

/** Exclusive end of [FROZEN_MEDIA_SPOOL_KEY_PREFIX]; next code point after ':'. */
const val FROZEN_MEDIA_SPOOL_KEY_RANGE_END = "frozen-media-spool;"

fun frozenMediaSpoolCacheKey(mutationId: String): String =
    "$FROZEN_MEDIA_SPOOL_KEY_PREFIX$mutationId"

const val TERMINAL_RECEIPT_KEY_PREFIX = "terminal-receipt:"

/** Exclusive end of [TERMINAL_RECEIPT_KEY_PREFIX]. '!' sorts before ':' and would match nothing. */
const val TERMINAL_RECEIPT_KEY_RANGE_END = "terminal-receipt;"

fun terminalReceiptCacheKey(entityType: String, clientUuid: String): String =
    "$TERMINAL_RECEIPT_KEY_PREFIX$entityType:$clientUuid"

const val PULL_DIAGNOSTIC_KEY_PREFIX = "pull-diagnostic:"

/** Exclusive end of [PULL_DIAGNOSTIC_KEY_PREFIX]; next code point after ':'. */
const val PULL_DIAGNOSTIC_KEY_RANGE_END = "pull-diagnostic;"

fun pullDiagnosticCacheKey(entityType: String, clientUuid: String): String =
    "$PULL_DIAGNOSTIC_KEY_PREFIX$entityType:$clientUuid"

const val DISMISSED_SKIP_KEY_PREFIX = "dismissed-skip:"

fun dismissedSkipCacheKey(entityType: String, clientUuid: String): String =
    "$DISMISSED_SKIP_KEY_PREFIX$entityType:$clientUuid"

const val DISMISSED_ENTITY_KEY_PREFIX = "dismissed-entity:"

/** Exclusive end of [DISMISSED_ENTITY_KEY_PREFIX]; next code point after ':'. */
const val DISMISSED_ENTITY_KEY_RANGE_END = "dismissed-entity;"

/**
 * Durable ledger key for an entity the user removed via 只从这台手机去掉.
 * Written in the same transaction as the local dismissed tombstone; an apply
 * gate consults it so a later family edit cannot resurrect the row on this
 * device. Reuses the existing `causal_transport_journal` table (zero schema).
 */
fun dismissedEntityCacheKey(entityType: String, clientUuid: String): String =
    "$DISMISSED_ENTITY_KEY_PREFIX$entityType:$clientUuid"

/** (entityType, clientUuid) for keys written by [dismissedEntityCacheKey], else null. */
fun parseDismissedEntityCacheKey(journalKey: String): Pair<String, String>? {
    if (!journalKey.startsWith(DISMISSED_ENTITY_KEY_PREFIX)) return null
    val parts = journalKey.removePrefix(DISMISSED_ENTITY_KEY_PREFIX).split(":", limit = 2)
    if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
    return parts[0] to parts[1]
}

data class TerminalRejectionReceipt(
    val entityType: String,
    val clientUuid: String,
    val mutationId: String,
    val code: String,
    val contentEpoch: Long,
    val recordedAt: Long,
    val abandoned: Boolean = false,
)

fun encodeTerminalReceipt(receipt: TerminalRejectionReceipt): String = buildJsonObject {
    put("entity_type", receipt.entityType)
    put("client_uuid", receipt.clientUuid)
    put("mutation_id", receipt.mutationId)
    put("code", receipt.code)
    put("content_epoch", receipt.contentEpoch)
    put("recorded_at", receipt.recordedAt)
    put("abandoned", receipt.abandoned)
}.toString()

fun decodeTerminalReceipt(json: String): TerminalRejectionReceipt {
    val obj = Json.parseToJsonElement(json).jsonObject
    return TerminalRejectionReceipt(
        entityType = obj.getValue("entity_type").jsonPrimitive.content,
        clientUuid = obj.getValue("client_uuid").jsonPrimitive.content,
        mutationId = obj.getValue("mutation_id").jsonPrimitive.content,
        code = obj.getValue("code").jsonPrimitive.content,
        contentEpoch = obj.getValue("content_epoch").jsonPrimitive.long,
        recordedAt = obj.getValue("recorded_at").jsonPrimitive.long,
        abandoned = obj["abandoned"]?.jsonPrimitive?.booleanOrNull ?: false,
    )
}

data class PullDiagnosticReceipt(
    val entityType: String,
    val clientUuid: String,
    val code: String,
    val recordedAt: Long,
    /** DeferredGate.name; null on receipts written before named verdicts. */
    val reasonGate: String? = null,
    val missingEntityType: String? = null,
    val missingClientUuid: String? = null,
    val localSnapshot: String? = null,
)

fun encodePullDiagnosticReceipt(receipt: PullDiagnosticReceipt): String = buildJsonObject {
    put("entity_type", receipt.entityType)
    put("client_uuid", receipt.clientUuid)
    put("code", receipt.code)
    put("recorded_at", receipt.recordedAt)
    receipt.reasonGate?.let { put("reason_gate", it) }
    receipt.missingEntityType?.let { put("missing_entity_type", it) }
    receipt.missingClientUuid?.let { put("missing_client_uuid", it) }
    receipt.localSnapshot?.let { put("local_snapshot", it) }
}.toString()

fun decodePullDiagnosticReceipt(json: String): PullDiagnosticReceipt {
    val obj = Json.parseToJsonElement(json).jsonObject
    fun reasonField(key: String): String? =
        obj[key]?.jsonPrimitive?.contentOrNull
    return PullDiagnosticReceipt(
        entityType = obj.getValue("entity_type").jsonPrimitive.content,
        clientUuid = obj.getValue("client_uuid").jsonPrimitive.content,
        code = obj.getValue("code").jsonPrimitive.content,
        recordedAt = obj.getValue("recorded_at").jsonPrimitive.long,
        reasonGate = reasonField("reason_gate"),
        missingEntityType = reasonField("missing_entity_type"),
        missingClientUuid = reasonField("missing_client_uuid"),
        localSnapshot = reasonField("local_snapshot"),
    )
}
@Dao
interface SuspectedDuplicateGroupDao {
    @Query("SELECT * FROM suspected_duplicate_groups WHERE status = 'open' ORDER BY updatedAt DESC")
    fun observeOpen(): Flow<List<SuspectedDuplicateGroupEntity>>

    @Query("SELECT * FROM suspected_duplicate_groups WHERE groupId = :groupId LIMIT 1")
    suspend fun get(groupId: String): SuspectedDuplicateGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SuspectedDuplicateGroupEntity)

    @Query("DELETE FROM suspected_duplicate_groups WHERE groupId = :groupId")
    suspend fun delete(groupId: String)

    @Query("DELETE FROM suspected_duplicate_groups")
    suspend fun deleteAll()
}

/**
 * Server canonical mutation-id prefix for auto near-neighbor alignments
 * (ADR-0023). Local [SourceRelationReason.PULL_SUMMARY] rows never carry it:
 * their mutation id stays in the 0.4.7-consumable `pull-$relationId:$fp` /
 * `pull-$relationId` format, and auto-ness lives in the namespaced transport
 * journal below so a rolled-back 0.4.7 reader cannot fail closed.
 */
const val AUTO_NEAR_NEIGHBOR_MUTATION_PREFIX = "auto-near-neighbor:"

/** causal_transport_journal namespace owning per-relation auto-alignment metadata. */
const val SOURCE_RELATION_AUTO_JOURNAL_KEY_PREFIX = "source-relation-auto:"

fun sourceRelationAutoJournalKey(relationId: String): String =
    "$SOURCE_RELATION_AUTO_JOURNAL_KEY_PREFIX$relationId"

private const val SOURCE_RELATION_AUTO_MARKER_JSON = "{\"autoAligned\":true}"

private const val AUTO_ALIGNED_DISPLAYS_QUERY = """
    SELECT DISTINCT relation.displayClientUuid
    FROM source_relations relation
    JOIN source_relation_members member
      ON member.relationId = relation.relationId
      AND member.recordClientUuid = relation.displayClientUuid
      AND member.role = 'display'
    WHERE relation.displayClientUuid != ''
      AND (
        EXISTS (
          SELECT 1 FROM causal_transport_journal journal
          WHERE journal.journalKey = '$SOURCE_RELATION_AUTO_JOURNAL_KEY_PREFIX' || relation.relationId
        )
        OR (
          relation.reason != 'pull_summary'
          AND relation.mutationId LIKE '$AUTO_NEAR_NEIGHBOR_MUTATION_PREFIX%'
        )
      )
    ORDER BY relation.displayClientUuid
"""

@Dao
abstract class SourceRelationDao {
    @Query("SELECT * FROM source_relations WHERE relationId = :relationId LIMIT 1")
    abstract suspend fun get(relationId: String): SourceRelationEntity?

    @Query("SELECT * FROM source_relations ORDER BY createdAt ASC")
    abstract suspend fun listAll(): List<SourceRelationEntity>

    @Query("SELECT * FROM source_relations ORDER BY createdAt ASC")
    abstract fun observeAll(): Flow<List<SourceRelationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertRelationRow(entity: SourceRelationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertMemberRow(member: SourceRelationMemberEntity)

    @Query("SELECT * FROM source_relation_members WHERE relationId = :relationId")
    abstract suspend fun listMembers(relationId: String): List<SourceRelationMemberEntity>

    @Query("SELECT * FROM source_relation_members")
    abstract suspend fun listAllMembers(): List<SourceRelationMemberEntity>

    @Query("SELECT * FROM source_relation_members ORDER BY relationId, recordClientUuid")
    abstract fun observeAllMembers(): Flow<List<SourceRelationMemberEntity>>

    @Query(
        "INSERT OR REPLACE INTO causal_transport_journal(journalKey, payloadJson, contentEpoch) " +
            "VALUES(:journalKey, :payloadJson, :contentEpoch)",
    )
    protected abstract suspend fun putTransportJournalRow(
        journalKey: String,
        payloadJson: String,
        contentEpoch: Long,
    )

    @Query(
        "DELETE FROM causal_transport_journal " +
            "WHERE journalKey = '$SOURCE_RELATION_AUTO_JOURNAL_KEY_PREFIX' || :relationId",
    )
    protected abstract suspend fun deleteAutoAlignedJournal(relationId: String)

    @Query(
        """
        DELETE FROM causal_transport_journal
        WHERE journalKey IN (
            SELECT '$SOURCE_RELATION_AUTO_JOURNAL_KEY_PREFIX' || source_relation_members.relationId
            FROM source_relation_members
            WHERE source_relation_members.recordClientUuid IN (:recordClientUuids)
              AND source_relation_members.relationId != :relationId
        )
        """,
    )
    protected abstract suspend fun deleteAutoAlignedJournalsSupersededBy(
        relationId: String,
        recordClientUuids: List<String>,
    )

    /** Namespaces the auto-alignment marker away from other receipt journal semantics. */
    private suspend fun putAutoAlignedJournal(relationId: String) {
        putTransportJournalRow(
            journalKey = sourceRelationAutoJournalKey(relationId),
            payloadJson = SOURCE_RELATION_AUTO_MARKER_JSON,
            contentEpoch = 0L,
        )
    }

    /** One SQLite snapshot owns relation, live display membership, and auto metadata. */
    @Query(AUTO_ALIGNED_DISPLAYS_QUERY)
    abstract suspend fun listAutoAlignedDisplayClientUuids(): List<String>

    @Query(AUTO_ALIGNED_DISPLAYS_QUERY)
    abstract fun observeAutoAlignedDisplayClientUuids(): Flow<List<String>>

    @Query(
        """
        SELECT * FROM source_relation_members
        WHERE recordClientUuid = :recordClientUuid
        """,
    )
    abstract suspend fun listMembersForRecord(
        recordClientUuid: String,
    ): List<SourceRelationMemberEntity>

    @Query(
        """
        DELETE FROM source_relation_members
        WHERE recordClientUuid IN (:recordClientUuids)
          AND relationId != :relationId
        """,
    )
    protected abstract suspend fun deleteOtherMemberships(
        relationId: String,
        recordClientUuids: List<String>,
    )

    @Query(
        """
        DELETE FROM source_relation_members
        WHERE relationId = :relationId
          AND recordClientUuid NOT IN (:recordClientUuids)
        """,
    )
    protected abstract suspend fun deleteMembersOutsideCanonicalSet(
        relationId: String,
        recordClientUuids: List<String>,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertDeclaration(declaration: SourceRelationDeclarationEntity)

    @Query("SELECT * FROM source_relation_declarations WHERE mutationId = :mutationId LIMIT 1")
    abstract suspend fun getDeclaration(mutationId: String): SourceRelationDeclarationEntity?

    @Query(
        """
        SELECT * FROM source_relation_declarations
        WHERE status = 'pending'
        ORDER BY createdAt ASC
        """,
    )
    abstract suspend fun listPendingDeclarations(): List<SourceRelationDeclarationEntity>

    /**
     * Apply a source relation + members (+ optional declaration status) atomically.
     * Never mutates Record.deletedAt or ordinary media tombstones.
     */
    @Transaction
    open suspend fun applyCanonicalTransition(
        relation: SourceRelationEntity,
        members: List<SourceRelationMemberEntity>,
        declaration: SourceRelationDeclarationEntity? = null,
    ) {
        require(relation.mediaRetained) { "source relations must retain media" }
        require(
            relation.reason == SourceRelationReason.AUTHOR_DECLARE ||
                relation.reason == SourceRelationReason.OWNER_GROUP_RESOLVE ||
                relation.reason == SourceRelationReason.PULL_SUMMARY,
        ) {
            "unknown source relation reason: ${relation.reason}"
        }
        val canonicalMembers = members.distinctBy { it.recordClientUuid }
        require(canonicalMembers.size == members.size) { "duplicate source relation member" }
        require(canonicalMembers.isNotEmpty()) { "source relation requires a member" }
        require(canonicalMembers.size <= 64) { "source relation exceeds canonical member limit" }
        require(canonicalMembers.all { it.relationId == relation.relationId }) {
            "source relation member relationId mismatch"
        }
        require(canonicalMembers.all { it.recordClientUuid.isNotBlank() }) {
            "source relation member UUID must not be blank"
        }
        require(canonicalMembers.all {
            it.role == SourceRelationRole.DISPLAY || it.role == SourceRelationRole.SOURCE
        }) { "unknown source relation member role" }
        val displays = canonicalMembers.filter { it.role == SourceRelationRole.DISPLAY }
        if (relation.displayClientUuid.isBlank()) {
            require(relation.reason == SourceRelationReason.PULL_SUMMARY && displays.isEmpty()) {
                "only a pending pull delta may omit the display member"
            }
        } else {
            require(displays.singleOrNull()?.recordClientUuid == relation.displayClientUuid) {
                "canonical source relation requires exactly one matching display member"
            }
        }
        val recordClientUuids = canonicalMembers.map { it.recordClientUuid }
        deleteAutoAlignedJournalsSupersededBy(relation.relationId, recordClientUuids)
        if (relation.reason != SourceRelationReason.PULL_SUMMARY &&
            !relation.mutationId.startsWith(AUTO_NEAR_NEIGHBOR_MUTATION_PREFIX)
        ) {
            deleteAutoAlignedJournal(relation.relationId)
        }
        deleteOtherMemberships(relation.relationId, recordClientUuids)
        deleteMembersOutsideCanonicalSet(relation.relationId, recordClientUuids)
        upsertRelationRow(relation)
        canonicalMembers.forEach { upsertMemberRow(it) }
        if (declaration != null) {
            upsertDeclaration(declaration)
        }
    }

    /**
     * Apply one wire §12.3 sidecar as an independent durable delta.
     *
     * A source-role page may arrive before its display peer; in that case only
     * the observed source is committed. The later display-role sidecar carries
     * the same closed peer set and atomically completes the canonical component.
     *
     * The PULL_SUMMARY mutation id always stays in the 0.4.7-consumable
     * `pull-$relationId:$fingerprint` / `pull-$relationId` format so a
     * rolled-back 0.4.7 reader can keep pulling; `autoAligned` is preserved as
     * a namespaced causal_transport_journal marker in the same transaction,
     * never folded into the mutation id or the reason.
     */
    @Transaction
    open suspend fun applyPullSummary(
        relationId: String,
        recordClientUuid: String,
        role: String,
        peerIds: List<String>,
        observedAt: Long,
        autoAligned: Boolean = false,
    ) {
        require(relationId.isNotBlank()) { "source relation id must not be blank" }
        require(role == SourceRelationRole.DISPLAY || role == SourceRelationRole.SOURCE) {
            "unknown source relation role: $role"
        }
        require(recordClientUuid !in peerIds) { "source relation peers must exclude self" }
        require(peerIds.distinct().size == peerIds.size) { "duplicate source relation peer" }
        require(peerIds.size < 64) { "source relation exceeds canonical member limit" }
        val closedMemberIds = (peerIds + recordClientUuid).toSet()
        val memberSetFingerprint = sourceRelationMemberSetFingerprint(closedMemberIds)
        val pullMutationId = "pull-$relationId:$memberSetFingerprint"
        val legacyPullMutationId = "pull-$relationId"
        val existing = get(relationId)
        val existingMembers = listMembers(relationId)
        require(existingMembers.all { it.recordClientUuid in closedMemberIds }) {
            "source relation peer set drift"
        }
        require(existingMembers.all { it.relationId == relationId }) {
            "source relation member relationId mismatch"
        }
        require(existingMembers.none {
            it.recordClientUuid == recordClientUuid && it.role != role
        }) {
            "source relation member role drift"
        }
        if (existing != null) {
            if (existing.reason == SourceRelationReason.PULL_SUMMARY) {
                when {
                    existing.mutationId == pullMutationId -> Unit
                    existing.mutationId == legacyPullMutationId ||
                        existing.mutationId.startsWith(AUTO_NEAR_NEIGHBOR_MUTATION_PREFIX) -> {
                        // Legacy ids and pre-repair 0.4.8 auto markers carry no
                        // closed-set proof of their own until a display names the
                        // component; before that the arriving closed set is frozen.
                        if (existing.displayClientUuid.isNotBlank()) {
                            require(
                                existingMembers.mapTo(mutableSetOf()) { it.recordClientUuid } ==
                                    closedMemberIds,
                            ) {
                                "source relation peer set drift"
                            }
                        }
                    }
                    else -> throw IllegalArgumentException("source relation peer set drift")
                }
            } else if (existing.displayClientUuid.isNotBlank()) {
                require(existingMembers.mapTo(mutableSetOf()) { it.recordClientUuid } == closedMemberIds) {
                    "source relation peer set drift"
                }
            }
        }
        if (role == SourceRelationRole.DISPLAY) {
            require(existing?.displayClientUuid.isNullOrBlank() || existing.displayClientUuid == recordClientUuid) {
                "source relation display drift"
            }
        }
        val displayClientUuid = when {
            role == SourceRelationRole.DISPLAY -> recordClientUuid
            !existing?.displayClientUuid.isNullOrBlank() -> existing!!.displayClientUuid
            else -> ""
        }
        if (displayClientUuid.isNotBlank()) {
            require(displayClientUuid in closedMemberIds) { "source relation peer set omits display" }
        }
        val reason = existing?.reason
            ?.takeUnless { it == SourceRelationReason.PULL_SUMMARY }
            ?: SourceRelationReason.PULL_SUMMARY
        val relation = SourceRelationEntity(
            relationId = relationId,
            displayClientUuid = displayClientUuid,
            mediaRetained = true,
            reason = reason,
            mutationId = when {
                existing?.mutationId.isNullOrBlank() ||
                    existing.mutationId == legacyPullMutationId ||
                    (
                        existing.reason == SourceRelationReason.PULL_SUMMARY &&
                            existing.mutationId.startsWith(AUTO_NEAR_NEIGHBOR_MUTATION_PREFIX)
                        ) -> pullMutationId
                else -> existing.mutationId
            },
            createdByMembershipId = existing?.createdByMembershipId.orEmpty(),
            createdAt = existing?.createdAt ?: observedAt,
        )
        val memberRoles = linkedMapOf<String, String>()
        if (displayClientUuid.isBlank()) {
            existingMembers.forEach { member ->
                memberRoles[member.recordClientUuid] = member.role
            }
        }
        memberRoles[recordClientUuid] = role
        if (displayClientUuid.isNotBlank()) {
            peerIds.forEach { peerId ->
                memberRoles[peerId] = if (peerId == displayClientUuid) {
                    SourceRelationRole.DISPLAY
                } else {
                    SourceRelationRole.SOURCE
                }
            }
        }
        val members = memberRoles.map { (clientUuid, memberRole) ->
            SourceRelationMemberEntity(relationId, clientUuid, memberRole)
        }
        if (autoAligned || existing?.mutationId?.startsWith(AUTO_NEAR_NEIGHBOR_MUTATION_PREFIX) == true) {
            putAutoAlignedJournal(relationId)
        }
        applyCanonicalTransition(relation, members)
    }

    /** Owner resolution changes provenance, never record or media tombstones. */
    @Transaction
    open suspend fun applyOwnerGroupResolution(
        relation: SourceRelationEntity,
        members: List<SourceRelationMemberEntity>,
    ) {
        require(relation.reason == SourceRelationReason.OWNER_GROUP_RESOLVE) {
            "applyOwnerGroupResolution requires owner_group_resolve reason"
        }
        applyCanonicalTransition(relation, members)
    }

    @Transaction
    open suspend fun applyAuthorDeclaration(
        relation: SourceRelationEntity,
        members: List<SourceRelationMemberEntity>,
        declaration: SourceRelationDeclarationEntity,
    ) {
        require(relation.reason == SourceRelationReason.AUTHOR_DECLARE) {
            "applyAuthorDeclaration requires author_declare reason"
        }
        applyCanonicalTransition(relation, members, declaration)
    }

    /**
     * One-shot compatible repair for rows written by the first 0.4.8 build,
     * which overwrote the PULL_SUMMARY mutation id with the auto near-neighbor
     * marker and made a rolled-back 0.4.7 reader fail closed on the next pull.
     *
     * Auto-ness moves to the namespaced transport journal; the mutation id
     * returns to the 0.4.7 format. A complete relation restores the fingerprint
     * from its actual members; a display-less half-edge cannot know the full
     * peer set, so no hash is fabricated and the row falls back to the
     * supported legacy `pull-$relationId` until the next summary freezes the
     * closed set. Members, records, and media references are untouched.
     * Idempotent; not a schema migration. Callers: the startup recovery lock,
     * before any sync runs.
     */
    @Transaction
    open suspend fun repairLegacyAutoAlignedSummaries() {
        listAll()
            .filter {
                it.reason == SourceRelationReason.PULL_SUMMARY &&
                    it.mutationId.startsWith(AUTO_NEAR_NEIGHBOR_MUTATION_PREFIX)
            }
            .forEach { legacy ->
                putAutoAlignedJournal(legacy.relationId)
                val memberIds = listMembers(legacy.relationId)
                    .mapTo(mutableSetOf()) { it.recordClientUuid }
                val repairedMutationId = when {
                    legacy.displayClientUuid.isNotBlank() && memberIds.isNotEmpty() ->
                        "pull-${legacy.relationId}:${sourceRelationMemberSetFingerprint(memberIds)}"
                    else -> "pull-${legacy.relationId}"
                }
                upsertRelationRow(legacy.copy(mutationId = repairedMutationId))
            }
    }

    @Query("DELETE FROM source_relation_members")
    abstract suspend fun deleteAllMembers()

    @Query("DELETE FROM source_relation_declarations")
    abstract suspend fun deleteAllDeclarations()

    @Query("DELETE FROM source_relations")
    protected abstract suspend fun deleteAllRelationRows()

    @Query("DELETE FROM causal_transport_journal WHERE journalKey LIKE '$SOURCE_RELATION_AUTO_JOURNAL_KEY_PREFIX%'")
    protected abstract suspend fun deleteAllAutoAlignedJournals()

    @Transaction
    open suspend fun deleteAll() {
        deleteAllAutoAlignedJournals()
        deleteAllRelationRows()
    }
}

@Dao
interface MediaReferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ref: MediaReferenceEntity)

    @Query(
        """
        DELETE FROM media_references
        WHERE mediaUuid = :mediaUuid
          AND holderKind = :holderKind
          AND holderId = :holderId
        """,
    )
    suspend fun remove(mediaUuid: String, holderKind: String, holderId: String)

    @Query("SELECT * FROM media_references WHERE mediaUuid = :mediaUuid")
    suspend fun listForMedia(mediaUuid: String): List<MediaReferenceEntity>

    @Query(
        """
        SELECT COUNT(*) FROM media_references
        WHERE localUri = :localUri
        """,
    )
    suspend fun countHoldersForLocalUri(localUri: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM media_references
        WHERE mediaUuid = :mediaUuid
        """,
    )
    suspend fun countHoldersForMedia(mediaUuid: String): Int

    /**
     * Replace holders for one media UUID atomically (e.g. after branch receipt or
     * resolution). Ref-aware cleanup must only delete bytes when no holder remains.
     */
    @Transaction
    suspend fun replaceHolders(mediaUuid: String, holders: List<MediaReferenceEntity>) {
        deleteForMedia(mediaUuid)
        holders.forEach { upsert(it) }
    }

    @Query("DELETE FROM media_references WHERE mediaUuid = :mediaUuid")
    suspend fun deleteForMedia(mediaUuid: String)

    /**
     * Drop reference holders for care media that leave with RecordsOnly clears
     * (log + wake). Avatar holders remain with retained baby data.
     */
    @Query(
        """
        DELETE FROM media_references
        WHERE mediaUuid IN (
            SELECT clientUuid FROM media_assets WHERE kind IN ('log', 'wake')
        )
        """,
    )
    suspend fun deleteForLogAndWakeMedia()

    @Query("DELETE FROM media_references")
    suspend fun deleteAll()
}

/**
 * Whether file bytes at [localUri] may be deleted: no active media_asset row and no
 * media_reference holder (stable / mutation / branch / duplicate source).
 */
fun mediaBytesEligibleForCleanup(
    activeMediaAssetReferences: Int,
    mediaReferenceHolders: Int,
): Boolean = activeMediaAssetReferences == 0 && mediaReferenceHolders == 0
