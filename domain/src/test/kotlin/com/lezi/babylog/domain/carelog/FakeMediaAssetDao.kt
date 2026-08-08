package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.matchesPublishedRevision
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory [MediaAssetDao] for carelog unit tests (photo reconcile, façade suite).
 * Shared so carelog package tests are not coupled to the root [CareLogTest] mega-suite.
 */
internal class FakeMediaAssetDao : MediaAssetDao {
    private val items = mutableListOf<MediaAssetEntity>()
    private val seq = AtomicLong(1)
    var failUpserts: Boolean = false
    private var updateCount: Int = 0
    private var failOnUpdateCount: Int? = null
    private var txSnapshot: List<MediaAssetEntity>? = null
    private var txSeq: Long? = null

    fun beginTx() {
        txSnapshot = items.toList()
        txSeq = seq.get()
    }

    fun commitTx() {
        txSnapshot = null
        txSeq = null
    }

    fun rollbackTx() {
        txSnapshot?.let {
            items.clear()
            items += it
        }
        txSeq?.let { seq.set(it) }
        txSnapshot = null
        txSeq = null
    }

    fun failUpdateAfterSuccessfulUpdates(count: Int) {
        require(count >= 0)
        failOnUpdateCount = updateCount + count + 1
    }

    fun seed(entity: MediaAssetEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: seq.getAndIncrement()
        if (entity.id > 0L) {
            seq.updateAndGet { next -> maxOf(next, entity.id + 1L) }
        }
        items.removeAll { it.id == id }
        items += entity.copy(id = id)
        return id
    }

    override suspend fun upsert(asset: MediaAssetEntity): Long {
        if (failUpserts) {
            throw IllegalStateException("media upsert failed")
        }
        return seed(asset)
    }

    override suspend fun listForRecord(recordId: Long): List<MediaAssetEntity> =
        items.filter { it.recordId == recordId }

    override suspend fun listActiveForRecord(recordId: Long): List<MediaAssetEntity> =
        items.filter { it.recordId == recordId && it.deletedAt == null }.sortedBy { it.id }

    override suspend fun listForCarePlan(carePlanId: Long): List<MediaAssetEntity> =
        items.filter { it.carePlanId == carePlanId }

    override suspend fun listActiveForCarePlan(carePlanId: Long): List<MediaAssetEntity> =
        items.filter { it.carePlanId == carePlanId && it.deletedAt == null }.sortedBy { it.id }

    override suspend fun listActiveForWakeObservation(
        wakeObservationId: Long,
    ): List<MediaAssetEntity> =
        items.filter {
            it.wakeObservationId == wakeObservationId && it.deletedAt == null
        }.sortedBy { it.id }

    override suspend fun activeAvatarForBaby(babyId: Long): MediaAssetEntity? =
        items.filter { it.babyId == babyId && it.kind == "avatar" && it.deletedAt == null }
            .maxWithOrNull(compareBy<MediaAssetEntity> { it.updatedAt }.thenBy { it.id })

    override suspend fun listActiveAvatarsForBaby(babyId: Long): List<MediaAssetEntity> =
        items.filter { it.babyId == babyId && it.kind == "avatar" && it.deletedAt == null }
            .sortedBy { it.id }

    override suspend fun listAllIncludingDeleted(): List<MediaAssetEntity> =
        items.sortedBy { it.id }

    override suspend fun listPendingSync(): List<MediaAssetEntity> =
        items.filter { it.syncDirty }.sortedBy { it.id }

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        items.replaceAll {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int {
        val before = items.size
        items.removeAll {
            it.clientUuid == clientUuid && it.updatedAt == updatedAt && it.deletedAt != null
        }
        return before - items.size
    }

    override suspend fun deleteExactRevision(
        clientUuid: String,
        expectedUpdatedAt: Long,
        expectedLocalUri: String,
        expectedDeletedAt: Long?,
    ): Int {
        val before = items.size
        items.removeAll {
            it.clientUuid == clientUuid &&
                it.updatedAt == expectedUpdatedAt &&
                it.localUri == expectedLocalUri &&
                it.deletedAt == expectedDeletedAt
        }
        return before - items.size
    }

    override suspend fun listMissingLocalBytes(): List<MediaAssetEntity> =
        items.filter {
            it.deletedAt == null && it.remoteUri != null && it.localUri.isEmpty()
        }.sortedBy { it.id }

    override suspend fun getByClientUuid(uuid: String): MediaAssetEntity? =
        items.find { it.clientUuid == uuid }

    override suspend fun countActiveReferences(localUri: String): Int =
        items.count { it.localUri == localUri && it.deletedAt == null }

    override suspend fun listPendingFileCleanupClientUuids(): List<String> =
        items.filter { it.deletedAt != null && it.localUri.isNotBlank() }
            .sortedBy { it.id }
            .map { it.clientUuid }

    override suspend fun update(asset: MediaAssetEntity) {
        updateCount += 1
        if (updateCount == failOnUpdateCount) {
            throw IllegalStateException("media update failed")
        }
        items.replaceAll { if (it.id == asset.id) asset else it }
    }

    override suspend fun mergePreparedMetadata(
        clientUuid: String,
        expectedUpdatedAt: Long,
        expectedLocalUri: String,
        expectedDeletedAt: Long?,
        mime: String?,
        width: Int?,
        height: Int?,
        byteSize: Long,
    ): Int {
        var changed = 0
        items.replaceAll {
            if (
                it.matchesPublishedRevision(
                    expectedClientUuid = clientUuid,
                    expectedUpdatedAt = expectedUpdatedAt,
                    expectedLocalUri = expectedLocalUri,
                    expectedDeletedAt = expectedDeletedAt,
                )
            ) {
                changed = 1
                it.copy(mime = mime, width = width, height = height, byteSize = byteSize)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun writeCommitReceipt(
        clientUuid: String,
        expectedUpdatedAt: Long,
        expectedLocalUri: String,
        expectedDeletedAt: Long?,
        remoteUri: String,
    ): Int {
        var changed = 0
        items.replaceAll {
            if (
                it.matchesPublishedRevision(
                    expectedClientUuid = clientUuid,
                    expectedUpdatedAt = expectedUpdatedAt,
                    expectedLocalUri = expectedLocalUri,
                    expectedDeletedAt = expectedDeletedAt,
                )
            ) {
                changed = 1
                it.copy(remoteUri = remoteUri)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun clearRemoteUris() {
        items.replaceAll { it.copy(remoteUri = null, syncDirty = true) }
    }

    override suspend fun deleteLogMedia() {
        items.removeAll { it.kind == "log" }
    }

    override suspend fun deleteByClientUuids(clientUuids: List<String>) {
        items.removeAll { it.clientUuid in clientUuids }
    }

    override suspend fun deleteForRecord(recordId: Long) {
        items.removeAll { it.recordId == recordId }
    }

    override suspend fun deleteAll() {
        items.clear()
    }
}
