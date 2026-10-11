package com.lezi.babylog.feature.summary

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Constraints
import java.time.LocalDate
import kotlin.math.roundToInt

private fun LocalDate.spokenChartDate(): String = "${monthValue}月${dayOfMonth}日"

internal fun summaryChartOverview(metricLabel: String, dates: List<LocalDate>): String =
    if (dates.isEmpty()) {
        "$metricLabel，无日期"
    } else {
        "$metricLabel，${dates.first().spokenChartDate()}至${dates.last().spokenChartDate()}，" +
            "${dates.size}天，可逐日查看"
    }

/** Formats existing plotted values; never treats a missing slot as a measured zero. */
internal fun summaryValueDayDescription(
    date: LocalDate,
    metricLabel: String,
    value: Float?,
    hasRecords: Boolean?,
    valueFormatter: (Float) -> String,
): String {
    val detail = when {
        hasRecords == false -> "无记录"
        value == null || (value == 0f && hasRecords == null) -> "暂无可用数值"
        else -> valueFormatter(value)
    }
    return "${date.spokenChartDate()} $metricLabel $detail"
}

internal fun diaperDayDescription(date: LocalDate, pee: Float, poop: Float): String =
    if (pee == 0f && poop == 0f) {
        "${date.spokenChartDate()} 尿布 无记录"
    } else {
        "${date.spokenChartDate()} 尿${pee.toInt()}次 便${poop.toInt()}次 " +
            "共${(pee + poop).toInt()}次"
    }

internal fun accessibleFoodDayDescription(
    date: LocalDate,
    laneNames: List<String>,
    values: List<Float>,
    unparseableCount: Int,
    hasRecords: Boolean?,
): String = when {
    hasRecords == false -> "${date.spokenChartDate()} 辅食 无记录"
    values.all { it == 0f } && unparseableCount > 0 ->
        "${date.spokenChartDate()} 辅食 未填量${unparseableCount}条"
    values.all { it == 0f } && hasRecords == null ->
        "${date.spokenChartDate()} 辅食 暂无可用数值"
    else -> foodDayDescription(date, laneNames, values, unparseableCount)
}

internal data class SummaryChartDayBounds(val left: Float, val right: Float)

/** Adjacent day cells partition the plot around the existing bar/point centers. */
internal fun summaryChartDayBounds(
    centers: List<Float>,
    canvasWidth: Float,
): List<SummaryChartDayBounds> = centers.mapIndexed { index, center ->
    val left = if (index == 0) 0f else (centers[index - 1] + center) / 2f
    val right = if (index == centers.lastIndex) canvasWidth else (center + centers[index + 1]) / 2f
    SummaryChartDayBounds(left.coerceIn(0f, canvasWidth), right.coerceIn(0f, canvasWidth))
}

/** Read-only semantics, with real non-overlapping plot bounds and no visible controls. */
@Composable
internal fun SummaryChartDayCells(
    descriptions: List<String>,
    modifier: Modifier = Modifier,
    lineChart: Boolean = false,
) {
    Layout(
        modifier = modifier,
        content = {
            descriptions.forEachIndexed { index, description ->
                Box(Modifier.semantics {
                    contentDescription = description
                    traversalIndex = index.toFloat()
                })
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val count = descriptions.size
        val centers = if (lineChart) {
            List(count) { index -> width * (index / (count - 1f).coerceAtLeast(1f)) }
        } else {
            val slots = chartBarLayout(count, width.toFloat())
            List(count) { index ->
                slots.firstBarX + index * (slots.barWidth + slots.gap) + slots.barWidth / 2f
            }
        }
        val bounds = summaryChartDayBounds(centers, width.toFloat())
        val placeables = measurables.mapIndexed { index, measurable ->
            val bound = bounds[index]
            measurable.measure(Constraints.fixed(
                width = (bound.right.roundToInt() - bound.left.roundToInt()).coerceAtLeast(0),
                height = height,
            ))
        }
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                // The drawn chronological axis remains left-to-right in every locale.
                placeable.place(bounds[index].left.roundToInt(), 0)
            }
        }
    }
}
