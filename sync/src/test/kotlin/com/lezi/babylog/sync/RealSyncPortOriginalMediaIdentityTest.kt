package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.sync.media.FileImmutableMediaSpool
import com.lezi.babylog.sync.media.LocalMediaInfo
import com.lezi.babylog.sync.media.PreparedMedia
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/** Actual source/spool files; the image codec boundary deliberately is not idempotent. */
class RealSyncPortOriginalMediaIdentityTest {
    @Test(timeout = 30_000)
    fun originatingDeviceNormalizesOnceAndReusesPublishedBytesAfterRestartAndNoteEdit() = runBlocking {
        val directory = Files.createTempDirectory("original-media-identity").toFile()
        try {
            val raw = byteArrayOf(1, 2, 3, 4)
            val canonical = byteArrayOf(9, 8, 7)
            val source = File(directory, "import.png").apply { writeBytes(raw) }
            val normalizations = AtomicInteger()
            fun newRig(session: com.lezi.babylog.sync.session.SyncSession): SyncRig {
                val files = FileCodecBoundary(directory, canonical, normalizations)
                return SyncRig(
                    session,
                    mediaFileStore = files,
                    immutableMediaSpoolOverride = FileImmutableMediaSpool(
                        files, File(directory, "spool"), 1024 * 1024, 1024,
                    ),
                )
            }
            val first = newRig(joinedSession("family-a"))
            first.awaitStartupRecovery()
            val babyId = first.babies.seed(localBaby().copy(syncDirty = false))
            val recordId = first.records.seed(localRecord(babyId))
            val mediaUuid = "55555555-5555-4555-8555-555555555555"
            first.media.seed(MediaAssetEntity(
                clientUuid = mediaUuid, recordId = recordId, localUri = source.path,
                mime = "image/png", byteSize = raw.size.toLong(), width = 1, height = 1,
                sha256 = digest(raw), createdAt = 100, updatedAt = 100,
            ))

            first.port.sync(SyncTrigger.LocalWrite).getOrThrow()
            val initialManifest = first.backend.causalCommittedUnits.flatten().single().media.single()
            val persistedMedia = requireNotNull(first.media.getByClientUuid(mediaUuid))
            assertThat(File(persistedMedia.localUri).readBytes()).isEqualTo(canonical)
            assertThat(persistedMedia.sha256).isEqualTo(digest(canonical))
            assertThat(persistedMedia.byteSize).isEqualTo(3)

            // Recreate the port, codec adapter and spool; only persisted rows and files survive.
            val restarted = newRig(first.preferences.current())
            restarted.awaitStartupRecovery()
            first.babies.listAllIncludingDeleted().forEach { restarted.babies.seed(it) }
            first.records.listAllIncludingDeleted().forEach { record ->
                restarted.records.seed(record.copy(note = "after restart", updatedAt = record.updatedAt + 1, syncDirty = true, mutationId = null))
            }
            first.media.listAllIncludingDeleted().forEach { restarted.media.seed(it) }
            requireNotNull(first.conflictDetails.getTransportJournal("canonical-media-bytes-v1:$mediaUuid")).let {
                restarted.conflictDetails.putTransportJournal(it.journalKey, it.payloadJson, it.contentEpoch)
            }
            restarted.port.sync(SyncTrigger.LocalWrite).getOrThrow()

            val editedManifest = restarted.backend.causalCommittedUnits.flatten().single().media.single()
            assertThat(editedManifest).isEqualTo(initialManifest)
            assertThat(normalizations.get()).isEqualTo(1)
            assertThat(File(requireNotNull(restarted.media.getByClientUuid(mediaUuid)).localUri).readBytes())
                .isEqualTo(canonical)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 30_000)
    fun legacyPublishedRawFileWithCanonicalColumnsIsRecoveredFromAuthenticatedPull() =
        legacyPublishedRawFileIsRecovered(canonicalColumns = true)

    @Test(timeout = 30_000)
    fun legacyPublishedRawFileWithRawColumnsIsRecoveredFromAuthenticatedPull() =
        legacyPublishedRawFileIsRecovered(canonicalColumns = false)

    @Test(timeout = 30_000)
    fun legacyMaintenanceResumesItsOwnCursorAndStopsScanningAfterCompletion() =
        legacyPublishedRawFileIsRecovered(canonicalColumns = false, restartMaintenance = true)

    @Test(timeout = 30_000)
    fun legacyDownloadCannotOverwriteConcurrentSameTimestampMetadataEdit() =
        legacyPublishedRawFileIsRecovered(canonicalColumns = false, concurrentEdit = true)

    @Test(timeout = 30_000)
    fun laterLegacyRowRearmsCompletedMaintenanceWithoutAnotherLocalScan() =
        legacyPublishedRawFileIsRecovered(canonicalColumns = false, completeFirst = true)

    @Test(timeout = 30_000)
    fun markedCanonicalRowRepairsCorruptBytesWithoutReplacingAcceptedMetadata() =
        legacyPublishedRawFileIsRecovered(canonicalColumns = true, markedCorrupt = true)

    @Test(timeout = 30_000)
    fun dirtyMarkedCanonicalRowRepairsCorruptBytesAndPublishesNewMetadata() =
        legacyPublishedRawFileIsRecovered(canonicalColumns = true, markedCorrupt = true, markedDirty = true)

    @Test(timeout = 30_000)
    fun dirtyMarkedCanonicalRowRepairsMissingBytesAndPublishesNewMetadata() =
        legacyPublishedRawFileIsRecovered(canonicalColumns = true, markedCorrupt = true,
            markedDirty = true, markedMissing = true)

    @Test(timeout = 30_000)
    fun cleanMarkedCanonicalRowAppliesGenuinelyNewerRemoteMetadata() =
        legacyPublishedRawFileIsRecovered(canonicalColumns = true, markedCorrupt = true, markedRemoteNewer = true)

    private fun legacyPublishedRawFileIsRecovered(canonicalColumns: Boolean,
        restartMaintenance: Boolean = false, concurrentEdit: Boolean = false,
        completeFirst: Boolean = false, markedCorrupt: Boolean = false,
        markedDirty: Boolean = false, markedMissing: Boolean = false,
        markedRemoteNewer: Boolean = false) = runBlocking {
        val directory = Files.createTempDirectory("legacy-published-media").toFile()
        val laterDownloadStarted = CompletableDeferred<Unit>()
        val allowLaterDownload = CompletableDeferred<Unit>()
        try {
            val raw = byteArrayOf(1, 2, 3, 4)
            val canonical = byteArrayOf(9, 8, 7)
            val source = File(directory, "legacy-import.png").apply { writeBytes(raw) }
            val normalizations = AtomicInteger()
            fun newRig() : SyncRig {
                val files = FileCodecBoundary(directory, canonical, normalizations)
                return SyncRig(joinedSession("family-a").copy(pullCursor = 41),
                    mediaFileStore = files,
                    immutableMediaSpoolOverride = FileImmutableMediaSpool(files,
                        File(directory, "spool"), 1024 * 1024, 1024))
            }
            var rig = newRig()
            rig.awaitStartupRecovery()
            if (completeFirst) rig.port.sync(SyncTrigger.PullToRefresh).getOrThrow()
            val initialAuditQueries = rig.media.canonicalAuditQueries
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            val record = localRecord(babyId).copy(note = "new note after upgrade", baseVersion = "old-base")
            val recordId = rig.records.seed(record)
            val photo = "88888888-8888-4888-8888-888888888888"
            val columnBytes = if (canonicalColumns) canonical else raw
            rig.media.seed(MediaAssetEntity(clientUuid = photo, recordId = recordId,
                localUri = source.path, remoteUri = rig.preferences.current().expectedMediaReceipt(photo),
                mime = if (canonicalColumns) "image/jpeg" else "image/png", width = 1, height = 1,
                byteSize = columnBytes.size.toLong(), sha256 = digest(columnBytes),
                createdAt = 100, updatedAt = 100, syncDirty = false))
            if (markedCorrupt) {
                val row = requireNotNull(rig.media.getByClientUuid(photo))
                rig.media.update(row.copy(mime = "accepted custom MIME", width = 9,
                    syncDirty = markedDirty, updatedAt = if (markedDirty) 101 else row.updatedAt))
                if (markedMissing) check(source.delete())
                rig.conflictDetails.putTransportJournal("canonical-media-bytes-v1:$photo", source.path, 100)
            }
            // Seed the old on-disk state directly: no new publication, spool or provenance marker.
            rig.backend.mediaBytes = canonical
            val mediaPage = com.lezi.babylog.sync.backend.PullResult(
                entities = listOf(com.lezi.babylog.sync.backend.SyncEntity(
                    type = "media", clientUuid = photo, updatedAt = if (markedRemoteNewer) 101 else 100,
                    payloadJson = """{"kind":"log","record_client_uuid":"${record.clientUuid}","care_plan_client_uuid":null,"baby_client_uuid":null,"mime":"image/jpeg","width":1,"height":1,"byte_size":3}""",
                    mediaIdentity = com.lezi.babylog.sync.backend.PullMediaIdentity(photo, "log", digest(canonical), 3),
                )), cursor = 42, generation = "current-generation", hasMore = false)

            var concurrentRevision: MediaAssetEntity? = null
            if (concurrentEdit) {
                val downloads = AtomicInteger()
                rig.backend.onGetMedia = {
                    if (downloads.incrementAndGet() > 1) {
                        laterDownloadStarted.complete(Unit)
                        allowLaterDownload.await()
                    }
                }
                rig.backend.beforeGetMediaReturn = {
                    val current = requireNotNull(rig.media.getByClientUuid(photo))
                    concurrentRevision = current.copy(mime = "new user metadata", width = 7, syncDirty = true)
                    rig.media.update(requireNotNull(concurrentRevision))
                }
            }
            if (restartMaintenance) {
                rig.backend.pullResults += com.lezi.babylog.sync.backend.PullResult(emptyList(), 41, "current-generation", false)
                rig.backend.pullResults += mediaPage.copy(cursor = 1, hasMore = true)
                for (cursor in 2L..8L) rig.backend.pullResults +=
                    com.lezi.babylog.sync.backend.PullResult(emptyList(), cursor, "current-generation", true)
                // Public sync schedules continuation after progress. Hold every attempt beyond
                // this durable page boundary, including background continuations, until restart.
                val repairing = rig
                lateinit var stopAtBoundary: suspend () -> Unit
                stopAtBoundary = {
                    repairing.backend.beforePullReturn = stopAtBoundary
                    if (repairing.backend.pullCursors.last() == 8L)
                        throw java.io.IOException("restart at durable media cursor 8")
                }
                repairing.backend.beforePullReturn = stopAtBoundary
                rig.port.sync(SyncTrigger.PullToRefresh).getOrThrow()
                assertThat(rig.preferences.current().pullCursor).isEqualTo(41)
                assertThat(rig.backend.causalCommittedUnits).isEmpty()
                val old = rig
                val maintenance = requireNotNull(old.conflictDetails.getTransportJournal("published-media-upgrade-v1"))
                assertThat(maintenance.payloadJson).contains("\"phase\":\"pull\"")
                assertThat(maintenance.payloadJson).contains("\"cursor\":8")
                rig = newRig()
                rig.awaitStartupRecovery()
                old.babies.listAllIncludingDeleted().forEach { rig.babies.seed(it) }
                old.records.listAllIncludingDeleted().forEach { rig.records.seed(it) }
                old.media.listAllIncludingDeleted().forEach { rig.media.seed(it) }
                for (key in listOf("published-media-upgrade-v1", "canonical-media-bytes-v1:$photo")) {
                    requireNotNull(old.conflictDetails.getTransportJournal(key)).let {
                        rig.conflictDetails.putTransportJournal(it.journalKey, it.payloadJson, it.contentEpoch)
                    }
                }
                rig.backend.pullResults += com.lezi.babylog.sync.backend.PullResult(emptyList(), 41, "current-generation", false)
                rig.backend.pullResults += com.lezi.babylog.sync.backend.PullResult(emptyList(), 9, "current-generation", false)
            } else rig.backend.nextPull = mediaPage
            if (completeFirst) {
                assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
                assertThat(rig.backend.causalCommittedUnits).isEmpty()
                assertThat(requireNotNull(rig.conflictDetails.getTransportJournal("published-media-upgrade-v1")).payloadJson)
                    .contains("\"phase\":\"pull\"")
            }
            val result = async { rig.port.sync(SyncTrigger.PullToRefresh) }
            if (concurrentEdit) {
                // A deferred download is retried within this round and may then schedule
                // another round. Park every later GET so these assertions observe the first
                // attempt, before a fresh round can legitimately repair the edited row.
                withTimeout(5_000) { laterDownloadStarted.await() }
                assertThat(rig.backend.mediaGets).hasSize(2)
                assertThat(rig.backend.causalCommittedUnits).isEmpty()
                val retained = requireNotNull(rig.media.getByClientUuid(photo))
                assertThat(retained).isEqualTo(requireNotNull(concurrentRevision))
                assertThat(rig.conflictDetails.getTransportJournal("canonical-media-bytes-v1:$photo")).isNull()
                assertThat(retained.mime).isEqualTo("new user metadata")
                assertThat(retained.width).isEqualTo(7)
                assertThat(retained.syncDirty).isTrue()
                assertThat(retained.localUri).isEqualTo(source.path)
                assertThat(source.readBytes()).isEqualTo(raw)
                assertThat(normalizations.get()).isEqualTo(0)
                allowLaterDownload.complete(Unit)
                result.await().getOrThrow()
                rig.port.sync(SyncTrigger.PullToRefresh).getOrThrow()
                val repaired = requireNotNull(rig.media.getByClientUuid(photo))
                assertThat(File(repaired.localUri).readBytes()).isEqualTo(canonical)
                assertThat(repaired.mime).isEqualTo("new user metadata")
                assertThat(repaired.width).isEqualTo(7)
                assertThat(rig.backend.causalCommittedUnits.flatten().single().media.single().mime)
                    .isEqualTo("new user metadata")
                assertThat(normalizations.get()).isEqualTo(0)
                return@runBlocking
            }
            result.await().getOrThrow()
            if (restartMaintenance) {
                assertThat(rig.backend.pullCursors).containsAtLeast(41L, 8L).inOrder()
                assertThat(rig.preferences.current().pullCursor).isEqualTo(41)
                assertThat(rig.media.canonicalAuditRowsRead).isEqualTo(0)
                assertThat(rig.media.canonicalAuditQueries).isEqualTo(0)
                val pulls = rig.backend.pullCount
                rig.port.sync(SyncTrigger.PullToRefresh).getOrThrow()
                assertThat(rig.backend.pullCursors.drop(pulls)).isNotEmpty()
                assertThat(rig.backend.pullCursors.drop(pulls).toSet()).containsExactly(41L)
                assertThat(rig.media.canonicalAuditRowsRead).isEqualTo(0)
                assertThat(rig.media.canonicalAuditQueries).isEqualTo(0)
            }

            if (completeFirst) assertThat(rig.media.canonicalAuditQueries).isEqualTo(initialAuditQueries)
            val adopted = requireNotNull(rig.media.getByClientUuid(photo))
            assertThat(File(adopted.localUri).readBytes()).isEqualTo(canonical)
            assertThat(adopted.sha256).isEqualTo(digest(canonical))
            assertThat(adopted.byteSize).isEqualTo(3)
            assertThat(adopted.mime).isEqualTo(if (markedCorrupt && !markedRemoteNewer) "accepted custom MIME" else "image/jpeg")
            if (markedCorrupt) assertThat(adopted.width).isEqualTo(if (markedRemoteNewer) 1 else 9)
            if (markedRemoteNewer) assertThat(adopted.updatedAt).isEqualTo(101)
            if (markedMissing) assertThat(source.exists()).isFalse()
            else assertThat(source.readBytes()).isEqualTo(raw)
            if (markedDirty) assertThat(adopted.updatedAt).isEqualTo(101)
            val manifest = rig.backend.causalCommittedUnits.flatten().single().media.single()
            assertThat(manifest.sha256).isEqualTo(digest(canonical))
            assertThat(normalizations.get()).isEqualTo(0)
        } finally {
            allowLaterDownload.cancel()
            directory.deleteRecursively()
        }
    }

    private class FileCodecBoundary(
        private val root: File,
        private val canonical: ByteArray,
        private val normalizations: AtomicInteger,
    ) : TestMediaFileStore() {
        override fun statLength(localUri: String): Long? = readableFile(localUri)?.length()
        override suspend fun inspect(localUri: String): LocalMediaInfo? = readableFile(localUri)?.let {
            LocalMediaInfo(it.length(), if (it.extension == "png") "image/png" else "image/jpeg", 1, 1)
        }
        override suspend fun prepareUpload(localUri: String): PreparedMedia {
            check(readableFile(localUri) != null)
            val bytes = if (normalizations.incrementAndGet() == 1) canonical else byteArrayOf(6, 5, 4)
            val file = File.createTempFile("codec-", ".jpg", root).apply { writeBytes(bytes) }
            return PreparedMedia(file, "image/jpeg", 1, 1)
        }
        override suspend fun saveDownloaded(clientUuid: String, kind: String, bytes: ByteArray, mime: String?): String =
            File(root, "canonical-$clientUuid.jpg").apply { writeBytes(bytes) }.path
        override suspend fun saveDownloadedOwned(clientUuid: String, kind: String, bytes: ByteArray,
            mime: String?, reserve: suspend (String) -> Unit): String {
            val file = File(root, "canonical-$clientUuid.jpg")
            reserve(file.path)
            file.writeBytes(bytes)
            return file.path
        }
    }

    private companion object {
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
