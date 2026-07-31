package com.lezi.babylog.feature.log

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orchestrator-level regressions for ticket 04: cancel-after-write orphans,
 * save-priority preemption reclaim, and attach-only success paths.
 */
class RecordComposerPhotoImportRunnerTest {
    @Test
    fun cancelAfterDiskWriteReclaimsPathsFromOnPathCommittedEvenWhenReturnIsDiscarded() =
        runBlocking {
            val gate = RecordComposerImportSaveSerialization()
            val begin = requireNotNull(gate.beginImport())
            val deleted = mutableListOf<String>()
            val attached = mutableListOf<String>()

            val failure = runCatching {
                runComposerPhotoImport(
                    importSave = gate,
                    epoch = begin.epoch,
                    import = { onPathCommitted ->
                        onPathCommitted("/cache/late-orphan.jpg")
                        // Simulate withContext prompt-cancel after files exist: CE with no return.
                        throw CancellationException("cancelled after write")
                    },
                    delete = { deleted += it },
                    attach = { paths ->
                        attached += paths
                        true
                    },
                )
            }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(listOf("/cache/late-orphan.jpg"), deleted)
            assertTrue(attached.isEmpty())
            assertTrue(gate.drainUnattached().isEmpty())
        }

    @Test
    fun savePriorityPreemptThenJoinReclaimsInFlightProducedPaths() = runBlocking {
        val gate = RecordComposerImportSaveSerialization()
        val begin = requireNotNull(gate.beginImport())
        val deleted = mutableListOf<String>()
        val pathReported = AtomicBoolean(false)

        val importJob = async {
            runComposerPhotoImport(
                importSave = gate,
                epoch = begin.epoch,
                import = { onPathCommitted ->
                    onPathCommitted("/cache/in-flight.jpg")
                    pathReported.set(true)
                    // Stay suspended so preempt can cancel the job before attach.
                    delay(Long.MAX_VALUE)
                    listOf("/cache/in-flight.jpg")
                },
                delete = { deleted += it },
                attach = { false },
            )
        }

        while (!pathReported.get()) {
            yield()
        }

        gate.beginCommit()
        val preempted = gate.preemptImport()
        importJob.cancel()
        joinAndReclaimCancelledImport(
            importSave = gate,
            preemptedOrphans = preempted,
            importJob = importJob,
            delete = { deleted += it },
        )

        assertTrue(
            "expected in-flight path reclaimed via CE callback or drain, got $deleted",
            deleted.contains("/cache/in-flight.jpg"),
        )
        assertTrue(gate.drainUnattached().isEmpty())
        assertFalse(gate.allowImport())
        gate.endCommit()
        assertTrue(gate.allowImport())
    }

    @Test
    fun successfulAttachDoesNotDeletePaths() = runBlocking {
        val gate = RecordComposerImportSaveSerialization()
        val begin = requireNotNull(gate.beginImport())
        val deleted = mutableListOf<String>()
        var draftPhotos = emptyList<String>()

        runComposerPhotoImport(
            importSave = gate,
            epoch = begin.epoch,
            import = { onPathCommitted ->
                val path = "/cache/kept.jpg"
                onPathCommitted(path)
                listOf(path)
            },
            delete = { deleted += it },
            attach = { paths ->
                draftPhotos = paths
                gate.markAttached(begin.epoch)
            },
        )

        assertEquals(listOf("/cache/kept.jpg"), draftPhotos)
        assertTrue(deleted.isEmpty())
        assertTrue(gate.drainUnattached().isEmpty())
    }

    @Test
    fun importWhileCommitActiveIsRejectedAtGateBeforeRunner() {
        val gate = RecordComposerImportSaveSerialization()
        gate.beginCommit()
        assertFalse(gate.allowImport())
        assertEquals(null, gate.beginImport())
    }

    @Test
    fun doubleImportLastWinsDeletesSupersededUnattachedViaCaller() = runBlocking {
        val gate = RecordComposerImportSaveSerialization()
        val first = requireNotNull(gate.beginImport())
        gate.markProduced(first.epoch, listOf("/cache/first.jpg"))

        val second = requireNotNull(gate.beginImport())
        val deleted = mutableListOf<String>()
        deleted += second.supersededUnattached

        runComposerPhotoImport(
            importSave = gate,
            epoch = second.epoch,
            import = { onPath ->
                onPath("/cache/second.jpg")
                listOf("/cache/second.jpg")
            },
            delete = { deleted += it },
            attach = { gate.markAttached(second.epoch) },
        )

        assertEquals(listOf("/cache/first.jpg"), deleted)
        assertTrue(gate.drainUnattached().isEmpty())
    }

    @Test
    fun cancelAndJoinImportJobBeforeResetMatchesCloseContract() = runBlocking {
        val gate = RecordComposerImportSaveSerialization()
        val begin = requireNotNull(gate.beginImport())
        val deleted = mutableListOf<String>()
        val pathReported = AtomicBoolean(false)

        val job = async {
            runComposerPhotoImport(
                importSave = gate,
                epoch = begin.epoch,
                import = { onPath ->
                    onPath("/cache/close-orphan.jpg")
                    pathReported.set(true)
                    delay(Long.MAX_VALUE)
                    listOf("/cache/close-orphan.jpg")
                },
                delete = { deleted += it },
                attach = { false },
            )
        }

        while (!pathReported.get()) {
            yield()
        }
        val preempted = gate.preemptImport()
        job.cancelAndJoin()
        joinAndReclaimCancelledImport(
            importSave = gate,
            preemptedOrphans = preempted,
            importJob = null, // already joined
            delete = { deleted += it },
        )
        gate.reset()

        assertTrue(deleted.contains("/cache/close-orphan.jpg"))
        assertTrue(gate.allowImport())
        assertTrue(gate.drainUnattached().isEmpty())
    }
}
