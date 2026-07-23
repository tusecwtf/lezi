package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.RecordEntity
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Test

class WeekSummaryTest {
    private val zone = ZoneOffset.UTC

    @Test
    fun weekStartMondayVsSunday() {
        val sunday = LocalDate.of(2026, 7, 19)
        val mon = weekStartFor(sunday, weekStartSetting = 1)
        val sun = weekStartFor(sunday, weekStartSetting = 7)
        assertThat(mon).isEqualTo(LocalDate.of(2026, 7, 13))
        assertThat(sun).isEqualTo(LocalDate.of(2026, 7, 19))
    }

    @Test
    fun aggregateWeekBuckets() {
        val weekStart = LocalDate.of(2026, 7, 20)
        val base = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val records = listOf(
            rec(1, "formula", base + 8 * 3600_000L, """{"amount_ml":100}"""),
            rec(2, "formula", base + 26 * 3600_000L, """{"amount_ml":50}"""),
            rec(3, "pee", base + 9 * 3600_000L),
            rec(4, "sleep", base + 10 * 3600_000L, end = base + 10 * 3600_000L + 60 * 60_000L),
            rec(5, "temperature", base + 11 * 3600_000L, """{"celsius":36.8}"""),
            rec(6, "nursing", base + 12 * 3600_000L, """{"left_min":10,"right_min":5,"amount_ml":20}"""),
            rec(7, "both_diaper", base + 30 * 3600_000L),
            rec(8, "formula", base + 8 * 3600_000L, """{"amount_ml":40}""", deleted = base),
        )
        val w = aggregateWeek(records, weekStart, zone)
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

    private fun rec(
        id: Long,
        type: String,
        ts: Long,
        payload: String = "{}",
        end: Long? = null,
        deleted: Long? = null,
    ) = RecordEntity(
        id = id,
        clientUuid = "u$id",
        babyId = 1,
        type = type,
        timestamp = ts,
        endTimestamp = end,
        createdByUserId = 1,
        payloadJson = payload,
        updatedAt = ts,
        deletedAt = deleted,
    )
}
