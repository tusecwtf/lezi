package com.lezi.babylog.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Uncertain sleep is a dashed stroke; the intervals never change with the frame. */
private val UncertainSleepDash: PathEffect by lazy {
    PathEffect.dashPathEffect(floatArrayOf(7f, 5f), 0f)
}

/**
 * A day-lane mark. Sleep is an [isEvent]=false interval; feed/care are
 * moment events so they render as large tappable dots rather than thin bars.
 *
 * [startMs] / [endMs] are **absolute epoch milliseconds**. The rail maps them
 * with `(t − viewportStart) / duration × width` and does not hold a calendar
 * origin.
 *
 * [dayChartCategoryKey] ties the mark to a page-level day-chart category filter
 * (opaque string owned by the feature layer; null = not a selectable day-chart type).
 */
data class TimelineLaneSegment(
    val startMs: Long,
    val endMs: Long,
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
    /**
     * Instant where an open-sleep bar switches from solid (known) to a dashed
     * uncertain tail. Null means the whole interval is certain.
     */
    val uncertainFromMs: Long? = null,
)

/** Legend entry for a day-chart category; keys match [TimelineLaneSegment.dayChartCategoryKey]. */
data class TimelineLegendEntry(
    val key: String,
    val label: String,
    val colorRole: LeziRecordColorRole,
    /** Sleep uses a short bar swatch; feed/care use dots. */
    val isBar: Boolean = false,
)

/** One per-frame rail delta measured against the rail width at drag time. */
data class TimelinePanGesture(
    val deltaPx: Float,
    val axisLengthPx: Float,
)

