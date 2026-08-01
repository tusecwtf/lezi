package com.lezi.babylog.feature.growth

import com.lezi.babylog.core.model.Sex
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(
            "7岁及以上的测量仅显示个人趋势",
            growthReferenceNotice(
                sex = Sex.FEMALE,
                hasReferenceBands = true,
                hasMeasurementsOutsideReference = true,
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
