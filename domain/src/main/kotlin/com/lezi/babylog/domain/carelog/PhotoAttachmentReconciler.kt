package com.lezi.babylog.domain.carelog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.lezi.babylog.core.common.MediaContentDigest
import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.MediaLocalPathGate
import com.lezi.babylog.core.model.MAX_RECORD_PHOTOS
import com.lezi.babylog.domain.nextSyncUpdatedAt

internal sealed interface PhotoAttachmentOwner {
    val id: Long

    data class Record(override val id: Long) : PhotoAttachmentOwner {
        init {
            require(id > 0L) { "record photo owner id must be positive" }
        }
    }

    data class CarePlan(override val id: Long) : PhotoAttachmentOwner {
        init {
            require(id > 0L) { "care plan photo owner id must be positive" }
        }
    }
}

internal data class PhotoAttachmentMutation(
    val changed: Boolean,
    val tombstonedClientUuids: Set<String> = emptySet(),
)

/**
 * Authoritative writer for record and care-plan photo attachment rows.
 *
 * This writer does not open a transaction. Callers must invoke it inside the same domain
 * transaction that writes the owning Record or CarePlan so a DAO failure cannot leave a partial
 * root/attachment state.
 *
 * Callers that may attach, revive, or tombstone paths must hold [MediaLocalPathGate] for every
 * involved `local_uri` **before** opening the Room write lease. Global lock order is
 * **path gate → sleepMutationMutex (when used) → Room**. Use [withInvolvedPaths] for that
 * outer exclusion; it is shared with reference-aware file GC and must receive the same
 * process-wide [MediaLocalPathGate] singleton (do not mint a private gate).
 *
 * Module-scoped [internal]: careplan/carelog coordinators may use it; feature modules must not.
 */
