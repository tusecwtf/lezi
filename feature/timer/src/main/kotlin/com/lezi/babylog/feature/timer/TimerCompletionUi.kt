package com.lezi.babylog.feature.timer

import androidx.lifecycle.SavedStateHandle

/**
 * Observable completion stage for the nursing timer.
 *
 * Single source of truth for the confirmation sheet, in-flight save, and post-success
 * next-feed / exit. Host compositions subscribe — they must not dual-master draft/saving
 * in Compose `remember` locals that die on configuration change.
 */
internal data class TimerCompletionUiState(
    val draft: NursingCompletionDraft? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    /** Post-success next-feed offer; durable in [TimerCompletionSavedState]. */
    val pendingNextFeedSuggestedAt: Long? = null,
    /**
     * One-shot exit after success without a next-feed offer. Durable until the Host
     * acknowledges navigation so a late composition still exits once.
     */
    val pendingExit: Boolean = false,
) {
    val sheetVisible: Boolean
        get() = draft != null

    val hasPostSaveStage: Boolean
        get() = pendingNextFeedSuggestedAt != null || pendingExit
}

/** Open the confirmation sheet with a frozen draft (not while a post-save stage owns the flow). */
internal fun openTimerCompletionSheet(
    current: TimerCompletionUiState,
    draft: NursingCompletionDraft,
): TimerCompletionUiState {
    if (current.saving || current.hasPostSaveStage) return current
    return TimerCompletionUiState(draft = draft)
}

/** Edit draft while the sheet is open and not saving. */
internal fun updateTimerCompletionDraft(
    current: TimerCompletionUiState,
    draft: NursingCompletionDraft,
): TimerCompletionUiState {
    if (current.saving || current.draft == null || current.hasPostSaveStage) return current
    return current.copy(draft = draft, saveError = null)
}

/** Dismiss the sheet only when not mid-save and no post-save stage. */
internal fun dismissTimerCompletionSheet(
    current: TimerCompletionUiState,
): TimerCompletionUiState {
    if (current.saving || current.hasPostSaveStage) return current
    return TimerCompletionUiState()
}

/**
 * Whether a new domain-complete job may start. When already saving, re-confirm is a no-op:
 * the observable state already shows Saving; a second coroutine/Record must not start.
 */
internal fun mayStartTimerCompletionSave(saving: Boolean): Boolean = !saving

/** Enter Saving with the confirmed draft; clears prior error. */
internal fun beginTimerCompletionSave(
    current: TimerCompletionUiState,
    draft: NursingCompletionDraft,
): TimerCompletionUiState {
    if (current.hasPostSaveStage) return current
    return current.copy(draft = draft, saving = true, saveError = null)
}

/** Domain/validation failure → retryable sheet with the same draft. */
internal fun timerCompletionSaveFailed(
    current: TimerCompletionUiState,
    error: String,
): TimerCompletionUiState {
    if (current.hasPostSaveStage) return current
    return current.copy(saving = false, saveError = error)
}

/**
 * Domain success: clear sheet and publish either a next-feed offer or a consumable exit.
 * Call only after completeNursing has returned (idempotent on [completionClientUuid]).
 */
internal fun timerCompletionSucceeded(
    current: TimerCompletionUiState,
    suggestedNextFeedAt: Long?,
): TimerCompletionUiState =
    if (suggestedNextFeedAt != null) {
        TimerCompletionUiState(pendingNextFeedSuggestedAt = suggestedNextFeedAt)
    } else {
        TimerCompletionUiState(pendingExit = true)
    }

/** Host acknowledged exit navigation — safe to re-subscribe without re-navigating. */
internal fun consumeTimerPendingExit(
    current: TimerCompletionUiState,
): TimerCompletionUiState {
    if (!current.pendingExit) return current
    return current.copy(pendingExit = false)
}

/** Host finished the shared next-feed flow (scheduled or skipped). */
internal fun consumeTimerPendingNextFeed(
    current: TimerCompletionUiState,
): TimerCompletionUiState {
    if (current.pendingNextFeedSuggestedAt == null) return current
    return current.copy(pendingNextFeedSuggestedAt = null)
}

/**
 * After process death the completion job is gone. Resume only when SavedState still says
 * Saving with a draft and no post-save stage (domain may or may not have committed —
 * completeNursing is idempotent on completionClientUuid).
 */
internal fun shouldResumeTimerCompletionSave(state: TimerCompletionUiState): Boolean =
    state.saving && state.draft != null && !state.hasPostSaveStage

