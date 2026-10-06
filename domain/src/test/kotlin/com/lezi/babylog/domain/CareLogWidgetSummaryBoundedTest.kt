package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.ProjectedRecordEntity
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.CareAggregation
import com.lezi.babylog.domain.carelog.toProjectedSleepRecord
import com.lezi.babylog.domain.toModel
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * recentCareSummary is a bounded read (today's window + newest-root fallback);
 * every scenario asserts byte equality against a full-history projection scan
 * fed through the same aggregation, so the bound can never change widget
 * output. Timestamps anchor to the real clock near today's boundaries.
 */
class CareLogWidgetSummaryBoundedTest {

    private val zone = ZoneOffset.UTC
    private val hour = 3_600_000L

    private fun projectedRecord(entity: ProjectedRecordEntity): Record =
        entity.sleepInterval?.let { entity.root.toProjectedSleepRecord(it) } ?: entity.root.toModel()

    private suspend fun fullScanReference(
        fakes: Fakes,
        babyId: Long,
        now: Long,
        hidden: Set<String> = emptySet(),
    ) = CareAggregation.widget(
        records = fakes.timelineWindow
            .loadRecordProjection(babyId, 0L, Long.MAX_VALUE)
            .map(::projectedRecord)
            .filter { it.clientUuid !in hidden },
        babyName = "豆豆",
        date = LocalDate.now(zone),
        zone = zone,
        now = now,
    )

    @Test
    fun boundedSummaryMatchesFullScanWithCrossMidnightSleepAndDeepHistory() = runTest {
        val now = System.currentTimeMillis()
        val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        assumeTrue(now - dayStart > 2 * hour)
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        // Deep history far outside the bounded window.
        care.addRecord(
            babyId, RecordType.FORMULA,
            timestamp = dayStart - 10L * 24 * hour,
            payloadJson = """{"amount_ml":30}""",
        )
        // Yesterday evening feed (23:00-equivalent) and a cross-midnight sleep.
        care.addRecord(
            babyId, RecordType.FORMULA,
            timestamp = dayStart - 2 * hour,
            payloadJson = """{"amount_ml":120}""",
        )
        care.addRecord(
            babyId, RecordType.SLEEP,
            timestamp = dayStart - hour,
            endTimestamp = now - 5 * 60_000L,
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
        )
        care.addRecord(
            babyId, RecordType.PEE,
            timestamp = dayStart + hour,
            payloadJson = """{"pee_amount":1}""",
        )
        care.addRecord(
            babyId, RecordType.FORMULA,
            timestamp = now - 10 * 60_000L,
            payloadJson = """{"amount_ml":60}""",
        )

        val actual = care.recentCareSummary(babyId, zone)

        assertThat(actual).isEqualTo(fullScanReference(fakes, babyId, now))
        // Cross-midnight sleep contributes only today's part of the interval.
        assertThat(actual.sleepMin).isEqualTo(((now - 5 * 60_000L - dayStart) / 60_000L).toInt())
        // Only today's feed counts; the newest fact is today's formula.
        assertThat(actual.feedMl).isEqualTo(60)
        assertThat(actual.pee).isEqualTo(1)
        assertThat(actual.lastLabel).contains("60ml")
    }

    @Test
    fun preDayPointFactNewerThanRiderSleepWinsLatestLabel() = runTest {
        val now = System.currentTimeMillis()
        val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        assumeTrue(now - dayStart > 2 * hour)
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        // Sleep 23:00 yesterday -> 02:00 today rides the bounded window as an
        // overlap sleep; the 23:50 feed is newer but pre-day, so only the
        // fallback comparison can surface it as the latest fact.
        care.addRecord(
            babyId, RecordType.SLEEP,
            timestamp = dayStart - hour,
            endTimestamp = dayStart + 2 * hour,
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
        )
        care.addRecord(
            babyId, RecordType.FORMULA,
            timestamp = dayStart - 10 * 60_000L,
            payloadJson = """{"amount_ml":70}""",
        )

        val actual = care.recentCareSummary(babyId, zone)

        assertThat(actual).isEqualTo(fullScanReference(fakes, babyId, now))
        assertThat(actual.lastLabel).contains("70ml")
        // The rider sleep still contributes today's two hours.
        assertThat(actual.sleepMin).isEqualTo(2 * 60)
    }

    @Test
    fun quietDayFallsBackToPreDayLatestRecord() = runTest {
        val now = System.currentTimeMillis()
        val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        care.addRecord(
            babyId, RecordType.SLEEP,
            timestamp = dayStart - 3L * 24 * hour,
            endTimestamp = dayStart - 3L * 24 * hour + 8 * hour,
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
        )
        care.addRecord(
            babyId, RecordType.FORMULA,
            timestamp = dayStart - 2L * 24 * hour,
            payloadJson = """{"amount_ml":90}""",
        )

        val actual = care.recentCareSummary(babyId, zone)

        assertThat(actual).isEqualTo(fullScanReference(fakes, babyId, now))
        assertThat(actual.sleepMin).isEqualTo(0)
        assertThat(actual.feedMl).isEqualTo(0)
        // The widget keeps showing the last fact even with nothing today.
        assertThat(actual.lastLabel).contains("90ml")
    }

    @Test
    fun hiddenLatestFallsThroughToNextNewestPreDayRecord() = runTest {
        val now = System.currentTimeMillis()
        val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        care.addRecord(
            babyId, RecordType.FORMULA,
            timestamp = dayStart - 5L * 24 * hour,
            payloadJson = """{"amount_ml":10}""",
            clientUuid = "11111111-1111-4111-8111-111111111151",
        )
        care.addRecord(
            babyId, RecordType.FORMULA,
            timestamp = dayStart - 4L * 24 * hour,
            payloadJson = """{"amount_ml":20}""",
            clientUuid = "22222222-2222-4222-8222-222222222251",
        )
        care.addRecord(
            babyId, RecordType.FORMULA,
            timestamp = dayStart - 3L * 24 * hour,
            payloadJson = """{"amount_ml":40}""",
            clientUuid = "33333333-3333-4333-8333-333333333351",
        )
        val hidden = setOf(
            "33333333-3333-4333-8333-333333333351",
            "22222222-2222-4222-8222-222222222251",
        )

        // The facade computes hidden sources from source relations; this test
        // targets the bounded read itself, so it drives the queries seam.
        val queries = com.lezi.babylog.domain.carelog.CareLogQueries(
            babyDao = fakes.babies,
            recordDao = fakes.records,
            carePlanDao = fakes.carePlans,
            fulfillmentCandidateDao = fakes.fulfillmentCandidates,
            recordWakeProjectionDao = fakes.timelineWindow,
        )
        val actual = queries.recentCareSummary(babyId, zone, hidden)

        assertThat(actual).isEqualTo(fullScanReference(fakes, babyId, now, hidden))
        assertThat(actual.lastLabel).contains("10ml")
    }

    @Test
    fun openSleepPeerArbitrationMatchesFullScan() = runTest {
        val now = System.currentTimeMillis()
        val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        assumeTrue(now - dayStart > 2 * hour)
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        // Two concurrent open sleeps (raw seeds: the mutation coordinator
        // refuses a second open sleep through the facade); the projection's
        // open-sleep arbitration must see both peers exactly like the full
        // scan even though the bounded window starts at today.
        fakes.records.upsert(
            com.lezi.babylog.core.database.RecordEntity(
                clientUuid = "44444444-4444-4444-8444-444444444452",
                babyId = babyId,
                type = "sleep",
                timestamp = dayStart - 30 * hour,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                schemaVersion = 2,
                updatedAt = dayStart - 30 * hour,
                syncDirty = false,
            ),
        )
        fakes.records.upsert(
            com.lezi.babylog.core.database.RecordEntity(
                clientUuid = "44444444-4444-4444-8444-444444444453",
                babyId = babyId,
                type = "sleep",
                timestamp = now - hour,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                schemaVersion = 2,
                updatedAt = now - hour,
                syncDirty = false,
            ),
        )
        care.addRecord(
            babyId, RecordType.FORMULA,
            timestamp = now - 30 * 60_000L,
            payloadJson = """{"amount_ml":50}""",
        )

        val actual = care.recentCareSummary(babyId, zone)

        assertThat(actual).isEqualTo(fullScanReference(fakes, babyId, now))
        assertThat(actual.feedMl).isEqualTo(50)
    }
}
