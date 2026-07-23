package com.lezi.babylog.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class LeziTone { Blue, Yellow, Cream, Neutral }

@Composable
fun toneBg(tone: LeziTone): Color {
    val ext = LocalLeziColors.current
    return when (tone) {
        LeziTone.Blue -> ext.skySoft
        LeziTone.Yellow -> ext.sunSoft
        LeziTone.Cream -> ext.creamDeep
        LeziTone.Neutral -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    }
}

@Composable
fun LeziCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(LeziSpacing.CardPad),
    content: @Composable ColumnScope.() -> Unit,
) {
    val journal = LeziThemeExt.isJournal
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && onClick != null) 0.98f else 1f, label = "cardScale")
    Surface(
        modifier = modifier
            .scale(scale)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = ripple(bounded = true),
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        shape = if (journal) LeziShapes.JournalCard else LeziShapes.Md,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = if (journal) 0.95f else 0.65f),
        ),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

data class JournalSummaryValue(
    val value: String,
    val label: String,
    val tone: LeziTone,
)

/** Five-column glance strip used by the compact record-book template. */
@Composable
fun JournalSummaryStrip(
    values: List<JournalSummaryValue>,
    modifier: Modifier = Modifier,
) {
    LeziCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth()) {
            val visibleValues = values.take(5)
            visibleValues.forEachIndexed { index, item ->
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 58.dp)
                        .padding(horizontal = 3.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(toneBg(item.tone)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)),
                        )
                    }
                    Text(item.value, style = LeziTypography.Mono, maxLines = 1)
                    Text(
                        item.label,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (index < visibleValues.lastIndex) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(58.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
                    )
                }
            }
        }
    }
}

@Composable
fun SummaryMetric(
    value: String,
    label: String,
    tone: LeziTone = LeziTone.Neutral,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    // Prototype glance-card: soft tinted surface, icon, strong value, muted label.
    val bg = toneBg(tone)
    Surface(
        modifier = modifier.heightIn(min = 82.dp),
        shape = LeziShapes.Md,
        color = bg,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
        ),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Top,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) icon() else Text("·", style = LeziTypography.Label)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                value,
                style = LeziTypography.Mono.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                label,
                style = LeziTypography.Meta.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

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
                drawLine(tint, Offset(sx(9f), sy(12f)), Offset(sx(17f), sy(12f)), strokeWidth = stroke, cap = StrokeCap.Round)
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
                drawLine(tint, Offset(sx(12f), sy(5f)), Offset(sx(12f), sy(19f)), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(sx(5f), sy(12f)), Offset(sx(19f), sy(12f)), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
    }
}

enum class LeziGlyph { Bottle, Drop, Moon, Toilet, Pin, Plus }

data class TimelineLaneSegment(
    val startMinOfDay: Int,
    val endMinOfDay: Int,
    val color: Color,
)

