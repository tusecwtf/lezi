package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import org.junit.Test

class DailySummaryTest {
    @Test
    fun aggregatesFeedSleepDiaper() {
        val base = 1_700_000_000_000L
        val records = listOf(
            rec(1, "formula", base, """{"amount_ml":120}"""),
            rec(2, "pee", base + 1),
            rec(3, "poop", base + 2, """{"stool_amount":3}"""),
            rec(4, "sleep", base + 3, end = base + 3 + 90 * 60_000L),
            rec(5, "nursing", base + 4, """{"left_min":10,"right_min":5,"amount_ml":30}"""),
            rec(6, "both_diaper", base + 5),
            rec(7, "pumped_feed", base + 6, """{"amount_ml":50}"""),
            rec(8, "pump_express", base + 7, """{"amount_ml":80}"""),
        )
        val s = aggregateDaily(records)
        assertThat(s.formulaMl).isEqualTo(120)
        assertThat(s.pumpedFeedMl).isEqualTo(50)
        assertThat(s.feedMl).isEqualTo(120 + 50 + 30)
        assertThat(s.peeCount).isEqualTo(2)
        assertThat(s.poopCount).isEqualTo(2)
        assertThat(s.sleepMinutes).isEqualTo(90)
        assertThat(s.nursingMinutes).isEqualTo(15)
    }

    @Test
    fun softDeletedIgnored() {
        val base = 1_700_000_000_000L
        val records = listOf(
            rec(1, "formula", base, """{"amount_ml":100}""", deleted = base),
            rec(2, "formula", base + 1, """{"amount_ml":40}"""),
        )
        assertThat(aggregateDaily(records).feedMl).isEqualTo(40)
    }

    @Test
    fun clipsCrossMidnightSleepAndCountsOpenSleepUntilNow() {
        val dayStart = 1_700_006_400_000L
        val dayEnd = dayStart + 24 * 60 * 60_000L
        val records = listOf(
            rec(
                id = 1,
                type = "sleep",
                ts = dayStart - 30 * 60_000L,
                end = dayStart + 45 * 60_000L,
            ),
            rec(
                id = 2,
                type = "sleep",
                ts = dayStart + 2 * 60 * 60_000L,
            ),
        )

        val summary = aggregateDaily(
            records = records,
            windowStartInclusive = dayStart,
            windowEndExclusive = dayEnd,
            now = dayStart + 3 * 60 * 60_000L,
        )

        assertThat(summary.sleepMinutes).isEqualTo(45 + 60)
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
        createdByUserId = 1,
        payloadJson = payload,
        updatedAt = ts,
        deletedAt = deleted,
    )
}
