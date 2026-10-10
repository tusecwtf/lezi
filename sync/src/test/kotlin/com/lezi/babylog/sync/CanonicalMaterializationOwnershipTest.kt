package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.MediaContentDigest
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.sync.media.FileImmutableMediaSpool
import com.lezi.babylog.sync.media.LocalMediaInfo
import com.lezi.babylog.sync.media.PreparedMedia
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

class CanonicalMaterializationOwnershipTest {
    @Test(timeout = 30_000)
    fun secondCanonicalSaveFailureReclaimsFirstCopyAndReusesSpoolOnRetry() = failure(false, false)

    @Test(timeout = 30_000)
    fun secondCanonicalSaveCancellationReclaimsFirstCopyAndReusesSpoolOnRetry() = failure(true, false)

    @Test(timeout = 30_000)
    fun failedCleanupKeepsDurableReservationAndRestartReclaimsItBeforeRetry() = failure(false, true)

    private fun failure(cancel: Boolean, restart: Boolean) = runBlocking {
        val directory = Files.createTempDirectory("canonical-owned").toFile()
        try {
            var saves = 0
            var failSave = true
            var failDelete = restart
            val codecs = mutableMapOf<String, Int>()
            val files = object : TestMediaFileStore() {
                override fun statLength(localUri: String): Long? = readableFile(localUri)?.length()
                override suspend fun inspect(localUri: String): LocalMediaInfo? = readableFile(localUri)?.let {
                    LocalMediaInfo(it.length(), if (it.extension == "png") "image/png" else "image/jpeg", 1, 1)
                }
                override suspend fun prepareUpload(localUri: String): PreparedMedia {
                    codecs[localUri] = (codecs[localUri] ?: 0) + 1
                    val bytes = File(localUri).readBytes() + byteArrayOf(9)
                    return PreparedMedia(File.createTempFile("codec-", ".jpg", directory).apply { writeBytes(bytes) }, "image/jpeg", 1, 1)
                }
                override suspend fun saveDownloadedOwned(clientUuid: String, kind: String, bytes: ByteArray,
                    mime: String?, reserve: suspend (String) -> Unit): String {
                    val target = File(directory, "canonical-$clientUuid.jpg")
                    reserve(target.path)
                    saves++
                    if (failSave && saves == 2) {
                        if (cancel) throw CancellationException("second canonical save cancelled")
                        throw IOException("second canonical save failed")
                    }
                    target.writeBytes(bytes)
                    return target.path
                }
                override suspend fun delete(localUri: String) {
                    if (failDelete) { failDelete = false; throw IOException("cleanup interrupted") }
                    File(localUri).delete()
                }
            }
            fun newRig() = SyncRig(joinedSession("family-a"), mediaFileStore = files,
                immutableMediaSpoolOverride = FileImmutableMediaSpool(files, File(directory, "spool"), 1024 * 1024, 1024))
            var rig = newRig()
            rig.awaitStartupRecovery()
            val baby = rig.babies.seed(localBaby().copy(syncDirty = false))
            val record = rig.records.seed(localRecord(baby))
            val raw = (1..2).map { index -> File(directory, "raw$index.png").apply { writeBytes(byteArrayOf(index.toByte())) } }
            raw.forEachIndexed { index, file -> rig.media.seed(MediaAssetEntity(
                clientUuid = "11111111-1111-4111-8111-11111111111${index + 1}", recordId = record,
                localUri = file.path, sha256 = MediaContentDigest.ofReadableFile(file), byteSize = 1,
                mime = "image/png", width = 1, height = 1, createdAt = 100, updatedAt = 100)) }
            assertThat(runCatching { rig.port.sync(SyncTrigger.LocalWrite).getOrThrow() }.isFailure).isTrue()
            assertThat(rig.backend.causalCommittedUnits).isEmpty()
            assertThat(directory.listFiles().orEmpty().count { it.name.startsWith("canonical-") }).isEqualTo(if (restart) 1 else 0)
            val mutation = requireNotNull(rig.records.getByClientUuid("record-local")?.mutationId)
            assertThat(rig.conflictDetails.getFrozenMediaSpoolManifest(mutation)).isNotNull()
            failSave = false
            if (restart) {
                assertThat(rig.conflictDetails.getTransportJournal("canonical-media-materialization-v1")).isNotNull()
                val old = rig
                rig = newRig()
                rig.awaitStartupRecovery()
                old.babies.listAllIncludingDeleted().forEach { rig.babies.seed(it) }
                old.records.listAllIncludingDeleted().forEach { rig.records.seed(it) }
                old.media.listAllIncludingDeleted().forEach { rig.media.seed(it) }
                old.conflictDetails.listFrozenMediaSpoolManifests().forEach {
                    rig.conflictDetails.putTransportJournal(it.journalKey, it.payloadJson, it.contentEpoch)
                }
                for (key in listOf("media-freeze-capture-v1:$mutation", "media-preparation-sources-v1:$mutation", "canonical-media-materialization-v1")) {
                    requireNotNull(old.conflictDetails.getTransportJournal(key)).let {
                        rig.conflictDetails.putTransportJournal(it.journalKey, it.payloadJson, it.contentEpoch)
                    }
                }
            }
            rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()
            assertThat(rig.backend.causalCommittedUnits.flatten()).hasSize(1)
            assertThat(directory.listFiles().orEmpty().count { it.name.startsWith("canonical-") }).isEqualTo(2)
            raw.forEach { assertThat(codecs[it.path]).isEqualTo(1) }
            assertThat(rig.conflictDetails.getTransportJournal("canonical-media-materialization-v1")).isNull()
            rig.media.listAllIncludingDeleted().forEach { row ->
                val bytes = File(row.localUri).readBytes()
                assertThat(MediaContentDigest.ofBytes(bytes)).isEqualTo(row.sha256)
                assertThat(bytes.size.toLong()).isEqualTo(row.byteSize)
            }
        } finally { directory.deleteRecursively() }
    }
}
