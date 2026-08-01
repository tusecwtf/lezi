package com.lezi.babylog.feature.log

import androidx.lifecycle.SavedStateHandle
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.TimerHandoffSeed
import com.lezi.babylog.domain.CustomRecordItem

sealed interface RecordComposerRequest : java.io.Serializable {
    /**
     * Create a new fact or plan for a concrete record item.
     *
     * [type] remains the storage [RecordType]. When [type] is [RecordType.CUSTOM],
     * [customItemId] identifies the specific custom definition (required for new
     * catalog picks). [createIntent] freezes an explicit Calendar scheduling path;
     * otherwise the shared Composer derives fact versus plan from [timestamp].
     * CUSTOM creation always carries a concrete definition id.
     */
    data class New(
        val babyId: Long,
        val type: RecordType,
        val timestamp: Long,
        val historical: Boolean,
        val openSleepId: Long? = null,
        val lastAmountMl: Int? = null,
        /** Concrete custom definition when [type] is CUSTOM; null for built-ins. */
        val customItemId: Long? = null,
        /** Explicit Calendar scheduling remains a plan even if its initial time expires. */
        val createIntent: ComposerCreateIntent = ComposerCreateIntent.DeriveFromTimestamp,
    ) : RecordComposerRequest {
        init {
            require(type != RecordType.CUSTOM || customItemId?.takeIf { it > 0L } != null) {
                "CUSTOM requires positive customItemId"
            }
        }

        fun itemIdentity(): RecordItemIdentity = if (type == RecordType.CUSTOM) {
            RecordItemIdentity.custom(requireNotNull(customItemId))
        } else {
            RecordItemIdentity.BuiltIn(type)
        }
    }

    data class Edit(val recordId: Long) : RecordComposerRequest

    /** Open Composer in fulfill mode for a local care plan. */
    data class Fulfill(val carePlanId: Long) : RecordComposerRequest

    /** Open Composer to edit an open local care plan (not fulfill). */
    data class EditPlan(val carePlanId: Long) : RecordComposerRequest
}