@Composable
fun TimelineLane(
    label: String,
    segments: List<TimelineLaneSegment>,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 28.dp,
    nowMinOfDay: Int? = null,
    markerStyle: Boolean = true,
) {
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val nowColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f)
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
                .height(trackHeight),
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
            for (seg in segments) {
                val x = size.width * (seg.startMinOfDay / total)
                if (markerStyle) {
                    val w = 11.dp.toPx()
                    val markH = (h * 0.72f).coerceAtLeast(10f)
                    drawRoundRect(
                        color = seg.color,
                        topLeft = Offset(x - w / 2f, (h - markH) / 2f),
                        size = Size(w, markH),
                        cornerRadius = CornerRadius(4f, 4f),
                    )
                } else {
                    val w = size.width * ((seg.endMinOfDay - seg.startMinOfDay).coerceAtLeast(1) / total)
                    drawRoundRect(
                        color = seg.color,
                        topLeft = Offset(x, h * 0.18f),
                        size = Size(w.coerceAtLeast(4f), h * 0.64f),
                        cornerRadius = CornerRadius(6f, 6f),
                    )
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
    if (LeziThemeExt.isJournal) {
        JournalTimelineRail(
            sleep = sleep,
            feed = feed,
            care = care,
            recordCount = recordCount,
            nowMinOfDay = nowMinOfDay,
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
        TimelineLane(label = "睡眠", segments = sleep, nowMinOfDay = nowMinOfDay, markerStyle = false)
        Spacer(Modifier.height(6.dp))
        TimelineLane(label = "喂养", segments = feed, nowMinOfDay = nowMinOfDay, markerStyle = true)
        Spacer(Modifier.height(6.dp))
        TimelineLane(label = "护理", segments = care, nowMinOfDay = nowMinOfDay, markerStyle = true)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LegendDot("睡眠", ext.laneSleep)
            LegendDot("喂养", ext.laneFeed)
            LegendDot("护理", ext.laneCare)
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Spacer(Modifier.width(5.dp))
        Text(label, style = LeziTypography.Meta.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun JournalTimelineRail(
    sleep: List<TimelineLaneSegment>,
    feed: List<TimelineLaneSegment>,
    care: List<TimelineLaneSegment>,
    recordCount: Int,
    nowMinOfDay: Int?,
    modifier: Modifier,
) {
    val grid = LeziThemeExt.colors.chartGrid
    val danger = LeziThemeExt.colors.danger
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
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .width(26.dp)
                    .height(204.dp),
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
                    .height(204.dp),
            ) {
                for (i in 0..4) {
                    val y = size.height * i / 4f
                    drawLine(grid.copy(alpha = 0.75f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                for (i in 0..3) {
                    val x = size.width * i / 3f
                    drawLine(grid.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                }
                lanes.forEachIndexed { laneIndex, segments ->
                    val laneWidth = size.width / 3f
                    segments.forEach { segment ->
                        val top = size.height * (segment.startMinOfDay.coerceIn(0, 1440) / 1440f)
                        val bottom = size.height * (segment.endMinOfDay.coerceIn(0, 1440) / 1440f)
                        drawRoundRect(
                            color = segment.color.copy(alpha = 0.8f),
                            topLeft = Offset(laneIndex * laneWidth + 5f, top),
                            size = Size((laneWidth - 10f).coerceAtLeast(3f), (bottom - top).coerceAtLeast(5f)),
                            cornerRadius = CornerRadius(5f, 5f),
                        )
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

@Composable
fun QuickRecordButton(
    title: String,
    subtitle: String,
    tone: LeziTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit = {},
) {
    val journal = LeziThemeExt.isJournal
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "quickScale")
    Surface(
        modifier = modifier
            .heightIn(min = 74.dp)
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                onClick = onClick,
            ),
        shape = if (journal) LeziShapes.JournalCard else LeziShapes.Md,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
    ) {
        if (journal) Column(
            Modifier.fillMaxSize().padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(toneBg(tone)),
                contentAlignment = Alignment.Center,
            ) { icon() }
            Text(title, style = LeziTypography.Label, maxLines = 1)
            Text(subtitle, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        } else Row(
            Modifier.padding(LeziSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(toneBg(tone)),
                contentAlignment = Alignment.Center,
            ) { icon() }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = LeziTypography.BodyStrong)
                Text(subtitle, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

enum class StateKind { Loading, Empty, Error, Recording, Success }

@Composable
fun StateContainer(
    kind: StateKind,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    LeziCard(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = LeziSpacing.Lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when (kind) {
                    StateKind.Loading -> "…"
                    StateKind.Empty -> "○"
                    StateKind.Error -> "!"
                    StateKind.Recording -> "●"
                    StateKind.Success -> "✓"
                },
                style = LeziTypography.Display,
                color = when (kind) {
                    StateKind.Error -> LocalLeziColors.current.danger
                    StateKind.Success -> LocalLeziColors.current.success
                    StateKind.Recording -> LocalLeziColors.current.fab
                    else -> MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.height(LeziSpacing.Xs))
            Text(title, style = LeziTypography.TitleSm)
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = LeziTypography.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(LeziSpacing.Sm))
                LeziPrimaryButton(actionLabel, onClick = onAction)
            }
        }
    }
}

@Composable
fun LeziPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val journal = LeziThemeExt.isJournal
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Surface(
        modifier = modifier
            .heightIn(min = LeziSpacing.Touch)
            .clickable(enabled = enabled, interactionSource = interaction, indication = ripple(), onClick = onClick),
        shape = if (journal) LeziShapes.JournalButton else LeziShapes.Button,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = if (pressed) 0.dp else 4.dp,
    ) {
        Box(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = LeziTypography.Label, maxLines = 1)
        }
    }
}

@Composable
fun LeziSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val journal = LeziThemeExt.isJournal
    val shape = if (journal) LeziShapes.JournalButton else LeziShapes.Button
    Surface(
        modifier = modifier
            .heightIn(min = LeziSpacing.Touch)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = LeziTypography.Label, maxLines = 1)
        }
    }
}

@Composable
fun SectionHeading(
    eyebrow: String? = null,
    title: String,
    meta: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(eyebrow, style = LeziTypography.Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(title, style = LeziTypography.TitleSm)
        }
        if (meta != null) {
            Text(meta, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) {
            Row(content = trailing)
        }
    }
}

@Composable
fun PageScaffoldBackground(content: @Composable BoxScope.() -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    val sun = LocalLeziColors.current.sunSoft
    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
            .background(
                Brush.radialGradient(
                    colors = listOf(sun.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(Float.POSITIVE_INFINITY, 0f),
                    radius = 520f,
                ),
            ),
        content = content,
    )
}

/** Prototype top brand strip: 乐 mark + 乐记 + tagline + search/theme. */
@Composable
fun AppBrandBar(
    onSearch: (() -> Unit)? = null,
    onToggleTheme: (() -> Unit)? = null,
    dark: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = LeziSpacing.Page, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "乐",
                color = MaterialTheme.colorScheme.onPrimary,
                style = LeziTypography.TitleSm.copy(fontWeight = FontWeight.Bold),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("乐记", style = LeziTypography.TitleSm.copy(fontWeight = FontWeight.SemiBold))
            Text(
                "今天也好好长大",
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onSearch != null) {
            IconButton(onClick = onSearch) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (onToggleTheme != null) {
            IconButton(onClick = onToggleTheme) {
                Icon(
                    if (dark) Icons.Filled.DarkMode else Icons.Outlined.DarkMode,
                    contentDescription = "切换深色",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Prototype context row: baby chip + day navigator. */
@Composable
fun AppContextRow(
    babyName: String,
    dayLabel: String,
    onCycleBaby: () -> Unit,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onOpenDayPicker: (() -> Unit)? = null,
    canGoNext: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = LeziSpacing.Page, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Surface(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(onClick = onCycleBaby),
            shape = LeziShapes.Pill,
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                1.5.dp,
                accent.copy(alpha = 0.55f),
            ),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            Row(
                Modifier.padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        babyName.take(1).ifBlank { "乐" },
                        color = Color.White,
                        style = LeziTypography.BodyStrong,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        "正在记录",
                        style = LeziTypography.Eyebrow,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(babyName.ifBlank { "乐记" }, style = LeziTypography.BodyStrong)
                        Spacer(Modifier.width(2.dp))
                        Text(
                            "▾",
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevDay, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.ChevronLeft,
                    contentDescription = "前一天",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Surface(
                onClick = { onOpenDayPicker?.invoke() },
                enabled = onOpenDayPicker != null,
                shape = LeziShapes.Pill,
                color = Color.Transparent,
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
            ) {
                Text(
                    dayLabel,
                    style = LeziTypography.BodyStrong,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    maxLines = 1,
                )
            }
            IconButton(
                onClick = onNextDay,
                enabled = canGoNext,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "后一天",
                    tint = if (canGoNext) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
                    },
                )
            }
        }
    }
}

/** Large page title block matching prototype hero copy. */
@Composable
fun PageHero(
    eyebrow: String,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f, fill = false).padding(end = if (trailing != null) 12.dp else 0.dp)) {
                Text(
                    eyebrow,
                    style = LeziTypography.Eyebrow,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    title,
                    style = LeziTypography.Display.copy(fontSize = 34.sp, lineHeight = 40.sp),
                )
            }
            if (trailing != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = LeziTypography.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
