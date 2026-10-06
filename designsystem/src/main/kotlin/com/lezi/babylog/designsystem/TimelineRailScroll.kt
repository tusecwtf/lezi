package com.lezi.babylog.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged

/**
 * Shared official horizontal [scrollable] for the day rail: one state drives
 * hour labels and all three lanes so fling / overscroll stay in sync.
 */
internal class TimelineRailScrollSession {
    lateinit var state: ScrollableState
    var axisLengthPx: Float = 0f
}

internal val LocalTimelineRailScrollSession = compositionLocalOf<TimelineRailScrollSession?> { null }

@Composable
internal fun rememberTimelineRailScrollSession(
    enabled: Boolean,
    onHorizontalPan: ((TimelinePanGesture) -> Float)?,
    onPanEnd: (() -> Unit)?,
    onPanCancel: (() -> Unit)?,
): TimelineRailScrollSession? {
    val onPanState by rememberUpdatedState(onHorizontalPan)
    val onPanEndState by rememberUpdatedState(onPanEnd)
    val onPanCancelState by rememberUpdatedState(onPanCancel)
    val session = remember { TimelineRailScrollSession() }
    var gestureActive by remember { mutableStateOf(false) }
    var settled by remember { mutableStateOf(false) }

    session.state = rememberScrollableState { delta ->
        if (!enabled) return@rememberScrollableState 0f
        val pan = onPanState ?: return@rememberScrollableState 0f
        val width = session.axisLengthPx
        if (width <= 0f) return@rememberScrollableState 0f
        gestureActive = true
        settled = false
        pan.invoke(TimelinePanGesture(delta, width))
    }

    LaunchedEffect(session.state.isScrollInProgress) {
        if (session.state.isScrollInProgress) {
            gestureActive = true
            settled = false
        } else if (gestureActive && !settled) {
            settled = true
            gestureActive = false
            onPanEndState?.invoke()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (gestureActive && !settled) {
                // Leaving the rail mid-fling settles the viewport already
                // reached. Cancelling would jump back to the finger-down origin.
                onPanEndState?.invoke()
            }
        }
    }
    return session.takeIf { enabled && onHorizontalPan != null }
}

/**
 * Apply the shared rail [session] as a system horizontal scrollable.
 * Width updates from this node so day-fraction math matches the track.
 *
 * Remembered so a pan frame that recomposes the lane does not allocate a new
 * scrollable node (and restart the pointer session) on every frame.
 */
@Composable
internal fun Modifier.timelineRailScrollable(
    session: TimelineRailScrollSession?,
): Modifier {
    if (session == null) return this
    val flingBehavior = rememberTimelineRailFlingBehavior()
    val scrollState = session.state
    val scrollableModifier = remember(session, scrollState, flingBehavior) {
        Modifier
            .onSizeChanged { size ->
                if (size.width > 0) session.axisLengthPx = size.width.toFloat()
            }
            .scrollable(
                state = scrollState,
                orientation = Orientation.Horizontal,
                flingBehavior = flingBehavior,
            )
    }
    return this.then(scrollableModifier)
}

/**
 * Follow [targetStartMs] 1:1 while dragging. After release, spring only a
 * large target jump (ReturnToNow / live reattach / calendar reset). Minute
 * live-follow ticks snap so the rail does not lag a spring behind "now".
 * Changing selected day does not change the target, so it never settles.
 */
internal const val LIVE_FOLLOW_SNAP_MS: Long = 2L * 60 * 1000

/** Carry the previous drawn-target gap only when the target jumped far enough to settle. */
internal fun settleViewportJumpMs(previousTargetMs: Long, targetStartMs: Long): Float {
    val jump = (previousTargetMs - targetStartMs).toFloat()
    return if (kotlin.math.abs(jump) <= LIVE_FOLLOW_SNAP_MS) 0f else jump
}

@Composable
internal fun rememberSettledViewportStartMs(
    targetStartMs: Long,
    scrolling: Boolean,
): Long {
    val delta = remember { Animatable(0f) }
    var lastTarget by remember { mutableLongStateOf(targetStartMs) }
    val settleMs = leziMotionMillis(LeziMotion.Emphasized)
    LaunchedEffect(targetStartMs, scrolling, settleMs) {
        if (scrolling) {
            lastTarget = targetStartMs
            delta.snapTo(0f)
        } else {
            val jump = settleViewportJumpMs(lastTarget, targetStartMs)
            lastTarget = targetStartMs
            if (jump != 0f) {
                delta.snapTo(delta.value + jump)
            } else {
                delta.snapTo(0f)
            }
            if (delta.value != 0f) {
                if (settleMs == 0) {
                    delta.snapTo(0f)
                } else {
                    delta.animateTo(
                        0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )
                }
            }
        }
    }
    return targetStartMs + delta.value.toLong()
}
