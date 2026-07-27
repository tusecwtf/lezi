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
     * Empty when created on a never-joined / legacy local-only device.
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

@Entity(tableName = "calendar_events")
data class CalendarEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientUuid: String,
    val babyId: Long,
    val title: String,
    val note: String? = null,
    val eventAt: Long,
    val remindAt: Long? = null,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Dao
interface CalendarEventDao {
    @Query(
        """
        SELECT * FROM calendar_events
        WHERE babyId = :babyId AND deletedAt IS NULL
          AND eventAt >= :start AND eventAt < :end
        ORDER BY eventAt ASC
        """,
    )
    fun observeRange(babyId: Long, start: Long, end: Long): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE babyId = :babyId AND deletedAt IS NULL ORDER BY eventAt ASC")
    suspend fun listForBaby(babyId: Long): List<CalendarEventEntity>

    @Query("SELECT * FROM calendar_events WHERE babyId = :babyId ORDER BY eventAt ASC, id ASC")
    suspend fun listForBabyIncludingDeleted(babyId: Long): List<CalendarEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: CalendarEventEntity): Long

    @Update
    suspend fun update(event: CalendarEventEntity)

    @Query("UPDATE calendar_events SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long)

    @Query("DELETE FROM calendar_events")
    suspend fun deleteAll()
}

/**
 * Durable hand-off from the Room clear transaction to Android alarm cleanup.
 *
 * A single records-clear row survives process death until every captured
 * PendingIntent has been cancelled. This storage entity is private to
 * [RoomPendingReminderCleanupStore]; callers use typed snapshots.
 */
@Entity(tableName = "pending_reminder_cleanup")
data class PendingReminderCleanupEntity(
    @PrimaryKey val operation: String,
    val calendarEventIds: String,
    @androidx.room.ColumnInfo(defaultValue = "''")
    val carePlanIds: String = "",
    val familyServerRetained: Boolean,
)

@Dao
interface PendingReminderCleanupDao {
    @Query("SELECT * FROM pending_reminder_cleanup WHERE operation = :operation LIMIT 1")
    suspend fun get(operation: String): PendingReminderCleanupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pending: PendingReminderCleanupEntity)

    @Query("DELETE FROM pending_reminder_cleanup WHERE operation = :operation")
    suspend fun delete(operation: String)
}
