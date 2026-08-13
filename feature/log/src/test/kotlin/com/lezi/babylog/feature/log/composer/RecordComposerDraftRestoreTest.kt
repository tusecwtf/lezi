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
class RecordComposerDraftRestoreTest {
    @Test
    fun processRecreationRestoresOnePendingWriteIdentityInsteadOfAnEditableDraft() {
        val handle = SavedStateHandle()
        val request = RecordComposerRequest.New(
            babyId = 7L,
            type = RecordType.DIARY,
            timestamp = 1_000L,
            historical = false,
        )
        val originalDraft = QuickRecordDraft.create(RecordType.DIARY, 1_000L).copy(
            body = "只应写入一次",
        )
        val saved = RecordComposerSavedState(handle)
        saved.initialize(request, originalDraft)
        val pending = freezeComposerWrite(
            request = request,
            babyId = 7L,
            draft = originalDraft,
            confirmedAtMillis = 2_000L,
            clientUuid = "composer-fact-identity",
        )

        saved.savePendingWrite(pending)
        // A late editable-field callback cannot mutate an already-confirmed write.
        saved.update(request, originalDraft.copy(body = "不应覆盖"))

        val recreated = RecordComposerSavedState(handle)
        assertEquals(pending, recreated.pendingWrite())
        assertEquals(originalDraft, recreated.pendingWrite()?.draft)
        assertNull(recreated.restore(request))
    }
    @Test
    fun recreatedPendingWriteMapsToLockedAutomaticResumeState() {
        val request = RecordComposerRequest.New(
            babyId = 7L,
            type = RecordType.DIARY,
            timestamp = 1_000L,
            historical = false,
        )
        val draft = QuickRecordDraft.create(RecordType.DIARY, 1_000L).copy(
            body = "只写一次",
        )
        val pending = freezeComposerWrite(
            request = request,
            babyId = 7L,
            draft = draft,
            confirmedAtMillis = 2_000L,
            clientUuid = "composer-resume-identity",
        )

        val restored = recordComposerPendingWriteUiState(pending)

        assertEquals(request, restored.activeRequest)
        assertEquals(draft, restored.draft)
        assertTrue(restored.saving)
        assertFalse(restored.hasUserChanges)
        assertNull(restored.error)
    }
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
}
