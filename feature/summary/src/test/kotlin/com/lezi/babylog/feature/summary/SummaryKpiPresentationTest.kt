package com.lezi.babylog.feature.summary

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SummaryKpiPresentationTest {
    @Test
    fun splitSummaryKpiAmountPeelsTrailingCountAndDurationUnits() {
        assertThat(splitSummaryKpiAmount("0次")).isEqualTo("0" to "次")
        assertThat(splitSummaryKpiAmount("12次")).isEqualTo("12" to "次")
        assertThat(splitSummaryKpiAmount("2–4次")).isEqualTo("2–4" to "次")
        assertThat(splitSummaryKpiAmount("0m")).isEqualTo("0" to "m")
        assertThat(splitSummaryKpiAmount("3h")).isEqualTo("3" to "h")
        assertThat(splitSummaryKpiAmount("2h10m")).isEqualTo("2h10" to "m")
        assertThat(splitSummaryKpiAmount("12h20m")).isEqualTo("12h20" to "m")
        assertThat(splitSummaryKpiAmount("")).isEqualTo("" to "")
        assertThat(splitSummaryKpiAmount("3")).isEqualTo("3" to "")
    }
}
