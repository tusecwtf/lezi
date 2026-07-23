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

    @Query("DELETE FROM local_users")
    suspend fun deleteAll()
}

@Dao
interface FamilyDao {
    @Query("SELECT * FROM families WHERE id = :id")
    suspend fun get(id: Long): FamilyEntity?

    @Query("SELECT * FROM families ORDER BY id ASC")
    suspend fun listAll(): List<FamilyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(family: FamilyEntity): Long

    @Query("DELETE FROM families")
    suspend fun deleteAll()
}

@Dao
interface MembershipDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(membership: MembershipEntity)

    @Query("SELECT * FROM memberships WHERE familyId = :familyId")
    suspend fun listForFamily(familyId: Long): List<MembershipEntity>

    @Query("DELETE FROM memberships")
    suspend fun deleteAll()
}

@Dao
interface BabyDao {
    @Query("SELECT * FROM babies WHERE deletedAt IS NULL ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<BabyEntity>>

    @Query("SELECT * FROM babies WHERE deletedAt IS NULL ORDER BY sortOrder ASC, id ASC")
    suspend fun listAll(): List<BabyEntity>

    @Query("SELECT * FROM babies WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: Long): BabyEntity?

    @Query("SELECT COUNT(*) FROM babies WHERE deletedAt IS NULL")
    suspend fun countActive(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(baby: BabyEntity): Long

    @Update
    suspend fun update(baby: BabyEntity)

    @Query("DELETE FROM babies")
    suspend fun deleteAll()
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
    suspend fun listDay(babyId: Long, startInclusive: Long, endExclusive: Long): List<RecordEntity>

    @Query("SELECT * FROM records WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: Long): RecordEntity?

    @Query("SELECT * FROM records WHERE clientUuid = :uuid LIMIT 1")
    suspend fun getByClientUuid(uuid: String): RecordEntity?

    @Query(
        """
        SELECT * FROM records
        WHERE babyId = :babyId
          AND type = 'sleep'
          AND deletedAt IS NULL
          AND endTimestamp IS NULL
        ORDER BY timestamp DESC
        LIMIT 1
        """,
    )
    suspend fun findOpenSleep(babyId: Long): RecordEntity?

    @Query(
        """
        SELECT * FROM records
        WHERE babyId = :babyId
          AND deletedAt IS NULL
        ORDER BY timestamp DESC
        """,
    )
    suspend fun listForBaby(babyId: Long): List<RecordEntity>

    @Query(
        """
        SELECT * FROM records
        WHERE babyId = :babyId
          AND deletedAt IS NULL
          AND timestamp >= :startInclusive
          AND timestamp < :endExclusive
        ORDER BY timestamp ASC
        """,
    )
    suspend fun listRange(babyId: Long, startInclusive: Long, endExclusive: Long): List<RecordEntity>

    @Query(
        """
        SELECT * FROM records
        WHERE babyId = :babyId
          AND deletedAt IS NULL
          AND type = :type
        ORDER BY timestamp ASC
        """,
    )
    suspend fun listByType(babyId: Long, type: String): List<RecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: RecordEntity): Long

    @Update
    suspend fun update(record: RecordEntity)

    @Query("UPDATE records SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long)

    @Query("DELETE FROM records")
    suspend fun deleteAll()
}

@Dao
interface MediaAssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(asset: MediaAssetEntity): Long

    @Query("SELECT * FROM media_assets WHERE recordId = :recordId")
    suspend fun listForRecord(recordId: Long): List<MediaAssetEntity>

    @Query("DELETE FROM media_assets WHERE recordId = :recordId")
    suspend fun deleteForRecord(recordId: Long)

    @Query("DELETE FROM media_assets")
    suspend fun deleteAll()
}
