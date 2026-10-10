package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.sync.session.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** A new restore authority captures available local bytes; old ordinary receipts stay immutable. */
class RestoreNewAuthoritySourceTest {
    @Test(timeout = 30_000)
    fun readableLegacyRawFileSurvivesOldCanonicalLengthColumns() = probe(byteArrayOf(9, 8, 7))

    @Test(timeout = 30_000)
    fun readableLegacyRawFileSurvivesSameSizeOldCanonicalDigest() = probe(byteArrayOf(9, 8, 7, 6))

    private fun probe(oldCanonical: ByteArray) = runBlocking {
        val directory = Files.createTempDirectory("restore-new-authority-source").toFile()
        try {
            val raw = byteArrayOf(1, 2, 3, 4)
            val source = File(directory, "legacy-readable.png").apply { writeBytes(raw) }
            val rig = SyncRig(joinedSession("family-a"), setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty,
                    setOf("nursing_plan_intent_v1", "restore_authority_v1"))
            })
            rig.awaitStartupRecovery()
            val baby = rig.babies.seed(localBaby().copy(clientUuid = "11111111-1111-4111-8111-111111111111"))
            val record = rig.records.seed(localRecord(baby).copy(clientUuid = "22222222-2222-4222-8222-222222222222"))
            val photo = "33333333-3333-4333-8333-333333333333"
            rig.media.seed(MediaAssetEntity(clientUuid = photo, recordId = record, kind = "log",
                localUri = source.path, remoteUri = "old-family-receipt", mime = "image/jpeg",
                sha256 = MessageDigest.getInstance("SHA-256").digest(oldCanonical)
                    .joinToString("") { "%02x".format(it) },
                byteSize = oldCanonical.size.toLong(), createdAt = 120, updatedAt = 120, syncDirty = false))
            val before = requireNotNull(rig.media.getByClientUuid(photo))
            rig.port.startDisasterRecovery(TrustedEndpointProfile.systemPki("https://replacement.example.test"),
                "Owner", "Phone", "root").getOrThrow()
            assertThat(rig.backend.disasterRestoreMediaBodies.single().second).isEqualTo(raw)
            val manifest = rig.backend.disasterRestoreManifestMedia.single().single()
            assertThat(manifest.byteSize).isEqualTo(4)
            assertThat(manifest.sha256).isEqualTo("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a")
            assertThat(rig.media.getByClientUuid(photo)).isEqualTo(before)
            assertThat(source.readBytes()).isEqualTo(raw)
        } finally { directory.deleteRecursively() }
    }
}
