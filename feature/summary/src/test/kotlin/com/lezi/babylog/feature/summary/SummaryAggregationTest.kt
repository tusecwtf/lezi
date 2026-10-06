package com.lezi.babylog.feature.summary

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.CareAggregation
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SummaryAggregationTest {
    private val zone = ZoneOffset.UTC
    private val anchor = LocalDate.of(2026, 7, 23)

    @Test
    fun ranges_useRollingWeekAndRollingMonth() {
        assertThat(SummaryRange.Day.startDate(anchor)).isEqualTo(LocalDate.of(2026, 7, 23))
        assertThat(SummaryRange.Week.startDate(anchor)).isEqualTo(LocalDate.of(2026, 7, 17))
        assertThat(SummaryRange.Month.startDate(anchor)).isEqualTo(LocalDate.of(2026, 6, 24))
    }

    @Test
    fun aggregation_usesSelectedRangeWindows() {
        val records = listOf(
            record(1, RecordType.FORMULA, LocalDate.of(2026, 6, 24), """{"amount_ml":30}"""),
            record(2, RecordType.FORMULA, LocalDate.of(2026, 7, 16), """{"amount_ml":50}"""),
            record(
                3,
                RecordType.NURSING,
                LocalDate.of(2026, 7, 17),
                """{"left_min":10,"right_min":5,"order":"LR","amount_ml":20,"record_mode":"end"}""",
            ),
            record(4, RecordType.TEMPERATURE, LocalDate.of(2026, 7, 22), """{"celsius":37.2}"""),
            record(5, RecordType.FORMULA, anchor, """{"amount_ml":100}"""),
            record(6, RecordType.PUMPED_FEED, anchor, """{"amount_ml":50}"""),
            record(
                7,
                RecordType.SLEEP,
                anchor,
                """{"is_nap":false,"anomaly_flag":false}""",
                endOffsetMinutes = 90,
            ),
            record(8, RecordType.PEE, anchor),
            record(9, RecordType.BOTH_DIAPER, anchor),
            record(10, RecordType.TEMPERATURE, anchor, """{"celsius":36.8}"""),
            record(11, RecordType.FORMULA, anchor.plusDays(1), """{"amount_ml":999}"""),
        )

        val week = runBlocking {
            SummaryAggregationEngine().calculate(
                SummaryAggregationRequest(
                    records = records,
                    range = SummaryRange.Week,
                    anchorDate = anchor,
                    showAvgSleep = false,
                    comparePrevWeek = true,
                    babyName = "年年",
                    zone = zone,
                ),
            )
        }
        // Rolling week 7/17–7/23: 7/17 nursing (20ml, 15min) is inside the window.
        assertThat(week.rangeStartDate).isEqualTo(LocalDate.of(2026, 7, 17))
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
        // Anchor day 7/23 has pee + both_diaper → pee=2, poop=1 on window index 6.
        assertThat(week.totals.dayValuesPee).hasSize(7)
        assertThat(week.totals.dayValuesPoop).hasSize(7)
        assertThat(week.totals.dayValuesPee[6]).isEqualTo(2f)
        assertThat(week.totals.dayValuesPoop[6]).isEqualTo(1f)
        assertThat(week.totals.dayValuesDiaper[6]).isEqualTo(3f)
        // Anchor day 7/23: formula 100 + pumped 50; sleep 90; pee+both → pee 2, poop 1
        assertThat(week.totals.chartWindows.dayFeedMl).isEqualTo(150)
        assertThat(week.totals.chartWindows.dayFeedCount).isEqualTo(2)
        assertThat(week.totals.chartWindows.daySleepMin).isEqualTo(90)
        assertThat(week.totals.chartWindows.daySleepSegments).isEqualTo(1)
        assertThat(week.totals.chartWindows.dayPee).isEqualTo(2)
        assertThat(week.totals.chartWindows.dayPoop).isEqualTo(1)
        assertThat(week.totals.chartWindows.dayDiaper).isEqualTo(3)
        // Previous window 7/10–7/16 holds only the 7/16 formula record.
        assertThat(week.previousWeekTotals!!.feedMl).isEqualTo(50)
        assertThat(week.previousWeekTotals!!.nursingMin).isEqualTo(0)

        val day = runBlocking {
            SummaryAggregationEngine().calculate(
                SummaryAggregationRequest(
                    records = records,
                    range = SummaryRange.Day,
                    anchorDate = anchor,
                    showAvgSleep = false,
                    babyName = "年年",
                    zone = zone,
                ),
            )
        }
        assertThat(day.totals.feedMl).isEqualTo(150)
        assertThat(day.totals.dayValuesFeed).containsExactly(150f)
        assertThat(day.totals.dayValuesFeed.single()).isEqualTo(week.totals.dayValuesFeed[6])
        assertThat(day.totals.dayValuesSleep.single()).isEqualTo(week.totals.dayValuesSleep[6])
        assertThat(day.totals.dayValuesPee.single()).isEqualTo(week.totals.dayValuesPee[6])
        assertThat(day.totals.dayValuesPoop.single()).isEqualTo(week.totals.dayValuesPoop[6])
        assertThat(day.totals.dayValuesDiaper.single()).isEqualTo(week.totals.dayValuesDiaper[6])
        assertThat(day.totals.feedTimeBuckets).containsExactly(0f, 2f, 0f, 0f).inOrder()

        val month = runBlocking {
            SummaryAggregationEngine().calculate(
                SummaryAggregationRequest(
                    records = records,
                    range = SummaryRange.Month,
                    anchorDate = anchor,
                    showAvgSleep = false,
                    babyName = "年年",
                    zone = zone,
                ),
            )
        }
        assertThat(month.totals.feedMl).isEqualTo(250)
        assertThat(month.totals.dayValuesFeed).hasSize(30)
    }

    @Test
    fun oneDayBar_usesWeekWidthAndIsCentered() {
        val day = calculateBarSlotLayout(canvasWidth = 700f, barCount = 1, preferredGap = 4f)
        val week = calculateBarSlotLayout(canvasWidth = 700f, barCount = 7, preferredGap = 4f)

        assertThat(day.barWidth).isWithin(0.001f).of(week.barWidth)
        assertThat(day.firstBarX + day.barWidth / 2f).isWithin(0.001f).of(350f)
        assertThat(week.firstBarX).isWithin(0.001f).of(week.gap)
    }

    @Test
    fun nursingWithoutVolume_isStillNonEmpty() {
        val ui = runBlocking {
            SummaryAggregationEngine().calculate(
                SummaryAggregationRequest(
                    records = listOf(
                        record(
                            1,
                            RecordType.NURSING,
                            anchor,
                            """{"left_min":7,"right_min":8,"order":"LR","record_mode":"end"}""",
                        ),
                    ),
                    range = SummaryRange.Day,
                    anchorDate = anchor,
                    showAvgSleep = true,
                    babyName = "年年",
                    zone = zone,
                ),
            )
        }

        assertThat(ui.empty).isFalse()
        assertThat(ui.totals.feedCount).isEqualTo(1)
        assertThat(ui.totals.nursingMin).isEqualTo(15)
        assertThat(ui.showAvgSleep).isTrue()
    }

    @Test
    fun logSummaryAndWidgetProjectTheSameCareFacts() {
        val records = listOf(
            record(1, RecordType.FORMULA, anchor, """{"amount_ml":120}"""),
            record(2, RecordType.PEE, anchor),
            record(
                3,
                RecordType.SLEEP,
                anchor,
                """{"is_nap":false,"anomaly_flag":false}""",
                endOffsetMinutes = 45,
            ),
        )
        val day = CareAggregation.day(records, anchor, zone)
        val summary = runBlocking {
            SummaryAggregationEngine().calculate(
                SummaryAggregationRequest(
                    records = records,
                    range = SummaryRange.Day,
                    anchorDate = anchor,
                    showAvgSleep = false,
                    babyName = "年年",
                    zone = zone,
                ),
            )
        }
        val widget = CareAggregation.widget(
            records = records,
            babyName = "年年",
            date = anchor,
            zone = zone,
            now = anchor.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )

        assertThat(summary.totals.feedMl).isEqualTo(day.bucket.feedMl)
        assertThat(summary.totals.sleepMin).isEqualTo(day.bucket.sleepMin)
        assertThat(summary.totals.pee).isEqualTo(day.bucket.pee)
        assertThat(widget.feedMl).isEqualTo(day.bucket.feedMl)
        assertThat(widget.sleepMin).isEqualTo(day.bucket.sleepMin)
        assertThat(widget.pee).isEqualTo(day.bucket.pee)
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
            payloadJson = payload,
            updatedAt = timestamp,
        )
    }
}
