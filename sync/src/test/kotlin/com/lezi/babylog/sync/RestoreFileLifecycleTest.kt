package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import java.io.File
import com.lezi.babylog.sync.session.*
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test

class RestoreFileLifecycleTest {
    @Test(timeout = 30_000)
    fun restartFinishesPreparedCompactRetirementBeforeAnyRemoteReplay() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-prepared-terminal").toFile()
        try {
            val rig = SyncRig(joinedSession("family-a"), appUpdateCacheDir = directory,
                setupProbe = SetupProbe { _, trusted -> SetupProbeResult.Ready(requireNotNull(trusted),
                    SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1")) })
            rig.awaitStartupRecovery()
            rig.babies.seed(localBaby().copy(clientUuid = "11111111-1111-4111-8111-111111111111"))
            rig.port.startDisasterRecovery(TrustedEndpointProfile.systemPki("https://replacement.example.test"),
                "Owner", "Phone", "root").getOrThrow()
            val request = requireNotNull(rig.preferences.disasterRestoreCheckpoint.first()).startRequestId
            val root = File(directory, "restore-snapshots")
            val store = com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotStore(root)
            store.prepareRetirement(requireNotNull(store.completed(request)),
                com.lezi.babylog.sync.disasterrecovery.RestoreFileRetirementReason.Cancelled)
            // Crash boundary: durable compact receipt exists; original Room pointer is still prepared.
            assertThat(rig.conflictDetails.listRestoreFileOwners()).isEmpty()
            rig.backend.disasterRestoreStatusFailure = java.io.IOException("must not recontact terminal batch")
            val startup = TestPendingReplicaCleanupStore()
            RealSyncPort(backend = rig.backend, preferences = rig.preferences,
                setupProbe = SetupProbe { _, trusted -> SetupProbeResult.Ready(requireNotNull(trusted),
                    SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1")) },
                foregroundSyncGate = com.lezi.babylog.sync.engine.ForegroundSyncGate(),
                pendingPublishDao = rig.pendingPublish, recordDao = rig.records, carePlanDao = rig.carePlans,
                babyDao = rig.babies, mediaDao = rig.media, customItemDao = rig.customItems,
                familyDao = rig.families, clock = rig.clock, foregroundState = rig.foreground,
                mediaFiles = rig.mediaFiles, immutableMediaSpool = rig.immutableMediaSpool,
                mediaFileCleanup = rig.mediaFileCleanup, transactionRunner = rig.transactions,
                pendingReplicaCleanupStore = startup, fulfillmentCandidateDao = rig.fulfillmentCandidates,
                fulfillmentAuthoritySettlement = rig.fulfillmentAuthoritySettlement,
                wakeObservationDao = rig.wakeObservations, conflictSummaryDao = rig.conflictSummaries,
                conflictSnapshotCacheDao = rig.conflictDetails, sourceRelationDao = rig.sourceRelations,
                restoreSnapshotsDir = root)
            kotlinx.coroutines.withTimeout(5_000) { startup.firstLoad.await() }
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            assertThat(rig.backend.disasterRestoreStartRequestIds).hasSize(1)
            assertThat(rig.backend.disasterRestoreCommitRootPasswords).isEmpty()
            assertThat(runCatching { com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotStore(root).completed(request) }
                .exceptionOrNull()).isInstanceOf(com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotRetirementPendingException::class.java)
            Unit
        } finally { directory.deleteRecursively() }
    }

    @Test(timeout = 30_000)
    fun unavailablePreparedFileSnapshotRetiresBeforeCheckpointIsForgotten() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-unavailable").toFile()
        try {
            val rig = SyncRig(joinedSession("family-a"), appUpdateCacheDir = directory,
                setupProbe = SetupProbe { _, trusted -> SetupProbeResult.Ready(requireNotNull(trusted),
                    SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1")) })
            rig.awaitStartupRecovery()
            rig.babies.seed(localBaby().copy(clientUuid = "11111111-1111-4111-8111-111111111111"))
            rig.port.startDisasterRecovery(TrustedEndpointProfile.systemPki("https://replacement.example.test"),
                "Owner", "Phone", "root").getOrThrow()
            val request = requireNotNull(rig.preferences.disasterRestoreCheckpoint.first()).startRequestId
            rig.backend.disasterRestoreStatusFailure = com.lezi.babylog.sync.backend.SyncHttpException(401)
            val expired = rig.port.resumeDisasterRecovery()
            assertThat(expired.exceptionOrNull()).hasMessageThat().contains("批次已失效")
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            assertThat(rig.preferences.current().accessToken).isEqualTo(joinedSession("family-a").accessToken)
            val store = com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotStore(File(directory, "restore-snapshots"))
            assertThat(runCatching { store.completed(request) }.exceptionOrNull())
                .isInstanceOf(com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotRetirementPendingException::class.java)
            assertThat(rig.backend.disasterRestoreCommitRootPasswords).isEmpty()
            Unit
        } finally { directory.deleteRecursively() }
    }

    @Test(timeout = 30_000)
    fun restartFinishesPruningAfterRoomOwnerCommittedBeforeAnyUnlink() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-prune-restart").toFile()
        try {
            val raw = byteArrayOf(11, 22, 33)
            val original = File(directory, "original.jpg").apply { writeBytes(raw) }
            val rig = SyncRig(joinedSession("family-a"), appUpdateCacheDir = directory,
                setupProbe = SetupProbe { _, trusted -> SetupProbeResult.Ready(requireNotNull(trusted),
                    SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1")) })
            rig.awaitStartupRecovery()
            val baby = rig.babies.seed(localBaby().copy(clientUuid = "11111111-1111-4111-8111-111111111111"))
            val record = rig.records.seed(localRecord(baby).copy(clientUuid = "22222222-2222-4222-8222-222222222222"))
            rig.media.seed(MediaAssetEntity(clientUuid = "33333333-3333-4333-8333-333333333333",
                recordId = record, localUri = original.path, mime = "image/jpeg", byteSize = 3,
                createdAt = 120, updatedAt = 120))
            rig.port.startDisasterRecovery(TrustedEndpointProfile.systemPki("https://replacement.example.test"),
                "Owner", "Phone", "root").getOrThrow()
            val root = File(directory, "restore-snapshots")
            val request = requireNotNull(rig.preferences.disasterRestoreCheckpoint.first()).startRequestId
            fun owner(crash: Boolean) = com.lezi.babylog.sync.disasterrecovery.RestoreFileLifecycleOwner(
                rig.conflictDetails, com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotStore(root),
                rig.transactions, rig.media, rig.mediaFiles, rig.mediaFileCleanup, rig.babies,
                currentFamilyId = { rig.preferences.current().familyId },
                fault = { if (crash) throw java.io.IOException("crash after committed prune pointer") })
            owner(false).retirePrepared(request,
                com.lezi.babylog.sync.disasterrecovery.RestoreFileRetirementReason.Cancelled)
            rig.preferences.clearDisasterRestoreCheckpoint()
            val failed = runCatching { owner(true).reclaimRetired(force = true) }
            assertThat(failed.exceptionOrNull()).hasMessageThat().contains("committed prune pointer")
            assertThat(root.walkTopDown().any { it.isFile && it.length() == 3L && it.readBytes().contentEquals(raw) }).isTrue()
            owner(false).reclaimRetired()
            assertThat(root.walkTopDown().any { it.isFile && it.length() == 3L && it.readBytes().contentEquals(raw) }).isFalse()
            assertThat(rig.conflictDetails.listRestoreFileOwners()).isEmpty()
            assertThat(original.readBytes()).isEqualTo(raw)
            Unit
        } finally { directory.deleteRecursively() }
    }

    @Test(timeout = 30_000)
    fun cancellationReclaimsOnlyTheVerifiedDuplicateSnapshotCopy() = cancellationBytes(removeOriginal = false)

    @Test(timeout = 30_000)
    fun cancellationRetainsTheOnlyReadableCopy() = cancellationBytes(removeOriginal = true)

    @Test(timeout = 30_000)
    fun cancellationAdoptsMissingUnchangedBytesWithoutChangingBusinessOrReceiptFields() =
        cancellationBytes(removeOriginal = true, requireAdoption = true)

    @Test(timeout = 30_000)
    fun changedMetadataAndSamePathReplacementThenDisappearanceNeverSelectsOldBytes() =
        cancellationBytes(removeOriginal = true, editMetadata = true)

    private fun cancellationBytes(removeOriginal: Boolean, requireAdoption: Boolean = false,
        editMetadata: Boolean = false) = runBlocking {
        val directory = Files.createTempDirectory("restore-file-cancel-bytes").toFile()
        try {
            val raw = byteArrayOf(11, 22, 33)
            val original = File(directory, "original.jpg").apply { writeBytes(raw) }
            val rig = SyncRig(joinedSession("family-a"), appUpdateCacheDir = directory,
                setupProbe = SetupProbe { _, trusted -> SetupProbeResult.Ready(requireNotNull(trusted),
                    SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1")) })
            rig.awaitStartupRecovery()
            val baby = rig.babies.seed(localBaby().copy(clientUuid = "11111111-1111-4111-8111-111111111111"))
            val record = rig.records.seed(localRecord(baby).copy(clientUuid = "22222222-2222-4222-8222-222222222222"))
            rig.media.seed(MediaAssetEntity(clientUuid = "33333333-3333-4333-8333-333333333333",
                recordId = record, localUri = original.path, mime = "image/jpeg", byteSize = 3,
                createdAt = 120, updatedAt = 120))
            rig.port.startDisasterRecovery(TrustedEndpointProfile.systemPki("https://replacement.example.test"),
                "Owner", "Phone", "root").getOrThrow()
            val photo = "33333333-3333-4333-8333-333333333333"
            if (editMetadata) {
                original.writeBytes(byteArrayOf(44, 55, 66)) // same path/length, later byte generation
                rig.media.update(requireNotNull(rig.media.getByClientUuid(photo)).copy(mime = "image/png", syncDirty = true))
            }
            val beforeCancel = requireNotNull(rig.media.getByClientUuid(photo))
            if (removeOriginal) assertThat(original.delete()).isTrue()
            rig.port.cancelDisasterRecovery().getOrThrow()
            val afterCancel = requireNotNull(rig.media.getByClientUuid(photo))
            if (requireAdoption) {
                assertThat(afterCancel.localUri).isNotEqualTo(original.path)
                assertThat(requireNotNull(rig.mediaFiles.readableFile(afterCancel.localUri)).readBytes()).isEqualTo(raw)
                assertThat(afterCancel.copy(localUri = beforeCancel.localUri)).isEqualTo(beforeCancel)
            }
            if (editMetadata) assertThat(afterCancel).isEqualTo(beforeCancel)
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            val retainedCopies = File(directory, "restore-snapshots").walkTopDown()
                .filter { it.isFile && it.length() == raw.size.toLong() && it.readBytes().contentEquals(raw) }.toList()
            if (removeOriginal) assertThat(retainedCopies).hasSize(1)
            else {
                assertThat(retainedCopies).isEmpty()
                assertThat(original.readBytes()).isEqualTo(raw)
            }
            Unit
        } finally { directory.deleteRecursively() }
    }

    @Test(timeout = 30_000)
    fun cancelRetiresFileReplayBeforeStartingAFreshSnapshot() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-lifecycle").toFile()
        try {
            val rig = SyncRig(joinedSession("family-a"), appUpdateCacheDir = directory,
                setupProbe = SetupProbe { _, trusted -> SetupProbeResult.Ready(requireNotNull(trusted),
                    SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1")) })
            rig.awaitStartupRecovery()
            val baby = rig.babies.seed(localBaby().copy(clientUuid = "11111111-1111-4111-8111-111111111111"))
            val recordUuid = "22222222-2222-4222-8222-222222222222"
            rig.records.seed(localRecord(baby).copy(clientUuid = recordUuid, note = "first snapshot"))
            val endpoint = TrustedEndpointProfile.systemPki("https://replacement.example.test")
            rig.port.startDisasterRecovery(endpoint, "Owner", "Phone", "root").getOrThrow()
            rig.port.cancelDisasterRecovery().getOrThrow()
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            assertThat(rig.records.getByClientUuid(recordUuid)?.note).isEqualTo("first snapshot")
            val changed = requireNotNull(rig.records.getByClientUuid(recordUuid)).copy(note = "second snapshot")
            rig.records.update(changed)
            rig.preferences.nextDisasterRestoreRequestIds = DisasterRestoreRequestIds(
                "dddddddd-dddd-4ddd-8ddd-dddddddddddd", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                "ffffffff-ffff-4fff-8fff-ffffffffffff")
            rig.port.startDisasterRecovery(endpoint, "Owner", "Phone", "root").getOrThrow()
            val second = rig.backend.disasterRestoreManifestEntities.last().single { it.clientUuid == recordUuid }
            assertThat(Json.parseToJsonElement(second.payloadJson).jsonObject["note"]?.jsonPrimitive?.content)
                .isEqualTo("second snapshot")
            assertThat(rig.backend.disasterRestoreStartRequestIds).hasSize(2)
            assertThat(rig.backend.disasterRestoreStartRequestIds.distinct()).hasSize(2)
        } finally { directory.deleteRecursively() }
    }
}