private const val EVENT_CLUSTER_WINDOW_MS = 12L * 60 * 1000
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
    /**
     * Requested lane height. Values below 48dp are raised (event dots need room),
     * so the effective floor is 48dp — pass a taller value to grow the lanes.
     */
    trackHeight: Dp = 48.dp,
    /** Absolute instant for the now line; null hides it. */
    nowMs: Long? = null,
    markerStyle: Boolean = true,
    selectedCategoryKey: String? = null,
    onCategorySelect: (String?) -> Unit = {},
    viewportStartMs: Long = 0L,
    viewportDurationMs: Long = TimelineAxis.DEFAULT_VIEWPORT_DURATION_MS,
    dayBoundariesMs: List<Long> = emptyList(),
    primaryRangeMs: LongRange = LongRange.EMPTY,
    /**
     * Shared horizontal pan for the rail. Invoked with this frame's finger delta
     * (px, positive = right) and axis width; return consumed pixels (0 at the
     * now clamp). Caller owns one viewport for all lanes. Null disables pan.
     * Pan end/cancel stay on [TimelineRailCard]'s shared scroll session.
     */
    onHorizontalPan: ((TimelinePanGesture) -> Float)? = null,
) {
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    // Midnight day bounds: stronger than the now line so cross-day is obvious.
    val dayBoundaryColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    val dayBoundaryHalo = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val nowColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f)
    val focusRing = MaterialTheme.colorScheme.primary
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val density = LocalDensity.current
    // Taller lanes for event markers so dots + touch targets are comfortable.
    val laneHeight = trackHeight.coerceAtLeast(48.dp)
    val safeViewportDuration = viewportDurationMs.coerceAtLeast(1L)
    val viewportEnd = viewportStartMs + safeViewportDuration
    val windowGeometry = TimelineWindowGeometry(
        dayBoundariesMs = dayBoundariesMs.distinct().sorted(),
        primaryRangeMs = primaryRangeMs,
    )
    // Updated each recomposition so pointerInput need not restart mid-pan.
    val viewportStartState by rememberUpdatedState(viewportStartMs)
    val selectedKeyState by rememberUpdatedState(selectedCategoryKey)
    val segmentsState by rememberUpdatedState(segments)
    val onSelectState by rememberUpdatedState(onCategorySelect)
    val scrollSession = LocalTimelineRailScrollSession.current
    val canPan = scrollSession != null || onHorizontalPan != null
    // Category highlight settles on the Fast tier (motion-polish ticket 03);
    // captured here because the LaunchedEffect body is not @Composable.
    val highlightFastMs = leziMotionMillis(LeziMotion.Fast)
    // Animated selection highlight: per-category 0→1 progress so dot radius,
    // alpha, and the focus ring ease instead of jumping on tap.
    val highlightAnims = remember { mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>() }
    segments.mapNotNull { it.dayChartCategoryKey }.distinct().forEach { key ->
        val anim = highlightAnims.getOrPut(key) { Animatable(0f) }
        LaunchedEffect(key, selectedCategoryKey) {
            anim.animateTo(
                if (key == selectedCategoryKey) 1f else 0f,
                animationSpec = tween(highlightFastMs),
            )
        }
    }
    val selectionLevel by animateFloatAsState(
        targetValue = if (selectedCategoryKey != null) 1f else 0f,
        animationSpec = tween(highlightFastMs),
        label = "laneSelectionDim",
    )
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = if (LeziThemeExt.isElder) {
                LeziThemeExt.typography.Meta
            } else {
                LeziTypography.Micro
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (LeziThemeExt.isElder) {
                Modifier.widthIn(min = 44.dp)
            } else {
                Modifier.width(44.dp)
            },
            maxLines = if (LeziThemeExt.isElder) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(
            Modifier
                .weight(1f)
                .height(laneHeight)
                .testTag("timeline_lane_$label")
                .semantics {
                    contentDescription = if (canPan) {
                        "$label 轨道，点按可按类型筛选明细，横向拖动可连续浏览日期"
                    } else {
                        "$label 轨道，点按可按类型筛选明细"
                    }
                }
                .timelineRailScrollable(scrollSession)
                .pointerInput(
                    markerStyle,
                    density,
                    safeViewportDuration,
                ) {
                    val axisWidth = { size.width.toFloat() }
                    fun hitTestAt(offset: Offset): TimelineLaneSegment? {
                        val start = viewportStartState
                        val segs = segmentsState
                        val instant = TimelineAxis.axisPxToInstant(
                            axisPx = offset.x,
                            axisLengthPx = axisWidth(),
                            viewportStartMs = start,
                            viewportDurationMs = safeViewportDuration,
                        )
                        return if (markerStyle) {
                            with(density) {
                                layoutTimelineEventMarkers(
                                    segments = segs,
                                    axisLengthPx = axisWidth(),
                                    clusterWindowMs = EVENT_CLUSTER_WINDOW_MS,
                                    slotSpacingPx = EVENT_SLOT_SPACING.toPx(),
                                    edgeInsetPx = EVENT_EDGE_INSET.toPx(),
                                    hitRadiusPx = EVENT_HIT_RADIUS.toPx(),
                                    viewportStartMs = start,
                                    viewportDurationMs = safeViewportDuration,
                                )
                            }.hitTest(offset.x)
                        } else {
                            segs.asReversed().firstOrNull { seg ->
                                val end = seg.endMs.coerceAtLeast(seg.startMs + 1)
                                instant in seg.startMs until end
                            }
                        }
                    }
                    detectTapGestures { offset ->
                        onSelectState(
                            nextCategorySelection(selectedKeyState, hitTestAt(offset)),
                        )
                    }
                }
                .drawWithCache {
                    // Cache phase: marker clustering + draw ordering depend only
                    // on segments, size, and the viewport — never on the animated
                    // highlight levels — so they rebuild on pan/resize but are
                    // skipped on every tap-highlight animation frame.
                    // Interval rails (markerStyle = false) have no event markers;
                    // skip the cluster layout and paint segments in source order.
                    val markerTargetsByIndex: Map<Int, TimelineMarkerTarget>
                    val drawingIndices: Iterable<Int>
                    if (markerStyle) {
                        val laidOut = layoutTimelineEventMarkers(
                            segments = segments,
                            axisLengthPx = size.width,
                            clusterWindowMs = EVENT_CLUSTER_WINDOW_MS,
                            slotSpacingPx = EVENT_SLOT_SPACING.toPx(),
                            edgeInsetPx = EVENT_EDGE_INSET.toPx(),
                            hitRadiusPx = EVENT_HIT_RADIUS.toPx(),
                            viewportStartMs = viewportStartMs,
                            viewportDurationMs = safeViewportDuration,
                        ).targets.associateBy(TimelineMarkerTarget::sourceIndex)
                        markerTargetsByIndex = laidOut
                        drawingIndices = segments.indices.sortedWith(
                            compareBy<Int> { markerTargetsByIndex[it] != null }
                                .thenBy { markerTargetsByIndex[it]?.zOrder ?: it },
                        )
                    } else {
                        markerTargetsByIndex = emptyMap()
                        drawingIndices = segments.indices
                    }
                    // Sleep slices depend only on the visible span, day cuts, and
                    // the uncertain boundary — not on highlight animation frames.
                    val sleepSlicesByIndex = buildMap {
                        segments.forEachIndexed { index, seg ->
                            if (seg.isEvent) return@forEachIndexed
                            val segStart = seg.startMs
                            val segEnd = seg.endMs.coerceAtLeast(segStart + 1)
                            val visStart = maxOf(segStart, viewportStartMs)
                            val visEnd = minOf(segEnd, viewportEnd)
                            if (visEnd <= visStart) return@forEachIndexed
                            put(
                                index,
                                sleepPaintSlices(
                                    visStart = visStart,
                                    visEnd = visEnd,
                                    dayBoundariesMs = windowGeometry.dayBoundariesMs,
                                    uncertainFromMs = seg.uncertainFromMs,
                                ),
                            )
                        }
                    }
                    onDrawBehind {
                        val h = size.height
            // Midnight day boundaries (D / D+1 00:00). Under marks. Date text
            // lives in the hour-label row so it can win overlap against ticks.
            for (boundaryMs in windowGeometry.dayBoundariesMs) {
                if (boundaryMs !in viewportStartMs..viewportEnd) continue
                val bx = TimelineAxis.instantToAxisPx(
                    instantMs = boundaryMs,
                    axisLengthPx = size.width,
                    viewportStartMs = viewportStartMs,
                    viewportDurationMs = safeViewportDuration,
                )
                drawLine(
                    dayBoundaryHalo,
                    Offset(bx, 0f),
                    Offset(bx, h),
                    strokeWidth = 5f,
                )
                drawLine(
                    dayBoundaryColor,
                    Offset(bx, 0f),
                    Offset(bx, h),
                    strokeWidth = 2.4f,
                )
            }
            drawLine(
                track,
                Offset(0f, h / 2f),
                Offset(size.width, h / 2f),
                strokeWidth = 1.2f,
            )
                        val hasSelection = selectedCategoryKey != null
                        drawingIndices.forEach { index ->
                val seg = segments[index]
                val segmentColor = resolveLeziRecordColor(seg.colorRole, darkTheme)
                val highlighted = hasSelection &&
                    seg.dayChartCategoryKey != null &&
                    seg.dayChartCategoryKey == selectedCategoryKey
                // Selection dim wins when a filter is active; otherwise neighbor peeks soften.
                val selectionDimmed = hasSelection && !highlighted
                val neighborDimmed = !windowGeometry.isPrimaryInstant(seg.startMs)
                val markerTarget = markerTargetsByIndex[index]
                if (markerTarget != null) {
                    val x = markerTarget.centerPx
                    // Skip markers laid out outside the canvas (safety).
                    if (x < -EVENT_HIT_RADIUS.toPx() || x > size.width + EVENT_HIT_RADIUS.toPx()) {
                        return@forEach
                    }
                    val center = Offset(x, h * 0.32f)
                    // Animated levels: highlight eases 0→1 for the selected category;
                    // dim eases in for everything else while a filter is active.
                    val highlightLevel = seg.dayChartCategoryKey
                        ?.let { highlightAnims[it]?.value }
                        ?: 0f
                    val dimLevel = selectionLevel * (1f - highlightLevel)
                    val baseRadius = if (neighborDimmed) 7.dp.toPx() else 7.5.dp.toPx()
                    val radius = baseRadius +
                        (11.dp.toPx() - baseRadius) * highlightLevel +
                        (6.5.dp.toPx() - baseRadius) * dimLevel
                    val baseFill = if (neighborDimmed) 0.42f else 0.95f
                    val fillAlpha = baseFill +
                        (1f - baseFill) * highlightLevel +
                        (0.35f - baseFill) * dimLevel
                    val baseStem = if (neighborDimmed) 0.28f else 0.55f
                    val stemAlpha = baseStem +
                        (0.55f - baseStem) * highlightLevel +
                        (0.2f - baseStem) * dimLevel
                    val baseCore = if (neighborDimmed) 0.5f else 0.9f
                    val coreAlpha = baseCore +
                        (0.9f - baseCore) * highlightLevel +
                        (0.35f - baseCore) * dimLevel
                    drawLine(
                        color = segmentColor.copy(alpha = stemAlpha),
                        start = Offset(x, h * 0.18f),
                        end = Offset(x, h / 2f),
                        strokeWidth = 2.2f + 0.8f * highlightLevel,
                    )
                    if (highlightLevel > 0f) {
                        drawCircle(
                            color = focusRing.copy(alpha = 0.28f * highlightLevel),
                            radius = radius + 7f,
                            center = center,
                        )
                        drawCircle(
                            color = focusRing.copy(alpha = 0.45f * highlightLevel),
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
                    if (highlightLevel > 0f) {
                        drawCircle(
                            color = focusRing.copy(alpha = highlightLevel),
                            radius = radius + 2.5f,
                            center = center,
                            style = Stroke(width = 3f),
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = highlightLevel),
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
                    val slices = sleepSlicesByIndex[index] ?: return@forEach
                    val visStart = slices.first().startMs
                    val visEnd = slices.last().endMs
                    val barH = if (highlighted) h * 0.72f else h * 0.64f
                    val barTop = (h - barH) / 2f
                    // Highlight ring once around the whole visible span.
                    if (highlighted) {
                        val fullX = TimelineAxis.instantToAxisPx(
                            instantMs = visStart,
                            axisLengthPx = size.width,
                            viewportStartMs = viewportStartMs,
                            viewportDurationMs = safeViewportDuration,
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
                        val sliceNeighbor = !windowGeometry.isPrimaryInstant(slice.startMs)
                        val fillAlpha = when {
                            highlighted -> 1f
                            selectionDimmed -> 0.28f
                            sliceNeighbor -> 0.38f
                            else -> 0.9f
                        }
                        val x = TimelineAxis.instantToAxisPx(
                            instantMs = slice.startMs,
                            axisLengthPx = size.width,
                            viewportStartMs = viewportStartMs,
                            viewportDurationMs = safeViewportDuration,
                        )
                        val w = size.width *
                            ((slice.endMs - slice.startMs).coerceAtLeast(1) /
                                safeViewportDuration.toFloat())
                        val barW = w.coerceAtLeast(if (slices.size == 1) 4f else 2f)
                        val topLeft = Offset(x, barTop)
                        val barSize = Size(barW, barH)
                        val corners = CornerRadius(6f, 6f)
                        if (slice.uncertain) {
                            drawRoundRect(
                                color = segmentColor.copy(alpha = fillAlpha * 0.28f),
                                topLeft = topLeft,
                                size = barSize,
                                cornerRadius = corners,
                            )
                            drawRoundRect(
                                color = segmentColor.copy(alpha = fillAlpha * 0.9f),
                                topLeft = topLeft,
                                size = barSize,
                                cornerRadius = corners,
                                style = Stroke(
                                    width = 1.8f,
                                    pathEffect = UncertainSleepDash,
                                ),
                            )
                        } else {
                            drawRoundRect(
                                color = segmentColor.copy(alpha = fillAlpha),
                                topLeft = topLeft,
                                size = barSize,
                                cornerRadius = corners,
                            )
                        }
                    }
                }
            }
            if (nowMs != null &&
                nowMs in viewportStartMs..viewportEnd
            ) {
                val nx = TimelineAxis.instantToAxisPx(
                    instantMs = nowMs,
                    axisLengthPx = size.width,
                    viewportStartMs = viewportStartMs,
                    viewportDurationMs = safeViewportDuration,
                )
                drawLine(nowColor, Offset(nx, 0f), Offset(nx, h), strokeWidth = 2.2f)
                drawCircle(nowColor, radius = 4f, center = Offset(nx, 0f))
            }
                    }
                },
        )
    }
}

/**
 * Split a visible sleep interval at primary-day midnights so neighbor peeks
 * can paint at reduced alpha while the mark stays one logical segment.
 */
internal fun sleepPaintSlices(
    visStart: Long,
    visEnd: Long,
    dayBoundariesMs: List<Long> = emptyList(),
    uncertainFromMs: Long? = null,
): List<SleepPaintSlice> {
    if (visEnd <= visStart) return emptyList()
    val cuts = buildList {
        add(visStart)
        for (boundary in dayBoundariesMs) {
            if (boundary in (visStart + 1) until visEnd) add(boundary)
        }
        if (uncertainFromMs != null && uncertainFromMs in (visStart + 1) until visEnd) {
            add(uncertainFromMs)
        }
        add(visEnd)
    }.distinct().sorted()
    return cuts.zipWithNext { a, b ->
        SleepPaintSlice(
            startMs = a,
            endMs = b,
            uncertain = uncertainFromMs != null && a >= uncertainFromMs,
        )
    }
}

/** One painted sub-span of a continuous sleep bar (absolute instants). */
internal data class SleepPaintSlice(
    val startMs: Long,
    val endMs: Long,
    val uncertain: Boolean = false,
)

/**
 * Day time-bar card. Warm and journal share the same horizontal three-lane geometry
 * ([TimelineLane] draw + hit testing). Template differences are limited to card chrome
 * (padding / title / meta density); colors come from the active theme.
 *
 * The rail maps an absolute viewport `[viewportStartMs, +viewportDurationMs)`
 * onto pixels. Selection is owned by the caller (page state): all marks that
 * share [selectedCategoryKey] highlight together.
 *
 * When [onHorizontalPan] is non-null, hour labels and lanes share one official
 * horizontal `scrollable` (fling + nested scroll). The feature state machine owns
 * the absolute viewport, date effect and future clamp; tap still filters.
 */
@Composable
fun TimelineRailCard(
    sleep: List<TimelineLaneSegment>,
    feed: List<TimelineLaneSegment>,
    care: List<TimelineLaneSegment>,
    recordCount: Int,
    nowMs: Long?,
    modifier: Modifier = Modifier,
    selectedCategoryKey: String? = null,
    onCategorySelect: (String?) -> Unit = {},
    legend: List<TimelineLegendEntry> = emptyList(),
    viewportStartMs: Long = 0L,
    viewportDurationMs: Long = TimelineAxis.DEFAULT_VIEWPORT_DURATION_MS,
    dayBoundariesMs: List<Long> = emptyList(),
    primaryRangeMs: LongRange = LongRange.EMPTY,
    onHorizontalPan: ((TimelinePanGesture) -> Float)? = null,
    onPanEnd: (() -> Unit)? = null,
    onPanCancel: (() -> Unit)? = null,
    /**
     * Absolute instant → label. Defaults to a 00/06/12/18/24 fill when the
     * viewport is a 24h window from 0.
     */
    hourTicks: List<Pair<Long, String>>? = null,
    /**
     * Preformatted midnight 「月/日」 labels (`instantMs` to text). Feature
     * owns ZoneId formatting; an empty list draws no date text. The now line
     * is never labeled here.
     */
    dayBoundaryLabels: List<Pair<Long, String>> = emptyList(),
    /**
     * Test hook: reports the settled drawn viewport start (absolute ms).
     */
    onDrawnViewportStart: ((Long) -> Unit)? = null,
    /**
     * Optional header titles above the lanes. Product log home passes
     * [titleSecondary] = 「时间轴」; previews may set either. Blank strings ignored.
     */
    titlePrimary: String? = null,
    titleSecondary: String? = null,
) {
    val journal = LeziThemeExt.isJournal
    val density = LeziThemeExt.density
    // Structural pads from template density (warm open / journal compact).
    val contentPadding = PaddingValues(density.panelContent)
    val sectionGap = density.sectionGap
    // Lane gap stays on the 4/8 grid; not a named density role.
    val laneGap = LeziSpacing.Xs
    val hourLabelStart = if (journal) 32.dp else 34.dp
    val safeViewportDuration = viewportDurationMs.coerceAtLeast(1L)
    val scrollSession = rememberTimelineRailScrollSession(
        enabled = onHorizontalPan != null,
        onHorizontalPan = onHorizontalPan,
        onPanEnd = onPanEnd,
        onPanCancel = onPanCancel,
    )
    val scrolling = scrollSession?.state?.isScrollInProgress == true
    val drawnViewportStart = rememberSettledViewportStartMs(
        targetStartMs = viewportStartMs,
        scrolling = scrolling,
    )
    val onDrawnViewportStartState by rememberUpdatedState(onDrawnViewportStart)
    SideEffect {
        onDrawnViewportStartState?.invoke(drawnViewportStart)
    }
    val resolvedHourTicks = hourTicks ?: defaultHourTicks(
        viewportStartMs = drawnViewportStart,
        viewportDurationMs = safeViewportDuration,
    )
    val primaryTitle = titlePrimary?.takeIf { it.isNotBlank() }
    val secondaryTitle = titleSecondary?.takeIf { it.isNotBlank() }
    val showTitleBlock = primaryTitle != null || secondaryTitle != null
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
        CompositionLocalProvider(LocalTimelineRailScrollSession provides scrollSession) {
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
                                style = LeziThemeExt.typography.Eyebrow,
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
            labels = resolvedHourTicks,
            dayBoundaryLabels = dayBoundaryLabels,
            viewportStartMs = drawnViewportStart,
            viewportDurationMs = safeViewportDuration,
            primaryRangeMs = primaryRangeMs,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = hourLabelStart),
        )
        Spacer(Modifier.height(4.dp))
        TimelineLane(
            label = "睡眠",
            segments = sleep,
            nowMs = nowMs,
            markerStyle = false,
            selectedCategoryKey = selectedCategoryKey,
            onCategorySelect = onCategorySelect,
            viewportStartMs = drawnViewportStart,
            viewportDurationMs = safeViewportDuration,
            dayBoundariesMs = dayBoundariesMs,
            primaryRangeMs = primaryRangeMs,
            onHorizontalPan = onHorizontalPan,
        )
        Spacer(Modifier.height(laneGap))
        TimelineLane(
            label = "喂养",
            segments = feed,
            nowMs = nowMs,
            markerStyle = true,
            selectedCategoryKey = selectedCategoryKey,
            onCategorySelect = onCategorySelect,
            viewportStartMs = drawnViewportStart,
            viewportDurationMs = safeViewportDuration,
            dayBoundariesMs = dayBoundariesMs,
            primaryRangeMs = primaryRangeMs,
            onHorizontalPan = onHorizontalPan,
        )
        Spacer(Modifier.height(laneGap))
        TimelineLane(
            label = "护理",
            segments = care,
            nowMs = nowMs,
            markerStyle = true,
            selectedCategoryKey = selectedCategoryKey,
            onCategorySelect = onCategorySelect,
            viewportStartMs = drawnViewportStart,
            viewportDurationMs = safeViewportDuration,
            dayBoundariesMs = dayBoundariesMs,
            primaryRangeMs = primaryRangeMs,
            onHorizontalPan = onHorizontalPan,
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
}

@Composable
private fun TimelineHourLabels(
    labels: List<Pair<Long, String>>,
    dayBoundaryLabels: List<Pair<Long, String>>,
    viewportStartMs: Long,
    viewportDurationMs: Long,
    primaryRangeMs: LongRange,
    modifier: Modifier = Modifier,
) {
    val scrollSession = LocalTimelineRailScrollSession.current
    val trackMin = LeziThemeExt.structure.timeBarTrackMinHeight
    val elder = LeziThemeExt.isElder
    val displayLabels = labels.map { (instantMs, label) ->
        instantMs to if (elder) compactElderHourLabel(label) else label
    }
    val minGapPx = with(LocalDensity.current) { LeziSpacing.Xs.roundToPx() }
    val tickStyle = LeziThemeExt.typography.Micro
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dimColor = tickColor.copy(alpha = NEIGHBOR_DAY_LABEL_ALPHA)
    val windowGeometry = TimelineWindowGeometry(primaryRangeMs = primaryRangeMs)
    // Candidate width depends only on (label, style, density) — cache it so a
    // pan/fling frame pays placement arithmetic plus the shown subcomposes,
    // not a fresh measure-composition of every candidate.
    val density = LocalDensity.current
    val widthCache = remember(tickStyle, density) { mutableMapOf<String, Int>() }
    SubcomposeLayout(
        modifier
            .then(
                if (elder) {
                    Modifier.heightIn(min = trackMin)
                } else {
                    Modifier.height(14.dp)
                },
            )
            .fillMaxWidth()
            .timelineRailScrollable(scrollSession),
    ) { constraints ->
        val trackWidthPx = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val dayCandidates = dayBoundaryLabels.mapIndexed { index, (instantMs, label) ->
            val widthPx = widthCache.getOrPut(label) {
                subcompose("day-measure-$index") {
                    Text(label, style = tickStyle, color = tickColor, maxLines = 1)
                }.first().measure(loose).width
            }
            TimelineHourLabelCandidate(
                index = index,
                instantMs = instantMs,
                label = label,
                widthPx = widthPx,
            )
        }
        val hourCandidates = displayLabels.mapIndexed { index, (instantMs, label) ->
            val widthPx = widthCache.getOrPut(label) {
                subcompose("hour-measure-$index") {
                    Text(label, style = tickStyle, color = tickColor, maxLines = 1)
                }.first().measure(loose).width
            }
            TimelineHourLabelCandidate(
                index = index,
                instantMs = instantMs,
                label = label,
                widthPx = widthPx,
            )
        }
        val placed = placeTimelineAxisLabels(
            dayLabels = dayCandidates,
            hourLabels = hourCandidates,
            trackWidthPx = trackWidthPx,
            viewportStartMs = viewportStartMs,
            viewportDurationMs = viewportDurationMs,
            minGapPx = minGapPx,
        )
        val shownDays = placed.dayLabels.map { item ->
            val color = if (windowGeometry.isPrimaryInstant(item.instantMs)) {
                tickColor
            } else {
                dimColor
            }
            val placeable = subcompose("day-${item.index}") {
                Text(
                    item.label,
                    style = tickStyle,
                    color = color,
                    maxLines = 1,
                    modifier = Modifier.testTag("timeline_day_${item.instantMs}"),
                )
            }.first().measure(loose)
            item to placeable
        }
        val shownHours = placed.hourLabels.map { item ->
            val placeable = subcompose("hour-${item.index}") {
                Text(
                    item.label,
                    style = tickStyle,
                    color = tickColor,
                    maxLines = 1,
                    modifier = Modifier.testTag("timeline_hour_${item.instantMs}"),
                )
            }.first().measure(loose)
            item to placeable
        }
        val shown = shownDays + shownHours
        val contentHeight = shown.maxOfOrNull { it.second.height } ?: 0
        layout(trackWidthPx, contentHeight.coerceAtLeast(constraints.minHeight)) {
            shown.forEach { (item, placeable) ->
                placeable.place(item.xPx, 0)
            }
        }
    }
}

/**
 * Default hour tick labels for the visible viewport when the caller omits
 * [hourTicks]. Labels every 6h from a UTC-aligned origin so single-day
 * previews keep classic 00/06/12/18/24.
 */
internal fun defaultHourTicks(
    viewportStartMs: Long,
    viewportDurationMs: Long,
): List<Pair<Long, String>> {
    val hourMs = 3_600_000L
    val stepMs = 6L * hourMs
    val endMs = viewportStartMs + viewportDurationMs
    val startAligned = viewportStartMs - Math.floorMod(viewportStartMs, stepMs)
    val labels = mutableListOf<Pair<Long, String>>()
    var tick = startAligned
    while (tick <= endMs) {
        if (tick in viewportStartMs..endMs) {
            val hour = Math.floorMod(tick / hourMs, 24L).toInt()
            labels += tick to hour.toString().padStart(2, '0')
        }
        tick += stepMs
    }
    return labels.ifEmpty {
        listOf(viewportStartMs to "00")
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
                    style = if (LeziThemeExt.isElder) {
                        LeziThemeExt.typography.Meta
                    } else {
                        LeziTypography.Micro
                    },
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
