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
 * Observes reducers + SavedState only — not private VM helpers.
 */
class TimerCompletionUiTest {
    private fun sampleDraft(
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

    // --- open / edit / dismiss sheet ---

    @Test
    fun openSheetPublishesDraftWithoutSaving() {
        val draft = sampleDraft()
        val next = openTimerCompletionSheet(TimerCompletionUiState(), draft)
        assertEquals(draft, next.draft)
        assertFalse(next.saving)
        assertNull(next.saveError)
        assertNull(next.pendingNextFeedSuggestedAt)
        assertFalse(next.pendingExit)
        assertTrue(next.sheetVisible)
    }

    @Test
    fun updateDraftClearsErrorWhileSheetOpenAndIdle() {
        val draft = sampleDraft()
        val open = openTimerCompletionSheet(TimerCompletionUiState(), draft)
            .copy(saveError = "保存失败")
        val edited = draft.copy(note = "edited")
        val next = updateTimerCompletionDraft(open, edited)
        assertEquals("edited", next.draft?.note)
        assertNull(next.saveError)
    }

    @Test
    fun updateDraftIgnoredWhileSaving() {
        val draft = sampleDraft()
        val saving = beginTimerCompletionSave(
            openTimerCompletionSheet(TimerCompletionUiState(), draft),
            draft,
        )
        val next = updateTimerCompletionDraft(saving, draft.copy(note = "nope"))
        assertEquals(saving, next)
        assertEquals("note", next.draft?.note)
    }

    @Test
    fun dismissSheetClearsDraftWhenNotSaving() {
        val open = openTimerCompletionSheet(TimerCompletionUiState(), sampleDraft())
        val next = dismissTimerCompletionSheet(open)
        assertNull(next.draft)
        assertFalse(next.sheetVisible)
        assertNull(next.saveError)
    }

    @Test
    fun dismissSheetBlockedWhileSaving() {
        val draft = sampleDraft()
        val saving = beginTimerCompletionSave(
            openTimerCompletionSheet(TimerCompletionUiState(), draft),
            draft,
        )
        assertEquals(saving, dismissTimerCompletionSheet(saving))
        assertTrue(saving.saving)
        assertNotNull(saving.draft)
    }

    // --- save lifecycle ---

    @Test
    fun mayStartSaveOnlyWhenNotAlreadySaving() {
        assertTrue(mayStartTimerCompletionSave(saving = false))
        assertFalse(mayStartTimerCompletionSave(saving = true))
    }

    @Test
    fun beginSaveMarksBusyAndClearsError() {
        val draft = sampleDraft(note = "confirm")
        val open = openTimerCompletionSheet(TimerCompletionUiState(), sampleDraft())
            .copy(saveError = "old")
        val next = beginTimerCompletionSave(open, draft)
        assertTrue(next.saving)
        assertEquals(draft, next.draft)
        assertNull(next.saveError)
    }

    @Test
    fun saveFailedReturnsRetryableSheet() {
        val draft = sampleDraft()
        val saving = beginTimerCompletionSave(
            openTimerCompletionSheet(TimerCompletionUiState(), draft),
            draft,
        )
        val next = timerCompletionSaveFailed(saving, "请先添加宝宝")
        assertFalse(next.saving)
        assertEquals(draft, next.draft)
        assertEquals("请先添加宝宝", next.saveError)
        assertTrue(next.sheetVisible)
        assertFalse(next.hasPostSaveStage)
    }

    @Test
    fun successWithNextFeedClearsSheetAndPublishesOffer() {
        val draft = sampleDraft()
        val saving = beginTimerCompletionSave(
            openTimerCompletionSheet(TimerCompletionUiState(), draft),
            draft,
        )
        val next = timerCompletionSucceeded(saving, suggestedNextFeedAt = 1_800_000_000_000L)
        assertNull(next.draft)
        assertFalse(next.saving)
        assertNull(next.saveError)
        assertEquals(1_800_000_000_000L, next.pendingNextFeedSuggestedAt)
        assertFalse(next.pendingExit)
        assertTrue(next.hasPostSaveStage)
        assertFalse(next.sheetVisible)
    }

    @Test
    fun successWithoutNextFeedRequestsConsumableExit() {
        val draft = sampleDraft()
        val saving = beginTimerCompletionSave(
            openTimerCompletionSheet(TimerCompletionUiState(), draft),
            draft,
        )
        val next = timerCompletionSucceeded(saving, suggestedNextFeedAt = null)
        assertNull(next.draft)
        assertFalse(next.saving)
        assertTrue(next.pendingExit)
        assertNull(next.pendingNextFeedSuggestedAt)
        assertTrue(next.hasPostSaveStage)
    }

    @Test
    fun consumeExitIsIdempotentAndStopsReFire() {
        val pending = timerCompletionSucceeded(
            beginTimerCompletionSave(
                openTimerCompletionSheet(TimerCompletionUiState(), sampleDraft()),
                sampleDraft(),
            ),
            suggestedNextFeedAt = null,
        )
        val once = consumeTimerPendingExit(pending)
        assertFalse(once.pendingExit)
        assertFalse(once.hasPostSaveStage)
        assertEquals(once, consumeTimerPendingExit(once))
    }

    @Test
    fun consumeNextFeedClearsOfferWithoutReopeningSheet() {
        val pending = timerCompletionSucceeded(
            beginTimerCompletionSave(
                openTimerCompletionSheet(TimerCompletionUiState(), sampleDraft()),
                sampleDraft(),
            ),
            suggestedNextFeedAt = 99L,
        )
        val next = consumeTimerPendingNextFeed(pending)
        assertNull(next.pendingNextFeedSuggestedAt)
        assertNull(next.draft)
        assertFalse(next.pendingExit)
    }

    // --- SavedState round-trip (config + process recreation) ---

    @Test
    fun savedStateRestoresEditingSheet() {
        val handle = SavedStateHandle()
        val saved = TimerCompletionSavedState(handle)
        val open = openTimerCompletionSheet(TimerCompletionUiState(), sampleDraft(note = "persist"))
        saved.persist(open)

        val restored = TimerCompletionSavedState(handle).restore()
        assertEquals(open.draft, restored.draft)
        assertFalse(restored.saving)
        assertNull(restored.saveError)
    }

    @Test
    fun savedStateRestoresSavingBusySheet() {
        val handle = SavedStateHandle()
        val draft = sampleDraft(note = "in-flight")
        val saving = beginTimerCompletionSave(
            openTimerCompletionSheet(TimerCompletionUiState(), draft),
            draft,
        )
        TimerCompletionSavedState(handle).persist(saving)

        val restored = TimerCompletionSavedState(handle).restore()
        assertTrue(restored.saving)
        assertEquals(draft, restored.draft)
        assertNull(restored.saveError)
    }

    @Test
    fun savedStateRestoresSaveErrorOnRetryableSheet() {
        val handle = SavedStateHandle()
        val failed = timerCompletionSaveFailed(
            beginTimerCompletionSave(
                openTimerCompletionSheet(TimerCompletionUiState(), sampleDraft()),
                sampleDraft(),
            ),
            "保存失败",
        )
        TimerCompletionSavedState(handle).persist(failed)

        val restored = TimerCompletionSavedState(handle).restore()
        assertEquals("保存失败", restored.saveError)
        assertFalse(restored.saving)
        assertNotNull(restored.draft)
    }

    @Test
    fun savedStateRestoresNextFeedOfferAndBabyIdentity() {
        val handle = SavedStateHandle()
        val offer = timerCompletionSucceeded(
            beginTimerCompletionSave(
                openTimerCompletionSheet(TimerCompletionUiState(), sampleDraft()),
                sampleDraft(),
            ),
            suggestedNextFeedAt = 1_900L,
        )
        val saved = TimerCompletionSavedState(handle)
        saved.persist(offer)
        saved.savePendingNextFeedIdentity(babyId = 42L, suggestedAt = 1_900L)

        val recreated = TimerCompletionSavedState(handle)
        val restored = recreated.restore()
        assertEquals(1_900L, restored.pendingNextFeedSuggestedAt)
        assertNull(restored.draft)
        assertFalse(restored.saving)
        assertEquals(42L, recreated.pendingNextFeedBabyId())
        assertEquals(1_900L, recreated.pendingNextFeedSuggestedAt())
    }

    @Test
    fun savedStateRestoresPendingExitUntilConsumed() {
        val handle = SavedStateHandle()
        val exit = timerCompletionSucceeded(
            beginTimerCompletionSave(
                openTimerCompletionSheet(TimerCompletionUiState(), sampleDraft()),
                sampleDraft(),
            ),
            suggestedNextFeedAt = null,
        )
        TimerCompletionSavedState(handle).persist(exit)
        assertTrue(TimerCompletionSavedState(handle).restore().pendingExit)

        val consumed = consumeTimerPendingExit(exit)
        TimerCompletionSavedState(handle).persist(consumed)
        assertFalse(TimerCompletionSavedState(handle).restore().pendingExit)
    }

    @Test
    fun rehydratePrefersLiveStateThenSavedState() {
        val handle = SavedStateHandle()
        val saved = TimerCompletionSavedState(handle)
        val offer = timerCompletionSucceeded(
            beginTimerCompletionSave(
                openTimerCompletionSheet(TimerCompletionUiState(), sampleDraft()),
                sampleDraft(),
            ),
            suggestedNextFeedAt = 55L,
        )
        saved.persist(offer)

        // Live already advanced (e.g. exit consumed) must win over stale SavedState only when
        // rehydrate is given live; empty live falls back to durable.
        val fromDurable = rehydrateTimerCompletionUi(
            live = TimerCompletionUiState(),
            savedState = saved,
        )
        assertEquals(55L, fromDurable.pendingNextFeedSuggestedAt)

        val liveExit = offer.copy(pendingNextFeedSuggestedAt = null, pendingExit = true)
        val fromLive = rehydrateTimerCompletionUi(live = liveExit, savedState = saved)
        assertTrue(fromLive.pendingExit)
        assertNull(fromLive.pendingNextFeedSuggestedAt)
    }

    @Test
    fun shouldResumeInFlightSaveAfterProcessDeath() {
        val saving = beginTimerCompletionSave(
            openTimerCompletionSheet(TimerCompletionUiState(), sampleDraft()),
            sampleDraft(),
        )
        assertTrue(shouldResumeTimerCompletionSave(saving))

        val failed = timerCompletionSaveFailed(saving, "x")
        assertFalse(shouldResumeTimerCompletionSave(failed))

        val offer = timerCompletionSucceeded(saving, suggestedNextFeedAt = 1L)
        assertFalse(shouldResumeTimerCompletionSave(offer))

        val exit = timerCompletionSucceeded(saving, suggestedNextFeedAt = null)
        assertFalse(shouldResumeTimerCompletionSave(exit))

        assertFalse(shouldResumeTimerCompletionSave(TimerCompletionUiState()))
    }
}
