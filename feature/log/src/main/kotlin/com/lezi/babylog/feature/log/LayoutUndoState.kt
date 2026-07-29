package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import com.lezi.babylog.core.model.normalizeDeviceLayoutSnapshot

internal enum class LayoutUndoKind {
    ClearSlot,
    MoveToLocalDeleted,
}

internal data class LayoutUndoCandidate(
    val token: Long,
    val kind: LayoutUndoKind,
    val before: DeviceLayoutSnapshot,
    val after: DeviceLayoutSnapshot,
)

internal sealed interface LayoutUndoState {
    data object Idle : LayoutUndoState
    data class AwaitingOriginal(val candidate: LayoutUndoCandidate) : LayoutUndoState
    data class Available(val candidate: LayoutUndoCandidate) : LayoutUndoState
    data class Restoring(val candidate: LayoutUndoCandidate) : LayoutUndoState
    data class RestoreFailed(val candidate: LayoutUndoCandidate) : LayoutUndoState
}

internal sealed interface LayoutUndoEvent {
    data class IntentApplied(
        val token: Long,
        val intent: LayoutEditIntent,
        val before: DeviceLayoutSnapshot,
        val after: DeviceLayoutSnapshot,
    ) : LayoutUndoEvent

    data class OriginalWriteFinished(
        val token: Long,
        val succeeded: Boolean,
        val currentSnapshot: DeviceLayoutSnapshot,
    ) : LayoutUndoEvent

    data class OfferExpired(val token: Long) : LayoutUndoEvent

    data class UndoRequested(
        val token: Long,
        val currentSnapshot: DeviceLayoutSnapshot,
    ) : LayoutUndoEvent

    data class RetryUndoRequested(
        val token: Long,
        val currentSnapshot: DeviceLayoutSnapshot,
    ) : LayoutUndoEvent

    data class UndoWriteFinished(
        val token: Long,
        val succeeded: Boolean,
        val currentSnapshot: DeviceLayoutSnapshot,
    ) : LayoutUndoEvent

    data object EditorExited : LayoutUndoEvent
}

internal data class LayoutUndoReduction(
    val state: LayoutUndoState,
    val restoredSnapshot: DeviceLayoutSnapshot? = null,
    val announcement: String? = null,
)

/**
 * A no-op intent normally needs no write. If it invalidated an undo already being
 * written, enqueue the still-visible after snapshot behind that write so FIFO
 * durability cannot finish on the now-rejected before snapshot.
 */
internal fun shouldWriteLayoutIntentResult(
    undoStateBeforeIntent: LayoutUndoState,
    undoStateAfterIntent: LayoutUndoState,
    before: DeviceLayoutSnapshot,
    after: DeviceLayoutSnapshot,
): Boolean =
    normalizeDeviceLayoutSnapshot(before) != normalizeDeviceLayoutSnapshot(after) ||
        (undoStateBeforeIntent is LayoutUndoState.Restoring &&
            undoStateAfterIntent !is LayoutUndoState.Restoring)

internal fun reduceLayoutUndo(
    state: LayoutUndoState,
    event: LayoutUndoEvent,
): LayoutUndoReduction = when (event) {
    is LayoutUndoEvent.IntentApplied -> {
        val kind = when (event.intent) {
            is LayoutEditIntent.ClearSlot -> LayoutUndoKind.ClearSlot
            is LayoutEditIntent.MoveToLocalDeleted -> LayoutUndoKind.MoveToLocalDeleted
            else -> null
        }
        val before = normalizeDeviceLayoutSnapshot(event.before)
        val after = normalizeDeviceLayoutSnapshot(event.after)
        val next = if (kind != null && before != after) {
            LayoutUndoState.AwaitingOriginal(
                LayoutUndoCandidate(
                    token = event.token,
                    kind = kind,
                    before = before,
                    after = after,
                ),
            )
        } else {
            LayoutUndoState.Idle
        }
        LayoutUndoReduction(next)
    }
    is LayoutUndoEvent.OriginalWriteFinished -> {
        val awaiting = state as? LayoutUndoState.AwaitingOriginal
        val next = if (
            awaiting != null &&
            awaiting.candidate.token == event.token &&
            event.succeeded &&
            awaiting.candidate.after == normalizeDeviceLayoutSnapshot(event.currentSnapshot)
        ) {
            LayoutUndoState.Available(awaiting.candidate)
        } else if (awaiting?.candidate?.token == event.token) {
            LayoutUndoState.Idle
        } else {
            state
        }
        LayoutUndoReduction(next)
    }
    is LayoutUndoEvent.OfferExpired -> {
        val available = state as? LayoutUndoState.Available
        LayoutUndoReduction(
            if (available?.candidate?.token == event.token) LayoutUndoState.Idle else state,
        )
    }
    is LayoutUndoEvent.UndoRequested -> {
        val available = state as? LayoutUndoState.Available
        LayoutUndoReduction(
            when {
                available?.candidate?.token != event.token -> state
                available.candidate.after != normalizeDeviceLayoutSnapshot(event.currentSnapshot) -> {
                    LayoutUndoState.Idle
                }
                else -> LayoutUndoState.Restoring(available.candidate)
            },
        )
    }
    is LayoutUndoEvent.RetryUndoRequested -> {
        val failed = state as? LayoutUndoState.RestoreFailed
        LayoutUndoReduction(
            when {
                failed?.candidate?.token != event.token -> state
                failed.candidate.after != normalizeDeviceLayoutSnapshot(event.currentSnapshot) -> {
                    LayoutUndoState.Idle
                }
                else -> LayoutUndoState.Restoring(failed.candidate)
            },
        )
    }
    is LayoutUndoEvent.UndoWriteFinished -> {
        val restoring = state as? LayoutUndoState.Restoring
        when {
            restoring?.candidate?.token != event.token -> LayoutUndoReduction(state)
            restoring.candidate.after != normalizeDeviceLayoutSnapshot(event.currentSnapshot) -> {
                LayoutUndoReduction(LayoutUndoState.Idle)
            }
            event.succeeded -> LayoutUndoReduction(
                state = LayoutUndoState.Idle,
                restoredSnapshot = restoring.candidate.before,
                announcement = "布局已撤销",
            )
            else -> LayoutUndoReduction(LayoutUndoState.RestoreFailed(restoring.candidate))
        }
    }
    LayoutUndoEvent.EditorExited -> LayoutUndoReduction(LayoutUndoState.Idle)
}
