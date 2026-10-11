package com.lezi.babylog.feature.family.baby

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.ui.OwnedCameraCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BabyEditDraftTest {
    @Test
    fun unchangedAndEquivalentProfileValuesCloseWithoutConfirmation() {
        val draft = BabyEditDraft(BabyFixture)
        assertEquals(BabyProfileExit.Close, draft.requestExit())
        draft.nickname = " 乐乐 "
        draft.weightText = " 3.20 "
        assertFalse(draft.dirty)
        assertEquals(BabyProfileExit.Close, draft.requestExit())
    }

    @Test
    fun unchangedWeightDisplayStaysCleanAtLegacyDoubleTruncationBoundary() {
        val draft = BabyEditDraft(BabyFixture.copy(birthWeightGrams = 4_015))
        assertEquals("4.015", draft.weightText)
        assertFalse(draft.dirty)
        assertEquals(BabyProfileExit.Close, draft.requestExit())
        draft.weightText = " 4.015 "
        assertFalse(draft.dirty)
        draft.weightText = "4.016"
        assertTrue(draft.dirty)
    }

    @Test
    fun everyUnsavedProfileFieldNeedsConfirmationAndCanReturnToClean() {
        val mutations: List<(BabyEditDraft) -> Unit> = listOf(
            { it.nickname = "晨晨" },
            { it.sex = "female" },
            { it.birthday += 1 },
            { it.weightText = "3.21" },
            { it.weightText = "-3.2" },
            { it.removeAvatar = true },
        )
        mutations.forEach { mutate ->
            val draft = BabyEditDraft(BabyFixture)
            mutate(draft)
            assertEquals(BabyProfileExit.ConfirmDiscard, draft.requestExit())
            assertTrue(draft.showDiscard)
            draft.keepEditing()
            assertFalse(draft.showDiscard)
            assertTrue(draft.dirty)
        }
        val draft = BabyEditDraft(BabyFixture)
        draft.nickname = "晨晨"
        draft.nickname = BabyFixture.nickname
        assertEquals(BabyProfileExit.Close, draft.requestExit())
    }

    @Test
    fun immediateLocalThemeIsNotAnUnsavedProfileChange() {
        val draft = BabyEditDraft(BabyFixture)
        draft.themeArgb = 0xffddaa77.toInt()
        draft.localPrefError = "排序失败"
        assertFalse(draft.dirty)
        assertEquals(BabyProfileExit.Close, draft.requestExit())
    }

    @Test
    fun savingLocksEveryExitAndFailureRetainsDraftForRetry() {
        val draft = BabyEditDraft(BabyFixture)
        draft.nickname = "晨晨"
        draft.weightText = "3.21"
        assertEquals(3_210, draft.beginSave()?.grams)
        assertEquals(BabyProfileExit.Saving, draft.requestExit())
        assertFalse(draft.showDiscard)
        assertNull(draft.beginSave())
        draft.finishSave("保存失败，请重试")
        assertEquals("晨晨", draft.nickname)
        assertEquals("3.21", draft.weightText)
        assertEquals("保存失败，请重试", draft.formError)
        assertEquals(BabyProfileExit.ConfirmDiscard, draft.requestExit())
        draft.keepEditing()
        assertEquals(3_210, draft.beginSave()?.grams)
        assertNull(draft.formError)
    }

    @Test
    fun successfulOutcomeIsConsumedOnceAndReopenStartsFresh() {
        val host = BabyEditDraftHost()
        val draft = host.open(BabyFixture)
        draft.nickname = "晨晨"
        draft.beginSave()
        draft.finishSave(null)
        assertTrue(draft.saved)
        assertEquals(BabyProfileExit.Saving, draft.requestExit())
        assertNull(draft.beginSave())
        assertTrue(draft.consumeSaved())
        assertFalse(draft.consumeSaved())
        host.close(draft)
        val reopened = host.open(BabyFixture)
        assertNotSame(draft, reopened)
        assertEquals(BabyFixture.nickname, reopened.nickname)
        assertFalse(reopened.dirty)
    }

    @Test
    fun reattachmentRetainsTheSameSessionAndOwnedCaptureUntilExplicitClose() {
        val host = BabyEditDraftHost()
        val draft = host.open(BabyFixture)
        val capture = FakeCapture()
        draft.replaceOwnedCapture(capture)
        draft.nickname = "晨晨"
        assertSame(draft, host.open(BabyFixture.copy(themeColorArgb = 0xffaa7788.toInt())))
        assertEquals("晨晨", host.open(BabyFixture).nickname)
        assertEquals(0, capture.releases)
        host.close(draft)
        host.close(draft)
        assertEquals(1, capture.releases)
    }

    @Test
    fun reusedLocalIdCannotResumeAnotherBabysOrFamilysDraft() {
        val host = BabyEditDraftHost()
        val draft = host.open(BabyFixture)
        draft.nickname = "旧档案草稿"
        val otherBaby = BabyFixture.copy(clientUuid = "another-baby")
        val replaced = host.open(otherBaby)
        assertNotSame(draft, replaced)
        assertEquals(BabyFixture.nickname, replaced.nickname)
        val otherFamily = host.open(otherBaby.copy(familyId = 2))
        assertNotSame(replaced, otherFamily)
        assertFalse(otherFamily.dirty)
    }

    @Test
    fun ownerClearAndDifferentBabyReattachmentBoundTheSessionAndReleaseOnlyOwnedCapture() {
        val host = BabyEditDraftHost()
        val capture = FakeCapture()
        val draft = host.open(BabyFixture)
        draft.replaceOwnedCapture(capture)
        val other = host.open(BabyFixture.copy(id = 10, nickname = "晨晨"))
        assertNotSame(draft, other)
        assertEquals(1, capture.releases)
        val nextCapture = FakeCapture()
        other.replaceOwnedCapture(nextCapture)
        ViewModelStore().apply { put("profile", host); clear() }
        assertEquals(1, nextCapture.releases)
    }

    private class FakeCapture : OwnedCameraCapture {
        override val token = "fake-profile-capture"
        override val uri: Uri get() = error("The state holder never resolves a provider Uri")
        var releases = 0
        override fun release() { releases += 1 }
    }

    private companion object {
        val BabyFixture = Baby(
            id = 9,
            familyId = 1,
            nickname = "乐乐",
            birthdayEpochDay = 20_000,
            birthWeightGrams = 3_200,
            avatarPath = "existing-owned-avatar.jpg",
            themeColorArgb = 0xff6688aa.toInt(),
            clientUuid = "baby-draft-9",
            updatedAt = 1,
        )
    }
}
