package com.lezi.babylog.feature.log.composer
import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

class RecordComposerDiscardPolicyTest {
    private val initial = QuickRecordDraft.create(
        type = RecordType.DIARY,
        timestamp = 1_000L,
    ).copy(
        body = "预填正文",
        photos = listOf("/persisted/one.jpg", "/persisted/two.jpg"),
        sourcePhotos = listOf("/persisted/one.jpg", "/persisted/two.jpg"),
        customTitle = "原项目",
        customItemId = 7L,
    )

    @Test
    fun fieldsTimePhotoOrderAndCustomIdentityAreUserChanges() {
        val changed = listOf(
            initial.copy(body = "修改正文"),
            initial.copy(timestamp = 2_000L),
            initial.copy(endTimestamp = 3_000L),
            initial.copy(photos = initial.photos.reversed()),
            initial.copy(customTitle = "新项目"),
            initial.copy(customItemId = 8L),
        )

        assertFalse(hasRecordComposerUserChanges(initial, initial))
        changed.forEach { assertTrue(hasRecordComposerUserChanges(initial, it)) }
    }

    @Test
    fun draftOwnershipBookkeepingAloneDoesNotCreateAFalsePrompt() {
        val bookkeepingOnly = initial.copy(
            ownedDraftPhotos = listOf("/already-cleaned/import.jpg"),
        )

        assertFalse(hasRecordComposerUserChanges(initial, bookkeepingOnly))
    }

    @Test
    fun everyExitSourceUsesTheSameCleanDirtyAndBusyDecision() {
        ComposerDismissSource.entries.forEach { source ->
            assertEquals(
                ComposerDismissDecision.DismissNow,
                decideRecordComposerDismiss(source, hasUserChanges = false, busy = false),
            )
            assertEquals(
                ComposerDismissDecision.ConfirmDiscard,
                decideRecordComposerDismiss(source, hasUserChanges = true, busy = false),
            )
            assertEquals(
                ComposerDismissDecision.IgnoreWhileBusy,
                decideRecordComposerDismiss(source, hasUserChanges = true, busy = true),
            )
        }
    }

    @Test
    fun viewSessionDismissesImmediatelyWithoutDiscardPrompt() {
        ComposerDismissSource.entries.forEach { source ->
            assertEquals(
                ComposerDismissDecision.DismissNow,
                decideRecordComposerDismiss(
                    source,
                    hasUserChanges = true,
                    busy = false,
                    viewing = true,
                ),
            )
        }
    }
}
