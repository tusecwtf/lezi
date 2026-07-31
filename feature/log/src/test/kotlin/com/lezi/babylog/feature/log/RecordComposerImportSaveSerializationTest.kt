package com.lezi.babylog.feature.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Public seam: save-priority serialization of Composer photo import vs save/delete/close.
 *
 * Covers ticket 04 acceptance paths without constructing the Hilt ViewModel:
 * import-during-save, save-during-import, double-import last-wins, and cancel-without-orphans.
 */
class RecordComposerImportSaveSerializationTest {
    private val gate = RecordComposerImportSaveSerialization()

    @Test
    fun saveDuringImportReclaimsUnattachedProducedPathsAndBlocksFurtherImport() {
        val begin = requireNotNull(gate.beginImport())
        gate.markProduced(begin.epoch, listOf("/cache/import-a.jpg", "/cache/import-b.jpg"))

        gate.beginCommit()
        val preempted = gate.preemptImport()

        assertEquals(listOf("/cache/import-a.jpg", "/cache/import-b.jpg"), preempted)
        assertFalse(gate.isCurrent(begin.epoch))
        assertNull(gate.beginImport())
        assertTrue(gate.reclaimEpoch(begin.epoch).isEmpty())
        assertTrue(gate.drainUnattached().isEmpty())
    }

    @Test
    fun importWhileCommitActiveIsRejected() {
        gate.beginCommit()

        assertFalse(gate.allowImport())
        assertNull(gate.beginImport())

        gate.endCommit()
        assertTrue(gate.allowImport())
        assertNotNull(gate.beginImport())
    }

    @Test
    fun doubleImportLastWinsReclaimsPriorUnattachedWithoutAttachingStaleEpoch() {
        val first = requireNotNull(gate.beginImport())
        gate.markProduced(first.epoch, listOf("/cache/first.jpg"))

        val second = requireNotNull(gate.beginImport())
        assertEquals(listOf("/cache/first.jpg"), second.supersededUnattached)
        assertFalse(gate.isCurrent(first.epoch))
        assertTrue(gate.isCurrent(second.epoch))

        gate.markProduced(second.epoch, listOf("/cache/second.jpg"))
        assertTrue(gate.markAttached(second.epoch))
        assertTrue(gate.drainUnattached().isEmpty())

        // Late attach from superseded import must not claim ownership; paths were
        // already handed back via supersededUnattached for the caller to delete.
        assertFalse(gate.markAttached(first.epoch))
        assertTrue(gate.reclaimEpoch(first.epoch).isEmpty())
    }

    @Test
    fun cancelOrCloseWithoutAttachDrainsProducedPathsAsOrphans() {
        val begin = requireNotNull(gate.beginImport())
        gate.markProduced(begin.epoch, listOf("/cache/orphan.jpg"))

        val closed = gate.preemptImport()
        assertEquals(listOf("/cache/orphan.jpg"), closed)
        assertFalse(gate.isCurrent(begin.epoch))
        assertTrue(gate.drainUnattached().isEmpty())
    }

    @Test
    fun successfulAttachClearsUnattachedSoCommitDoesNotDeleteDraftPhotos() {
        val begin = requireNotNull(gate.beginImport())
        gate.markProduced(begin.epoch, listOf("/cache/kept.jpg"))
        assertTrue(gate.markAttached(begin.epoch))

        gate.beginCommit()
        assertTrue(gate.preemptImport().isEmpty())
        assertTrue(gate.drainUnattached().isEmpty())
    }

    @Test
    fun lateProduceAfterPreemptStillReclaimableByEpoch() {
        val begin = requireNotNull(gate.beginImport())
        gate.beginCommit()
        gate.preemptImport()

        // Import coroutine finished write after cancel bookkeeping: still reclaimable.
        gate.markProduced(begin.epoch, listOf("/cache/late.jpg"))
        assertEquals(listOf("/cache/late.jpg"), gate.reclaimEpoch(begin.epoch))
        assertTrue(gate.drainUnattached().isEmpty())
    }
}
