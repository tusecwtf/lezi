package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.OutboxEntity
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AtomicMediaBundlePublisherTest {
    @Test
    fun zeroMediaStillStagesAndCommitsTheRootWithoutPreparingAFile() = runTest {
        val backend = RecordingAtomicBundleBackend()
        val publisher = AtomicMediaBundlePublisher(
            backend = backend,
            mediaFiles = QueueMediaFileStore(),
            loadMedia = { error("zero media must not load metadata") },
            updateMedia = { error("zero media must not update metadata") },
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
    fun publishPreparesManifestUploadsMissingMediaWritesReceiptThenCommits() = runTest {
        val backend = RecordingAtomicBundleBackend()
        val stored = mutableMapOf(
            "media-1" to mediaAsset(
                clientUuid = "media-1",
                mime = "image/png",
                width = 80,
                height = 60,
                byteSize = 99,
            ),
        )
        val publisher = AtomicMediaBundlePublisher(
            backend = backend,
            mediaFiles = StubMediaFileStore(
                preparedMedia(
                    bytes = byteArrayOf(1, 2, 3),
                    mime = "image/jpeg",
                    width = 40,
                    height = 30,
                ),
            ),
            loadMedia = stored::get,
            updateMedia = { stored[it.clientUuid] = it },
            requireRemoteAllowed = { backend.operations += "gate" },
        )

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
        assertThat(stored.getValue("media-1")).isEqualTo(
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
        val stored = mutableMapOf(
            "media-1" to mediaAsset(
                clientUuid = "media-1",
                mime = null,
                width = null,
                height = null,
                byteSize = 0,
            ),
        )
        val publisher = AtomicMediaBundlePublisher(
            backend = backend,
            mediaFiles = StubMediaFileStore(
                preparedMedia(byteArrayOf(1, 2, 3), "image/jpeg", 40, 30),
            ),
            loadMedia = stored::get,
            updateMedia = { stored[it.clientUuid] = it },
            requireRemoteAllowed = { backend.operations += "gate" },
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
        assertThat(stored.getValue("media-1").remoteUri).isNull()
        assertThat(stored.getValue("media-1").byteSize).isEqualTo(3)
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
        val stored = (1..3).associate { index ->
            "media-$index" to mediaAsset(
                clientUuid = "media-$index",
                mime = null,
                width = null,
                height = null,
                byteSize = 0,
            )
        }.toMutableMap()
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
            loadMedia = stored::get,
            updateMedia = { stored[it.clientUuid] = it },
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
        assertThat(stored.values.map { it.remoteUri }).containsExactly(null, null, null)
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
        assertThat(stored.values.map { it.remoteUri }).containsExactly(
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
        val stored = (1..3).associate { index ->
            "media-$index" to mediaAsset(
                clientUuid = "media-$index",
                mime = null,
                width = null,
                height = null,
                byteSize = 0,
            )
        }.toMutableMap()
        val prepared = (1..3).map { index ->
            preparedMedia(byteArrayOf(index.toByte()), "image/jpeg", 40, 30)
        }
        val publisher = AtomicMediaBundlePublisher(
            backend = backend,
            mediaFiles = QueueMediaFileStore(*prepared.toTypedArray()),
            loadMedia = stored::get,
            updateMedia = { stored[it.clientUuid] = it },
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
        assertThat(stored.values.map { it.remoteUri }).containsExactly(null, null, null)
        assertThat(prepared.map { it.file.exists() }).containsExactly(false, false, false)
    }

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
        ) = MediaAssetEntity(
            id = 1,
            recordId = 1,
            clientUuid = clientUuid,
            localUri = "/local/$clientUuid",
            remoteUri = remoteUri,
            mime = mime,
            width = width,
            height = height,
            byteSize = byteSize,
            createdAt = 1,
            updatedAt = 2,
        )

        fun mediaOutboxRow(clientUuid: String) = OutboxEntity(
            id = 2,
            familyId = "family-1",
            entityType = "media",
            clientUuid = clientUuid,
            payloadJson = "{\"kind\":\"log\"}",
            updatedAt = 2,
        )
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
