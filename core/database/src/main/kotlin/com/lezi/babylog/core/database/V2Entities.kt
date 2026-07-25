package com.lezi.babylog.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "custom_items")
data class CustomItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientUuid: String,
    val familyId: Long,
    val name: String,
    val iconSlot: Int,
    val sortOrder: Int = 0,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Dao
interface CustomItemDao {
    @Query("SELECT * FROM custom_items WHERE deletedAt IS NULL ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<CustomItemEntity>>

    @Query("SELECT * FROM custom_items WHERE deletedAt IS NULL ORDER BY sortOrder ASC, id ASC")
    suspend fun listAll(): List<CustomItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: CustomItemEntity): Long

    @Update
    suspend fun update(item: CustomItemEntity)

    @Query("UPDATE custom_items SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
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
