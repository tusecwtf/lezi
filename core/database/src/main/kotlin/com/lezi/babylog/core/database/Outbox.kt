package com.lezi.babylog.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val familyId: String,
    val entityType: String,
    val clientUuid: String,
    val payloadJson: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(row: OutboxEntity): Long

    @Query("SELECT * FROM outbox ORDER BY id ASC LIMIT :limit")
    suspend fun peek(limit: Int = 100): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("DELETE FROM outbox")
    suspend fun deleteAll()
}
