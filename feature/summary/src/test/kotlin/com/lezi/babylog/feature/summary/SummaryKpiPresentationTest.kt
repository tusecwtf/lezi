package com.lezi.babylog.feature.summary

import com.google.common.truth.Truth.assertThat
import androidx.compose.ui.graphics.Color
import com.lezi.babylog.designsystem.foodSummaryLaneColors
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
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

    @Test
    fun formatFoodAmountTotalKeepsAcceptedInputPrecision() {
        assertThat(formatFoodAmountTotal(0.0)).isEqualTo("0")
        assertThat(formatFoodAmountTotal(2.0)).isEqualTo("2")
        assertThat(formatFoodAmountTotal(1.25)).isEqualTo("1.25")
        assertThat(formatFoodAmountTotal(0.5)).isEqualTo("0.5")
        // Sum noise below display precision rounds away instead of printing tails.
        assertThat(formatFoodAmountTotal(2.9999999999999996)).isEqualTo("3")
        assertThat(formatFoodAmountBound(1.0, 1.0)).isEqualTo("1")
        assertThat(formatFoodAmountBound(1.0, 2.0)).isEqualTo("1–2")
    }

    @Test
    fun foodUnfilledNoteOnlyAppearsWhenUnparseableRecordsExist() {
        assertThat(foodUnfilledNote(0)).isNull()
        assertThat(foodUnfilledNote(2)).isEqualTo("另有 2 条未填量")
    }

    @Test
    fun foodUnparseableOnlyDaysDistinguishMissingAmountsFromZeroDays() {
        assertThat(
            foodUnparseableOnlyDays(
                laneDayValues = listOf(
                    listOf(0f, 0f, 1.5f),
                    listOf(0f, 0f, 0f),
                ),
                unparseableDayCounts = listOf(0, 2, 1),
            ),
        ).containsExactly(false, true, false).inOrder()
    }

    @Test
    fun foodDayDescriptionDisclosesDailyUnparseableCount() {
        val date = LocalDate.of(2026, 7, 23)

        assertThat(
            foodDayDescription(
                date = date,
                laneNames = listOf("米粉", "南瓜"),
                values = listOf(0f, 0f),
                unparseableCount = 2,
            ),
        ).isEqualTo("7月23日 共0 未填量2条")
        assertThat(
            foodDayDescription(
                date = date,
                laneNames = listOf("米粉", "南瓜"),
                values = listOf(1.5f, 0f),
                unparseableCount = 1,
            ),
        ).isEqualTo("7月23日 米粉1.5 共1.5 未填量1条")
    }

    @Test
    fun foodLaneColorsStayPairwiseDistinctInLightAndDarkThemes() {
        listOf(false, true).forEach { darkTheme ->
            val colors = foodSummaryLaneColors(darkTheme)
            assertThat(colors).hasSize(4)
            colors.indices.forEach { first ->
                ((first + 1) until colors.size).forEach { second ->
                    assertThat(rgbDistance(colors[first], colors[second])).isGreaterThan(50f)
                }
            }

            val other = colors[3]
            val channelSpread = max(other.red, max(other.green, other.blue)) -
                min(other.red, min(other.green, other.blue))
            assertThat(channelSpread).isLessThan(0.12f)
        }
    }

    @Test
    fun formatFoodChartValueDropsWholeNumberFraction() {
        assertThat(formatFoodChartValue(2f)).isEqualTo("2")
        assertThat(formatFoodChartValue(1.5f)).isEqualTo("1.5")
        assertThat(formatFoodChartValue(0f)).isEqualTo("0")
    }

    private fun rgbDistance(first: Color, second: Color): Float {
        val red = (first.red - second.red) * 255f
        val green = (first.green - second.green) * 255f
        val blue = (first.blue - second.blue) * 255f
        return sqrt(red * red + green * green + blue * blue)
    }
}
