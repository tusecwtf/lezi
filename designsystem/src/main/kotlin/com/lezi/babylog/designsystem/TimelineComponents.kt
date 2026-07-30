package com.lezi.babylog.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

enum class LeziGlyph { Bottle, Drop, Moon, Toilet, Pin, Plus, Dot }

/**
 * A day-lane mark. Sleep is an [isEvent]=false interval; feed/care are
 * moment events so they render as large tappable dots rather than thin bars.
 *
 * [startMinOfDay] / [endMinOfDay] are **content-axis minutes** from the rail
 * window origin (D−1 00:00 for the three-day rail; day 00:00 for a single-day
 * fill). They are not necessarily clamped to 0…1440.
 *
 * [dayChartCategoryKey] ties the mark to a page-level day-chart category filter
 * (opaque string owned by the feature layer; null = not a selectable day-chart type).
 */
data class TimelineLaneSegment(
    val startMinOfDay: Int,
    val endMinOfDay: Int,
    val colorRole: LeziRecordColorRole,
    /** Short record-type name, e.g. 睡眠 / 配方奶. */
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
    val colorRole: LeziRecordColorRole,
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
    /** Content-axis minute for the now line; null hides it. */
    nowContentMinute: Int? = null,
    markerStyle: Boolean = true,
    selectedCategoryKey: String? = null,
    onCategorySelect: (String?) -> Unit = {},
    viewportStartMinutes: Int = 0,
    viewportDurationMinutes: Int = TimelineAxis.MINUTES_PER_DAY,
    windowGeometry: TimelineWindowGeometry = TimelineWindowGeometry.SingleDay,
    /**
     * Shared horizontal pan for the rail. Invoked with cumulative finger delta
     * (px, positive = right) and axis width from the drag origin; caller owns
     * one viewport for all lanes. Null disables pan (tap-only).
     */
    onHorizontalPan: ((totalDeltaPx: Float, axisLengthPx: Float) -> Unit)? = null,
    /** Clears shared pan-origin when the pointer lifts (after pan or tap). */
    onPanEnd: (() -> Unit)? = null,
) {
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    // Day boundaries: slightly stronger than quarter grid, weaker than now line; no labels.
    val dayBoundaryColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.48f)
    val nowColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f)
    val focusRing = MaterialTheme.colorScheme.primary
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val density = LocalDensity.current
    // Taller lanes for event markers so dots + touch targets are comfortable.
    val laneHeight = trackHeight.coerceAtLeast(48.dp)
    val safeViewportDuration = viewportDurationMinutes.coerceAtLeast(1)
    val viewportEnd = viewportStartMinutes + safeViewportDuration
    // Updated each recomposition so pointerInput need not restart mid-pan.
    val viewportStartState by rememberUpdatedState(viewportStartMinutes)
    val selectedKeyState by rememberUpdatedState(selectedCategoryKey)
    val segmentsState by rememberUpdatedState(segments)
    val onSelectState by rememberUpdatedState(onCategorySelect)
    val onPanState by rememberUpdatedState(onHorizontalPan)
    val onPanEndState by rememberUpdatedState(onPanEnd)
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
                .testTag("timeline_lane_$label")
                .semantics {
                    contentDescription = if (onHorizontalPan != null) {
                        "$label 轨道，点按可按类型筛选明细，横向拖动可窥视邻日"
                    } else {
                        "$label 轨道，点按可按类型筛选明细"
                    }
                }
                .pointerInput(
                    markerStyle,
                    density,
                    safeViewportDuration,
                    onHorizontalPan != null,
                ) {
                    val axisWidth = { size.width.toFloat() }
                    fun hitTestAt(offset: Offset): TimelineLaneSegment? {
                        val start = viewportStartState
                        val segs = segmentsState
                        val contentMin = TimelineAxis.axisPxToContentMinute(
                            axisPx = offset.x,
                            axisLengthPx = axisWidth(),
                            viewportStartMinutes = start,
                            viewportDurationMinutes = safeViewportDuration,
                        )
                        return if (markerStyle) {
                            with(density) {
                                layoutTimelineEventMarkers(
                                    segments = segs,
                                    axisLengthPx = axisWidth(),
                                    clusterWindowMinutes = EVENT_CLUSTER_WINDOW_MINUTES,
                                    slotSpacingPx = EVENT_SLOT_SPACING.toPx(),
                                    edgeInsetPx = EVENT_EDGE_INSET.toPx(),
                                    hitRadiusPx = EVENT_HIT_RADIUS.toPx(),
                                    viewportStartMinutes = start,
                                    viewportDurationMinutes = safeViewportDuration,
                                )
                            }.hitTest(offset.x)
                        } else {
                            segs.asReversed().firstOrNull { seg ->
                                val end = seg.endMinOfDay.coerceAtLeast(seg.startMinOfDay + 1)
                                contentMin in seg.startMinOfDay until end
                            }
                        }
                    }
                    detectTimelineRailGestures(
                        onTap = { offset ->
                            onSelectState(
                                nextCategorySelection(selectedKeyState, hitTestAt(offset)),
                            )
                        },
                        onHorizontalPan = onPanState?.let { pan ->
                            { totalDeltaPx -> pan(totalDeltaPx, axisWidth()) }
                        },
                        onGestureEnd = { onPanEndState?.invoke() },
                    )
                },
        ) {
            val h = size.height
            // Decorative quarter grid of the visible viewport (not wall-clock hours).
            for (i in 1..3) {
                val x = size.width * i / 4f
                drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            }
            // Unlabeled midnight day boundaries (D / D+1 00:00). Under marks; no date text.
            for (boundaryMin in windowGeometry.dayBoundaryMinutes) {
                if (boundaryMin !in viewportStartMinutes..viewportEnd) continue
                val bx = TimelineAxis.contentMinuteToAxisPx(
                    contentMinute = boundaryMin,
                    axisLengthPx = size.width,
                    viewportStartMinutes = viewportStartMinutes,
                    viewportDurationMinutes = safeViewportDuration,
                )
                drawLine(
                    dayBoundaryColor,
                    Offset(bx, 0f),
                    Offset(bx, h),
                    strokeWidth = 1.4f,
                )
            }
            drawLine(
                track,
                Offset(0f, h / 2f),
                Offset(size.width, h / 2f),
                strokeWidth = 1.2f,
            )
            val markerTargetsByIndex = layoutTimelineEventMarkers(
                segments = segments,
                axisLengthPx = size.width,
                clusterWindowMinutes = EVENT_CLUSTER_WINDOW_MINUTES,
                slotSpacingPx = EVENT_SLOT_SPACING.toPx(),
                edgeInsetPx = EVENT_EDGE_INSET.toPx(),
                hitRadiusPx = EVENT_HIT_RADIUS.toPx(),
                viewportStartMinutes = viewportStartMinutes,
                viewportDurationMinutes = safeViewportDuration,
            ).targets.associateBy(TimelineMarkerTarget::sourceIndex)
            val hasSelection = selectedCategoryKey != null
            val drawingIndices = segments.indices.sortedWith(
                compareBy<Int> { markerTargetsByIndex[it] != null }
                    .thenBy { markerTargetsByIndex[it]?.zOrder ?: it },
            )
            drawingIndices.forEach { index ->
                val seg = segments[index]
                val segmentColor = resolveLeziRecordColor(seg.colorRole, darkTheme)
                val highlighted = hasSelection &&
                    seg.dayChartCategoryKey != null &&
                    seg.dayChartCategoryKey == selectedCategoryKey
                // Selection dim wins when a filter is active; otherwise neighbor peeks soften.
                val selectionDimmed = hasSelection && !highlighted
                val neighborDimmed = !windowGeometry.isPrimaryMinute(seg.startMinOfDay)
                val markerTarget = markerTargetsByIndex[index]
                if (markerTarget != null) {
                    val x = markerTarget.centerPx
                    // Skip markers laid out outside the canvas (safety).
                    if (x < -EVENT_HIT_RADIUS.toPx() || x > size.width + EVENT_HIT_RADIUS.toPx()) {
                        return@forEach
                    }
                    val center = Offset(x, h * 0.32f)
                    val radius = when {
                        highlighted -> 11.dp.toPx()
                        selectionDimmed -> 6.5.dp.toPx()
                        neighborDimmed -> 7.dp.toPx()
                        else -> 7.5.dp.toPx()
                    }
                    val fillAlpha = when {
                        highlighted -> 1f
                        selectionDimmed -> 0.35f
                        neighborDimmed -> 0.42f
                        else -> 0.95f
                    }
                    val stemAlpha = when {
                        highlighted -> 0.55f
                        selectionDimmed -> 0.2f
                        neighborDimmed -> 0.28f
                        else -> 0.55f
                    }
                    val coreAlpha = when {
                        selectionDimmed -> 0.35f
                        neighborDimmed -> 0.5f
                        else -> 0.9f
                    }
                    drawLine(
                        color = segmentColor.copy(alpha = stemAlpha),
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
                        color = segmentColor.copy(alpha = fillAlpha),
                        radius = radius,
                        center = center,
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = coreAlpha),
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
                } else if (!seg.isEvent) {
                    // Interval bars: clip to the visible viewport so overnight
                    // sleep can span midnights as one content-axis segment.
                    // Alpha-slice at day bounds so D stays full-strength while
                    // neighbor peeks look dimmed; the datum remains one segment.
                    val segStart = seg.startMinOfDay
                    val segEnd = seg.endMinOfDay.coerceAtLeast(segStart + 1)
                    val visStart = maxOf(segStart, viewportStartMinutes)
                    val visEnd = minOf(segEnd, viewportEnd)
                    if (visEnd <= visStart) return@forEach
                    val barH = if (highlighted) h * 0.72f else h * 0.64f
                    val barTop = (h - barH) / 2f
                    val slices = sleepPaintSlices(
                        visStart = visStart,
                        visEnd = visEnd,
                        dayBoundaryMinutes = windowGeometry.dayBoundaryMinutes,
                    )
                    // Highlight ring once around the whole visible span.
                    if (highlighted) {
                        val fullX = TimelineAxis.contentMinuteToAxisPx(
                            contentMinute = visStart,
                            axisLengthPx = size.width,
                            viewportStartMinutes = viewportStartMinutes,
                            viewportDurationMinutes = safeViewportDuration,
                        )
                        val fullW = size.width *
                            ((visEnd - visStart).coerceAtLeast(1) / safeViewportDuration.toFloat())
                        val barW = fullW.coerceAtLeast(4f)
                        drawRoundRect(
                            color = focusRing.copy(alpha = 0.22f),
                            topLeft = Offset(fullX - 4f, barTop - 4f),
                            size = Size(barW + 8f, barH + 8f),
                            cornerRadius = CornerRadius(10f, 10f),
                        )
                        drawRoundRect(
                            color = focusRing,
                            topLeft = Offset(fullX - 2f, barTop - 2f),
                            size = Size(barW + 4f, barH + 4f),
                            cornerRadius = CornerRadius(8f, 8f),
                            style = Stroke(width = 3f),
                        )
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.9f),
                            topLeft = Offset(fullX - 0.5f, barTop - 0.5f),
                            size = Size(barW + 1f, barH + 1f),
                            cornerRadius = CornerRadius(6f, 6f),
                            style = Stroke(width = 1.5f),
                        )
                    }
                    slices.forEach { slice ->
                        val sliceNeighbor = !windowGeometry.isPrimaryMinute(slice.startMin)
                        val fillAlpha = when {
                            highlighted -> 1f
                            selectionDimmed -> 0.28f
                            sliceNeighbor -> 0.38f
                            else -> 0.9f
                        }
                        val x = TimelineAxis.contentMinuteToAxisPx(
                            contentMinute = slice.startMin,
                            axisLengthPx = size.width,
                            viewportStartMinutes = viewportStartMinutes,
                            viewportDurationMinutes = safeViewportDuration,
                        )
                        val w = size.width *
                            ((slice.endMin - slice.startMin).coerceAtLeast(1) /
                                safeViewportDuration.toFloat())
                        val barW = w.coerceAtLeast(if (slices.size == 1) 4f else 2f)
                        drawRoundRect(
                            color = segmentColor.copy(alpha = fillAlpha),
                            topLeft = Offset(x, barTop),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(6f, 6f),
                        )
                    }
                }
            }
            if (nowContentMinute != null &&
                nowContentMinute in viewportStartMinutes..viewportEnd
            ) {
                val nx = TimelineAxis.contentMinuteToAxisPx(
                    contentMinute = nowContentMinute,
                    axisLengthPx = size.width,
                    viewportStartMinutes = viewportStartMinutes,
                    viewportDurationMinutes = safeViewportDuration,
                )
                drawLine(nowColor, Offset(nx, 0f), Offset(nx, h), strokeWidth = 2.2f)
                drawCircle(nowColor, radius = 4f, center = Offset(nx, 0f))
            }
        }
    }
}

