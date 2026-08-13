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
// Contract-cluster split (ticket 08).
class TimerCompletionSheetTest {
    @Test
    fun openSheetPublishesDraftWithoutSavingAndKeepsIdentity() {
        val draft = sampleDraft()
        val next = openWithIdentity(draft)
        assertEquals(draft, next.draft)
        assertFalse(next.saving)
        assertNull(next.saveError)
        assertNull(next.pendingNextFeed)
        assertFalse(next.pendingExit)
        assertTrue(next.sheetVisible)
        assertEquals("session-uuid", next.completionClientUuid)
        assertEquals(11L, next.sessionBabyId)
    }
    @Test
    fun openSheetBlockedWhileSavingOrPostSave() {
        val draft = sampleDraft()
        val saving = beginTimerCompletionSave(openWithIdentity(draft), draft)
        assertEquals(saving, openTimerCompletionSheet(saving, sampleDraft(note = "other")))
        val offer = timerCompletionSucceeded(
            saving,
            TimerPendingNextFeed(babyId = 1L, suggestedAt = 9L),
        )
        assertEquals(offer, openTimerCompletionSheet(offer, sampleDraft(note = "other")))
    }
    @Test
    fun updateDraftClearsErrorWhileSheetOpenAndIdle() {
        val draft = sampleDraft()
        val open = openWithIdentity(draft).copy(saveError = "保存失败")
        val edited = draft.copy(note = "edited")
        val next = updateTimerCompletionDraft(open, edited)
        assertEquals("edited", next.draft?.note)
        assertNull(next.saveError)
        assertEquals("session-uuid", next.completionClientUuid)
    }
    @Test
    fun updateDraftIgnoredWhileSaving() {
        val draft = sampleDraft()
        val saving = beginTimerCompletionSave(openWithIdentity(draft), draft)
        val next = updateTimerCompletionDraft(saving, draft.copy(note = "nope"))
        assertEquals(saving, next)
        assertEquals("note", next.draft?.note)
    }
    @Test
    fun dismissSheetClearsDraftAndIdentityWhenNotSaving() {
        val open = openWithIdentity()
        val next = dismissTimerCompletionSheet(open)
        assertNull(next.draft)
        assertFalse(next.sheetVisible)
        assertNull(next.saveError)
        assertNull(next.completionClientUuid)
        assertNull(next.sessionBabyId)
    }
    @Test
    fun dismissSheetBlockedWhileSaving() {
        val draft = sampleDraft()
        val saving = beginTimerCompletionSave(openWithIdentity(draft), draft)
        assertEquals(saving, dismissTimerCompletionSheet(saving))
        assertTrue(saving.saving)
        assertNotNull(saving.draft)
    }
    @Test
    fun mayStartSaveRequiresIdleSheetWithDraft() {
        assertTrue(mayStartTimerCompletionSave(openWithIdentity()))
        assertFalse(mayStartTimerCompletionSave(TimerCompletionUiState()))
        val draft = sampleDraft()
        val saving = beginTimerCompletionSave(openWithIdentity(draft), draft)
        assertFalse(mayStartTimerCompletionSave(saving))
        val offer = timerCompletionSucceeded(
            saving,
            TimerPendingNextFeed(1L, 2L),
        )
        assertFalse(mayStartTimerCompletionSave(offer))
    }
    @Test
    fun beginSaveMarksBusyAndClearsError_noOpWhenAlreadySaving() {
        val draft = sampleDraft(note = "confirm")
        val open = openWithIdentity(sampleDraft()).copy(saveError = "old")
        val next = beginTimerCompletionSave(open, draft)
        assertTrue(next.saving)
        assertEquals(draft, next.draft)
        assertNull(next.saveError)
        assertEquals("session-uuid", next.completionClientUuid)
        // Already saving: must not overwrite in-flight draft.
        val ignored = beginTimerCompletionSave(next, sampleDraft(note = "second"))
        assertEquals(next, ignored)
        assertEquals("confirm", ignored.draft?.note)
    }
    @Test
    fun beginSaveNoOpWhenSheetNotVisible() {
        val idle = TimerCompletionUiState()
        assertEquals(idle, beginTimerCompletionSave(idle, sampleDraft()))
    }
    @Test
    fun validationFailedReturnsRetryableSheetViaReducer() {
        val open = openWithIdentity()
        val bad = sampleDraft(note = "x".repeat(201))
        val next = timerCompletionValidationFailed(open, bad, "备注最多 200 字")
        assertFalse(next.saving)
        assertEquals(bad, next.draft)
        assertEquals("备注最多 200 字", next.saveError)
        assertEquals("session-uuid", next.completionClientUuid)
        // Illegal while saving.
        val saving = beginTimerCompletionSave(openWithIdentity(), sampleDraft())
        assertEquals(
            saving,
            timerCompletionValidationFailed(saving, sampleDraft(), "x"),
        )
    }
    @Test
    fun saveFailedReturnsRetryableSheet() {
        val draft = sampleDraft()
        val saving = beginTimerCompletionSave(openWithIdentity(draft), draft)
        val next = timerCompletionSaveFailed(saving, "请先添加宝宝")
        assertFalse(next.saving)
        assertEquals(draft, next.draft)
        assertEquals("请先添加宝宝", next.saveError)
        assertTrue(next.sheetVisible)
        assertFalse(next.hasPostSaveStage)
        assertEquals("session-uuid", next.completionClientUuid)
    }
    @Test
    fun successWithNextFeedClearsSheetAndPublishesOfferBlob() {
        val draft = sampleDraft()
        val saving = beginTimerCompletionSave(openWithIdentity(draft), draft)
        val offer = TimerPendingNextFeed(babyId = 42L, suggestedAt = 1_800_000_000_000L)
        val next = timerCompletionSucceeded(saving, pendingNextFeed = offer)
        assertNull(next.draft)
        assertFalse(next.saving)
        assertNull(next.saveError)
        assertEquals(offer, next.pendingNextFeed)
        assertEquals(1_800_000_000_000L, next.pendingNextFeedSuggestedAt)
        assertFalse(next.pendingExit)
        assertTrue(next.hasPostSaveStage)
        assertFalse(next.sheetVisible)
        assertNull(next.completionClientUuid)
    }
    @Test
    fun successWithoutNextFeedRequestsConsumableExit() {
        val draft = sampleDraft()
        val saving = beginTimerCompletionSave(openWithIdentity(draft), draft)
        val next = timerCompletionSucceeded(saving, pendingNextFeed = null)
        assertNull(next.draft)
        assertFalse(next.saving)
        assertTrue(next.pendingExit)
        assertNull(next.pendingNextFeed)
        assertTrue(next.hasPostSaveStage)
    }
    @Test
    fun postSaveExitWaitsForDurableTimerClearAndThenBecomesConsumable() {
        val saving = beginTimerCompletionSave(openWithIdentity(), sampleDraft())
        val committed = timerCompletionSucceeded(saving, pendingNextFeed = null)

        assertTrue(committed.timerClearPending)
        assertFalse(committed.readyToExit)

        val cleared = timerCompletionTimerCleared(committed)
        assertFalse(cleared.timerClearPending)
        assertTrue(cleared.readyToExit)
        assertEquals(cleared, timerCompletionTimerCleared(cleared))
    }
    @Test
    fun successIgnoredWhenNotSaving() {
        val open = openWithIdentity()
        assertEquals(open, timerCompletionSucceeded(open, pendingNextFeed = null))
        val idle = TimerCompletionUiState()
        assertEquals(idle, timerCompletionSucceeded(idle, null))
    }
    @Test
    fun consumeExitIsIdempotentAndStopsReFire() {
        val pending = timerCompletionSucceeded(
            beginTimerCompletionSave(openWithIdentity(), sampleDraft()),
            pendingNextFeed = null,
        )
        val once = consumeTimerPendingExit(pending)
        assertFalse(once.pendingExit)
        assertFalse(once.hasPostSaveStage)
        assertEquals(once, consumeTimerPendingExit(once))
    }
    @Test
    fun finishNextFeedPublishesDurableExit() {
        val pending = timerCompletionSucceeded(
            beginTimerCompletionSave(openWithIdentity(), sampleDraft()),
            pendingNextFeed = TimerPendingNextFeed(99L, 99L),
        )
        val next = finishTimerNextFeedToExit(pending)
        assertNull(next.pendingNextFeed)
        assertNull(next.draft)
        assertTrue(next.pendingExit)
        assertTrue(next.hasPostSaveStage)
        // Idle / no offer: no-op
        assertEquals(next, finishTimerNextFeedToExit(next))
    }
    @Test
    fun openSheetFreezesCompletionPhotoPathsForProcessDeathReplay() {
        val paths = listOf("handoff-owned.jpg", "plan.jpg")
        val open = openTimerCompletionSheet(
            current = TimerCompletionUiState(),
            draft = sampleDraft(),
            completionClientUuid = "session-uuid",
            sessionBabyId = 11L,
            completionPhotoPaths = paths,
        )
        assertEquals(paths, open.completionPhotoPaths)

        val saving = beginTimerCompletionSave(open, open.draft!!)
        assertEquals(paths, saving.completionPhotoPaths)

        val handle = SavedStateHandle()
        TimerCompletionSavedState(handle).persist(saving)
        val restored = TimerCompletionSavedState(handle).restore()
        assertTrue(restored.saving)
        assertEquals(paths, restored.completionPhotoPaths)

        val decision = decideTimerCompletionResume(
            stage = restored,
            hasTimerData = false, // fail-closed empty timer
            timerSessionUuid = null,
        )
        val replay = decision as TimerCompletionResumeDecision.ReplayInFlightSave
        assertEquals(paths, replay.completionPhotoPaths)
    }
    @Test
    fun beginSaveFreezesPhotoPathsWhenOpenHadNone() {
        val open = openWithIdentity()
        assertNull(open.completionPhotoPaths)
        val saving = beginTimerCompletionSave(
            current = open,
            draft = open.draft!!,
            completionPhotoPaths = listOf("late-seed.jpg"),
        )
        assertEquals(listOf("late-seed.jpg"), saving.completionPhotoPaths)
        // Already frozen wins over a second begin attempt (no-op while saving).
        val ignored = beginTimerCompletionSave(
            current = saving,
            draft = open.draft!!,
            completionPhotoPaths = listOf("other.jpg"),
        )
        assertEquals(saving, ignored)
    }
    @Test
    fun canMutateSheetAlignsOpenUpdateDismissAndMayStart() {
        val open = openWithIdentity()
        assertTrue(canMutateSheet(open))
        assertTrue(mayStartTimerCompletionSave(open))
        val saving = beginTimerCompletionSave(open, sampleDraft())
        assertFalse(canMutateSheet(saving))
        val exit = timerCompletionSucceeded(saving, null)
        assertFalse(canMutateSheet(exit))
    }
}
