package com.lezi.babylog.sync.media
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.OutboxEntity
import com.lezi.babylog.core.database.matchesPublishedRevision
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.lezi.babylog.sync.backend.AtomicBundleDraft
import com.lezi.babylog.sync.backend.BundleCommitResult
import com.lezi.babylog.sync.backend.BundleStageStatus
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.backend.FakeSyncBackend
import com.lezi.babylog.sync.engine.receiptFor

/**
 * Public seams:
 * - [AtomicMediaBundlePublisher.publish] stages probe metadata on the wire,
 *   uploads, commits the root, then CAS-writes prepare fields / receipt only.
 * - Concurrent tombstone / path replace / higher updatedAt during a long upload
 *   must keep the current row (no full prepare snapshot rewrite).
 */
class AtomicMediaBundlePublisherTest {
    @Test
    fun zeroMediaStillStagesAndCommitsTheRootWithoutPreparingAFile() = runTest {
        val backend = RecordingAtomicBundleBackend()
        val publisher = AtomicMediaBundlePublisher(
            backend = backend,
            mediaFiles = QueueMediaFileStore(),
            loadMedia = { error("zero media must not load metadata") },
            mergePreparedMetadata = { _, _, _, _, _, _, _, _ ->
                error("zero media must not merge metadata")
            },
            writeCommitReceipt = { _, _, _, _, _ ->
                error("zero media must not write receipt")
            },
            requireRemoteAllowed = { backend.operations += "gate" },
        )

        val result = publisher.publish(
            session = session,
            bundleId = "record:record-1:10",
            root = SyncEntity("record", "record-1", "{}", 10),
            mediaRows = emptyList(),
        )

        assertThat(result).isEqualTo(backend.commitResult)
        assertThat(backend.operations).containsExactly("gate", "stage", "gate", "commit")
            .inOrder()
        assertThat(backend.uploads).isEmpty()
    }

    @Test
    fun publishPreparesManifestUploadsMissingMediaCommitsThenWritesReceipt() = runTest {
        val backend = RecordingAtomicBundleBackend()
        val store = CasMediaStore(
            mediaAsset(
                clientUuid = "media-1",
                mime = "image/png",
                width = 80,
                height = 60,
                byteSize = 99,
            ),
        )
        val publisher = publisher(backend, store, preparedMedia(
            bytes = byteArrayOf(1, 2, 3),
            mime = "image/jpeg",
            width = 40,
            height = 30,
        ))

        val result = publisher.publish(
            session = session,
            bundleId = "record:record-1:10",
            root = SyncEntity(
                type = "record",
                clientUuid = "record-1",
                payloadJson = "{}",
                updatedAt = 10,
            ),
            mediaRows = listOf(mediaOutboxRow("media-1")),
        )

        val stagedMedia = backend.staged.single().media.single()
        assertThat(stagedMedia.payloadJson).isEqualTo(
            "{\"kind\":\"log\",\"mime\":\"image/jpeg\",\"byte_size\":3," +
                "\"width\":40,\"height\":30}",
        )
        assertThat(backend.uploads.single().bytes).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(backend.uploads.single().mime).isEqualTo("image/jpeg")
        assertThat(store.get("media-1")).isEqualTo(
            mediaAsset(
                clientUuid = "media-1",
                mime = "image/jpeg",
                width = 40,
                height = 30,
                byteSize = 3,
                remoteUri = session.receiptFor("media-1"),
            ),
        )
        assertThat(result).isEqualTo(backend.commitResult)
        assertThat(backend.operations).containsExactly(
            "gate",
            "stage",
            "gate",
            "put:media-1",
            "gate",
            "commit",
        ).inOrder()
    }

