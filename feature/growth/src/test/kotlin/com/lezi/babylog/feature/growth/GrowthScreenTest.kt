package com.lezi.babylog.feature.growth

import com.lezi.babylog.core.model.GrowthReferenceBand
import com.lezi.babylog.core.model.Sex
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowthScreenTest {
    @Test
    fun growthPageExposesOnlyWeightAndLinearGrowth() {
        assertEquals(
            listOf(GrowthMetric.WEIGHT, GrowthMetric.HEIGHT),
            GrowthMetric.entries,
        )
    }

    @Test
    fun linearMeasurementLabelSwitchesAtTheSecondBirthday() {
        val birthday = LocalDate.of(2024, 2, 29)

        assertEquals(
            "身长",
            growthLinearMeasurementLabel(
                birthday = birthday,
                measuredAt = LocalDate.of(2026, 2, 27)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli(),
                zone = ZoneOffset.UTC,
            ),
        )
        assertEquals(
            "身高",
            growthLinearMeasurementLabel(
                birthday = birthday,
                measuredAt = LocalDate.of(2026, 2, 28)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli(),
                zone = ZoneOffset.UTC,
            ),
        )
    }

    @Test
    fun referenceNoticeExplainsWhyOnlyPersonalTrendIsShown() {
        assertEquals(
            "未设置用于生长参考的性别，仅显示个人趋势",
            growthReferenceNotice(
                sex = Sex.UNKNOWN,
                hasReferenceBands = false,
                hasMeasurementsOutsideReference = false,
            ),
        )
        val overAgeBands = listOf(
            GrowthReferenceBand(month = 0f, p3 = 2f, p50 = 3f, p97 = 4f),
        )
        val inside = measurePoint(monthAge = 12f)
        val outside = measurePoint(monthAge = UNDER_SEVEN_MONTH_EXCLUSIVE)
        assertTrue(
            growthChartReferenceBands(
                bands = overAgeBands,
                points = listOf(outside),
                sex = Sex.FEMALE,
                referenceValidUntilMonthExclusive = UNDER_SEVEN_MONTH_EXCLUSIVE,
                currentMonthAge = 12f,
            ).isEmpty(),
        )
        assertTrue(
            growthChartReferenceBands(
                bands = overAgeBands,
                points = listOf(inside),
                sex = Sex.MALE,
                referenceValidUntilMonthExclusive = UNDER_SEVEN_MONTH_EXCLUSIVE,
                currentMonthAge = UNDER_SEVEN_MONTH_EXCLUSIVE,
            ).isEmpty(),
        )
        assertEquals(
            overAgeBands,
            growthChartReferenceBands(
                bands = overAgeBands,
                points = listOf(inside),
                sex = Sex.FEMALE,
                referenceValidUntilMonthExclusive = UNDER_SEVEN_MONTH_EXCLUSIVE,
                currentMonthAge = 12f,
            ),
        )
        assertTrue(
            growthChartReferenceBands(
                bands = overAgeBands,
                points = listOf(inside),
                sex = Sex.UNKNOWN,
                referenceValidUntilMonthExclusive = UNDER_SEVEN_MONTH_EXCLUSIVE,
                currentMonthAge = 12f,
            ).isEmpty(),
        )
        assertEquals(
            "7岁及以上的测量仅显示个人趋势",
            growthReferenceNotice(
                sex = Sex.FEMALE,
                hasReferenceBands = growthChartReferenceBands(
                    bands = overAgeBands,
                    points = listOf(outside),
                    sex = Sex.FEMALE,
                    referenceValidUntilMonthExclusive = UNDER_SEVEN_MONTH_EXCLUSIVE,
                    currentMonthAge = 12f,
                ).isNotEmpty(),
                hasMeasurementsOutsideReference = growthTrendIsPersonalOnly(
                    points = listOf(outside),
                    birthday = LocalDate.of(2010, 1, 1),
                    referenceValidUntilMonthExclusive = UNDER_SEVEN_MONTH_EXCLUSIVE,
                    today = LocalDate.of(2011, 1, 1),
                ),
            ),
        )
        assertEquals(
            "参考数据暂不可用，仅显示个人趋势",
            growthReferenceNotice(
                sex = Sex.MALE,
                hasReferenceBands = false,
                hasMeasurementsOutsideReference = false,
            ),
        )
        assertNull(
            growthReferenceNotice(
                sex = Sex.MALE,
                hasReferenceBands = true,
                hasMeasurementsOutsideReference = false,
            ),
        )
    }
}

private fun measurePoint(monthAge: Float) = MeasurePoint(
    monthAge = monthAge,
    value = 10f,
    recordId = 1L,
    measuredAt = 0L,
    note = null,
    referenceWarning = null,
)
