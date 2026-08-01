package com.lezi.babylog.core.database

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-path mutual exclusion for media file ownership changes.
 *
 * Lock order is always **path gate → Room write transaction**. Never acquire a path
 * lock while a Room write lease is already held (would deadlock with cleanup, which
 * takes path then Room around short claim/clear transactions and holds path across
 * the slow filesystem delete).
 *
 * Shared by:
 * - reference-aware tombstone file reclaim (file phase)
 * - attach / import path binding / revive of a tombstoned `local_uri`
 */
@Singleton
class MediaLocalPathGate @Inject constructor() {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(path: String, block: suspend () -> T): T {
        val key = path.trim()
        require(key.isNotEmpty()) { "media path lock requires a non-blank path" }
        val mutex = locks.getOrPut(key) { Mutex() }
        return mutex.withLock { block() }
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
