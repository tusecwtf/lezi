package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.MediaContentDigest
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.sync.media.FileImmutableMediaSpool
import com.lezi.babylog.sync.media.LocalMediaInfo
import com.lezi.babylog.sync.media.PreparedMedia
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Actual raw files, non-idempotent codec outputs and the durable file spool. */
class RetainedPreparationSourceTest {
    @Test(timeout = 30_000)
    fun attachmentAdditionReusesUnchangedSourcesCanonicalResultExactlyOnce() = race(false)

    @Test(timeout = 30_000)
    fun changedRawBytesCannotReuseAnEarlierSourcesCanonicalResult() = race(true)

    @Test(timeout = 30_000)
    fun retainedCanonicalDonorReplacesSameSizeWrongBytesBehindCanonicalColumns() =
        race(changeSourceBytes = false, canonicalColumnsCorrupt = true)

    @Test(timeout = 30_000)
    fun sameMutationRestartReusesUnchangedCapturedSourceWithoutAnotherCodecPass() =
        race(changeSourceBytes = false, manifestCrash = true)

    @Test(timeout = 30_000)
    fun sameMutationRestartRejectsChangedRawBytesBehindTheSameRowRevision() =
        race(changeSourceBytes = true, manifestCrash = true)

    @Test(timeout = 30_000)
    fun sameMutationRestartPreservesMissingSourcesSoleCanonicalSpoolCopy() =
        race(changeSourceBytes = false, manifestCrash = true, missingSource = true)

    private fun race(changeSourceBytes: Boolean, canonicalColumnsCorrupt: Boolean = false,
        manifestCrash: Boolean = false, missingSource: Boolean = false) = runBlocking {
        val directory = Files.createTempDirectory("retained-preparation").toFile()
        try {
            val source = File(directory, "original.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val added = File(directory, "added.png").apply { writeBytes(byteArrayOf(7, 7, 7)) }
            val counts = mutableMapOf<String, Int>()
            var afterPrepare: (suspend () -> Unit)? = null
            val files = object : TestMediaFileStore() {
                override fun statLength(localUri: String): Long? = readableFile(localUri)?.length()
                override suspend fun inspect(localUri: String): LocalMediaInfo? = readableFile(localUri)?.let {
                    LocalMediaInfo(it.length(), if (it.extension == "png") "image/png" else "image/jpeg", 1, 1)
                }
                override suspend fun prepareUpload(localUri: String): PreparedMedia {
                    counts[localUri] = (counts[localUri] ?: 0) + 1
                    val input = File(localUri).readBytes()
                    val bytes = when (input.first().toInt()) {
                        1 -> byteArrayOf(9, 8, 7)
                        4 -> byteArrayOf(6, 5, 4)
                        else -> byteArrayOf(8, 8)
                    }
                    val file = File.createTempFile("codec-", ".jpg", directory).apply { writeBytes(bytes) }
                    afterPrepare?.also { afterPrepare = null }?.invoke()
                    return PreparedMedia(file, "image/jpeg", 1, 1)
                }
                override suspend fun saveDownloaded(clientUuid: String, kind: String, bytes: ByteArray, mime: String?): String =
                    File(directory, "canonical-$clientUuid.jpg").apply { writeBytes(bytes) }.path
            }
            fun newRig() = SyncRig(joinedSession("family-a"), mediaFileStore = files,
                immutableMediaSpoolOverride = FileImmutableMediaSpool(files, File(directory, "spool"), 1024 * 1024, 1024))
            var rig = newRig()
            rig.awaitStartupRecovery()
            val baby = rig.babies.seed(localBaby().copy(syncDirty = false))
            val record = rig.records.seed(localRecord(baby))
            val originalUuid = "11111111-1111-4111-8111-111111111111"
            val addedUuid = "22222222-2222-4222-8222-222222222222"
            fun asset(uuid: String, file: File) = MediaAssetEntity(clientUuid = uuid, recordId = record,
                localUri = file.path, sha256 = MediaContentDigest.ofReadableFile(file), byteSize = file.length(),
                mime = "image/png", width = 1, height = 1, createdAt = 100, updatedAt = 100)
            rig.media.seed(asset(originalUuid, source))
            if (manifestCrash) rig.conflictDetails.failNextFrozenManifestWrite = java.io.IOException("manifest write interrupted")
            else afterPrepare = {
                rig.media.seed(asset(addedUuid, added))
                if (changeSourceBytes) source.writeBytes(byteArrayOf(4, 3, 2, 1))
            }
            val first = rig.port.sync(SyncTrigger.LocalWrite)
            if (manifestCrash) assertThat(first.isFailure).isTrue() else first.getOrThrow()
            assertThat(rig.backend.causalCommittedUnits).isEmpty()
            assertThat(counts[source.path]).isEqualTo(1)
            if (manifestCrash) {
                val old = rig
                val mutation = requireNotNull(old.records.getByClientUuid("record-local")?.mutationId)
                if (changeSourceBytes) source.writeBytes(byteArrayOf(4, 3, 2, 1))
                if (missingSource) check(source.delete())
                rig = newRig()
                rig.awaitStartupRecovery()
                old.babies.listAllIncludingDeleted().forEach { rig.babies.seed(it) }
                old.records.listAllIncludingDeleted().forEach { rig.records.seed(it) }
                old.media.listAllIncludingDeleted().forEach { rig.media.seed(it) }
                for (key in listOf("media-freeze-capture-v1:$mutation", "media-preparation-sources-v1:$mutation")) {
                    requireNotNull(old.conflictDetails.getTransportJournal(key)).let {
                        rig.conflictDetails.putTransportJournal(it.journalKey, it.payloadJson, it.contentEpoch)
                    }
                }
            }
            if (canonicalColumnsCorrupt) {
                val canonical = byteArrayOf(9, 8, 7)
                val row = requireNotNull(rig.media.getByClientUuid(originalUuid))
                rig.media.update(row.copy(sha256 = MediaContentDigest.ofBytes(canonical), byteSize = 3,
                    mime = "image/jpeg", width = 1, height = 1))
                source.writeBytes(byteArrayOf(0, 0, 0))
            }
            // Rotate only the provably unbound preparation, then freeze the current attachment set.
            rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()
            rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()
            val published = rig.backend.causalCommittedUnits.flatten().single()
            if (manifestCrash) assertThat(published.media.map { it.mediaUuid }).containsExactly(originalUuid)
            else assertThat(published.media.map { it.mediaUuid }).containsExactly(originalUuid, addedUuid)
            assertThat(counts[source.path]).isEqualTo(if (changeSourceBytes) 2 else 1)
            if (!manifestCrash) assertThat(counts[added.path]).isEqualTo(1)
            val expected = if (changeSourceBytes) byteArrayOf(6, 5, 4) else byteArrayOf(9, 8, 7)
            assertThat(published.media.single { it.mediaUuid == originalUuid }.sha256)
                .isEqualTo(MediaContentDigest.ofBytes(expected))
            assertThat(File(requireNotNull(rig.media.getByClientUuid(originalUuid)).localUri).readBytes()).isEqualTo(expected)
            if (canonicalColumnsCorrupt) {
                assertThat(rig.media.getByClientUuid(originalUuid)?.localUri).isNotEqualTo(source.path)
                assertThat(source.readBytes()).isEqualTo(byteArrayOf(0, 0, 0))
            }
        } finally { directory.deleteRecursively() }
    }
}