internal class PhotoAttachmentReconciler private constructor(
    private val mediaAssetDao: MediaAssetDao,
    private val pathGate: MediaLocalPathGate,
    private val digestFile: (String) -> String?,
    private val uuidFactory: () -> String,
) {
    /** The only accessible constructor retains historical positional/trailing UUID semantics. */
    constructor(
        mediaAssetDao: MediaAssetDao,
        pathGate: MediaLocalPathGate,
        uuidFactory: () -> String = ::newClientUuid,
    ) : this(mediaAssetDao, pathGate, { MediaContentDigest.ofReadableFile(it) }, uuidFactory)

    companion object {
        /** Explicit filesystem-boundary injection cannot compete with the legacy constructor. */
        fun withDigest(
            mediaAssetDao: MediaAssetDao,
            pathGate: MediaLocalPathGate,
            digestFile: (String) -> String?,
            uuidFactory: () -> String = ::newClientUuid,
        ): PhotoAttachmentReconciler =
            PhotoAttachmentReconciler(mediaAssetDao, pathGate, digestFile, uuidFactory)
    }

    /**
     * Acquires path locks for existing owner media plus [additionalPaths], then runs [block].
     * Must wrap the Room transaction that calls [reconcile] / [tombstone], never the reverse.
     *
     * **Snapshot race (intentional, bounded):** owner paths are listed once before locking.
     * A concurrent rebinding of a *different* path onto this owner between list and lock is
     * not covered by this set. Callers still hold locks for every path they attach in
     * [additionalPaths] and for the pre-list snapshot; file reclaim re-resolves under its
     * own path lock and claim identity, so a missed rebinding cannot reclaim active bytes.
     * Blank paths are ignored.
     */
    suspend fun <T> withInvolvedPaths(
        owner: PhotoAttachmentOwner?,
        additionalPaths: Collection<String> = emptyList(),
        block: suspend () -> T,
    ): T {
        val existing = when (owner) {
            is PhotoAttachmentOwner.Record ->
                mediaAssetDao.listForRecord(owner.id).map(MediaAssetEntity::localUri)
            is PhotoAttachmentOwner.CarePlan ->
                mediaAssetDao.listForCarePlan(owner.id).map(MediaAssetEntity::localUri)
            null -> emptyList()
        }
        return pathGate.withLocks(existing + additionalPaths, block)
    }

    internal suspend fun activePlanPhotoSnapshot(planId: Long): List<MediaAssetEntity> =
        mediaAssetDao.listActiveForCarePlan(planId)

    internal suspend fun digestReadablePaths(paths: Collection<String>): Map<String, String> {
        // No filesystem operation means no dispatcher handoff. In particular, a
        // note/time-only edit can commit before its guarded provider follow-up.
        if (paths.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            val digests = linkedMapOf<String, String>()
            paths.forEach { raw ->
                val path = raw.trim()
                if (path.isEmpty() || path in digests) return@forEach
                digestFile(path)?.let { digests[path] = it }
            }
            digests
        }
    }

    /** Returns the exact tombstones that may be handed to physical cleanup after commit. */
    suspend fun reconcile(
        owner: PhotoAttachmentOwner,
        photoLocalPaths: List<String>,
        at: Long,
        contentDigests: Map<String, String> = emptyMap(),
    ): PhotoAttachmentMutation {
        val normalizedPaths = photoLocalPaths
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        require(normalizedPaths.size <= MAX_RECORD_PHOTOS) {
            "每条记录最多 $MAX_RECORD_PHOTOS 张照片"
        }

        val existing = when (owner) {
            is PhotoAttachmentOwner.Record -> mediaAssetDao.listForRecord(owner.id)
            is PhotoAttachmentOwner.CarePlan -> mediaAssetDao.listForCarePlan(owner.id)
        }.filter { it.kind == "log" }
        val active = existing.filter { it.deletedAt == null }
        val desired = normalizedPaths.toSet()
        var changed = false
        val tombstonedClientUuids = linkedSetOf<String>()

        normalizedPaths.forEach { path ->
            val live = active.firstOrNull { it.localUri == path }
            if (live != null) return@forEach

            val tombstoned = existing.firstOrNull {
                it.localUri == path && it.deletedAt != null
            }
            if (tombstoned != null) {
                mediaAssetDao.update(
                    tombstoned.copy(
                        deletedAt = null,
                        updatedAt = nextSyncUpdatedAt(tombstoned.updatedAt, at),
                        syncDirty = true,
                        sha256 = contentDigests[path] ?: tombstoned.sha256,
                    ),
                )
                changed = true
            } else {
                mediaAssetDao.upsert(newAttachment(owner, path, at, contentDigests))
                changed = true
            }
        }

        active.filter { it.localUri !in desired }.forEach { asset ->
            mediaAssetDao.update(
                asset.copy(
                    deletedAt = at,
                    updatedAt = nextSyncUpdatedAt(asset.updatedAt, at),
                    syncDirty = true,
                ),
            )
            changed = true
            tombstonedClientUuids += asset.clientUuid
        }
        return PhotoAttachmentMutation(
            changed = changed,
            tombstonedClientUuids = tombstonedClientUuids,
        )
    }

    suspend fun tombstone(
        owner: PhotoAttachmentOwner,
        deletedAt: Long,
    ): PhotoAttachmentMutation {
        val active = when (owner) {
            is PhotoAttachmentOwner.Record -> mediaAssetDao.listActiveForRecord(owner.id)
            is PhotoAttachmentOwner.CarePlan -> mediaAssetDao.listActiveForCarePlan(owner.id)
        }.filter { it.kind == "log" }

        active.forEach { asset ->
            mediaAssetDao.update(
                asset.copy(
                    deletedAt = deletedAt,
                    updatedAt = nextSyncUpdatedAt(asset.updatedAt, deletedAt),
                    syncDirty = true,
                ),
            )
        }
        return PhotoAttachmentMutation(
            changed = active.isNotEmpty(),
            tombstonedClientUuids = active.mapTo(linkedSetOf()) { it.clientUuid },
        )
    }

    private fun newAttachment(
        owner: PhotoAttachmentOwner,
        path: String,
        at: Long,
        contentDigests: Map<String, String>,
    ): MediaAssetEntity = MediaAssetEntity(
        recordId = (owner as? PhotoAttachmentOwner.Record)?.id,
        carePlanId = (owner as? PhotoAttachmentOwner.CarePlan)?.id,
        clientUuid = uuidFactory(),
        kind = "log",
        babyId = null,
        localUri = path,
        createdAt = at,
        updatedAt = at,
        syncDirty = true,
        sha256 = contentDigests[path],
    )
}
