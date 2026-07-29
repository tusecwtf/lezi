package com.lezi.babylog.feature.log

import androidx.lifecycle.SavedStateHandle
import com.lezi.babylog.core.model.RecordType
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordComposerPhotoLifecycleTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val lifecycle = RecordComposerPhotoLifecycle { paths ->
        paths.forEach { File(it).delete() }
    }

    @Test
    fun fulfillmentHydrationPreservesZeroToThreeBorrowedPhotoOrder() {
        (0..3).forEach { count ->
            val planPhotos = (0 until count).map { index ->
                photo("plan-$count-$index.jpg", byteArrayOf(index.toByte())).absolutePath
            }

            val draft = lifecycle.fulfillmentDraft(
                base = QuickRecordDraft.create(RecordType.DIARY, 1_000L).copy(carePlanId = 7L),
                planPhotos = planPhotos,
            )

            assertEquals(planPhotos, draft.photos)
            assertEquals(planPhotos, draft.borrowedPhotos)
            assertTrue(draft.sourcePhotos.isEmpty())
            assertTrue(draft.ownedDraftPhotos.isEmpty())
        }
    }

    @Test
    fun removingAndAbandoningBorrowedPhotosNeverDeletesPlanBytesEvenWithDuplicates() =
        runBlocking {
            val planABytes = byteArrayOf(1, 2, 3)
            val planBBytes = byteArrayOf(4, 5, 6)
            val ownedBytes = byteArrayOf(7, 8, 9)
            val planA = photo("plan-a.jpg", planABytes)
            val planB = photo("plan-b.jpg", planBBytes)
            val owned = photo("owned.jpg", ownedBytes)
            val hydrated = lifecycle.fulfillmentDraft(
                base = QuickRecordDraft.create(RecordType.DIARY, 1_000L).copy(carePlanId = 7L),
                planPhotos = listOf(planA.absolutePath, planA.absolutePath, planB.absolutePath),
            )
            // A malformed duplicate ownership entry must still fail closed to borrowed.
            val before = lifecycle.imported(
                hydrated,
                listOf(planA.absolutePath, owned.absolutePath, owned.absolutePath),
            )

            val after = lifecycle.removed(before, planA.absolutePath)
            lifecycle.cleanupRemoved(before, after)
            lifecycle.cleanupAbandoned(after)

            assertArrayEquals(planABytes, planA.readBytes())
            assertArrayEquals(planBBytes, planB.readBytes())
            assertFalse(owned.exists())
        }

    @Test
    fun removingOwnedImportDeletesOnlyThatUnreferencedDraftFile() = runBlocking {
        val planBytes = byteArrayOf(1, 3, 5)
        val ownedBytes = byteArrayOf(2, 4, 6)
        val plan = photo("plan.jpg", planBytes)
        val owned = photo("owned.jpg", ownedBytes)
        val hydrated = lifecycle.fulfillmentDraft(
            base = QuickRecordDraft.create(RecordType.DIARY, 1_000L).copy(carePlanId = 7L),
            planPhotos = listOf(plan.absolutePath),
        )
        val before = lifecycle.imported(hydrated, listOf(owned.absolutePath))

        val after = lifecycle.removed(before, owned.absolutePath)
        lifecycle.cleanupRemoved(before, after)

        assertArrayEquals(planBytes, plan.readBytes())
        assertFalse(owned.exists())
    }

    @Test
    fun successfulFulfillmentCleansOnlyDiscardedOwnedImports() = runBlocking {
        val planKeepBytes = byteArrayOf(1)
        val planRemovedBytes = byteArrayOf(2)
        val ownedKeepBytes = byteArrayOf(3)
        val planKeep = photo("plan-keep.jpg", planKeepBytes)
        val planRemoved = photo("plan-removed.jpg", planRemovedBytes)
        val ownedKeep = photo("owned-keep.jpg", ownedKeepBytes)
        val ownedRemoved = photo("owned-removed.jpg", byteArrayOf(4))
        val hydrated = lifecycle.fulfillmentDraft(
            base = QuickRecordDraft.create(RecordType.DIARY, 1_000L).copy(carePlanId = 7L),
            planPhotos = listOf(planKeep.absolutePath, planRemoved.absolutePath),
        )
        val imported = lifecycle.imported(
            lifecycle.removed(hydrated, planRemoved.absolutePath),
            listOf(ownedKeep.absolutePath, ownedRemoved.absolutePath),
        )
        val confirmed = lifecycle.removed(imported, ownedRemoved.absolutePath)

        lifecycle.cleanupAfterCommit(confirmed)

        assertArrayEquals(planKeepBytes, planKeep.readBytes())
        assertArrayEquals(planRemovedBytes, planRemoved.readBytes())
        assertArrayEquals(ownedKeepBytes, ownedKeep.readBytes())
        assertFalse(ownedRemoved.exists())
        assertEquals(
            listOf(planKeep.absolutePath, ownedKeep.absolutePath),
            confirmed.photos,
        )
    }

    @Test
    fun failedSaveThenRecreationRetainsOwnershipUntilExplicitAbandon() = runBlocking {
        val planBytes = byteArrayOf(11, 12)
        val ownedBytes = byteArrayOf(21, 22)
        val plan = photo("plan.jpg", planBytes)
        val owned = photo("owned.jpg", ownedBytes)
        val request = RecordComposerRequest.Fulfill(carePlanId = 7L)
        val original = lifecycle.imported(
            lifecycle.fulfillmentDraft(
                base = QuickRecordDraft.create(RecordType.DIARY, 1_000L).copy(carePlanId = 7L),
                planPhotos = listOf(plan.absolutePath),
            ),
            listOf(owned.absolutePath),
        )
        val handle = SavedStateHandle()
        RecordComposerSavedState(handle).save(request, original)

        // A failed write performs no cleanup. The recreated draft retains ordered ownership.
        val restored = RecordComposerSavedState(handle).restore(request)!!
        assertEquals(listOf(plan.absolutePath), restored.borrowedPhotos)
        assertEquals(listOf(owned.absolutePath), restored.ownedDraftPhotos)
        assertArrayEquals(planBytes, plan.readBytes())
        assertArrayEquals(ownedBytes, owned.readBytes())

        lifecycle.cleanupAbandoned(restored)

        assertArrayEquals(planBytes, plan.readBytes())
        assertFalse(owned.exists())
    }

    private fun photo(name: String, bytes: ByteArray): File =
        temporaryFolder.newFile(name).apply { writeBytes(bytes) }
}
