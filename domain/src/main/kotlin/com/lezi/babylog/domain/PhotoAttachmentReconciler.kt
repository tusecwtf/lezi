package com.lezi.babylog.domain

import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.model.MAX_RECORD_PHOTOS

sealed interface PhotoAttachmentOwner {
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

/**
 * Authoritative writer for record and care-plan photo attachment rows.
 *
 * This writer does not open a transaction. Callers must invoke it inside the same domain
 * transaction that writes the owning Record or CarePlan so a DAO failure cannot leave a partial
 * root/attachment state.
 */
class PhotoAttachmentReconciler(
    private val mediaAssetDao: MediaAssetDao,
    private val uuidFactory: () -> String = ::newClientUuid,
) {
    /** @return true when at least one attachment row was inserted, revived, or tombstoned. */
    suspend fun reconcile(
        owner: PhotoAttachmentOwner,
        photoLocalPaths: List<String>,
        at: Long,
    ): Boolean {
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
                    ),
                )
                changed = true
            } else {
                mediaAssetDao.upsert(newAttachment(owner, path, at))
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
        }
        return changed
    }

    suspend fun tombstone(
        owner: PhotoAttachmentOwner,
        deletedAt: Long,
    ) {
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
    }

    private fun newAttachment(
        owner: PhotoAttachmentOwner,
        path: String,
        at: Long,
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
    )
}
