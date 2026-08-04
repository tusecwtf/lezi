package com.lezi.babylog.designsystem

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/**
 * Shared "touch slop → direction decision → horizontal consumption" gesture core
 * used by swipe-action rows ([SwipeEditDeleteRow]) and the timeline rail pan
 * (`detectTimelineRailGestures`). Extracted so the two call sites cannot drift.
 *
 * - [onGestureStart] runs right after the first pointer down (capture per-gesture state).
 * - Movement under touch slop does nothing; on pointer-up before slop, [onTap] fires
 *   (with the down position, matching the historical rail behavior).
 * - Once past slop the dominant axis wins: horizontal motion is consumed and reported
 *   as **cumulative** delta from down via [onHorizontalDrag]; vertical motion is left
 *   unconsumed so an outer LazyColumn / pull-to-refresh takes over.
 * - [onGestureEnd] always runs in `finally` with `wasHorizontal` so callers can settle.
 */
internal suspend fun PointerInputScope.trackSlopHorizontalGesture(
    onGestureStart: () -> Unit = {},
    onTap: ((Offset) -> Unit)? = null,
    onHorizontalStart: () -> Unit = {},
    onHorizontalDrag: (totalDeltaX: Float) -> Unit,
    onGestureEnd: (wasHorizontal: Boolean) -> Unit = {},
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onGestureStart()
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
                    if (!pastSlop) onTap?.invoke(down.position)
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
                            onHorizontalStart()
                            onHorizontalDrag(totalX)
                        }
                        // Vertical: leave unconsumed so LazyColumn can scroll.
                    }
                } else if (isHorizontal) {
                    totalX += delta.x
                    change.consume()
                    onHorizontalDrag(totalX)
                }
            }
        } finally {
            onGestureEnd(isHorizontal)
        }
    }
}
