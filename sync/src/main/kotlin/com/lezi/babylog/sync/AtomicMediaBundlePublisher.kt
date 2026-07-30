package com.lezi.babylog.sync

import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.OutboxEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Publishes one already-mapped root and its complete media manifest atomically.
 *
 * Root mapping, ownership selection, acknowledgement, and outbox cleanup remain
 * caller responsibilities so a failed upload or commit cannot partially drain
 * the local root package.
 */
internal class AtomicMediaBundlePublisher(
    private val backend: SyncBackend,
    private val mediaFiles: SyncMediaFileStore,
    private val loadMedia: suspend (clientUuid: String) -> MediaAssetEntity?,
    private val updateMedia: suspend (MediaAssetEntity) -> Unit,
    private val requireRemoteAllowed: suspend (SyncSession) -> Unit,
) {
    suspend fun publish(
        session: SyncSession,
        bundleId: String,
        root: SyncEntity,
        mediaRows: List<OutboxEntity>,
    ): BundleCommitResult {
        val ownedSources = mutableListOf<PreparedMedia>()
        var primaryFailure: Throwable? = null
        try {
            val prepared = prepare(mediaRows, ownedSources)
            requireRemoteAllowed(session)
            val stage = backend.stageBundle(
                session,
                AtomicBundleDraft(bundleId, root, prepared.entities),
            )
            val toUpload = stage.mediaUuidsToUpload(
                prepared.sources.map { it.first.clientUuid }.toSet(),
            )
            for ((media, source) in prepared.sources) {
                if (media.clientUuid in toUpload) {
                    requireRemoteAllowed(session)
                    backend.putBundleMedia(
                        session,
                        bundleId,
                        media.clientUuid,
                        source,
                    )
                }
            }
            requireRemoteAllowed(session)
            val result = backend.commitBundle(session, bundleId)
            for ((media, _) in prepared.sources) {
                updateMedia(media.copy(remoteUri = session.receiptFor(media.clientUuid)))
            }
            return result
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            closePreparedSources(ownedSources, primaryFailure)
        }
    }

    private suspend fun prepare(
        mediaRows: List<OutboxEntity>,
        ownedSources: MutableList<PreparedMedia>,
    ): AtomicMediaPackage {
        val mediaEntities = mutableListOf<SyncEntity>()
        val mediaSources = mutableListOf<Pair<MediaAssetEntity, PreparedMedia>>()
        for (row in mediaRows) {
            var payload = row.payloadJson
            val media = loadMedia(row.clientUuid)
                ?: error("本地媒体元数据不存在")
            if (row.deletedAt == null && media.localUri.isNotBlank()) {
                val prepared = mediaFiles.prepareUpload(media.localUri)
                ownedSources += prepared
                val updated = media.copy(
                    mime = prepared.mime,
                    width = prepared.width ?: media.width,
                    height = prepared.height ?: media.height,
                    byteSize = prepared.contentLength,
                )
                updateMedia(updated)
                mediaSources += updated to prepared
                val rawObject = Json.parseToJsonElement(payload).jsonObject
                payload = JsonObject(
                    rawObject +
                        ("mime" to JsonPrimitive(updated.mime)) +
                        ("byte_size" to JsonPrimitive(updated.byteSize)) +
                        listOfNotNull(
                            updated.width?.let { "width" to JsonPrimitive(it) },
                            updated.height?.let { "height" to JsonPrimitive(it) },
                        ).toMap(),
                ).toString()
            }
            mediaEntities += SyncEntity(
                type = "media",
                clientUuid = row.clientUuid,
                payloadJson = payload,
                updatedAt = row.updatedAt,
                deletedAt = row.deletedAt,
            )
        }
        return AtomicMediaPackage(mediaEntities, mediaSources)
    }
}

private data class AtomicMediaPackage(
    val entities: List<SyncEntity>,
    val sources: List<Pair<MediaAssetEntity, PreparedMedia>>,
)

private fun closePreparedSources(
    sources: List<PreparedMedia>,
    primaryFailure: Throwable?,
) {
    var cleanupFailure: Throwable? = null
    sources.forEach { source ->
        try {
            source.close()
        } catch (failure: Throwable) {
            val earlierCleanupFailure = cleanupFailure
            if (earlierCleanupFailure == null) {
                cleanupFailure = failure
            } else {
                earlierCleanupFailure.addSuppressed(failure)
            }
        }
    }
    cleanupFailure?.let { failure ->
        if (primaryFailure == null) throw failure else primaryFailure.addSuppressed(failure)
    }
}
