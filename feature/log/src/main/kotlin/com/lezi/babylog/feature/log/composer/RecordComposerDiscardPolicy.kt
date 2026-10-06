package com.lezi.babylog.feature.log.composer
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*
internal enum class ComposerDismissSource {
    SystemBack,
    HeaderClose,
    FooterCancel,
    SheetDismiss,
}

internal enum class ComposerDismissDecision {
    DismissNow,
    ConfirmDiscard,
    IgnoreWhileBusy,
}

/** One decision authority for every user-reachable Composer exit. */
internal fun decideRecordComposerDismiss(
    source: ComposerDismissSource,
    hasUserChanges: Boolean,
    busy: Boolean,
    viewing: Boolean = false,
): ComposerDismissDecision {
    if (viewing) return ComposerDismissDecision.DismissNow
    val sharedDecision = when {
        busy -> ComposerDismissDecision.IgnoreWhileBusy
        hasUserChanges -> ComposerDismissDecision.ConfirmDiscard
        else -> ComposerDismissDecision.DismissNow
    }
    return when (source) {
        ComposerDismissSource.SystemBack,
        ComposerDismissSource.HeaderClose,
        ComposerDismissSource.FooterCancel,
        ComposerDismissSource.SheetDismiss,
        -> sharedDecision
    }
}

/** Ownership bookkeeping is not user-visible; every other draft field is part of the edit. */
internal fun hasRecordComposerUserChanges(
    initial: QuickRecordDraft,
    current: QuickRecordDraft,
): Boolean = initial.forUserChangeComparison() != current.forUserChangeComparison()

private fun QuickRecordDraft.forUserChangeComparison(): QuickRecordDraft = copy(
    ownedDraftPhotos = emptyList(),
)
