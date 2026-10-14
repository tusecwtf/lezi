package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.MediaContentDigest
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.RecordingSyncBackend
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.TestMediaFileStore
import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.backend.MediaDownloadReceipt
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.withMediaDownloadWriteErrors
import com.lezi.babylog.sync.media.LocalMediaInfo
import com.lezi.babylog.sync.media.MediaDownloadOwnership
import com.lezi.babylog.sync.media.OwnedMediaDownloadStore
import com.lezi.babylog.sync.media.SyncMediaDownloadTarget
import com.lezi.babylog.sync.media.deleteExistingSyncMediaFile
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.receiptFor
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Production owned store + production engine; buffered legacy entry points deliberately fail. */
class ReplicaSyncEngineOwnedMediaStreamingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val session get() = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 4)
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private val replacementBytes = byteArrayOf(8, 6, 7, 5)

    @Test fun streamedPagePublishesOnlyAfterValidationAndReleasesDurableMarkers() = runTest {
        val files = OwnedFiles(temporary.root)
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        backend.stream = { _, output ->
            assertThat(rig.records.getByClientUuid(RECORD)).isNull()
            assertThat(rig.media.getByClientUuid(MEDIA_A)).isNull()
            output.write(bytes)
            receipt()
        }
        rig.engine.applyInitialEntities(session, page())
        val row = requireNotNull(rig.media.getByClientUuid(MEDIA_A))
        assertThat(File(row.localUri).readBytes()).isEqualTo(bytes)
        assertThat(row.sha256).isEqualTo(MediaContentDigest.ofBytes(bytes))
        assertThat(row.byteSize).isEqualTo(4)
        assertThat(files.pendingDownloads()).isEmpty()
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun laterRemoteRevisionReclaimsPreviousUnsharedOwnedFileAfterPageCommit() = runTest {
        val files = OwnedFiles(temporary.root)
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        rig.engine.applyInitialEntities(session, page())
        val old = requireNotNull(rig.media.getByClientUuid(MEDIA_A))
        val oldFile = File(old.localUri)
        backend.serveReplacement()
        files.beforeDelete = {
            assertThat(rig.transactions.depth).isEqualTo(0)
            assertThat(rig.media.getByClientUuid(MEDIA_A)?.localUri).isNotEqualTo(old.localUri)
        }

        rig.engine.applyInitialEntities(session, replacementPage())

        val current = requireNotNull(rig.media.getByClientUuid(MEDIA_A))
        assertThat(current.localUri).isNotEqualTo(old.localUri)
        assertThat(File(current.localUri).readBytes()).isEqualTo(replacementBytes)
        assertThat(current.sha256).isEqualTo(MediaContentDigest.ofBytes(replacementBytes))
        assertThat(oldFile.exists()).isFalse()
        assertThat(oldFile.parentFile!!.exists()).isFalse()
        assertThat(mediaFiles()).hasSize(1)
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun committedReplacementDeleteFailureRetainsOldMarkerForLaterRecovery() = runTest {
        val files = OwnedFiles(temporary.root)
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        rig.engine.applyInitialEntities(session, page())
        val old = requireNotNull(rig.media.getByClientUuid(MEDIA_A))
        backend.serveReplacement()
        files.beforeRetain = { assertThat(rig.transactions.depth).isEqualTo(0) }
        val expected = IOException("synthetic old-file delete failure")
        files.beforeDelete = { throw expected }

        assertThat(runCatching {
            rig.engine.applyInitialEntities(session, replacementPage())
        }.exceptionOrNull()).isSameInstanceAs(expected)
        val current = requireNotNull(rig.media.getByClientUuid(MEDIA_A))
        assertThat(current.localUri).isNotEqualTo(old.localUri)
        assertThat(File(old.localUri).readBytes()).isEqualTo(bytes)
        assertThat(ownerMarkers()).hasSize(1)
        files.beforeDelete = null

        // Recovery works without receiving the replaced entity again.
        rig.engine.applyInitialEntities(session, emptyList())
        assertThat(File(old.localUri).exists()).isFalse()
        assertThat(File(current.localUri).readBytes()).isEqualTo(replacementBytes)
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun newOwnershipReleaseFailureCannotStrandCommittedPreviousFile() = runTest {
        val files = OwnedFiles(temporary.root)
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        rig.engine.applyInitialEntities(session, page())
        val old = requireNotNull(rig.media.getByClientUuid(MEDIA_A))
        backend.serveReplacement()
        files.failNewRelease = true

        assertThat(runCatching {
            rig.engine.applyInitialEntities(session, replacementPage())
        }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        val current = requireNotNull(rig.media.getByClientUuid(MEDIA_A))
        assertThat(current.localUri).isNotEqualTo(old.localUri)
        assertThat(File(old.localUri).exists()).isFalse()
        assertThat(ownerMarkers()).hasSize(1)
        files.failNewRelease = false
        rig.engine.applyInitialEntities(session, emptyList())
        assertThat(File(current.localUri).readBytes()).isEqualTo(replacementBytes)
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun cancellationAfterRetirementRegistrationKeepsCurrentFileAndReleasesBothLeases() = runTest {
        val files = OwnedFiles(temporary.root)
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        rig.engine.applyInitialEntities(session, page())
        val old = requireNotNull(rig.media.getByClientUuid(MEDIA_A))
        backend.serveReplacement()
        files.cancelAfterRetain = true
        val job = launch { rig.engine.applyInitialEntities(session, replacementPage()) }
        job.join()
        assertThat(job.isCancelled).isTrue()
        assertThat(rig.media.getByClientUuid(MEDIA_A)).isEqualTo(old)
        assertThat(File(old.localUri).readBytes()).isEqualTo(bytes)
        assertThat(mediaFiles()).hasSize(1)
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun replacementKeepsPreviousOwnedBytesWhileAnotherActiveAssetSharesThem() = runTest {
        val files = OwnedFiles(temporary.root)
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        rig.engine.applyInitialEntities(session, page())
        val old = requireNotNull(rig.media.getByClientUuid(MEDIA_A))
        rig.media.seed(old.copy(id = 0, clientUuid = MEDIA_B))
        backend.serveReplacement()

        rig.engine.applyInitialEntities(session, replacementPage())

        assertThat(rig.media.getByClientUuid(MEDIA_A)?.localUri).isNotEqualTo(old.localUri)
        assertThat(rig.media.getByClientUuid(MEDIA_B)?.localUri).isEqualTo(old.localUri)
        assertThat(File(old.localUri).readBytes()).isEqualTo(bytes)
        assertThat(mediaFiles()).hasSize(2)
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun replacingTwoAssetsSharingOneOldPathArmsItOnlyOnce() = runTest {
        val files = OwnedFiles(temporary.root)
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        rig.engine.applyInitialEntities(session, page())
        val old = requireNotNull(rig.media.getByClientUuid(MEDIA_A))
        rig.media.seed(old.copy(id = 0, clientUuid = MEDIA_B))
        backend.serveReplacement()
        var armed = 0
        files.beforeRetain = { armed += 1 }

        rig.engine.applyInitialEntities(session, page(twoMedia = true).map { entity ->
            if (entity.type == "media") entity.copy(updatedAt = 211) else entity
        })

        assertThat(armed).isEqualTo(1)
        assertThat(File(old.localUri).exists()).isFalse()
        listOf(MEDIA_A, MEDIA_B).forEach { uuid ->
            val current = requireNotNull(rig.media.getByClientUuid(uuid))
            assertThat(File(current.localUri).readBytes()).isEqualTo(replacementBytes)
        }
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun replacementKeepsPreviousOwnedBytesWhileACausalHolderReferencesThem() = runTest {
        val files = OwnedFiles(temporary.root)
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        rig.engine.applyInitialEntities(session, page())
        val old = requireNotNull(rig.media.getByClientUuid(MEDIA_A))
        rig.mediaReferences.upsert(com.lezi.babylog.core.database.causal.MediaReferenceEntity(
            mediaUuid = MEDIA_A,
            holderKind = com.lezi.babylog.core.database.causal.MediaReferenceHolderKind.CONFLICT_BRANCH,
            holderId = "synthetic-retained-branch",
            localUri = old.localUri,
            createdAt = 1,
        ))
        backend.serveReplacement()

        rig.engine.applyInitialEntities(session, replacementPage())

        assertThat(rig.media.getByClientUuid(MEDIA_A)?.localUri).isNotEqualTo(old.localUri)
        assertThat(File(old.localUri).readBytes()).isEqualTo(bytes)
        assertThat(mediaFiles()).hasSize(2)
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun pageTransactionFailureKeepsPreviousOwnedFileAndReclaimsNewStaging() = runTest {
        val files = OwnedFiles(temporary.root)
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        rig.engine.applyInitialEntities(session, page())
        val old = requireNotNull(rig.media.getByClientUuid(MEDIA_A))
        val expected = IOException("synthetic page transaction failure")
        backend.stream = { _, output ->
            output.write(replacementBytes)
            // The next transaction is page apply, after the real owned file completes.
            rig.transactions.failBeforeNextBlock = expected
            replacementReceipt()
        }

        val actual = runCatching {
            rig.engine.applyInitialEntities(session, replacementPage())
        }.exceptionOrNull()

        assertThat(actual).isSameInstanceAs(expected)
        assertThat(rig.media.getByClientUuid(MEDIA_A)).isEqualTo(old)
        assertThat(File(old.localUri).readBytes()).isEqualTo(bytes)
        assertThat(mediaFiles()).hasSize(1)
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun newerLocalRevisionRejectsReplacementAndKeepsPreviousOwnedFile() = runTest {
        val files = OwnedFiles(temporary.root)
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        rig.engine.applyInitialEntities(session, page())
        val old = requireNotNull(rig.media.getByClientUuid(MEDIA_A))
        val newerLocal = old.copy(updatedAt = 300, syncDirty = true)
        backend.stream = { _, output ->
            output.write(replacementBytes)
            rig.media.update(newerLocal)
            replacementReceipt()
        }

        rig.engine.applyInitialEntities(session, replacementPage())

        assertThat(rig.media.getByClientUuid(MEDIA_A)).isEqualTo(newerLocal)
        assertThat(File(old.localUri).readBytes()).isEqualTo(bytes)
        assertThat(mediaFiles()).hasSize(1)
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun equalRemoteRevisionKeepsTheSameOwnedFileWithoutASecondDownload() = runTest {
        val files = OwnedFiles(temporary.root)
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        rig.engine.applyInitialEntities(session, page())
        val old = requireNotNull(rig.media.getByClientUuid(MEDIA_A))

        rig.engine.applyInitialEntities(session, page())

        assertThat(rig.media.getByClientUuid(MEDIA_A)?.localUri).isEqualTo(old.localUri)
        assertThat(File(old.localUri).readBytes()).isEqualTo(bytes)
        assertThat(backend.mediaGets).containsExactly(MEDIA_A)
        assertThat(mediaFiles()).hasSize(1)
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun parallelFailureJoinsWritersThenReclaimsEveryNewFileWithoutAdvancingCursor() = runTest {
        val files = OwnedFiles(temporary.root)
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        val firstWriting = CompletableDeferred<Unit>()
        var writerExited = false
        val expected = IOException("synthetic second response failed")
        backend.stream = { uuid, output ->
            if (uuid == MEDIA_A) {
                try {
                    output.write(bytes)
                    firstWriting.complete(Unit)
                    awaitCancellation()
                } finally {
                    writerExited = true
                }
            } else {
                firstWriting.await()
                throw expected
            }
        }
        files.beforeDelete = { assertThat(writerExited).isTrue() }
        val old = temporary.newFolder("record-media").resolve("old-shared.jpg").apply { writeBytes(byteArrayOf(9)) }
        backend.nextPull = PullResult(page(twoMedia = true), 5, session.pullGeneration, false)
        val failure = runCatching { rig.engine.synchronize(session, SyncTrigger.Foreground) }.exceptionOrNull()
        assertThat(failure).isSameInstanceAs(expected)
        assertThat(writerExited).isTrue()
        assertThat(rig.records.getByClientUuid(RECORD)).isNull()
        assertThat(rig.media.getByClientUuid(MEDIA_A)).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(4)
        assertThat(mediaFiles()).isEmpty()
        assertThat(ownerMarkers()).isEmpty()
        assertThat(old.readBytes()).isEqualTo(byteArrayOf(9))
    }

    @Test fun cancellationDuringDownloadClosesAndReclaimsBeforeReturning() = runTest {
        val files = OwnedFiles(temporary.root)
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        val started = CompletableDeferred<Unit>()
        var writerExited = false
        backend.stream = { _, output ->
            try {
                output.write(bytes)
                started.complete(Unit)
                awaitCancellation()
            } finally { writerExited = true }
        }
        files.beforeDelete = { assertThat(writerExited).isTrue() }
        val job = launch { rig.engine.applyInitialEntities(session, page()) }
        started.await()
        job.cancelAndJoin()
        assertThat(writerExited).isTrue()
        assertThat(mediaFiles()).isEmpty()
        assertThat(ownerMarkers()).isEmpty()
        assertThat(rig.records.getByClientUuid(RECORD)).isNull()
    }

    @Test fun cancellationAtDispatcherHandoffStillReclaimsRegisteredTarget() = runTest {
        val files = OwnedFiles(temporary.root).apply { cancelAfterRegistration = true }
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        val job = launch { rig.engine.applyInitialEntities(session, page()) }
        job.join()
        assertThat(job.isCancelled).isTrue()
        assertThat(backend.mediaGets).isEmpty()
        assertThat(mediaFiles()).isEmpty()
        assertThat(ownerMarkers()).isEmpty()
        assertThat(files.pendingDownloads()).isEmpty()
    }

    @Test fun initialJoinRecoveryKeepsAdoptedBytesAndReclaimsOnlyUnadoptedNewFiles() = runTest {
        val oldStore = OwnedMediaDownloadStore(temporary.root)
        val adopted = oldStore.begin(MEDIA_A, "log", "image/jpeg")
        adopted.output.write(bytes)
        val adoptedUri = adopted.complete(receipt())
        val orphan = oldStore.begin(MEDIA_B, "log", "image/jpeg")
        orphan.output.write(bytes)
        val orphanUri = orphan.complete(receipt())
        // A fresh store has no process-local active leases, exactly as after restart.
        val files = OwnedFiles(temporary.root)
        val rig = newRig(files, StreamingBackend())
        seedHistorical(rig, MEDIA_A, adoptedUri)
        rig.engine.applyInitialEntities(session, emptyList())
        assertThat(File(adoptedUri).readBytes()).isEqualTo(bytes)
        assertThat(File(orphanUri).exists()).isFalse()
        assertThat(rig.media.getByClientUuid(MEDIA_A)?.localUri).isEqualTo(adoptedUri)
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun localMidStreamWriteFailureKeepsHistoricalRetryJournalAndNoPartialFile() = runTest {
        val files = OwnedFiles(temporary.root).apply { failWrites = true }
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        seedHistorical(rig, MEDIA_A)
        rig.engine.synchronize(session, SyncTrigger.Foreground)
        assertThat(rig.media.getByClientUuid(MEDIA_A)?.localUri).isEmpty()
        assertThat(rig.conflictDetails.getTransportJournal("media-save-failed:$MEDIA_A")).isNotNull()
        assertThat(mediaFiles()).isEmpty()
        assertThat(ownerMarkers()).isEmpty()
        files.failWrites = false
        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
        val adopted = requireNotNull(rig.media.getByClientUuid(MEDIA_A))
        assertThat(File(adopted.localUri).readBytes()).isEqualTo(bytes)
        assertThat(rig.conflictDetails.getTransportJournal("media-save-failed:$MEDIA_A")).isNull()
    }

    @Test fun pendingCleanupFailureDoesNotBlockMediaFreeBootstrapAndIsRetriedBeforeDownload() = runTest {
        val oldStore = OwnedMediaDownloadStore(temporary.root)
        oldStore.begin(MEDIA_B, "log", "image/jpeg").apply { output.write(bytes); close() }
        val files = OwnedFiles(temporary.root).apply { beforeDelete = { throw IOException("synthetic cleanup failure") } }
        val rig = newRig(files, StreamingBackend())
        rig.engine.applyInitialEntities(session, emptyList())
        assertThat(ownerMarkers()).hasSize(1)
        val failure = runCatching { rig.engine.applyInitialEntities(session, page()) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(rig.records.getByClientUuid(RECORD)).isNull()
        files.beforeDelete = null
        rig.engine.applyInitialEntities(session, page())
        assertThat(rig.records.getByClientUuid(RECORD)).isNotNull()
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun fileDisappearingAfterReuseScanCannotBePublishedByPageApply() = runTest {
        val files = OwnedFiles(temporary.root)
        val old = temporary.newFolder("record-media").resolve("donor.jpg").apply { writeBytes(bytes) }
        val rig = newRig(files, StreamingBackend())
        seedHistorical(rig, MEDIA_A, old.path)
        files.afterReadable = { file -> file.delete() }
        val failure = runCatching { rig.engine.applyInitialEntities(session, page()) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(rig.records.getByClientUuid(RECORD)).isNull()
        assertThat(rig.backend.mediaGets).isEmpty()
    }

    @Test fun manifestMismatchReclaimsOwnedDownloadAndLeavesPageCursorUnchanged() = runTest {
        val files = OwnedFiles(temporary.root)
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord(RECORD).copy(media = listOf(identity(MEDIA_A).copy(sha256 = "0".repeat(64)))),
                remoteReplicaMedia(MEDIA_A, RECORD),
            ), cursor = 5, generation = session.pullGeneration, hasMore = false,
        )
        val failure = runCatching { rig.engine.synchronize(session, SyncTrigger.Foreground) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(rig.records.getByClientUuid(RECORD)).isNull()
        assertThat(rig.media.getByClientUuid(MEDIA_A)).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(4)
        assertThat(mediaFiles()).isEmpty()
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun duplicateManifestContentUsesOneOwnedFileAndBothReferencesSurviveRelease() = runTest {
        val files = OwnedFiles(temporary.root)
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        rig.engine.applyInitialEntities(session, listOf(
            remoteReplicaRecord(RECORD).copy(media = listOf(identity(MEDIA_A), identity(MEDIA_B))),
            remoteReplicaMedia(MEDIA_A, RECORD), remoteReplicaMedia(MEDIA_B, RECORD),
        ))
        val first = requireNotNull(rig.media.getByClientUuid(MEDIA_A))
        val second = requireNotNull(rig.media.getByClientUuid(MEDIA_B))
        assertThat(backend.mediaGets).containsExactly(MEDIA_A)
        assertThat(first.localUri).isEqualTo(second.localUri)
        assertThat(File(first.localUri).readBytes()).isEqualTo(bytes)
        assertThat(mediaFiles()).hasSize(1)
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun cleanupFailureIsSuppressedOnOriginalDownloadErrorAndRecoveredAtNextEntry() = runTest {
        val files = OwnedFiles(temporary.root)
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        val primary = IOException("synthetic response interrupted")
        val cleanup = IOException("synthetic reclaim failure")
        val secondWriting = CompletableDeferred<Unit>()
        backend.stream = { uuid, output ->
            output.write(bytes)
            if (uuid == MEDIA_A) {
                secondWriting.await()
                throw primary
            } else {
                secondWriting.complete(Unit)
                awaitCancellation()
            }
        }
        files.beforeDelete = { throw cleanup }
        val failure = runCatching { rig.engine.applyInitialEntities(session, page(twoMedia = true)) }.exceptionOrNull()
        assertThat(failure).isSameInstanceAs(primary)
        assertThat(primary.suppressed.toList()).containsExactly(cleanup)
        assertThat(ownerMarkers()).hasSize(2)
        assertThat(files.pendingDownloads()).hasSize(2)
        assertThat(rig.records.getByClientUuid(RECORD)).isNull()
        files.beforeDelete = null
        rig.engine.applyInitialEntities(session, emptyList())
        assertThat(mediaFiles()).isEmpty()
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun samePrimaryAndCleanupExceptionInstanceIsNeverSelfSuppressed() = runTest {
        val files = OwnedFiles(temporary.root)
        val backend = StreamingBackend()
        val rig = newRig(files, backend)
        val failure = IOException("shared synthetic failure")
        backend.stream = { _, output -> output.write(bytes); throw failure }
        files.beforeDelete = { throw failure }
        val actual = runCatching { rig.engine.applyInitialEntities(session, page()) }.exceptionOrNull()
        assertThat(actual).isSameInstanceAs(failure)
        assertThat(failure.suppressed).isEmpty()
        assertThat(ownerMarkers()).hasSize(1)
        files.beforeDelete = null
        rig.engine.applyInitialEntities(session, emptyList())
        assertThat(ownerMarkers()).isEmpty()
    }

    private fun identity(uuid: String) = CausalMediaItem(
        mediaUuid = uuid, role = "log", sha256 = MediaContentDigest.ofBytes(bytes), byteSize = 4,
        mime = "image/jpeg",
    )

    private fun newRig(files: OwnedFiles, backend: StreamingBackend) = ReplicaEngineRig(session, backend, files).also {
        it.babies.seed(localReplicaBaby().copy(syncDirty = false, familyAuthority = true, baseVersion = "v-baby"))
    }

    private fun seedHistorical(rig: ReplicaEngineRig, uuid: String, path: String = "") {
        val recordId = rig.records.seed(RecordEntity(
            clientUuid = "historical-host", babyId = 1, type = "formula", timestamp = 100,
            payloadJson = """{"amount_ml":90}""", schemaVersion = 2, updatedAt = 100,
            syncDirty = false, baseVersion = "v-r0",
        ))
        rig.media.seed(MediaAssetEntity(
            clientUuid = uuid, recordId = recordId, kind = "log", localUri = path,
            remoteUri = session.receiptFor(uuid), mime = "image/jpeg", byteSize = 4,
            createdAt = 210, updatedAt = 210, syncDirty = false,
            sha256 = MediaContentDigest.ofBytes(bytes),
        ))
    }

    private fun page(twoMedia: Boolean = false): List<SyncEntity> = buildList {
        add(remoteReplicaRecord(RECORD))
        add(remoteReplicaMedia(MEDIA_A, RECORD))
        if (twoMedia) add(remoteReplicaMedia(MEDIA_B, RECORD))
    }
    private fun replacementPage() = page().map { entity ->
        if (entity.type == "media") entity.copy(updatedAt = 211) else entity
    }
    private fun replacementReceipt() = MediaDownloadReceipt(
        MediaContentDigest.ofBytes(replacementBytes), replacementBytes.size.toLong(),
    )
    private fun StreamingBackend.serveReplacement() {
        stream = { _, output -> output.write(replacementBytes); replacementReceipt() }
    }
    private fun receipt() = MediaDownloadReceipt(MediaContentDigest.ofBytes(bytes), bytes.size.toLong())
    private fun ownerMarkers() = temporary.root.walkTopDown().filter { it.name.endsWith(".owner") }.toList()
    private fun mediaFiles() = temporary.root.walkTopDown().filter { it.isFile && it.name.startsWith("media.") }.toList()

    private inner class StreamingBackend : RecordingSyncBackend() {
        var stream: suspend (String, OutputStream) -> MediaDownloadReceipt = { _, output ->
            output.write(bytes)
            receipt()
        }
        override suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray =
            error("The engine must use the streaming transport")
        override suspend fun downloadMedia(session: SyncSession, clientUuid: String, target: OutputStream): MediaDownloadReceipt {
            mediaGets += clientUuid
            return stream(clientUuid, target)
        }
    }

    private class OwnedFiles(root: File) : TestMediaFileStore() {
        private val store = OwnedMediaDownloadStore(root)
        var beforeDelete: (suspend () -> Unit)? = null
        var afterReadable: ((File) -> Unit)? = null
        var cancelAfterRegistration = false
        var failWrites = false
        var failNewRelease = false
        var beforeRetain: (() -> Unit)? = null
        var cancelAfterRetain = false
        init { filesRoot = root }
        override suspend fun beginDownload(
            clientUuid: String, kind: String, mime: String?, registerOwnership: (MediaDownloadOwnership) -> Unit,
        ): SyncMediaDownloadTarget = withContext(Dispatchers.IO) {
            val target = store.begin(clientUuid, kind, mime).also { ownership ->
                registerOwnership(object : MediaDownloadOwnership {
                    override suspend fun release(reclaimUnreferenced: suspend (String) -> Unit) {
                        ownership.release { path ->
                            if (failNewRelease) throw IOException("synthetic new ownership release failure")
                            reclaimUnreferenced(path)
                        }
                    }
                })
            }
            if (cancelAfterRegistration) currentCoroutineContext().cancel()
            if (!failWrites) target else object : SyncMediaDownloadTarget by target {
                override val output: OutputStream = object : OutputStream() {
                    override fun write(value: Int) = throw IOException("synthetic disk full")
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        target.output.write(bytes, offset, minOf(2, length))
                        throw IOException("synthetic disk full")
                    }
                }.withMediaDownloadWriteErrors()
            }
        }
        override suspend fun retainForReplacement(
            localUri: String, registerOwnership: (MediaDownloadOwnership) -> Unit,
        ) = withContext(Dispatchers.IO) {
            beforeRetain?.invoke()
            store.retainForReplacement(localUri)?.let(registerOwnership)
            if (cancelAfterRetain) currentCoroutineContext().cancel()
            Unit
        }
        override fun pendingDownloads() = store.pending()
        override suspend fun saveDownloaded(clientUuid: String, kind: String, bytes: ByteArray, mime: String?): String =
            error("The production owned store must not use the buffered adapter")
        override fun readableFile(localUri: String): File? = super.readableFile(localUri)?.also { file ->
            afterReadable?.also { afterReadable = null }?.invoke(file)
        }
        override fun statLength(localUri: String): Long? = super.readableFile(localUri)?.length()
        override suspend fun inspect(localUri: String): LocalMediaInfo? = statLength(localUri)?.let {
            LocalMediaInfo(it, "image/jpeg", null, null)
        }
        override suspend fun delete(localUri: String) {
            beforeDelete?.invoke()
            super.delete(localUri)
            val raw = File(localUri)
            deleteExistingSyncMediaFile(if (raw.isAbsolute) raw else File(filesRoot, localUri))
        }
    }

    private companion object {
        const val RECORD = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee01"
        const val MEDIA_A = "11111111-1111-4111-8111-111111111111"
        const val MEDIA_B = "22222222-2222-4222-8222-222222222222"
    }
}
