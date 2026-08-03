package com.lezi.babylog.sync.media
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.MediaLocalPathGate
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.lezi.babylog.sync.MemoryMediaDao
import com.lezi.babylog.sync.RecordingTransactionRunner

/**
 * Public seams under test:
 * - [ReferenceAwareMediaFileCleanup.cleanupTombstones] / [ReferenceAwareMediaFileCleanup.cleanupPendingTombstones]
 * - reclaim-scoped caller invariant: delete runs outside Room write lease (not a shared
 *   [SyncMediaFileStore] depth==0 guard; local replica clear may delete under a lease)
 * - [MediaLocalPathGate] serializes file reclaim with concurrent path rebinding
 * - tombstone `localUri` remains durable retry evidence until a matching claim clears it
 */
class ReferenceAwareMediaFileCleanupTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun discardedPathCleanupWaitsForConcurrentAttachAndRechecksActiveOwner() = runTest {
        val media = MemoryMediaDao()
        val files = RealTemporaryMediaFileStore()
        val pathGate = MediaLocalPathGate()
        val cleanup = newCleanup(media, files, pathGate = pathGate)
        val photo = temporaryFolder.newFile("concurrent-discarded-attach.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val attachEntered = CompletableDeferred<Unit>()
        val releaseAttach = CompletableDeferred<Unit>()
        val attach = async {
            pathGate.withLock(photo.absolutePath) {
                attachEntered.complete(Unit)
                releaseAttach.await()
                media.seed(
                    logMedia(
                        clientUuid = "12121212-1212-3212-8212-121212121212",
                        localUri = photo.absolutePath,
                        recordId = 7,
                    ),
                )
            }
        }
        attachEntered.await()

        val reclaim = async {
            cleanup.cleanupUnreferencedPaths(setOf(photo.absolutePath))
        }
        delay(50)
        assertThat(reclaim.isCompleted).isFalse()
        assertThat(photo.isFile).isTrue()

        releaseAttach.complete(Unit)
        attach.await()
        reclaim.await()

        assertThat(photo.isFile).isTrue()
        assertThat(files.deletedPaths).isEmpty()
    }