/** SavedStateHandle adapter for the restorable Composer request/draft pair. */
internal class RecordComposerSavedState(
    private val handle: SavedStateHandle,
) {
    fun initialize(
        request: RecordComposerRequest,
        initialDraft: QuickRecordDraft,
        activeDraft: QuickRecordDraft = initialDraft,
    ) {
        handle[REQUEST_KEY] = request
        handle[INITIAL_DRAFT_KEY] = initialDraft
        handle[DRAFT_KEY] = activeDraft
    }

    fun update(request: RecordComposerRequest, draft: QuickRecordDraft) {
        if (handle.get<RecordComposerRequest>(REQUEST_KEY) != request) return
        handle[DRAFT_KEY] = draft
    }

    fun restore(request: RecordComposerRequest): QuickRecordDraft? =
        handle.get<RecordComposerRequest>(REQUEST_KEY)
            ?.takeIf { it == request }
            ?.let { handle[DRAFT_KEY] }

    fun restoreInitial(request: RecordComposerRequest): QuickRecordDraft? =
        handle.get<RecordComposerRequest>(REQUEST_KEY)
            ?.takeIf { it == request }
            ?.let { handle[INITIAL_DRAFT_KEY] }

    fun draftForCleanup(): QuickRecordDraft? = handle[DRAFT_KEY]

    /** Persist the whole offer as one Serializable blob (all-or-nothing restore). */
    fun savePendingNextFeed(pending: PendingNextFeed) {
        handle[PENDING_NEXT_FEED_KEY] = pending
        // Drop legacy multi-key shape if present from earlier builds.
        clearLegacyPendingNextFeedKeys()
    }

    fun pendingNextFeed(): PendingNextFeed? {
        handle.get<PendingNextFeed>(PENDING_NEXT_FEED_KEY)?.let { return it }
        // One-shot migration from pre-blob multi-key rows; rewrite as single blob.
        val legacy = readLegacyPendingNextFeed() ?: return null
        handle[PENDING_NEXT_FEED_KEY] = legacy
        clearLegacyPendingNextFeedKeys()
        return legacy
    }

    fun clearPendingNextFeed() {
        handle.remove<PendingNextFeed>(PENDING_NEXT_FEED_KEY)
        clearLegacyPendingNextFeedKeys()
    }

    /**
     * Durable one-shot finish copy for non-feed success (or after next-feed complete/skip).
     * Cleared when Host acknowledges presentation.
     */
    fun savePendingFinishMessage(message: String) {
        handle[PENDING_FINISH_MESSAGE_KEY] = message
    }

    fun pendingFinishMessage(): String? = handle[PENDING_FINISH_MESSAGE_KEY]

    fun clearPendingFinishMessage() {
        handle.remove<String>(PENDING_FINISH_MESSAGE_KEY)
    }

    /**
     * Persist Composer→Timer handoff lock + seed so process death mid-handoff cannot
     * unlock dismiss/cleanup before Timer accept/reject settles (Ticket 09).
     */
    fun savePendingTimerHandoff(seed: TimerHandoffSeed) {
        handle[TIMER_HANDOFF_PENDING_SEED_JSON_KEY] = seed.toJson()
        handle[TIMER_HANDOFF_IN_FLIGHT_KEY] = true
    }

    fun pendingTimerHandoffSeed(): TimerHandoffSeed? =
        TimerHandoffSeed.fromJson(handle.get<String>(TIMER_HANDOFF_PENDING_SEED_JSON_KEY))

    fun timerHandoffInFlight(): Boolean =
        handle.get<Boolean>(TIMER_HANDOFF_IN_FLIGHT_KEY) == true

    fun clearPendingTimerHandoff() {
        handle.remove<String>(TIMER_HANDOFF_PENDING_SEED_JSON_KEY)
        handle.remove<Boolean>(TIMER_HANDOFF_IN_FLIGHT_KEY)
    }

    fun clear() {
        handle.remove<RecordComposerRequest>(REQUEST_KEY)
        handle.remove<QuickRecordDraft>(DRAFT_KEY)
        handle.remove<QuickRecordDraft>(INITIAL_DRAFT_KEY)
        clearPendingTimerHandoff()
    }

    private fun readLegacyPendingNextFeed(): PendingNextFeed? {
        val babyId = handle.get<Long>(LEGACY_PENDING_NEXT_FEED_BABY_KEY) ?: return null
        val type = handle.get<String>(LEGACY_PENDING_NEXT_FEED_TYPE_KEY)
            ?.let(RecordType::fromKey) ?: return null
        val suggestedAtMillis = handle.get<Long>(LEGACY_PENDING_NEXT_FEED_SUGGESTED_AT_KEY)
            ?: return null
        val factMessage = handle.get<String>(LEGACY_PENDING_NEXT_FEED_MESSAGE_KEY) ?: return null
        return PendingNextFeed(babyId, type, suggestedAtMillis, factMessage)
    }

    private fun clearLegacyPendingNextFeedKeys() {
        handle.remove<Long>(LEGACY_PENDING_NEXT_FEED_BABY_KEY)
        handle.remove<String>(LEGACY_PENDING_NEXT_FEED_TYPE_KEY)
        handle.remove<Long>(LEGACY_PENDING_NEXT_FEED_SUGGESTED_AT_KEY)
        handle.remove<String>(LEGACY_PENDING_NEXT_FEED_MESSAGE_KEY)
    }

    private companion object {
        const val REQUEST_KEY = "record_composer_saved_request"
        const val DRAFT_KEY = "record_composer_saved_draft"
        const val INITIAL_DRAFT_KEY = "record_composer_saved_initial_draft"
        const val PENDING_NEXT_FEED_KEY = "pending_next_feed"
        const val PENDING_FINISH_MESSAGE_KEY = "pending_finish_message"
        const val TIMER_HANDOFF_PENDING_SEED_JSON_KEY = "timer_handoff_pending_seed_json"
        const val TIMER_HANDOFF_IN_FLIGHT_KEY = "timer_handoff_in_flight"
        const val LEGACY_PENDING_NEXT_FEED_BABY_KEY = "pending_next_feed_baby"
        const val LEGACY_PENDING_NEXT_FEED_TYPE_KEY = "pending_next_feed_type"
        const val LEGACY_PENDING_NEXT_FEED_SUGGESTED_AT_KEY = "pending_next_feed_suggested_at"
        const val LEGACY_PENDING_NEXT_FEED_MESSAGE_KEY = "pending_next_feed_message"
    }
}

/**
 * Durable post-fact next-feed offer: identity for schedule/reconcile, suggested time, and the
 * fact-success copy shown while the user chooses whether to plan. Survives request clear and
 * process recreation independently of the restorable Composer draft. Single Serializable blob in
 * SavedState — restore is all-or-nothing (no multi-key partial fallbacks).
 */
