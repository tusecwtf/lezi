package com.lezi.babylog.feature.log.composer
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*
/**
 * Save-priority serialization for Composer photo import vs save/delete/close.
 *
 * Rules (locked by p1-review-residuals #04):
 * - Import while a save/delete commit is active is rejected.
 * - A newer import supersedes an older in-flight import (last-wins, serial).
 * - Save/delete/close preempts the active import; paths produced on disk but not yet
 *   attached to the draft must be reclaimed (no orphans).
 * - Paths produced after preemption remain reclaimable by epoch.
 *
 * Threading: mutate only on the Composer main/session thread (same assumption as
 * [RecordComposerSessionGate]). Path lists may be accumulated off-thread and handed
 * to [markProduced] after resume.
 *
 * Commit callers must pair [beginCommit] with [preemptImport]/[drainUnattached] and
 * eventually [endCommit]/[reset]; [beginCommit] alone only locks new imports.
 */
internal class RecordComposerImportSaveSerialization {
    private var commitActive: Boolean = false
    private var nextEpoch: Long = 0L
    private var activeEpoch: Long? = null
    private val unattachedByEpoch = linkedMapOf<Long, MutableList<String>>()

    fun allowImport(): Boolean = !commitActive

    /**
     * Locks out new imports and clears the active epoch token.
     * Does **not** drain unattached paths — call [preemptImport] or [drainUnattached]
     * (after joining the cancelled import job) so late produce is not lost.
     */
    fun beginCommit() {
        commitActive = true
        activeEpoch = null
    }

    fun endCommit() {
        commitActive = false
    }

    /**
     * Start a new import epoch. Returns null when a commit is active.
     * [ImportBegin.supersededUnattached] holds prior-import paths that never attached.
     */
    fun beginImport(): ImportBegin? {
        if (commitActive) return null
        val supersededEpoch = activeEpoch
        val superseded = if (supersededEpoch != null) {
            reclaimEpoch(supersededEpoch)
        } else {
            emptyList()
        }
        val epoch = ++nextEpoch
        activeEpoch = epoch
        return ImportBegin(epoch = epoch, supersededUnattached = superseded)
    }

    fun isCurrent(epoch: Long): Boolean = activeEpoch == epoch

    fun markProduced(epoch: Long, paths: List<String>) {
        if (paths.isEmpty()) return
        unattachedByEpoch.getOrPut(epoch) { mutableListOf() }.addAll(paths)
    }

    /** Marks paths as attached to the draft. Only the still-current epoch may succeed. */
    fun markAttached(epoch: Long): Boolean {
        if (activeEpoch != epoch) return false
        unattachedByEpoch.remove(epoch)
        activeEpoch = null
        return true
    }

    /** End the active import and return every unattached path (save/delete/close). */
    fun preemptImport(): List<String> {
        activeEpoch = null
        return drainUnattached()
    }

    fun drainUnattached(): List<String> {
        if (unattachedByEpoch.isEmpty()) return emptyList()
        val all = unattachedByEpoch.values.flatten()
        unattachedByEpoch.clear()
        return all
    }

    /**
     * Late produce under superseded/preempted epochs (not [activeEpoch]).
     * Used after joining a cancelled prior import so the active epoch is preserved.
     */
    fun drainNonCurrentUnattached(): List<String> {
        if (unattachedByEpoch.isEmpty()) return emptyList()
        val current = activeEpoch
        if (current == null) return drainUnattached()
        val drained = mutableListOf<String>()
        val iterator = unattachedByEpoch.entries.iterator()
        while (iterator.hasNext()) {
            val (epoch, paths) = iterator.next()
            if (epoch != current) {
                drained += paths
                iterator.remove()
            }
        }
        return drained
    }

    /**
     * Reclaim one import epoch's unattached paths (cancel, failure, stale late-produce).
     * Clears [activeEpoch] when it still matches.
     */
    fun reclaimEpoch(epoch: Long): List<String> {
        if (activeEpoch == epoch) activeEpoch = null
        return unattachedByEpoch.remove(epoch).orEmpty()
    }

    /** Reset all import/commit bookkeeping (composer open/close). */
    fun reset() {
        commitActive = false
        activeEpoch = null
        unattachedByEpoch.clear()
    }

    internal data class ImportBegin(
        val epoch: Long,
        val supersededUnattached: List<String>,
    )
}
