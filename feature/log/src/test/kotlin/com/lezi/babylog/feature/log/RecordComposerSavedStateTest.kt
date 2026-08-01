package com.lezi.babylog.feature.log

import androidx.lifecycle.SavedStateHandle
import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordComposerSavedStateTest {
    @Test
    fun recreatedStoreRestoresDraftOnlyForTheSameRequest() {
        val handle = SavedStateHandle()
        val request = RecordComposerRequest.New(
            babyId = 7L,
            type = RecordType.DIARY,
            timestamp = 1_000L,
            historical = false,
            customItemId = null,
        )
        val draft = QuickRecordDraft.create(RecordType.DIARY, 1_000L).copy(
            body = "已输入的正文",
            photos = listOf("/data/user/0/com.lezi/files/record-media/draft.jpg"),
        )
        RecordComposerSavedState(handle).initialize(request, draft)

        val recreated = RecordComposerSavedState(handle)

        assertEquals(draft, recreated.restore(request))
        assertNull(recreated.restore(RecordComposerRequest.Edit(recordId = 99L)))
    }

    @Test
    fun explicitScheduleIntentSurvivesRecreationAndDoesNotRestoreAsTimestampDerived() {
        val handle = SavedStateHandle()
        val request = RecordComposerRequest.New(
            babyId = 7L,
            type = RecordType.BATH,
            timestamp = 2_000L,
            historical = false,
            createIntent = ComposerCreateIntent.ScheduleCare,
        )
        val draft = QuickRecordDraft.create(
            type = RecordType.BATH,
            timestamp = 2_000L,
            createIntent = ComposerCreateIntent.ScheduleCare,
        )
        RecordComposerSavedState(handle).initialize(request, draft)

        val recreated = RecordComposerSavedState(handle)

        assertEquals(ComposerCreateIntent.ScheduleCare, recreated.restore(request)?.createIntent)
        assertNull(
            recreated.restore(
                request.copy(createIntent = ComposerCreateIntent.DeriveFromTimestamp),
            ),
        )
    }

    @Test
    fun explicitClearRemovesRestorableDraft() {
        val handle = SavedStateHandle()
        val request = RecordComposerRequest.Edit(recordId = 9L)
        val saved = RecordComposerSavedState(handle)
        saved.initialize(request, QuickRecordDraft.create(RecordType.DIARY, 1_000L))

        saved.clear()

        assertNull(RecordComposerSavedState(handle).restore(request))
    }

    @Test
    fun recreatedStoreKeepsInitialAndEditedDraftForDirtyRecovery() {
        val handle = SavedStateHandle()
        val request = RecordComposerRequest.New(
            babyId = 7L,
            type = RecordType.DIARY,
            timestamp = 1_000L,
            historical = false,
        )
        val initial = QuickRecordDraft.create(RecordType.DIARY, 1_000L)
        val edited = initial.copy(
            body = "进程重建后仍未保存",
            photos = listOf("/data/user/0/com.lezi/files/record-media/draft.jpg"),
            ownedDraftPhotos = listOf(
                "/data/user/0/com.lezi/files/record-media/draft.jpg",
            ),
        )
        val saved = RecordComposerSavedState(handle)
        saved.initialize(request, initial)
        saved.update(request, edited)

        val recreated = RecordComposerSavedState(handle)

        assertEquals(initial, recreated.restoreInitial(request))
        assertEquals(edited, recreated.restore(request))
        assertTrue(
            hasRecordComposerUserChanges(
                requireNotNull(recreated.restoreInitial(request)),
                requireNotNull(recreated.restore(request)),
            ),
        )
    }

    @Test
    fun persistedFactKeepsNextFeedIdentityAfterComposerDraftIsConsumed() {
        val handle = SavedStateHandle()
        val saved = RecordComposerSavedState(handle)
        val request = RecordComposerRequest.New(
            babyId = 7L,
            type = RecordType.FORMULA,
            timestamp = 1_000L,
            historical = false,
        )
        saved.initialize(request, QuickRecordDraft.create(RecordType.FORMULA, 1_000L))
        saved.savePendingNextFeed(
            babyId = 7L,
            type = RecordType.FORMULA,
            suggestedAtMillis = 9_000L,
            factMessage = "已记录配方奶",
        )

        saved.clear()

        assertNull(saved.restore(request))
        assertEquals(
            PendingNextFeed(
                babyId = 7L,
                type = RecordType.FORMULA,
                suggestedAtMillis = 9_000L,
                factMessage = "已记录配方奶",
            ),
            saved.pendingNextFeed(),
        )
        saved.clearPendingNextFeed()
        assertNull(saved.pendingNextFeed())
    }

    @Test
    fun recreatedHandleRestoresUnifiedNextFeedOfferIncludingFactMessage() {
        val handle = SavedStateHandle()
        RecordComposerSavedState(handle).savePendingNextFeed(
            babyId = 3L,
            type = RecordType.NURSING,
            suggestedAtMillis = 12_000L,
            factMessage = "已记录母乳",
        )

        val restored = RecordComposerSavedState(handle).pendingNextFeed()

        assertEquals(
            PendingNextFeed(
                babyId = 3L,
                type = RecordType.NURSING,
                suggestedAtMillis = 12_000L,
                factMessage = "已记录母乳",
            ),
            restored,
        )
    }

    @Test
    fun postSaveOutcomeUnifiesOfferWithoutComposeLocalDualMaster() {
        val offer = composerPostSaveOutcome(
            message = "已记录配方奶",
            suggestedNextFeedAt = 9_000L,
            babyId = 7L,
            type = RecordType.FORMULA,
        )
        assertEquals(
            ComposerPostSaveOutcome.NextFeedOffer(
                PendingNextFeed(
                    babyId = 7L,
                    type = RecordType.FORMULA,
                    suggestedAtMillis = 9_000L,
                    factMessage = "已记录配方奶",
                ),
            ),
            offer,
        )
        assertEquals(
            ComposerPostSaveOutcome.Finished("已记录笔记"),
            composerPostSaveOutcome(
                message = "已记录笔记",
                suggestedNextFeedAt = null,
                babyId = 7L,
                type = RecordType.DIARY,
            ),
        )
    }

    @Test
    fun closedUiStatePreservesPendingOfferAndFinishMessageForNewComposition() {
        val offer = PendingNextFeed(
            babyId = 7L,
            type = RecordType.FORMULA,
            suggestedAtMillis = 9_000L,
            factMessage = "已记录配方奶",
        )
        val closed = recordComposerClosedUiState(
            pendingNextFeedOffer = offer,
            pendingFinishMessage = null,
        )
        assertEquals(offer, closed.pendingNextFeedOffer)
        assertNull(closed.activeRequest)
        assertNull(closed.draft)

        val finished = recordComposerClosedUiState(
            pendingNextFeedOffer = null,
            pendingFinishMessage = "已记录笔记",
        )
        assertEquals("已记录笔记", finished.pendingFinishMessage)
        assertNull(finished.pendingNextFeedOffer)
    }

    @Test
    fun applyPostSaveOutcomePublishesObservableOfferAndConsumesRestorableIdentity() {
        val handle = SavedStateHandle()
        val saved = RecordComposerSavedState(handle)
        val request = RecordComposerRequest.New(
            babyId = 7L,
            type = RecordType.FORMULA,
            timestamp = 1_000L,
            historical = false,
        )
        saved.initialize(request, QuickRecordDraft.create(RecordType.FORMULA, 1_000L))
        val openState = RecordComposerUiState(
            activeRequest = request,
            draft = QuickRecordDraft.create(RecordType.FORMULA, 1_000L),
            babyId = 7L,
            saving = true,
        )

        val applied = applyComposerPostSaveOutcome(
            current = openState,
            savedState = saved,
            outcome = ComposerPostSaveOutcome.NextFeedOffer(
                PendingNextFeed(
                    babyId = 7L,
                    type = RecordType.FORMULA,
                    suggestedAtMillis = 9_000L,
                    factMessage = "已记录配方奶",
                ),
            ),
            committedPhotos = listOf("/cache/kept.jpg"),
        )

        assertNull(saved.restore(request))
        assertEquals(
            PendingNextFeed(
                babyId = 7L,
                type = RecordType.FORMULA,
                suggestedAtMillis = 9_000L,
                factMessage = "已记录配方奶",
            ),
            saved.pendingNextFeed(),
        )
        assertEquals(
            PendingNextFeed(
                babyId = 7L,
                type = RecordType.FORMULA,
                suggestedAtMillis = 9_000L,
                factMessage = "已记录配方奶",
            ),
            applied.pendingNextFeedOffer,
        )
        assertNull(applied.pendingFinishMessage)
        assertTrue(!applied.saving)
        assertEquals(listOf("/cache/kept.jpg"), applied.draft?.sourcePhotos)
    }

    @Test
    fun applyPostSaveOutcomePublishesFinishMessageWithoutNextFeedOffer() {
        val handle = SavedStateHandle()
        val saved = RecordComposerSavedState(handle)
        saved.initialize(
            RecordComposerRequest.Edit(9L),
            QuickRecordDraft.create(RecordType.DIARY, 1_000L),
        )
        val openState = RecordComposerUiState(
            activeRequest = RecordComposerRequest.Edit(9L),
            draft = QuickRecordDraft.create(RecordType.DIARY, 1_000L),
            saving = true,
        )

        val applied = applyComposerPostSaveOutcome(
            current = openState,
            savedState = saved,
            outcome = ComposerPostSaveOutcome.Finished("已记录笔记"),
            committedPhotos = emptyList(),
        )

        assertNull(saved.pendingNextFeed())
        assertNull(applied.pendingNextFeedOffer)
        assertEquals("已记录笔记", applied.pendingFinishMessage)
        assertTrue(!applied.saving)
    }

    @Test
    fun savedFeedConsumesRestorableRequestBeforeReminderChoice() {
        val events = mutableListOf<String>()
        var restorableRequest: RecordComposerRequest? = RecordComposerRequest.New(
            babyId = 7L,
            type = RecordType.FORMULA,
            timestamp = 1_000L,
            historical = false,
        )
        var pendingReminderMessage: String? = null

        dispatchRecordSaveCompletion(
            message = "已记录配方奶",
            suggestedNextFeedAt = 9_000L,
            onOfferReminder = { message, suggestedAt ->
                events += "reminder-ready"
                pendingReminderMessage = "$message@$suggestedAt"
            },
            onPersisted = {
                events += "root-consumed"
                restorableRequest = null
            },
            onFinished = { events += "finished" },
        )

        assertNull(restorableRequest)
        assertEquals("已记录配方奶@9000", pendingReminderMessage)
        assertEquals(listOf("reminder-ready", "root-consumed"), events)
        assertTrue("finished" !in events)
    }

    @Test
    fun savedNonFeedConsumesRestorableRequestThenFinishes() {
        val events = mutableListOf<String>()

        dispatchRecordSaveCompletion(
            message = "已记录笔记",
            suggestedNextFeedAt = null,
            onOfferReminder = { _, _ -> events += "unexpected-reminder" },
            onPersisted = { events += "root-consumed" },
            onFinished = { events += "finished:$it" },
        )

        assertEquals(listOf("root-consumed", "finished:已记录笔记"), events)
    }

    @Test
    fun hostConsumesObservablePostSaveWithoutDuplicateFinish() {
        val events = mutableListOf<String>()
        var restorableRequest: RecordComposerRequest? = RecordComposerRequest.New(
            babyId = 7L,
            type = RecordType.FORMULA,
            timestamp = 1_000L,
            historical = false,
        )
        val offer = PendingNextFeed(
            babyId = 7L,
            type = RecordType.FORMULA,
            suggestedAtMillis = 9_000L,
            factMessage = "已记录配方奶",
        )

        // New composition observes VM state after rotation mid-save deliver.
        consumeComposerPostSavePresentation(
            pendingNextFeedOffer = offer,
            pendingFinishMessage = null,
            onConsumeRootRequest = {
                events += "root-consumed"
                restorableRequest = null
            },
            onPresentFinish = { events += "finish:$it" },
        )
        assertNull(restorableRequest)
        assertEquals(listOf("root-consumed"), events)

        // Re-subscribe must not re-fire finish while offer is open.
        consumeComposerPostSavePresentation(
            pendingNextFeedOffer = offer,
            pendingFinishMessage = null,
            onConsumeRootRequest = { events += "root-consumed-again" },
            onPresentFinish = { events += "finish:$it" },
        )
        assertEquals(listOf("root-consumed", "root-consumed-again"), events)

        // Explicit complete/skip publishes finish once.
        consumeComposerPostSavePresentation(
            pendingNextFeedOffer = null,
            pendingFinishMessage = "已记录配方奶；未安排下次喂养",
            onConsumeRootRequest = { events += "root-noop" },
            onPresentFinish = { events += "finish:$it" },
        )
        assertEquals(
            listOf(
                "root-consumed",
                "root-consumed-again",
                "root-noop",
                "finish:已记录配方奶；未安排下次喂养",
            ),
            events,
        )
    }

    @Test
    fun carePlanSaveMessageSurfacesPermissionDegradationWithoutBlockingCopy() {
        assertTrue(isCarePlanSaveMessage("已安排配方奶"))
        assertTrue(isCarePlanSaveMessage("已保存护理计划"))
        assertTrue(!isCarePlanSaveMessage("已记录配方奶"))
        assertTrue(!isCarePlanSaveMessage("已完成护理计划"))

        assertEquals(
            "已安排配方奶",
            carePlanSaveMessageWithPermission(
                baseMessage = "已安排配方奶",
                notificationPermissionGranted = true,
                isCarePlanWrite = true,
            ),
        )
        assertEquals(
            "已安排配方奶；通知权限未开启，本机提醒已降级",
            carePlanSaveMessageWithPermission(
                baseMessage = "已安排配方奶",
                notificationPermissionGranted = false,
                isCarePlanWrite = true,
            ),
        )
        assertEquals(
            "护理计划已保存；通知权限未开启，本机提醒已降级",
            carePlanSaveMessageWithPermission(
                baseMessage = "已保存护理计划",
                notificationPermissionGranted = false,
                isCarePlanWrite = true,
            ),
        )
        // Non-care-plan paths must not be rewritten.
        assertEquals(
            "已记录配方奶",
            carePlanSaveMessageWithPermission(
                baseMessage = "已记录配方奶",
                notificationPermissionGranted = false,
                isCarePlanWrite = false,
            ),
        )
    }
}
