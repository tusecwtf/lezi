package com.lezi.babylog.sync.media
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.OutboxEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import com.lezi.babylog.sync.backend.AtomicBundleDraft
import com.lezi.babylog.sync.backend.BundleCommitResult
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.mediaUuidsToUpload
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.receiptFor

/**
 * Publishes one already-mapped root and its complete media manifest atomically.
 *
 * Root mapping, ownership selection, acknowledgement, and outbox cleanup remain
 * caller responsibilities so a failed upload or commit cannot partially drain
 * the local root package.
 *
 * Prepare metadata and commit receipts are conditional writes against the domain
 * revision that entered the package (clientUuid + updatedAt + localUri +
 * deletedAt). A concurrent tombstone, revive, path replace, or newer revision
 * keeps the current row; CAS miss does not invent a full-row prepare snapshot.
 */
internal class AtomicMediaBundlePublisher(
    private val backend: SyncBackend,
    private val mediaFiles: SyncMediaFileStore,
    private val loadMedia: suspend (clientUuid: String) -> MediaAssetEntity?,
    private val mergePreparedMetadata: suspend (
        clientUuid: String,
        expectedUpdatedAt: Long,
        expectedLocalUri: String,
        expectedDeletedAt: Long?,
        mime: String?,
        width: Int?,
        height: Int?,
        byteSize: Long,
    ) -> Int,
    private val writeCommitReceipt: suspend (
        clientUuid: String,
        expectedUpdatedAt: Long,
        expectedLocalUri: String,
        expectedDeletedAt: Long?,
        remoteUri: String,
    ) -> Int,
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
                applyFailOpenCas {
                    writeCommitReceipt(
                        media.clientUuid,
                        media.updatedAt,
                        media.localUri,
                        media.deletedAt,
                        session.receiptFor(media.clientUuid),
                    )
                }
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
                val mime = prepared.mime
                val width = prepared.width ?: media.width
                val height = prepared.height ?: media.height
                val byteSize = prepared.contentLength
                // Probe fields ride the wire for this package even when the local
                // row advanced; only merge into Room when the published revision
                // is still current.
                applyFailOpenCas {
                    mergePreparedMetadata(
                        media.clientUuid,
                        media.updatedAt,
                        media.localUri,
                        media.deletedAt,
                        mime,
                        width,
                        height,
                        byteSize,
                    )
                }
                mediaSources += media to prepared
                val rawObject = Json.parseToJsonElement(payload).jsonObject
                payload = JsonObject(
                    rawObject +
                        ("mime" to JsonPrimitive(mime)) +
                        ("byte_size" to JsonPrimitive(byteSize)) +
                        listOfNotNull(
                            width?.let { "width" to JsonPrimitive(it) },
                            height?.let { "height" to JsonPrimitive(it) },
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

/**
 * Runs a conditional media prepare/receipt write. A 0-row result means a
 * concurrent domain edit won and is fail-open — never treated as publish failure.
 */
internal suspend fun applyFailOpenCas(write: suspend () -> Int): Int = write()

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
