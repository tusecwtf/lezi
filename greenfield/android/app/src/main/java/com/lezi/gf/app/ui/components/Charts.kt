package com.lezi.gf.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lezi.gf.app.ui.model.ChartSeries
import com.lezi.gf.app.ui.theme.LeziDensity
import com.lezi.gf.app.ui.theme.LeziSpacing

@Composable
fun WeekBarChart(
    bars: List<ChartSeries.BarPoint>,
    title: String,
    barColor: Color,
    density: LeziDensity,
    modifier: Modifier = Modifier,
) {
    val max = ChartSeries.maxBar(bars)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "周汇总图 $title" },
        shape = RoundedCornerShape(if (density.useCards) density.cardCorner else 0.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (density.useCards) 1.dp else 0.dp,
    ) {
        Column(Modifier.padding(density.cardPad)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(LeziSpacing.Sm))
            if (!ChartSeries.isDrawable(bars)) {
                Text("本周暂无数据", style = MaterialTheme.typography.bodySmall)
            } else {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                ) {
                    val n = bars.size.coerceAtLeast(1)
                    val gap = size.width * 0.04f
                    val barW = (size.width - gap * (n + 1)) / n
                    bars.forEach { bar ->
                        val h = (bar.value / max) * size.height * 0.9f
                        val x = gap + bar.dayIndex * (barW + gap)
                        val y = size.height - h
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(x, y),
                            size = Size(barW, h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    bars.forEach { Text(it.label, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
fun GrowthCurveCanvas(
    drawable: ChartSeries.GrowthDrawable,
    title: String,
    density: LeziDensity,
    modifier: Modifier = Modifier,
) {
    val bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val p50Color = MaterialTheme.colorScheme.primary
    val measureColor = MaterialTheme.colorScheme.tertiary
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "成长曲线 $title" },
        shape = RoundedCornerShape(if (density.useCards) density.cardCorner else 0.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (density.useCards) 1.dp else 0.dp,
    ) {
        Column(Modifier.padding(density.cardPad)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(LeziSpacing.Sm))
            if (!drawable.nonEmpty) {
                Text("暂无参考曲线", style = MaterialTheme.typography.bodySmall)
            } else {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                ) {
                    fun mapX(x: Float): Float {
                        val maxX = drawable.p50.maxOfOrNull { it.x }?.coerceAtLeast(1f) ?: 1f
                        return (x / maxX) * size.width
                    }
                    fun mapY(y: Float): Float {
                        val span = (drawable.yMax - drawable.yMin).coerceAtLeast(0.1f)
                        return size.height - ((y - drawable.yMin) / span) * size.height
                    }
                    // Band between p3 and p97
                    if (drawable.p3.isNotEmpty() && drawable.p97.isNotEmpty()) {
                        val path = Path()
                        drawable.p3.forEachIndexed { i, p ->
                            val x = mapX(p.x)
                            val y = mapY(p.y)
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        for (i in drawable.p97.indices.reversed()) {
                            val p = drawable.p97[i]
                            path.lineTo(mapX(p.x), mapY(p.y))
                        }
                        path.close()
                        drawPath(path, bandColor)
                    }
                    fun stroke(points: List<ChartSeries.CurvePoint>, color: Color, width: Float) {
                        if (points.size < 2) return
                        val path = Path()
                        points.forEachIndexed { i, p ->
                            val x = mapX(p.x)
                            val y = mapY(p.y)
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
                    }
                    stroke(drawable.p50, p50Color, 3f)
                    stroke(drawable.p3, p50Color.copy(alpha = 0.5f), 1.5f)
                    stroke(drawable.p97, p50Color.copy(alpha = 0.5f), 1.5f)
                    drawable.measurements.forEach { m ->
                        drawCircle(
                            color = measureColor,
                            radius = 6f,
                            center = Offset(mapX(m.x), mapY(m.y)),
                        )
                    }
                }
                Text("P3 / P50 / P97 参考带 · 圆点为录入", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
