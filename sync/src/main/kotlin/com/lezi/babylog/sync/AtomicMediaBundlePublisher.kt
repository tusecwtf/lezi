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
        val prepared = prepare(mediaRows)
        requireRemoteAllowed(session)
        val stage = backend.stageBundle(
            session,
            AtomicBundleDraft(bundleId, root, prepared.entities),
        )
        val toUpload = stage.mediaUuidsToUpload(
            prepared.bytes.map { it.first.clientUuid }.toSet(),
        )
        for ((media, bytes) in prepared.bytes) {
            if (media.clientUuid in toUpload) {
                requireRemoteAllowed(session)
                backend.putBundleMedia(
                    session,
                    bundleId,
                    media.clientUuid,
                    bytes.bytes,
                    bytes.mime,
                )
            }
            updateMedia(media.copy(remoteUri = session.receiptFor(media.clientUuid)))
        }
        requireRemoteAllowed(session)
        return backend.commitBundle(session, bundleId)
    }

    private suspend fun prepare(mediaRows: List<OutboxEntity>): AtomicMediaPackage {
        val mediaEntities = mutableListOf<SyncEntity>()
        val mediaBytes = mutableListOf<Pair<MediaAssetEntity, PreparedMedia>>()
        for (row in mediaRows) {
            var payload = row.payloadJson
            val media = loadMedia(row.clientUuid)
                ?: error("本地媒体元数据不存在")
            if (row.deletedAt == null && media.localUri.isNotBlank()) {
                val prepared = mediaFiles.prepareUpload(media.localUri)
                val updated = media.copy(
                    mime = prepared.mime,
                    width = prepared.width ?: media.width,
                    height = prepared.height ?: media.height,
                    byteSize = prepared.bytes.size.toLong(),
                )
                updateMedia(updated)
                mediaBytes += updated to prepared
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
        return AtomicMediaPackage(mediaEntities, mediaBytes)
    }
}

private data class AtomicMediaPackage(
    val entities: List<SyncEntity>,
    val bytes: List<Pair<MediaAssetEntity, PreparedMedia>>,
)
