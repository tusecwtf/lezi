package com.lezi.babylog.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Stroke glyphs aligned to prototype glance SVGs (no emoji). */
@Composable
fun LeziGlyphIcon(
    kind: LeziGlyph,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = 15.dp,
) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = (s * 0.11f).coerceAtLeast(1.6f)
        val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Map 24x24 viewBox paths into local size.
        fun sx(x: Float) = x / 24f * s
        fun sy(y: Float) = y / 24f * s
        when (kind) {
            // M9 3h6v4l2 3v9a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2v-9l2-3V3ZM9 12h8
            LeziGlyph.Bottle -> {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(sx(9f), sy(3f))
                    lineTo(sx(15f), sy(3f))
                    lineTo(sx(15f), sy(7f))
                    lineTo(sx(17f), sy(10f))
                    lineTo(sx(17f), sy(19f))
                    cubicTo(sx(17f), sy(20.1f), sx(16.1f), sy(21f), sx(15f), sy(21f))
                    lineTo(sx(9f), sy(21f))
                    cubicTo(sx(7.9f), sy(21f), sx(7f), sy(20.1f), sx(7f), sy(19f))
                    lineTo(sx(7f), sy(10f))
                    lineTo(sx(9f), sy(7f))
                    lineTo(sx(9f), sy(3f))
                    close()
                }
                drawPath(p, tint, style = style)
                drawLine(
                    tint,
                    Offset(sx(9f), sy(12f)),
                    Offset(sx(17f), sy(12f)),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            // M12 3c3.4 4.1 5.2 7.2 5.2 10a5.2 5.2 0 0 1-10.4 0c0-2.8 1.8-5.9 5.2-10Z
            LeziGlyph.Drop -> {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(sx(12f), sy(3f))
                    cubicTo(sx(15.4f), sy(7.1f), sx(17.2f), sy(10.2f), sx(17.2f), sy(13f))
                    cubicTo(sx(17.2f), sy(15.87f), sx(14.87f), sy(18.2f), sx(12f), sy(18.2f))
                    cubicTo(sx(9.13f), sy(18.2f), sx(6.8f), sy(15.87f), sx(6.8f), sy(13f))
                    cubicTo(sx(6.8f), sy(10.2f), sx(8.6f), sy(7.1f), sx(12f), sy(3f))
                    close()
                }
                drawPath(p, tint, style = style)
            }
            // M20 15.5A8 8 0 0 1 8.5 4 8 8 0 1 0 20 15.5Z
            LeziGlyph.Moon -> {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(sx(20f), sy(15.5f))
                    cubicTo(sx(18.5f), sy(19.2f), sx(14.8f), sy(21.8f), sx(10.7f), sy(21.2f))
                    cubicTo(sx(6.6f), sy(20.6f), sx(3.4f), sy(17.4f), sx(2.8f), sy(13.3f))
                    cubicTo(sx(2.2f), sy(9.2f), sx(4.8f), sy(5.5f), sx(8.5f), sy(4f))
                    cubicTo(sx(7.2f), sy(7.8f), sx(8.1f), sy(12.2f), sx(11.3f), sy(14.7f))
                    cubicTo(sx(14.5f), sy(17.2f), sx(18.9f), sy(17.4f), sx(20f), sy(15.5f))
                    close()
                }
                drawPath(p, tint, style = style)
            }
            // M7 4v4.5a5 5 0 0 0 10 0V4M5 7h14M7 17c2.7 1.3 7.3 1.3 10 0
            LeziGlyph.Toilet -> {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(sx(7f), sy(4f))
                    lineTo(sx(7f), sy(8.5f))
                    cubicTo(sx(7f), sy(11.26f), sx(9.24f), sy(13.5f), sx(12f), sy(13.5f))
                    cubicTo(sx(14.76f), sy(13.5f), sx(17f), sy(11.26f), sx(17f), sy(8.5f))
                    lineTo(sx(17f), sy(4f))
                    moveTo(sx(5f), sy(7f))
                    lineTo(sx(19f), sy(7f))
                    moveTo(sx(7f), sy(17f))
                    cubicTo(sx(9.7f), sy(18.3f), sx(14.3f), sy(18.3f), sx(17f), sy(17f))
                }
                drawPath(p, tint, style = style)
            }
            // diaper + bar: same as toilet + M10 10h4
            LeziGlyph.Pin -> {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(sx(7f), sy(4f))
                    lineTo(sx(7f), sy(8.5f))
                    cubicTo(sx(7f), sy(11.26f), sx(9.24f), sy(13.5f), sx(12f), sy(13.5f))
                    cubicTo(sx(14.76f), sy(13.5f), sx(17f), sy(11.26f), sx(17f), sy(8.5f))
                    lineTo(sx(17f), sy(4f))
                    moveTo(sx(5f), sy(7f))
                    lineTo(sx(19f), sy(7f))
                    moveTo(sx(7f), sy(17f))
                    cubicTo(sx(9.7f), sy(18.3f), sx(14.3f), sy(18.3f), sx(17f), sy(17f))
                    moveTo(sx(10f), sy(10f))
                    lineTo(sx(14f), sy(10f))
                }
                drawPath(p, tint, style = style)
            }
            LeziGlyph.Plus -> {
                drawLine(
                    tint,
                    Offset(sx(12f), sy(5f)),
                    Offset(sx(12f), sy(19f)),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    tint,
                    Offset(sx(5f), sy(12f)),
                    Offset(sx(19f), sy(12f)),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            LeziGlyph.Dot -> {
                val r = s * 0.14f
                drawCircle(
                    color = tint,
                    radius = r,
                    center = Offset(s / 2f, s / 2f),
                    style = style,
                )
            }
        }
    }
}

