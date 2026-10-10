package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.sync.disasterrecovery.RestoreFileLifecycleOwner
import com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotRetirementPendingException
import com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotStore
import com.lezi.babylog.sync.session.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RestoreFileActivationTest {
    @Test(timeout = 30_000)
    fun confirmedCommitWithAmbiguousMissingSourcePreservesIntentAndRetriesOnlyLocalActivation() = runBlocking {
        val directory = Files.createTempDirectory("restore-confirmed-local-repair").toFile()
        try {
            val (rig, source) = seeded(directory)
            rig.port.startDisasterRecovery(ENDPOINT, "Owner", "Phone", "root").getOrThrow()
            val checkpoint = requireNotNull(rig.preferences.disasterRestoreCheckpoint.first())
            rig.media.update(requireNotNull(rig.media.getByClientUuid(PHOTO)).copy(mime = "image/png", syncDirty = true))
            source.writeBytes(byteArrayOf(7, 8, 9))
            assertThat(source.delete()).isTrue()
            rig.foreground.setForeground(false)
            val stopped = rig.port.commitDisasterRecovery("root")
            assertThat(stopped.exceptionOrNull()).hasMessageThat().contains("服务器已提交恢复")
            assertThat(rig.backend.disasterRestoreCommitRootPasswords).hasSize(1)
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()?.status).isEqualTo("committed")
            assertThat(rig.media.getByClientUuid(PHOTO)?.mime).isEqualTo("image/png")
            assertThat(rig.preferences.pendingReplicaResetPrevious()).isNotNull()
            source.writeBytes(BYTES) // Explicit repair restores the known captured byte generation.
            rig.backend.disasterRestoreCommitFailure = java.io.IOException("must not repost confirmed commit")
            val progress = rig.port.resumeDisasterRecovery().getOrThrow()
            assertThat(progress.status).isEqualTo("committed")
            assertThat(progress.localActivationReady).isTrue()
            rig.port.commitDisasterRecovery("").getOrThrow()
            assertThat(rig.backend.disasterRestoreCommitRootPasswords).hasSize(1)
            assertThat(rig.backend.disasterRestoreStartRequestIds).containsExactly(checkpoint.startRequestId)
            val current = requireNotNull(rig.media.getByClientUuid(PHOTO))
            assertThat(current.mime).isEqualTo("image/png")
            assertThat(current.syncDirty).isTrue()
            assertThat(current.sha256).isEqualTo("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81")
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            Unit
        } finally { directory.deleteRecursively() }
    }

    @Test(timeout = 30_000)
    fun sameTimestampRecordNullToLiteralNullEditStaysDirtyAfterActivation() = nullEdit(false)

    @Test(timeout = 30_000)
    fun sameTimestampPlanNullToLiteralNullEditStaysDirtyAfterActivation() = nullEdit(true)

    private fun nullEdit(plan: Boolean) = runBlocking {
        val directory = Files.createTempDirectory("restore-exact-null-activation").toFile()
        try {
            val (rig, _) = seeded(directory)
            val record = requireNotNull(rig.records.getByClientUuid(RECORD))
            rig.records.update(record.copy(note = null))
            val planUuid = "44444444-4444-4444-8444-444444444444"
            rig.carePlans.seed(localCarePlan(record.babyId).copy(clientUuid = planUuid, note = null))
            rig.port.startDisasterRecovery(ENDPOINT, "Owner", "Phone", "root").getOrThrow()
            if (plan) rig.carePlans.update(requireNotNull(rig.carePlans.getByClientUuid(planUuid)).copy(note = "null"))
            else rig.records.update(requireNotNull(rig.records.getByClientUuid(RECORD)).copy(note = "null"))
            rig.foreground.setForeground(false)
            rig.port.commitDisasterRecovery("root").getOrThrow()
            if (plan) {
                val current = requireNotNull(rig.carePlans.getByClientUuid(planUuid))
                assertThat(current.note).isEqualTo("null")
                assertThat(current.syncDirty).isTrue()
                assertThat(current.baseVersion).isNotNull()
            } else {
                val current = requireNotNull(rig.records.getByClientUuid(RECORD))
                assertThat(current.note).isEqualTo("null")
                assertThat(current.syncDirty).isTrue()
                assertThat(current.baseVersion).isNotNull()
                assertThat(current.updatedAt).isEqualTo(record.updatedAt)
            }
            Unit
        } finally { directory.deleteRecursively() }
    }

    @Test(timeout = 30_000)
    fun committedRestoreAdoptsItsVerifiedSolePhotoBeforeRetiringReplay() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-activation").toFile()
        try {
            val (rig, source) = seeded(directory)
            rig.port.startDisasterRecovery(ENDPOINT, "Owner", "Phone", "root").getOrThrow()
            val request = requireNotNull(rig.preferences.disasterRestoreCheckpoint.first()).startRequestId
            assertThat(source.delete()).isTrue()
            // Model backgrounding during commit; the automatic subsequent round is separately gated.
            rig.foreground.setForeground(false)
            rig.port.commitDisasterRecovery("root").getOrThrow()
            val media = requireNotNull(rig.media.getByClientUuid(PHOTO))
            assertThat(requireNotNull(rig.mediaFiles.readableFile(media.localUri)).readBytes()).isEqualTo(BYTES)
            assertThat(media.syncDirty).isFalse()
            assertThat(media.sha256).isEqualTo("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81")
            assertThat(rig.records.getByClientUuid(RECORD)?.syncDirty).isFalse()
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            val store = RestoreFileSnapshotStore(File(directory, "restore-snapshots"))
            assertThat(runCatching { store.completed(request) }.exceptionOrNull())
                .isInstanceOf(RestoreFileSnapshotRetirementPendingException::class.java)
        } finally { directory.deleteRecursively() }
    }

    @Test(timeout = 30_000)
    fun nextAuthoritySwitchPreservesPreviousOwnerOfALateEditedPhoto() = runBlocking {
        val directory = Files.createTempDirectory("restore-previous-file-owner").toFile()
        try {
            val (rig, source) = seeded(directory)
            rig.port.startDisasterRecovery(ENDPOINT, "Owner", "Phone", "root").getOrThrow()
            val firstRequest = requireNotNull(rig.preferences.disasterRestoreCheckpoint.first()).startRequestId
            assertThat(source.delete()).isTrue()
            rig.port.cancelDisasterRecovery().getOrThrow()
            val firstPath = requireNotNull(rig.media.getByClientUuid(PHOTO)).localUri
            val firstOwner = requireNotNull(rig.conflictDetails.getTransportJournal(RestoreFileLifecycleOwner.ownerKey(firstRequest)))
            rig.preferences.nextDisasterRestoreRequestIds = DisasterRestoreRequestIds(
                "dddddddd-dddd-4ddd-8ddd-dddddddddddd", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                "ffffffff-ffff-4fff-8fff-ffffffffffff")
            rig.backend.disasterRestoreStatus = rig.backend.disasterRestoreStatus.copy(batchId = "66666666-6666-4666-8666-666666666666")
            rig.backend.beforePutDisasterRestoreMedia = {
                rig.media.update(requireNotNull(rig.media.getByClientUuid(PHOTO)).copy(mime = "image/png", syncDirty = true))
            }
            rig.port.startDisasterRecovery(ENDPOINT, "Owner", "Phone", "root").getOrThrow()
            rig.foreground.setForeground(false)
            rig.port.commitDisasterRecovery("root").getOrThrow()
            val current = requireNotNull(rig.media.getByClientUuid(PHOTO))
            assertThat(current.localUri).isEqualTo(firstPath)
            assertThat(current.mime).isEqualTo("image/png")
            assertThat(current.syncDirty).isTrue()
            assertThat(rig.records.getByClientUuid(RECORD)?.syncDirty).isTrue()
            assertThat(rig.conflictDetails.getTransportJournal(firstOwner.journalKey)).isEqualTo(firstOwner)
            assertThat(requireNotNull(rig.mediaFiles.readableFile(firstPath)).readBytes()).isEqualTo(BYTES)
        } finally { directory.deleteRecursively() }
    }

    private suspend fun seeded(directory: File): Pair<SyncRig, File> {
        val source = File(directory, "original.jpg").apply { writeBytes(BYTES) }
        val rig = SyncRig(joinedSession("family-a"), appUpdateCacheDir = directory,
            setupProbe = SetupProbe { _, trusted -> SetupProbeResult.Ready(requireNotNull(trusted),
                SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1")) })
        rig.awaitStartupRecovery()
        val baby = rig.babies.seed(localBaby().copy(clientUuid = BABY))
        val record = rig.records.seed(localRecord(baby).copy(clientUuid = RECORD))
        rig.media.seed(MediaAssetEntity(clientUuid = PHOTO, recordId = record, localUri = source.path,
            mime = "image/jpeg", byteSize = 3, createdAt = 120, updatedAt = 120))
        return rig to source
    }

    companion object {
        private val ENDPOINT = TrustedEndpointProfile.systemPki("https://replacement.example.test")
        private val BYTES = byteArrayOf(1, 2, 3)
        private const val BABY = "11111111-1111-4111-8111-111111111111"
        private const val RECORD = "22222222-2222-4222-8222-222222222222"
        private const val PHOTO = "33333333-3333-4333-8333-333333333333"
    }
}
