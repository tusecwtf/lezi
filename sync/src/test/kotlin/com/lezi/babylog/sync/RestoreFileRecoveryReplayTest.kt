package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.sync.backend.DisasterRestoreMediaSpec
import com.lezi.babylog.sync.backend.SessionBootstrapResult
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotStore
import com.lezi.babylog.sync.engine.ForegroundSyncGate
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbe
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class RestoreFileRecoveryReplayTest {
    @Test(timeout = 30_000)
    fun lostCommitResponseKeepsOriginalSnapshotAndReplaysExactRequestAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-unknown-commit-restart").toFile()
        try {
            val source = File(directory, "original.jpg").apply { writeBytes(ORIGINAL_BYTES) }
            val backend = RecordingSyncBackend()
            val requests = mutableListOf<CommitRequest>()
            val lostResponse = IOException("connection closed before commit response")
            val transport = object : SyncBackend by backend {
                override suspend fun commitDisasterRestore(
                    endpoint: TrustedEndpointProfile,
                    batchId: String,
                    recoveryToken: String,
                    requestId: String,
                    rootPassword: String,
                ): SessionBootstrapResult {
                    requests += CommitRequest(endpoint, batchId, recoveryToken, requestId, rootPassword)
                    // Transport fault only: deliberately leave remote acceptance unknown.
                    throw lostResponse
                }
            }
            val rig = SyncRig(joinedSession("family-a"), syncBackend = transport,
                appUpdateCacheDir = directory, setupProbe = EMPTY_SERVER)
            rig.awaitStartupRecovery()
            seedPhoto(rig, source)
            start(rig.port).getOrThrow()
            val checkpoint = requireNotNull(rig.preferences.disasterRestoreCheckpoint.first())
            val originalSession = rig.preferences.current()
            val originalManifest = backend.disasterRestoreManifestEntities.single()
            val originalMedia = backend.disasterRestoreManifestMedia.single()
            val snapshotRoot = File(directory, "restore-snapshots")
            val store = RestoreFileSnapshotStore(snapshotRoot)
            val originalPointer = requireNotNull(store.completed(checkpoint.startRequestId))
            val captured = store.read(originalPointer)
            val originalFile = captured.ownedPath(captured.media.single())

            assertThat(rig.port.commitDisasterRecovery("commit-root").exceptionOrNull())
                .isSameInstanceAs(lostResponse)
            assertThat(rig.preferences.disasterRestoreCheckpoint.first())
                .isEqualTo(checkpoint.copy(status = "commit_uncertain"))
            assertThat(store.completed(checkpoint.startRequestId)).isEqualTo(originalPointer)
            assertThat(originalFile.readBytes()).isEqualTo(ORIGINAL_BYTES)
            assertThat(source.delete()).isTrue()
            rig.records.update(requireNotNull(rig.records.getByClientUuid(RECORD_UUID)).copy(note = "newer note"))
            val restarted = restart(rig, snapshotRoot, transport, startupBlockedByUnknownCommit = true)

            backend.disasterRestoreStatus = backend.disasterRestoreStatus.copy(status = "committed")
            val progress = restarted.resumeDisasterRecovery().getOrThrow()
            assertThat(progress.status).isEqualTo("committed")
            assertThat(progress.localActivationReady).isFalse()
            assertThat(restarted.commitDisasterRecovery("").exceptionOrNull()).hasMessageThat().contains("根密码")
            assertThat(requests).hasSize(1)
            assertThat(restarted.commitDisasterRecovery("commit-root").exceptionOrNull())
                .isSameInstanceAs(lostResponse)

            val expected = CommitRequest(ENDPOINT, checkpoint.batchId, "recovery-token-secret",
                checkpoint.commitRequestId, "commit-root")
            assertThat(requests).containsExactly(expected, expected).inOrder()
            assertThat(backend.disasterRestoreStartRequestIds).containsExactly(checkpoint.startRequestId)
            assertThat(backend.disasterRestoreManifestEntities).containsExactly(originalManifest)
            assertThat(backend.disasterRestoreManifestMedia).containsExactly(originalMedia)
            assertThat(backend.disasterRestoreMediaBodies.single().first).isEqualTo(PHOTO_UUID)
            assertThat(backend.disasterRestoreMediaBodies.single().second).isEqualTo(ORIGINAL_BYTES)
            assertThat(rig.preferences.disasterRestoreCheckpoint.first())
                .isEqualTo(checkpoint.copy(status = "commit_uncertain"))
            assertThat(rig.preferences.disasterRestoreToken()).isEqualTo("recovery-token-secret")
            assertThat(rig.preferences.current()).isEqualTo(originalSession)
            assertThat(store.completed(checkpoint.startRequestId)).isEqualTo(originalPointer)
            assertThat(store.read(originalPointer).manifestJson).isEqualTo(captured.manifestJson)
            assertThat(originalFile.readBytes()).isEqualTo(ORIGINAL_BYTES)
            assertThat(source.exists()).isFalse()
            assertThat(rig.records.getByClientUuid(RECORD_UUID)?.note).isEqualTo("newer note")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 30_000)
    fun completedCaptureRebindAfterRestartUploadsWhenOriginalPhotoDisappears() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-rebind-missing-source").toFile()
        try {
            val source = File(directory, "original.jpg").apply { writeBytes(ORIGINAL_BYTES) }
            val rig = SyncRig(joinedSession("family-a"), appUpdateCacheDir = directory, setupProbe = EMPTY_SERVER)
            rig.awaitStartupRecovery()
            seedPhoto(rig, source)
            val requestIds = rig.preferences.ensureDisasterRestoreRequestIds()
            rig.conflictDetails.restorePointerWriteFailuresRemaining = 1

            assertThat(start(rig.port).exceptionOrNull())
                .isSameInstanceAs(rig.conflictDetails.restorePointerWriteFailure)
            assertThat(rig.backend.disasterRestoreStartRequestIds).isEmpty()
            assertThat(rig.backend.disasterRestoreManifestEntities).isEmpty()
            assertThat(rig.backend.disasterRestoreMediaBodies).isEmpty()
            val snapshotRoot = File(directory, "restore-snapshots")
            val store = RestoreFileSnapshotStore(snapshotRoot)
            val originalPointer = requireNotNull(store.completed(requestIds.start))
            val originalManifest = store.read(originalPointer).manifestJson
            assertThat(source.delete()).isTrue()

            val retry = start(restart(rig, snapshotRoot)).getOrThrow()

            assertThat(retry.status).isEqualTo("ready_to_commit")
            assertThat(rig.backend.disasterRestoreStartRequestIds).containsExactly(requestIds.start)
            assertThat(rig.backend.disasterRestoreManifestMedia.single()).containsExactly(
                DisasterRestoreMediaSpec(PHOTO_UUID, 3, ORIGINAL_SHA256),
            )
            assertThat(rig.backend.disasterRestoreMediaBodies.single().first).isEqualTo(PHOTO_UUID)
            assertThat(rig.backend.disasterRestoreMediaBodies.single().second).isEqualTo(ORIGINAL_BYTES)
            assertThat(rig.preferences.ensureDisasterRestoreRequestIds()).isEqualTo(requestIds)
            assertThat(store.completed(requestIds.start)).isEqualTo(originalPointer)
            assertThat(store.read(originalPointer).manifestJson).isEqualTo(originalManifest)
            assertThat(source.exists()).isFalse()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 30_000)
    fun completedCaptureRebindAfterRestartUploadsOriginalSnapshotDespiteSourceEdits() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-rebind-edited-source").toFile()
        try {
            val source = File(directory, "original.jpg").apply { writeBytes(ORIGINAL_BYTES) }
            val rig = SyncRig(joinedSession("family-a"), appUpdateCacheDir = directory, setupProbe = EMPTY_SERVER)
            rig.awaitStartupRecovery()
            seedPhoto(rig, source)
            val requestIds = rig.preferences.ensureDisasterRestoreRequestIds()
            rig.conflictDetails.restorePointerWriteFailuresRemaining = 1

            val first = start(rig.port)

            assertThat(first.exceptionOrNull()).isSameInstanceAs(rig.conflictDetails.restorePointerWriteFailure)
            assertThat(rig.backend.disasterRestoreStartRequestIds).isEmpty()
            assertThat(rig.backend.disasterRestoreManifestEntities).isEmpty()
            assertThat(rig.backend.disasterRestoreMediaBodies).isEmpty()
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            val snapshotRoot = File(directory, "restore-snapshots")
            val store = RestoreFileSnapshotStore(snapshotRoot)
            val originalPointer = requireNotNull(store.completed(requestIds.start))
            val originalManifest = store.read(originalPointer).manifestJson

            val replacement = byteArrayOf(44, 55, 66)
            source.writeBytes(replacement)
            rig.records.update(requireNotNull(rig.records.getByClientUuid(RECORD_UUID)).copy(note = "newer note"))
            rig.media.update(requireNotNull(rig.media.getByClientUuid(PHOTO_UUID)).copy(mime = "image/png"))
            val restarted = restart(rig, snapshotRoot)

            val retry = start(restarted).getOrThrow()

            assertThat(retry.status).isEqualTo("ready_to_commit")
            assertThat(rig.backend.disasterRestoreStartRequestIds).containsExactly(requestIds.start)
            val manifest = rig.backend.disasterRestoreManifestEntities.single()
            assertThat(Json.parseToJsonElement(manifest.single { it.clientUuid == RECORD_UUID }.payloadJson)
                .jsonObject.getValue("note").jsonPrimitive.content).isEqualTo("captured note")
            assertThat(Json.parseToJsonElement(manifest.single { it.clientUuid == PHOTO_UUID }.payloadJson)
                .jsonObject.getValue("mime").jsonPrimitive.content).isEqualTo("image/jpeg")
            assertThat(rig.backend.disasterRestoreManifestMedia.single()).containsExactly(
                DisasterRestoreMediaSpec(PHOTO_UUID, 3, ORIGINAL_SHA256),
            )
            assertThat(rig.backend.disasterRestoreMediaBodies.single().first).isEqualTo(PHOTO_UUID)
            assertThat(rig.backend.disasterRestoreMediaBodies.single().second).isEqualTo(ORIGINAL_BYTES)
            assertThat(rig.preferences.ensureDisasterRestoreRequestIds()).isEqualTo(requestIds)
            assertThat(store.completed(requestIds.start)).isEqualTo(originalPointer)
            assertThat(store.read(originalPointer).manifestJson).isEqualTo(originalManifest)
            assertThat(source.readBytes()).isEqualTo(replacement)
            assertThat(rig.records.getByClientUuid(RECORD_UUID)?.note).isEqualTo("newer note")
            assertThat(rig.media.getByClientUuid(PHOTO_UUID)?.mime).isEqualTo("image/png")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 30_000)
    fun corruptCompletedPhotoAfterPointerWriteFailureStopsRestartBeforeRemoteStart() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-bind-restart").toFile()
        try {
            val source = File(directory, "original.jpg").apply { writeBytes(ORIGINAL_BYTES) }
            val rig = SyncRig(joinedSession("family-a"), appUpdateCacheDir = directory, setupProbe = EMPTY_SERVER)
            rig.awaitStartupRecovery()
            seedPhoto(rig, source)
            val requestIds = rig.preferences.ensureDisasterRestoreRequestIds()
            rig.conflictDetails.restorePointerWriteFailuresRemaining = 1

            val first = start(rig.port)

            assertThat(first.exceptionOrNull()).isSameInstanceAs(rig.conflictDetails.restorePointerWriteFailure)
            assertThat(rig.backend.disasterRestoreStartRequestIds).isEmpty()
            assertThat(rig.backend.disasterRestoreManifestEntities).isEmpty()
            assertThat(rig.backend.disasterRestoreMediaBodies).isEmpty()
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            val snapshotRoot = File(directory, "restore-snapshots")
            val store = RestoreFileSnapshotStore(snapshotRoot)
            val pointer = requireNotNull(store.completed(requestIds.start))
            val completed = store.read(pointer)
            val capturedPhoto = completed.media.single()
            assertThat(capturedPhoto.sha256).isEqualTo(ORIGINAL_SHA256)
            assertThat(completed.ownedPath(capturedPhoto).readBytes()).isEqualTo(ORIGINAL_BYTES)

            // Filesystem fault injection: change owned bytes without changing their length.
            completed.ownedPath(capturedPhoto).writeBytes(byteArrayOf(44, 55, 66))
            assertThat(source.delete()).isTrue()
            val restarted = restart(rig, snapshotRoot)

            val retry = start(restarted)

            assertThat(retry.isFailure).isTrue()
            assertThat(rig.backend.disasterRestoreStartRequestIds).isEmpty()
            assertThat(rig.backend.disasterRestoreManifestEntities).isEmpty()
            assertThat(rig.backend.disasterRestoreMediaBodies).isEmpty()
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            assertThat(rig.preferences.ensureDisasterRestoreRequestIds()).isEqualTo(requestIds)
            assertThat(store.completed(requestIds.start)).isEqualTo(pointer)
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun start(port: SyncPort) = port.startDisasterRecovery(
        ENDPOINT, "Owner", "Phone", "root",
    )

    private fun seedPhoto(rig: SyncRig, source: File) {
        val baby = rig.babies.seed(localBaby().copy(clientUuid = BABY_UUID))
        val record = rig.records.seed(localRecord(baby).copy(clientUuid = RECORD_UUID, note = "captured note"))
        rig.media.seed(MediaAssetEntity(
            recordId = record, clientUuid = PHOTO_UUID, kind = "log", localUri = source.path,
            mime = "image/jpeg", byteSize = ORIGINAL_BYTES.size.toLong(), createdAt = 120, updatedAt = 120,
        ))
    }

    /** New port and file-store objects reuse only the persisted filesystem, preferences and database seam. */
    private suspend fun restart(
        rig: SyncRig,
        snapshotRoot: File,
        backend: SyncBackend = rig.backend,
        startupBlockedByUnknownCommit: Boolean = false,
    ): SyncPort {
        val startup = TestPendingReplicaCleanupStore()
        val port: SyncPort = RealSyncPort(
            backend = backend,
            preferences = rig.preferences,
            setupProbe = EMPTY_SERVER,
            foregroundSyncGate = ForegroundSyncGate(),
            pendingPublishDao = rig.pendingPublish,
            recordDao = rig.records,
            carePlanDao = rig.carePlans,
            babyDao = rig.babies,
            mediaDao = rig.media,
            customItemDao = rig.customItems,
            familyDao = rig.families,
            clock = rig.clock,
            foregroundState = rig.foreground,
            mediaFiles = rig.mediaFiles,
            immutableMediaSpool = rig.immutableMediaSpool,
            mediaFileCleanup = rig.mediaFileCleanup,
            transactionRunner = rig.transactions,
            pendingReplicaCleanupStore = startup,
            fulfillmentCandidateDao = rig.fulfillmentCandidates,
            fulfillmentAuthoritySettlement = rig.fulfillmentAuthoritySettlement,
            wakeObservationDao = rig.wakeObservations,
            conflictSummaryDao = rig.conflictSummaries,
            conflictSnapshotCacheDao = rig.conflictDetails,
            sourceRelationDao = rig.sourceRelations,
            restoreSnapshotsDir = snapshotRoot,
        )
        if (startupBlockedByUnknownCommit) {
            withTimeout(5_000) { port.status().first { it == SyncStatus.Error } }
        } else {
            startup.firstLoad.await()
        }
        return port
    }

    private data class CommitRequest(
        val endpoint: TrustedEndpointProfile,
        val batchId: String,
        val recoveryToken: String,
        val requestId: String,
        val rootPassword: String,
    )

    private companion object {
        val ORIGINAL_BYTES = byteArrayOf(11, 22, 33)
        const val ORIGINAL_SHA256 = "2e579a55e9461f8583d3df536b94e1aa011d0b9eca4702559ae6dd1c015acb37"
        const val BABY_UUID = "11111111-1111-4111-8111-111111111111"
        const val RECORD_UUID = "22222222-2222-4222-8222-222222222222"
        const val PHOTO_UUID = "33333333-3333-4333-8333-333333333333"
        val ENDPOINT = TrustedEndpointProfile.systemPki("https://replacement.example.test")
        val EMPTY_SERVER = SetupProbe { _, trusted ->
            SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty,
                setOf("nursing_plan_intent_v1", "restore_authority_v1"))
        }
    }
}
