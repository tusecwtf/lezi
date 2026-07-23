package com.lezi.babylog.feature.growth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrowthRangeWarningTest {
    private val bands = listOf(
        CurveBand(month = 0f, p3 = 2.5f, p50 = 3.3f, p97 = 4.5f),
        CurveBand(month = 6f, p3 = 6.4f, p50 = 7.9f, p97 = 9.8f),
    )

    @Test
    fun interpolatesReferenceRangeAtMeasurementAge() {
        assertNull(growthRangeWarning(monthAge = 3f, value = 6f, bands = bands))
        assertEquals(
            "该数值高于同月龄参考范围，请确认单位和录入值。",
            growthRangeWarning(monthAge = 3f, value = 12f, bands = bands),
        )
    }

    @Test
    fun reportsLowValueAndHandlesMissingBands() {
        assertEquals(
            "该数值低于同月龄参考范围，请确认单位和录入值。",
            growthRangeWarning(monthAge = 6f, value = 1f, bands = bands),
        )
        assertNull(growthRangeWarning(monthAge = 6f, value = 1f, bands = emptyList()))
    }
}
