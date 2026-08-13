package com.lezi.babylog.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SleepIntervalProjection
import com.lezi.babylog.core.model.WakeObservationFact
import com.lezi.babylog.core.model.isWakeShortcutTarget
import com.lezi.babylog.core.model.projectSleepInterval
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

/** One immutable Room transaction used to assemble a timeline window. */
data class TimelineWindowDbSnapshot(
    val records: List<ProjectedRecordEntity>,
    val carePlans: List<CarePlanEntity>,
    val media: List<MediaAssetEntity>,
)

/** One Record root and its complete Wake read projection from one Room revision. */
data class ProjectedRecordEntity(
    val root: RecordEntity,
    val sleepInterval: SleepIntervalProjection?,
    val wakeObservations: List<WakeObservationEntity>,
    val wakeMedia: List<MediaAssetEntity>,
)

/**
 * Bounded Record + Wake read projection shared by Summary, detail and Timeline.
 *
 * Implementations publish only projections assembled from one storage revision. Effective-wake
 * selection, provisional fallback, illegal/withdrawn filtering and open-Sleep arbitration are
 * owned by this seam's canonical [projectSleepInterval] projection.
 */
interface RecordWakeProjectionDao {
    fun observeInvalidations(): Flow<Long>

    suspend fun loadRecordProjection(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<ProjectedRecordEntity>

    suspend fun loadRecordProjectionForRoots(
        rootClientUuids: List<String>,
    ): List<ProjectedRecordEntity>

    suspend fun loadOpenSleepProjection(babyId: Long): List<ProjectedRecordEntity>

    suspend fun loadWakeShortcutTarget(babyId: Long): ProjectedRecordEntity?
}

/**
 * Timeline-specific extension of the shared Record + Wake projection.
 *
 * Record projection is exactly three batch reads. A complete timeline snapshot adds one plan and
 * one log-media read, for five reads after each invalidation signal. Statement count is independent
 * of row count.
 */
@Dao
interface TimelineWindowDao : RecordWakeProjectionDao {
    /**
     * Lightweight invalidation probe for projected Record windows. Includes wake_observations and
     * conflict_summaries so WakeObservation create/edit/withdraw/select and
     * conflict summary updates re-assemble the rail without mutating Sleep rows.
     */
    @Query(
        """
        SELECT
            COALESCE((SELECT id FROM records WHERE id = -1), 0) +
            COALESCE((SELECT id FROM care_plans WHERE id = -1), 0) +
            COALESCE((SELECT id FROM media_assets WHERE id = -1), 0) +
            COALESCE((SELECT id FROM fulfillment_candidates WHERE id = -1), 0) +
            COALESCE((SELECT id FROM wake_observations WHERE id = -1), 0) +
            COALESCE((SELECT rowid FROM conflict_summaries WHERE rowid = -1), 0)
        """,
    )
    override fun observeInvalidations(): Flow<Long>

