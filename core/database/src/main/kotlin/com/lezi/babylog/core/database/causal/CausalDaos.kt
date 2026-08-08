package com.lezi.babylog.core.database.causal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WakeObservationDao {
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
        )
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
    @Query("SELECT * FROM conflict_summaries WHERE status = 'open' ORDER BY updatedAt DESC")
    fun observeOpen(): Flow<List<ConflictSummaryEntity>>

    @Query("SELECT * FROM conflict_summaries WHERE status = 'open' ORDER BY updatedAt DESC")
    suspend fun listOpen(): List<ConflictSummaryEntity>

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
interface ConflictDetailCacheDao {
    @Query("SELECT * FROM conflict_detail_cache WHERE conflictId = :conflictId LIMIT 1")
    suspend fun get(conflictId: String): ConflictDetailCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConflictDetailCacheEntity)

    @Query("DELETE FROM conflict_detail_cache WHERE conflictId = :conflictId")
    suspend fun delete(conflictId: String)

    @Query("DELETE FROM conflict_detail_cache")
    suspend fun deleteAll()
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

@Dao
interface SourceRelationDao {
    @Query("SELECT * FROM source_relations WHERE relationId = :relationId LIMIT 1")
    suspend fun get(relationId: String): SourceRelationEntity?

    @Query("SELECT * FROM source_relations ORDER BY createdAt ASC")
    suspend fun listAll(): List<SourceRelationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SourceRelationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: SourceRelationMemberEntity)

    @Query("SELECT * FROM source_relation_members WHERE relationId = :relationId")
    suspend fun listMembers(relationId: String): List<SourceRelationMemberEntity>

    @Query("SELECT * FROM source_relation_members")
    suspend fun listAllMembers(): List<SourceRelationMemberEntity>

    @Query(
        """
        SELECT * FROM source_relation_members
        WHERE recordClientUuid = :recordClientUuid
        """,
    )
    suspend fun listMembersForRecord(recordClientUuid: String): List<SourceRelationMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeclaration(declaration: SourceRelationDeclarationEntity)

    @Query("SELECT * FROM source_relation_declarations WHERE mutationId = :mutationId LIMIT 1")
    suspend fun getDeclaration(mutationId: String): SourceRelationDeclarationEntity?

    @Query(
        """
        SELECT * FROM source_relation_declarations
        WHERE status = 'pending'
        ORDER BY createdAt ASC
        """,
    )
    suspend fun listPendingDeclarations(): List<SourceRelationDeclarationEntity>

    /**
     * Apply a source relation + members (+ optional declaration status) atomically.
     * Never mutates Record.deletedAt or ordinary media tombstones.
     */
    @Transaction
    suspend fun applyRelation(
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
        upsert(relation)
        members.forEach { upsertMember(it) }
        if (declaration != null) {
            upsertDeclaration(declaration)
        }
    }

    /**
     * Apply an Owner group resolution: write relation + members in one transaction.
     * Never mutates Record.deletedAt or ordinary media tombstones.
     */
    @Transaction
    suspend fun applyOwnerGroupResolution(
        relation: SourceRelationEntity,
        members: List<SourceRelationMemberEntity>,
    ) {
        require(relation.reason == SourceRelationReason.OWNER_GROUP_RESOLVE) {
            "applyOwnerGroupResolution requires owner_group_resolve reason"
        }
        applyRelation(relation, members)
    }

    @Transaction
    suspend fun applyAuthorDeclaration(
        relation: SourceRelationEntity,
        members: List<SourceRelationMemberEntity>,
        declaration: SourceRelationDeclarationEntity,
    ) {
        require(relation.reason == SourceRelationReason.AUTHOR_DECLARE) {
            "applyAuthorDeclaration requires author_declare reason"
        }
        applyRelation(relation, members, declaration)
    }

    @Query("DELETE FROM source_relation_members")
    suspend fun deleteAllMembers()

    @Query("DELETE FROM source_relation_declarations")
    suspend fun deleteAllDeclarations()

    @Query("DELETE FROM source_relations")
    suspend fun deleteAll()
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