enum class LeziGlyph { Bottle, Drop, Moon, Toilet, Pin, Plus, Dot }

/**
 * A day-lane mark. Sleep is an [isEvent]=false interval; feed/care are
 * moment events so they render as large tappable dots rather than thin bars.
 */
data class TimelineLaneSegment(
    val startMinOfDay: Int,
    val endMinOfDay: Int,
    val color: Color,
    /** Short name shown in the tip, e.g. 睡眠 / 配方奶. */
    val title: String = "",
    /** Human-readable explanation, e.g. 22:00–06:00 · 8小时. */
    val detail: String = "",
    /** true = point-in-time (feed/care); false = duration bar (sleep). */
    val isEvent: Boolean = false,
)

private fun TimelineLaneSegment.isSameAs(other: TimelineLaneSegment?): Boolean {
    if (other == null) return false
    return startMinOfDay == other.startMinOfDay &&
        endMinOfDay == other.endMinOfDay &&
        title == other.title &&
        isEvent == other.isEvent
}

/** Pixel center X (horizontal lane) or Y (vertical rail) for an event mark. */
private fun eventSlotOffset(
    segments: List<TimelineLaneSegment>,
    seg: TimelineLaneSegment,
    slotPx: Float,
): Float {
    val cluster = segments.filter {
        it.isEvent && kotlin.math.abs(it.startMinOfDay - seg.startMinOfDay) <= 12
    }
    if (cluster.size <= 1) return 0f
    val index = cluster.indexOfFirst {
        it.startMinOfDay == seg.startMinOfDay &&
            it.title == seg.title &&
            it.detail == seg.detail
    }.coerceAtLeast(0)
    return (index - (cluster.size - 1) / 2f) * slotPx
}