    /** Conservative root candidates; [loadRecordProjection] applies canonical overlap afterward. */
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
                  AND (
                      endTimestamp IS NULL
                      OR endTimestamp > :startInclusive
                      OR
                      EXISTS (
                          SELECT 1 FROM wake_observations w
                          WHERE w.sleepRecordClientUuid = records.clientUuid
                            AND w.wakeTimestamp > :startInclusive
                      )
                  )
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
        SELECT w.* FROM wake_observations w
        JOIN records r ON r.clientUuid = w.sleepRecordClientUuid
        WHERE r.babyId = :babyId
          AND r.deletedAt IS NULL
          AND r.clientUuid NOT IN (
              SELECT recordClientUuid FROM fulfillment_candidates
              WHERE adoptionStatus = 'conflict_not_adopted'
                AND deletedAt IS NULL
          )
          AND r.timestamp < :endExclusive
          AND (
              r.timestamp >= :startInclusive
              OR (
                  r.type = 'sleep'
                  AND (
                      r.endTimestamp IS NULL
                      OR r.endTimestamp > :startInclusive
                      OR
                      EXISTS (
                          SELECT 1 FROM wake_observations candidate
                          WHERE candidate.sleepRecordClientUuid = r.clientUuid
                            AND candidate.wakeTimestamp > :startInclusive
                      )
                  )
              )
          )
        ORDER BY w.sleepRecordClientUuid, w.wakeTimestamp, w.clientUuid
        """,
    )
    suspend fun listWakeObservationRoots(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<WakeObservationEntity>

    @Query(
        """
        SELECT m.* FROM media_assets m
        JOIN wake_observations w ON w.id = m.wakeObservationId
        JOIN records r ON r.clientUuid = w.sleepRecordClientUuid
        WHERE m.kind = 'wake'
          AND m.deletedAt IS NULL
          AND r.babyId = :babyId
          AND r.deletedAt IS NULL
          AND r.clientUuid NOT IN (
              SELECT recordClientUuid FROM fulfillment_candidates
              WHERE adoptionStatus = 'conflict_not_adopted'
                AND deletedAt IS NULL
          )
          AND r.timestamp < :endExclusive
          AND (
              r.timestamp >= :startInclusive
              OR (
                  r.type = 'sleep'
                  AND (
                      r.endTimestamp IS NULL
                      OR r.endTimestamp > :startInclusive
                      OR
                      EXISTS (
                          SELECT 1 FROM wake_observations candidate
                          WHERE candidate.sleepRecordClientUuid = r.clientUuid
                            AND candidate.wakeTimestamp > :startInclusive
                      )
                  )
              )
          )
        ORDER BY m.wakeObservationId, m.id
        """,
    )
    suspend fun listActiveWakeMedia(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<MediaAssetEntity>

    @Query(
        """
        SELECT * FROM records
        WHERE clientUuid NOT IN (
               SELECT recordClientUuid FROM fulfillment_candidates
               WHERE adoptionStatus = 'conflict_not_adopted'
                 AND deletedAt IS NULL
           )
          AND (
            clientUuid IN (:rootClientUuids)
            OR (
               type = 'sleep'
               AND deletedAt IS NULL
               AND babyId IN (
                   SELECT babyId FROM records WHERE clientUuid IN (:rootClientUuids)
               )
               AND endTimestamp IS NULL
            )
          )
        ORDER BY timestamp DESC
        """,
    )
    suspend fun listRecordRootsByClientUuids(
        rootClientUuids: List<String>,
    ): List<RecordEntity>

    @Query(
        """
        SELECT * FROM wake_observations
        WHERE sleepRecordClientUuid IN (
            SELECT r.clientUuid FROM records r
            WHERE r.clientUuid NOT IN (
                      SELECT recordClientUuid FROM fulfillment_candidates
                      WHERE adoptionStatus = 'conflict_not_adopted'
                        AND deletedAt IS NULL
                  )
              AND (
                r.clientUuid IN (:sleepRootClientUuids)
                OR (
                   r.type = 'sleep'
                   AND r.deletedAt IS NULL
                   AND r.babyId IN (
                       SELECT babyId FROM records WHERE clientUuid IN (:sleepRootClientUuids)
                   )
                   AND r.endTimestamp IS NULL
               )
              )
        )
        ORDER BY sleepRecordClientUuid, wakeTimestamp, clientUuid
        """,
    )
    suspend fun listWakeObservationsForRoots(
        sleepRootClientUuids: List<String>,
    ): List<WakeObservationEntity>

    @Query(
        """
        SELECT m.* FROM media_assets m
        JOIN wake_observations w ON w.id = m.wakeObservationId
        WHERE m.kind = 'wake'
          AND m.deletedAt IS NULL
          AND w.sleepRecordClientUuid IN (
              SELECT r.clientUuid FROM records r
              WHERE r.clientUuid NOT IN (
                        SELECT recordClientUuid FROM fulfillment_candidates
                        WHERE adoptionStatus = 'conflict_not_adopted'
                          AND deletedAt IS NULL
                    )
                AND (
                  r.clientUuid IN (:sleepRootClientUuids)
                  OR (
                     r.type = 'sleep'
                     AND r.deletedAt IS NULL
                     AND r.babyId IN (
                         SELECT babyId FROM records WHERE clientUuid IN (:sleepRootClientUuids)
                     )
                     AND r.endTimestamp IS NULL
                 )
                )
          )
        ORDER BY m.wakeObservationId, m.id
        """,
    )
    suspend fun listActiveWakeMediaForRoots(
        sleepRootClientUuids: List<String>,
    ): List<MediaAssetEntity>

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
                                OR
                                EXISTS (
                                    SELECT 1 FROM wake_observations candidate
                                    WHERE candidate.sleepRecordClientUuid = records.clientUuid
                                      AND candidate.wakeTimestamp > :recordStartInclusive
                                )
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
    override suspend fun loadRecordProjection(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<ProjectedRecordEntity> {
        val context = currentCoroutineContext()
        val records = listRecordRoots(babyId, startInclusive, endExclusive)
        context.ensureActive()
        val wakes = listWakeObservationRoots(babyId, startInclusive, endExclusive)
        context.ensureActive()
        val wakeMedia = listActiveWakeMedia(babyId, startInclusive, endExclusive)
        context.ensureActive()
        return projectRecordRoots(records, wakes, wakeMedia).filter { projected ->
            projected.root.timestamp >= startInclusive ||
                projected.sleepInterval?.let { interval ->
                    val projectedEnd = interval.endTimestamp
                    projectedEnd == null || projectedEnd > startInclusive
                } == true
        }
    }

    /** Bounded detail/root projection plus open Sleep peers for overlap parity. */
    @Transaction
    override suspend fun loadRecordProjectionForRoots(
        rootClientUuids: List<String>,
    ): List<ProjectedRecordEntity> {
        val requested = rootClientUuids.map(String::trim).filter(String::isNotEmpty).distinct()
        require(requested.isNotEmpty()) { "record projection roots must not be empty" }
        require(requested.size <= MAX_EXPLICIT_PROJECTION_ROOTS) {
            "record projection root set exceeds $MAX_EXPLICIT_PROJECTION_ROOTS"
        }
        val context = currentCoroutineContext()
        val records = listRecordRootsByClientUuids(requested)
        context.ensureActive()
        val wakes = listWakeObservationsForRoots(requested)
        context.ensureActive()
        val wakeMedia = listActiveWakeMediaForRoots(requested)
        context.ensureActive()
        val projected = projectRecordRoots(records, wakes, wakeMedia).associateBy { it.root.clientUuid }
        return requested.mapNotNull(projected::get)
    }

    @Query(
        """
        SELECT r.* FROM records r
        WHERE r.babyId = :babyId
          AND r.type = 'sleep'
          AND r.deletedAt IS NULL
          AND r.endTimestamp IS NULL
          AND r.clientUuid NOT IN (
              SELECT recordClientUuid FROM fulfillment_candidates
              WHERE adoptionStatus = 'conflict_not_adopted'
                AND deletedAt IS NULL
          )
        ORDER BY r.timestamp DESC, r.clientUuid DESC
        """,
    )
    suspend fun listOpenSleepCandidateRoots(babyId: Long): List<RecordEntity>

    @Query(
        """
        SELECT w.* FROM wake_observations w
        JOIN records r ON r.clientUuid = w.sleepRecordClientUuid
        WHERE r.babyId = :babyId
          AND r.type = 'sleep'
          AND r.deletedAt IS NULL
          AND r.endTimestamp IS NULL
          AND r.clientUuid NOT IN (
              SELECT recordClientUuid FROM fulfillment_candidates
              WHERE adoptionStatus = 'conflict_not_adopted'
                AND deletedAt IS NULL
          )
        ORDER BY w.sleepRecordClientUuid, w.wakeTimestamp, w.clientUuid
        """,
    )
    suspend fun listWakesForOpenSleepCandidates(babyId: Long): List<WakeObservationEntity>

    @Query(
        """
        SELECT m.* FROM media_assets m
        JOIN wake_observations w ON w.id = m.wakeObservationId
        JOIN records r ON r.clientUuid = w.sleepRecordClientUuid
        WHERE r.babyId = :babyId
          AND r.type = 'sleep'
          AND r.deletedAt IS NULL
          AND r.endTimestamp IS NULL
          AND r.clientUuid NOT IN (
              SELECT recordClientUuid FROM fulfillment_candidates
              WHERE adoptionStatus = 'conflict_not_adopted'
                AND deletedAt IS NULL
          )
          AND m.kind = 'wake'
          AND m.deletedAt IS NULL
        ORDER BY m.wakeObservationId, m.id
        """,
    )
    suspend fun listWakeMediaForOpenSleepCandidates(babyId: Long): List<MediaAssetEntity>

    /** Canonical open-Sleep projection; SQL above deliberately returns only raw candidates. */
    @Transaction
    override suspend fun loadOpenSleepProjection(babyId: Long): List<ProjectedRecordEntity> {
        val context = currentCoroutineContext()
        val candidates = listOpenSleepCandidateRoots(babyId)
        context.ensureActive()
        if (candidates.isEmpty()) return emptyList()
        val wakes = listWakesForOpenSleepCandidates(babyId)
        context.ensureActive()
        val wakeMedia = listWakeMediaForOpenSleepCandidates(babyId)
        context.ensureActive()
        return projectRecordRoots(candidates, wakes, wakeMedia)
            .filter { it.sleepInterval?.isOpen == true }
    }

    /** The one canonical latest-open selection; callers do not reimplement tie-breaking. */
    @Transaction
    override suspend fun loadWakeShortcutTarget(babyId: Long): ProjectedRecordEntity? =
        loadOpenSleepProjection(babyId).singleOrNull { projected ->
            projected.sleepInterval?.let(::isWakeShortcutTarget) == true
        }

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
        val records = loadRecordProjection(babyId, recordStartInclusive, recordEndExclusive)
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

private const val MAX_EXPLICIT_PROJECTION_ROOTS = 64

private fun projectRecordRoots(
    records: List<RecordEntity>,
    wakes: List<WakeObservationEntity>,
    wakeMedia: List<MediaAssetEntity>,
): List<ProjectedRecordEntity> {
    val wakesBySleep = wakes.groupBy(WakeObservationEntity::sleepRecordClientUuid)
    val wakeMediaByObservation = wakeMedia.groupBy { requireNotNull(it.wakeObservationId) }
    val initialIntervals = records.asSequence()
        .filter { it.type == RecordType.SLEEP.key }
        .associate { root ->
            root.clientUuid to projectSleep(root, wakesBySleep[root.clientUuid].orEmpty(), emptyList())
        }
    val openPeerStarts = records.asSequence()
        .filter { root -> initialIntervals[root.clientUuid]?.isOpen == true }
        .map { it.clientUuid to it.timestamp }
        .toList()
    val latestOpenStart = openPeerStarts.maxWithOrNull(
        compareBy<Pair<String, Long>> { it.second }.thenBy { it.first },
    )
    return records.map { root ->
        val observations = wakesBySleep[root.clientUuid].orEmpty()
        val initialInterval = initialIntervals[root.clientUuid]
        ProjectedRecordEntity(
            root = root,
            sleepInterval = if (initialInterval?.isOpen == true) {
                projectSleep(
                    root = root,
                    observations = observations,
                    peerOpenSleepStarts = listOfNotNull(
                        latestOpenStart?.takeIf { it.first != root.clientUuid },
                    ),
                )
            } else {
                initialInterval
            },
            wakeObservations = observations,
            wakeMedia = observations.flatMap { wakeMediaByObservation[it.id].orEmpty() },
        )
    }
}

private fun projectSleep(
    root: RecordEntity,
    observations: List<WakeObservationEntity>,
    peerOpenSleepStarts: Collection<Pair<String, Long>>,
): SleepIntervalProjection = projectSleepInterval(
    sleepClientUuid = root.clientUuid,
    startTimestamp = root.timestamp,
    effectiveWakeObservationClientUuid = root.effectiveWakeObservationClientUuid,
    observations = observations.map { wake ->
        WakeObservationFact(
            clientUuid = wake.clientUuid,
            wakeTimestamp = wake.wakeTimestamp,
            withdrawn = wake.withdrawn,
            observerMembershipId = wake.observerMembershipId,
            note = wake.note,
            deleted = wake.deletedAt != null,
        )
    },
    legacyEndTimestamp = root.endTimestamp,
    peerOpenSleepStarts = peerOpenSleepStarts,
)
