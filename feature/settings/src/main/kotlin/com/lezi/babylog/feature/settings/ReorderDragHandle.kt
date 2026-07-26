package com.lezi.babylog.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Resolves a drag against centers measured from the actual rendered targets.
 * Strictly closer wins, so releasing exactly at a midpoint keeps the item put.
 */
internal fun <T> dropTargetDelta(
    orderedKeys: List<T>,
    sourceKey: T,
    dragDistancePx: Float,
    targetCentersPx: Map<T, Float>,
    unmeasuredEdgeThresholdPx: Float = Float.POSITIVE_INFINITY,
): Int {
    if (!dragDistancePx.isFinite()) return 0
    val sourceIndex = orderedKeys.indexOf(sourceKey)
    val sourceCenter = targetCentersPx[sourceKey]
    if (sourceIndex < 0 || sourceCenter == null || !sourceCenter.isFinite()) return 0

    val draggedCenter = sourceCenter + dragDistancePx
    var targetIndex = sourceIndex
    var closestDistance = abs(draggedCenter - sourceCenter)
    orderedKeys.forEachIndexed { index, key ->
        val targetCenter = targetCentersPx[key]?.takeIf(Float::isFinite) ?: return@forEachIndexed
        val distance = abs(draggedCenter - targetCenter)
        if (distance < closestDistance) {
            targetIndex = index
            closestDistance = distance
        }
    }
    if (targetIndex != sourceIndex) return targetIndex - sourceIndex

    if (
        !unmeasuredEdgeThresholdPx.isFinite() ||
        unmeasuredEdgeThresholdPx <= 0f ||
        abs(dragDistancePx) < unmeasuredEdgeThresholdPx
    ) {
        return 0
    }
    val direction = if (dragDistancePx > 0f) 1 else -1
    val adjacentIndex = sourceIndex + direction
    if (adjacentIndex !in orderedKeys.indices) return 0
    val hasMeasuredTargetInDirection = orderedKeys.indices.any { index ->
        val isInDirection = if (direction > 0) index > sourceIndex else index < sourceIndex
        isInDirection && targetCentersPx[orderedKeys[index]]?.isFinite() == true
    }
    return if (hasMeasuredTargetInDirection) 0 else direction
}

/** Moves one item while shifting every item between the source and destination. */
internal fun <T> moveItemBy(items: List<T>, fromIndex: Int, delta: Int): List<T> {
    if (fromIndex !in items.indices || delta == 0) return items.toList()
    val destination = (fromIndex + delta).coerceIn(items.indices)
    if (destination == fromIndex) return items.toList()
    return items.toMutableList().apply {
        val moved = removeAt(fromIndex)
        add(destination, moved)
    }
}

/**
 * A handle-only long-press drag target. Adjacent move buttons remain beside it
 * as a keyboard/TalkBack fallback; releasing the handle applies the drag once.
 */
@Composable
internal fun ReorderDragHandle(
    label: String,
    canMove: Boolean,
    onDragFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnDragFinished by rememberUpdatedState(onDragFinished)
    var dragDistancePx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    fun finishDrag() {
        val completedDistance = dragDistancePx
        dragDistancePx = 0f
        dragging = false
        if (completedDistance.isFinite()) currentOnDragFinished(completedDistance)
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .background(
                color = if (dragging) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                },
                shape = CircleShape,
            )
            .semantics {
                contentDescription = if (canMove) {
                    "$label，长按并上下拖动排序"
                } else {
                    "$label，无需排序"
                }
            }
            .pointerInput(canMove) {
                if (!canMove) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragDistancePx = 0f
                        dragging = true
                    },
                    onDragCancel = {
                        dragDistancePx = 0f
                        dragging = false
                    },
                    onDragEnd = ::finishDrag,
                    onDrag = { change, amount ->
                        change.consume()
                        dragDistancePx += amount.y
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.DragHandle,
            contentDescription = null,
            tint = if (canMove) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        )
    }
}