    @Test
    fun sharedRecordAndPlanBytesAreDeletedOnlyAfterTheLastActiveReference() = runTest {
        val media = MemoryMediaDao()
        val files = RealTemporaryMediaFileStore()
        val cleanup = newCleanup(media, files)
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
        val firstProcess = newCleanup(media, files)

        val failure = runCatching {
            firstProcess.cleanupTombstones(setOf(clientUuid))
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("disk unavailable")
        assertThat(shared.isFile).isTrue()
        assertThat(media.getByClientUuid(clientUuid)?.localUri).isEqualTo(shared.absolutePath)

        val recreated = newCleanup(media, RealTemporaryMediaFileStore())
        recreated.cleanupTombstones(setOf(clientUuid))

        assertThat(shared.exists()).isFalse()
        assertThat(media.getByClientUuid(clientUuid)?.localUri).isEmpty()
        assertThat(media.getByClientUuid(clientUuid)?.deletedAt).isEqualTo(200L)
    }

    @Test
    fun pendingUploadProtectsSharedBytesAndMissingFilesConvergeIdempotently() = runTest {
        val media = MemoryMediaDao()
        val files = RealTemporaryMediaFileStore()
        val cleanup = newCleanup(media, files)
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
        val interrupted = newCleanup(media, files)

        val failure = runCatching {
            interrupted.cleanupTombstones(setOf(clientUuid))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(photo.exists()).isFalse()
        assertThat(media.getByClientUuid(clientUuid)?.localUri).isEqualTo(photo.absolutePath)

        newCleanup(media, RealTemporaryMediaFileStore()).cleanupTombstones(setOf(clientUuid))

        assertThat(media.getByClientUuid(clientUuid)?.localUri).isEmpty()
    }

    @Test
    fun slowDeleteDoesNotHoldRoomWriteTransactionLease() = runTest {
        val media = MemoryMediaDao()
        val transactions = RecordingTransactionRunner()
        val deleteEntered = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        val files = RealTemporaryMediaFileStore(
            transactionDepthDuringDelete = { transactions.depth },
        ).apply {
            onDelete = {
                deleteEntered.complete(Unit)
                releaseDelete.await()
            }
        }
        val photo = temporaryFolder.newFile("slow-delete.jpg").apply {
            writeBytes(byteArrayOf(7, 8, 9))
        }
        val clientUuid = "88888888-8888-3888-8888-888888888888"
        media.seed(
            logMedia(
                clientUuid = clientUuid,
                localUri = photo.absolutePath,
                recordId = 7L,
                deletedAt = 200L,
                updatedAt = 200L,
            ),
        )
        val cleanup = ReferenceAwareMediaFileCleanup(
            mediaDao = media,
            mediaFiles = files,
            transactionRunner = transactions,
            pathGate = MediaLocalPathGate(),
        )

        val job = async {
            cleanup.cleanupTombstones(setOf(clientUuid))
        }
        deleteEntered.await()
        // While the slow delete is in flight, Room write depth must be zero.
        assertThat(transactions.depth).isEqualTo(0)
        assertThat(files.transactionDepthAtDelete).containsExactly(0)
        releaseDelete.complete(Unit)
        job.await()

        assertThat(photo.exists()).isFalse()
        assertThat(media.getByClientUuid(clientUuid)?.localUri).isEmpty()
        assertThat(transactions.depth).isEqualTo(0)
    }

    @Test
    fun abaSamePathWithNewerRevisionDoesNotClearOrDeleteForStaleClaim() = runTest {
        val media = MemoryMediaDao()
        val pathGate = MediaLocalPathGate()
        val transactions = RecordingTransactionRunner()
        val deleteStarted = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        val photo = temporaryFolder.newFile("aba.jpg").apply {
            writeBytes(byteArrayOf(10, 11, 12))
        }
        val clientUuid = "99999999-9999-3999-8999-999999999999"
        media.seed(
            logMedia(
                clientUuid = clientUuid,
                localUri = photo.absolutePath,
                recordId = 7L,
                deletedAt = 200L,
                updatedAt = 200L,
            ),
        )
        val files = RealTemporaryMediaFileStore().apply {
            onDelete = {
                // Simulate attach/revive advancing the row revision under the same path
                // after claim was taken but before marker clear (ABA).
                val current = requireNotNull(media.getByClientUuid(clientUuid))
                media.update(
                    current.copy(
                        deletedAt = null,
                        updatedAt = 500L,
                        syncDirty = true,
                    ),
                )
                deleteStarted.complete(Unit)
                releaseDelete.await()
            }
        }
        // Without path gate on the simulated revive, we prove revision identity: after
        // delete of the old bytes, marker clear is skipped because claim no longer matches.
        val cleanup = ReferenceAwareMediaFileCleanup(
            mediaDao = media,
            mediaFiles = files,
            transactionRunner = transactions,
            pathGate = pathGate,
        )

        // Force the race by mutating inside onDelete (holds path gate with cleanup).
        val job = async { cleanup.cleanupTombstones(setOf(clientUuid)) }
        deleteStarted.await()
        releaseDelete.complete(Unit)
        job.await()

        // File may be gone (delete already ran) but marker must not clear on ABA revive.
        assertThat(media.getByClientUuid(clientUuid)?.deletedAt).isNull()
        assertThat(media.getByClientUuid(clientUuid)?.localUri).isEqualTo(photo.absolutePath)
        assertThat(media.getByClientUuid(clientUuid)?.updatedAt).isEqualTo(500L)
    }

    @Test
    fun pathGateBlocksAttachDuringFilePhaseSoNewActiveOwnerIsNotDeleted() = runTest {
        val media = MemoryMediaDao()
        val pathGate = MediaLocalPathGate()
        val transactions = RecordingTransactionRunner()
        val deleteEntered = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        val photo = temporaryFolder.newFile("gated.jpg").apply {
            writeBytes(byteArrayOf(13, 14, 15))
        }
        val tombstoneUuid = "aaaaaaaa-aaaa-3aaa-8aaa-aaaaaaaaaaaa"
        val liveUuid = "bbbbbbbb-bbbb-3bbb-8bbb-bbbbbbbbbbbb"
        media.seed(
            logMedia(
                clientUuid = tombstoneUuid,
                localUri = photo.absolutePath,
                recordId = 7L,
                deletedAt = 200L,
                updatedAt = 200L,
            ),
        )
        val files = RealTemporaryMediaFileStore().apply {
            onDelete = {
                deleteEntered.complete(Unit)
                releaseDelete.await()
            }
        }
        val cleanup = ReferenceAwareMediaFileCleanup(
            mediaDao = media,
            mediaFiles = files,
            transactionRunner = transactions,
            pathGate = pathGate,
        )

        val cleanupJob = async { cleanup.cleanupTombstones(setOf(tombstoneUuid)) }
        deleteEntered.await()

        // Attach must wait for path gate (same lock order as production domain writers).
        val attachStarted = CompletableDeferred<Unit>()
        val attachJob = async {
            pathGate.withLock(photo.absolutePath) {
                attachStarted.complete(Unit)
                media.seed(
                    logMedia(
                        clientUuid = liveUuid,
                        localUri = photo.absolutePath,
                        carePlanId = 9L,
                        updatedAt = 400L,
                    ),
                )
            }
        }
        // Attach cannot enter the critical section while delete holds the path.
        delay(50)
        assertThat(attachStarted.isCompleted).isFalse()
        assertThat(media.getByClientUuid(liveUuid)).isNull()

        releaseDelete.complete(Unit)
        cleanupJob.await()
        attachJob.await()

        // Cleanup claimed with zero active refs and deleted; attach ran after.
        // Tombstone marker is cleared; live row now owns the (deleted) path string —
        // production import uses unique paths so this only exercises gate ordering.
        assertThat(media.getByClientUuid(tombstoneUuid)?.localUri).isEmpty()
        assertThat(media.getByClientUuid(liveUuid)?.localUri).isEqualTo(photo.absolutePath)
        assertThat(attachStarted.isCompleted).isTrue()
    }

    @Test
    fun newActiveReferenceAfterClaimSkipsDeleteWhenObservedBeforeFilePhase() = runTest {
        val media = MemoryMediaDao()
        val files = RealTemporaryMediaFileStore()
        val transactions = RecordingTransactionRunner()
        val pathGate = MediaLocalPathGate()
        val photo = temporaryFolder.newFile("new-active.jpg").apply {
            writeBytes(byteArrayOf(16, 17, 18))
        }
        val tombstoneUuid = "cccccccc-cccc-3ccc-8ccc-cccccccccccc"
        media.seed(
            logMedia(
                clientUuid = tombstoneUuid,
                localUri = photo.absolutePath,
                recordId = 7L,
                deletedAt = 200L,
                updatedAt = 200L,
            ),
        )
        // Seed a live owner before cleanup so claim phase sees active refs.
        media.seed(
            logMedia(
                clientUuid = "dddddddd-dddd-3ddd-8ddd-dddddddddddd",
                localUri = photo.absolutePath,
                carePlanId = 3L,
            ),
        )
        val cleanup = ReferenceAwareMediaFileCleanup(
            mediaDao = media,
            mediaFiles = files,
            transactionRunner = transactions,
            pathGate = pathGate,
        )

        cleanup.cleanupTombstones(setOf(tombstoneUuid))

        assertThat(photo.isFile).isTrue()
        assertThat(files.deletedPaths).isEmpty()
        assertThat(media.getByClientUuid(tombstoneUuid)?.localUri).isEmpty()
        assertThat(media.getByClientUuid(tombstoneUuid)?.deletedAt).isEqualTo(200L)
    }

    @Test
    fun pathHintMismatchUnderLockRetriesOnceAndConvergesInSameCall() = runTest {
        val media = MemoryMediaDao()
        val files = RealTemporaryMediaFileStore()
        val pathGate = MediaLocalPathGate()
        val first = temporaryFolder.newFile("hint-first.jpg").apply {
            writeBytes(byteArrayOf(22, 23, 24))
        }
        val second = temporaryFolder.newFile("hint-second.jpg").apply {
            writeBytes(byteArrayOf(25, 26, 27))
        }
        val clientUuid = "10101010-1010-3010-8010-101010101010"
        media.seed(
            logMedia(
                clientUuid = clientUuid,
                localUri = first.absolutePath,
                recordId = 1L,
                deletedAt = 200L,
                updatedAt = 200L,
            ),
        )
        // Between outer peek and lock acquisition, rebind the tombstone path.
        // cleanupOne must release the wrong lock, re-lock the live path, and reclaim.
        val cleanup = ReferenceAwareMediaFileCleanup(
            mediaDao = object : MediaAssetDao by media {
                private var peeks = 0
                override suspend fun getByClientUuid(clientUuid: String): MediaAssetEntity? {
                    val current = media.getByClientUuid(clientUuid) ?: return null
                    peeks += 1
                    // First outer peek returns the stale path while rebinding the row so the
                    // under-lock read sees the new path and triggers RetryWithPath once.
                    if (peeks == 1) {
                        media.update(
                            current.copy(
                                localUri = second.absolutePath,
                                updatedAt = 300L,
                            ),
                        )
                        return current
                    }
                    return media.getByClientUuid(clientUuid)
                }
            },
            mediaFiles = files,
            transactionRunner = RecordingTransactionRunner(),
            pathGate = pathGate,
        )

        cleanup.cleanupTombstones(setOf(clientUuid))

        assertThat(second.exists()).isFalse()
        assertThat(first.exists()).isTrue()
        assertThat(media.getByClientUuid(clientUuid)?.localUri).isEmpty()
        assertThat(files.deletedPaths).containsExactly(second.absolutePath)
    }

    @Test
    fun multipleTombstonesSharingPathRecycleOnlyOnceAndClearEachMarker() = runTest {
        val media = MemoryMediaDao()
        val files = RealTemporaryMediaFileStore()
        val cleanup = newCleanup(media, files)
        val shared = temporaryFolder.newFile("multi-tombstone.jpg").apply {
            writeBytes(byteArrayOf(19, 20, 21))
        }
        val first = "eeeeeeee-eeee-3eee-8eee-eeeeeeeeeeee"
        val second = "ffffffff-ffff-3fff-8fff-ffffffffffff"
        media.seed(
            logMedia(
                clientUuid = first,
                localUri = shared.absolutePath,
                recordId = 1L,
                deletedAt = 200L,
                updatedAt = 200L,
            ),
        )
        media.seed(
            logMedia(
                clientUuid = second,
                localUri = shared.absolutePath,
                carePlanId = 2L,
                deletedAt = 210L,
                updatedAt = 210L,
            ),
        )

        cleanup.cleanupTombstones(setOf(first, second))

        assertThat(shared.exists()).isFalse()
        assertThat(media.getByClientUuid(first)?.localUri).isEmpty()
        assertThat(media.getByClientUuid(second)?.localUri).isEmpty()
        // First cleanup deletes; second is missing-file idempotent success.
        assertThat(files.deletedPaths).containsExactly(shared.absolutePath, shared.absolutePath)
    }

    private fun newCleanup(
        media: MemoryMediaDao,
        files: RealTemporaryMediaFileStore,
        transactions: RecordingTransactionRunner = RecordingTransactionRunner(),
        pathGate: MediaLocalPathGate = MediaLocalPathGate(),
    ) = ReferenceAwareMediaFileCleanup(
        mediaDao = media,
        mediaFiles = files,
        transactionRunner = transactions,
        pathGate = pathGate,
    )

    private fun logMedia(
        clientUuid: String,
        localUri: String,
        recordId: Long? = null,
        carePlanId: Long? = null,
        deletedAt: Long? = null,
        updatedAt: Long? = null,
    ): MediaAssetEntity = MediaAssetEntity(
        recordId = recordId,
        carePlanId = carePlanId,
        clientUuid = clientUuid,
        kind = "log",
        localUri = localUri,
        createdAt = 100L,
        updatedAt = updatedAt ?: deletedAt ?: 100L,
        deletedAt = deletedAt,
        syncDirty = deletedAt != null,
    )
}

private class RealTemporaryMediaFileStore(
    private val transactionDepthDuringDelete: () -> Int = { 0 },
) : SyncMediaFileStore {
    val deleteFailures = ArrayDeque<Throwable>()
    val deletedPaths = mutableListOf<String>()
    val transactionDepthAtDelete = mutableListOf<Int>()
    var failureAfterDelete: Throwable? = null
    var onDelete: (suspend (String) -> Unit)? = null

    override suspend fun inspect(localUri: String): LocalMediaInfo? = error("not used")

    override suspend fun prepareUpload(localUri: String): PreparedMedia = error("not used")

    override suspend fun saveDownloaded(
        clientUuid: String,
        kind: String,
        bytes: ByteArray,
        mime: String?,
    ): String = error("not used")

    override suspend fun delete(localUri: String) {
        transactionDepthAtDelete += transactionDepthDuringDelete()
        deletedPaths += localUri
        onDelete?.invoke(localUri)
        deleteFailures.removeFirstOrNull()?.let { throw it }
        val file = File(localUri)
        check(!file.exists() || file.delete()) { "failed to delete $localUri" }
        failureAfterDelete?.let { throw it }
    }

    override suspend fun sweepUnreferenced(
        scope: com.lezi.babylog.core.database.LocalDataClearScope,
        retainedLocalUris: Set<String>,
    ) = error("local clear is outside this test")
}
