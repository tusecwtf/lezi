package com.lezi.babylog.feature.summary

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SummaryChartAccessibilityTest {
    private val firstDate = LocalDate.of(2026, 7, 1)

    @Test
    fun overviewNamesTheRangeWithoutReadingEveryDailyValue() {
        val dates = List(31) { firstDate.plusDays(it.toLong()) }
        assertThat(summaryChartOverview("喂养量", dates))
            .isEqualTo("喂养量，7月1日至7月31日，31天，可逐日查看")
        assertThat(summaryChartOverview("体温", emptyList())).isEqualTo("体温，无日期")
        // Product aggregation remains a rolling 30-day range.
        assertThat(SummaryRange.Month.dayCount).isEqualTo(30)
    }

    @Test
    fun dailyValuesKeepUnitsAndDistinguishAbsentFromRecordedZero() {
        fun feed(value: Float?, presence: Boolean?) = summaryValueDayDescription(
            firstDate, "喂养量", value, presence,
        ) { "${it.toInt()}ml" }
        assertThat(feed(0f, false)).isEqualTo("7月1日 喂养量 无记录")
        assertThat(feed(0f, true)).isEqualTo("7月1日 喂养量 0ml")
        assertThat(feed(0f, null)).isEqualTo("7月1日 喂养量 暂无可用数值")
        assertThat(feed(null, true)).isEqualTo("7月1日 喂养量 暂无可用数值")
        assertThat(feed(null, false)).isEqualTo("7月1日 喂养量 无记录")
        assertThat(feed(120f, true)).isEqualTo("7月1日 喂养量 120ml")
        assertThat(summaryValueDayDescription(firstDate, "睡眠分钟", 90f, true) {
            "${it.toLong()}分钟"
        }).isEqualTo("7月1日 睡眠分钟 90分钟")
        assertThat(summaryValueDayDescription(firstDate, "体温", 0f, false) {
            "%.1f℃".format(it)
        }).isEqualTo("7月1日 体温 无记录")
    }

    @Test
    fun foodMissingAmountsAreNotAnnouncedAsMeasuredZero() {
        fun food(presence: Boolean?, missing: Int, value: Float = 0f) =
            accessibleFoodDayDescription(firstDate, listOf("米粉"), listOf(value), missing, presence)
        assertThat(food(false, 0)).isEqualTo("7月1日 辅食 无记录")
        assertThat(food(true, 2)).isEqualTo("7月1日 辅食 未填量2条")
        assertThat(food(true, 0)).isEqualTo("7月1日 共0")
        assertThat(food(null, 0)).isEqualTo("7月1日 辅食 暂无可用数值")
        assertThat(food(true, 1, 1.5f)).isEqualTo(
            foodDayDescription(firstDate, listOf("米粉"), listOf(1.5f), 1),
        )
    }

    @Test
    fun diaperDescriptionsReuseCountsAndKeepAbsentDaysExplicit() {
        assertThat(diaperDayDescription(firstDate, 0f, 0f)).isEqualTo("7月1日 尿布 无记录")
        assertThat(diaperDayDescription(firstDate, 2f, 1f)).isEqualTo("7月1日 尿2次 便1次 共3次")
    }

    @Test
    fun thirtyOneDayCellsHaveSeparateBoundsInsideTheDrawnPlot() {
        val slots = calculateBarSlotLayout(360f, 31, preferredGap = 2f)
        val centers = List(31) { slots.firstBarX + it * (slots.barWidth + slots.gap) + slots.barWidth / 2 }
        val bounds = summaryChartDayBounds(centers, 360f)
        assertThat(bounds).hasSize(31)
        assertThat(bounds.first().left).isEqualTo(0f)
        assertThat(bounds.last().right).isEqualTo(360f)
        bounds.forEachIndexed { index, bound ->
            assertThat(bound.left).isAtMost(centers[index])
            assertThat(bound.right).isAtLeast(centers[index])
            assertThat(bound.right).isGreaterThan(bound.left)
            if (index > 0) assertThat(bound.left).isEqualTo(bounds[index - 1].right)
        }
        val lineBounds = summaryChartDayBounds(List(31) { it * 12f }, 360f)
        assertThat(lineBounds.first()).isEqualTo(SummaryChartDayBounds(0f, 6f))
        assertThat(lineBounds.last()).isEqualTo(SummaryChartDayBounds(354f, 360f))
    }

    @Test
    fun presenceComesFromExistingDayAndFoodInterpretationsWithoutChangingTotals() = runBlocking {
        val anchor = firstDate.plusDays(6)
        val zeroFeed = record(1, RecordType.NURSING, anchor, """{"left_min":7,"right_min":8,"order":"LR","record_mode":"end"}""")
        val zeroFood = record(2, RecordType.BABY_FOOD, anchor.minusDays(1), """{"content":"米粉","amount":"0碗"}""")
        val missingFood = record(3, RecordType.BABY_FOOD, anchor, """{"content":"米粉","amount":"一些"}""")
        val result = SummaryAggregationEngine(nowMillis = {
            anchor.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        }).calculate(SummaryAggregationRequest(
            records = listOf(zeroFeed, zeroFood, missingFood),
            range = SummaryRange.Week,
            anchorDate = anchor,
            showAvgSleep = false,
            babyName = "测试宝宝",
            zone = ZoneOffset.UTC,
        ))
        assertThat(result.totals.feedMl).isEqualTo(0)
        assertThat(result.totals.nursingMin).isEqualTo(15)
        assertThat(result.totals.dayHasFeedRecords)
            .containsExactly(false, false, false, false, false, false, true).inOrder()
        assertThat(result.totals.dayHasSleepRecords).containsExactlyElementsIn(List(7) { false })
        assertThat(result.food!!.dayHasRecords)
            .containsExactly(false, false, false, false, false, true, true).inOrder()
        assertThat(result.food!!.amountMin).isEqualTo(0.0)
        assertThat(result.food!!.unparseableDayCounts)
            .containsExactly(0, 0, 0, 0, 0, 0, 1).inOrder()
    }

    private fun record(id: Long, type: RecordType, date: LocalDate, payload: String): Record {
        val timestamp = date.atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        return Record(
            id = id, clientUuid = "chart-record-$id", babyId = 1,
            type = type, timestamp = timestamp, payloadJson = payload, updatedAt = timestamp,
        )
    }
}
