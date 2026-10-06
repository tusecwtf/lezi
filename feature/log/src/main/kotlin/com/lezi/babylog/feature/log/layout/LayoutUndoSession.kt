package com.lezi.babylog.feature.log.layout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

/**
 * Default window for a layout undo snackbar offer. Matches Material3
 * [androidx.compose.material3.SnackbarDuration.Short] (~4s) so existing
 * device timeout tests remain meaningful.
 */
internal const val LAYOUT_UNDO_OFFER_DURATION_MS: Long = 4_000L

/**
 * ViewModel-owned layout undo session. Configuration recreation retains the
 * same store (candidate, write phase, token sequence, stable offer deadline,
 * failure-dialog dismiss, and exit/retry chrome); process death / a fresh
 * store starts Idle and never invents a reverse write.
 */
internal data class LayoutUndoSessionUi(
    val state: LayoutUndoState = LayoutUndoState.Idle,
    val offerExpiresAtEpochMs: Long? = null,
    val pendingAnnouncement: String? = null,
    /** Write-failure sequence the user already dismissed; rotation must not revive it. */
    val dismissedLayoutFailureSequence: Long? = null,
    /** Exit flush or failure-retry busy chrome retained across recreation. */
    val exitFlushInProgress: Boolean = false,
    /** After a failed exit flush, close the editor once a later retry succeeds. */
    val exitAfterLayoutRetry: Boolean = false,
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
        var reduction = LayoutUndoReduction(mutableState.value.state)
        mutableState.update { before ->
            reduction = reduceLayoutUndo(before.state, event)
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
            before.copy(
                state = reduction.state,
                offerExpiresAtEpochMs = nextExpires,
                pendingAnnouncement = reduction.announcement
                    ?: before.pendingAnnouncement,
            )
        }
        return reduction
    }

    /**
     * Wall-clock remaining for the current Available offer. Production and tests
     * share [nowMs] so Canvas must not recompute with a second clock.
     */
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

    fun dismissLayoutFailure(sequence: Long?) {
        mutableState.update {
            it.copy(
                dismissedLayoutFailureSequence = sequence,
                exitAfterLayoutRetry = false,
            )
        }
    }

    fun clearDismissedLayoutFailure() {
        mutableState.update { it.copy(dismissedLayoutFailureSequence = null) }
    }

    fun setExitFlushInProgress(inProgress: Boolean) {
        mutableState.update { it.copy(exitFlushInProgress = inProgress) }
    }

    fun setExitAfterLayoutRetry(exitAfter: Boolean) {
        mutableState.update { it.copy(exitAfterLayoutRetry = exitAfter) }
    }

    /** Open editor / cold discard: Idle without inventing a restore write. */
    fun clear() {
        mutableState.value = LayoutUndoSessionUi()
    }
}
