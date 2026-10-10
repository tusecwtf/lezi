package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.MediaContentDigest
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.sync.backend.PullMediaIdentity
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncEntity
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RealSyncPortStagedDownloadOwnershipTest {
    @Test(timeout = 30_000)
    fun secondDownloadFailureReclaimsFirstOwnedFileBeforeRetry() = failedBatch(false)

    @Test(timeout = 30_000)
    fun cancelledSecondDownloadReclaimsFirstOwnedFileBeforeRetry() = failedBatch(true)

    private fun failedBatch(cancel: Boolean) = runBlocking {
        val root = Files.createTempDirectory("download-ownership").toFile()
        try {
            var saved = CompletableDeferred<Unit>()
            val files = object : TestMediaFileStore() {
                override suspend fun saveDownloadedOwned(clientUuid: String, kind: String, bytes: ByteArray,
                    mime: String?, reserve: suspend (String) -> Unit): String {
                    val file = File(root, "$clientUuid.jpg")
                    reserve(file.path)
                    file.writeBytes(bytes)
                    saved.complete(Unit)
                    return file.path
                }
                override suspend fun delete(localUri: String) { File(localUri).delete() }
            }
            val rig = SyncRig(joinedSession("family-a").copy(pullCursor = 1), mediaFileStore = files)
            rig.awaitStartupRecovery()
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            val record = localRecord(babyId).copy(syncDirty = false)
            rig.records.seed(record)
            val first = "11111111-1111-4111-8111-111111111111"
            val second = "22222222-2222-4222-8222-222222222222"
            val bytesA = byteArrayOf(1, 2, 3)
            val bytesB = byteArrayOf(4, 5, 6)
            fun media(uuid: String, bytes: ByteArray) = SyncEntity("media", uuid,
                """{"kind":"log","record_client_uuid":"${record.clientUuid}","care_plan_client_uuid":null,"baby_client_uuid":null,"mime":"image/jpeg","width":1,"height":1,"byte_size":3}""",
                100, mediaIdentity = PullMediaIdentity(uuid, "log", MediaContentDigest.ofBytes(bytes), 3))
            rig.backend.nextPull = PullResult(listOf(media(first, bytesA), media(second, bytesB)), 2, "current-generation", false)
            rig.backend.mediaBytesByUuid[first] = bytesA
            rig.backend.mediaBytesByUuid[second] = bytesB
            rig.backend.onGetMedia = { uuid ->
                if (uuid == second) {
                    saved.await()
                    if (cancel) throw CancellationException("cancel second GET")
                    else throw IOException("fail second GET")
                }
            }
            repeat(2) {
                saved = CompletableDeferred()
                assertThat(runCatching { rig.port.sync(SyncTrigger.PullToRefresh).getOrThrow() }.isFailure).isTrue()
                assertThat(root.listFiles().orEmpty().toList()).isEmpty()
                assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
                assertThat(rig.conflictDetails.getTransportJournal("staged-media-downloads-v1")).isNull()
            }
            rig.backend.onGetMedia = null
            rig.port.sync(SyncTrigger.PullToRefresh).getOrThrow()
            assertThat(root.listFiles().orEmpty().toList()).hasSize(2)
            assertThat(File(requireNotNull(rig.media.getByClientUuid(first)).localUri).readBytes()).isEqualTo(bytesA)
            assertThat(File(requireNotNull(rig.media.getByClientUuid(second)).localUri).readBytes()).isEqualTo(bytesB)
        } finally { root.deleteRecursively() }
    }

    @Test(timeout = 30_000)
    fun restartedCleanupReclaimsOnlyPathsWithoutCurrentOwners() = runBlocking {
        val root = Files.createTempDirectory("download-restart").toFile()
        try {
            val abandoned = File(root, "abandoned.jpg").apply { writeBytes(byteArrayOf(1)) }
            val retained = File(root, "retained.jpg").apply { writeBytes(byteArrayOf(2)) }
            val files = object : TestMediaFileStore() {
                override suspend fun delete(localUri: String) { File(localUri).delete() }
            }
            val rig = SyncRig(joinedSession("family-a"), mediaFileStore = files)
            rig.awaitStartupRecovery()
            val baby = rig.babies.seed(localBaby().copy(syncDirty = false))
            val record = rig.records.seed(localRecord(baby).copy(syncDirty = false))
            rig.media.seed(MediaAssetEntity(clientUuid = "33333333-3333-4333-8333-333333333333",
                recordId = record, localUri = retained.path, createdAt = 100, updatedAt = 100, syncDirty = false))
            rig.conflictDetails.putTransportJournal("staged-media-downloads-v1",
                kotlinx.serialization.json.JsonArray(listOf(abandoned.path, retained.path)
                    .map { kotlinx.serialization.json.JsonPrimitive(it) }).toString(), 0)
            rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()
            assertThat(abandoned.exists()).isFalse()
            assertThat(retained.readBytes()).isEqualTo(byteArrayOf(2))
            assertThat(rig.conflictDetails.getTransportJournal("staged-media-downloads-v1")).isNull()
        } finally { root.deleteRecursively() }
    }
}
