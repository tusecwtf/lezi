package com.lezi.babylog.domain.carelog
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.MAX_EXPLICIT_PROJECTION_ROOTS
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.ProjectedRecordEntity
import com.lezi.babylog.core.database.RecordWakeProjectionDao
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.MilkPayload
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import com.lezi.babylog.domain.toModel

/**
 * Single read-only coordination seam behind [CareLog].
 *
 * DAO selection, time windows, aggregation, search post-filtering and ordinary
 * fulfillment visibility live here so the public facade cannot grow a second
 * query implementation while mutation coordinators are split independently.
 */
internal class CareLogQueries(
    private val babyDao: BabyDao,
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val recordWakeProjectionDao: RecordWakeProjectionDao,
    private val sourceRoleClientUuids: suspend () -> Set<String> = { emptySet() },
) {
    private val fulfillmentSurface = FulfillmentSurface(fulfillmentCandidateDao)

    suspend fun projectedRecords(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<ProjectedRecordEntity> =
        recordWakeProjectionDao.loadRecordProjection(babyId, startInclusive, endExclusive)

    fun observeRecords(
        babyId: Long,
        startDayInclusive: LocalDate,
        endDayExclusive: LocalDate,
        zone: ZoneId,
    ): Flow<List<Record>> {
        require(startDayInclusive.isBefore(endDayExclusive)) {
            "startDayInclusive must be before endDayExclusive"
        }
        val start = startDayInclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = endDayExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        // DAO ordinary queries already exclude conflict-not-adopted fulfillment records.
        // transform (not mapLatest) so rapid StateFlow updates do not drop emissions.
        // Deliberately NOT distinctUntilChanged here: the widget refresh
        // coordinator rides this flow as its "summary inputs changed" trigger,
        // and a structurally equal window (a pre-day write under the bounded
        // widget read) must still re-emit. UI consumers dedupe at their own
        // terminal operator.
        return recordWakeProjectionDao.observeRecordWakeInvalidations().transform {
            emit(
                recordWakeProjectionDao.loadRecordProjection(babyId, start, end)
                    .map(ProjectedRecordEntity::toDomainRecord),
            )
        }
    }

    fun observeDayRecords(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId,
    ): Flow<List<Record>> = observeRecords(babyId, day, day.plusDays(1), zone)

    fun observeCarePlansInRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<CarePlan>> {
        require(startInclusive < endExclusive) {
            "startInclusive must be before endExclusive"
        }
        return carePlanDao.observeRange(babyId, startInclusive, endExclusive)
            .map { rows -> rows.map { it.toModel() } }
    }

    suspend fun dayRecords(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId,
    ): List<Record> {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return recordWakeProjectionDao.loadRecordProjection(babyId, start, end)
            .map(ProjectedRecordEntity::toDomainRecord)
    }

    suspend fun daySummary(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId,
        now: Long,
    ): DailySummary {
        val hidden = sourceRoleClientUuids()
        val records = dayRecords(babyId, day, zone).filter { it.clientUuid !in hidden }
        return CareAggregation.day(
            records = records,
            date = day,
            zone = zone,
            now = now,
        ).toDailySummary()
    }

    suspend fun getRecord(id: Long): Record? {
        val root = recordDao.get(id) ?: return null
        if (root.type != RecordType.SLEEP.key) return root.toModel()
        return recordWakeProjectionDao.loadRecordProjectionForRoots(listOf(root.clientUuid))
            .singleOrNull()
            ?.toDomainRecord()
    }

    suspend fun getCarePlan(id: Long): CarePlan? = carePlanDao.get(id)?.toModel()

    fun observeDayPendingPlans(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId,
    ): Flow<List<CarePlan>> {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return carePlanDao.observeDayPending(babyId, start, end).map { rows ->
            rows.map { it.toModel() }
        }
    }

    fun observeTodayPendingPlans(
        babyId: Long,
        zone: ZoneId,
        nowMillis: Long,
    ): Flow<List<CarePlan>> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return carePlanDao.observeTodayPending(babyId, start, end, nowMillis).map { rows ->
            rows.map { it.toModel() }
                .sortedWith(
                    compareBy<CarePlan> {
                        when (it.effectiveStatus(nowMillis)) {
                            CarePlanStatus.MISSED -> 0
                            else -> 1
                        }
                    }.thenBy { it.scheduledAt },
                )
        }.distinctUntilChanged()
    }

    suspend fun filterSurfaceRecords(records: List<Record>): List<Record> =
        fulfillmentSurface.filterSurfaceRecords(records)

    suspend fun isSurfaceRecord(clientUuid: String): Boolean =
        fulfillmentSurface.isSurfaceRecord(clientUuid)

    suspend fun getCarePlanByClientUuid(clientUuid: String): CarePlan? =
        carePlanDao.getByClientUuid(clientUuid)?.toModel()

    suspend fun weekSummary(
        babyId: Long,
        weekStart: LocalDate,
        zone: ZoneId,
        now: Long,
    ): WeekSummary {
        val start = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = weekStart.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val hidden = sourceRoleClientUuids()
        val records = recordWakeProjectionDao.loadRecordProjection(babyId, start, end)
            .map(ProjectedRecordEntity::toDomainRecord)
            .filter { it.clientUuid !in hidden }
        return CareAggregation.week(records, weekStart, zone, now)
    }

    suspend fun search(
        babyId: Long,
        query: String,
        hiddenSourceClientUuids: Set<String> = emptySet(),
    ): List<Record> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isEmpty()) return emptyList()
        val queryNeedsConvertedWeightCandidate = normalizedQuery.toDoubleOrNull()?.isFinite() == true
        val matchingTypeKeys = RecordType.entries
            .filter { type ->
                (
                    type == RecordType.SLEEP ||
                        type == RecordType.WEIGHT && queryNeedsConvertedWeightCandidate
                    ) ||
                    type.candidateSearchTerms().any { term ->
                        typeTermMatchesQuery(term, normalizedQuery)
                    }
            }
            .map { it.key }
            .ifEmpty { listOf(NO_MATCHING_RECORD_TYPE) }
        val hits = recordDao.searchCandidates(
            babyId = babyId,
            escapedPattern = normalizedQuery.payloadSearchNeedle().toSqlLikePattern(),
            matchingTypeKeys = matchingTypeKeys,
        ).asSequence()
            .map { it.toModel() }
            .filter { it.clientUuid !in hiddenSourceClientUuids }
            .toList()
        val sleepIds = hits.filter { it.type == RecordType.SLEEP }.map { it.clientUuid }
        if (sleepIds.isEmpty()) return hits.filter { it.matchesVisibleSearchText(normalizedQuery) }
        // Explicit-root projection refuses more than MAX_EXPLICIT_PROJECTION_ROOTS
        // ids. Search can match every sleep of a long history, so project in
        // capped batches and keep hit order.
        val projected = sleepIds
            .chunked(MAX_EXPLICIT_PROJECTION_ROOTS)
            .flatMap { chunk ->
                recordWakeProjectionDao.loadRecordProjectionForRoots(chunk)
            }
            .map(ProjectedRecordEntity::toDomainRecord)
            .associateBy { it.clientUuid }
        return hits.map { hit -> projected[hit.clientUuid] ?: hit }
            .filter { it.matchesVisibleSearchText(normalizedQuery) }
    }

    suspend fun recentCareSummary(
        babyId: Long,
        zone: ZoneId,
        hiddenSourceClientUuids: Set<String> = emptySet(),
        now: Long = RecordTime.currentTimeMillis(),
    ): WidgetSummaryDto {
        val baby = babyDao.get(babyId)?.toModel()
        // The date and stale-open cutoff must belong to the same clock snapshot.
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        // Bounded widget read (was a full-history projection scan). The window
        // overlap clause carries today's point facts plus every sleep whose
        // interval can still reach today — including ALL open sleeps, so
        // open-sleep peer arbitration sees the same peer set as the former
        // full scan. Aggregation's per-record skip guards make the superset
        // output-identical.
        val windowRecords = recordWakeProjectionDao.loadRecordProjection(
            babyId = babyId,
            startInclusive = dayStart,
            endExclusive = Long.MAX_VALUE,
        ).map(ProjectedRecordEntity::toDomainRecord)
            .filter { it.clientUuid !in hiddenSourceClientUuids }
        // Any in-window record with timestamp >= dayStart that is at-or-before
        // now is strictly newer than every pre-day record, so it is the global
        // latest without another read. Overlap-rider sleeps started before
        // dayStart can still be the in-window max without that guarantee — a
        // later pre-day point fact would outrank them — so that case still
        // consults the pre-day fallback and compares by timestamp.
        val inWindowLatest = windowRecords.asSequence()
            .filter { it.timestamp <= now }
            .maxByOrNull(Record::timestamp)
        var latest = inWindowLatest?.takeIf { it.timestamp >= dayStart }
        if (latest == null) {
            // Nothing today outranks the riders: resolve the newest pre-day
            // records. No open sleep exists outside the window (they always
            // ride it), so per-root projection matches the full-scan
            // projection exactly. Hidden-source filtering needs at most
            // |hidden| extra leading roots; beyond the projection cap the
            // pathological case keeps the exact full-scan path.
            val preDayLatest = if (hiddenSourceClientUuids.size + 1 > MAX_EXPLICIT_PROJECTION_ROOTS) {
                recordWakeProjectionDao.loadRecordProjection(babyId, 0L, Long.MAX_VALUE)
                    .map(ProjectedRecordEntity::toDomainRecord)
                    .filter { it.clientUuid !in hiddenSourceClientUuids }
                    .filter { it.timestamp <= now.coerceAtMost(dayStart - 1) }
                    .maxByOrNull(Record::timestamp)
            } else {
                recordWakeProjectionDao.loadLatestProjectedRecordsAtOrBefore(
                    babyId = babyId,
                    maxTimestampInclusive = now.coerceAtMost(dayStart - 1),
                    maxRootCount = hiddenSourceClientUuids.size + 1,
                ).map(ProjectedRecordEntity::toDomainRecord)
                    .firstOrNull { it.clientUuid !in hiddenSourceClientUuids }
            }
            latest = listOfNotNull(inWindowLatest, preDayLatest)
                .maxByOrNull(Record::timestamp)
        }
        val records = if (
            latest != null &&
            windowRecords.none { it.clientUuid == latest.clientUuid }
        ) {
            windowRecords + latest
        } else {
            windowRecords
        }
        return CareAggregation.widget(
            records = records,
            babyName = baby?.nickname ?: "乐记",
            date = day,
            zone = zone,
            now = now,
        )
    }

    fun observeMeasurements(babyId: Long, type: RecordType): Flow<List<Record>> =
        recordDao.observeByType(babyId, type.key).map { records ->
            records.map { it.toModel() }
        }.distinctUntilChanged()

    suspend fun recentMilkAmounts(
        babyId: Long,
        type: RecordType,
        limit: Int,
    ): List<Int> {
        require(type in setOf(RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS))
        require(limit >= 0)
        return selectRecentDistinctPaged(
            limit,
            page = { offset, pageSize ->
                recordDao.listByTypePage(babyId, type.key, pageSize, offset)
            },
            transform = { entity ->
                (entity.toModel().payload.payload as? MilkPayload)?.amountMl
                    ?.takeIf { amount -> amount in 1..999 }
            },
        )
    }

    suspend fun recentNotes(
        babyId: Long,
        type: RecordType,
        limit: Int,
    ): List<String> {
        require(limit >= 0)
        return selectRecentDistinctPaged(
            limit,
            page = { offset, pageSize ->
                recordDao.listRecentNotePage(babyId, type.key, pageSize, offset)
            },
            transform = { raw -> raw.trim().takeIf(String::isNotBlank) },
        )
    }
}

