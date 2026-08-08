package com.lezi.babylog.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.lezi.babylog.core.database.causal.toAppliedColumns
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

    @Query("SELECT * FROM babies WHERE deletedAt IS NULL AND familyAuthority = 1 ORDER BY sortOrder ASC, id ASC")
    suspend fun listFamilyAuthority(): List<BabyEntity>

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

    @Query(
        """
        DELETE FROM babies
        WHERE clientUuid = :clientUuid AND updatedAt = :updatedAt AND deletedAt IS NOT NULL
        """,
    )
    suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int

    /**
     * After an avatar (or baby+avatar) atomic package publishes elevated
     * [publishedUpdatedAt] for content epoch [expectedLocalUpdatedAt], CAS-align
     * local `updatedAt` so the next baby edit exceeds NAS LWW.
     *
     * Advance + clear dirty only when local revision still equals
     * [expectedLocalUpdatedAt]. Concurrent profile edits (including ones that
     * land exactly on [publishedUpdatedAt]) are never overwritten and dirty
     * stays set so the next sync cycle replans the newer root.
     *
     * Returns true when local content matched the published epoch and was
     * advanced (or already clean at published) — the baby equivalent of a
     * family root receipt watermark. See [decideSyntheticRootPublicationBaby].
     */
    @Transaction
    suspend fun acknowledgeSyntheticRootPublication(
        clientUuid: String,
        expectedLocalUpdatedAt: Long,
        publishedUpdatedAt: Long,
    ): Boolean {
        val current = getByClientUuid(clientUuid) ?: return false
        val decision = decideSyntheticRootPublicationBaby(
            currentUpdatedAt = current.updatedAt,
            currentSyncDirty = current.syncDirty,
            expectedLocalUpdatedAt = expectedLocalUpdatedAt,
            publishedUpdatedAt = publishedUpdatedAt,
        )
        val write = decision.write
        if (write != null) {
            update(
                current.copy(
                    updatedAt = write.updatedAt,
                    syncDirty = write.syncDirty,
                ),
            )
        }
        return decision.confirmed
    }

    @Query("UPDATE babies SET syncDirty = 1")
    suspend fun markAllPendingSync()

    @Query("UPDATE babies SET familyAuthority = 0")
    suspend fun clearFamilyAuthority()

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

    /** Device-local field only; cannot overwrite a concurrent family-authority revision. */
    @Query("UPDATE babies SET themeColorArgb = :themeColorArgb WHERE id = :id")
    suspend fun updateLocalTheme(id: Long, themeColorArgb: Int)

    /** Device-local field only; cannot overwrite a concurrent family-authority revision. */
    @Query("UPDATE babies SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateLocalSortOrder(id: Long, sortOrder: Int)

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

    /**
     * Freeze a dirty content epoch for causal reconcile/commit. Same local revision
     * reuses [BabyEntity.mutationId]; a newer edit mints [newMutationId].
     */
    @Transaction
    suspend fun freezeDirtyEpoch(
        clientUuid: String,
        contentEpoch: Long,
        newMutationId: String,
    ): BabyEntity? {
        val current = getByClientUuid(clientUuid) ?: return null
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

    @Query(
        """
        DELETE FROM records
        WHERE clientUuid = :clientUuid AND updatedAt = :updatedAt AND deletedAt IS NOT NULL
        """,
    )
    suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int

    /**
     * Persist a successful atomic root commit without letting an old acknowledgement
     * clear a newer local edit. Future/invalid acknowledgements are rejected.
     */
    @Transaction
    suspend fun acknowledgeFamilyPublishedVersion(
        clientUuid: String,
        publishedUpdatedAt: Long,
    ): Boolean {
        val current = getByClientUuid(clientUuid) ?: return false
        if (publishedUpdatedAt <= 0L || publishedUpdatedAt > current.updatedAt) return false
        val validExisting = current.familyPublishedUpdatedAt
            ?.takeIf { it > 0L && it <= current.updatedAt }
        val mergedReceipt = maxOf(validExisting ?: 0L, publishedUpdatedAt)
        val confirmsCurrent = publishedUpdatedAt == current.updatedAt
        if (validExisting != mergedReceipt || (confirmsCurrent && current.syncDirty)) {
            update(
                current.copy(
                    familyPublishedUpdatedAt = mergedReceipt,
                    syncDirty = if (confirmsCurrent) false else current.syncDirty,
                ),
            )
        }
        return confirmsCurrent
    }

    /**
     * After a standalone-media atomic package elevated the root to
     * [publishedUpdatedAt] for content epoch [expectedLocalUpdatedAt], align local
     * revision + [RecordEntity.familyPublishedUpdatedAt] without clobbering a
     * concurrent content edit.
     *
     * - Content still at [expectedLocalUpdatedAt]: advance `updatedAt` to
     *   [publishedUpdatedAt], set receipt to that value, clear dirty.
     * - Concurrent newer content (including `updatedAt == publishedUpdatedAt`
     *   with dirty still set): never overwrite body; advance receipt only when
     *   [publishedUpdatedAt] ≤ current `updatedAt` (monotonic); keep dirty so
     *   the next sync cycle replans the newer root.
     * - Already clean at [publishedUpdatedAt] (idempotent retry): ensure receipt.
     *
     * Returns true when local content was confirmed at the published revision.
     * Shared CAS: [decideSyntheticRootPublicationWithReceipt].
     */
    @Transaction
    suspend fun acknowledgeSyntheticRootPublication(
        clientUuid: String,
        expectedLocalUpdatedAt: Long,
        publishedUpdatedAt: Long,
    ): Boolean {
        val current = getByClientUuid(clientUuid) ?: return false
        val decision = decideSyntheticRootPublicationWithReceipt(
            currentUpdatedAt = current.updatedAt,
            currentFamilyPublishedUpdatedAt = current.familyPublishedUpdatedAt,
            currentSyncDirty = current.syncDirty,
            expectedLocalUpdatedAt = expectedLocalUpdatedAt,
            publishedUpdatedAt = publishedUpdatedAt,
        )
        val write = decision.write
        if (write != null) {
            update(
                current.copy(
                    updatedAt = write.updatedAt,
                    familyPublishedUpdatedAt = write.familyPublishedUpdatedAt,
                    syncDirty = write.syncDirty,
                ),
            )
        }
        return decision.confirmed
    }

    /** Merge server-owned metadata without changing content, revision, or dirty state. */
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

    /**
     * Latest truly open SleepStart: no effective wake, no legacy end, and no
     * non-withdrawn legal WakeObservation (ticket 06 provisional close).
     * Observes both `records` and `wake_observations` for live invalidation.
     */
    @Query(
        """
        SELECT r.* FROM records r
        WHERE r.babyId = :babyId
          AND r.type = 'sleep'
          AND r.deletedAt IS NULL
          AND r.endTimestamp IS NULL
          AND r.effectiveWakeObservationClientUuid IS NULL
          AND NOT EXISTS (
            SELECT 1 FROM wake_observations w
            WHERE w.sleepRecordClientUuid = r.clientUuid
              AND w.deletedAt IS NULL
              AND w.withdrawn = 0
              AND w.wakeTimestamp >= r.timestamp
          )
        ORDER BY r.timestamp DESC, r.clientUuid DESC
        LIMIT 1
        """,
    )
    suspend fun findOpenSleep(babyId: Long): RecordEntity?

    @Query(
        """
        SELECT r.* FROM records r
        WHERE r.babyId = :babyId
          AND r.type = 'sleep'
          AND r.deletedAt IS NULL
          AND r.endTimestamp IS NULL
          AND r.effectiveWakeObservationClientUuid IS NULL
          AND NOT EXISTS (
            SELECT 1 FROM wake_observations w
            WHERE w.sleepRecordClientUuid = r.clientUuid
              AND w.deletedAt IS NULL
              AND w.withdrawn = 0
              AND w.wakeTimestamp >= r.timestamp
          )
        ORDER BY r.timestamp DESC, r.clientUuid DESC
        """,
    )
    suspend fun listOpenSleeps(babyId: Long): List<RecordEntity>

    @Query(
        """
        SELECT r.* FROM records r
        WHERE r.babyId = :babyId
          AND r.type = 'sleep'
          AND r.deletedAt IS NULL
          AND r.endTimestamp IS NULL
          AND r.effectiveWakeObservationClientUuid IS NULL
          AND NOT EXISTS (
            SELECT 1 FROM wake_observations w
            WHERE w.sleepRecordClientUuid = r.clientUuid
              AND w.deletedAt IS NULL
              AND w.withdrawn = 0
              AND w.wakeTimestamp >= r.timestamp
          )
        ORDER BY r.timestamp DESC, r.clientUuid DESC
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
        SET deletedAt = :deletedAt, updatedAt = :deletedAt, syncDirty = 1
        WHERE id = :id
        """,
    )
    suspend fun softDelete(id: Long, deletedAt: Long)

    /**
     * Freeze a dirty content epoch for causal reconcile/commit. Same local revision
     * reuses [RecordEntity.mutationId]; a newer edit mints [newMutationId] while
     * keeping the last acknowledged [RecordEntity.baseVersion].
     */
    @Transaction
    suspend fun freezeDirtyEpoch(
        clientUuid: String,
        contentEpoch: Long,
        newMutationId: String,
    ): RecordEntity? {
        val current = getByClientUuid(clientUuid) ?: return null
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

    /**
     * Atomic `branched` receipt: convert pending mutation to unresolved conflict
     * without infinite resend or false fully-synced state.
     */
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

    @Query("DELETE FROM records")
    suspend fun deleteAll()
}

private fun RecordEntity.toCausalMutationState() =
    com.lezi.babylog.core.database.causal.CausalRootMutationState(
        baseVersion = baseVersion,
        mutationId = mutationId,
        contentEpoch = updatedAt,
        syncDirty = syncDirty,
        openConflictId = openConflictId,
        localBranchVersionId = localBranchVersionId,
    )

private fun BabyEntity.toCausalMutationState() =
    com.lezi.babylog.core.database.causal.CausalRootMutationState(
        baseVersion = baseVersion,
        mutationId = mutationId,
        contentEpoch = updatedAt,
        syncDirty = syncDirty,
        openConflictId = openConflictId,
        localBranchVersionId = localBranchVersionId,
    )

private fun CarePlanEntity.toCausalMutationState() =
    com.lezi.babylog.core.database.causal.CausalRootMutationState(
        baseVersion = baseVersion,
        mutationId = mutationId,
        contentEpoch = updatedAt,
        syncDirty = syncDirty,
        openConflictId = openConflictId,
        localBranchVersionId = localBranchVersionId,
    )

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

    @Query(
        """
        DELETE FROM care_plans
        WHERE clientUuid = :clientUuid AND updatedAt = :updatedAt AND deletedAt IS NOT NULL
        """,
    )
    suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int

    /** Record only successful atomic root commits; stale receipts never clean a newer edit. */
    @Transaction
    suspend fun acknowledgeFamilyPublishedVersion(
        clientUuid: String,
        publishedUpdatedAt: Long,
    ): Boolean {
        val current = getByClientUuid(clientUuid) ?: return false
        if (publishedUpdatedAt <= 0L || publishedUpdatedAt > current.updatedAt) return false
        val validExisting = current.familyPublishedUpdatedAt
            ?.takeIf { it > 0L && it <= current.updatedAt }
        val mergedReceipt = maxOf(validExisting ?: 0L, publishedUpdatedAt)
        val confirmsCurrent = publishedUpdatedAt == current.updatedAt
        if (validExisting != mergedReceipt || (confirmsCurrent && current.syncDirty)) {
            update(
                current.copy(
                    familyPublishedUpdatedAt = mergedReceipt,
                    syncDirty = if (confirmsCurrent) false else current.syncDirty,
                ),
            )
        }
        return confirmsCurrent
    }

    /**
     * CarePlan twin of [RecordDao.acknowledgeSyntheticRootPublication]: after a
     * standalone plan-photo package elevates the root, CAS-align local revision +
     * [CarePlanEntity.familyPublishedUpdatedAt] without clobbering concurrent edits.
     * Shared CAS: [decideSyntheticRootPublicationWithReceipt].
     */
    @Transaction
    suspend fun acknowledgeSyntheticRootPublication(
        clientUuid: String,
        expectedLocalUpdatedAt: Long,
        publishedUpdatedAt: Long,
    ): Boolean {
        val current = getByClientUuid(clientUuid) ?: return false
        val decision = decideSyntheticRootPublicationWithReceipt(
            currentUpdatedAt = current.updatedAt,
            currentFamilyPublishedUpdatedAt = current.familyPublishedUpdatedAt,
            currentSyncDirty = current.syncDirty,
            expectedLocalUpdatedAt = expectedLocalUpdatedAt,
            publishedUpdatedAt = publishedUpdatedAt,
        )
        val write = decision.write
        if (write != null) {
            update(
                current.copy(
                    updatedAt = write.updatedAt,
                    familyPublishedUpdatedAt = write.familyPublishedUpdatedAt,
                    syncDirty = write.syncDirty,
                ),
            )
        }
        return decision.confirmed
    }

    @Query("UPDATE care_plans SET syncDirty = 1")
    suspend fun markAllPendingSync()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: CarePlanEntity): Long

    @Update
    suspend fun update(plan: CarePlanEntity)

    /** Update device-local projection ownership without making the family entity dirty. */
    @Query(
        """
        UPDATE care_plans
        SET systemCalendarEventId = :eventId,
            systemCalendarReminderReady = :reminderReady,
            systemCalendarProjectionPending = :pending
        WHERE clientUuid = :clientUuid
        """,
    )
    suspend fun updateSystemCalendarProjection(
        clientUuid: String,
        eventId: String?,
        reminderReady: Boolean,
        pending: Boolean = false,
    )

    /** Persist only the device-local desired route; never changes family LWW metadata. */
    @Query(
        """
        UPDATE care_plans
        SET systemCalendarProjectionEnabled = :enabled
        WHERE clientUuid = :clientUuid
        """,
    )
    suspend fun updateSystemCalendarProjectionEnabled(clientUuid: String, enabled: Boolean)

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

    @Transaction
    suspend fun freezeDirtyEpoch(
        clientUuid: String,
        contentEpoch: Long,
        newMutationId: String,
    ): CarePlanEntity? {
        val current = getByClientUuid(clientUuid) ?: return null
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

    @Query(
        """
        DELETE FROM fulfillment_candidates
        WHERE clientUuid = :clientUuid AND updatedAt = :updatedAt AND deletedAt IS NOT NULL
        """,
    )
    suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int

    @Query("UPDATE fulfillment_candidates SET syncDirty = 1")
    suspend fun markAllPendingSync()

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
        WHERE wakeObservationId = :wakeObservationId AND deletedAt IS NULL
        ORDER BY id ASC
        """,
    )
    suspend fun listActiveForWakeObservation(wakeObservationId: Long): List<MediaAssetEntity>

    @Query(
        """
        SELECT * FROM media_assets
        WHERE babyId = :babyId AND kind = 'avatar' AND deletedAt IS NULL
        ORDER BY updatedAt DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun activeAvatarForBaby(babyId: Long): MediaAssetEntity?

    /**
     * Every active avatar row for [babyId] (no LIMIT). Used by deleteBaby to
     * tombstone legacy multi-active rows, not only the pointer target.
     */
    @Query(
        """
        SELECT * FROM media_assets
        WHERE babyId = :babyId AND kind = 'avatar' AND deletedAt IS NULL
        ORDER BY id ASC
        """,
    )
    suspend fun listActiveAvatarsForBaby(babyId: Long): List<MediaAssetEntity>

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
        DELETE FROM media_assets
        WHERE clientUuid = :clientUuid AND updatedAt = :updatedAt AND deletedAt IS NOT NULL
        """,
    )
    suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int

    @Query(
        """
        DELETE FROM media_assets
        WHERE clientUuid = :clientUuid
          AND updatedAt = :expectedUpdatedAt
          AND localUri = :expectedLocalUri
          AND (
              (deletedAt IS NULL AND :expectedDeletedAt IS NULL)
              OR deletedAt = :expectedDeletedAt
          )
        """,
    )
    suspend fun deleteExactRevision(
        clientUuid: String,
        expectedUpdatedAt: Long,
        expectedLocalUri: String,
        expectedDeletedAt: Long?,
    ): Int

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

    @Query(
        """
        SELECT COUNT(*) FROM media_assets
        WHERE localUri = :localUri AND deletedAt IS NULL
        """,
    )
    suspend fun countActiveReferences(localUri: String): Int

    @Query(
        """
        SELECT clientUuid FROM media_assets
        WHERE deletedAt IS NOT NULL AND localUri != ''
        ORDER BY id ASC
        """,
    )
    suspend fun listPendingFileCleanupClientUuids(): List<String>

    @Update
    suspend fun update(asset: MediaAssetEntity)

    /**
     * Merge prepare-time probe fields only when the published domain revision is
     * still current. Never rewrites ownership, tombstone, remoteUri, syncDirty,
     * or paths.
     *
     * **CAS WHERE anchor** ([MEDIA_ASSET_CAS_REVISION_WHERE]): keep this
     * predicate identical to [writeCommitReceipt]. JVM fakes must use
     * [MediaAssetEntity.matchesPublishedRevision]. Room regressions:
     * `MediaAssetCasRoomTest`.
     */
    @Query(
        """
        UPDATE media_assets
        SET mime = :mime,
            width = :width,
            height = :height,
            byteSize = :byteSize
        WHERE clientUuid = :clientUuid
          AND updatedAt = :expectedUpdatedAt
          AND localUri = :expectedLocalUri
          AND (
              (deletedAt IS NULL AND :expectedDeletedAt IS NULL)
              OR deletedAt = :expectedDeletedAt
          )
        """,
    )
    suspend fun mergePreparedMetadata(
        clientUuid: String,
        expectedUpdatedAt: Long,
        expectedLocalUri: String,
        expectedDeletedAt: Long?,
        mime: String?,
        width: Int?,
        height: Int?,
        byteSize: Long,
    ): Int

    /**
     * Write a root-commit receipt only when the published domain revision is
     * still current. Does not re-apply a prepare-time row snapshot.
     *
     * **CAS WHERE anchor** ([MEDIA_ASSET_CAS_REVISION_WHERE]): keep this
     * predicate identical to [mergePreparedMetadata]. JVM fakes must use
     * [MediaAssetEntity.matchesPublishedRevision]. Room regressions:
     * `MediaAssetCasRoomTest`.
     */
    @Query(
        """
        UPDATE media_assets
        SET remoteUri = :remoteUri
        WHERE clientUuid = :clientUuid
          AND updatedAt = :expectedUpdatedAt
          AND localUri = :expectedLocalUri
          AND (
              (deletedAt IS NULL AND :expectedDeletedAt IS NULL)
              OR deletedAt = :expectedDeletedAt
          )
        """,
    )
    suspend fun writeCommitReceipt(
        clientUuid: String,
        expectedUpdatedAt: Long,
        expectedLocalUri: String,
        expectedDeletedAt: Long?,
        remoteUri: String,
    ): Int

    @Query("UPDATE media_assets SET remoteUri = NULL, syncDirty = 1")
    suspend fun clearRemoteUris()

    @Query("DELETE FROM media_assets WHERE kind = 'log'")
    suspend fun deleteLogMedia()

    @Query("DELETE FROM media_assets WHERE clientUuid IN (:clientUuids)")
    suspend fun deleteByClientUuids(clientUuids: List<String>)

    @Query("DELETE FROM media_assets WHERE recordId = :recordId")
    suspend fun deleteForRecord(recordId: Long)

    @Query("DELETE FROM media_assets")
    suspend fun deleteAll()
}
