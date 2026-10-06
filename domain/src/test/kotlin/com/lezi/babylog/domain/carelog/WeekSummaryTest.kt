package com.lezi.babylog.domain.carelog
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Test

class WeekSummaryTest {
    private val zone = ZoneOffset.UTC

    @Test
    fun aggregateWeekBuckets() {
        val weekStart = LocalDate.of(2026, 7, 20)
        val base = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val records = listOf(
            rec(1, "formula", base + 8 * 3600_000L, """{"amount_ml":100}"""),
            rec(2, "formula", base + 26 * 3600_000L, """{"amount_ml":50}"""),
            rec(3, "pee", base + 9 * 3600_000L),
            rec(
                4,
                "sleep",
                base + 10 * 3600_000L,
                """{"is_nap":false,"anomaly_flag":false}""",
                end = base + 10 * 3600_000L + 60 * 60_000L,
            ),
            rec(5, "temperature", base + 11 * 3600_000L, """{"celsius":36.8}"""),
            rec(
                6,
                "nursing",
                base + 12 * 3600_000L,
                """{"left_min":10,"right_min":5,"order":"LR","amount_ml":20,"record_mode":"end"}""",
            ),
            rec(7, "both_diaper", base + 30 * 3600_000L),
            rec(8, "formula", base + 8 * 3600_000L, """{"amount_ml":40}""", deleted = base),
        )
        val w = CareAggregation.week(records, weekStart, zone)
        assertThat(w.days).hasSize(7)
        assertThat(w.days[0].feedMl).isEqualTo(120)
        assertThat(w.days[0].nursingMin).isEqualTo(15)
        assertThat(w.days[0].pee).isEqualTo(1)
        assertThat(w.days[0].sleepMin).isEqualTo(60)
        assertThat(w.days[0].temps).containsExactly(36.8)
        assertThat(w.days[1].feedMl).isEqualTo(50)
        assertThat(w.days[1].pee).isEqualTo(1)
        assertThat(w.days[1].poop).isEqualTo(1)
        assertThat(w.totalFeedMl).isEqualTo(170)
    }

    @Test
    fun crossMidnightAndOpenSleepAreSplitAcrossDayWindows() {
        val weekStart = LocalDate.of(2026, 7, 20)
        val base = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val records = listOf(
            rec(
                id = 1,
                type = "sleep",
                ts = base - 30 * 60_000L,
                payload = """{"is_nap":false,"anomaly_flag":false}""",
                end = base + 30 * 60_000L,
            ),
            rec(
                id = 2,
                type = "sleep",
                ts = base + 23 * 60 * 60_000L + 30 * 60_000L,
                payload = """{"is_nap":false,"anomaly_flag":false}""",
                end = base + 24 * 60 * 60_000L + 45 * 60_000L,
            ),
            rec(
                id = 3,
                type = "sleep",
                ts = base + 26 * 60 * 60_000L,
                payload = """{"is_nap":false,"anomaly_flag":false}""",
            ),
        )

        val now = base + 27 * 60 * 60_000L
        val summary = CareAggregation.week(
            records = records,
            weekStart = weekStart,
            zone = zone,
            now = now,
        )
        val range = CareAggregation.range(
            records = records,
            startDate = weekStart,
            dayCount = 7,
            zone = zone,
            now = now,
        )

        assertThat(summary.days[0].sleepMin).isEqualTo(30 + 30)
        assertThat(summary.days[1].sleepMin).isEqualTo(45 + 60)
        // Minutes clip per day; physical segments count only on the start day
        // (record1 started before weekStart → no segment on day0; record2 day0;
        // record3 day1).
        assertThat(range.days[0].sleepSegments).isEqualTo(1)
        assertThat(range.days[1].sleepSegments).isEqualTo(1)
        assertThat(range.sleepSegments).isEqualTo(2)
    }

    private fun rec(
        id: Long,
        type: String,
        ts: Long,
        payload: String = "{}",
        end: Long? = null,
        deleted: Long? = null,
    ) = Record(
        id = id,
        clientUuid = "u$id",
        babyId = 1,
        type = RecordType.fromKey(type)!!,
        timestamp = ts,
        endTimestamp = end,
        payloadJson = payload,
        updatedAt = ts,
        deletedAt = deleted,
    )
}
