package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.sync.disasterrecovery.LosslessRestoreCompatibilityException
import com.lezi.babylog.sync.disasterrecovery.RestoreAuthorityUnsupportedException
import com.lezi.babylog.sync.session.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class RestoreSchema13CompatibilityTest {
    @Test(timeout = 30_000)
    fun exceptionalRawMimeStopsBeforeAnyRestoreWriteAndPreservesLocalInputs() = runBlocking {
        for (mime in listOf(null, "", "x".repeat(129), "😀".repeat(33), "😀".repeat(255))) {
            val directory = Files.createTempDirectory("restore13-preflight").toFile()
            try {
                val file = java.io.File(directory, "original.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                val rig = SyncRig(joinedSession("family-a"), setupProbe = SetupProbe { _, trusted ->
                    SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1"))
                })
                rig.awaitStartupRecovery()
                val baby = rig.babies.seed(localBaby().copy(clientUuid = "11111111-1111-4111-8111-111111111111", syncDirty = false))
                val record = rig.records.seed(localRecord(baby).copy(clientUuid = "22222222-2222-4222-8222-222222222222"))
                val row = MediaAssetEntity(clientUuid = "33333333-3333-4333-8333-333333333333",
                    recordId = record, localUri = file.path, mime = mime, byteSize = 3,
                    width = 1, height = 1, createdAt = 100, updatedAt = 100)
                rig.media.seed(row)
                val before = rig.media.listAllIncludingDeleted()
                val result = rig.port.startDisasterRecovery(TrustedEndpointProfile.systemPki("https://restore13.example.test"), "Owner", "Phone", "root")
                assertThat(result.exceptionOrNull()).isInstanceOf(LosslessRestoreCompatibilityException::class.java)
                assertThat(rig.backend.disasterRestoreStartRequestIds).isEmpty()
                assertThat(rig.backend.disasterRestoreManifestEntities).isEmpty()
                assertThat(rig.backend.disasterRestoreMediaUuids).isEmpty()
                assertThat(rig.media.listAllIncludingDeleted()).isEqualTo(before)
                assertThat(file.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
                assertThat(rig.conflictDetails.listFrozenMediaSpoolManifests()).isEmpty()
            } finally { directory.deleteRecursively() }
        }
    }

    @Test(timeout = 30_000)
    fun supportedSchema13MimeUploadsExactMetadataAndBytesWithoutNormalization() = runBlocking {
        for (mime in listOf("image/jpeg", "  ", "x".repeat(128), "😀".repeat(32))) {
            val directory = Files.createTempDirectory("restore13-supported").toFile()
            try {
                val file = java.io.File(directory, "original.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                val rig = SyncRig(joinedSession("family-a"), setupProbe = SetupProbe { _, trusted ->
                    SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1"))
                })
                rig.awaitStartupRecovery()
                val baby = rig.babies.seed(localBaby().copy(clientUuid = "11111111-1111-4111-8111-111111111111", syncDirty = false))
                val record = rig.records.seed(localRecord(baby).copy(clientUuid = "22222222-2222-4222-8222-222222222222"))
                val photo = "33333333-3333-4333-8333-333333333333"
                rig.media.seed(MediaAssetEntity(clientUuid = photo, recordId = record, localUri = file.path,
                    mime = mime, byteSize = 3, width = null, height = null, createdAt = 100, updatedAt = 100))
                rig.port.startDisasterRecovery(TrustedEndpointProfile.systemPki("https://restore13.example.test"), "Owner", "Phone", "root").getOrThrow()
                val uploaded = rig.backend.disasterRestoreManifestEntities.single().single { it.type == "media" }
                assertThat(Json.parseToJsonElement(uploaded.payloadJson).jsonObject.getValue("mime").jsonPrimitive.content).isEqualTo(mime)
                assertThat(rig.backend.disasterRestoreMediaBodies.single().second).isEqualTo(byteArrayOf(1, 2, 3))
                assertThat(rig.mediaFiles.prepareUploadCounts).isEmpty()
                assertThat(rig.media.getByClientUuid(photo)?.mime).isEqualTo(mime)
                assertThat(file.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
            } finally { directory.deleteRecursively() }
        }
    }

    @Test(timeout = 30_000)
    fun oldServerCannotServeTheNewPlanDomainOrPerformUpgradedRestore() = runBlocking {
        val rig = SyncRig(joinedSession("family-a"), setupProbe = SetupProbe { _, trusted ->
            SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty, setOf("causal_sync_v2"))
        })
        rig.awaitStartupRecovery()
        rig.backend.anonymousHealthResult = rig.backend.anonymousHealthResult.copy(
            capabilities = rig.backend.anonymousHealthResult.capabilities - "nursing_plan_intent_v1")
        val session = rig.preferences.current()
        rig.backend.nextHandshake = sourceCausalHandshake(
            com.lezi.babylog.sync.backend.SyncHandshakePrincipal(session.membershipId, session.deviceId, session.role), "directory-1")
            .copy(capabilities = setOf("causal_sync_v2"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
        val result = rig.port.startDisasterRecovery(TrustedEndpointProfile.systemPki("https://old13.example.test"), "Owner", "Phone", "root")
        assertThat(result.exceptionOrNull()).isInstanceOf(RestoreAuthorityUnsupportedException::class.java)
        assertThat(rig.backend.disasterRestoreStartRequestIds).isEmpty()
    }
}
