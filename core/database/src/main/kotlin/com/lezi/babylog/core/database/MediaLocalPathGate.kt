package com.lezi.babylog.core.database

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-path mutual exclusion for media file ownership changes.
 *
 * Global lock order (never invert):
 * 1. **path gate** ([withLock] / [withLocks])
 * 2. **sleepMutationMutex** (when the writer also mutates open-sleep state)
 * 3. **Room write transaction**
 *
 * Never acquire a path lock while a Room write lease is already held (would deadlock
 * with cleanup, which takes path then Room around short claim/clear transactions and
 * holds path across the slow filesystem delete). Never take sleepMutationMutex *outside*
 * the path gate when the same critical section also needs path locks.
 *
 * Shared by:
 * - reference-aware tombstone file reclaim (file phase)
 * - attach / import path binding / revive of a tombstoned `local_uri`
 *
 * Note: remote media materialization still serializes reclaim work via the sync engine
 * mutex rather than this gate; domain attach/reclaim coordination uses this type.
 */
@Singleton
class MediaLocalPathGate @Inject constructor() {
    private class PathLock {
        val mutex = Mutex()
        // Accessed only inside the map's per-key compute; includes queued users.
        var users = 0
    }

    private val locks = ConcurrentHashMap<String, PathLock>()

    internal val retainedPathCount: Int
        get() = locks.size

    suspend fun <T> withLock(path: String, block: suspend () -> T): T {
        val key = path.trim()
        require(key.isNotEmpty()) { "media path lock requires a non-blank path" }
        val lock = locks.compute(key) { _, existing ->
            (existing ?: PathLock()).also { it.users++ }
        }!!
        try {
            return lock.mutex.withLock { block() }
        } finally {
            // withLock has already unlocked (or acquisition was cancelled). Retain
            // the entry until every owner/waiter is done so a new arrival cannot
            // use a different mutex while an existing user still owns this path.
            locks.compute(key) { _, existing ->
                existing?.takeIf { --it.users > 0 }
            }
        }
    }

    /**
     * Acquires locks for [paths] in sorted order to avoid multi-path deadlocks.
     * Blank paths are ignored; duplicates collapse.
     */
    suspend fun <T> withLocks(paths: Collection<String>, block: suspend () -> T): T {
        val ordered = paths.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
            .toList()
        return withLocksOrdered(ordered, 0, block)
    }

    private suspend fun <T> withLocksOrdered(
        ordered: List<String>,
        index: Int,
        block: suspend () -> T,
    ): T {
        if (index >= ordered.size) return block()
        return withLock(ordered[index]) {
            withLocksOrdered(ordered, index + 1, block)
        }
    }
}

/**
 * Identity of one reclaim attempt. Captured under a short Room write lease before
 * the slow filesystem delete so ABA (same path rebound to a newer revision) cannot
 * clear the wrong tombstone marker.
 */
data class MediaFileCleanupClaim(
    val clientUuid: String,
    val path: String,
    val updatedAt: Long,
    val deletedAt: Long,
) {
    fun matches(entity: MediaAssetEntity): Boolean =
        entity.clientUuid == clientUuid &&
            entity.localUri == path &&
            entity.updatedAt == updatedAt &&
            entity.deletedAt == deletedAt
}
