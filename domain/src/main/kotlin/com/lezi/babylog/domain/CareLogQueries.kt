package com.lezi.babylog.domain

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.MilkPayload
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
) {
    private val fulfillmentSurface = FulfillmentSurface(fulfillmentCandidateDao)

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
        return recordDao.observeRange(babyId, start, end).map { rows ->
            rows.map { it.toModel() }
        }
    }

    fun observeDayRecords(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId,
    ): Flow<List<Record>> = observeRecords(babyId, day, day.plusDays(1), zone)

    fun observeOpenSleep(babyId: Long): Flow<Record?> =
        recordDao.observeOpenSleep(babyId).map { it?.toModel() }

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
        return recordDao.listDay(babyId, start, end).map { it.toModel() }
    }

    suspend fun daySummary(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId,
        now: Long,
    ): DailySummary = CareAggregation.day(
        records = dayRecords(babyId, day, zone),
        date = day,
        zone = zone,
        now = now,
    ).toDailySummary()

    suspend fun getRecord(id: Long): Record? = recordDao.get(id)?.toModel()

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
        }
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
        val records = recordDao.listRange(babyId, start, end).map { it.toModel() }
        return CareAggregation.week(records, weekStart, zone, now)
    }

    suspend fun search(babyId: Long, query: String): List<Record> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isEmpty()) return emptyList()
        val queryNeedsConvertedWeightCandidate = normalizedQuery.toDoubleOrNull()?.isFinite() == true
        val matchingTypeKeys = RecordType.entries
            .filter { type ->
                (
                    type == RecordType.WEIGHT &&
                        queryNeedsConvertedWeightCandidate
                    ) ||
                    type.candidateSearchTerms().any { term ->
                        typeTermMatchesQuery(term, normalizedQuery)
                    }
            }
            .map { it.key }
            .ifEmpty { listOf(NO_MATCHING_RECORD_TYPE) }
        return recordDao.searchCandidates(
            babyId = babyId,
            escapedPattern = normalizedQuery.payloadSearchNeedle().toSqlLikePattern(),
            matchingTypeKeys = matchingTypeKeys,
        ).asSequence()
            .map { it.toModel() }
            .filter { it.matchesVisibleSearchText(normalizedQuery) }
            .toList()
    }

    suspend fun recentCareSummary(
        babyId: Long,
        zone: ZoneId,
    ): WidgetSummaryDto {
        val baby = babyDao.get(babyId)?.toModel()
        val day = LocalDate.now(zone)
        // Daily totals are windowed by CareAggregation; "last" intentionally spans prior days.
        val records = recordDao.listForBaby(babyId).map { it.toModel() }
        return CareAggregation.widget(
            records = records,
            babyName = baby?.nickname ?: "乐记",
            date = day,
            zone = zone,
        )
    }

    fun observeMeasurements(babyId: Long, type: RecordType): Flow<List<Record>> =
        recordDao.observeRange(
            babyId = babyId,
            startInclusive = Long.MIN_VALUE,
            endExclusive = Long.MAX_VALUE,
        ).map { records ->
            records.asSequence()
                .filter { it.type == type.key }
                .map { it.toModel() }
                .toList()
        }

    suspend fun recentMilkAmounts(
        babyId: Long,
        type: RecordType,
        limit: Int,
    ): List<Int> {
        require(type in setOf(RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS))
        return recordDao.listByType(babyId, type.key)
            .asReversed()
            .asSequence()
            .mapNotNull { (it.toModel().payload.payload as? MilkPayload)?.amountMl }
            .filter { it in 1..999 }
            .distinct()
            .take(limit)
            .toList()
    }

    suspend fun recentNotes(
        babyId: Long,
        type: RecordType,
        limit: Int,
    ): List<String> = recordDao.listByType(babyId, type.key)
        .asReversed()
        .asSequence()
        .mapNotNull { it.note?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
        .take(limit)
        .toList()
}
