package com.lezi.babylog.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "outbox",
    indices = [
        Index(
            value = ["familyId", "entityType", "clientUuid"],
            unique = true,
        ),
    ],
)
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

    @Query("SELECT COUNT(*) FROM outbox WHERE familyId = :familyId")
    fun observeCount(familyId: String): Flow<Int>

    @Query(
        """
        SELECT * FROM outbox
        WHERE familyId = :familyId
        ORDER BY id ASC
        LIMIT :limit
        """,
    )
    suspend fun peek(familyId: String, limit: Int = 100): List<OutboxEntity>

    @Query(
        """
        SELECT * FROM outbox
        WHERE familyId = :familyId
          AND entityType = :entityType
          AND clientUuid = :clientUuid
        LIMIT 1
        """,
    )
    suspend fun find(
        familyId: String,
        entityType: String,
        clientUuid: String,
    ): OutboxEntity?

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("DELETE FROM outbox WHERE familyId = :familyId")
    suspend fun deleteFamily(familyId: String)

    @Query(
        """
        DELETE FROM outbox
        WHERE familyId = :familyId AND entityType = :entityType
        """,
    )
    suspend fun deleteType(familyId: String, entityType: String)

    @Query("DELETE FROM outbox WHERE entityType = :entityType")
    suspend fun deleteTypeAcrossFamilies(entityType: String)

    @Query(
        """
        DELETE FROM outbox
        WHERE familyId = :familyId
          AND entityType = :entityType
          AND clientUuid IN (:clientUuids)
        """,
    )
    suspend fun deleteEntities(
        familyId: String,
        entityType: String,
        clientUuids: List<String>,
    )

    @Query(
        """
        DELETE FROM outbox
        WHERE entityType = :entityType
          AND clientUuid IN (:clientUuids)
        """,
    )
    suspend fun deleteEntitiesAcrossFamilies(
        entityType: String,
        clientUuids: List<String>,
    )

    @Query("DELETE FROM outbox")
    suspend fun deleteAll()
}
