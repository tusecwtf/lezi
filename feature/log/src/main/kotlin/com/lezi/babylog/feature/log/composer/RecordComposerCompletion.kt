package com.lezi.babylog.feature.log.composer
import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

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

/** Pure mapping: outcome + current UI → post-save UiState fields (no SavedState I/O). */
internal fun mapComposerPostSaveUiState(
    current: RecordComposerUiState,
    outcome: ComposerPostSaveOutcome,
    committedPhotos: List<String>,
): RecordComposerUiState {
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
            // Drop write-session identity from observable state; restorable keys cleared by persist.
            activeRequest = null,
            loading = false,
            initialDraft = null,
            pendingNextFeedOffer = outcome.pending,
            pendingFinishMessage = null,
        )
        is ComposerPostSaveOutcome.Finished -> current.copy(
            saving = false,
            error = null,
            draft = draftAfterCommit,
            activeRequest = null,
            loading = false,
            initialDraft = null,
            pendingNextFeedOffer = null,
            pendingFinishMessage = outcome.message,
        )
    }
}

/**
 * Persist durable post-save stage and drop restorable New/Edit request/draft so process death
 * cannot rewrite the fact. Call only after the domain fact/plan write has succeeded.
 */
internal fun persistComposerPostSave(
    savedState: RecordComposerSavedState,
    outcome: ComposerPostSaveOutcome,
) {
    when (outcome) {
        is ComposerPostSaveOutcome.NextFeedOffer -> {
            savedState.savePendingNextFeed(outcome.pending)
            savedState.clearPendingFinishMessage()
        }
        is ComposerPostSaveOutcome.Finished -> {
            savedState.clearPendingNextFeed()
            savedState.savePendingFinishMessage(outcome.message)
        }
    }
    // Fact is durable — drop restorable New/Edit request so process death cannot rewrite it.
    savedState.clear()
}

/**
 * Command + query entry for post-write stage: mutates SavedState then returns mapped UiState.
 * Prefer calling from a single VM path that also publishes `_state` (see [RecordComposerViewModel]).
 */
internal fun applyComposerPostSaveOutcome(
    current: RecordComposerUiState,
    savedState: RecordComposerSavedState,
    outcome: ComposerPostSaveOutcome,
    committedPhotos: List<String>,
): RecordComposerUiState {
    persistComposerPostSave(savedState, outcome)
    return mapComposerPostSaveUiState(current, outcome, committedPhotos)
}

/** Rehydrate durable post-save fields from live state with SavedState fallback. */
internal fun rehydrateComposerPostSaveStage(
    pendingNextFeedOffer: PendingNextFeed?,
    pendingFinishMessage: String?,
    savedState: RecordComposerSavedState,
): ComposerPostSaveStage = ComposerPostSaveStage(
    pendingNextFeedOffer = pendingNextFeedOffer ?: savedState.pendingNextFeed(),
    pendingFinishMessage = pendingFinishMessage ?: savedState.pendingFinishMessage(),
)

/** True while a next-feed offer or one-shot finish copy still owns the composition. */
internal fun hasComposerPostSaveStage(
    pendingNextFeedOffer: PendingNextFeed?,
    pendingFinishMessage: String?,
): Boolean = pendingNextFeedOffer != null || pendingFinishMessage != null

/**
 * Write-session open is refused while a post-save stage is live so process recreation cannot
 * re-arm a restorable New draft under an open next-feed offer (AC: 进程重建不能重复写事实).
 */
internal fun shouldOpenComposerWriteSession(
    request: RecordComposerRequest?,
    pendingNextFeedOffer: PendingNextFeed?,
    pendingFinishMessage: String?,
): Boolean = request != null &&
    !hasComposerPostSaveStage(pendingNextFeedOffer, pendingFinishMessage)

/** UiState after the sheet session closes while a post-save stage may still be open. */
internal fun recordComposerClosedUiState(
    pendingNextFeedOffer: PendingNextFeed?,
    pendingFinishMessage: String?,
    failureKind: FailureKind? = null,
): RecordComposerUiState = RecordComposerUiState(
    pendingNextFeedOffer = pendingNextFeedOffer,
    pendingFinishMessage = pendingFinishMessage,
    failureKind = failureKind,
)

/**
 * Host presentation of VM post-save state.
 *
 * Always invokes [onConsumeRootRequest] when a post-save stage is present so the Activity root
 * restorable request is nulled (must be idempotent beyond nulling — e.g. refresh widgets only when
 * a root was actually open). Presents finish only when no offer remains; callers must acknowledge
 * finish so [pendingFinishMessage] does not re-fire.
 */
internal fun consumeComposerPostSavePresentation(
    pendingNextFeedOffer: PendingNextFeed?,
    pendingFinishMessage: String?,
    onConsumeRootRequest: () -> Unit,
    onPresentFinish: (String) -> Unit,
) {
    if (hasComposerPostSaveStage(pendingNextFeedOffer, pendingFinishMessage)) {
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
 * Keeps parity with [com.lezi.babylog.feature.settings.calendar.carePlanReminderPermissionDeniedStatus].
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
