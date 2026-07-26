package com.lezi.babylog.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class LeziGlyph { Bottle, Drop, Moon, Toilet, Pin, Plus, Dot }

/**
 * A day-lane mark. Sleep is an [isEvent]=false interval; feed/care are
 * moment events so they render as large tappable dots rather than thin bars.
 *
 * [dayChartCategoryKey] ties the mark to a page-level day-chart category filter
 * (opaque string owned by the feature layer; null = not a selectable day-chart type).
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
    /**
     * Opaque day-chart category key for type-level selection/highlight.
     * Null marks (e.g. 吸奶 / 体温) cannot be selected as a filter target.
     */
    val dayChartCategoryKey: String? = null,
)

/** Legend entry for a day-chart category; keys match [TimelineLaneSegment.dayChartCategoryKey]. */
data class TimelineLegendEntry(
    val key: String,
    val label: String,
    val color: Color,
    /** Sleep uses a short bar swatch; feed/care use dots. */
    val isBar: Boolean = false,
)

private const val EVENT_CLUSTER_WINDOW_MINUTES = 12
private val EVENT_SLOT_SPACING = 5.dp
private val EVENT_EDGE_INSET = 10.dp
private val EVENT_HIT_RADIUS = 14.dp

/**
 * Resolve a tap on a segment into the next category selection.
 * Non-day-chart marks and blank hits clear; same key toggles off; other key switches.
 */
internal fun nextCategorySelection(
    selectedCategoryKey: String?,
    hit: TimelineLaneSegment?,
): String? {
    val hitKey = hit?.dayChartCategoryKey ?: return null
    return if (hitKey == selectedCategoryKey) null else hitKey
}

