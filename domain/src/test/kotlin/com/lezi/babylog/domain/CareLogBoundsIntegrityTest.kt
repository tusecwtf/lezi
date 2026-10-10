package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.IntBound
import com.lezi.babylog.domain.carelog.LongBound
import com.lezi.babylog.domain.carelog.CareAggregation
import java.time.ZoneId
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CareLogBoundsIntegrityTest {
    private val day = LocalDate.of(2024, 6, 1)
    private val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val hour = 3_600_000L

    @Test
    fun thresholdDstAndCalendarBoundariesAgreeAcrossAggregationReaders() = runTest {
        data class Case(val day: LocalDate, val zone: ZoneId, val hours: Long)
        val cases = listOf(
            Case(LocalDate.of(2024, 6, 30), ZoneOffset.UTC, 24), // Sunday and month end
            Case(LocalDate.of(2024, 3, 10), ZoneId.of("America/New_York"), 23),
            Case(LocalDate.of(2024, 11, 3), ZoneId.of("America/New_York"), 25),
        )
        val care = Fakes().careLog()
        for (case in cases) {
            val midnight = case.day.atStartOfDay(case.zone).toInstant().toEpochMilli()
            val nextMidnight = case.day.plusDays(1).atStartOfDay(case.zone).toInstant().toEpochMilli()
            assertThat(nextMidnight - midnight).isEqualTo(case.hours * hour)
            // Closed midnight interval, open exactly 24h, then the same open root 1ms stale.
            for (mode in listOf("closed", "exact24h", "stale")) {
                val from = if (mode == "closed") nextMidnight - hour else midnight
                val end = if (mode == "closed") nextMidnight else null
                val now = if (mode == "closed") nextMidnight + 48 * hour
                    else midnight + 24 * hour + if (mode == "stale") 1 else 0
                val rows = listOf(Record(
                    clientUuid = "boundary-sleep", babyId = 1, type = RecordType.SLEEP,
                    timestamp = from, endTimestamp = end, updatedAt = from,
                    payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                ))
                val expected = when (mode) {
                    "closed" -> 60L
                    "stale" -> 0L
                    else -> minOf(case.hours, 24) * 60
                }
                assertThat(CareAggregation.day(rows, case.day, case.zone, now).bucket.sleepMin)
                    .isEqualTo(expected)
                assertThat(care.daySummaryBounds(rows, case.day, case.zone, now).sleepMinutes)
                    .isEqualTo(LongBound(expected, expected))
                // Shared aggregation agrees for an explicitly selected historical day.
                assertThat(CareAggregation.widget(rows, "测试宝宝", case.day, case.zone, now).sleepMin)
                    .isEqualTo(expected)
                for (days in listOf(7, case.day.lengthOfMonth())) {
                    val rangeStart = if (days == 7) case.day.minusDays(6) else case.day.withDayOfMonth(1)
                    val dayOffset = java.time.temporal.ChronoUnit.DAYS.between(rangeStart, case.day).toInt()
                    val range = CareAggregation.range(rows, rangeStart, days, case.zone, now)
                    assertThat(range.days[dayOffset].bucket.sleepMin).isEqualTo(expected)
                    val bounds = care.rangeSummaryBounds(rows, rangeStart, days, case.zone, now)
                    assertThat(bounds.days[dayOffset].sleepMinutes).isEqualTo(LongBound(expected, expected))
                    assertThat(bounds.sleepMinutes).isEqualTo(LongBound(range.sleepMinutes, range.sleepMinutes))
                }
                assertThat(CareAggregation.week(rows, case.day.minusDays(6), case.zone, now).totalSleepMin)
                    .isEqualTo(expected)
                // Real CareLog reader orchestration, using existing fake DAO projections.
                // This is not Room or Android widget delivery evidence.
                val fakes = Fakes()
                val reader = fakes.careLog()
                val baby = reader.createBaby(CreateBabyInput(nickname = "测试宝宝", birthdayEpochDay = 1))
                val id = reader.sleepDown(baby, from, nowMillis = now)
                if (end != null) reader.recordWakeObservation(
                    babyId = baby, at = end, sleepRecordId = id,
                    clientUuid = "boundary-wake", nowMillis = now,
                )
                assertThat(reader.daySummary(baby, case.day, case.zone, now).sleepMinutes).isEqualTo(expected)
                assertThat(reader.weekSummary(baby, case.day.minusDays(6), case.zone, now).totalSleepMin)
                    .isEqualTo(expected)
                val monthStart = case.day.withDayOfMonth(1)
                val snapshot = reader.observeRecords(baby, monthStart, monthStart.plusMonths(1), case.zone).first()
                val month = reader.rangeSummaryBounds(snapshot, monthStart, case.day.lengthOfMonth(), case.zone, now)
                val monthMinutes = CareAggregation.range(
                    rows, monthStart, case.day.lengthOfMonth(), case.zone, now,
                ).sleepMinutes
                assertThat(month.sleepMinutes).isEqualTo(LongBound(monthMinutes, monthMinutes))
                val widgetDate = java.time.Instant.ofEpochMilli(now).atZone(case.zone).toLocalDate()
                val widgetStart = widgetDate.atStartOfDay(case.zone).toInstant().toEpochMilli()
                val widgetExpected = if (mode == "stale") 0L
                    else (minOf(end ?: now, now) - maxOf(from, widgetStart)).coerceAtLeast(0L) / 60_000L
                assertThat(reader.recentCareSummaryAt(baby, case.zone, now).sleepMin).isEqualTo(widgetExpected)
                assertThat(reader.daySummary(baby, widgetDate, case.zone, now).sleepMinutes).isEqualTo(widgetExpected)
            }
        }
    }

    @Test
    fun widgetUsesTheSuppliedInstantForBothDateAndAggregationAcrossMidnight() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val baby = care.createBaby(CreateBabyInput(nickname = "测试宝宝", birthdayEpochDay = 1))
        val midnight = start + 24 * hour
        care.sleepDown(baby, midnight - hour, nowMillis = midnight)
        for ((now, expected) in listOf(midnight - 1 to 59L, midnight to 0L, midnight + 60_000 to 1L)) {
            assertThat(care.recentCareSummaryAt(baby, ZoneOffset.UTC, now).sleepMin).isEqualTo(expected)
        }
    }

    @Test
    fun nextDayDuplicateRemainsAZeroContributionChoiceForSelectedDay() = runTest {
        val care = Fakes().careLog()
        val rows = listOf(
            Record(clientUuid = "before", babyId = 1, type = RecordType.FORMULA,
                timestamp = start + 24 * hour - 600_000, updatedAt = start,
                payloadJson = """{"amount_ml":100}""", createdByMembershipId = "a"),
            Record(clientUuid = "after", babyId = 1, type = RecordType.FORMULA,
                timestamp = start + 24 * hour + 600_000, updatedAt = start,
                payloadJson = """{"amount_ml":120}""", createdByMembershipId = "b"),
        )
        val result = care.suspectedDuplicateProjection(rows, day, 1, ZoneOffset.UTC, start + 48 * hour)
        assertThat(result.bounds.formulaMl).isEqualTo(IntBound(0, 100))
    }

    @Test
    fun historicalBoundsUseRealClockForStaleOpenSleepAndExactMidnightForClosedSleep() = runTest {
        val care = Fakes().careLog()
        fun sleep(uuid: String, end: Long?) = Record(
            clientUuid = uuid, babyId = 1, type = RecordType.SLEEP,
            timestamp = start + 23 * hour, endTimestamp = end, updatedAt = start,
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
        )
        val stale = care.suspectedDuplicateProjection(
            listOf(sleep("open", null)), day, 1, ZoneOffset.UTC, start + 72 * hour,
        )
        assertThat(stale.bounds.sleepMinutes).isEqualTo(LongBound(0, 0))
        val closed = care.suspectedDuplicateProjection(
            listOf(sleep("closed", start + 24 * hour)), day, 1, ZoneOffset.UTC, start + 72 * hour,
        )
        assertThat(closed.bounds.sleepMinutes).isEqualTo(LongBound(60, 60))
    }
}
