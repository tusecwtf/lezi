package com.lezi.babylog.sync.media
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.MediaFileCleanupClaim
import com.lezi.babylog.core.database.MediaLocalPathGate
import com.lezi.babylog.core.database.causal.MediaReferenceDao
import com.lezi.babylog.core.database.causal.mediaBytesEligibleForCleanup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reclaims local media bytes after their last active MediaAsset reference disappears
 * and no [media_references] holder (stable / mutation / branch / duplicate) remains.
 *
 * Two-phase protocol (path gate → short Room leases; never hold Room across FS delete):
 *
 * 1. **Claim (short Room write)** — re-read the exact tombstone, and if the path has no
 *    active owner or media_reference holder, freeze a durable cleanup claim identity
 *    (`clientUuid` + path + `updatedAt` revision + `deletedAt`). When other active rows
 *    or holders still share the path, only clear this tombstone's `local_uri` marker.
 * 2. **File phase (path gate, outside Room)** — under [MediaLocalPathGate] shared with
 *    attach/import/revive, re-validate the claim and holder-aware eligibility in a short
 *    Room lease, then run the slow [SyncMediaFileStore.delete] **outside** any write lease.
 * 3. **Clear marker (short Room write)** — clear `local_uri` only when the claim still
 *    matches the tombstone (ABA / revive / path replace leave the marker alone).
 */
@Singleton
class ReferenceAwareMediaFileCleanup @Inject constructor(
    private val mediaDao: MediaAssetDao,
    private val mediaReferenceDao: MediaReferenceDao,
    private val mediaFiles: SyncMediaFileStore,
    private val transactionRunner: DatabaseTransactionRunner,
    private val pathGate: MediaLocalPathGate,
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
                cleanupOne(clientUuid)
            }
    }

    /**
     * Reclaims staged/discarded paths that have no active MediaAsset row and no
     * media_reference holder. The path gate is acquired before the final Room recheck.
     */
    suspend fun cleanupUnreferencedPaths(paths: Set<String>) {
        paths.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .forEach { path ->
                pathGate.withLock(path) {
                    val eligible = transactionRunner.run {
                        bytesEligibleForCleanup(path)
                    }
                    if (eligible) mediaFiles.delete(path)
                }
            }
    }

    private suspend fun cleanupOne(clientUuid: String) {
        // Peek may race a path rebinding; under lock we re-resolve and retry once so a
        // single cleanupTombstones(uuid) call still converges without waiting for a sweep.
        var pathHint = mediaDao.getByClientUuid(clientUuid)
            ?.takeIf { it.deletedAt != null && it.localUri.isNotBlank() }
            ?.localUri
            ?: return
        var attempts = 0
        while (attempts < 2) {
            attempts += 1
            val outcome = pathGate.withLock(pathHint) {
                val claim = transactionRunner.run {
                    val current = mediaDao.getByClientUuid(clientUuid)
                        ?.takeIf { it.deletedAt != null && it.localUri.isNotBlank() }
                        ?: return@run ClaimDecision.Skip
                    val path = current.localUri
                    if (path != pathHint) {
                        // Holding the wrong path lock; release and re-acquire the live path.
                        return@run ClaimDecision.RetryWithPath(path)
                    }
                    if (!bytesEligibleForCleanup(path)) {
                        // Shared bytes still owned by asset or media_reference holders —
                        // only drop this tombstone's retry marker when an active asset remains.
                        if (mediaDao.countActiveReferences(path) > 0) {
                            clearMarkerIfMatches(current, path)
                        }
                        return@run ClaimDecision.Skip
                    }
                    val deletedAt = current.deletedAt ?: return@run ClaimDecision.Skip
                    ClaimDecision.Reclaim(
                        MediaFileCleanupClaim(
                            clientUuid = clientUuid,
                            path = path,
                            updatedAt = current.updatedAt,
                            deletedAt = deletedAt,
                        ),
                    )
                }

                when (claim) {
                    is ClaimDecision.RetryWithPath -> return@withLock ClaimDecision.RetryWithPath(claim.path)
                    ClaimDecision.Skip -> return@withLock ClaimDecision.Skip
                    is ClaimDecision.Reclaim -> Unit
                }
                val frozen = (claim as ClaimDecision.Reclaim).claim

                // Re-validate immediately before the slow delete while still holding the
                // path gate so attach/import/revive cannot publish a new active owner.
                val stillSafe = transactionRunner.run {
                    val current = mediaDao.getByClientUuid(frozen.clientUuid) ?: return@run false
                    frozen.matches(current) && bytesEligibleForCleanup(frozen.path)
                }
                if (!stillSafe) {
                    // New active reference, media_reference holder, or ABA revision —
                    // leave marker for a later pass only when still our pending path.
                    transactionRunner.run {
                        val current = mediaDao.getByClientUuid(frozen.clientUuid) ?: return@run
                        if (current.deletedAt != null && current.localUri == frozen.path) {
                            if (mediaDao.countActiveReferences(frozen.path) > 0) {
                                clearMarkerIfMatches(current, frozen.path)
                            }
                        }
                    }
                    return@withLock ClaimDecision.Skip
                }

                // FILE PHASE — reclaim-scoped: outside DatabaseTransactionRunner / Room lease.
                mediaFiles.delete(frozen.path)

                transactionRunner.run {
                    val stillPending = mediaDao.getByClientUuid(frozen.clientUuid) ?: return@run
                    if (frozen.matches(stillPending)) {
                        mediaDao.update(stillPending.copy(localUri = ""))
                    }
                }
                ClaimDecision.Skip
            }
            when (outcome) {
                is ClaimDecision.RetryWithPath -> pathHint = outcome.path
                else -> return
            }
        }
    }

    private suspend fun bytesEligibleForCleanup(path: String): Boolean =
        mediaBytesEligibleForCleanup(
            activeMediaAssetReferences = mediaDao.countActiveReferences(path),
            mediaReferenceHolders = mediaReferenceDao.countHoldersForLocalUri(path),
        )

    private suspend fun clearMarkerIfMatches(current: MediaAssetEntity, path: String) {
        if (current.deletedAt != null && current.localUri == path) {
            mediaDao.update(current.copy(localUri = ""))
        }
    }

    private sealed class ClaimDecision {
        data object Skip : ClaimDecision()
        data class Reclaim(val claim: MediaFileCleanupClaim) : ClaimDecision()
        data class RetryWithPath(val path: String) : ClaimDecision()
    }
}