/**
 * Newest-first pages in, at most [limit] distinct transformed values out.
 * [transform] may drop a row entirely (blank note, non-milk payload); a page
 * whose rows all drop still continues so later values are not lost — the
 * break conditions read the raw page size, never the transformed size.
 * Worst case (pathologically duplicated values) degrades to the full scan.
 */
internal suspend fun <R, T> selectRecentDistinctPaged(
    limit: Int,
    page: suspend (offset: Int, pageSize: Int) -> List<R>,
    transform: (R) -> T?,
): List<T> {
    if (limit == 0) return emptyList()
    val selected = ArrayList<T>(limit)
    val seen = HashSet<T>(limit)
    var offset = 0
    val pageSize = limit.coerceAtLeast(1) * RECENT_NOTE_PAGE_MULTIPLIER
    while (selected.size < limit) {
        val batch = page(offset, pageSize)
        if (batch.isEmpty()) break
        for (raw in batch) {
            val value = transform(raw) ?: continue
            if (seen.add(value)) {
                selected += value
                if (selected.size == limit) return selected
            }
        }
        if (batch.size < pageSize) break
        offset += batch.size
    }
    return selected
}

internal const val RECENT_NOTE_PAGE_MULTIPLIER = 8

private fun ProjectedRecordEntity.toDomainRecord(): Record = sleepInterval?.let {
    root.toProjectedSleepRecord(it)
} ?: root.toModel()
