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
 * [viewportStartMinutes] / [viewportDurationMinutes] describe which slice of the
 * content axis (segment start/end minutes) maps onto [axisLengthPx]. Defaults
 * preserve the historical single-day fill (0…24h maps to the full axis).
 *
 * The whole cluster is shifted at an edge so its centers keep their spacing. If the
 * available axis is too short, spacing compresses instead of collapsing or drawing out
 * of bounds. Titles and details deliberately do not participate in ordering.
 */
fun layoutTimelineEventMarkers(
    segments: List<TimelineLaneSegment>,
    axisLengthPx: Float,
    clusterWindowMinutes: Int,
    slotSpacingPx: Float,
    edgeInsetPx: Float,
    hitRadiusPx: Float,
    viewportStartMinutes: Int = 0,
    viewportDurationMinutes: Int = TimelineAxis.MINUTES_PER_DAY,
): TimelineMarkerLayout {
    val safeAxisLengthPx = axisLengthPx.coerceAtLeast(0f)
    val safeClusterWindowMinutes = clusterWindowMinutes.coerceAtLeast(0)
    val safeEdgeInsetPx = edgeInsetPx.coerceIn(0f, safeAxisLengthPx / 2f)
    val safeHitRadiusPx = hitRadiusPx.coerceAtLeast(0f)
    val safeViewportDuration = viewportDurationMinutes.coerceAtLeast(1)
    val viewportEnd = viewportStartMinutes + safeViewportDuration
    val sortedEvents = segments
        .mapIndexed(::IndexedSegment)
        .filter { it.segment.isEvent }
        // Only lay out events that can fall on the visible slice (with a small margin
        // for edge clusters that still need room to expand).
        .filter {
            val m = it.segment.startMinOfDay
            m in (viewportStartMinutes - safeClusterWindowMinutes)..
                (viewportEnd + safeClusterWindowMinutes)
        }
        .sortedWith(
            compareBy<IndexedSegment>(
                { it.segment.startMinOfDay },
                { it.segment.dayChartCategoryKey.orEmpty() },
                { it.segment.color.value },
                IndexedSegment::sourceIndex,
            ),
        )
    val clusters = buildList {
        var current = mutableListOf<IndexedSegment>()
        sortedEvents.forEach { indexedSegment ->
            if (
                current.isNotEmpty() &&
                indexedSegment.segment.startMinOfDay -
                current.last().segment.startMinOfDay > safeClusterWindowMinutes
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
                TimelineAxis.contentMinuteToAxisPx(
                    contentMinute = it.segment.startMinOfDay,
                    axisLengthPx = safeAxisLengthPx,
                    viewportStartMinutes = viewportStartMinutes,
                    viewportDurationMinutes = safeViewportDuration,
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
