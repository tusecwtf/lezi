package com.lezi.babylog.feature.log

internal sealed interface LayoutDragFeedbackState {
    data object Idle : LayoutDragFeedbackState

    data class Dragging(
        val token: Long,
        val currentTarget: LayoutDropTarget? = null,
    ) : LayoutDragFeedbackState
}

internal enum class LayoutDragVisualPulseKind {
    Pickup,
    Target,
    Drop,
}

internal data class LayoutDragVisualPulse(
    val token: Long,
    val kind: LayoutDragVisualPulseKind,
    val target: LayoutDropTarget? = null,
)

internal enum class LayoutDragHaptic {
    Pickup,
    Target,
    Drop,
}

internal sealed interface LayoutDragFeedbackEvent {
    data class PickedUp(
        val token: Long,
        val initialTarget: LayoutDropTarget? = null,
    ) : LayoutDragFeedbackEvent

    data class CurrentTargetChanged(
        val token: Long,
        val target: LayoutDropTarget?,
    ) : LayoutDragFeedbackEvent

    data class DropFinished(
        val token: Long,
        val accepted: Boolean,
    ) : LayoutDragFeedbackEvent

    data class Cancelled(val token: Long) : LayoutDragFeedbackEvent
}

internal data class LayoutDragFeedbackReduction(
    val state: LayoutDragFeedbackState,
    val visualPulse: LayoutDragVisualPulse? = null,
    val haptic: LayoutDragHaptic? = null,
    val clearVisualPulse: Boolean = false,
)

internal fun reduceLayoutDragFeedback(
    state: LayoutDragFeedbackState,
    event: LayoutDragFeedbackEvent,
): LayoutDragFeedbackReduction = when (event) {
    is LayoutDragFeedbackEvent.PickedUp -> LayoutDragFeedbackReduction(
        state = LayoutDragFeedbackState.Dragging(
            token = event.token,
            currentTarget = event.initialTarget,
        ),
        visualPulse = LayoutDragVisualPulse(
            token = event.token,
            kind = LayoutDragVisualPulseKind.Pickup,
        ),
        haptic = LayoutDragHaptic.Pickup,
    )

    is LayoutDragFeedbackEvent.CurrentTargetChanged -> {
        val dragging = state as? LayoutDragFeedbackState.Dragging
        when {
            dragging == null || dragging.token != event.token -> {
                LayoutDragFeedbackReduction(state = state)
            }

            dragging.currentTarget == event.target -> {
                LayoutDragFeedbackReduction(state = state)
            }

            event.target == null -> LayoutDragFeedbackReduction(
                state = dragging.copy(currentTarget = null),
            )

            else -> LayoutDragFeedbackReduction(
                state = dragging.copy(currentTarget = event.target),
                visualPulse = LayoutDragVisualPulse(
                    token = event.token,
                    kind = LayoutDragVisualPulseKind.Target,
                    target = event.target,
                ),
                haptic = LayoutDragHaptic.Target,
            )
        }
    }

    is LayoutDragFeedbackEvent.DropFinished -> {
        val dragging = state as? LayoutDragFeedbackState.Dragging
        if (dragging == null || dragging.token != event.token) {
            LayoutDragFeedbackReduction(state = state)
        } else if (event.accepted) {
            LayoutDragFeedbackReduction(
                state = LayoutDragFeedbackState.Idle,
                visualPulse = LayoutDragVisualPulse(
                    token = event.token,
                    kind = LayoutDragVisualPulseKind.Drop,
                    target = dragging.currentTarget,
                ),
                haptic = LayoutDragHaptic.Drop,
            )
        } else {
            LayoutDragFeedbackReduction(
                state = LayoutDragFeedbackState.Idle,
                clearVisualPulse = true,
            )
        }
    }

    is LayoutDragFeedbackEvent.Cancelled -> {
        val dragging = state as? LayoutDragFeedbackState.Dragging
        if (dragging == null || dragging.token != event.token) {
            LayoutDragFeedbackReduction(state = state)
        } else {
            LayoutDragFeedbackReduction(
                state = LayoutDragFeedbackState.Idle,
                clearVisualPulse = true,
            )
        }
    }
}

internal fun layoutDragFeedbackDurationMillis(motionDurationScale: Float): Int =
    if (motionDurationScale <= 0f) 0 else 160