/**
 * Split a visible sleep interval at primary-day midnights so neighbor peeks
 * can paint at reduced alpha while the mark stays one logical segment.
 */
internal fun sleepPaintSlices(
    visStart: Int,
    visEnd: Int,
    dayBoundaryMinutes: List<Int> = TimelineAxis.dayBoundaryContentMinutes(),
): List<SleepPaintSlice> {
    if (visEnd <= visStart) return emptyList()
    val cuts = buildList {
        add(visStart)
        for (boundary in dayBoundaryMinutes) {
            if (boundary in (visStart + 1) until visEnd) add(boundary)
        }
        add(visEnd)
    }
    return cuts.zipWithNext { a, b -> SleepPaintSlice(a, b) }
}

/** One painted sub-span of a continuous sleep bar (content minutes). */
internal data class SleepPaintSlice(val startMin: Int, val endMin: Int)

/**
 * Day time-bar card. Warm and journal share the same horizontal three-lane geometry
 * ([TimelineLane] draw + hit testing). Template differences are limited to card chrome
 * (padding / title / meta density); colors come from the active theme.
 *
 * Content may be a multi-day axis (default three-day window); [viewportStartMinutes]
 * and [viewportDurationMinutes] select the visible slice. Selection is owned by the
 * caller (page state): all marks that share [selectedCategoryKey] highlight together.
 *
 * When [onViewportStartChange] is non-null, horizontal drag pans a **single shared
 * viewport** for all lanes (clamped to content); tap still filters; pan never
 * changes selected day D.
 */
