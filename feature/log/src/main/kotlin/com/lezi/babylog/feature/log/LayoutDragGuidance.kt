package com.lezi.babylog.feature.log

/** Session-only visibility; durable completion remains owned by SettingsStore. */
internal enum class LayoutDragGuidanceVisibility {
    Auto,
    Manual,
    Hidden,
}

internal data class LayoutDragGuidanceState(
    val completed: Boolean,
    val visibility: LayoutDragGuidanceVisibility,
)

internal enum class LayoutGuidanceInputOrigin {
    TouchDrag,
    AlternativeAction,
}

internal sealed interface LayoutDragGuidanceEvent {
    data object HelpRequested : LayoutDragGuidanceEvent
    data object CloseRequested : LayoutDragGuidanceEvent
    data class CompletionMarkerFinished(val succeeded: Boolean) : LayoutDragGuidanceEvent
    data class DragCompletionFinished(
        val changed: Boolean,
        val inputOrigin: LayoutGuidanceInputOrigin,
        val layoutReceiptSucceeded: Boolean,
        val markerReceiptSucceeded: Boolean,
    ) : LayoutDragGuidanceEvent
}

internal data class LayoutDragGuidanceReduction(
    val state: LayoutDragGuidanceState,
    /** Request the monotonic SettingsStore marker; never requests clearing it. */
    val markCompleted: Boolean = false,
)

internal fun initialLayoutDragGuidanceState(completed: Boolean): LayoutDragGuidanceState =
    LayoutDragGuidanceState(
        completed = completed,
        visibility = if (completed) {
            LayoutDragGuidanceVisibility.Hidden
        } else {
            LayoutDragGuidanceVisibility.Auto
        },
    )

internal fun isDurableLayoutDragGuidanceCompletion(
    changed: Boolean,
    inputOrigin: LayoutGuidanceInputOrigin,
    layoutReceiptSucceeded: Boolean,
    markerReceiptSucceeded: Boolean,
): Boolean =
    changed &&
        inputOrigin == LayoutGuidanceInputOrigin.TouchDrag &&
        layoutReceiptSucceeded &&
        markerReceiptSucceeded

internal fun shouldRequestLayoutDragGuidanceCompletion(
    state: LayoutDragGuidanceState,
    changed: Boolean,
    inputOrigin: LayoutGuidanceInputOrigin,
    layoutReceiptSucceeded: Boolean,
): Boolean =
    !state.completed &&
        changed &&
        inputOrigin == LayoutGuidanceInputOrigin.TouchDrag &&
        layoutReceiptSucceeded

internal fun reduceLayoutDragGuidance(
    state: LayoutDragGuidanceState,
    event: LayoutDragGuidanceEvent,
): LayoutDragGuidanceReduction = when (event) {
    LayoutDragGuidanceEvent.HelpRequested -> LayoutDragGuidanceReduction(
        state = state.copy(visibility = LayoutDragGuidanceVisibility.Manual),
    )
    LayoutDragGuidanceEvent.CloseRequested -> LayoutDragGuidanceReduction(
        state = state.copy(visibility = LayoutDragGuidanceVisibility.Hidden),
        markCompleted = !state.completed,
    )
    is LayoutDragGuidanceEvent.CompletionMarkerFinished -> LayoutDragGuidanceReduction(
        state = if (event.succeeded) state.copy(completed = true) else state,
    )
    is LayoutDragGuidanceEvent.DragCompletionFinished -> LayoutDragGuidanceReduction(
        state = if (
            !state.completed &&
            isDurableLayoutDragGuidanceCompletion(
                changed = event.changed,
                inputOrigin = event.inputOrigin,
                layoutReceiptSucceeded = event.layoutReceiptSucceeded,
                markerReceiptSucceeded = event.markerReceiptSucceeded,
            )
        ) {
            state.copy(
                completed = true,
                visibility = LayoutDragGuidanceVisibility.Hidden,
            )
        } else {
            state
        },
    )
}
