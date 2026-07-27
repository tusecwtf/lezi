package com.lezi.babylog.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "custom_items",
    indices = [Index("syncDirty")],
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
}