@Composable
fun TimelineLane(
    label: String,
    segments: List<TimelineLaneSegment>,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 34.dp,
    nowMinOfDay: Int? = null,
    markerStyle: Boolean = true,
    selectedCategoryKey: String? = null,
    onCategorySelect: (String?) -> Unit = {},
) {
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val nowColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f)
    val focusRing = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    // Taller lanes for event markers so dots + touch targets are comfortable.
    val laneHeight = trackHeight.coerceAtLeast(48.dp)
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = LeziTypography.Meta.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
            maxLines = 1,
        )
        Canvas(
            Modifier
                .weight(1f)
                .height(laneHeight)
                .semantics {
                    contentDescription = "$label 轨道，点按可按类型筛选明细"
                }
                .pointerInput(segments, markerStyle, selectedCategoryKey, density) {
                    detectTapGestures { offset ->
                        val total = 24f * 60f
                        val min = ((offset.x / size.width) * total).toInt().coerceIn(0, 24 * 60)
                        val hit = if (markerStyle) {
                            with(density) {
                                layoutTimelineEventMarkers(
                                    segments = segments,
                                    axisLengthPx = size.width.toFloat(),
                                    clusterWindowMinutes = EVENT_CLUSTER_WINDOW_MINUTES,
                                    slotSpacingPx = EVENT_SLOT_SPACING.toPx(),
                                    edgeInsetPx = EVENT_EDGE_INSET.toPx(),
                                    hitRadiusPx = EVENT_HIT_RADIUS.toPx(),
                                )
                            }.hitTest(offset.x)
                        } else {
                            segments.asReversed().firstOrNull { seg ->
                                val end = seg.endMinOfDay.coerceAtLeast(seg.startMinOfDay + 1)
                                min in seg.startMinOfDay until end
                            }
                        }
                        onCategorySelect(nextCategorySelection(selectedCategoryKey, hit))
                    }
                },
        ) {
            val h = size.height
            for (i in 1..3) {
                val x = size.width * i / 4f
                drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            }
            drawLine(
                track,
                Offset(0f, h / 2f),
                Offset(size.width, h / 2f),
                strokeWidth = 1.2f,
            )
            val total = 24f * 60f
            val markerTargetsByIndex = layoutTimelineEventMarkers(
                segments = segments,
                axisLengthPx = size.width,
                clusterWindowMinutes = EVENT_CLUSTER_WINDOW_MINUTES,
                slotSpacingPx = EVENT_SLOT_SPACING.toPx(),
                edgeInsetPx = EVENT_EDGE_INSET.toPx(),
                hitRadiusPx = EVENT_HIT_RADIUS.toPx(),
            ).targets.associateBy(TimelineMarkerTarget::sourceIndex)
            val hasSelection = selectedCategoryKey != null
            val drawingIndices = segments.indices.sortedWith(
                compareBy<Int> { markerTargetsByIndex[it] != null }
                    .thenBy { markerTargetsByIndex[it]?.zOrder ?: it },
            )
            drawingIndices.forEach { index ->
                val seg = segments[index]
                val highlighted = hasSelection &&
                    seg.dayChartCategoryKey != null &&
                    seg.dayChartCategoryKey == selectedCategoryKey
                val dimmed = hasSelection && !highlighted
                val markerTarget = markerTargetsByIndex[index]
                if (markerTarget != null) {
                    val x = markerTarget.centerPx
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
                    drawLine(
                        color = seg.color.copy(alpha = if (dimmed) 0.2f else 0.55f),
                        start = Offset(x, h * 0.18f),
                        end = Offset(x, h / 2f),
                        strokeWidth = if (highlighted) 3f else 2.2f,
                    )
                    if (highlighted) {
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

/**
 * Day time-bar card. Selection is owned by the caller (page state): all marks that share
 * [selectedCategoryKey] highlight together; legend and tip use the same key.
 */
@Composable
fun TimelineRailCard(
    sleep: List<TimelineLaneSegment>,
    feed: List<TimelineLaneSegment>,
    care: List<TimelineLaneSegment>,
    recordCount: Int,
    nowMinOfDay: Int?,
    modifier: Modifier = Modifier,
    selectedCategoryKey: String? = null,
    onCategorySelect: (String?) -> Unit = {},
    legend: List<TimelineLegendEntry> = emptyList(),
    tipLabel: String? = null,
    tipCount: Int = 0,
) {
    if (LeziThemeExt.isJournal) {
        JournalTimelineRail(
            sleep = sleep,
            feed = feed,
            care = care,
            recordCount = recordCount,
            nowMinOfDay = nowMinOfDay,
            selectedCategoryKey = selectedCategoryKey,
            onCategorySelect = onCategorySelect,
            legend = legend,
            tipLabel = tipLabel,
            tipCount = tipCount,
            modifier = modifier,
        )
        return
    }
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
        TimelineInteractionHint()
        Spacer(Modifier.height(10.dp))
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
            selectedCategoryKey = selectedCategoryKey,
            onCategorySelect = onCategorySelect,
        )
        Spacer(Modifier.height(8.dp))
        TimelineLane(
            label = "喂养",
            segments = feed,
            nowMinOfDay = nowMinOfDay,
            markerStyle = true,
            selectedCategoryKey = selectedCategoryKey,
            onCategorySelect = onCategorySelect,
        )
        Spacer(Modifier.height(8.dp))
        TimelineLane(
            label = "护理",
            segments = care,
            nowMinOfDay = nowMinOfDay,
            markerStyle = true,
            selectedCategoryKey = selectedCategoryKey,
            onCategorySelect = onCategorySelect,
        )
        TimelineCategoryTip(
            label = tipLabel,
            count = tipCount,
            onDismiss = { onCategorySelect(null) },
        )
        if (legend.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            TimelineLegendRow(
                items = legend,
                selectedCategoryKey = selectedCategoryKey,
                onCategorySelect = onCategorySelect,
                modifier = Modifier.padding(start = 34.dp),
            )
        }
    }
}

@Composable
private fun TimelineLegendRow(
    items: List<TimelineLegendEntry>,
    selectedCategoryKey: String?,
    onCategorySelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { item ->
            val selected = item.key == selectedCategoryKey
            val description = if (selected) {
                "已选${item.label}，再点取消筛选"
            } else {
                "筛选${item.label}"
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .then(
                        if (selected) {
                            Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f))
                                .border(
                                    width = 1.5.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(50),
                                )
                        } else {
                            Modifier
                        },
                    )
                    .clickable {
                        onCategorySelect(
                            if (selected) null else item.key,
                        )
                    }
                    .semantics { contentDescription = description }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                if (item.isBar) {
                    Box(
                        Modifier
                            .width(12.dp)
                            .height(7.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(item.color),
                    )
                } else {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(item.color),
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    item.label,
                    style = LeziTypography.Meta.copy(fontSize = 10.sp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** Type-level filter tip: category name · record count · tap to clear. */
@Composable
private fun TimelineCategoryTip(
    label: String?,
    count: Int,
    onDismiss: () -> Unit,
) {
    if (label.isNullOrBlank()) return
    Spacer(Modifier.height(10.dp))
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDismiss)
            .semantics {
                contentDescription = "已筛选$label，$count 条，再点取消筛选"
            },
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
                Text(
                    "$label · $count 条",
                    style = LeziTypography.BodyStrong,
                )
                Text(
                    "取消",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "再点取消筛选",
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun JournalTimelineRail(
    sleep: List<TimelineLaneSegment>,
    feed: List<TimelineLaneSegment>,
    care: List<TimelineLaneSegment>,
    recordCount: Int,
    nowMinOfDay: Int?,
    selectedCategoryKey: String?,
    onCategorySelect: (String?) -> Unit,
    legend: List<TimelineLegendEntry>,
    tipLabel: String?,
    tipCount: Int,
    modifier: Modifier,
) {
    val grid = LeziThemeExt.colors.chartGrid
    val danger = LeziThemeExt.colors.danger
    val focusRing = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
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
        TimelineInteractionHint()
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
                    .semantics { contentDescription = "0到24小时记录轨道，点按可按类型筛选明细" }
                    .pointerInput(sleep, feed, care, selectedCategoryKey, density) {
                        detectTapGestures { offset ->
                            val laneIndex = ((offset.x / size.width) * 3f).toInt().coerceIn(0, 2)
                            val min = ((offset.y / size.height) * 1440f).toInt().coerceIn(0, 1440)
                            val lane = lanes[laneIndex]
                            val intervalHit = lane.firstOrNull { seg ->
                                if (seg.isEvent) {
                                    false
                                } else {
                                    val end = seg.endMinOfDay.coerceAtLeast(seg.startMinOfDay + 1)
                                    min in seg.startMinOfDay until end
                                }
                            }
                            val eventHit = with(density) {
                                layoutTimelineEventMarkers(
                                    segments = lane,
                                    axisLengthPx = size.height.toFloat(),
                                    clusterWindowMinutes = EVENT_CLUSTER_WINDOW_MINUTES,
                                    slotSpacingPx = EVENT_SLOT_SPACING.toPx(),
                                    edgeInsetPx = EVENT_EDGE_INSET.toPx(),
                                    hitRadiusPx = EVENT_HIT_RADIUS.toPx(),
                                )
                            }.hitTest(offset.y)
                            val hit = intervalHit ?: eventHit
                            onCategorySelect(nextCategorySelection(selectedCategoryKey, hit))
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
                val hasSelection = selectedCategoryKey != null
                lanes.forEachIndexed { laneIndex, segments ->
                    val laneWidth = size.width / 3f
                    val laneLeft = laneIndex * laneWidth
                    val markerTargetsByIndex = layoutTimelineEventMarkers(
                        segments = segments,
                        axisLengthPx = size.height,
                        clusterWindowMinutes = EVENT_CLUSTER_WINDOW_MINUTES,
                        slotSpacingPx = EVENT_SLOT_SPACING.toPx(),
                        edgeInsetPx = EVENT_EDGE_INSET.toPx(),
                        hitRadiusPx = EVENT_HIT_RADIUS.toPx(),
                    ).targets.associateBy(TimelineMarkerTarget::sourceIndex)
                    val drawingIndices = segments.indices.sortedWith(
                        compareBy<Int> { markerTargetsByIndex[it] != null }
                            .thenBy { markerTargetsByIndex[it]?.zOrder ?: it },
                    )
                    drawingIndices.forEach { segmentIndex ->
                        val segment = segments[segmentIndex]
                        val highlighted = hasSelection &&
                            segment.dayChartCategoryKey != null &&
                            segment.dayChartCategoryKey == selectedCategoryKey
                        val dimmed = hasSelection && !highlighted
                        val markerTarget = markerTargetsByIndex[segmentIndex]
                        if (markerTarget != null) {
                            val y = markerTarget.centerPx
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
        if (legend.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            TimelineLegendRow(
                items = legend,
                selectedCategoryKey = selectedCategoryKey,
                onCategorySelect = onCategorySelect,
                modifier = Modifier.padding(start = 32.dp),
            )
        }
        TimelineCategoryTip(
            label = tipLabel,
            count = tipCount,
            onDismiss = { onCategorySelect(null) },
        )
    }
}

@Composable
private fun TimelineInteractionHint() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("点选类型", style = LeziTypography.Meta)
            Text("筛选明细", style = LeziTypography.Meta)
        }
    }
}