    @Test
    fun uploadFailureDoesNotWriteReceiptOrCommitBundle() = runTest {
        val backend = RecordingAtomicBundleBackend().apply {
            putFailure = IllegalStateException("upload failed")
        }
        val store = CasMediaStore(
            mediaAsset(
                clientUuid = "media-1",
                mime = null,
                width = null,
                height = null,
                byteSize = 0,
            ),
        )
        val publisher = publisher(
            backend,
            store,
            preparedMedia(byteArrayOf(1, 2, 3), "image/jpeg", 40, 30),
        )

        val failure = runCatching {
            publisher.publish(
                session = session,
                bundleId = "record:record-1:10",
                root = SyncEntity("record", "record-1", "{}", 10),
                mediaRows = listOf(mediaOutboxRow("media-1")),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo("upload failed")
        assertThat(store.get("media-1").remoteUri).isNull()
        // Prepare CAS still merges probe fields when the published revision holds.
        assertThat(store.get("media-1").byteSize).isEqualTo(3)
        assertThat(backend.operations).containsExactly(
            "gate",
            "stage",
            "gate",
            "put:media-1",
        ).inOrder()
    }

    @Test
    fun secondOfThreeUploadFailureClosesWholeBatchAndRetryWritesReceiptsAfterCommit() = runTest {
        val backend = RecordingAtomicBundleBackend().apply {
            putFailureClientUuid = "media-2"
            putFailure = IllegalStateException("middle upload failed")
        }
        val store = CasMediaStore(
            *(1..3).map { index ->
                mediaAsset(
                    clientUuid = "media-$index",
                    mime = null,
                    width = null,
                    height = null,
                    byteSize = 0,
                )
            }.toTypedArray(),
        )
        val firstAttempt = (1..3).map { index ->
            preparedMedia(byteArrayOf(index.toByte()), "image/jpeg", 40, 30)
        }
        val retryAttempt = (1..3).map { index ->
            preparedMedia(byteArrayOf(index.toByte()), "image/jpeg", 40, 30)
        }
        val mediaFiles = QueueMediaFileStore(*(firstAttempt + retryAttempt).toTypedArray())
        val publisher = AtomicMediaBundlePublisher(
            backend = backend,
            mediaFiles = mediaFiles,
            loadMedia = store::getOrNull,
            mergePreparedMetadata = store::mergePreparedMetadata,
            writeCommitReceipt = store::writeCommitReceipt,
            requireRemoteAllowed = { backend.operations += "gate" },
        )
        val rows = (1..3).map { mediaOutboxRow("media-$it") }

        val firstFailure = runCatching {
            publisher.publish(
                session = session,
                bundleId = "record:record-1:10",
                root = SyncEntity("record", "record-1", "{}", 10),
                mediaRows = rows,
            )
        }.exceptionOrNull()

        assertThat(firstFailure).hasMessageThat().isEqualTo("middle upload failed")
        assertThat(backend.operations).doesNotContain("commit")
        assertThat(store.all().map { it.remoteUri }).containsExactly(null, null, null)
        assertThat(firstAttempt.map { it.file.exists() }).containsExactly(false, false, false)

        backend.operations.clear()
        backend.uploads.clear()
        backend.putFailure = null
        backend.putFailureClientUuid = null
        val retried = publisher.publish(
            session = session,
            bundleId = "record:record-1:10",
            root = SyncEntity("record", "record-1", "{}", 10),
            mediaRows = rows,
        )

        assertThat(retried).isEqualTo(backend.commitResult)
        assertThat(backend.uploads.map { it.clientUuid })
            .containsExactly("media-1", "media-2", "media-3").inOrder()
        assertThat(backend.operations.last()).isEqualTo("commit")
        assertThat(store.all().map { it.remoteUri }).containsExactly(
            session.receiptFor("media-1"),
            session.receiptFor("media-2"),
            session.receiptFor("media-3"),
        )
        assertThat(retryAttempt.map { it.file.exists() }).containsExactly(false, false, false)
    }

    @Test
    fun cancellationDuringSecondUploadClosesEveryPreparedSourceWithoutCommitOrReceipt() = runTest {
        val backend = RecordingAtomicBundleBackend().apply {
            putFailureClientUuid = "media-2"
            putFailure = CancellationException("sync cancelled")
        }
        val store = CasMediaStore(
            *(1..3).map { index ->
                mediaAsset(
                    clientUuid = "media-$index",
                    mime = null,
                    width = null,
                    height = null,
                    byteSize = 0,
                )
            }.toTypedArray(),
        )
        val prepared = (1..3).map { index ->
            preparedMedia(byteArrayOf(index.toByte()), "image/jpeg", 40, 30)
        }
        val publisher = AtomicMediaBundlePublisher(
            backend = backend,
            mediaFiles = QueueMediaFileStore(*prepared.toTypedArray()),
            loadMedia = store::getOrNull,
            mergePreparedMetadata = store::mergePreparedMetadata,
            writeCommitReceipt = store::writeCommitReceipt,
            requireRemoteAllowed = { backend.operations += "gate" },
        )

        val failure = supervisorScope {
            val publishing = async {
                publisher.publish(
                    session = session,
                    bundleId = "record:record-1:10",
                    root = SyncEntity("record", "record-1", "{}", 10),
                    mediaRows = (1..3).map { mediaOutboxRow("media-$it") },
                )
            }
            runCatching { publishing.await() }.exceptionOrNull()
        }

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(backend.operations).doesNotContain("commit")
        assertThat(store.all().map { it.remoteUri }).containsExactly(null, null, null)
        assertThat(prepared.map { it.file.exists() }).containsExactly(false, false, false)
    }

    @Test
    fun concurrentTombstoneDuringUploadKeepsTombstoneAndRejectsStaleReceipt() = runTest {
        val store = CasMediaStore(
            mediaAsset(
                clientUuid = "media-1",
                mime = null,
                width = null,
                height = null,
                byteSize = 0,
                updatedAt = 2,
                localUri = "/local/media-1",
            ),
        )
        val backend = RecordingAtomicBundleBackend().apply {
            onPut = {
                store.replace(
                    mediaAsset(
                        clientUuid = "media-1",
                        mime = null,
                        width = null,
                        height = null,
                        byteSize = 0,
                        updatedAt = 99,
                        localUri = "/local/media-1",
                        deletedAt = 99,
                        syncDirty = true,
                    ),
                )
            }
        }
        val publisher = publisher(
            backend,
            store,
            preparedMedia(byteArrayOf(1, 2, 3), "image/jpeg", 40, 30),
        )

        publisher.publish(
            session = session,
            bundleId = "record:record-1:10",
            root = SyncEntity("record", "record-1", "{}", 10),
            mediaRows = listOf(mediaOutboxRow("media-1", updatedAt = 2)),
        )

        val after = store.get("media-1")
        assertThat(after.deletedAt).isEqualTo(99)
        assertThat(after.updatedAt).isEqualTo(99)
        assertThat(after.remoteUri).isNull()
        assertThat(after.syncDirty).isTrue()
        assertThat(after.mime).isNull()
        assertThat(after.byteSize).isEqualTo(0)
        assertThat(backend.operations).contains("commit")
    }

    @Test
    fun concurrentLocalUriReplaceDuringUploadKeepsNewPathAndRejectsStaleReceipt() = runTest {
        val store = CasMediaStore(
            mediaAsset(
                clientUuid = "media-1",
                mime = "image/png",
                width = 10,
                height = 10,
                byteSize = 4,
                updatedAt = 2,
                localUri = "/local/old.jpg",
            ),
        )
        val backend = RecordingAtomicBundleBackend().apply {
            onPut = {
                store.replace(
                    mediaAsset(
                        clientUuid = "media-1",
                        mime = "image/png",
                        width = 10,
                        height = 10,
                        byteSize = 4,
                        updatedAt = 50,
                        localUri = "/local/new.jpg",
                        syncDirty = true,
                    ),
                )
            }
        }
        val publisher = publisher(
            backend,
            store,
            preparedMedia(byteArrayOf(9, 9, 9), "image/jpeg", 40, 30),
        )

        publisher.publish(
            session = session,
            bundleId = "record:record-1:10",
            root = SyncEntity("record", "record-1", "{}", 10),
            mediaRows = listOf(mediaOutboxRow("media-1", updatedAt = 2)),
        )

        val after = store.get("media-1")
        assertThat(after.localUri).isEqualTo("/local/new.jpg")
        assertThat(after.updatedAt).isEqualTo(50)
        assertThat(after.remoteUri).isNull()
        assertThat(after.mime).isEqualTo("image/png")
        assertThat(after.byteSize).isEqualTo(4)
        assertThat(after.syncDirty).isTrue()
    }

    @Test
    fun concurrentHigherRevisionPathReplaceRejectsStalePrepareAndReceipt() = runTest {
        // Publish starts against a live revision; mid-upload domain writes a
        // higher updatedAt + new localUri (path replace / recapture).
        val store = CasMediaStore(
            mediaAsset(
                clientUuid = "media-1",
                mime = "image/png",
                width = 8,
                height = 8,
                byteSize = 2,
                updatedAt = 2,
                localUri = "/local/media-1",
            ),
        )
        val backend = RecordingAtomicBundleBackend().apply {
            onPut = {
                store.replace(
                    mediaAsset(
                        clientUuid = "media-1",
                        mime = "image/webp",
                        width = 64,
                        height = 64,
                        byteSize = 128,
                        updatedAt = 77,
                        localUri = "/local/revived.jpg",
                        syncDirty = true,
                    ),
                )
            }
        }
        val publisher = publisher(
            backend,
            store,
            preparedMedia(byteArrayOf(1, 2, 3), "image/jpeg", 40, 30),
        )

        publisher.publish(
            session = session,
            bundleId = "record:record-1:10",
            root = SyncEntity("record", "record-1", "{}", 10),
            mediaRows = listOf(mediaOutboxRow("media-1", updatedAt = 2)),
        )

        val after = store.get("media-1")
        assertThat(after.updatedAt).isEqualTo(77)
        assertThat(after.localUri).isEqualTo("/local/revived.jpg")
        assertThat(after.mime).isEqualTo("image/webp")
        assertThat(after.width).isEqualTo(64)
        assertThat(after.height).isEqualTo(64)
        assertThat(after.byteSize).isEqualTo(128)
        assertThat(after.remoteUri).isNull()
        assertThat(after.syncDirty).isTrue()
        assertThat(after.deletedAt).isNull()
    }

    @Test
    fun higherUpdatedAtAloneBlocksStalePrepareMetadataAndReceipt() = runTest {
        val store = CasMediaStore(
            mediaAsset(
                clientUuid = "media-1",
                mime = "image/png",
                width = 1,
                height = 1,
                byteSize = 1,
                updatedAt = 2,
                localUri = "/local/media-1",
            ),
        )
        val backend = RecordingAtomicBundleBackend().apply {
            onPut = {
                store.replace(
                    mediaAsset(
                        clientUuid = "media-1",
                        mime = "image/png",
                        width = 1,
                        height = 1,
                        byteSize = 1,
                        updatedAt = 100,
                        localUri = "/local/media-1",
                        syncDirty = true,
                    ),
                )
            }
        }
        val publisher = publisher(
            backend,
            store,
            preparedMedia(byteArrayOf(5, 5, 5), "image/jpeg", 99, 88),
        )

        // Prepare succeeds against revision 2 before the concurrent edit.
        // Put mutates to 100; receipt CAS must not land.
        publisher.publish(
            session = session,
            bundleId = "record:record-1:10",
            root = SyncEntity("record", "record-1", "{}", 10),
            mediaRows = listOf(mediaOutboxRow("media-1", updatedAt = 2)),
        )

        val after = store.get("media-1")
        assertThat(after.updatedAt).isEqualTo(100)
        assertThat(after.remoteUri).isNull()
        // Prepare ran before the concurrent edit and CAS-merged probe fields onto
        // revision 2; the concurrent write then replaced the row with revision 100
        // that never received those probe fields or a receipt.
        assertThat(after.mime).isEqualTo("image/png")
        assertThat(after.byteSize).isEqualTo(1)
        assertThat(after.syncDirty).isTrue()
    }

    private fun publisher(
        backend: RecordingAtomicBundleBackend,
        store: CasMediaStore,
        prepared: PreparedMedia,
    ) = AtomicMediaBundlePublisher(
        backend = backend,
        mediaFiles = StubMediaFileStore(prepared),
        loadMedia = store::getOrNull,
        mergePreparedMetadata = store::mergePreparedMetadata,
        writeCommitReceipt = store::writeCommitReceipt,
        requireRemoteAllowed = { backend.operations += "gate" },
    )

    private companion object {
        val session = SyncSession(
            familyId = "family-1",
            accessToken = "token-1",
            deviceId = "device-1",
            role = FamilyRole.Owner,
            pullGeneration = "generation-1",
            serverHost = "nas.local",
            membershipId = "membership-1",
        )

        fun mediaAsset(
            clientUuid: String,
            mime: String?,
            width: Int?,
            height: Int?,
            byteSize: Long,
            remoteUri: String? = null,
            updatedAt: Long = 2,
            localUri: String = "/local/$clientUuid",
            deletedAt: Long? = null,
            syncDirty: Boolean = true,
        ) = MediaAssetEntity(
            id = 1,
            recordId = 1,
            clientUuid = clientUuid,
            localUri = localUri,
            remoteUri = remoteUri,
            mime = mime,
            width = width,
            height = height,
            byteSize = byteSize,
            createdAt = 1,
            updatedAt = updatedAt,
            deletedAt = deletedAt,
            syncDirty = syncDirty,
        )

        fun mediaOutboxRow(clientUuid: String, updatedAt: Long = 2) = OutboxEntity(
            id = 2,
            familyId = "family-1",
            entityType = "media",
            clientUuid = clientUuid,
            payloadJson = "{\"kind\":\"log\"}",
            updatedAt = updatedAt,
        )
    }
}

/**
 * In-memory CAS surface matching [com.lezi.babylog.core.database.MediaAssetDao]
 * mergePreparedMetadata / writeCommitReceipt via the shared
 * [com.lezi.babylog.core.database.matchesPublishedRevision] helper.
 */
private class CasMediaStore(vararg initial: MediaAssetEntity) {
    private val stored = initial.associateBy { it.clientUuid }.toMutableMap()

    fun get(clientUuid: String): MediaAssetEntity = stored.getValue(clientUuid)

    fun getOrNull(clientUuid: String): MediaAssetEntity? = stored[clientUuid]

    fun all(): List<MediaAssetEntity> = stored.values.toList()

    fun replace(entity: MediaAssetEntity) {
        stored[entity.clientUuid] = entity
    }

    fun mergePreparedMetadata(
        clientUuid: String,
        expectedUpdatedAt: Long,
        expectedLocalUri: String,
        expectedDeletedAt: Long?,
        mime: String?,
        width: Int?,
        height: Int?,
        byteSize: Long,
    ): Int {
        val current = stored[clientUuid] ?: return 0
        if (
            !current.matchesPublishedRevision(
                expectedClientUuid = clientUuid,
                expectedUpdatedAt = expectedUpdatedAt,
                expectedLocalUri = expectedLocalUri,
                expectedDeletedAt = expectedDeletedAt,
            )
        ) {
            return 0
        }
        stored[clientUuid] = current.copy(
            mime = mime,
            width = width,
            height = height,
            byteSize = byteSize,
        )
        return 1
    }

    fun writeCommitReceipt(
        clientUuid: String,
        expectedUpdatedAt: Long,
        expectedLocalUri: String,
        expectedDeletedAt: Long?,
        remoteUri: String,
    ): Int {
        val current = stored[clientUuid] ?: return 0
        if (
            !current.matchesPublishedRevision(
                expectedClientUuid = clientUuid,
                expectedUpdatedAt = expectedUpdatedAt,
                expectedLocalUri = expectedLocalUri,
                expectedDeletedAt = expectedDeletedAt,
            )
        ) {
            return 0
        }
        stored[clientUuid] = current.copy(remoteUri = remoteUri)
        return 1
    }
}

private class RecordingAtomicBundleBackend(
    delegate: SyncBackend = FakeSyncBackend(),
) : SyncBackend by delegate {
    data class Upload(
        val bundleId: String,
        val clientUuid: String,
        val bytes: ByteArray,
        val mime: String?,
    )

    val staged = mutableListOf<AtomicBundleDraft>()
    val uploads = mutableListOf<Upload>()
    val operations = mutableListOf<String>()
    var putFailure: Throwable? = null
    var putFailureClientUuid: String? = null
    var onPut: (() -> Unit)? = null
    val commitResult = BundleCommitResult(
        bundleId = "record:record-1:10",
        status = "committed",
        applied = 2,
        cursor = 3,
    )

    override suspend fun stageBundle(
        session: SyncSession,
        draft: AtomicBundleDraft,
    ): BundleStageStatus {
        operations += "stage"
        staged += draft
        return BundleStageStatus(
            bundleId = draft.bundleId,
            status = "staging",
            missingMedia = draft.media.filter { it.deletedAt == null }.map { it.clientUuid },
        )
    }

    override suspend fun putBundleMedia(
        session: SyncSession,
        bundleId: String,
        clientUuid: String,
        source: SyncMediaUploadSource,
    ): BundleStageStatus {
        operations += "put:$clientUuid"
        onPut?.invoke()
        if (putFailureClientUuid == null || putFailureClientUuid == clientUuid) {
            putFailure?.let { throw it }
        }
        val bytes = source.openStream().use { it.readBytes() }
        uploads += Upload(bundleId, clientUuid, bytes, source.mime)
        return BundleStageStatus(
            bundleId = bundleId,
            status = "staging",
            stagedMedia = listOf(clientUuid),
        )
    }

    override suspend fun commitBundle(
        session: SyncSession,
        bundleId: String,
    ): BundleCommitResult {
        operations += "commit"
        return commitResult.copy(bundleId = bundleId)
    }
}

private class StubMediaFileStore(
    private val prepared: PreparedMedia,
) : SyncMediaFileStore {
    override suspend fun inspect(localUri: String): LocalMediaInfo? = null
    override suspend fun prepareUpload(localUri: String): PreparedMedia = prepared

    override suspend fun saveDownloaded(
        clientUuid: String,
        kind: String,
        bytes: ByteArray,
        mime: String?,
    ): String = error("download is outside this test")

    override suspend fun delete(localUri: String) = error("delete is outside this test")
}

private class QueueMediaFileStore(
    vararg prepared: PreparedMedia,
) : SyncMediaFileStore {
    private val queue = ArrayDeque(prepared.toList())

    override suspend fun inspect(localUri: String): LocalMediaInfo? = null

    override suspend fun prepareUpload(localUri: String): PreparedMedia = queue.removeFirst()

    override suspend fun saveDownloaded(
        clientUuid: String,
        kind: String,
        bytes: ByteArray,
        mime: String?,
    ): String = error("download is outside this test")

    override suspend fun delete(localUri: String) = error("delete is outside this test")
}

private fun preparedMedia(
    bytes: ByteArray,
    mime: String,
    width: Int? = null,
    height: Int? = null,
): PreparedMedia {
    val file = File.createTempFile("prepared-media-test-", ".tmp").apply { writeBytes(bytes) }
    return PreparedMedia(file = file, mime = mime, width = width, height = height)
}
