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

    @Query("SELECT * FROM babies WHERE id = :id")
    suspend fun getIncludingDeleted(id: Long): BabyEntity?

    @Query("SELECT * FROM babies WHERE clientUuid = :uuid LIMIT 1")
    suspend fun getByClientUuid(uuid: String): BabyEntity?

    @Query("SELECT * FROM babies ORDER BY sortOrder ASC, id ASC")
    suspend fun listAllIncludingDeleted(): List<BabyEntity>

    @Query("SELECT * FROM babies WHERE syncDirty = 1 ORDER BY id ASC")
    suspend fun listPendingSync(): List<BabyEntity>

    @Query(
        """
        UPDATE babies SET syncDirty = 0
        WHERE clientUuid = :clientUuid AND updatedAt = :updatedAt
        """,
    )
    suspend fun markSynced(clientUuid: String, updatedAt: Long)

    @Query("UPDATE babies SET syncDirty = 1")
    suspend fun markAllPendingSync()

    @Query(
        """
        SELECT COUNT(*) FROM babies
        WHERE deletedAt IS NULL
          AND TRIM(nickname) = TRIM(:nickname)
          AND (:excludeId < 0 OR id != :excludeId)
        """,
    )
    suspend fun countByNickname(nickname: String, excludeId: Long = -1L): Int

    @Query("SELECT COUNT(*) FROM babies WHERE deletedAt IS NULL")
    suspend fun countActive(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(baby: BabyEntity): Long

    @Update
    suspend fun update(baby: BabyEntity)

    @Query(
        """
        UPDATE babies
        SET avatarMediaUuid = :avatarMediaUuid, avatarPath = :avatarPath
        WHERE clientUuid = :clientUuid
        """,
    )
    suspend fun updateAvatarReplica(
        clientUuid: String,
        avatarMediaUuid: String?,
        avatarPath: String?,
    )

    @Query(
        """
        UPDATE babies
        SET avatarMediaUuid = :avatarMediaUuid, syncDirty = 1
        WHERE id = :id
          AND updatedAt = :expectedUpdatedAt
          AND (
              avatarPath = :expectedAvatarPath
              OR (avatarPath IS NULL AND :expectedAvatarPath IS NULL)
          )
        """,
    )
    suspend fun updateAvatarMediaForLocalSnapshot(
        id: Long,
        expectedUpdatedAt: Long,
        expectedAvatarPath: String?,
        avatarMediaUuid: String?,
    ): Int

    @Query(
        """
        UPDATE babies
        SET avatarPath = :avatarPath
        WHERE id = :id
          AND (
              avatarMediaUuid = :expectedAvatarMediaUuid
              OR (avatarMediaUuid IS NULL AND :expectedAvatarMediaUuid IS NULL)
          )
        """,
    )
    suspend fun updateAvatarPathForReplica(
        id: Long,
        expectedAvatarMediaUuid: String?,
        avatarPath: String?,
    ): Int

    @Query("DELETE FROM babies")
    suspend fun deleteAll()
}

@Dao
interface RecordDao {
    /**
     * Ordinary timeline window. Excludes records that lost multi-candidate
     * fulfillment ([fulfillment_candidates.adoptionStatus] = conflict_not_adopted)
     * without soft-deleting them — audit rows remain via getByClientUuid.
     */
    @Query(
        """
        SELECT * FROM records
        WHERE babyId = :babyId
          AND deletedAt IS NULL
          AND clientUuid NOT IN (
              SELECT recordClientUuid FROM fulfillment_candidates
              WHERE adoptionStatus = 'conflict_not_adopted'
                AND deletedAt IS NULL
          )
          AND timestamp < :endExclusive
          AND (
              timestamp >= :startInclusive
              OR (
                  type = 'sleep'
                  AND (endTimestamp IS NULL OR endTimestamp > :startInclusive)
              )
          )
        ORDER BY timestamp DESC
        """,
    )
    fun observeRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<RecordEntity>>

