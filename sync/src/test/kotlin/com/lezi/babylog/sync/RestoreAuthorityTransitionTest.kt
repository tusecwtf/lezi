package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.session.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Public SyncPort transition regression: a local draft is never rebased onto a later pulled head. */
class RestoreAuthorityTransitionTest {
    @Test fun restoreBindsNewerDraftToExactCommittedSnapshotAndReplacesOldMutation() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://replacement.example.test")
        val rig = SyncRig(joinedSession("family-a"), setupProbe = SetupProbe { _, trusted ->
            SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1"))
        })
        rig.awaitStartupRecovery()
        rig.backend.disasterRestoreStatus = rig.backend.disasterRestoreStatus.copy(
            batchId = "11111111-1111-4111-8111-111111111111",
        )
        val babyId = rig.babies.seed(localBaby().copy(clientUuid = "33333333-3333-4333-8333-333333333333"))
        val uuid = "22222222-2222-4222-8222-222222222222"
        val old = localRecord(babyId).copy(clientUuid = uuid, baseVersion = "old-base", mutationId = "old-mutation")
        rig.records.seed(old)
        rig.port.startDisasterRecovery(endpoint, "Owner", "Phone", "root").getOrThrow()
        val captured = requireNotNull(rig.records.getByClientUuid(uuid))
        // Same timestamp is intentional: content equality, not only updatedAt, is required.
        rig.records.update(captured.copy(note = "written while upload waits"))
        val followup = RestoreFollowupTestGate(rig)
        try {
            rig.port.commitDisasterRecovery("root").getOrThrow()
            followup.awaitEntered()

            val current = requireNotNull(rig.records.getByClientUuid(uuid))
            assertThat(current.note).isEqualTo("written while upload waits")
            assertThat(current.updatedAt).isEqualTo(captured.updatedAt)
            assertThat(current.baseVersion).isEqualTo("e0bec011-0509-5e15-98e1-0f7e7a49ee87")
            assertThat(current.mutationId).isNotEqualTo("old-mutation")
            assertThat(current.mutationId).isNotNull()
            assertThat(current.syncDirty).isTrue()
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            assertThat(rig.preferences.pendingReplicaResetPrevious()).isNull()
        } finally { followup.resume() }
        rig.awaitAutomaticRecordPublication(uuid)
        assertThat(rig.records.getByClientUuid(uuid)?.note).isEqualTo("written while upload waits")
        assertThat(rig.records.getByClientUuid(uuid)?.updatedAt).isEqualTo(captured.updatedAt)
    }

    @Test fun interruptedCredentialWriteRetainsOriginalBatchUntilAuthenticatedReplay() = runTest {
        val rig = restoreRig()
        val uuid = "22222222-2222-4222-8222-222222222222"
        val id = rig.babies.seed(localBaby().copy(clientUuid = "33333333-3333-4333-8333-333333333333"))
        rig.records.seed(localRecord(id).copy(clientUuid = uuid, baseVersion = "old-base"))
        rig.port.startDisasterRecovery(restoreEndpoint, "Owner", "Phone", "root").getOrThrow()
        val batch = requireNotNull(rig.preferences.disasterRestoreCheckpoint.first())
        rig.preferences.failPendingReplicaCredentialWriteAttempts = 1
        assertThat(rig.port.commitDisasterRecovery("root").isFailure).isTrue()
        assertThat(rig.preferences.current().isJoined).isFalse()
        assertThat(rig.records.getByClientUuid(uuid)?.baseVersion).isEqualTo("old-base")
        assertThat(rig.port.sync(SyncTrigger.Foreground).isFailure).isTrue()
        assertThat(rig.preferences.disasterRestoreCheckpoint.first()?.batchId).isEqualTo(batch.batchId)
        rig.port.commitDisasterRecovery("root").getOrThrow()
        assertThat(rig.records.getByClientUuid(uuid)?.baseVersion)
            .isEqualTo("e0bec011-0509-5e15-98e1-0f7e7a49ee87")
        assertThat(rig.preferences.current().isJoined).isTrue()
        assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
    }

    @Test fun switchedRoomMarkerDoesNotRemintNewerDraftWhenSessionPublicationRetries() = runTest {
        val rig = restoreRig()
        val uuid = "22222222-2222-4222-8222-222222222222"
        val id = rig.babies.seed(localBaby().copy(clientUuid = "33333333-3333-4333-8333-333333333333"))
        rig.records.seed(localRecord(id).copy(clientUuid = uuid))
        rig.port.startDisasterRecovery(restoreEndpoint, "Owner", "Phone", "root").getOrThrow()
        rig.records.update(requireNotNull(rig.records.getByClientUuid(uuid)).copy(note = "newer"))
        rig.preferences.failCompleteRestoreSessionAttempts = 1
        assertThat(rig.port.commitDisasterRecovery("root").isFailure).isTrue()
        val switched = requireNotNull(rig.records.getByClientUuid(uuid))
        assertThat(switched.baseVersion).isEqualTo("e0bec011-0509-5e15-98e1-0f7e7a49ee87")
        assertThat(switched.mutationId).isNotNull()
        rig.port.commitDisasterRecovery("root").getOrThrow()
        assertThat(rig.records.getByClientUuid(uuid)).isEqualTo(switched)
    }


    @Test fun interruptedUploadReplaysPinnedBytesAndPreservesAttachmentOnlyReplacement() = runTest {
        val rig = restoreRig()
        val root = "22222222-2222-4222-8222-222222222222"
        val firstPhoto = "44444444-4444-4444-8444-444444444444"
        val nextPhoto = "55555555-5555-4555-8555-555555555555"
        val babyId = rig.babies.seed(localBaby().copy(clientUuid = "33333333-3333-4333-8333-333333333333"))
        val recordId = rig.records.seed(localRecord(babyId).copy(clientUuid = root))
        rig.mediaFiles.seedReadableSource("first.jpg", byteArrayOf(1))
        rig.media.seed(com.lezi.babylog.core.database.MediaAssetEntity(
            clientUuid = firstPhoto, recordId = recordId, kind = "log", localUri = "first.jpg",
            byteSize = 1, mime = "image/jpeg", createdAt = 120, updatedAt = 120,
        ))
        var interrupted = false
        rig.backend.beforePutDisasterRestoreMedia = {
            if (!interrupted) { interrupted = true; throw java.io.IOException("lost upload") }
            rig.backend.disasterRestoreStatus = rig.backend.disasterRestoreStatus.copy(status = "ready_to_commit")
        }
        val interruptedStart = rig.port.startDisasterRecovery(restoreEndpoint, "Owner", "Phone", "root")
        assertThat(interruptedStart.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)
        assertThat(interrupted).isTrue()
        val originalManifest = rig.backend.disasterRestoreManifestEntities.single()
        val oldMedia = requireNotNull(rig.media.getByClientUuid(firstPhoto))
        rig.media.update(oldMedia.copy(deletedAt = 121, updatedAt = 121))
        rig.mediaFiles.seedReadableSource("next.jpg", byteArrayOf(1))
        rig.media.seed(oldMedia.copy(id = 0, clientUuid = nextPhoto, localUri = "next.jpg", updatedAt = 121))
        rig.mediaFiles.preparedUploadBytes["first.jpg"] = byteArrayOf(9)
        rig.backend.disasterRestoreStatus = rig.backend.disasterRestoreStatus.copy(status = "manifest_staged")
        rig.port.resumeDisasterRecovery().getOrThrow()
        assertThat(rig.backend.disasterRestoreManifestEntities).containsExactly(originalManifest)
        assertThat(rig.backend.disasterRestoreMediaBodies.last().first).isEqualTo(firstPhoto)
        assertThat(rig.backend.disasterRestoreMediaBodies.last().second).isEqualTo(byteArrayOf(1))
        val followup = RestoreFollowupTestGate(rig)
        try {
            rig.port.commitDisasterRecovery("root").getOrThrow()
            followup.awaitEntered()

            val current = requireNotNull(rig.records.getByClientUuid(root))
            assertThat(current.updatedAt).isEqualTo(120)
            assertThat(current.syncDirty).isTrue()
            assertThat(current.baseVersion).isEqualTo("e0bec011-0509-5e15-98e1-0f7e7a49ee87")
            assertThat(rig.media.getByClientUuid(firstPhoto)?.deletedAt).isEqualTo(121)
            assertThat(rig.media.getByClientUuid(nextPhoto)?.localUri).isEqualTo("next.jpg")
            assertThat(rig.media.getByClientUuid(nextPhoto)?.syncDirty).isTrue()
        } finally { followup.resume() }
        rig.awaitAutomaticRecordPublication(root)
        assertThat(rig.media.getByClientUuid(firstPhoto)?.deletedAt).isEqualTo(121)
        val publishedPhoto = requireNotNull(rig.media.getByClientUuid(nextPhoto))
        assertThat(publishedPhoto.deletedAt).isNull()
        assertThat(requireNotNull(rig.mediaFiles.readableFile(publishedPhoto.localUri)).readBytes())
            .isEqualTo(byteArrayOf(1))
    }

    private val restoreEndpoint = TrustedEndpointProfile.systemPki("https://replacement.example.test")
    private suspend fun restoreRig(): SyncRig {
        val rig = SyncRig(joinedSession("family-a"), setupProbe = SetupProbe { _, trusted ->
            SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1"))
        })
        rig.awaitStartupRecovery()
        rig.backend.disasterRestoreStatus = rig.backend.disasterRestoreStatus.copy(
            batchId = "11111111-1111-4111-8111-111111111111",
        )
        return rig
    }
}
