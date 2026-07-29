package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReferenceAwareMediaFileCleanupTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sharedRecordAndPlanBytesAreDeletedOnlyAfterTheLastActiveReference() = runTest {
        val media = MemoryMediaDao()
        val files = RealTemporaryMediaFileStore()
        val cleanup = ReferenceAwareMediaFileCleanup(
            mediaDao = media,
            mediaFiles = files,
            transactionRunner = RecordingTransactionRunner(),
        )
        val shared = temporaryFolder.newFile("shared.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val recordUuid = "11111111-1111-3111-8111-111111111111"
        val planUuid = "22222222-2222-3222-8222-222222222222"
        media.seed(
            logMedia(
                clientUuid = recordUuid,
                localUri = shared.absolutePath,
                recordId = 7L,
                deletedAt = 200L,
            ),
        )
        media.seed(
            logMedia(
                clientUuid = planUuid,
                localUri = shared.absolutePath,
                carePlanId = 9L,
            ),
        )

        cleanup.cleanupTombstones(setOf(recordUuid))

        assertThat(shared.isFile).isTrue()
        assertThat(media.getByClientUuid(recordUuid)?.localUri).isEmpty()
        assertThat(media.getByClientUuid(recordUuid)?.deletedAt).isEqualTo(200L)
        assertThat(media.getByClientUuid(planUuid)?.localUri).isEqualTo(shared.absolutePath)

        val plan = requireNotNull(media.getByClientUuid(planUuid))
        media.update(plan.copy(deletedAt = 300L, updatedAt = 300L, syncDirty = true))

        cleanup.cleanupTombstones(setOf(planUuid))

        assertThat(shared.exists()).isFalse()
        assertThat(media.getByClientUuid(planUuid)?.localUri).isEmpty()
        assertThat(media.getByClientUuid(planUuid)?.deletedAt).isEqualTo(300L)
        assertThat(media.listAllIncludingDeleted().map(MediaAssetEntity::clientUuid))
            .containsExactly(recordUuid, planUuid)
    }

    @Test
    fun deleteFailureKeepsDurableMarkerAndARecreatedCleanupRetriesSafely() = runTest {
        val media = MemoryMediaDao()
        val files = RealTemporaryMediaFileStore()
        val shared = temporaryFolder.newFile("retry.jpg").apply {
            writeBytes(byteArrayOf(4, 5, 6))
        }
        val clientUuid = "33333333-3333-3333-8333-333333333333"
        media.seed(
            logMedia(
                clientUuid = clientUuid,
                localUri = shared.absolutePath,
                recordId = 7L,
                deletedAt = 200L,
            ),
        )
        files.deleteFailures += IllegalStateException("disk unavailable")
        val firstProcess = ReferenceAwareMediaFileCleanup(
            mediaDao = media,
            mediaFiles = files,
            transactionRunner = RecordingTransactionRunner(),
        )

        val failure = runCatching {
            firstProcess.cleanupTombstones(setOf(clientUuid))
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("disk unavailable")
        assertThat(shared.isFile).isTrue()
        assertThat(media.getByClientUuid(clientUuid)?.localUri).isEqualTo(shared.absolutePath)

        val recreated = ReferenceAwareMediaFileCleanup(
            mediaDao = media,
            mediaFiles = RealTemporaryMediaFileStore(),
            transactionRunner = RecordingTransactionRunner(),
        )
        recreated.cleanupTombstones(setOf(clientUuid))

        assertThat(shared.exists()).isFalse()
        assertThat(media.getByClientUuid(clientUuid)?.localUri).isEmpty()
        assertThat(media.getByClientUuid(clientUuid)?.deletedAt).isEqualTo(200L)
    }

    @Test
    fun pendingUploadProtectsSharedBytesAndMissingFilesConvergeIdempotently() = runTest {
        val media = MemoryMediaDao()
        val files = RealTemporaryMediaFileStore()
        val cleanup = ReferenceAwareMediaFileCleanup(
            mediaDao = media,
            mediaFiles = files,
            transactionRunner = RecordingTransactionRunner(),
        )
        val pending = temporaryFolder.newFile("pending.jpg")
        val tombstoneUuid = "44444444-4444-3444-8444-444444444444"
        media.seed(
            logMedia(
                clientUuid = tombstoneUuid,
                localUri = pending.absolutePath,
                recordId = 7L,
                deletedAt = 200L,
            ),
        )
        media.seed(
            logMedia(
                clientUuid = "55555555-5555-3555-8555-555555555555",
                localUri = pending.absolutePath,
                carePlanId = 9L,
            ).copy(syncDirty = true),
        )

        cleanup.cleanupTombstones(setOf(tombstoneUuid))

        assertThat(pending.isFile).isTrue()
        assertThat(files.deletedPaths).isEmpty()
        assertThat(media.getByClientUuid(tombstoneUuid)?.localUri).isEmpty()

        val missingPath = File(temporaryFolder.root, "already-missing.jpg").absolutePath
        val missingUuid = "66666666-6666-3666-8666-666666666666"
        media.seed(
            logMedia(
                clientUuid = missingUuid,
                localUri = missingPath,
                recordId = 8L,
                deletedAt = 300L,
            ),
        )

        cleanup.cleanupTombstones(setOf(missingUuid))

        assertThat(media.getByClientUuid(missingUuid)?.localUri).isEmpty()
        assertThat(media.getByClientUuid(missingUuid)?.deletedAt).isEqualTo(300L)
    }

    @Test
    fun cancellationAfterPhysicalDeleteLeavesAnIdempotentRetryHandoff() = runTest {
        val media = MemoryMediaDao()
        val files = RealTemporaryMediaFileStore()
        val photo = temporaryFolder.newFile("cancelled-after-delete.jpg")
        val clientUuid = "77777777-7777-3777-8777-777777777777"
        media.seed(
            logMedia(
                clientUuid = clientUuid,
                localUri = photo.absolutePath,
                recordId = 7L,
                deletedAt = 200L,
            ),
        )
        files.failureAfterDelete = CancellationException("process cancelled")
        val interrupted = ReferenceAwareMediaFileCleanup(
            mediaDao = media,
            mediaFiles = files,
            transactionRunner = RecordingTransactionRunner(),
        )

        val failure = runCatching {
            interrupted.cleanupTombstones(setOf(clientUuid))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(photo.exists()).isFalse()
        assertThat(media.getByClientUuid(clientUuid)?.localUri).isEqualTo(photo.absolutePath)

        ReferenceAwareMediaFileCleanup(
            mediaDao = media,
            mediaFiles = RealTemporaryMediaFileStore(),
            transactionRunner = RecordingTransactionRunner(),
        ).cleanupTombstones(setOf(clientUuid))

        assertThat(media.getByClientUuid(clientUuid)?.localUri).isEmpty()
    }

    private fun logMedia(
        clientUuid: String,
        localUri: String,
        recordId: Long? = null,
        carePlanId: Long? = null,
        deletedAt: Long? = null,
    ): MediaAssetEntity = MediaAssetEntity(
        recordId = recordId,
        carePlanId = carePlanId,
        clientUuid = clientUuid,
        kind = "log",
        localUri = localUri,
        createdAt = 100L,
        updatedAt = deletedAt ?: 100L,
        deletedAt = deletedAt,
        syncDirty = deletedAt != null,
    )
}

private class RealTemporaryMediaFileStore : SyncMediaFileStore {
    val deleteFailures = ArrayDeque<Throwable>()
    val deletedPaths = mutableListOf<String>()
    var failureAfterDelete: Throwable? = null

    override suspend fun inspect(localUri: String): LocalMediaInfo? = error("not used")

    override suspend fun prepareUpload(localUri: String): PreparedMedia = error("not used")

    override suspend fun saveDownloaded(
        clientUuid: String,
        kind: String,
        bytes: ByteArray,
        mime: String?,
    ): String = error("not used")

    override suspend fun delete(localUri: String) {
        deletedPaths += localUri
        deleteFailures.removeFirstOrNull()?.let { throw it }
        val file = File(localUri)
        check(!file.exists() || file.delete()) { "failed to delete $localUri" }
        failureAfterDelete?.let { throw it }
    }
}
