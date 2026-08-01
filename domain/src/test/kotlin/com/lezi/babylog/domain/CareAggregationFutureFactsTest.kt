package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Public-seam contract: aggregation clock excludes point facts that have not
 * yet occurred. Boundary is inclusive at [now] (`timestamp <= now` counts).
 */
class CareAggregationFutureFactsTest {
    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 6, 15)
    private val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    /** Mid-day aggregation clock. */
    private val now = dayStart + 12 * 60 * 60_000L

    @Test
    fun day_excludesPointFactsAfterNow_andIncludesAtBoundary() {
        val past = rec(1, RecordType.FORMULA, now - 60_000L, """{"amount_ml":100}""")
        val atNow = rec(2, RecordType.FORMULA, now, """{"amount_ml":40}""")
        val future = rec(3, RecordType.FORMULA, now + 1L, """{"amount_ml":999}""")
        val peeFuture = rec(4, RecordType.PEE, now + 5 * 60_000L)
        val tempFuture = rec(5, RecordType.TEMPERATURE, now + 1L, """{"celsius":39.0}""")
        val tempPast = rec(6, RecordType.TEMPERATURE, now - 1L, """{"celsius":36.5}""")
        val nursingFuture = rec(
            7,
            RecordType.NURSING,
            now + 30_000L,
            """{"left_min":10,"right_min":5,"order":"LR","amount_ml":30,"record_mode":"end"}""",
        )
        val pumpedFuture = rec(8, RecordType.PUMPED_FEED, now + 2L, """{"amount_ml":80}""")
        val bothFuture = rec(9, RecordType.BOTH_DIAPER, now + 3L)

        val careDay = CareAggregation.day(
            records = listOf(
                past,
                atNow,
                future,
                peeFuture,
                tempFuture,
                tempPast,
                nursingFuture,
                pumpedFuture,
                bothFuture,
            ),
            date = day,
            zone = zone,
            now = now,
        )

        // Past 100 + boundary 40; all future point facts excluded.
        assertThat(careDay.bucket.feedMl).isEqualTo(140)
        assertThat(careDay.feedCount).isEqualTo(2)
        assertThat(careDay.bucket.pee).isEqualTo(0)
        assertThat(careDay.bucket.poop).isEqualTo(0)
        assertThat(careDay.bucket.temps).containsExactly(36.5)
        assertThat(careDay.bucket.nursingMin).isEqualTo(0)
        // past (11:59) → index 1 (06–12); at-now noon hour=12 → index 2 (12–18).
        assertThat(careDay.feedTimeBuckets.sum()).isEqualTo(2f)
        assertThat(careDay.feedTimeBuckets[1]).isEqualTo(1f)
        assertThat(careDay.feedTimeBuckets[2]).isEqualTo(1f)
    }

    @Test
    fun day_sleepClipsToNow_andFutureStartProducesNoSegment() {
        val records = sleepClipFixture()

        val careDay = CareAggregation.day(
            records = records,
            date = day,
            zone = zone,
            now = now,
        )

        // Closed: 30 min until now (not 45). Open: 20 min until now. Future start: 0.
        assertThat(careDay.bucket.sleepMin).isEqualTo(30 + 20)
        // Two physical starts that fall at/before now (future start excluded by clip).
        assertThat(careDay.sleepSegments).isEqualTo(2)
    }

    @Test
    fun window_sleepClipsToNow_matchesDayUnderSameClock() = runTest {
        val records = sleepClipFixture()

        val careDay = CareAggregation.day(records, day, zone, now)
        val windowDay = CareAggregation.window(records, day, 1, zone, now).days[0]

        // Public seam: window() uses the same sleepIntervalEnd clock as day().
        assertThat(windowDay.bucket.sleepMin).isEqualTo(careDay.bucket.sleepMin)
        assertThat(windowDay.sleepSegments).isEqualTo(careDay.sleepSegments)
        assertThat(windowDay.bucket.sleepMin).isEqualTo(30 + 20)
        assertThat(windowDay.sleepSegments).isEqualTo(2)
    }

    @Test
    fun rangeWeekWindowAndWidgetShareExclusionSemantics() = runTest {
        val fiveMin = RecordTime.FULFILLMENT_ACTUAL_TIME_SKEW_MILLIS
        val past = rec(1, RecordType.FORMULA, now - 60_000L, """{"amount_ml":50}""")
        val fulfillSkew = rec(2, RecordType.FORMULA, now + fiveMin, """{"amount_ml":120}""")
        val atNow = rec(3, RecordType.PEE, now)
        val records = listOf(past, fulfillSkew, atNow)

        val careDay = CareAggregation.day(records, day, zone, now)
        val range = CareAggregation.range(records, day, 1, zone, now)
        val week = CareAggregation.week(records, day, zone, now)
        val window = CareAggregation.window(records, day, 1, zone, now)
        val widget = CareAggregation.widget(
            records = records,
            babyName = "年年",
            date = day,
            zone = zone,
            now = now,
        )

        assertThat(careDay.bucket.feedMl).isEqualTo(50)
        assertThat(careDay.bucket.pee).isEqualTo(1)
        assertThat(range.feedMl).isEqualTo(50)
        assertThat(range.peeCount).isEqualTo(1)
        assertThat(week.days[0].feedMl).isEqualTo(50)
        assertThat(week.days[0].pee).isEqualTo(1)
        assertThat(window.days[0].bucket.feedMl).isEqualTo(50)
        assertThat(window.days[0].bucket.pee).isEqualTo(1)
        assertThat(widget.feedMl).isEqualTo(50)
        assertThat(widget.pee).isEqualTo(1)

        // After the skew instant arrives, the same DB row appears without mutation.
        val later = now + fiveMin
        assertThat(CareAggregation.day(records, day, zone, later).bucket.feedMl).isEqualTo(170)
        assertThat(
            CareAggregation.window(records, day, 1, zone, later).days[0].bucket.feedMl,
        ).isEqualTo(170)
        assertThat(
            CareAggregation.widget(records, "年年", day, zone, later).feedMl,
        ).isEqualTo(170)
    }

    @Test
    fun widgetLatestLabelIncludesBoundaryAndExcludesFuture() {
        val past = rec(1, RecordType.FORMULA, now - 60_000L, """{"amount_ml":50}""")
        val future = rec(2, RecordType.FORMULA, now + 1L, """{"amount_ml":999}""")
        val atNow = rec(3, RecordType.PEE, now)

        val withFuture = CareAggregation.widget(
            records = listOf(past, future, atNow),
            babyName = "年年",
            date = day,
            zone = zone,
            now = now,
        )
        // Inclusive boundary: at-now pee is the latest eligible fact.
        assertThat(withFuture.lastLabel).contains("尿")
        assertThat(withFuture.lastLabel).doesNotContain("999")

        val onlyFuture = CareAggregation.widget(
            records = listOf(future),
            babyName = "年年",
            date = day,
            zone = zone,
            now = now,
        )
        assertThat(onlyFuture.lastLabel).isNull()
    }

    @Test
    fun historicalAndFutureCalendarWindowsDoNotRegress() {
        val historical = day.minusDays(10)
        val histTs = historical.atTime(10, 0).toInstant(zone).toEpochMilli()
        val histRec = rec(1, RecordType.FORMULA, histTs, """{"amount_ml":80}""")
        // Viewing a fully past day with today's now: still counts.
        assertThat(
            CareAggregation.day(listOf(histRec), historical, zone, now).bucket.feedMl,
        ).isEqualTo(80)

        val futureDay = day.plusDays(3)
        val futureTs = futureDay.atTime(10, 0).toInstant(zone).toEpochMilli()
        val futureRec = rec(2, RecordType.FORMULA, futureTs, """{"amount_ml":200}""")
        // Future calendar day under current now: all point facts still unoccurred.
        assertThat(
            CareAggregation.day(listOf(futureRec), futureDay, zone, now).bucket.feedMl,
        ).isEqualTo(0)
    }

    @Test
    fun dstSpringForwardDayBoundaryStillUsesLocalMidnight() {
        // America/New_York 2026-03-08 springs forward (23h local day).
        val ny = ZoneId.of("America/New_York")
        val dstDay = LocalDate.of(2026, 3, 8)
        val local10 = dstDay.atTime(10, 0).atZone(ny).toInstant().toEpochMilli()
        val localNow = dstDay.atTime(15, 0).atZone(ny).toInstant().toEpochMilli()
        val afterNow = dstDay.atTime(16, 0).atZone(ny).toInstant().toEpochMilli()
        val records = listOf(
            rec(1, RecordType.FORMULA, local10, """{"amount_ml":60}"""),
            rec(2, RecordType.FORMULA, afterNow, """{"amount_ml":90}"""),
        )
        val careDay = CareAggregation.day(records, dstDay, ny, localNow)
        assertThat(careDay.bucket.feedMl).isEqualTo(60)
        // Next local day is empty under the same clock.
        assertThat(
            CareAggregation.day(records, dstDay.plusDays(1), ny, localNow).bucket.feedMl,
        ).isEqualTo(0)
    }

    /** Closed end past now, open sleep, and future start — shared day/window fixture. */
    private fun sleepClipFixture(): List<Record> = listOf(
        rec(
            id = 1,
            type = RecordType.SLEEP,
            ts = now - 30 * 60_000L,
            payload = """{"is_nap":false,"anomaly_flag":false}""",
            end = now + 15 * 60_000L,
        ),
        rec(
            id = 2,
            type = RecordType.SLEEP,
            ts = now + 10 * 60_000L,
            payload = """{"is_nap":false,"anomaly_flag":false}""",
            end = now + 40 * 60_000L,
        ),
        rec(
            id = 3,
            type = RecordType.SLEEP,
            ts = now - 20 * 60_000L,
            payload = """{"is_nap":false,"anomaly_flag":false}""",
        ),
    )

    private fun rec(
        id: Long,
        type: RecordType,
        ts: Long,
        payload: String = "{}",
        end: Long? = null,
    ) = Record(
        id = id,
        clientUuid = "future-fact-$id",
        babyId = 1,
        type = type,
        timestamp = ts,
        endTimestamp = end,
        payloadJson = payload,
        updatedAt = ts,
    )
}
