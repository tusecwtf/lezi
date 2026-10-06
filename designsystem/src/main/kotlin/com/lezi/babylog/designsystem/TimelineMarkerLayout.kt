package com.lezi.babylog.designsystem

import kotlin.math.abs

/** One event's final one-dimensional drawing center and matching touch halo. */
data class TimelineMarkerTarget(
    val sourceIndex: Int,
    val segment: TimelineLaneSegment,
    val centerPx: Float,
    val hitRadiusPx: Float,
    val zOrder: Int,
)

/** Orientation-free marker geometry; callers map [TimelineMarkerTarget.centerPx] to X or Y. */
class TimelineMarkerLayout(
    val targets: List<TimelineMarkerTarget>,
) {
    /** Returns the nearest drawn marker; equal-distance overlaps follow drawing z-order. */
    fun hitTest(axisPositionPx: Float): TimelineLaneSegment? =
        targets
            .asSequence()
            .filter { abs(it.centerPx - axisPositionPx) <= it.hitRadiusPx }
            .minWithOrNull(
                compareBy<TimelineMarkerTarget> { abs(it.centerPx - axisPositionPx) }
                    .thenByDescending { it.zOrder },
            )
            ?.segment
}

/**
 * Places event segments into stable time clusters on a viewport-mapped axis.
 *
 * [viewportStartMs] / [viewportDurationMs] describe which absolute slice maps
 * onto [axisLengthPx]. Defaults preserve a 24h fill from epoch 0.
 *
 * The whole cluster is shifted at an edge so its centers keep their spacing. If the
 * available axis is too short, spacing compresses instead of collapsing or drawing out
 * of bounds. Titles and details deliberately do not participate in ordering.
 */
fun layoutTimelineEventMarkers(
    segments: List<TimelineLaneSegment>,
    axisLengthPx: Float,
    clusterWindowMs: Long,
    slotSpacingPx: Float,
    edgeInsetPx: Float,
    hitRadiusPx: Float,
    viewportStartMs: Long = 0L,
    viewportDurationMs: Long = TimelineAxis.DEFAULT_VIEWPORT_DURATION_MS,
): TimelineMarkerLayout {
    val safeAxisLengthPx = axisLengthPx.coerceAtLeast(0f)
    val safeClusterWindowMs = clusterWindowMs.coerceAtLeast(0L)
    val safeEdgeInsetPx = edgeInsetPx.coerceIn(0f, safeAxisLengthPx / 2f)
    val safeHitRadiusPx = hitRadiusPx.coerceAtLeast(0f)
    val safeViewportDuration = viewportDurationMs.coerceAtLeast(1L)
    val viewportEnd = viewportStartMs + safeViewportDuration
    val sortedEvents = segments
        .mapIndexed(::IndexedSegment)
        .filter { it.segment.isEvent }
        // Only lay out events that can fall on the visible slice (with a small margin
        // for edge clusters that still need room to expand).
        .filter {
            val instant = it.segment.startMs
            instant in (viewportStartMs - safeClusterWindowMs)..
                (viewportEnd + safeClusterWindowMs)
        }
        .sortedWith(
            compareBy<IndexedSegment>(
                { it.segment.startMs },
                { it.segment.dayChartCategoryKey.orEmpty() },
                { it.segment.colorRole.ordinal },
                IndexedSegment::sourceIndex,
            ),
        )
    val clusters = buildList {
        var current = mutableListOf<IndexedSegment>()
        sortedEvents.forEach { indexedSegment ->
            if (
                current.isNotEmpty() &&
                indexedSegment.segment.startMs -
                current.last().segment.startMs > safeClusterWindowMs
            ) {
                add(current)
                current = mutableListOf()
            }
            current += indexedSegment
        }
        if (current.isNotEmpty()) add(current)
    }
    var zOrder = 0
    val targets = clusters.flatMap { cluster ->
        val naturalAnchorPx = cluster
            .map {
                TimelineAxis.instantToAxisPx(
                    instantMs = it.segment.startMs,
                    axisLengthPx = safeAxisLengthPx,
                    viewportStartMs = viewportStartMs,
                    viewportDurationMs = safeViewportDuration,
                )
            }
            .average()
            .toFloat()
        val availableSpanPx = safeAxisLengthPx - 2f * safeEdgeInsetPx
        val effectiveSpacingPx = if (cluster.size <= 1) {
            0f
        } else {
            slotSpacingPx.coerceAtLeast(0f).coerceAtMost(
                availableSpanPx / (cluster.size - 1),
            )
        }
        val halfSpanPx = (cluster.size - 1) * effectiveSpacingPx / 2f
        val anchorPx = naturalAnchorPx.coerceIn(
            minimumValue = safeEdgeInsetPx + halfSpanPx,
            maximumValue = safeAxisLengthPx - safeEdgeInsetPx - halfSpanPx,
        )
        cluster.mapIndexed { index, indexedSegment ->
            TimelineMarkerTarget(
                sourceIndex = indexedSegment.sourceIndex,
                segment = indexedSegment.segment,
                centerPx = anchorPx +
                    (index - (cluster.size - 1) / 2f) * effectiveSpacingPx,
                hitRadiusPx = safeHitRadiusPx,
                zOrder = zOrder++,
            )
        }
    }
    return TimelineMarkerLayout(targets)
}

private data class IndexedSegment(
    val sourceIndex: Int,
    val segment: TimelineLaneSegment,
)
