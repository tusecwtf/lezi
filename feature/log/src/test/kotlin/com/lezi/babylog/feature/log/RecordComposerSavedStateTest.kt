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
        )
        val draft = QuickRecordDraft.create(RecordType.DIARY, 1_000L).copy(
            body = "已输入的正文",
            photos = listOf("/data/user/0/com.lezi/files/record-media/draft.jpg"),
        )
        RecordComposerSavedState(handle).save(request, draft)

        val recreated = RecordComposerSavedState(handle)

        assertEquals(draft, recreated.restore(request))
        assertNull(recreated.restore(RecordComposerRequest.Edit(recordId = 99L)))
    }

    @Test
    fun explicitClearRemovesRestorableDraft() {
        val handle = SavedStateHandle()
        val request = RecordComposerRequest.Edit(recordId = 9L)
        val saved = RecordComposerSavedState(handle)
        saved.save(request, QuickRecordDraft.create(RecordType.MEMO, 1_000L))

        saved.clear()

        assertNull(RecordComposerSavedState(handle).restore(request))
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
            offerReminder = true,
            onOfferReminder = {
                events += "reminder-ready"
                pendingReminderMessage = it
            },
            onPersisted = {
                events += "root-consumed"
                restorableRequest = null
            },
            onFinished = { events += "finished" },
        )

        assertNull(restorableRequest)
        assertEquals("已记录配方奶", pendingReminderMessage)
        assertEquals(listOf("reminder-ready", "root-consumed"), events)
        assertTrue("finished" !in events)
    }

    @Test
    fun savedNonFeedConsumesRestorableRequestThenFinishes() {
        val events = mutableListOf<String>()

        dispatchRecordSaveCompletion(
            message = "已记录笔记",
            offerReminder = false,
            onOfferReminder = { events += "unexpected-reminder" },
            onPersisted = { events += "root-consumed" },
            onFinished = { events += "finished:$it" },
        )

        assertEquals(listOf("root-consumed", "finished:已记录笔记"), events)
    }
}
