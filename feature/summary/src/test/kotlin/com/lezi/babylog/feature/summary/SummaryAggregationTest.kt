package com.lezi.babylog.feature.summary

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Test

class SummaryAggregationTest {
    private val zone = ZoneOffset.UTC
    private val anchor = LocalDate.of(2026, 7, 23)

    @Test
    fun ranges_areRollingWindowsAnchoredAtSelectedDate() {
        assertThat(SummaryRange.Day.startDate(anchor)).isEqualTo(LocalDate.of(2026, 7, 23))
        assertThat(SummaryRange.Week.startDate(anchor)).isEqualTo(LocalDate.of(2026, 7, 17))
        assertThat(SummaryRange.Month.startDate(anchor)).isEqualTo(LocalDate.of(2026, 6, 24))
    }

    @Test
    fun aggregation_usesSelectedRangeAndKeepsSevenDayDetailWindow() {
        val records = listOf(
            record(1, RecordType.FORMULA, LocalDate.of(2026, 6, 24), """{"amount_ml":30}"""),
            record(2, RecordType.FORMULA, LocalDate.of(2026, 7, 16), """{"amount_ml":50}"""),
            record(
                3,
                RecordType.NURSING,
                LocalDate.of(2026, 7, 17),
                """{"left_min":10,"right_min":5,"amount_ml":20}""",
            ),
            record(4, RecordType.TEMPERATURE, LocalDate.of(2026, 7, 22), """{"value":37.2}"""),
            record(5, RecordType.FORMULA, anchor, """{"amount_ml":100}"""),
            record(6, RecordType.PUMPED_FEED, anchor, """{"amount_ml":50}"""),
            record(7, RecordType.SLEEP, anchor, endOffsetMinutes = 90),
            record(8, RecordType.PEE, anchor),
            record(9, RecordType.BOTH_DIAPER, anchor),
            record(10, RecordType.TEMPERATURE, anchor, """{"celsius":36.8}"""),
            record(11, RecordType.FORMULA, anchor.plusDays(1), """{"amount_ml":999}"""),
        )

        val week = buildSummaryUi(
            records = records,
            range = SummaryRange.Week,
            anchorDate = anchor,
            showAvgSleep = false,
            babyName = "年年",
            zone = zone,
        )
        assertThat(week.totals.feedMl).isEqualTo(170)
        assertThat(week.totals.feedCount).isEqualTo(3)
        assertThat(week.totals.nursingMin).isEqualTo(15)
        assertThat(week.totals.sleepMin).isEqualTo(90)
        assertThat(week.totals.sleepSegments).isEqualTo(1)
        assertThat(week.totals.pee).isEqualTo(2)
        assertThat(week.totals.poop).isEqualTo(1)
        assertThat(week.totals.tempAvg!!).isWithin(0.001).of(37.0)
        assertThat(week.totals.tempDays).isEqualTo(2)
        assertThat(week.totals.dayValuesFeed).hasSize(7)
        assertThat(week.week!!.days.map { it.date })
            .containsExactlyElementsIn(
                (0L..6L).map { LocalDate.of(2026, 7, 17).plusDays(it) },
            )
            .inOrder()

        val day = buildSummaryUi(
            records = records,
            range = SummaryRange.Day,
            anchorDate = anchor,
            showAvgSleep = false,
            babyName = "年年",
            zone = zone,
        )
        assertThat(day.totals.feedMl).isEqualTo(150)
        assertThat(day.totals.dayValuesFeed).containsExactly(150f)
        assertThat(day.totals.feedTimeBuckets).containsExactly(0f, 2f, 0f, 0f).inOrder()
        assertThat(day.week!!.days.first().date).isEqualTo(LocalDate.of(2026, 7, 17))
        assertThat(day.week!!.days.first().nursingMin).isEqualTo(15)

        val month = buildSummaryUi(
            records = records,
            range = SummaryRange.Month,
            anchorDate = anchor,
            showAvgSleep = false,
            babyName = "年年",
            zone = zone,
        )
        assertThat(month.totals.feedMl).isEqualTo(250)
        assertThat(month.totals.dayValuesFeed).hasSize(30)
        assertThat(month.week!!.days.first().date).isEqualTo(LocalDate.of(2026, 7, 17))
    }

    @Test
    fun nursingWithoutVolume_isStillNonEmpty() {
        val ui = buildSummaryUi(
            records = listOf(
                record(
                    1,
                    RecordType.NURSING,
                    anchor,
                    """{"left_min":7,"right_min":8}""",
                ),
            ),
            range = SummaryRange.Day,
            anchorDate = anchor,
            showAvgSleep = true,
            babyName = "年年",
            zone = zone,
        )

        assertThat(ui.empty).isFalse()
        assertThat(ui.totals.feedCount).isEqualTo(1)
        assertThat(ui.totals.nursingMin).isEqualTo(15)
        assertThat(ui.showAvgSleep).isTrue()
    }

    private fun record(
        id: Long,
        type: RecordType,
        date: LocalDate,
        payload: String = "{}",
        endOffsetMinutes: Long? = null,
    ): Record {
        val timestamp = date.atTime(10, 0).toInstant(zone).toEpochMilli()
        return Record(
            id = id,
            clientUuid = "record-$id",
            babyId = 1,
            type = type,
            timestamp = timestamp,
            endTimestamp = endOffsetMinutes?.let { timestamp + it * 60_000L },
            createdByUserId = 1,
            payloadJson = payload,
            updatedAt = timestamp,
        )
    }
}
