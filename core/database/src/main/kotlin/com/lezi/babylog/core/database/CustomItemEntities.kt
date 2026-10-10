package com.lezi.babylog.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.lezi.babylog.core.database.causal.CommitFirstSettlementEpoch
import com.lezi.babylog.core.database.causal.toAppliedColumns
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "custom_items",
    indices = [
        Index("syncDirty"),
        Index(value = ["clientUuid"], unique = true),
    ],
)
data class CustomItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientUuid: String,
    val familyId: Long,
    val name: String,
    val iconSlot: Int,
    val sortOrder: Int = 0,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /**
     * Server-minted membership identity of the creator.
     * Empty when created before this device joins a family.
     */
    @androidx.room.ColumnInfo(defaultValue = "''")
    val createdByMembershipId: String = "",
    /**
     * True while this device still needs to publish the shared definition
     * (name/icon/tombstone). Layout fields (sortOrder / hide / slots) stay local.
     */
    @androidx.room.ColumnInfo(defaultValue = "1")
    val syncDirty: Boolean = true,
    @androidx.room.ColumnInfo(defaultValue = "NULL")
    val baseVersion: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "NULL")
    val mutationId: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "NULL")
    val openConflictId: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "NULL")
    val localBranchVersionId: String? = null,
)

@Dao
interface CustomItemDao {
    @Query("SELECT * FROM custom_items WHERE deletedAt IS NULL ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<CustomItemEntity>>

    @Query("SELECT * FROM custom_items WHERE deletedAt IS NULL ORDER BY sortOrder ASC, id ASC")
    suspend fun listAll(): List<CustomItemEntity>

    /** Includes tombstoned rows (family sync / leave-takeover tests). */
    @Query("SELECT * FROM custom_items ORDER BY sortOrder ASC, id ASC")
    suspend fun listAllIncludingDeleted(): List<CustomItemEntity>

    @Query("SELECT * FROM custom_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CustomItemEntity?

    // Same predicate as getById; batch form for capture loops.
    @Query("SELECT * FROM custom_items WHERE id IN (:ids)")
    suspend fun getByIdsIn(ids: List<Long>): List<CustomItemEntity>

    suspend fun getByIds(ids: List<Long>): List<CustomItemEntity> =
        queryInChunks(ids, ::getByIdsIn)

    @Query("SELECT * FROM custom_items WHERE clientUuid = :clientUuid LIMIT 1")
    suspend fun getByClientUuid(clientUuid: String): CustomItemEntity?

    @Query("SELECT * FROM custom_items WHERE syncDirty = 1 ORDER BY id ASC")
    suspend fun listPendingSync(): List<CustomItemEntity>

    @Query(
        """
        UPDATE custom_items SET syncDirty = 0
        WHERE clientUuid = :clientUuid AND updatedAt = :updatedAt
        """,
    )
    suspend fun markSynced(clientUuid: String, updatedAt: Long)

    @Query(
        """
        DELETE FROM custom_items
        WHERE clientUuid = :clientUuid AND updatedAt = :updatedAt AND deletedAt IS NOT NULL
        """,
    )
    suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int

    @Query("UPDATE custom_items SET syncDirty = 1")
    suspend fun markAllPendingSync()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: CustomItemEntity): Long

    @Update
    suspend fun update(item: CustomItemEntity)

    @Query(
        """
        UPDATE custom_items
        SET deletedAt = :deletedAt, updatedAt = :deletedAt, syncDirty = 1
        WHERE id = :id
        """,
    )
    suspend fun softDelete(id: Long, deletedAt: Long)

    @Query("DELETE FROM custom_items")
    suspend fun deleteAll()

    @Transaction
    suspend fun freezeDirtyEpoch(
        clientUuid: String,
        contentEpoch: Long,
        newMutationId: String,
    ): CustomItemEntity? {
        val current = getByClientUuid(clientUuid) ?: return null
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

    /** Freeze the CustomItem's sole empty-media envelope with its product fact identity. */
    @Transaction
    suspend fun freezeCommitFirstEpoch(
        clientUuid: String,
        contentEpoch: Long,
        newMutationId: String,
    ): CustomItemEntity? {
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

    @Transaction
    suspend fun settleCommitFirstAcceptedOrMerged(
        clientUuid: String,
        expectedMutationId: String,
        expectedContentEpoch: Long,
        newBaseVersion: String,
        matchesBoundContent: Boolean = true,
    ): CommitFirstSettlementEpoch? {
        val current = getByClientUuid(clientUuid) ?: return null
        val settled = com.lezi.babylog.core.database.causal.settleCommitFirstAcceptedOrMerged(
            current = current.toCausalMutationState(),
            expectedMutationId = expectedMutationId,
            expectedContentEpoch = expectedContentEpoch,
            newBaseVersion = newBaseVersion,
            matchesBoundContent = matchesBoundContent,
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

    @Transaction
    suspend fun settleCommitFirstBranched(
        clientUuid: String,
        expectedMutationId: String,
        expectedContentEpoch: Long,
        conflictId: String,
        branchVersionId: String,
        stableBaseVersion: String,
        matchesBoundContent: Boolean = true,
    ): CommitFirstSettlementEpoch? {
        val current = getByClientUuid(clientUuid) ?: return null
        val settled = com.lezi.babylog.core.database.causal.settleCommitFirstBranched(
            current = current.toCausalMutationState(),
            expectedMutationId = expectedMutationId,
            expectedContentEpoch = expectedContentEpoch,
            conflictId = conflictId,
            branchVersionId = branchVersionId,
            stableBaseVersion = stableBaseVersion,
            matchesBoundContent = matchesBoundContent,
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
            ),
        )
        return true
    }

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

private fun CustomItemEntity.toCausalMutationState() =
    com.lezi.babylog.core.database.causal.CausalRootMutationState(
        baseVersion = baseVersion,
        mutationId = mutationId,
        contentEpoch = updatedAt,
        syncDirty = syncDirty,
        openConflictId = openConflictId,
        localBranchVersionId = localBranchVersionId,
    )
