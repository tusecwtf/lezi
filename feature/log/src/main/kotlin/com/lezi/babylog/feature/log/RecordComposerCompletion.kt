package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.RecordType

/**
 * Enforces the post-write ordering: prepare any reminder UI, then consume the restorable root
 * request before reporting final completion. This prevents process recreation from replaying a
 * successfully persisted New request.
 *
 * Prefer [applyComposerPostSaveOutcome] + [consumeComposerPostSavePresentation] for production
 * Host/ViewModel wiring so outcomes live in observable VM/SavedState rather than composition-local
 * dual masters.
 */
internal fun dispatchRecordSaveCompletion(
    message: String,
    suggestedNextFeedAt: Long?,
    onOfferReminder: (String, Long) -> Unit,
    onPersisted: () -> Unit,
    onFinished: (String) -> Unit,
) {
    if (suggestedNextFeedAt != null) onOfferReminder(message, suggestedNextFeedAt)
    onPersisted()
    if (suggestedNextFeedAt == null) onFinished(message)
}

/**
 * Successful domain write outcome for Composer. Fact is already durable; this only decides whether
 * to present the optional next-feed plan flow or finish with a single result message.
 */
internal sealed interface ComposerPostSaveOutcome {
    data class NextFeedOffer(val pending: PendingNextFeed) : ComposerPostSaveOutcome
    data class Finished(val message: String) : ComposerPostSaveOutcome
}

internal fun composerPostSaveOutcome(
    message: String,
    suggestedNextFeedAt: Long?,
    babyId: Long,
    type: RecordType,
): ComposerPostSaveOutcome =
    if (suggestedNextFeedAt != null) {
        ComposerPostSaveOutcome.NextFeedOffer(
            PendingNextFeed(
                babyId = babyId,
                type = type,
                suggestedAtMillis = suggestedNextFeedAt,
                factMessage = message,
            ),
        )
    } else {
        ComposerPostSaveOutcome.Finished(message)
    }

/**
 * Immediately consume the restorable request/draft and publish a single observable post-save stage.
 * Call only after the domain fact/plan write has succeeded.
 */
internal fun applyComposerPostSaveOutcome(
    current: RecordComposerUiState,
    savedState: RecordComposerSavedState,
    outcome: ComposerPostSaveOutcome,
    committedPhotos: List<String>,
): RecordComposerUiState {
    when (outcome) {
        is ComposerPostSaveOutcome.NextFeedOffer -> {
            val pending = outcome.pending
            savedState.savePendingNextFeed(
                babyId = pending.babyId,
                type = pending.type,
                suggestedAtMillis = pending.suggestedAtMillis,
                factMessage = pending.factMessage,
            )
        }
        is ComposerPostSaveOutcome.Finished -> savedState.clearPendingNextFeed()
    }
    // Fact is durable — drop restorable New/Edit request so process death cannot rewrite it.
    savedState.clear()
    val draftAfterCommit = current.draft?.copy(
        sourcePhotos = committedPhotos,
        borrowedPhotos = emptyList(),
        ownedDraftPhotos = emptyList(),
    )
    return when (outcome) {
        is ComposerPostSaveOutcome.NextFeedOffer -> current.copy(
            saving = false,
            error = null,
            draft = draftAfterCommit,
            pendingNextFeedOffer = outcome.pending,
            pendingFinishMessage = null,
        )
        is ComposerPostSaveOutcome.Finished -> current.copy(
            saving = false,
            error = null,
            draft = draftAfterCommit,
            pendingNextFeedOffer = null,
            pendingFinishMessage = outcome.message,
        )
    }
}

/** UiState after the sheet session closes while a post-save stage may still be open. */
internal fun recordComposerClosedUiState(
    pendingNextFeedOffer: PendingNextFeed?,
    pendingFinishMessage: String?,
): RecordComposerUiState = RecordComposerUiState(
    pendingNextFeedOffer = pendingNextFeedOffer,
    pendingFinishMessage = pendingFinishMessage,
)

/**
 * Host presentation of VM post-save state. Always consumes the restorable root when a post-save
 * stage is present; presents finish only when no offer remains. Safe to re-call on resubscribe —
 * callers must acknowledge finish so [pendingFinishMessage] does not re-fire.
 */
internal fun consumeComposerPostSavePresentation(
    pendingNextFeedOffer: PendingNextFeed?,
    pendingFinishMessage: String?,
    onConsumeRootRequest: () -> Unit,
    onPresentFinish: (String) -> Unit,
) {
    if (pendingNextFeedOffer != null || pendingFinishMessage != null) {
        onConsumeRootRequest()
    }
    if (pendingNextFeedOffer == null && pendingFinishMessage != null) {
        onPresentFinish(pendingFinishMessage)
    }
}

/** Care-plan create/edit/convert success snackbars (not feed-fact or fulfill). */
internal fun isCarePlanSaveMessage(message: String): Boolean =
    message.startsWith("已安排") ||
        message.startsWith("已转为护理计划") ||
        message == "已保存护理计划"

/**
 * Permission denial never blocks plan persistence; surface a clear local-reminder
 * degradation so users know why they may not get a notification.
 * Keeps parity with [com.lezi.babylog.feature.settings.carePlanReminderPermissionDeniedStatus].
 */
internal fun carePlanSaveMessageWithPermission(
    baseMessage: String,
    notificationPermissionGranted: Boolean,
    isCarePlanWrite: Boolean,
): String {
    if (!isCarePlanWrite || notificationPermissionGranted) return baseMessage
    return if (baseMessage == "已保存护理计划") {
        "护理计划已保存；通知权限未开启，本机提醒已降级"
    } else {
        "$baseMessage；通知权限未开启，本机提醒已降级"
    }
}
