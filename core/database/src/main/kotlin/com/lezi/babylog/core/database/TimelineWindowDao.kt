package com.lezi.babylog.core.database

import com.lezi.babylog.core.database.causal.AUTO_ALIGNED_RELATION_PREDICATE
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SleepIntervalProjection
import com.lezi.babylog.core.model.WakeObservationFact
import com.lezi.babylog.core.model.isWakeShortcutTarget
import com.lezi.babylog.core.database.causal.TERMINAL_RECEIPT_KEY_PREFIX
import com.lezi.babylog.core.database.causal.TERMINAL_RECEIPT_KEY_RANGE_END
import com.lezi.babylog.core.model.projectSleepIntervalCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

/**
 * Record-window invalidation probe. Subscribes to records, wake observations,
 * fulfillment candidates, and conflict summaries. Media writes must not reload
 * this projection: its domain record output has no media fields.
 */
internal const val RECORD_WAKE_INVALIDATION_SQL =
    "SELECT " +
        "COALESCE((SELECT id FROM records WHERE id = -1), 0) + " +
        "COALESCE((SELECT id FROM wake_observations WHERE id = -1), 0) + " +
        "COALESCE((SELECT id FROM fulfillment_candidates WHERE id = -1), 0) + " +
        "COALESCE((SELECT rowid FROM conflict_summaries WHERE rowid = -1), 0)"

/**
 * Timeline snapshot invalidation. Source metadata writes share their source-relation transaction.
 * Do not subscribe to the entire transport journal: unrelated sync bookkeeping must not reload
 * the complete window. Identity retirement clears relations alongside their auto markers.
 */
internal const val TIMELINE_WINDOW_INVALIDATION_SQL =
    "SELECT " +
        "COALESCE((SELECT id FROM records WHERE id = -1), 0) + " +
        "COALESCE((SELECT id FROM care_plans WHERE id = -1), 0) + " +
        "COALESCE((SELECT id FROM media_assets WHERE id = -1), 0) + " +
        "COALESCE((SELECT id FROM fulfillment_candidates WHERE id = -1), 0) + " +
        "COALESCE((SELECT id FROM wake_observations WHERE id = -1), 0) + " +
        "COALESCE((SELECT rowid FROM conflict_summaries WHERE rowid = -1), 0) + " +
        "COALESCE((SELECT rowid FROM source_relations WHERE rowid = -1), 0) + " +
        "COALESCE((SELECT rowid FROM source_relation_members WHERE rowid = -1), 0)"

/** One SQL candidate rule for record roots, wake evidence, root photos and source edges. */
private const val RECORD_WINDOW_PREDICATE = """
    r.babyId = :babyId AND r.deletedAt IS NULL
    AND r.clientUuid NOT IN (
        SELECT recordClientUuid FROM fulfillment_candidates
        WHERE adoptionStatus = 'conflict_not_adopted' AND deletedAt IS NULL
    )
    AND r.timestamp < :endExclusive
    AND (r.timestamp >= :startInclusive OR (r.type = 'sleep' AND COALESCE(
        (SELECT selected.wakeTimestamp FROM wake_observations selected
         WHERE selected.clientUuid = r.effectiveWakeObservationClientUuid
           AND selected.sleepRecordClientUuid = r.clientUuid
           AND selected.deletedAt IS NULL AND selected.withdrawn = 0
           AND selected.wakeTimestamp >= r.timestamp),
        (SELECT MIN(legal.wakeTimestamp) FROM wake_observations legal
         WHERE legal.sleepRecordClientUuid = r.clientUuid
           AND legal.deletedAt IS NULL AND legal.withdrawn = 0
           AND legal.wakeTimestamp >= r.timestamp),
        r.endTimestamp, 9223372036854775807) > :startInclusive))
"""

/** One immutable Room transaction used to assemble a timeline window. */
data class TimelineWindowDbSnapshot(
    val records: List<ProjectedRecordEntity>,
    val carePlans: List<CarePlanEntity>,
    val media: List<MediaAssetEntity>,
    val unacceptedReceiptEpochs: Map<String, Long> = emptyMap(),
    val sourceRelations: List<TimelineSourceRelation> = emptyList(),
)

/** Existing source facts relevant to roots in this window, read in the same transaction. */
data class TimelineSourceRelation(
    val recordClientUuid: String,
    val displayClientUuid: String,
    val role: String,
    val autoAligned: Boolean,
)