/**
 * Prefer a non-idle live snapshot (same process, config change) over durable SavedState.
 * Empty live falls back to SavedState so process recreation rehydrates the stage.
 */
internal fun rehydrateTimerCompletionUi(
    live: TimerCompletionUiState,
    savedState: TimerCompletionSavedState,
): TimerCompletionUiState {
    val liveActive = live.draft != null ||
        live.saving ||
        live.saveError != null ||
        live.hasPostSaveStage
    return if (liveActive) live else savedState.restore()
}

/**
 * SavedStateHandle adapter for timer completion UI.
 *
 * Survives configuration change and process recreation with the activity. Draft is a single
 * Serializable blob (all-or-nothing). Pending next-feed baby/suggestedAt keep the historical
 * keys used by schedule/reconcile so identity and UI stay aligned.
 */
internal class TimerCompletionSavedState(
    private val handle: SavedStateHandle,
) {
    fun persist(state: TimerCompletionUiState) {
        if (state.draft != null) {
            handle[DRAFT_KEY] = state.draft
        } else {
            handle.remove<NursingCompletionDraft>(DRAFT_KEY)
        }
        handle[SAVING_KEY] = state.saving
        if (state.saveError != null) {
            handle[SAVE_ERROR_KEY] = state.saveError
        } else {
            handle.remove<String>(SAVE_ERROR_KEY)
        }
        handle[PENDING_EXIT_KEY] = state.pendingExit
        when {
            state.pendingNextFeedSuggestedAt != null -> {
                handle[PENDING_NEXT_FEED_SUGGESTED_AT_KEY] = state.pendingNextFeedSuggestedAt
            }
            state.pendingExit -> clearPendingNextFeedIdentity()
            state.draft != null -> {
                // Sheet open: not a post-save offer. Leave any prior baby key alone only if
                // mid-resume; otherwise no offer keys should linger with a draft.
            }
            else -> clearPendingNextFeedIdentity()
        }
    }

    fun restore(): TimerCompletionUiState {
        val draft = handle.get<NursingCompletionDraft>(DRAFT_KEY)
        val saving = handle.get<Boolean>(SAVING_KEY) ?: false
        val saveError = handle.get<String>(SAVE_ERROR_KEY)
        val pendingExit = handle.get<Boolean>(PENDING_EXIT_KEY) ?: false
        val suggestedAt = handle.get<Long>(PENDING_NEXT_FEED_SUGGESTED_AT_KEY)
        return when {
            pendingExit && draft == null -> TimerCompletionUiState(pendingExit = true)
            draft != null -> TimerCompletionUiState(
                draft = draft,
                saving = saving,
                saveError = saveError,
            )
            // Post-save next-feed: suggestedAt is enough for UI; schedule uses baby key.
            suggestedAt != null -> TimerCompletionUiState(pendingNextFeedSuggestedAt = suggestedAt)
            else -> TimerCompletionUiState()
        }
    }

    fun savePendingNextFeedIdentity(babyId: Long, suggestedAt: Long) {
        handle[PENDING_NEXT_FEED_BABY_KEY] = babyId
        handle[PENDING_NEXT_FEED_SUGGESTED_AT_KEY] = suggestedAt
    }

    fun pendingNextFeedBabyId(): Long? = handle.get(PENDING_NEXT_FEED_BABY_KEY)

    fun pendingNextFeedSuggestedAt(): Long? = handle.get(PENDING_NEXT_FEED_SUGGESTED_AT_KEY)

    fun clearPendingNextFeedIdentity() {
        handle.remove<Long>(PENDING_NEXT_FEED_BABY_KEY)
        handle.remove<Long>(PENDING_NEXT_FEED_SUGGESTED_AT_KEY)
    }

    fun clearPendingExit() {
        handle[PENDING_EXIT_KEY] = false
    }

    companion object {
        const val DRAFT_KEY = "timer_completion_draft"
        const val SAVING_KEY = "timer_completion_saving"
        const val SAVE_ERROR_KEY = "timer_completion_save_error"
        const val PENDING_EXIT_KEY = "timer_completion_pending_exit"
        /** Shared with historical TimerViewModel schedule/reconcile keys. */
        const val PENDING_NEXT_FEED_BABY_KEY = "timer_pending_next_feed_baby"
        const val PENDING_NEXT_FEED_SUGGESTED_AT_KEY = "timer_pending_next_feed_suggested_at"
    }
}
