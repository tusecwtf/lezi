package com.lezi.babylog.feature.summary

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SummaryElderChartPolicyTest {
    @Test
    fun elderDayRangeHidesChartCards() {
        assertThat(elderHidesSummaryCharts(SummaryRange.Day)).isTrue()
    }

    @Test
    fun elderWeekAndMonthKeepChartCards() {
        assertThat(elderHidesSummaryCharts(SummaryRange.Week)).isFalse()
        assertThat(elderHidesSummaryCharts(SummaryRange.Month)).isFalse()
    }
}
