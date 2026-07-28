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
        RecordComposerSavedState(handle).save(request, draft)

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
        RecordComposerSavedState(handle).save(request, draft)

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
        saved.save(request, QuickRecordDraft.create(RecordType.DIARY, 1_000L))

        saved.clear()

        assertNull(RecordComposerSavedState(handle).restore(request))
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
        saved.save(request, QuickRecordDraft.create(RecordType.FORMULA, 1_000L))
        saved.savePendingNextFeed(7L, RecordType.FORMULA)

        saved.clear()

        assertNull(saved.restore(request))
        assertEquals(7L to RecordType.FORMULA, saved.pendingNextFeed())
        saved.clearPendingNextFeed()
        assertNull(saved.pendingNextFeed())
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
