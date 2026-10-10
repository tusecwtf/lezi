package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.sync.media.FileImmutableMediaSpool
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbe
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RestoreDedicatedStorageTest {
    @Test(timeout = 30_000)
    fun restoreSnapshotLargerThanOrdinaryQueueUploadsExactBytesWithoutConsumingQueue() = runBlocking {
        val directory = Files.createTempDirectory("restore-dedicated-budget").toFile()
        try {
            val files = TestMediaFileStore()
            val ordinaryRoot = File(directory, "ordinary")
            val spool = FileImmutableMediaSpool(files, ordinaryRoot, capacityBytes = 96, slotReservationBytes = 16)
            val rig = SyncRig(joinedSession("family-a"), mediaFileStore = files,
                immutableMediaSpoolOverride = spool, setupProbe = SetupProbe { _, trusted ->
                    SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty,
                        setOf("nursing_plan_intent_v1", "restore_authority_v1"))
                })
            rig.awaitStartupRecovery()
            val baby = rig.babies.seed(localBaby().copy(clientUuid = "11111111-1111-4111-8111-111111111111"))
            val record = rig.records.seed(localRecord(baby).copy(clientUuid = "22222222-2222-4222-8222-222222222222"))
            val sourceBytes = listOf(ByteArray(64) { 7 }, ByteArray(64) { 9 })
            sourceBytes.forEachIndexed { index, bytes ->
                val source = File(directory, "source-$index.jpg").apply { writeBytes(bytes) }
                rig.media.seed(MediaAssetEntity(recordId = record,
                    clientUuid = "33333333-3333-4333-8333-33333333333${index + 1}",
                    kind = "log", localUri = source.path, mime = "image/jpeg", byteSize = bytes.size.toLong(),
                    createdAt = 120, updatedAt = 120))
            }
            rig.port.startDisasterRecovery(TrustedEndpointProfile.systemPki("https://replacement.example.test"),
                "Owner", "Phone", "root").getOrThrow()
            assertThat(rig.backend.disasterRestoreMediaBodies.map { it.second.toList() })
                .containsExactlyElementsIn(sourceBytes.map { it.toList() }).inOrder()
            assertThat(ordinaryRoot.walkTopDown().filter { it.isFile }.toList()).isEmpty()
            sourceBytes.forEachIndexed { index, bytes ->
                assertThat(File(directory, "source-$index.jpg").readBytes()).isEqualTo(bytes)
            }
        } finally { directory.deleteRecursively() }
    }
}
