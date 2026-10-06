package com.lezi.babylog.feature.log.composer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

/**
 * One import epoch: write paths to disk, attach to draft, or reclaim on cancel/failure.
 *
 * [import] must invoke [onPathCommitted] as each absolute path is finalized so prompt
 * cancellation that discards the suspend return still leaves a reclaimable path list.
 *
 * Assumes callers drive this from the Composer main/session thread for [importSave] mutations
 * after [import] resumes (same contract as [RecordComposerImportSaveSerialization]).
 */
internal suspend fun runComposerPhotoImport(
    importSave: RecordComposerImportSaveSerialization,
    epoch: Long,
    import: suspend (onPathCommitted: (String) -> Unit) -> List<String>,
    delete: suspend (Collection<String>) -> Unit,
    attach: (List<String>) -> Boolean,
): Boolean {
    val produced = mutableListOf<String>()
    val imported = try {
        import { path -> produced += path }
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) {
            importSave.reclaimEpoch(epoch)
            if (produced.isNotEmpty()) delete(produced)
        }
        throw cancelled
    } catch (error: Throwable) {
        withContext(NonCancellable) {
            importSave.reclaimEpoch(epoch)
            if (produced.isNotEmpty()) delete(produced)
        }
        throw error
    }
    // Prefer the successful return; fall back to callback accumulation if empty.
    val paths = if (imported.isNotEmpty()) imported else produced.toList()
    try {
        importSave.markProduced(epoch, paths)
        // Cancel after write+markProduced must reclaim: ensureActive/attach can throw CE
        // outside the import{} catch above (ticket 04 cancel-without-orphans).
        currentCoroutineContext().ensureActive()
        if (!importSave.isCurrent(epoch)) {
            withContext(NonCancellable) {
                val reclaim = importSave.reclaimEpoch(epoch).ifEmpty { paths }
                if (reclaim.isNotEmpty()) delete(reclaim)
            }
            return false
        }
        val attached = attach(paths)
        if (!attached) {
            withContext(NonCancellable) {
                val reclaim = importSave.reclaimEpoch(epoch).ifEmpty { paths }
                if (reclaim.isNotEmpty()) delete(reclaim)
            }
            return false
        }
        // Attach may have markAttached; any leftover is still an orphan.
        val leftover = importSave.reclaimEpoch(epoch)
        if (leftover.isNotEmpty()) {
            withContext(NonCancellable) { delete(leftover) }
        }
        return true
    } catch (cancelled: CancellationException) {
        // Reclaim only map-tracked unattached paths. Do not fall back to [paths]:
        // empty map means already attached or already reclaimed (avoid deleting draft).
        withContext(NonCancellable) {
            val reclaim = importSave.reclaimEpoch(epoch)
            if (reclaim.isNotEmpty()) delete(reclaim)
        }
        throw cancelled
    }
}

/**
 * After [RecordComposerImportSaveSerialization.beginCommit] + cancel of the import [Job],
 * join that job and delete every path still unattached (preempted + late produce).
 */
internal suspend fun joinAndReclaimCancelledImport(
    importSave: RecordComposerImportSaveSerialization,
    preemptedOrphans: List<String>,
    importJob: kotlinx.coroutines.Job?,
    delete: suspend (Collection<String>) -> Unit,
) {
    importJob?.join()
    val all = preemptedOrphans + importSave.drainUnattached()
    if (all.isNotEmpty()) {
        withContext(NonCancellable) { delete(all) }
    }
}
