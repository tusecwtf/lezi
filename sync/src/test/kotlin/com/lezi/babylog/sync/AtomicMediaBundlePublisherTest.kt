package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.OutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AtomicMediaBundlePublisherTest {
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
                PreparedMedia(
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
                PreparedMedia(byteArrayOf(1, 2, 3), "image/jpeg", 40, 30),
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

    private companion object {
        val session = SyncSession(
            familyId = "family-1",
            familyToken = "token-1",
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
        bytes: ByteArray,
        mime: String?,
    ): BundleStageStatus {
        operations += "put:$clientUuid"
        putFailure?.let { throw it }
        uploads += Upload(bundleId, clientUuid, bytes, mime)
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
