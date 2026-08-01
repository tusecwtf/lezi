package com.lezi.babylog.feature.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default window for a layout undo snackbar offer. Matches Material3
 * [androidx.compose.material3.SnackbarDuration.Short] (~4s) so existing
 * device timeout tests remain meaningful.
 */
internal const val LAYOUT_UNDO_OFFER_DURATION_MS: Long = 4_000L

/**
 * ViewModel-owned layout undo session. Configuration recreation retains the
 * same store (candidate, write phase, token sequence, stable offer deadline);
 * process death / a fresh store starts Idle and never invents a reverse write.
 */
internal data class LayoutUndoSessionUi(
    val state: LayoutUndoState = LayoutUndoState.Idle,
    val offerExpiresAtEpochMs: Long? = null,
    val pendingAnnouncement: String? = null,
)

/**
 * Observable undo session for the layout editor. Deliberately has no
 * SavedStateHandle adapter: configuration recreation retains the store while
 * process restart starts idle.
 */
internal class LayoutUndoSessionStore(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val offerDurationMs: Long = LAYOUT_UNDO_OFFER_DURATION_MS,
) {
    private val mutableState = MutableStateFlow(LayoutUndoSessionUi())
    val state: StateFlow<LayoutUndoSessionUi> = mutableState.asStateFlow()

    private var nextToken: Long = 0L

    val current: LayoutUndoSessionUi
        get() = mutableState.value

    fun allocateToken(): Long {
        nextToken += 1L
        return nextToken
    }

    fun reduce(event: LayoutUndoEvent): LayoutUndoReduction {
        val before = mutableState.value
        val reduction = reduceLayoutUndo(before.state, event)
        val nextExpires = when (val next = reduction.state) {
            is LayoutUndoState.Available -> {
                val previousAvailable = before.state as? LayoutUndoState.Available
                if (
                    previousAvailable?.candidate?.token == next.candidate.token &&
                    before.offerExpiresAtEpochMs != null
                ) {
                    before.offerExpiresAtEpochMs
                } else {
                    nowMs() + offerDurationMs
                }
            }
            else -> null
        }
        mutableState.value = LayoutUndoSessionUi(
            state = reduction.state,
            offerExpiresAtEpochMs = nextExpires,
            pendingAnnouncement = reduction.announcement
                ?: before.pendingAnnouncement,
        )
        return reduction
    }

    fun remainingOfferMs(nowMs: Long = this.nowMs()): Long {
        val expiresAt = mutableState.value.offerExpiresAtEpochMs ?: return 0L
        return (expiresAt - nowMs).coerceAtLeast(0L)
    }

    fun consumeAnnouncement(): String? {
        var taken: String? = null
        mutableState.update { current ->
            taken = current.pendingAnnouncement
            if (taken == null) current else current.copy(pendingAnnouncement = null)
        }
        return taken
    }

    /** Open editor / cold discard: Idle without inventing a restore write. */
    fun clear() {
        mutableState.value = LayoutUndoSessionUi()
    }
}