@Composable
fun TimelineRailCard(
    sleep: List<TimelineLaneSegment>,
    feed: List<TimelineLaneSegment>,
    care: List<TimelineLaneSegment>,
    recordCount: Int,
    nowContentMinute: Int?,
    modifier: Modifier = Modifier,
    selectedCategoryKey: String? = null,
    onCategorySelect: (String?) -> Unit = {},
    legend: List<TimelineLegendEntry> = emptyList(),
    viewportStartMinutes: Int = 0,
    viewportDurationMinutes: Int = TimelineAxis.MINUTES_PER_DAY,
    windowGeometry: TimelineWindowGeometry = TimelineWindowGeometry.SingleDay,
    /**
     * When non-null, enables horizontal pan across [windowGeometry]. Caller owns day-keyed viewport
     * state and must clamp via [TimelineAxis.clampViewportStart] / pan helper.
     */
    onViewportStartChange: ((Int) -> Unit)? = null,
    /**
     * Hour labels drawn relative to the content axis. Defaults to a single-day
     * 00/06/12/18/24 fill when the viewport is the classic 24h window.
     */
    hourLabels: List<Pair<Int, String>>? = null,
    /**
     * Optional header titles above the lanes. Product log home passes
     * [titleSecondary] = 「时间轴」; previews may set either. Blank strings ignored.
     */
    titlePrimary: String? = null,
    titleSecondary: String? = null,
) {
    val journal = LeziThemeExt.isJournal
    val contentPadding = if (journal) PaddingValues(10.dp) else PaddingValues(18.dp)
    val sectionGap = if (journal) 6.dp else 10.dp
    val laneGap = if (journal) 6.dp else 8.dp
    val hourLabelStart = if (journal) 32.dp else 34.dp
    val safeViewportDuration = viewportDurationMinutes.coerceAtLeast(1)
    val resolvedHourLabels = hourLabels ?: defaultHourLabels(
        viewportStartMinutes = viewportStartMinutes,
        viewportDurationMinutes = safeViewportDuration,
    )
    val primaryTitle = titlePrimary?.takeIf { it.isNotBlank() }
    val secondaryTitle = titleSecondary?.takeIf { it.isNotBlank() }
    val showTitleBlock = primaryTitle != null || secondaryTitle != null
    // One shared viewport for all lanes: origin fixed for the life of a pan gesture.
    val viewportStartState by rememberUpdatedState(viewportStartMinutes)
    val onViewportChangeState by rememberUpdatedState(onViewportStartChange)
    val panSession = remember { TimelinePanSession() }
    val onHorizontalPan: ((Float, Float) -> Unit)? =
        if (onViewportStartChange != null) {
            { totalDeltaPx, axisLengthPx ->
                val origin = panSession.originStartMinutes
                    ?: viewportStartState.also { panSession.originStartMinutes = it }
                val next = TimelineAxis.panViewportStart(
                    currentStartMinutes = origin,
                    deltaPx = totalDeltaPx,
                    axisLengthPx = axisLengthPx,
                    viewportDurationMinutes = safeViewportDuration,
                    contentDurationMinutes = windowGeometry.contentDurationMinutes,
                )
                onViewportChangeState?.invoke(next)
            }
        } else {
            null
        }
    val onPanEnd: (() -> Unit)? =
        if (onViewportStartChange != null) {
            { panSession.originStartMinutes = null }
        } else {
            null
        }

    val panel: @Composable (@Composable ColumnScope.() -> Unit) -> Unit = { body ->
        if (journal) {
            LeziSurfacePanel(
                modifier = modifier.fillMaxWidth(),
                contentPadding = contentPadding,
                bottomBand = true,
                content = body,
            )
        } else {
            LeziCard(
                modifier = modifier.fillMaxWidth(),
                contentPadding = contentPadding,
                content = body,
            )
        }
    }
    panel {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showTitleBlock) {
                if (journal) {
                    Text(
                        primaryTitle ?: secondaryTitle.orEmpty(),
                        style = LeziTypography.Label,
                    )
                } else {
                    Column {
                        if (primaryTitle != null) {
                            Text(
                                primaryTitle,
                                style = LeziTypography.Eyebrow,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (secondaryTitle != null) {
                            Text(secondaryTitle, style = LeziTypography.TitleSm)
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                if (journal) "$recordCount 条" else "$recordCount 条记录",
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(sectionGap))
        TimelineHourLabels(
            labels = resolvedHourLabels,
            viewportStartMinutes = viewportStartMinutes,
            viewportDurationMinutes = safeViewportDuration,
            onHorizontalPan = onHorizontalPan,
            onPanEnd = onPanEnd,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = hourLabelStart),
        )
        Spacer(Modifier.height(4.dp))
        TimelineLane(
            label = "睡眠",
            segments = sleep,
            nowContentMinute = nowContentMinute,
            markerStyle = false,
            selectedCategoryKey = selectedCategoryKey,
            onCategorySelect = onCategorySelect,
            viewportStartMinutes = viewportStartMinutes,
            viewportDurationMinutes = safeViewportDuration,
            windowGeometry = windowGeometry,
            onHorizontalPan = onHorizontalPan,
            onPanEnd = onPanEnd,
        )
        Spacer(Modifier.height(laneGap))
        TimelineLane(
            label = "喂养",
            segments = feed,
            nowContentMinute = nowContentMinute,
            markerStyle = true,
            selectedCategoryKey = selectedCategoryKey,
            onCategorySelect = onCategorySelect,
            viewportStartMinutes = viewportStartMinutes,
            viewportDurationMinutes = safeViewportDuration,
            windowGeometry = windowGeometry,
            onHorizontalPan = onHorizontalPan,
            onPanEnd = onPanEnd,
        )
        Spacer(Modifier.height(laneGap))
        TimelineLane(
            label = "护理",
            segments = care,
            nowContentMinute = nowContentMinute,
            markerStyle = true,
            selectedCategoryKey = selectedCategoryKey,
            onCategorySelect = onCategorySelect,
            viewportStartMinutes = viewportStartMinutes,
            viewportDurationMinutes = safeViewportDuration,
            windowGeometry = windowGeometry,
            onHorizontalPan = onHorizontalPan,
            onPanEnd = onPanEnd,
        )
        if (legend.isNotEmpty()) {
            Spacer(Modifier.height(sectionGap))
            TimelineLegendRow(
                items = legend,
                selectedCategoryKey = selectedCategoryKey,
                onCategorySelect = onCategorySelect,
                modifier = Modifier.padding(start = hourLabelStart),
            )
        }
    }
}

/** Holds the viewport start at the beginning of a multi-lane pan gesture. */
private class TimelinePanSession {
    var originStartMinutes: Int? = null
}

@Composable
private fun TimelineHourLabels(
    labels: List<Pair<Int, String>>,
    viewportStartMinutes: Int,
    viewportDurationMinutes: Int,
    modifier: Modifier = Modifier,
    onHorizontalPan: ((totalDeltaPx: Float, axisLengthPx: Float) -> Unit)? = null,
    onPanEnd: (() -> Unit)? = null,
) {
    val onPanState by rememberUpdatedState(onHorizontalPan)
    val onPanEndState by rememberUpdatedState(onPanEnd)
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .height(14.dp)
            .fillMaxWidth()
            .then(
                if (onHorizontalPan != null) {
                    Modifier.pointerInput(viewportDurationMinutes) {
                        detectTimelineRailGestures(
                            onTap = {},
                            onHorizontalPan = { totalDeltaPx ->
                                onPanState?.invoke(totalDeltaPx, size.width.toFloat())
                            },
                            onGestureEnd = { onPanEndState?.invoke() },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        labels.forEach { (contentMinute, label) ->
            val fraction =
                (contentMinute - viewportStartMinutes).toFloat() / viewportDurationMinutes
            if (fraction < -0.02f || fraction > 1.02f) return@forEach
            val x = maxWidth * fraction.coerceIn(0f, 1f)
            Text(
                label,
                style = LeziTypography.Meta.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = x),
                maxLines = 1,
            )
        }
    }
}

/**
 * Tap vs horizontal-pan disambiguation for the day rail.
 *
 * - Movement under touch slop → [onTap] (category filter).
 * - Horizontal past slop → [onHorizontalPan] with **cumulative** finger delta
 *   from down (positive = right); horizontal motion is consumed so outer
 *   LazyColumn / pull-to-refresh do not thrash.
 * - Vertical past slop → do not consume; list scroll wins.
 */
internal suspend fun PointerInputScope.detectTimelineRailGestures(
    onTap: (Offset) -> Unit,
    onHorizontalPan: ((totalDeltaPx: Float) -> Unit)?,
    onGestureEnd: (() -> Unit)? = null,
) {
    if (onHorizontalPan == null) {
        detectTapGestures(onTap = onTap)
        return
    }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val touchSlop = viewConfiguration.touchSlop
        var totalX = 0f
        var totalY = 0f
        var pastSlop = false
        var isHorizontal = false
        val pointerId = down.id
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (!change.pressed) {
                    if (!pastSlop) {
                        onTap(down.position)
                    }
                    break
                }
                val delta = change.positionChange()
                if (!pastSlop) {
                    totalX += delta.x
                    totalY += delta.y
                    val distSq = totalX * totalX + totalY * totalY
                    if (distSq >= touchSlop * touchSlop) {
                        pastSlop = true
                        isHorizontal = abs(totalX) >= abs(totalY)
                        if (isHorizontal) {
                            change.consume()
                            onHorizontalPan(totalX)
                        }
                        // Vertical: leave unconsumed so LazyColumn can scroll.
                    }
                } else if (isHorizontal) {
                    totalX += delta.x
                    change.consume()
                    onHorizontalPan(totalX)
                }
            }
        } finally {
            onGestureEnd?.invoke()
        }
    }
}

/**
 * Default hour tick labels for the visible viewport.
 * Single-day fill keeps classic 00/06/12/18/24; multi-day peeks label the
 * primary day's same ticks so D remains the readable spine.
 */
internal fun defaultHourLabels(
    viewportStartMinutes: Int,
    viewportDurationMinutes: Int,
): List<Pair<Int, String>> {
    // Prefer primary-day ticks when the three-day default viewport is in use.
    val primaryTicks = listOf(0, 6, 12, 18, 24).map { hour ->
        val content = TimelineAxis.PRIMARY_DAY_START_MINUTES + hour * 60
        content to hour.toString().padStart(2, '0')
    }
    val primaryVisible = primaryTicks.any { (m, _) ->
        m in viewportStartMinutes..(viewportStartMinutes + viewportDurationMinutes)
    }
    if (primaryVisible && viewportDurationMinutes > TimelineAxis.MINUTES_PER_DAY) {
        return primaryTicks
    }
    if (viewportStartMinutes == 0 && viewportDurationMinutes == TimelineAxis.MINUTES_PER_DAY) {
        return listOf(0, 6, 12, 18, 24).map { it * 60 to it.toString().padStart(2, '0') }
    }
    // Generic: label every 6h content boundary inside the viewport.
    val startHour = (viewportStartMinutes / 60)
    val endMinute = viewportStartMinutes + viewportDurationMinutes
    val labels = mutableListOf<Pair<Int, String>>()
    var hour = (startHour / 6) * 6
    while (hour * 60 <= endMinute) {
        val m = hour * 60
        if (m in viewportStartMinutes..endMinute) {
            labels += m to (hour % 24).toString().padStart(2, '0')
        }
        hour += 6
    }
    return labels.ifEmpty {
        listOf(viewportStartMinutes to "00")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineLegendRow(
    items: List<TimelineLegendEntry>,
    selectedCategoryKey: String?,
    onCategorySelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEach { item ->
            val itemColor = resolveLeziRecordColor(item.colorRole, darkTheme)
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
                    .heightIn(min = LeziSpacing.Touch)
                    .selectable(
                        selected = selected,
                        role = Role.Button,
                        onClick = {
                            onCategorySelect(
                                if (selected) null else item.key,
                            )
                        },
                    )
                    .semantics { contentDescription = description }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                if (item.isBar) {
                    Box(
                        Modifier
                            .width(12.dp)
                            .height(7.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(itemColor),
                    )
                } else {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(itemColor),
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
