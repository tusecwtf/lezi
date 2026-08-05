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
class TimerCompletionPersistResumeTest {
    @Test
    fun savedStateRestoresEditingSheetWithIdentity() {
        val handle = SavedStateHandle()
        val saved = TimerCompletionSavedState(handle)
        val open = openWithIdentity(sampleDraft(note = "persist"))
        saved.persist(open)

        val restored = TimerCompletionSavedState(handle).restore()
        assertEquals(open.draft, restored.draft)
        assertFalse(restored.saving)
        assertNull(restored.saveError)
        assertEquals("session-uuid", restored.completionClientUuid)
        assertEquals(11L, restored.sessionBabyId)
    }
    @Test
    fun savedStateRestoresSavingBusySheetWithIdentity() {
        val handle = SavedStateHandle()
        val draft = sampleDraft(note = "in-flight")
        val saving = beginTimerCompletionSave(openWithIdentity(draft), draft)
        TimerCompletionSavedState(handle).persist(saving)

        val restored = TimerCompletionSavedState(handle).restore()
        assertTrue(restored.saving)
        assertEquals(draft, restored.draft)
        assertNull(restored.saveError)
        assertEquals("session-uuid", restored.completionClientUuid)
        assertEquals(11L, restored.sessionBabyId)
    }
    @Test
    fun postSaveClearsCompletionPhotoPathsFromSavedState() {
        val open = openTimerCompletionSheet(
            current = TimerCompletionUiState(),
            draft = sampleDraft(),
            completionClientUuid = "u",
            sessionBabyId = 1L,
            completionPhotoPaths = listOf("a.jpg"),
        )
        val saving = beginTimerCompletionSave(open, open.draft!!)
        val success = timerCompletionSucceeded(saving, pendingNextFeed = null)
        assertNull(success.completionPhotoPaths)
        val handle = SavedStateHandle()
        TimerCompletionSavedState(handle).persist(success)
        assertNull(handle.get<ArrayList<String>>("timer_completion_photo_paths"))
    }
    @Test
    fun midSavePersistKeepsIdentityAndClearsStaleNextFeedKeys() {
        val handle = SavedStateHandle()
        // Stale offer from a previous session still on the handle.
        handle["timer_pending_next_feed_baby"] = 999L
        handle["timer_pending_next_feed_suggested_at"] = 1L
        val draft = sampleDraft()
        val saving = beginTimerCompletionSave(openWithIdentity(draft), draft)
        val saved = TimerCompletionSavedState(handle)
        saved.persist(saving)

        assertNull(saved.pendingNextFeed())
        val restored = saved.restore()
        assertTrue(restored.saving)
        assertEquals("session-uuid", restored.completionClientUuid)
        assertNull(restored.pendingNextFeed)
    }
    @Test
    fun savedStateRestoresSaveErrorOnRetryableSheet() {
        val handle = SavedStateHandle()
        val failed = timerCompletionSaveFailed(
            beginTimerCompletionSave(openWithIdentity(), sampleDraft()),
            "保存失败",
        )
        TimerCompletionSavedState(handle).persist(failed)

        val restored = TimerCompletionSavedState(handle).restore()
        assertEquals("保存失败", restored.saveError)
        assertFalse(restored.saving)
        assertNotNull(restored.draft)
        assertEquals("session-uuid", restored.completionClientUuid)
    }
    @Test
    fun savedStateRestoresNextFeedOfferAsSingleBlob() {
        val handle = SavedStateHandle()
        val offer = timerCompletionSucceeded(
            beginTimerCompletionSave(openWithIdentity(), sampleDraft()),
            pendingNextFeed = TimerPendingNextFeed(babyId = 42L, suggestedAt = 1_900L),
        )
        val saved = TimerCompletionSavedState(handle)
        saved.persist(offer)

        val recreated = TimerCompletionSavedState(handle)
        val restored = recreated.restore()
        assertEquals(1_900L, restored.pendingNextFeedSuggestedAt)
        assertEquals(42L, restored.pendingNextFeed?.babyId)
        assertNull(restored.draft)
        assertFalse(restored.saving)
        assertNull(restored.completionClientUuid)
        assertEquals(42L, recreated.pendingNextFeedBabyId())
        assertEquals(1_900L, recreated.pendingNextFeedSuggestedAt())
    }
    @Test
    fun savedStateMigratesLegacyNextFeedKeysToBlob() {
        val handle = SavedStateHandle()
        handle["timer_pending_next_feed_baby"] = 7L
        handle["timer_pending_next_feed_suggested_at"] = 55L
        val restored = TimerCompletionSavedState(handle).restore()
        assertEquals(TimerPendingNextFeed(7L, 55L), restored.pendingNextFeed)
        // Rewrite as blob; legacy keys cleared.
        assertNull(handle.get<Long>("timer_pending_next_feed_baby"))
        assertNotNull(handle.get<TimerPendingNextFeed>("timer_pending_next_feed"))
    }
    @Test
    fun savedStateRestoresPendingExitUntilConsumed() {
        val handle = SavedStateHandle()
        val exit = timerCompletionSucceeded(
            beginTimerCompletionSave(openWithIdentity(), sampleDraft()),
            pendingNextFeed = null,
        )
        TimerCompletionSavedState(handle).persist(exit)
        assertTrue(TimerCompletionSavedState(handle).restore().pendingExit)

        val consumed = consumeTimerPendingExit(exit)
        TimerCompletionSavedState(handle).persist(consumed)
        assertFalse(TimerCompletionSavedState(handle).restore().pendingExit)
    }
    @Test
    fun successPersistClearsCompletionIdentityKeys() {
        val handle = SavedStateHandle()
        val open = openWithIdentity()
        val saved = TimerCompletionSavedState(handle)
        saved.persist(open)
        assertEquals("session-uuid", handle.get<String>("timer_completion_client_uuid"))

        val saving = beginTimerCompletionSave(open, sampleDraft())
        saved.persist(saving)
        val exit = timerCompletionSucceeded(saving, pendingNextFeed = null)
        saved.persist(exit)
        assertNull(handle.get<String>("timer_completion_client_uuid"))
        assertNull(handle.get<Long>("timer_completion_session_baby"))
        assertTrue(saved.restore().pendingExit)
    }
    @Test
    fun rehydratePrefersLiveStateThenSavedState() {
        val handle = SavedStateHandle()
        val saved = TimerCompletionSavedState(handle)
        val offer = timerCompletionSucceeded(
            beginTimerCompletionSave(openWithIdentity(), sampleDraft()),
            pendingNextFeed = TimerPendingNextFeed(1L, 55L),
        )
        saved.persist(offer)

        val fromDurable = rehydrateTimerCompletionUi(
            live = TimerCompletionUiState(),
            savedState = saved,
        )
        assertEquals(55L, fromDurable.pendingNextFeedSuggestedAt)

        val liveExit = TimerCompletionUiState(pendingExit = true)
        val fromLive = rehydrateTimerCompletionUi(live = liveExit, savedState = saved)
        assertTrue(fromLive.pendingExit)
        assertNull(fromLive.pendingNextFeed)
    }
    @Test
    fun shouldResumeInFlightSaveAfterProcessDeath() {
        val saving = beginTimerCompletionSave(openWithIdentity(), sampleDraft())
        assertTrue(shouldResumeTimerCompletionSave(saving))

        val failed = timerCompletionSaveFailed(saving, "x")
        assertFalse(shouldResumeTimerCompletionSave(failed))

        val offer = timerCompletionSucceeded(
            saving,
            pendingNextFeed = TimerPendingNextFeed(1L, 1L),
        )
        assertFalse(shouldResumeTimerCompletionSave(offer))

        val exit = timerCompletionSucceeded(saving, pendingNextFeed = null)
        assertFalse(shouldResumeTimerCompletionSave(exit))

        assertFalse(shouldResumeTimerCompletionSave(TimerCompletionUiState()))
    }
    @Test
    fun resumeBeforeCommit_replaysWithDurableUuidFromCompletionState() {
        val saving = beginTimerCompletionSave(openWithIdentity(), sampleDraft())
        // Timer DataStore empty (fail-closed), but completion SavedState still has uuid.
        val decision = decideTimerCompletionResume(
            stage = saving,
            hasTimerData = false,
            timerSessionUuid = null,
        )
        assertTrue(decision is TimerCompletionResumeDecision.ReplayInFlightSave)
        val replay = decision as TimerCompletionResumeDecision.ReplayInFlightSave
        assertEquals("session-uuid", replay.completionClientUuid)
        assertEquals(11L, replay.sessionBabyId)
        assertEquals(saving.draft, replay.draft)
    }
    @Test
    fun resumeBeforeCommit_usesTimerSessionUuidWhenCompletionIdentityMissing() {
        val saving = beginTimerCompletionSave(
            openTimerCompletionSheet(TimerCompletionUiState(), sampleDraft()),
            sampleDraft(),
        )
        assertNull(saving.completionClientUuid)
        val decision = decideTimerCompletionResume(
            stage = saving,
            hasTimerData = true,
            timerSessionUuid = "from-timer",
        )
        assertTrue(decision is TimerCompletionResumeDecision.ReplayInFlightSave)
        assertEquals(
            "from-timer",
            (decision as TimerCompletionResumeDecision.ReplayInFlightSave).completionClientUuid,
        )
    }
    @Test
    fun resumeMissingSession_failClosedRetryableNotPendingExit() {
        val saving = beginTimerCompletionSave(
            openTimerCompletionSheet(TimerCompletionUiState(), sampleDraft()),
            sampleDraft(),
        )
        val decision = decideTimerCompletionResume(
            stage = saving,
            hasTimerData = false,
            timerSessionUuid = null,
        )
        assertTrue(decision is TimerCompletionResumeDecision.FailClosedRetryable)
        val fail = decision as TimerCompletionResumeDecision.FailClosedRetryable
        assertEquals(TIMER_COMPLETION_SESSION_EXPIRED_MESSAGE, fail.error)
        assertEquals(saving.draft, fail.draft)
        // Applying the decision yields retryable sheet, not exit.
        val published = timerCompletionSaveFailed(
            saving.copy(draft = fail.draft),
            fail.error,
        )
        assertFalse(published.pendingExit)
        assertTrue(published.sheetVisible)
        assertEquals(TIMER_COMPLETION_SESSION_EXPIRED_MESSAGE, published.saveError)
        assertFalse(published.saving)
    }
    @Test
    fun resumeAfterCommitBeforeClear_finishesTimerClearOnly() {
        val exit = timerCompletionSucceeded(
            beginTimerCompletionSave(openWithIdentity(), sampleDraft()),
            pendingNextFeed = null,
        )
        val decision = decideTimerCompletionResume(
            stage = exit,
            hasTimerData = true,
            timerSessionUuid = "still-in-datastore",
        )
        assertEquals(TimerCompletionResumeDecision.FinishClearTimer, decision)
    }
    @Test
    fun resumeAfterClearBeforeHostAck_restoresOfferOrExitWithoutReplay() {
        val offer = timerCompletionTimerCleared(
            timerCompletionSucceeded(
                beginTimerCompletionSave(openWithIdentity(), sampleDraft()),
                pendingNextFeed = TimerPendingNextFeed(3L, 4L),
            ),
        )
        assertEquals(
            TimerCompletionResumeDecision.None,
            decideTimerCompletionResume(
                stage = offer,
                hasTimerData = false,
                timerSessionUuid = null,
            ),
        )
        val exit = timerCompletionTimerCleared(
            timerCompletionSucceeded(
                beginTimerCompletionSave(openWithIdentity(), sampleDraft()),
                pendingNextFeed = null,
            ),
        )
        assertEquals(
            TimerCompletionResumeDecision.None,
            decideTimerCompletionResume(
                stage = exit,
                hasTimerData = false,
                timerSessionUuid = null,
            ),
        )
        // Host still has durable stage to re-collect — no dual remember required.
        assertTrue(offer.hasPostSaveStage)
        assertTrue(exit.hasPostSaveStage)
    }
}