data class TerminalReceiptKeyAndEpoch(
    val journalKey: String,
    val contentEpoch: Long,
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
 * owned by this seam's canonical [projectSleepIntervalCancellable] projection.
 */
interface RecordWakeProjectionDao {
    /** Wide probe kept for timeline snapshot consumers that include media. */
    fun observeInvalidations(): Flow<Long>

    /** Narrow probe for record + wake projection. Does not include media_assets. */
    fun observeRecordWakeInvalidations(): Flow<Long>

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

    /**
     * Newest projected records (non-deleted, not conflict-not-adopted) whose
     * root starts at or before [maxTimestampInclusive], newest first, bounded
     * to [maxRootCount] roots. Lets callers resolve "the newest visible
     * record" without a full-history projection scan; returned order is the
     * root DESC order so caller-side filtering (e.g. hidden duplicate-source
     * rows) can still pick the newest survivor.
     */
    suspend fun loadLatestProjectedRecordsAtOrBefore(
        babyId: Long,
        maxTimestampInclusive: Long,
        maxRootCount: Int,
    ): List<ProjectedRecordEntity>
}

/**
 * Timeline-specific extension of the shared Record + Wake projection.
 *
 * Record projection is exactly three batch reads. A complete timeline snapshot adds one plan
 * read, one log-media read, one terminal-receipt journal read and one bounded source-relation
 * read, for seven reads after each invalidation signal. Source edges replace separate role/auto
 * subscriptions and per-tick detail reads. Statement count is independent of row count.
 */
@Dao
interface TimelineWindowDao : RecordWakeProjectionDao {
    /**
     * Lightweight invalidation probe for the timeline snapshot. Includes media_assets and
     * care_plans so photo and plan rows refresh. Record windows use
     * [observeRecordWakeInvalidations] instead.
     */
    @Query(TIMELINE_WINDOW_INVALIDATION_SQL)
    override fun observeInvalidations(): Flow<Long>

    /**
     * Record + wake window probe. Includes wake_observations, fulfillment_candidates, and
     * conflict_summaries so wake and conflict updates re-assemble records without a media
     * subscription.
     */
    @Query(RECORD_WAKE_INVALIDATION_SQL)
    override fun observeRecordWakeInvalidations(): Flow<Long>

    /** Conservative root candidates; [loadRecordProjection] applies canonical overlap afterward. */
    @Query(
        "SELECT r.* FROM records r WHERE " + RECORD_WINDOW_PREDICATE + " ORDER BY r.timestamp DESC",
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
        WHERE """ + RECORD_WINDOW_PREDICATE + """
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
        WHERE m.kind = 'wake' AND m.deletedAt IS NULL AND """ + RECORD_WINDOW_PREDICATE + """
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
               AND NOT EXISTS (
                   SELECT 1 FROM wake_observations legal
                   WHERE legal.sleepRecordClientUuid = records.clientUuid
                     AND legal.deletedAt IS NULL AND legal.withdrawn = 0
                     AND legal.wakeTimestamp >= records.timestamp
               )
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
                  SELECT 1 FROM records r
                  WHERE r.id = media_assets.recordId AND """ + RECORD_WINDOW_PREDICATE + """
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
        startInclusive: Long,
        endExclusive: Long,
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
        return projectRecordRoots(records, wakes, wakeMedia).filterIndexed { index, projected ->
            if ((index and 127) == 0) context.ensureActive()
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
        val projected = projectRecordRoots(records, wakes, wakeMedia).associateBy {
            context.ensureActive()
            it.root.clientUuid
        }
        return requested.mapNotNull(projected::get)
    }

    /** Newest-root primitive behind [loadLatestProjectedRecordsAtOrBefore]. */
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
          AND timestamp <= :maxTimestampInclusive
        ORDER BY timestamp DESC, clientUuid DESC
        LIMIT :maxRootCount
        """,
    )
    suspend fun listLatestRecordRoots(
        babyId: Long,
        maxTimestampInclusive: Long,
        maxRootCount: Int,
    ): List<RecordEntity>

    @Transaction
    override suspend fun loadLatestProjectedRecordsAtOrBefore(
        babyId: Long,
        maxTimestampInclusive: Long,
        maxRootCount: Int,
    ): List<ProjectedRecordEntity> {
        require(maxRootCount > 0) { "maxRootCount must be positive" }
        require(maxRootCount <= MAX_EXPLICIT_PROJECTION_ROOTS) {
            "latest-root fetch must stay within $MAX_EXPLICIT_PROJECTION_ROOTS roots"
        }
        val roots = listLatestRecordRoots(babyId, maxTimestampInclusive, maxRootCount)
        if (roots.isEmpty()) return emptyList()
        return loadRecordProjectionForRoots(roots.map(RecordEntity::clientUuid))
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
    @Query(
        "SELECT journalKey, contentEpoch FROM causal_transport_journal " +
            "WHERE journalKey >= '" + TERMINAL_RECEIPT_KEY_PREFIX +
            "' AND journalKey < '" + TERMINAL_RECEIPT_KEY_RANGE_END + "'",
    )
    suspend fun listTerminalReceiptKeyAndEpochs(): List<TerminalReceiptKeyAndEpoch>
    @Query(
        """
        SELECT member.recordClientUuid, relation.displayClientUuid, member.role,
               (member.role = 'display' AND member.recordClientUuid = relation.displayClientUuid
                AND (""" + AUTO_ALIGNED_RELATION_PREDICATE + """)) AS autoAligned
        FROM source_relation_members member
        JOIN source_relations relation ON relation.relationId = member.relationId
        JOIN records r ON r.clientUuid = member.recordClientUuid
        WHERE """ + RECORD_WINDOW_PREDICATE + """
        ORDER BY relation.createdAt, member.recordClientUuid
        """,
    )
    suspend fun listWindowSourceRelations(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<TimelineSourceRelation>

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
            startInclusive = recordStartInclusive,
            endExclusive = recordEndExclusive,
            planDayStart = planDayStart,
            planDayEnd = planDayEnd,
            nowMillis = nowMillis,
            includeOverdue = includeOverdue,
        )
        context.ensureActive()
        val unacceptedEntries = listTerminalReceiptKeyAndEpochs()
        val unacceptedEpochs = unacceptedEntries.mapNotNull { entry ->
            val uuid = entry.journalKey.substringAfter("terminal-receipt:").substringAfter(":", missingDelimiterValue = "").takeIf(String::isNotBlank)
            if (uuid != null) uuid to entry.contentEpoch else null
        }.toMap()
        context.ensureActive()
        val sourceRelations = listWindowSourceRelations(babyId, recordStartInclusive, recordEndExclusive)
        context.ensureActive()
        return TimelineWindowDbSnapshot(
            records, carePlans, media,
            unacceptedReceiptEpochs = unacceptedEpochs,
            sourceRelations = sourceRelations,
        )
    }
}

/** Root cap shared by explicit-root projection and the latest-root fetch. */
const val MAX_EXPLICIT_PROJECTION_ROOTS = 64

private suspend fun projectRecordRoots(
    records: List<RecordEntity>,
    wakes: List<WakeObservationEntity>,
    wakeMedia: List<MediaAssetEntity>,
): List<ProjectedRecordEntity> {
    val context = currentCoroutineContext()
    var visited = 0
    fun checkWork() {
        if ((visited++ and 127) == 0) context.ensureActive()
    }
    val wakesBySleep = linkedMapOf<String, MutableList<WakeObservationEntity>>()
    for (wake in wakes) {
        checkWork()
        wakesBySleep.getOrPut(wake.sleepRecordClientUuid) { mutableListOf() }.add(wake)
    }
    val wakeMediaByObservation = linkedMapOf<Long, MutableList<MediaAssetEntity>>()
    for (media in wakeMedia) {
        checkWork()
        wakeMediaByObservation.getOrPut(requireNotNull(media.wakeObservationId)) { mutableListOf() }.add(media)
    }
    val initialIntervals = linkedMapOf<String, SleepIntervalProjection>()
    for (root in records) {
        checkWork()
        if (root.type == RecordType.SLEEP.key) {
            initialIntervals[root.clientUuid] = projectSleep(root, wakesBySleep[root.clientUuid].orEmpty(), emptyList())
        }
    }
    var latestOpenStart: Pair<String, Long>? = null
    val order = compareBy<Pair<String, Long>> { it.second }.thenBy { it.first }
    for (root in records) {
        checkWork()
        if (initialIntervals[root.clientUuid]?.isOpen == true) {
            val candidate = root.clientUuid to root.timestamp
            val previous = latestOpenStart
            if (previous == null || order.compare(candidate, previous) > 0) latestOpenStart = candidate
        }
    }
    return records.map { root ->
        checkWork()
        val observations = wakesBySleep[root.clientUuid].orEmpty()
        val initialInterval = initialIntervals[root.clientUuid]
        val projectedMedia = buildList {
            for (observation in observations) {
                checkWork()
                for (media in wakeMediaByObservation[observation.id].orEmpty()) {
                    checkWork()
                    add(media)
                }
            }
        }
        ProjectedRecordEntity(
            root = root,
            sleepInterval = if (initialInterval?.isOpen == true) {
                projectSleep(
                    root = root,
                    observations = observations,
                    peerOpenSleepStarts = listOfNotNull(latestOpenStart?.takeIf { it.first != root.clientUuid }),
                )
            } else {
                initialInterval
            },
            wakeObservations = observations,
            wakeMedia = projectedMedia,
        )
    }
}

private suspend fun projectSleep(
    root: RecordEntity,
    observations: List<WakeObservationEntity>,
    peerOpenSleepStarts: Collection<Pair<String, Long>>,
): SleepIntervalProjection {
    val context = currentCoroutineContext()
    return projectSleepIntervalCancellable(
        sleepClientUuid = root.clientUuid,
        startTimestamp = root.timestamp,
        effectiveWakeObservationClientUuid = root.effectiveWakeObservationClientUuid,
        observations = observations.mapIndexed { index, wake ->
            if ((index and 127) == 0) context.ensureActive()
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
}
