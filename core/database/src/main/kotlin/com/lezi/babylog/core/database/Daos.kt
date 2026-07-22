package com.lezi.babylog.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalUserDao {
    @Query("SELECT * FROM local_users LIMIT 1")
    suspend fun get(): LocalUserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: LocalUserEntity): Long
}

@Dao
interface FamilyDao {
    @Query("SELECT * FROM families WHERE id = :id")
    suspend fun get(id: Long): FamilyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(family: FamilyEntity): Long
}

@Dao
interface MembershipDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(membership: MembershipEntity)
}

@Dao
interface BabyDao {
    @Query("SELECT * FROM babies WHERE deletedAt IS NULL ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<BabyEntity>>

    @Query("SELECT * FROM babies WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: Long): BabyEntity?

    @Query("SELECT COUNT(*) FROM babies WHERE deletedAt IS NULL")
    suspend fun countActive(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(baby: BabyEntity): Long

    @Update
    suspend fun update(baby: BabyEntity)
}

@Dao
interface RecordDao {
    @Query(
        """
        SELECT * FROM records
        WHERE babyId = :babyId
          AND deletedAt IS NULL
          AND timestamp >= :startInclusive
          AND timestamp < :endExclusive
        ORDER BY timestamp DESC
        """,
    )
    fun observeDay(babyId: Long, startInclusive: Long, endExclusive: Long): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: Long): RecordEntity?

    @Query("SELECT * FROM records WHERE clientUuid = :uuid LIMIT 1")
    suspend fun getByClientUuid(uuid: String): RecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: RecordEntity): Long

    @Update
    suspend fun update(record: RecordEntity)

    @Query("UPDATE records SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long)
}
