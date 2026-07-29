package com.lezi.babylog.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

/** One immutable Room transaction used to assemble a timeline window. */
data class TimelineWindowDbSnapshot(
    val records: List<RecordEntity>,
    val carePlans: List<CarePlanEntity>,
    val media: List<MediaAssetEntity>,
)

/**
 * Bounded database seam for timeline roots and their active log media.
 *
 * One invalidation query triggers exactly three batch reads. No query accepts a
 * per-row id list, so the number of SQL statements is independent of row count.
 */
@Dao
interface TimelineWindowDao {
    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM records) +
            (SELECT COUNT(*) FROM care_plans) +
            (SELECT COUNT(*) FROM media_assets)
        """,
    )
    fun observeInvalidations(): Flow<Long>

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
    suspend fun listRecordRoots(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<RecordEntity>

    @Query(
        """
        SELECT * FROM care_plans
        WHERE babyId = :babyId
          AND deletedAt IS NULL
          AND status IN ('pending', 'missed')
          AND (
              (:includeOverdue AND scheduledAt < :nowMillis)
              OR (scheduledAt >= :dayStart AND scheduledAt < :dayEnd)
          )
        ORDER BY scheduledAt ASC
        """,
    )
    suspend fun listPlanRoots(
        babyId: Long,
        dayStart: Long,
        dayEnd: Long,
        nowMillis: Long,
        includeOverdue: Boolean,
    ): List<CarePlanEntity>

    @Query(
        """
        SELECT media_assets.* FROM media_assets
        WHERE media_assets.kind = 'log'
          AND media_assets.deletedAt IS NULL
          AND (
              EXISTS (
                  SELECT 1 FROM records
                  WHERE records.id = media_assets.recordId
                    AND records.babyId = :babyId
                    AND records.deletedAt IS NULL
                    AND records.clientUuid NOT IN (
                        SELECT recordClientUuid FROM fulfillment_candidates
                        WHERE adoptionStatus = 'conflict_not_adopted'
                          AND deletedAt IS NULL
                    )
                    AND records.timestamp < :recordEndExclusive
                    AND (
                        records.timestamp >= :recordStartInclusive
                        OR (
                            records.type = 'sleep'
                            AND (
                                records.endTimestamp IS NULL
                                OR records.endTimestamp > :recordStartInclusive
                            )
                        )
                    )
              )
              OR EXISTS (
                  SELECT 1 FROM care_plans
                  WHERE care_plans.id = media_assets.carePlanId
                    AND care_plans.babyId = :babyId
                    AND care_plans.deletedAt IS NULL
                    AND care_plans.status IN ('pending', 'missed')
                    AND (
                        (:includeOverdue AND care_plans.scheduledAt < :nowMillis)
                        OR (
                            care_plans.scheduledAt >= :planDayStart
                            AND care_plans.scheduledAt < :planDayEnd
                        )
                    )
              )
          )
        ORDER BY media_assets.id ASC
        """,
    )
    suspend fun listActiveLogMedia(
        babyId: Long,
        recordStartInclusive: Long,
        recordEndExclusive: Long,
        planDayStart: Long,
        planDayEnd: Long,
        nowMillis: Long,
        includeOverdue: Boolean,
    ): List<MediaAssetEntity>

    @Transaction
    suspend fun loadSnapshot(
        babyId: Long,
        recordStartInclusive: Long,
        recordEndExclusive: Long,
        planDayStart: Long,
        planDayEnd: Long,
        nowMillis: Long,
        includeOverdue: Boolean,
    ): TimelineWindowDbSnapshot {
        val context = currentCoroutineContext()
        val records = listRecordRoots(babyId, recordStartInclusive, recordEndExclusive)
        context.ensureActive()
        val carePlans = listPlanRoots(
            babyId = babyId,
            dayStart = planDayStart,
            dayEnd = planDayEnd,
            nowMillis = nowMillis,
            includeOverdue = includeOverdue,
        )
        context.ensureActive()
        val media = listActiveLogMedia(
            babyId = babyId,
            recordStartInclusive = recordStartInclusive,
            recordEndExclusive = recordEndExclusive,
            planDayStart = planDayStart,
            planDayEnd = planDayEnd,
            nowMillis = nowMillis,
            includeOverdue = includeOverdue,
        )
        context.ensureActive()
        return TimelineWindowDbSnapshot(records, carePlans, media)
    }
}