    @Query(
        """
        SELECT * FROM records
        WHERE babyId = :babyId
          AND deletedAt IS NULL
          AND clientUuid NOT IN (
              SELECT recordClientUuid FROM fulfillment_candidates
              WHERE adoptionStatus = 'conflict_not_adopted'
                AND deletedAt IS NULL
          )
          AND timestamp < :endExclusive
          AND (
              timestamp >= :startInclusive
              OR (
                  type = 'sleep'
                  AND (endTimestamp IS NULL OR endTimestamp > :startInclusive)
              )
          )
        ORDER BY timestamp DESC
        """,
    )
    fun observeDay(babyId: Long, startInclusive: Long, endExclusive: Long): Flow<List<RecordEntity>>

    @Query(
        """
        SELECT * FROM records
        WHERE babyId = :babyId
          AND deletedAt IS NULL
          AND clientUuid NOT IN (
              SELECT recordClientUuid FROM fulfillment_candidates
              WHERE adoptionStatus = 'conflict_not_adopted'
                AND deletedAt IS NULL
          )
          AND timestamp < :endExclusive
          AND (
              timestamp >= :startInclusive
              OR (
                  type = 'sleep'
                  AND (endTimestamp IS NULL OR endTimestamp > :startInclusive)
              )
          )
        ORDER BY timestamp DESC
        """,
    )
    suspend fun listDay(babyId: Long, startInclusive: Long, endExclusive: Long): List<RecordEntity>

