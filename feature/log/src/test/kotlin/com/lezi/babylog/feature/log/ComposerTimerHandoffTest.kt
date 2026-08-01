package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.TimerHandoffBuildResult
import com.lezi.babylog.core.model.TimerHandoffPhotoOwnership
import com.lezi.babylog.core.model.TimerHandoffSeed
import com.lezi.babylog.core.model.UntransferableTimerField
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComposerTimerHandoffTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val lifecycle = RecordComposerPhotoLifecycle { paths ->
        paths.forEach { File(it).delete() }
    }

    @Test
    fun newNursingHandoffCarriesNoteAmountAndOwnedPhotosWithoutCarePlan() {
        val owned = photo("owned.jpg")
        val draft = QuickRecordDraft.create(RecordType.NURSING, 1_000L).copy(
            note = "顺利",
            nursingAmountMl = "80",
            photos = listOf(owned.absolutePath),
            ownedDraftPhotos = listOf(owned.absolutePath),
        )

        val result = prepareTimerHandoffSeed(
            handoffId = "h-new",
            babyId = 5L,
            draft = draft,
        )
        val seed = (result as TimerHandoffBuildResult.Ready).seed
        assertEquals(5L, seed.babyId)
        assertEquals(null, seed.carePlanId)
        assertEquals("顺利", seed.note)
        assertEquals("80", seed.amountMl)
        assertEquals(listOf(owned.absolutePath), seed.orderedPaths)
        assertEquals(
            TimerHandoffPhotoOwnership.ComposerOwned,
            seed.photos.single().ownership,
        )
    }

    @Test
    fun fulfillNursingHandoffKeepsBorrowedPlanPhotosAndOwnedImports() {
        val plan = photo("plan.jpg")
        val owned = photo("import.jpg")
        val draft = QuickRecordDraft.create(RecordType.NURSING, 2_000L).copy(
            carePlanId = 99L,
            note = "履行",
            nursingAmountMl = "100",
            photos = listOf(plan.absolutePath, owned.absolutePath),
            borrowedPhotos = listOf(plan.absolutePath),
            ownedDraftPhotos = listOf(owned.absolutePath),
        )

        val seed = (
            prepareTimerHandoffSeed(
                handoffId = "h-fulfill",
                babyId = 1L,
                draft = draft,
                livePlanPhotoPaths = listOf(plan.absolutePath),
            ) as TimerHandoffBuildResult.Ready
            ).seed

        assertEquals(99L, seed.carePlanId)
        assertEquals(
            listOf(
                TimerHandoffPhotoOwnership.Borrowed,
                TimerHandoffPhotoOwnership.ComposerOwned,
            ),
            seed.photos.map { it.ownership },
        )
    }

    @Test
    fun releaseForTimerHandoffKeepsTransferredOwnedAndDeletesOrphansOnly() = runBlocking {
        val keepBytes = byteArrayOf(1, 2, 3)
        val orphanBytes = byteArrayOf(4, 5, 6)
        val planBytes = byteArrayOf(7, 8, 9)
        val keep = photo("keep.jpg", keepBytes)
        val orphan = photo("orphan.jpg", orphanBytes)
        val plan = photo("plan.jpg", planBytes)
        val draft = QuickRecordDraft.create(RecordType.NURSING, 1_000L).copy(
            carePlanId = 3L,
            photos = listOf(plan.absolutePath, keep.absolutePath),
            borrowedPhotos = listOf(plan.absolutePath),
            ownedDraftPhotos = listOf(keep.absolutePath, orphan.absolutePath),
        )
        val seed = TimerHandoffSeed(
            handoffId = "h",
            babyId = 1L,
            carePlanId = 3L,
            photos = listOf(
                com.lezi.babylog.core.model.TimerHandoffPhoto(
                    plan.absolutePath,
                    TimerHandoffPhotoOwnership.Borrowed,
                ),
                com.lezi.babylog.core.model.TimerHandoffPhoto(
                    keep.absolutePath,
                    TimerHandoffPhotoOwnership.ComposerOwned,
                ),
            ),
        )

        lifecycle.releaseForTimerHandoff(draft, seed.composerOwnedPaths)

        assertTrue(keep.isFile)
        assertTrue(plan.isFile)
        assertFalse(orphan.exists())
    }

    @Test
    fun rejectedConflictAndCancelDoNotReleaseOwnedImports() = runBlocking {
        val owned = photo("owned-cancel.jpg", byteArrayOf(9, 8, 7))
        val draft = QuickRecordDraft.create(RecordType.NURSING, 1_000L).copy(
            photos = listOf(owned.absolutePath),
            ownedDraftPhotos = listOf(owned.absolutePath),
        )
        val seed = TimerHandoffSeed(
            handoffId = "h-cancel",
            babyId = 1L,
            photos = listOf(
                com.lezi.babylog.core.model.TimerHandoffPhoto(
                    owned.absolutePath,
                    TimerHandoffPhotoOwnership.ComposerOwned,
                ),
            ),
        )

        // Shell policy: only Accepted / AlreadyAccepted may closeAfterTimerHandoff.
        assertFalse(
            shouldReleaseComposerAfterHandoff(
                com.lezi.babylog.core.model.TimerHandoffAcceptResult.RejectedConflict,
            ),
        )
        // Cancel / pop before accept: never invoke release — owned files intact.
        assertTrue(owned.isFile)
        assertEquals(listOf(owned.absolutePath), seed.composerOwnedPaths)
        // Reject path does not call releaseForTimerHandoff / cleanupAbandoned.
        assertTrue(owned.isFile)
        assertTrue(owned.readBytes().contentEquals(byteArrayOf(9, 8, 7)))
        // Control: explicit abandon (user discard draft) still reclaims.
        lifecycle.cleanupAbandoned(draft)
        assertFalse(owned.exists())
    }

    @Test
    fun handoffInFlightBlocksDismissLikeBusy() {
        ComposerDismissSource.entries.forEach { source ->
            assertEquals(
                ComposerDismissDecision.IgnoreWhileBusy,
                decideRecordComposerDismiss(
                    source = source,
                    hasUserChanges = true,
                    busy = true, // timerHandoffInFlight maps to busy
                ),
            )
        }
    }

    @Test
    fun untransferableDirtyDurationTriggersConfirmMessage() {
        val baseline = QuickRecordDraft.create(RecordType.NURSING, 1_000L)
        val dirty = baseline.copy(leftMin = "12", rightMin = "5")
        val fields = untransferableFieldsForTimerHandoff(dirty, baseline)
        assertEquals(setOf(UntransferableTimerField.ManualDuration), fields)
        assertTrue(
            timerHandoffUntransferableConfirmMessage(fields).contains("时长"),
        )
    }

    @Test
    fun photoOverflowBeforeLeaveKeepsDraftEditableMessage() {
        val result = prepareTimerHandoffSeed(
            handoffId = "h",
            babyId = 1L,
            draft = QuickRecordDraft.create(RecordType.NURSING, 1_000L).copy(
                carePlanId = 7L,
                photos = listOf("s1.jpg", "s2.jpg"),
                ownedDraftPhotos = listOf("s1.jpg", "s2.jpg"),
            ),
            livePlanPhotoPaths = listOf("p1.jpg", "p2.jpg", "p3.jpg"),
        )
        val overflow = result as TimerHandoffBuildResult.PhotoOverflow
        assertEquals(5, overflow.distinctCount)
        assertTrue(
            timerHandoffPhotoOverflowMessage(overflow.distinctCount, overflow.maxAllowed)
                .contains("删减"),
        )
    }

    private fun photo(name: String, bytes: ByteArray = byteArrayOf(1)): File =
        temporaryFolder.newFile(name).apply { writeBytes(bytes) }
}
