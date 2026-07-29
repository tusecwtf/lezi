package com.lezi.babylog.sync

import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.MediaAssetDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reclaims local media bytes after their last active MediaAsset reference disappears.
 *
 * A tombstone's non-blank localUri is the durable retry marker. The ownership re-check,
 * physical delete, and marker clear share one Room transaction lease so a concurrent domain
 * write cannot publish a new active owner between the check and deletion.
 */
@Singleton
class ReferenceAwareMediaFileCleanup @Inject constructor(
    private val mediaDao: MediaAssetDao,
    private val mediaFiles: SyncMediaFileStore,
    private val transactionRunner: DatabaseTransactionRunner,
) {
    suspend fun cleanupPendingTombstones() {
        cleanupTombstones(mediaDao.listPendingFileCleanupClientUuids().toSet())
    }

    suspend fun cleanupTombstones(clientUuids: Set<String>) {
        clientUuids.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .forEach { clientUuid ->
                transactionRunner.run {
                    val current = mediaDao.getByClientUuid(clientUuid)
                        ?.takeIf { it.deletedAt != null && it.localUri.isNotBlank() }
                        ?: return@run
                    val path = current.localUri
                    if (mediaDao.countActiveReferences(path) == 0) {
                        mediaFiles.delete(path)
                    }
                    val stillPending = mediaDao.getByClientUuid(clientUuid)
                    if (stillPending?.deletedAt != null && stillPending.localUri == path) {
                        mediaDao.update(stillPending.copy(localUri = ""))
                    }
                }
            }
    }
}