internal data class PendingNextFeed(
    val babyId: Long,
    val type: RecordType,
    val suggestedAtMillis: Long,
    val factMessage: String,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** In-memory + SavedState snapshot of any still-open post-write stage. */
internal data class ComposerPostSaveStage(
    val pendingNextFeedOffer: PendingNextFeed? = null,
    val pendingFinishMessage: String? = null,
) {
    val isActive: Boolean
        get() = pendingNextFeedOffer != null || pendingFinishMessage != null
}

internal data class RecordComposerUiState(
    val activeRequest: RecordComposerRequest? = null,
    val loading: Boolean = false,
    val draft: QuickRecordDraft? = null,
    val initialDraft: QuickRecordDraft? = null,
    val babyId: Long? = null,
    /** Used for age-based tips (e.g. complementary food). */
    val birthdayEpochDay: Long? = null,
    val amountStepMl: Int = 5,
    val timeStepMin: Int = 1,
    val timePickerStyle: String = "dropdown",
    val preferredHand: String = "right",
    val infantFeverAdviceEnabled: Boolean = true,
    val customItems: List<CustomRecordItem> = emptyList(),
    val canStartNursingTimer: Boolean = false,
    /** Settings.timerEnabled snapshot for the open session (ticket 16 workMode gating). */
    val timerEnabledSetting: Boolean = false,
    /** Device has enabled system calendar + chosen writable target (ticket 21). */
    val systemCalendarConfigured: Boolean = false,
    val saving: Boolean = false,
    val deleting: Boolean = false,
    val error: String? = null,
    /**
     * Observable next-feed offer after a feed fact succeeds. Single source of truth — not Compose
     * local rememberSaveable dual-mastered with SavedState identity.
     */
    val pendingNextFeedOffer: PendingNextFeed? = null,
    /**
     * One-shot finish copy (non-feed success, or next-feed completed/skipped). Durable in
     * SavedState until Host acknowledges; re-subscribe must not re-fire after acknowledge.
     */
    val pendingFinishMessage: String? = null,
    /**
     * Composer→Timer handoff launched; dismiss/import blocked until accept/reject settles.
     * Durable in [RecordComposerSavedState] across process death (not Compose remember).
     */
    val timerHandoffInFlight: Boolean = false,
) {
    val hasUserChanges: Boolean
        get() = initialDraft?.let { baseline ->
            draft?.let { current -> hasRecordComposerUserChanges(baseline, current) }
        } ?: false

    val hasPostSaveStage: Boolean
        get() = pendingNextFeedOffer != null || pendingFinishMessage != null
}

/** One immutable write decision for a confirm attempt; never resample wall-clock mode mid-save. */
internal enum class ComposerWriteDecision {
    UpdateCarePlan,
    FulfillCarePlan,
    ConvertRecordToCarePlan,
    ConfirmSleep,
    UpdateRecord,
    CreateCarePlan,
    AddRecord,
}

internal fun QuickRecordDraft.writeDecision(nowMillis: Long): ComposerWriteDecision = when {
    isEditingCarePlan -> ComposerWriteDecision.UpdateCarePlan
    carePlanId != null -> ComposerWriteDecision.FulfillCarePlan
    needsConvertToCarePlan(nowMillis) -> ComposerWriteDecision.ConvertRecordToCarePlan
    workMode(nowMillis) == ComposerWorkMode.ScheduleCare -> ComposerWriteDecision.CreateCarePlan
    type == RecordType.SLEEP && sleepAction in setOf(
        SleepDraftAction.SleepDown,
        SleepDraftAction.WakeUp,
    ) -> ComposerWriteDecision.ConfirmSleep
    existingRecordId != null -> ComposerWriteDecision.UpdateRecord
    else -> ComposerWriteDecision.AddRecord
}

/**
 * Ticket 16 / Composer S2: nursing timer is a stateful action — only for live
 * New (non-historical) or Fulfill (not edit-plan). ScheduleCare / EditPlan never
 * enable start-timer even if the draft type is nursing.
 */
internal fun computeCanStartNursingTimer(
    request: RecordComposerRequest,
    draft: QuickRecordDraft,
    timerEnabled: Boolean,
    nowMillis: Long = RecordTime.currentTimeMillis(),
): Boolean {
    if (!timerEnabled || draft.type != RecordType.NURSING) return false
    // Convert / schedule / edit-plan are intent-only — never expose start-timer.
    if (draft.needsConvertToCarePlan(nowMillis)) return false
    val mode = draft.workMode(nowMillis)
    if (mode == ComposerWorkMode.ScheduleCare || mode == ComposerWorkMode.EditPlan) {
        return false
    }
    return when (request) {
        is RecordComposerRequest.New -> !request.historical
        is RecordComposerRequest.Fulfill -> !draft.editCarePlan
        else -> false
    }
}
