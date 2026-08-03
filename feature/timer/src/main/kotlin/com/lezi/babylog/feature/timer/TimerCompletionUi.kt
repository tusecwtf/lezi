package com.lezi.babylog.feature.timer

import androidx.lifecycle.SavedStateHandle

/**
 * Durable post-success next-feed offer for the nursing timer.
 *
 * Single Serializable blob (baby + suggestedAt) — all-or-nothing restore, matching the
 * Composer pending-next-feed contract so identity and UI cannot partially desync across
 * process recreation.
 */
internal data class TimerPendingNextFeed(
    val babyId: Long,
    val suggestedAt: Long,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Observable completion stage for the nursing timer.
 *
 * Single source of truth for the confirmation sheet, in-flight save, and post-success
 * next-feed / exit. Host compositions subscribe — they must not dual-master draft/saving
 * in Compose `remember` locals that die on configuration change.
 *
 * [completionClientUuid] / [sessionBabyId] are the durable submit identity for idempotent
 * process-death recovery. They live with the completion stage (not only in timer DataStore)
 * so a fail-closed empty timer restore cannot desync Saving from the replay key.
 */
internal data class TimerCompletionUiState(
    val draft: NursingCompletionDraft? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    /** Post-success next-feed offer; durable in [TimerCompletionSavedState] as one blob. */
    val pendingNextFeed: TimerPendingNextFeed? = null,
    /**
     * One-shot exit after success without a next-feed offer, or after the offer is finished.
     * Durable until the Host acknowledges navigation so a late composition still exits once.
     */
    val pendingExit: Boolean = false,
    /** Domain committed, but the durable timer snapshot still needs idempotent clearing. */
    val timerClearPending: Boolean = false,
    /** Idempotent completeNursing key; kept until post-save stage is published. */
    val completionClientUuid: String? = null,
    /** Session baby for replay when timer DataStore is empty; cleared with submit identity. */
    val sessionBabyId: Long? = null,
    /**
     * Frozen ordered handoff/completion photo paths for mid-Saving process-death replay.
     * Null means not frozen yet (prefer live [TimerState.handoffSeed]); empty list means
     * frozen with no seed photos. Survives fail-closed empty timer restore.
     */
    val completionPhotoPaths: List<String>? = null,
) {
    val sheetVisible: Boolean
        get() = draft != null

    val hasPostSaveStage: Boolean
        get() = pendingNextFeed != null || pendingExit

    /** Navigation is safe only after the successful fact cannot restore a ghost timer. */
    val readyToExit: Boolean
        get() = pendingExit && !timerClearPending

    /** Convenience for hosts that only need the suggested clock time. */
    val pendingNextFeedSuggestedAt: Long?
        get() = pendingNextFeed?.suggestedAt
}

/**
 * Sheet is idle for user edits (open/update/dismiss). Not while Saving or after domain success.
 */
internal fun canMutateSheet(current: TimerCompletionUiState): Boolean =
    !current.saving && !current.hasPostSaveStage

/** Open the confirmation sheet with a frozen draft (not while a post-save stage owns the flow). */
internal fun openTimerCompletionSheet(
    current: TimerCompletionUiState,
    draft: NursingCompletionDraft,
    completionClientUuid: String? = null,
    sessionBabyId: Long? = null,
    completionPhotoPaths: List<String>? = null,
): TimerCompletionUiState {
    if (!canMutateSheet(current)) return current
    return TimerCompletionUiState(
        draft = draft,
        completionClientUuid = completionClientUuid,
        sessionBabyId = sessionBabyId,
        completionPhotoPaths = completionPhotoPaths,
    )
}

/** Edit draft while the sheet is open and not saving. */
internal fun updateTimerCompletionDraft(
    current: TimerCompletionUiState,
    draft: NursingCompletionDraft,
): TimerCompletionUiState {
    if (!canMutateSheet(current) || current.draft == null) return current
    return current.copy(draft = draft, saveError = null)
}

/** Dismiss the sheet only when not mid-save and no post-save stage. */
internal fun dismissTimerCompletionSheet(
    current: TimerCompletionUiState,
): TimerCompletionUiState {
    if (!canMutateSheet(current)) return current
    return TimerCompletionUiState()
}

/**
 * Whether a new domain-complete job may start. When already saving, post-save, or no draft,
 * re-confirm is a no-op: the observable state already owns the flow.
 */
internal fun mayStartTimerCompletionSave(state: TimerCompletionUiState): Boolean =
    canMutateSheet(state) && state.draft != null

/** Enter Saving with the confirmed draft; clears prior error. No-op if already saving. */
internal fun beginTimerCompletionSave(
    current: TimerCompletionUiState,
    draft: NursingCompletionDraft,
    completionPhotoPaths: List<String>? = null,
): TimerCompletionUiState {
    if (current.saving || !current.sheetVisible || current.hasPostSaveStage) return current
    return current.copy(
        draft = draft,
        saving = true,
        saveError = null,
        // Freeze seed photos at save start when open did not (or seed arrived late).
        completionPhotoPaths = current.completionPhotoPaths ?: completionPhotoPaths,
    )
}

/**
 * Pre-flight validation failure (before Saving). Keeps sheet open with the rejected draft.
 * Fail-closed while Saving or post-save.
 */
internal fun timerCompletionValidationFailed(
    current: TimerCompletionUiState,
    draft: NursingCompletionDraft,
    error: String,
): TimerCompletionUiState {
    if (current.saving || current.hasPostSaveStage) return current
    return current.copy(draft = draft, saving = false, saveError = error)
}

/** Domain/validation failure → retryable sheet with the same draft (identity retained). */
internal fun timerCompletionSaveFailed(
    current: TimerCompletionUiState,
    error: String,
): TimerCompletionUiState {
    if (current.hasPostSaveStage) return current
    return current.copy(saving = false, saveError = error)
}

/**
 * Domain success: clear sheet and publish either a next-feed offer or a consumable exit.
 * Call only after completeNursing has returned (idempotent on [TimerCompletionUiState.completionClientUuid]).
 * Illegal when not currently Saving — pure layer is total; VM must not rely on AtomicBoolean alone.
 */
internal fun timerCompletionSucceeded(
    current: TimerCompletionUiState,
    pendingNextFeed: TimerPendingNextFeed?,
): TimerCompletionUiState {
    if (!current.saving) return current
    return if (pendingNextFeed != null) {
        TimerCompletionUiState(
            pendingNextFeed = pendingNextFeed,
            timerClearPending = true,
        )
    } else {
        TimerCompletionUiState(
            pendingExit = true,
            timerClearPending = true,
        )
    }
}

/** Durable timer JSON is empty; post-save offer/exit may now proceed. */
internal fun timerCompletionTimerCleared(
    current: TimerCompletionUiState,
): TimerCompletionUiState {
    if (!current.timerClearPending) return current
    return current.copy(timerClearPending = false)
}

/** Host acknowledged exit navigation — safe to re-subscribe without re-navigating. */
internal fun consumeTimerPendingExit(
    current: TimerCompletionUiState,
): TimerCompletionUiState {
    if (!current.pendingExit) return current
    return current.copy(pendingExit = false)
}

/**
 * Host finished the shared next-feed flow (scheduled or skipped).
 * Publishes durable [TimerCompletionUiState.pendingExit] so process death after offer consumption
 * still re-exits (same consumable token as the no-offer success path).
 */
internal fun finishTimerNextFeedToExit(
    current: TimerCompletionUiState,
): TimerCompletionUiState {
    if (current.pendingNextFeed == null) return current
    return TimerCompletionUiState(
        pendingExit = true,
        timerClearPending = current.timerClearPending,
    )
}

/**
 * After process death the completion job is gone. Resume only when SavedState still says
 * Saving with a draft and no post-save stage (domain may or may not have committed —
 * completeNursing is idempotent on completionClientUuid).
 */
internal fun shouldResumeTimerCompletionSave(state: TimerCompletionUiState): Boolean =
    state.saving && state.draft != null && !state.hasPostSaveStage

/**
 * Pure decision for process-death / init convergence of the completion stage.
 *
 * Promote to next-feed/exit only via durable [TimerCompletionUiState.hasPostSaveStage]
 * (domain success already published). Mid-save without a durable submit identity is
 * fail-closed retryable sheet — never false-success pendingExit.
 */
internal sealed class TimerCompletionResumeDecision {
    data object None : TimerCompletionResumeDecision()
    /** Post-save published but timer DataStore not cleared yet. */
    data object FinishClearTimer : TimerCompletionResumeDecision()
    /** Mid-save with durable uuid — replay completeNursing (idempotent). */
    data class ReplayInFlightSave(
        val draft: NursingCompletionDraft,
        val completionClientUuid: String,
        val sessionBabyId: Long?,
        val completionPhotoPaths: List<String>? = null,
    ) : TimerCompletionResumeDecision()
    /** Saving without session identity — keep draft, surface retryable error. */
    data class FailClosedRetryable(
        val draft: NursingCompletionDraft,
        val error: String,
        val completionClientUuid: String?,
        val sessionBabyId: Long?,
    ) : TimerCompletionResumeDecision()
}

internal const val TIMER_COMPLETION_SESSION_EXPIRED_MESSAGE = "会话已失效，请重试"

internal fun decideTimerCompletionResume(
    stage: TimerCompletionUiState,
    hasTimerData: Boolean,
    timerSessionUuid: String?,
): TimerCompletionResumeDecision {
    if (stage.hasPostSaveStage) {
        return if (hasTimerData || stage.timerClearPending) {
            TimerCompletionResumeDecision.FinishClearTimer
        } else {
            TimerCompletionResumeDecision.None
        }
    }
    if (!shouldResumeTimerCompletionSave(stage)) {
        return TimerCompletionResumeDecision.None
    }
    val draft = requireNotNull(stage.draft)
    val uuid = stage.completionClientUuid ?: timerSessionUuid
    return if (uuid != null) {
        TimerCompletionResumeDecision.ReplayInFlightSave(
            draft = draft,
            completionClientUuid = uuid,
            sessionBabyId = stage.sessionBabyId,
            completionPhotoPaths = stage.completionPhotoPaths,
        )
    } else {
        TimerCompletionResumeDecision.FailClosedRetryable(
            draft = draft,
            error = TIMER_COMPLETION_SESSION_EXPIRED_MESSAGE,
            completionClientUuid = stage.completionClientUuid,
            sessionBabyId = stage.sessionBabyId,
        )
    }
}

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
 * Serializable blob (all-or-nothing). Next-feed is a single [TimerPendingNextFeed] blob.
 * Submit identity ([completionClientUuid] / baby) is durable with Saving/draft until post-save.
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
        handle[TIMER_CLEAR_PENDING_KEY] = state.timerClearPending
        persistPendingNextFeed(state)
        persistCompletionIdentity(state)
        persistCompletionPhotoPaths(state)
    }

    /**
     * Next-feed keys: write blob when offer is live; clear on exit/idle; clear on sheet
     * (draft open / mid-save is never a post-save offer).
     */
    private fun persistPendingNextFeed(state: TimerCompletionUiState) {
        when {
            state.pendingNextFeed != null -> savePendingNextFeed(state.pendingNextFeed)
            else -> clearPendingNextFeed()
        }
    }

    /**
     * Submit identity lifetime: present while sheet/Saving (draft or saving); cleared once
     * post-save owns the flow or the stage is fully idle.
     */
    private fun persistCompletionIdentity(state: TimerCompletionUiState) {
        when {
            state.hasPostSaveStage -> clearCompletionIdentity()
            state.draft != null || state.saving -> {
                if (state.completionClientUuid != null) {
                    handle[COMPLETION_CLIENT_UUID_KEY] = state.completionClientUuid
                } else {
                    handle.remove<String>(COMPLETION_CLIENT_UUID_KEY)
                }
                if (state.sessionBabyId != null) {
                    handle[SESSION_BABY_ID_KEY] = state.sessionBabyId
                } else {
                    handle.remove<Long>(SESSION_BABY_ID_KEY)
                }
            }
            else -> clearCompletionIdentity()
        }
    }

    /**
     * Seed photo paths lifetime mirrors submit identity: present with sheet/Saving so
     * ReplayInFlightSave can attach handoff imports when timer DataStore seed is gone.
     */
    private fun persistCompletionPhotoPaths(state: TimerCompletionUiState) {
        when {
            state.hasPostSaveStage -> clearCompletionPhotoPaths()
            state.draft != null || state.saving -> {
                if (state.completionPhotoPaths != null) {
                    handle[COMPLETION_PHOTO_PATHS_KEY] =
                        ArrayList(state.completionPhotoPaths)
                } else {
                    handle.remove<ArrayList<String>>(COMPLETION_PHOTO_PATHS_KEY)
                }
            }
            else -> clearCompletionPhotoPaths()
        }
    }

    fun restore(): TimerCompletionUiState {
        val draft = handle.get<NursingCompletionDraft>(DRAFT_KEY)
        val saving = handle.get<Boolean>(SAVING_KEY) ?: false
        val saveError = handle.get<String>(SAVE_ERROR_KEY)
        val pendingExit = handle.get<Boolean>(PENDING_EXIT_KEY) ?: false
        val timerClearPending = handle.get<Boolean>(TIMER_CLEAR_PENDING_KEY) ?: false
        val pendingNextFeed = pendingNextFeed()
        val completionClientUuid = handle.get<String>(COMPLETION_CLIENT_UUID_KEY)
        val sessionBabyId = handle.get<Long>(SESSION_BABY_ID_KEY)
        val completionPhotoPaths = handle.get<ArrayList<String>>(COMPLETION_PHOTO_PATHS_KEY)
            ?.toList()
        return when {
            pendingExit && draft == null -> TimerCompletionUiState(
                pendingExit = true,
                timerClearPending = timerClearPending,
            )
            draft != null -> TimerCompletionUiState(
                draft = draft,
                saving = saving,
                saveError = saveError,
                completionClientUuid = completionClientUuid,
                sessionBabyId = sessionBabyId,
                completionPhotoPaths = completionPhotoPaths,
            )
            pendingNextFeed != null -> TimerCompletionUiState(
                pendingNextFeed = pendingNextFeed,
                timerClearPending = timerClearPending,
            )
            else -> TimerCompletionUiState()
        }
    }

    fun savePendingNextFeed(pending: TimerPendingNextFeed) {
        handle[PENDING_NEXT_FEED_KEY] = pending
        clearLegacyPendingNextFeedKeys()
    }

    fun pendingNextFeed(): TimerPendingNextFeed? {
        handle.get<TimerPendingNextFeed>(PENDING_NEXT_FEED_KEY)?.let { return it }
        val legacy = readLegacyPendingNextFeed() ?: return null
        handle[PENDING_NEXT_FEED_KEY] = legacy
        clearLegacyPendingNextFeedKeys()
        return legacy
    }

    fun clearPendingNextFeed() {
        handle.remove<TimerPendingNextFeed>(PENDING_NEXT_FEED_KEY)
        clearLegacyPendingNextFeedKeys()
    }

    /** @deprecated Prefer [pendingNextFeed]; kept for schedule call sites during migration. */
    fun pendingNextFeedBabyId(): Long? = pendingNextFeed()?.babyId

    fun pendingNextFeedSuggestedAt(): Long? = pendingNextFeed()?.suggestedAt

    private fun clearCompletionIdentity() {
        handle.remove<String>(COMPLETION_CLIENT_UUID_KEY)
        handle.remove<Long>(SESSION_BABY_ID_KEY)
    }

    private fun clearCompletionPhotoPaths() {
        handle.remove<ArrayList<String>>(COMPLETION_PHOTO_PATHS_KEY)
    }

    private fun readLegacyPendingNextFeed(): TimerPendingNextFeed? {
        val babyId = handle.get<Long>(LEGACY_PENDING_NEXT_FEED_BABY_KEY) ?: return null
        val suggestedAt = handle.get<Long>(LEGACY_PENDING_NEXT_FEED_SUGGESTED_AT_KEY) ?: return null
        return TimerPendingNextFeed(babyId = babyId, suggestedAt = suggestedAt)
    }

    private fun clearLegacyPendingNextFeedKeys() {
        handle.remove<Long>(LEGACY_PENDING_NEXT_FEED_BABY_KEY)
        handle.remove<Long>(LEGACY_PENDING_NEXT_FEED_SUGGESTED_AT_KEY)
    }

    private companion object {
        const val DRAFT_KEY = "timer_completion_draft"
        const val SAVING_KEY = "timer_completion_saving"
        const val SAVE_ERROR_KEY = "timer_completion_save_error"
        const val PENDING_EXIT_KEY = "timer_completion_pending_exit"
        const val TIMER_CLEAR_PENDING_KEY = "timer_completion_clear_pending"
        const val PENDING_NEXT_FEED_KEY = "timer_pending_next_feed"
        const val COMPLETION_CLIENT_UUID_KEY = "timer_completion_client_uuid"
        const val SESSION_BABY_ID_KEY = "timer_completion_session_baby"
        const val COMPLETION_PHOTO_PATHS_KEY = "timer_completion_photo_paths"
        /** Historical multi-key shape; migrated to [PENDING_NEXT_FEED_KEY] on restore. */
        const val LEGACY_PENDING_NEXT_FEED_BABY_KEY = "timer_pending_next_feed_baby"
        const val LEGACY_PENDING_NEXT_FEED_SUGGESTED_AT_KEY = "timer_pending_next_feed_suggested_at"
    }
}
