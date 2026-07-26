package com.lezi.babylog.feature.growth

import org.junit.Assert.assertEquals
import org.junit.Test

class GrowthChartAccessibilityTest {
    @Test
    fun chartSummaryDescribesMetricRangeAndReferenceCurves() {
        val points = listOf(
            point(monthAge = 2f, value = 5.4f),
            point(monthAge = 6f, value = 6.2f),
        )

        assertEquals(
            "体重趋势图，共 2 次测量；月龄 2.0 到 6.0 个月；数值 5.40 到 6.20 kg；包含 WHO P3、P50、P97 参考曲线",
            growthChartAccessibilitySummary(
                points = points,
                metric = GrowthMetric.WEIGHT,
                hasReferenceBands = true,
            ),
        )
    }

    @Test
    fun chartSummaryDoesNotClaimReferenceCurvesWhenUnavailable() {
        assertEquals(
            "头围趋势图，共 1 次测量；月龄 4.0 个月；数值 39.5 cm",
            growthChartAccessibilitySummary(
                points = listOf(point(monthAge = 4f, value = 39.5f)),
                metric = GrowthMetric.HEAD,
                hasReferenceBands = false,
            ),
        )
    }

    private fun point(monthAge: Float, value: Float) = MeasurePoint(
        monthAge = monthAge,
        value = value,
        recordId = monthAge.toLong(),
        measuredAt = 0L,
        note = null,
        referenceWarning = null,
    )
}
