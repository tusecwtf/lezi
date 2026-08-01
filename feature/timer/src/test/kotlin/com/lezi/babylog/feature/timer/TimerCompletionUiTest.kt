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

    private fun openWithIdentity(
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

    // --- save lifecycle ---

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

    // --- SavedState round-trip (config + process recreation) ---

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

    // --- Process-death resume matrix (VM init pure decision) ---

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
        val offer = timerCompletionSucceeded(
            beginTimerCompletionSave(openWithIdentity(), sampleDraft()),
            pendingNextFeed = TimerPendingNextFeed(3L, 4L),
        )
        assertEquals(
            TimerCompletionResumeDecision.None,
            decideTimerCompletionResume(
                stage = offer,
                hasTimerData = false,
                timerSessionUuid = null,
            ),
        )
        val exit = timerCompletionSucceeded(
            beginTimerCompletionSave(openWithIdentity(), sampleDraft()),
            pendingNextFeed = null,
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