    @Query("SELECT * FROM records WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: Long): RecordEntity?

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun getIncludingDeleted(id: Long): RecordEntity?

    @Query("SELECT * FROM records WHERE clientUuid = :uuid LIMIT 1")
    suspend fun getByClientUuid(uuid: String): RecordEntity?

    @Query("SELECT * FROM records ORDER BY id ASC")
    suspend fun listAllIncludingDeleted(): List<RecordEntity>

    @Query("SELECT * FROM records WHERE syncDirty = 1 ORDER BY id ASC")
    suspend fun listPendingSync(): List<RecordEntity>

    @Query(
        """
        UPDATE records SET syncDirty = 0
        WHERE clientUuid = :clientUuid AND updatedAt = :updatedAt
        """,
    )
    suspend fun markSynced(clientUuid: String, updatedAt: Long)

    /** Merge server-owned metadata without changing content, revision, dirty state, or outbox. */
    @Query(
        """
        UPDATE records SET createdByMembershipId = :membershipId
        WHERE clientUuid = :clientUuid
          AND updatedAt = :expectedUpdatedAt
          AND TRIM(:membershipId) != ''
        """,
    )
    suspend fun mergeCanonicalAuthor(
        clientUuid: String,
        expectedUpdatedAt: Long,
        membershipId: String,
    ): Int

    @Query("UPDATE records SET syncDirty = 1")
    suspend fun markAllPendingSync()

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
          AND type = 'sleep'
          AND deletedAt IS NULL
          AND endTimestamp IS NULL
        ORDER BY timestamp DESC, id DESC
        """,
    )
    suspend fun listOpenSleeps(babyId: Long): List<RecordEntity>

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
    fun observeOpenSleep(babyId: Long): Flow<RecordEntity?>

    @Query(
        """
        SELECT * FROM records
        WHERE babyId = :babyId
          AND deletedAt IS NULL
          AND clientUuid NOT IN (
              SELECT recordClientUuid FROM fulfillment_candidates
              WHERE adoptionStatus = 'conflict_not_adopted'
                AND deletedAt IS NULL
          )
        ORDER BY timestamp DESC
        """,
    )
    suspend fun listForBaby(babyId: Long): List<RecordEntity>

    /**
     * Use SQLite to narrow search to rows that can plausibly match. Callers
     * still inspect type-specific visible fields so JSON keys and hidden
     * payload values cannot become user-visible false positives.
     */
    @Query(
        """
        SELECT * FROM records
        WHERE babyId = :babyId
          AND deletedAt IS NULL
          AND clientUuid NOT IN (
              SELECT recordClientUuid FROM fulfillment_candidates
              WHERE adoptionStatus = 'conflict_not_adopted'
                AND deletedAt IS NULL
          )
          AND (
              LOWER(COALESCE(note, '')) LIKE :escapedPattern ESCAPE '\'
              OR LOWER(payloadJson) LIKE :escapedPattern ESCAPE '\'
              OR type IN (:matchingTypeKeys)
          )
        ORDER BY timestamp DESC
        """,
    )
    suspend fun searchCandidates(
        babyId: Long,
        escapedPattern: String,
        matchingTypeKeys: List<String>,
    ): List<RecordEntity>

    @Query(
        """
        SELECT * FROM records
        WHERE babyId = :babyId
          AND deletedAt IS NULL
          AND clientUuid NOT IN (
              SELECT recordClientUuid FROM fulfillment_candidates
              WHERE adoptionStatus = 'conflict_not_adopted'
                AND deletedAt IS NULL
          )
          AND timestamp < :endExclusive
          AND (
              timestamp >= :startInclusive
              OR (
                  type = 'sleep'
                  AND (endTimestamp IS NULL OR endTimestamp > :startInclusive)
              )
          )
        ORDER BY timestamp ASC
        """,
    )
    suspend fun listRange(babyId: Long, startInclusive: Long, endExclusive: Long): List<RecordEntity>

    @Query(
        """
        SELECT * FROM records
        WHERE babyId = :babyId
          AND deletedAt IS NULL
          AND clientUuid NOT IN (
              SELECT recordClientUuid FROM fulfillment_candidates
              WHERE adoptionStatus = 'conflict_not_adopted'
                AND deletedAt IS NULL
          )
          AND type = :type
        ORDER BY timestamp ASC
        """,
    )
    suspend fun listByType(babyId: Long, type: String): List<RecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: RecordEntity): Long

    @Update
    suspend fun update(record: RecordEntity)

    @Query(
        """
        UPDATE records
        SET payloadJson = :payloadJson
        WHERE id = :id AND payloadJson = :expectedPayloadJson
        """,
    )
    suspend fun updatePayloadReplica(
        id: Long,
        expectedPayloadJson: String,
        payloadJson: String,
    ): Int

    @Query(
        """
        UPDATE records
        SET deletedAt = :deletedAt, updatedAt = :deletedAt, syncDirty = 1
        WHERE id = :id
        """,
    )
    suspend fun softDelete(id: Long, deletedAt: Long)

    @Query("DELETE FROM records")
    suspend fun deleteAll()
}

@Dao
interface CarePlanDao {
    @Query(
        """
        SELECT * FROM care_plans
        WHERE babyId = :babyId
          AND deletedAt IS NULL
          AND status IN ('pending', 'missed')
          AND scheduledAt >= :startInclusive
          AND scheduledAt < :endExclusive
        ORDER BY scheduledAt ASC
        """,
    )
    fun observeDayPending(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<CarePlanEntity>>

    /**
     * Today view: all still-open plans that are overdue (missed) for this baby,
     * plus pending plans scheduled in the local day window (caller supplies bounds).
     */
    @Query(
        """
        SELECT * FROM care_plans
        WHERE babyId = :babyId
          AND deletedAt IS NULL
          AND status IN ('pending', 'missed')
          AND (
              scheduledAt < :nowMillis
              OR (scheduledAt >= :dayStart AND scheduledAt < :dayEnd)
          )
        ORDER BY scheduledAt ASC
        """,
    )
    fun observeTodayPending(
        babyId: Long,
        dayStart: Long,
        dayEnd: Long,
        nowMillis: Long,
    ): Flow<List<CarePlanEntity>>

    /**
     * Calendar surface: every non-deleted plan in the absolute time window,
     * regardless of status (pending/missed/completed/skipped).
     */
    @Query(
        """
        SELECT * FROM care_plans
        WHERE babyId = :babyId
          AND deletedAt IS NULL
          AND scheduledAt >= :startInclusive
          AND scheduledAt < :endExclusive
        ORDER BY scheduledAt ASC
        """,
    )
    fun observeRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<CarePlanEntity>>

    @Query(
        """
        SELECT * FROM care_plans
        WHERE babyId = :babyId
          AND deletedAt IS NULL
          AND status IN ('pending', 'missed')
          AND scheduledAt > :nowMillis
        ORDER BY scheduledAt ASC
        """,
    )
    suspend fun listOpenFuture(babyId: Long, nowMillis: Long): List<CarePlanEntity>

    @Query(
        """
        SELECT * FROM care_plans
        WHERE deletedAt IS NULL
          AND status IN ('pending', 'missed')
          AND scheduledAt > :nowMillis
        ORDER BY scheduledAt ASC
        """,
    )
    suspend fun listAllOpenFuture(nowMillis: Long): List<CarePlanEntity>

    @Query("SELECT * FROM care_plans WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): CarePlanEntity?

    @Query("SELECT * FROM care_plans WHERE clientUuid = :clientUuid LIMIT 1")
    suspend fun getByClientUuid(clientUuid: String): CarePlanEntity?

    @Query("SELECT * FROM care_plans ORDER BY id ASC")
    suspend fun listAllIncludingDeleted(): List<CarePlanEntity>

    @Query("SELECT * FROM care_plans WHERE syncDirty = 1 ORDER BY id ASC")
    suspend fun listPendingSync(): List<CarePlanEntity>

    @Query(
        """
        UPDATE care_plans SET syncDirty = 0
        WHERE clientUuid = :clientUuid AND updatedAt = :updatedAt
        """,
    )
    suspend fun markSynced(clientUuid: String, updatedAt: Long)

    @Query("UPDATE care_plans SET syncDirty = 1")
    suspend fun markAllPendingSync()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: CarePlanEntity): Long

    @Update
    suspend fun update(plan: CarePlanEntity)

    /**
     * Device-local payload replica (e.g. photos[] paths) without advancing
     * [CarePlanEntity.updatedAt] or [CarePlanEntity.syncDirty].
     */
    @Query(
        """
        UPDATE care_plans
        SET payloadJson = :payloadJson
        WHERE id = :id AND payloadJson = :expectedPayloadJson
        """,
    )
    suspend fun updatePayloadReplica(
        id: Long,
        expectedPayloadJson: String,
        payloadJson: String,
    ): Int

    @Query(
        """
        UPDATE care_plans
        SET deletedAt = :deletedAt, updatedAt = :deletedAt, syncDirty = 1
        WHERE id = :id
        """,
    )
    suspend fun softDelete(id: Long, deletedAt: Long)

    @Query("DELETE FROM care_plans")
    suspend fun deleteAll()
}

@Dao
interface FulfillmentCandidateDao {
    @Query("SELECT * FROM fulfillment_candidates WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): FulfillmentCandidateEntity?

    @Query("SELECT * FROM fulfillment_candidates WHERE clientUuid = :clientUuid LIMIT 1")
    suspend fun getByClientUuid(clientUuid: String): FulfillmentCandidateEntity?

    @Query(
        """
        SELECT * FROM fulfillment_candidates
        WHERE carePlanClientUuid = :carePlanClientUuid
        ORDER BY id ASC
        """,
    )
    suspend fun listForCarePlan(carePlanClientUuid: String): List<FulfillmentCandidateEntity>

    @Query(
        """
        SELECT * FROM fulfillment_candidates
        WHERE recordClientUuid = :recordClientUuid
        ORDER BY id ASC
        """,
    )
    suspend fun listForRecord(recordClientUuid: String): List<FulfillmentCandidateEntity>

    @Query("SELECT * FROM fulfillment_candidates ORDER BY id ASC")
    suspend fun listAllIncludingDeleted(): List<FulfillmentCandidateEntity>

    @Query("SELECT * FROM fulfillment_candidates WHERE syncDirty = 1 ORDER BY id ASC")
    suspend fun listPendingSync(): List<FulfillmentCandidateEntity>

    /**
     * Record client UUIDs linked to conflict-not-adopted candidates. Ordinary
     * timeline/summary/search/export must exclude these without soft-deleting
     * the underlying Record or photos.
     */
    @Query(
        """
        SELECT recordClientUuid FROM fulfillment_candidates
        WHERE adoptionStatus = 'conflict_not_adopted'
          AND deletedAt IS NULL
        """,
    )
    suspend fun listConflictNotAdoptedRecordUuids(): List<String>

    @Query(
        """
        SELECT * FROM fulfillment_candidates
        WHERE adoptionStatus = 'conflict_not_adopted'
          AND deletedAt IS NULL
        ORDER BY confirmedAt ASC, clientUuid ASC
        """,
    )
    suspend fun listConflictNotAdopted(): List<FulfillmentCandidateEntity>

    @Query(
        """
        SELECT * FROM fulfillment_candidates
        WHERE carePlanClientUuid = :carePlanClientUuid
          AND adoptionStatus = 'conflict_not_adopted'
          AND deletedAt IS NULL
        ORDER BY confirmedAt ASC, clientUuid ASC
        """,
    )
    suspend fun listConflictNotAdoptedForCarePlan(
        carePlanClientUuid: String,
    ): List<FulfillmentCandidateEntity>

    @Query(
        """
        SELECT recordClientUuid FROM fulfillment_candidates
        WHERE adoptionStatus = 'conflict_not_adopted'
          AND deletedAt IS NULL
        """,
    )
    fun observeConflictNotAdoptedRecordUuids(): Flow<List<String>>

    @Query(
        """
        UPDATE fulfillment_candidates SET syncDirty = 0
        WHERE clientUuid = :clientUuid AND updatedAt = :updatedAt
        """,
    )
    suspend fun markSynced(clientUuid: String, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(candidate: FulfillmentCandidateEntity): Long

    @Update
    suspend fun update(candidate: FulfillmentCandidateEntity)

    @Query("DELETE FROM fulfillment_candidates")
    suspend fun deleteAll()
}

@Dao
interface MediaAssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(asset: MediaAssetEntity): Long

    @Query("SELECT * FROM media_assets WHERE recordId = :recordId")
    suspend fun listForRecord(recordId: Long): List<MediaAssetEntity>

    @Query(
        """
        SELECT * FROM media_assets
        WHERE recordId = :recordId AND deletedAt IS NULL
        ORDER BY id ASC
        """,
    )
    suspend fun listActiveForRecord(recordId: Long): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE carePlanId = :carePlanId")
    suspend fun listForCarePlan(carePlanId: Long): List<MediaAssetEntity>

    @Query(
        """
        SELECT * FROM media_assets
        WHERE carePlanId = :carePlanId AND deletedAt IS NULL
        ORDER BY id ASC
        """,
    )
    suspend fun listActiveForCarePlan(carePlanId: Long): List<MediaAssetEntity>

    @Query(
        """
        SELECT * FROM media_assets
        WHERE babyId = :babyId AND kind = 'avatar' AND deletedAt IS NULL
        ORDER BY updatedAt DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun activeAvatarForBaby(babyId: Long): MediaAssetEntity?

    @Query("SELECT * FROM media_assets ORDER BY id ASC")
    suspend fun listAllIncludingDeleted(): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE syncDirty = 1 ORDER BY id ASC")
    suspend fun listPendingSync(): List<MediaAssetEntity>

    @Query(
        """
        UPDATE media_assets SET syncDirty = 0
        WHERE clientUuid = :clientUuid AND updatedAt = :updatedAt
        """,
    )
    suspend fun markSynced(clientUuid: String, updatedAt: Long)

    @Query(
        """
        SELECT * FROM media_assets
        WHERE deletedAt IS NULL
          AND remoteUri IS NOT NULL
          AND localUri = ''
        ORDER BY id ASC
        """,
    )
    suspend fun listMissingLocalBytes(): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE clientUuid = :uuid LIMIT 1")
    suspend fun getByClientUuid(uuid: String): MediaAssetEntity?

    @Update
    suspend fun update(asset: MediaAssetEntity)

    @Query("UPDATE media_assets SET remoteUri = NULL, syncDirty = 1")
    suspend fun clearRemoteUris()

    @Query("DELETE FROM media_assets WHERE kind = 'log'")
    suspend fun deleteLogMedia()

    @Query("DELETE FROM media_assets WHERE recordId = :recordId")
    suspend fun deleteForRecord(recordId: Long)

    @Query("DELETE FROM media_assets")
    suspend fun deleteAll()
}