@Composable
fun TimelineLane(
    label: String,
    segments: List<TimelineLaneSegment>,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 34.dp,
    nowMinOfDay: Int? = null,
    markerStyle: Boolean = true,
    selected: TimelineLaneSegment? = null,
    onSegmentClick: (TimelineLaneSegment?) -> Unit = {},
) {
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val nowColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f)
    val focusRing = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    // Taller lanes for event markers so dots + touch targets are comfortable.
    val laneHeight = if (markerStyle) trackHeight.coerceAtLeast(36.dp) else trackHeight
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = LeziTypography.Meta.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(34.dp),
            maxLines = 1,
        )
        Canvas(
            Modifier
                .weight(1f)
                .height(laneHeight)
                .semantics {
                    contentDescription = "$label 轨道，点按可高亮并查看说明"
                }
                .pointerInput(segments, markerStyle, selected) {
                    detectTapGestures { offset ->
                        val total = 24f * 60f
                        val min = ((offset.x / size.width) * total).toInt().coerceIn(0, 24 * 60)
                        // Large touch halo: ~28dp or ≥18 minutes of day-axis.
                        val halfMin = with(density) {
                            ((28.dp.toPx() / 2f) / size.width * total).toInt().coerceAtLeast(18)
                        }
                        val hit = if (markerStyle) {
                            // Nearest event within the halo — easier than exact hit boxes.
                            segments
                                .filter { seg ->
                                    val anchor = if (seg.isEvent) {
                                        seg.startMinOfDay
                                    } else {
                                        (seg.startMinOfDay + seg.endMinOfDay) / 2
                                    }
                                    kotlin.math.abs(anchor - min) <= halfMin
                                }
                                .minByOrNull { seg ->
                                    val anchor = if (seg.isEvent) {
                                        seg.startMinOfDay
                                    } else {
                                        (seg.startMinOfDay + seg.endMinOfDay) / 2
                                    }
                                    kotlin.math.abs(anchor - min)
                                }
                        } else {
                            segments.asReversed().firstOrNull { seg ->
                                val end = seg.endMinOfDay.coerceAtLeast(seg.startMinOfDay + 1)
                                min in seg.startMinOfDay until end
                            }
                        }
                        // Tap same mark again to clear highlight.
                        onSegmentClick(
                            if (hit != null && hit.isSameAs(selected)) null else hit,
                        )
                    }
                },
        ) {
            val h = size.height
            // quarter guides
            for (i in 1..3) {
                val x = size.width * i / 4f
                drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            }
            // center baseline
            drawLine(
                track,
                Offset(0f, h / 2f),
                Offset(size.width, h / 2f),
                strokeWidth = 1.2f,
            )
            val total = 24f * 60f
            val slotPx = 5.dp.toPx()
            val hasSelection = selected != null
            for (seg in segments) {
                val highlighted = seg.isSameAs(selected)
                val dimmed = hasSelection && !highlighted
                if (markerStyle || seg.isEvent) {
                    // Point-in-time: pin + large filled circle (not a thin duration sliver).
                    val baseX = size.width * (seg.startMinOfDay / total)
                    val x = baseX + eventSlotOffset(segments, seg, slotPx)
                    val center = Offset(x, h * 0.32f)
                    val radius = when {
                        highlighted -> 11.dp.toPx()
                        dimmed -> 6.5.dp.toPx()
                        else -> 7.5.dp.toPx()
                    }
                    val fillAlpha = when {
                        highlighted -> 1f
                        dimmed -> 0.35f
                        else -> 0.95f
                    }
                    // Stem to baseline
                    drawLine(
                        color = seg.color.copy(alpha = if (dimmed) 0.2f else 0.55f),
                        start = Offset(x, h * 0.18f),
                        end = Offset(x, h / 2f),
                        strokeWidth = if (highlighted) 3f else 2.2f,
                    )
                    if (highlighted) {
                        // Soft outer glow
                        drawCircle(
                            color = focusRing.copy(alpha = 0.28f),
                            radius = radius + 7f,
                            center = center,
                        )
                        drawCircle(
                            color = focusRing.copy(alpha = 0.45f),
                            radius = radius + 4f,
                            center = center,
                        )
                    }
                    drawCircle(
                        color = seg.color.copy(alpha = fillAlpha),
                        radius = radius,
                        center = center,
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = if (dimmed) 0.35f else 0.9f),
                        radius = radius * 0.35f,
                        center = center,
                    )
                    if (highlighted) {
                        drawCircle(
                            color = focusRing,
                            radius = radius + 2.5f,
                            center = center,
                            style = Stroke(width = 3f),
                        )
                        drawCircle(
                            color = Color.White,
                            radius = radius + 0.5f,
                            center = center,
                            style = Stroke(width = 1.6f),
                        )
                    }
                } else {
                    val w = size.width * ((seg.endMinOfDay - seg.startMinOfDay).coerceAtLeast(1) / total)
                    val x = size.width * (seg.startMinOfDay / total)
                    val barH = if (highlighted) h * 0.72f else h * 0.64f
                    val barTop = (h - barH) / 2f
                    val barW = w.coerceAtLeast(4f)
                    val fillAlpha = when {
                        highlighted -> 1f
                        dimmed -> 0.28f
                        else -> 0.9f
                    }
                    if (highlighted) {
                        drawRoundRect(
                            color = focusRing.copy(alpha = 0.22f),
                            topLeft = Offset(x - 4f, barTop - 4f),
                            size = Size(barW + 8f, barH + 8f),
                            cornerRadius = CornerRadius(10f, 10f),
                        )
                    }
                    drawRoundRect(
                        color = seg.color.copy(alpha = fillAlpha),
                        topLeft = Offset(x, barTop),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(6f, 6f),
                    )
                    if (highlighted) {
                        drawRoundRect(
                            color = focusRing,
                            topLeft = Offset(x - 2f, barTop - 2f),
                            size = Size(barW + 4f, barH + 4f),
                            cornerRadius = CornerRadius(8f, 8f),
                            style = Stroke(width = 3f),
                        )
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.9f),
                            topLeft = Offset(x - 0.5f, barTop - 0.5f),
                            size = Size(barW + 1f, barH + 1f),
                            cornerRadius = CornerRadius(6f, 6f),
                            style = Stroke(width = 1.5f),
                        )
                    }
                }
            }
            if (nowMinOfDay != null) {
                val nx = size.width * (nowMinOfDay.coerceIn(0, 24 * 60) / total)
                drawLine(nowColor, Offset(nx, 0f), Offset(nx, h), strokeWidth = 2.2f)
                drawCircle(nowColor, radius = 4f, center = Offset(nx, 0f))
            }
        }
    }
}

