package com.lezi.babylog.feature.timer

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Public seams for timer completion UI across configuration / process recreation.
 * Observes pure reducers, SavedState, and resume decision — not private VM helpers.
 */
// Shared harness extracted for ticket 08.

internal fun sampleDraft(
    left: String = "5",
    right: String = "3",
    note: String = "note",
) = NursingCompletionDraft(
    leftMinutes = left,
    rightMinutes = right,
    order = "LR",
    amountMl = "30",
    note = note,
    startedAt = 1_700_000_000_000L,
    endedAt = 1_700_000_600_000L,
    capturedAt = 1_700_000_600_000L,
    carePlanId = 7L,
)


internal fun openWithIdentity(
    draft: NursingCompletionDraft = sampleDraft(),
    uuid: String? = "session-uuid",
    babyId: Long? = 11L,
) = openTimerCompletionSheet(
    current = TimerCompletionUiState(),
    draft = draft,
    completionClientUuid = uuid,
    sessionBabyId = babyId,
)

// --- open / edit / dismiss sheet ---


