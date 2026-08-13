package com.lezi.babylog.feature.log.composer
import androidx.lifecycle.SavedStateHandle
import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

// Contract-cluster split (ticket 08).
class RecordComposerPostSaveTest {
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
            PendingNextFeed(
                babyId = 7L,
                type = RecordType.FORMULA,
                suggestedAtMillis = 9_000L,
                factMessage = "已记录配方奶",
            ),
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
            PendingNextFeed(
                babyId = 3L,
                type = RecordType.NURSING,
                suggestedAtMillis = 12_000L,
                factMessage = "已记录母乳",
            ),
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
    fun partialLegacyMultiKeyOfferIsNotRestoredWithoutMessage() {
        val handle = SavedStateHandle()
        // Legacy partial row without message must not invent a presentable offer.
        handle["pending_next_feed_baby"] = 7L
        handle["pending_next_feed_type"] = RecordType.FORMULA.key
        handle["pending_next_feed_suggested_at"] = 9_000L

        assertNull(RecordComposerSavedState(handle).pendingNextFeed())
    }
    @Test
    fun completeLegacyMultiKeyOfferMigratesToSingleBlob() {
        val handle = SavedStateHandle()
        handle["pending_next_feed_baby"] = 7L
        handle["pending_next_feed_type"] = RecordType.FORMULA.key
        handle["pending_next_feed_suggested_at"] = 9_000L
        handle["pending_next_feed_message"] = "已记录配方奶"

        val restored = RecordComposerSavedState(handle).pendingNextFeed()
        assertEquals(
            PendingNextFeed(
                babyId = 7L,
                type = RecordType.FORMULA,
                suggestedAtMillis = 9_000L,
                factMessage = "已记录配方奶",
            ),
            restored,
        )
        // Legacy keys dropped after migration.
        assertNull(handle.get<Long>("pending_next_feed_baby"))
        // Blob survives a second read.
        assertEquals(restored, RecordComposerSavedState(handle).pendingNextFeed())
    }
    @Test
    fun pendingFinishMessageSurvivesRecreationUntilAcknowledged() {
        val handle = SavedStateHandle()
        val saved = RecordComposerSavedState(handle)
        saved.savePendingFinishMessage("已记录笔记")

        assertEquals("已记录笔记", RecordComposerSavedState(handle).pendingFinishMessage())
        saved.clearPendingFinishMessage()
        assertNull(RecordComposerSavedState(handle).pendingFinishMessage())
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
        val draft = QuickRecordDraft.create(RecordType.FORMULA, 1_000L)
        saved.initialize(request, draft)
        saved.savePendingWrite(
            freezeComposerWrite(
                request = request,
                babyId = 7L,
                draft = draft,
                confirmedAtMillis = 2_000L,
                clientUuid = "composer-post-save-identity",
            ),
        )
        val openState = RecordComposerUiState(
            activeRequest = request,
            draft = draft,
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
        assertNull(saved.pendingWrite())
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
        assertNull(applied.activeRequest)
        assertTrue(!applied.saving)
        assertEquals(listOf("/cache/kept.jpg"), applied.draft?.sourcePhotos)
    }
    @Test
    fun applyPostSaveOutcomePublishesDurableFinishMessageWithoutNextFeedOffer() {
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
        assertEquals("已记录笔记", saved.pendingFinishMessage())
        assertNull(applied.pendingNextFeedOffer)
        assertEquals("已记录笔记", applied.pendingFinishMessage)
        assertNull(applied.activeRequest)
        assertTrue(!applied.saving)
    }
    @Test
    fun persistAndMapPostSaveAreSeparableCommandAndQuery() {
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
            saving = true,
        )
        val outcome = ComposerPostSaveOutcome.NextFeedOffer(
            PendingNextFeed(
                babyId = 7L,
                type = RecordType.FORMULA,
                suggestedAtMillis = 9_000L,
                factMessage = "已记录配方奶",
            ),
        )

        // Pure map does not touch SavedState.
        val mappedOnly = mapComposerPostSaveUiState(openState, outcome, emptyList())
        assertTrue(saved.restore(request) != null)
        assertNull(saved.pendingNextFeed())
        assertNull(mappedOnly.activeRequest)
        assertEquals(outcome.pending, mappedOnly.pendingNextFeedOffer)

        persistComposerPostSave(saved, outcome)
        assertNull(saved.restore(request))
        assertEquals(outcome.pending, saved.pendingNextFeed())
    }
    @Test
    fun writeSessionOpenIsRefusedWhilePostSaveStageIsLive() {
        val request = RecordComposerRequest.New(
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
        assertFalse(
            shouldOpenComposerWriteSession(
                request = request,
                pendingNextFeedOffer = offer,
                pendingFinishMessage = null,
            ),
        )
        assertFalse(
            shouldOpenComposerWriteSession(
                request = request,
                pendingNextFeedOffer = null,
                pendingFinishMessage = "已记录笔记",
            ),
        )
        assertTrue(
            shouldOpenComposerWriteSession(
                request = request,
                pendingNextFeedOffer = null,
                pendingFinishMessage = null,
            ),
        )
        assertFalse(
            shouldOpenComposerWriteSession(
                request = null,
                pendingNextFeedOffer = null,
                pendingFinishMessage = null,
            ),
        )
    }
    @Test
    fun processRecreateWithRootStillSetCannotRearmDraftUnderPendingOffer() {
        // Simulate: apply cleared VM request/draft SavedState; Activity root still New;
        // process death restores pending offer + root. open refuse + Host gate block rewrite.
        val handle = SavedStateHandle()
        val saved = RecordComposerSavedState(handle)
        val request = RecordComposerRequest.New(
            babyId = 7L,
            type = RecordType.FORMULA,
            timestamp = 1_000L,
            historical = false,
        )
        saved.initialize(request, QuickRecordDraft.create(RecordType.FORMULA, 1_000L))
        val applied = applyComposerPostSaveOutcome(
            current = RecordComposerUiState(
                activeRequest = request,
                draft = QuickRecordDraft.create(RecordType.FORMULA, 1_000L),
                babyId = 7L,
                saving = true,
            ),
            savedState = saved,
            outcome = ComposerPostSaveOutcome.NextFeedOffer(
                PendingNextFeed(
                    babyId = 7L,
                    type = RecordType.FORMULA,
                    suggestedAtMillis = 9_000L,
                    factMessage = "已记录配方奶",
                ),
            ),
            committedPhotos = emptyList(),
        )
        // Root still set at Activity (not modeled here); VM SavedState has no draft.
        assertNull(saved.restore(request))
        val recreated = RecordComposerSavedState(handle)
        val restoredOffer = recreated.pendingNextFeed()
        assertEquals(applied.pendingNextFeedOffer, restoredOffer)
        assertFalse(
            shouldOpenComposerWriteSession(
                request = request, // Activity still holds New
                pendingNextFeedOffer = restoredOffer,
                pendingFinishMessage = null,
            ),
        )
        // Re-open path must not re-initialize restorable identity under live offer.
        assertNull(recreated.restore(request))
    }
    @Test
    fun deliverMissStillPublishesDurableOfferFromSavedState() {
        val handle = SavedStateHandle()
        val saved = RecordComposerSavedState(handle)
        val applied = applyComposerPostSaveOutcome(
            current = RecordComposerUiState(
                activeRequest = RecordComposerRequest.New(
                    babyId = 7L,
                    type = RecordType.FORMULA,
                    timestamp = 1_000L,
                    historical = false,
                ),
                saving = true,
            ),
            savedState = saved,
            outcome = ComposerPostSaveOutcome.NextFeedOffer(
                PendingNextFeed(
                    babyId = 7L,
                    type = RecordType.FORMULA,
                    suggestedAtMillis = 9_000L,
                    factMessage = "已记录配方奶",
                ),
            ),
            committedPhotos = emptyList(),
        )
        // Session gate deliver missed — rehydrate from SavedState like VM miss branch.
        val durable = rehydrateComposerPostSaveStage(
            pendingNextFeedOffer = null,
            pendingFinishMessage = null,
            savedState = saved,
        )
        assertEquals(applied.pendingNextFeedOffer, durable.pendingNextFeedOffer)
        assertTrue(durable.isActive)
    }
    @Test
    fun hostConsumesObservablePostSaveWithIdempotentRootCallback() {
        val events = mutableListOf<String>()
        var restorableRequest: RecordComposerRequest? = RecordComposerRequest.New(
            babyId = 7L,
            type = RecordType.FORMULA,
            timestamp = 1_000L,
            historical = false,
        )
        var rootWasOpen = true
        val offer = PendingNextFeed(
            babyId = 7L,
            type = RecordType.FORMULA,
            suggestedAtMillis = 9_000L,
            factMessage = "已记录配方奶",
        )
        val idempotentConsumeRoot = {
            // Mirrors MainViewModel.closeComposerAfterPersist: side effects only when open.
            if (rootWasOpen) {
                events += "root-consumed"
                restorableRequest = null
                rootWasOpen = false
            } else {
                events += "root-noop"
            }
        }

        consumeComposerPostSavePresentation(
            pendingNextFeedOffer = offer,
            pendingFinishMessage = null,
            onConsumeRootRequest = idempotentConsumeRoot,
            onPresentFinish = { events += "finish:$it" },
        )
        assertNull(restorableRequest)
        assertEquals(listOf("root-consumed"), events)

        // Re-subscribe / rotation: callback may fire again but must be side-effect free.
        consumeComposerPostSavePresentation(
            pendingNextFeedOffer = offer,
            pendingFinishMessage = null,
            onConsumeRootRequest = idempotentConsumeRoot,
            onPresentFinish = { events += "finish:$it" },
        )
        assertEquals(listOf("root-consumed", "root-noop"), events)

        // Explicit complete/skip publishes finish once.
        consumeComposerPostSavePresentation(
            pendingNextFeedOffer = null,
            pendingFinishMessage = "已记录配方奶；未安排下次喂养",
            onConsumeRootRequest = idempotentConsumeRoot,
            onPresentFinish = { events += "finish:$it" },
        )
        assertEquals(
            listOf(
                "root-consumed",
                "root-noop",
                "root-noop",
                "finish:已记录配方奶；未安排下次喂养",
            ),
            events,
        )
    }
    @Test
    fun nonFeedFinishIsDurableUntilAcknowledged() {
        val handle = SavedStateHandle()
        val saved = RecordComposerSavedState(handle)
        applyComposerPostSaveOutcome(
            current = RecordComposerUiState(
                activeRequest = RecordComposerRequest.New(
                    babyId = 1L,
                    type = RecordType.DIARY,
                    timestamp = 1L,
                    historical = false,
                ),
                saving = true,
            ),
            savedState = saved,
            outcome = ComposerPostSaveOutcome.Finished("已记录笔记"),
            committedPhotos = emptyList(),
        )
        assertEquals("已记录笔记", saved.pendingFinishMessage())
        // Host acknowledges after one-shot present.
        saved.clearPendingFinishMessage()
        assertNull(saved.pendingFinishMessage())
        assertFalse(
            hasComposerPostSaveStage(
                pendingNextFeedOffer = saved.pendingNextFeed(),
                pendingFinishMessage = saved.pendingFinishMessage(),
            ),
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
    @Test
    fun composerSaveSuccessMessageCoversFeedAndNonFeed() {
        val formula = QuickRecordDraft.create(RecordType.FORMULA, 1_000L)
        assertEquals(
            "已记录配方奶",
            composerSaveSuccessMessage(
                writeDecision = ComposerWriteDecision.AddRecord,
                draft = formula,
                commandType = RecordType.FORMULA,
            ),
        )
        assertEquals(
            "已记录日记",
            composerSaveSuccessMessage(
                writeDecision = ComposerWriteDecision.AddRecord,
                draft = QuickRecordDraft.create(RecordType.DIARY, 1_000L),
                commandType = RecordType.DIARY,
            ),
        )
    }
}