@Composable
fun TimelineRailCard(
    sleep: List<TimelineLaneSegment>,
    feed: List<TimelineLaneSegment>,
    care: List<TimelineLaneSegment>,
    recordCount: Int,
    nowMinOfDay: Int?,
    modifier: Modifier = Modifier,
) {
    var selected by remember(sleep, feed, care) {
        mutableStateOf<TimelineLaneSegment?>(null)
    }
    if (LeziThemeExt.isJournal) {
        JournalTimelineRail(
            sleep = sleep,
            feed = feed,
            care = care,
            recordCount = recordCount,
            nowMinOfDay = nowMinOfDay,
            selected = selected,
            onSelect = { selected = it },
            modifier = modifier,
        )
        return
    }
    val ext = LocalLeziColors.current
    LeziCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("一天一眼", style = LeziTypography.Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("24h 时间轴", style = LeziTypography.TitleSm)
            }
            Text("$recordCount 条记录", style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "点睡眠条或喂养/护理圆点可高亮并查看说明",
            style = LeziTypography.Meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        // Hours on top, indented under lane labels
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(34.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("00", "06", "12", "18", "24").forEach {
                    Text(
                        it,
                        style = LeziTypography.Meta.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        TimelineLane(
            label = "睡眠",
            segments = sleep,
            nowMinOfDay = nowMinOfDay,
            markerStyle = false,
            selected = selected,
            onSegmentClick = { selected = it },
        )
        Spacer(Modifier.height(8.dp))
        TimelineLane(
            label = "喂养",
            segments = feed,
            nowMinOfDay = nowMinOfDay,
            markerStyle = true,
            selected = selected,
            onSegmentClick = { selected = it },
        )
        Spacer(Modifier.height(8.dp))
        TimelineLane(
            label = "护理",
            segments = care,
            nowMinOfDay = nowMinOfDay,
            markerStyle = true,
            selected = selected,
            onSegmentClick = { selected = it },
        )
        TimelineSegmentTip(
            segment = selected,
            onDismiss = { selected = null },
        )
        Spacer(Modifier.height(10.dp))
        TimelineLegendRow(
            items = listOf(
                TimelineLegendItem.Bar("睡眠", ext.laneSleep),
                TimelineLegendItem.Dot("喂养", ext.laneFeed),
                TimelineLegendItem.Dot("尿尿", ext.laneCare),
                TimelineLegendItem.Dot("便便", ext.sun),
            ),
            modifier = Modifier.padding(start = 34.dp),
        )
    }
}

private sealed class TimelineLegendItem {
    abstract val label: String
    abstract val color: Color

    data class Dot(override val label: String, override val color: Color) : TimelineLegendItem()
    data class Bar(override val label: String, override val color: Color) : TimelineLegendItem()
}

@Composable
private fun TimelineLegendRow(
    items: List<TimelineLegendItem>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.forEach { item ->
            when (item) {
                is TimelineLegendItem.Dot -> LegendDot(item.label, item.color)
                is TimelineLegendItem.Bar -> LegendBar(item.label, item.color)
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = LeziTypography.Meta.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendBar(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(12.dp)
                .height(7.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = LeziTypography.Meta.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimelineSegmentTip(
    segment: TimelineLaneSegment?,
    onDismiss: () -> Unit,
) {
    if (segment == null) return
    val title = segment.title.ifBlank { "记录" }
    val detail = segment.detail.ifBlank {
        val start = formatMinOfDay(segment.startMinOfDay)
        val end = formatMinOfDay(segment.endMinOfDay)
        if (segment.endMinOfDay - segment.startMinOfDay <= 15) {
            "约 $start"
        } else {
            "$start–$end"
        }
    }
    Spacer(Modifier.height(10.dp))
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDismiss),
        shape = LeziShapes.Sm,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = LeziTypography.BodyStrong)
                Text(
                    "关闭",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                detail,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatMinOfDay(minOfDay: Int): String {
    val clamped = minOfDay.coerceIn(0, 24 * 60)
    val h = clamped / 60
    val m = clamped % 60
    return "%02d:%02d".format(h.coerceAtMost(24), m)
}

@Composable
private fun JournalTimelineRail(
    sleep: List<TimelineLaneSegment>,
    feed: List<TimelineLaneSegment>,
    care: List<TimelineLaneSegment>,
    recordCount: Int,
    nowMinOfDay: Int?,
    selected: TimelineLaneSegment?,
    onSelect: (TimelineLaneSegment?) -> Unit,
    modifier: Modifier,
) {
    val grid = LeziThemeExt.colors.chartGrid
    val danger = LeziThemeExt.colors.danger
    val focusRing = MaterialTheme.colorScheme.primary
    val lanes = listOf(sleep, feed, care)
    LeziCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("0–24h 记录轨道", style = LeziTypography.Label)
            Text("$recordCount 条", style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "点睡眠条或喂养/护理圆点可高亮并查看说明",
            style = LeziTypography.Meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .width(26.dp)
                    .height(220.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                listOf("0", "6", "12", "18", "24").forEach {
                    Text(it, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(6.dp))
            Canvas(
                Modifier
                    .weight(1f)
                    .height(220.dp)
                    .semantics { contentDescription = "0到24小时记录轨道，点按可高亮并查看说明" }
                    .pointerInput(sleep, feed, care, selected) {
                        detectTapGestures { offset ->
                            val laneIndex = ((offset.x / size.width) * 3f).toInt().coerceIn(0, 2)
                            val min = ((offset.y / size.height) * 1440f).toInt().coerceIn(0, 1440)
                            val lane = lanes[laneIndex]
                            // Events: nearest pin within ~22 minutes; intervals: contain check.
                            val hit = lane
                                .mapNotNull { seg ->
                                    if (seg.isEvent) {
                                        val dist = kotlin.math.abs(seg.startMinOfDay - min)
                                        if (dist <= 22) seg to dist else null
                                    } else {
                                        val end = seg.endMinOfDay.coerceAtLeast(seg.startMinOfDay + 1)
                                        if (min in seg.startMinOfDay until end) {
                                            seg to 0
                                        } else {
                                            null
                                        }
                                    }
                                }
                                .minByOrNull { it.second }
                                ?.first
                            onSelect(
                                if (hit != null && hit.isSameAs(selected)) null else hit,
                            )
                        }
                    },
            ) {
                for (i in 0..4) {
                    val y = size.height * i / 4f
                    drawLine(grid.copy(alpha = 0.75f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                for (i in 0..3) {
                    val x = size.width * i / 3f
                    drawLine(grid.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                }
                val slotPx = 5.dp.toPx()
                val hasSelection = selected != null
                lanes.forEachIndexed { laneIndex, segments ->
                    val laneWidth = size.width / 3f
                    val laneLeft = laneIndex * laneWidth
                    segments.forEach { segment ->
                        val highlighted = segment.isSameAs(selected)
                        val dimmed = hasSelection && !highlighted
                        if (segment.isEvent) {
                            // Moment pin: large circle centered on time, easy to see/tap.
                            val baseY = size.height * (segment.startMinOfDay.coerceIn(0, 1440) / 1440f)
                            val y = (baseY + eventSlotOffset(segments, segment, slotPx))
                                .coerceIn(10f, size.height - 10f)
                            val cx = laneLeft + laneWidth / 2f
                            val center = Offset(cx, y)
                            val radius = when {
                                highlighted -> 11.dp.toPx()
                                dimmed -> 6.5.dp.toPx()
                                else -> 7.5.dp.toPx()
                            }
                            val fillAlpha = when {
                                highlighted -> 1f
                                dimmed -> 0.32f
                                else -> 0.95f
                            }
                            // Soft lane stripe behind the pin for scanning.
                            drawLine(
                                color = segment.color.copy(alpha = if (dimmed) 0.08f else 0.22f),
                                start = Offset(laneLeft + 8f, y),
                                end = Offset(laneLeft + laneWidth - 8f, y),
                                strokeWidth = if (highlighted) 4.5f else 3f,
                            )
                            if (highlighted) {
                                drawCircle(
                                    color = focusRing.copy(alpha = 0.28f),
                                    radius = radius + 8f,
                                    center = center,
                                )
                                drawCircle(
                                    color = focusRing.copy(alpha = 0.4f),
                                    radius = radius + 4.5f,
                                    center = center,
                                )
                            }
                            drawCircle(
                                color = segment.color.copy(alpha = fillAlpha),
                                radius = radius,
                                center = center,
                            )
                            drawCircle(
                                color = Color.White.copy(alpha = if (dimmed) 0.3f else 0.9f),
                                radius = radius * 0.35f,
                                center = center,
                            )
                            if (highlighted) {
                                drawCircle(
                                    color = focusRing,
                                    radius = radius + 2.5f,
                                    center = center,
                                    style = Stroke(width = 3f),
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = radius + 0.5f,
                                    center = center,
                                    style = Stroke(width = 1.6f),
                                )
                            }
                        } else {
                            val top = size.height * (segment.startMinOfDay.coerceIn(0, 1440) / 1440f)
                            val bottom = size.height * (segment.endMinOfDay.coerceIn(0, 1440) / 1440f)
                            val barH = (bottom - top).coerceAtLeast(5f)
                            val barW = (laneWidth - 10f).coerceAtLeast(3f)
                            val left = laneLeft + 5f
                            val fillAlpha = when {
                                highlighted -> 1f
                                dimmed -> 0.25f
                                else -> 0.8f
                            }
                            if (highlighted) {
                                drawRoundRect(
                                    color = focusRing.copy(alpha = 0.22f),
                                    topLeft = Offset(left - 3f, top - 3f),
                                    size = Size(barW + 6f, barH + 6f),
                                    cornerRadius = CornerRadius(8f, 8f),
                                )
                            }
                            drawRoundRect(
                                color = segment.color.copy(alpha = fillAlpha),
                                topLeft = Offset(left, top),
                                size = Size(barW, barH),
                                cornerRadius = CornerRadius(5f, 5f),
                            )
                            if (highlighted) {
                                drawRoundRect(
                                    color = focusRing,
                                    topLeft = Offset(left - 2f, top - 2f),
                                    size = Size(barW + 4f, barH + 4f),
                                    cornerRadius = CornerRadius(7f, 7f),
                                    style = Stroke(width = 3f),
                                )
                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.9f),
                                    topLeft = Offset(left - 0.5f, top - 0.5f),
                                    size = Size(barW + 1f, barH + 1f),
                                    cornerRadius = CornerRadius(5f, 5f),
                                    style = Stroke(width = 1.5f),
                                )
                            }
                        }
                    }
                }
                if (nowMinOfDay != null) {
                    val y = size.height * (nowMinOfDay.coerceIn(0, 1440) / 1440f)
                    drawLine(danger, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
                    drawCircle(danger, radius = 4f, center = Offset(0f, y))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 32.dp)) {
            listOf("睡眠", "喂养", "护理").forEach {
                Text(
                    it,
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        val ext = LocalLeziColors.current
        TimelineLegendRow(
            items = listOf(
                TimelineLegendItem.Bar("睡眠", ext.laneSleep),
                TimelineLegendItem.Dot("喂养", ext.laneFeed),
                TimelineLegendItem.Dot("尿尿", ext.laneCare),
                TimelineLegendItem.Dot("便便", ext.sun),
            ),
            modifier = Modifier.padding(start = 32.dp),
        )
        TimelineSegmentTip(
            segment = selected,
            onDismiss = { onSelect(null) },
        )
    }
}

@Composable
fun RecordRow(
    time: String,
    title: String,
    summary: String,
    relative: String,
    tone: LeziTone = LeziTone.Neutral,
    anomaly: Boolean = false,
    leading: @Composable () -> Unit = {
        Text("•", style = LeziTypography.TitleSm)
    },
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LeziThemeExt.isJournal) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = LeziSpacing.Touch)
                .clickable(onClick = onClick),
            shape = LeziShapes.JournalCard,
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(time, style = LeziTypography.Mono, modifier = Modifier.width(48.dp), maxLines = 1)
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(LeziShapes.JournalCard)
                        .background(toneBg(tone)),
                    contentAlignment = Alignment.Center,
                ) { leading() }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = LeziTypography.BodyStrong, maxLines = 1)
                        if (anomaly) {
                            Spacer(Modifier.width(4.dp))
                            Text("!", color = LocalLeziColors.current.danger, style = LeziTypography.BodyStrong)
                        }
                    }
                    if (summary.isNotBlank()) {
                        Text(
                            summary,
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(relative, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    LeziCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(50.dp)) {
                Text(time, style = LeziTypography.Mono, maxLines = 1)
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(toneBg(tone)),
                contentAlignment = Alignment.Center,
            ) { leading() }
            Spacer(Modifier.width(LeziSpacing.Xs))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = LeziTypography.TitleSm, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (anomaly) {
                        Spacer(Modifier.width(4.dp))
                        Text("!", color = LocalLeziColors.current.danger, style = LeziTypography.BodyStrong)
                    }
                }
                if (summary.isNotBlank()) {
                    Text(
                        summary,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                relative,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(48.dp),
            )
        }
    }
}
