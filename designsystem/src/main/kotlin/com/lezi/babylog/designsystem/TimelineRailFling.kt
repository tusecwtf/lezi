package com.lezi.babylog.designsystem

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.math.abs

/**
 * Higher than Compose `exponentialDecay` default (`1f`) so a hard fling does not
 * run away across a week. Ticket 07 will tune this on device to
 * “one hard fling ≈ 2–3 days”. No hard travel cap — only decay + the now clamp.
 */
internal const val TIMELINE_RAIL_FLING_FRICTION_MULTIPLIER = 2.5f

@Composable
internal fun rememberTimelineRailFlingBehavior(): FlingBehavior {
    val spec = remember {
        exponentialDecay<Float>(
            frictionMultiplier = TIMELINE_RAIL_FLING_FRICTION_MULTIPLIER,
        )
    }
    return remember(spec) { TimelineRailFlingBehavior(spec) }
}

private class TimelineRailFlingBehavior(
    private val flingDecay: DecayAnimationSpec<Float>,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        var velocityLeft = initialVelocity
        var lastValue = 0f
        if (abs(initialVelocity) > 1f) {
            AnimationState(
                initialValue = 0f,
                initialVelocity = initialVelocity,
            ).animateDecay(flingDecay) {
                val delta = value - lastValue
                val consumed = scrollBy(delta)
                lastValue = value
                velocityLeft = this.velocity
                if (abs(delta - consumed) > 0.5f) {
                    this.cancelAnimation()
                }
            }
        }
        return velocityLeft
    }
}
