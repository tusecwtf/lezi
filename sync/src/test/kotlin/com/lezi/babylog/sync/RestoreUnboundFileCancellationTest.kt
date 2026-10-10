package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotRetirementPendingException
import com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotStore
import com.lezi.babylog.sync.session.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RestoreUnboundFileCancellationTest {
    @Test(timeout = 30_000)
    fun explicitCancelReclaimsCompletedNeverDispatchedDuplicate() = cancelUnbound(false)

    @Test(timeout = 30_000)
    fun explicitCancelAdoptsCompletedNeverDispatchedSoleCopy() = cancelUnbound(true)

    private fun cancelUnbound(removeSource: Boolean) = runBlocking {
        val directory = Files.createTempDirectory("restore-unbound-cancel").toFile()
        try {
            val (rig, original) = seeded(directory)
            rig.conflictDetails.restorePointerWriteFailuresRemaining = 1
            assertThat(start(rig).isFailure).isTrue()
            val request = rig.preferences.ensureDisasterRestoreRequestIds().start
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            assertThat(rig.backend.disasterRestoreStartRequestIds).isEmpty()
            val progress = rig.port.resumeDisasterRecovery().getOrThrow()
            assertThat(progress.status).isEqualTo("local_capture_pending")
            assertThat(rig.backend.disasterRestoreStartRequestIds).isEmpty()
            val before = requireNotNull(rig.media.getByClientUuid(PHOTO))
            if (removeSource) assertThat(original.delete()).isTrue()
            rig.port.cancelDisasterRecovery().getOrThrow()
            val after = requireNotNull(rig.media.getByClientUuid(PHOTO))
            if (removeSource) {
                assertThat(after.localUri).isNotEqualTo(before.localUri)
                assertThat(after.copy(localUri = before.localUri)).isEqualTo(before)
                assertThat(requireNotNull(rig.mediaFiles.readableFile(after.localUri)).readBytes()).isEqualTo(BYTES)
            } else assertThat(original.readBytes()).isEqualTo(BYTES)
            val store = RestoreFileSnapshotStore(File(directory, "restore-snapshots"))
            assertThat(runCatching { store.completed(request) }.exceptionOrNull())
                .isInstanceOf(RestoreFileSnapshotRetirementPendingException::class.java)
            assertThat(rig.backend.disasterRestoreStartRequestIds).isEmpty()
            Unit
        } finally { directory.deleteRecursively() }
    }

    @Test(timeout = 30_000)
    fun missingStartResponseCannotBeSilentlyLocallyAbandoned() = runBlocking {
        val directory = Files.createTempDirectory("restore-start-unknown").toFile()
        try {
            val (rig, original) = seeded(directory)
            rig.backend.disasterRestoreStartFailure = IOException("response lost after possible remote start")
            assertThat(start(rig).isFailure).isTrue()
            val ids = rig.preferences.ensureDisasterRestoreRequestIds()
            val store = RestoreFileSnapshotStore(File(directory, "restore-snapshots"))
            val pointer = requireNotNull(store.completed(ids.start))
            assertThat(rig.backend.disasterRestoreStartRequestIds).containsExactly(ids.start)
            assertThat(rig.port.resumeDisasterRecovery().getOrThrow().status).isEqualTo("start_unknown")
            assertThat(rig.backend.disasterRestoreStartRequestIds).containsExactly(ids.start)
            val cancelled = rig.port.cancelDisasterRecovery()
            assertThat(cancelled.exceptionOrNull()).hasMessageThat().contains("开始请求结果尚未确认")
            assertThat(rig.preferences.ensureDisasterRestoreRequestIds()).isEqualTo(ids)
            assertThat(store.completed(ids.start)).isEqualTo(pointer)
            assertThat(original.readBytes()).isEqualTo(BYTES)
            Unit
        } finally { directory.deleteRecursively() }
    }

    @Test(timeout = 30_000)
    fun lostStartResponseCannotMoveOriginalRequestToChangedTargetOrArguments() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("restore-start-bound-target").toFile()
        try {
            val (rig, _) = seeded(directory)
            rig.backend.disasterRestoreStartFailure = IOException("lost")
            assertThat(start(rig).isFailure).isTrue()
            val ids = rig.preferences.ensureDisasterRestoreRequestIds()
            val before = rig.conflictDetails.getTransportJournal(com.lezi.babylog.sync.disasterrecovery.RestoreSnapshotJournal.key(ids.start))
            val recreated = reopen(rig, directory)
            assertThat(recreated.resumeDisasterRecovery().getOrThrow().status).isEqualTo("start_unknown")
            val changedName = recreated.startDisasterRecovery(ENDPOINT, "Changed", "Phone", "root")
            assertThat(changedName.exceptionOrNull()).hasMessageThat().contains("原服务器")
            val changedTarget = recreated.startDisasterRecovery(
                TrustedEndpointProfile.systemPki("https://another.example.test"), "Owner", "Phone", "root")
            assertThat(changedTarget.exceptionOrNull()).hasMessageThat().contains("原服务器")
            val changedDevice = recreated.startDisasterRecovery(ENDPOINT, "Owner", "Other phone", "root")
            assertThat(changedDevice.exceptionOrNull()).hasMessageThat().contains("原服务器")
            val changedTrust = recreated.startDisasterRecovery(TrustedEndpointProfile.tofuSpki(ENDPOINT.origin,
                java.util.Base64.getEncoder().encodeToString(ByteArray(32))), "Owner", "Phone", "root")
            assertThat(changedTrust.exceptionOrNull()).hasMessageThat().contains("原服务器")
            assertThat(rig.backend.disasterRestoreStartRequestIds).containsExactly(ids.start)
            assertThat(rig.preferences.ensureDisasterRestoreRequestIds()).isEqualTo(ids)
            assertThat(rig.conflictDetails.getTransportJournal(com.lezi.babylog.sync.disasterrecovery.RestoreSnapshotJournal.key(ids.start))).isEqualTo(before)
            rig.backend.disasterRestoreStartFailure = null
            rig.preferences.saveSession(rig.preferences.current().copy(familyName = "Later local family name"))
            recreated.retryDisasterRecoveryStart("root").getOrThrow()
            assertThat(rig.backend.disasterRestoreStartRequestIds).containsExactly(ids.start, ids.start).inOrder()
        } finally { directory.deleteRecursively() }
    }

    @Test(timeout = 30_000)
    fun completedUnboundSnapshotContinuesAfterRestartWithoutOriginalSourcesOrFreshSummary() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("restore-unbound-continue").toFile()
        try {
            val (rig, original) = seeded(directory)
            rig.conflictDetails.restorePointerWriteFailuresRemaining = 1
            assertThat(start(rig).isFailure).isTrue()
            val ids = rig.preferences.ensureDisasterRestoreRequestIds()
            assertThat(original.delete()).isTrue()
            val recreated = reopen(rig, directory)
            assertThat(recreated.resumeDisasterRecovery().getOrThrow().status).isEqualTo("local_capture_pending")
            assertThat(recreated.prepareDisasterRecovery().getOrThrow().photos).isEqualTo(1)
            recreated.startDisasterRecovery(ENDPOINT, "Owner", "Phone", "root").getOrThrow()
            assertThat(rig.backend.disasterRestoreStartRequestIds).containsExactly(ids.start)
            assertThat(rig.backend.disasterRestoreMediaBodies.single().second).isEqualTo(BYTES)
        } finally { directory.deleteRecursively() }
    }

    @Test(timeout = 30_000)
    fun legacyPreparedBindingWithoutStartIntentRemainsUnknownAndUnchanged() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("restore-legacy-start-intent").toFile()
        try {
            val (rig, _) = seeded(directory)
            rig.backend.disasterRestoreStartFailure = IOException("lost")
            assertThat(start(rig).isFailure).isTrue()
            val ids = rig.preferences.ensureDisasterRestoreRequestIds()
            val key = com.lezi.babylog.sync.disasterrecovery.RestoreSnapshotJournal.key(ids.start)
            val row = requireNotNull(rig.conflictDetails.getTransportJournal(key))
            val value = kotlinx.serialization.json.Json.parseToJsonElement(row.payloadJson) as kotlinx.serialization.json.JsonObject
            val legacy = kotlinx.serialization.json.JsonObject(value - "start_intent").toString()
            rig.conflictDetails.putTransportJournal(key, legacy, row.contentEpoch)
            val recreated = reopen(rig, directory)
            assertThat(recreated.resumeDisasterRecovery().exceptionOrNull()).hasMessageThat().contains("受控修复")
            assertThat(recreated.retryDisasterRecoveryStart("root").exceptionOrNull()).hasMessageThat().contains("受控修复")
            assertThat(rig.backend.disasterRestoreStartRequestIds).containsExactly(ids.start)
            assertThat(rig.conflictDetails.getTransportJournal(key)?.payloadJson).isEqualTo(legacy)
            assertThat(rig.preferences.ensureDisasterRestoreRequestIds()).isEqualTo(ids)
        } finally { directory.deleteRecursively() }
    }

    private suspend fun reopen(rig: SyncRig, directory: File): RealSyncPort {
        val startup = TestPendingReplicaCleanupStore()
        val port = RealSyncPort(backend = rig.backend, preferences = rig.preferences,
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
            restoreSnapshotsDir = File(directory, "restore-snapshots"))
        kotlinx.coroutines.withTimeout(5_000) { startup.firstLoad.await() }
        return port
    }

    private suspend fun start(rig: SyncRig) = rig.port.startDisasterRecovery(ENDPOINT, "Owner", "Phone", "root")
    private suspend fun seeded(directory: File): Pair<SyncRig, File> {
        val source = File(directory, "source.jpg").apply { writeBytes(BYTES) }
        val rig = SyncRig(joinedSession("family-a"), appUpdateCacheDir = directory,
            setupProbe = SetupProbe { _, trusted -> SetupProbeResult.Ready(requireNotNull(trusted),
                SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1")) })
        rig.awaitStartupRecovery()
        val baby = rig.babies.seed(localBaby().copy(clientUuid = "11111111-1111-4111-8111-111111111111"))
        val record = rig.records.seed(localRecord(baby).copy(clientUuid = "22222222-2222-4222-8222-222222222222"))
        rig.media.seed(MediaAssetEntity(clientUuid = PHOTO, recordId = record, localUri = source.path,
            mime = "image/jpeg", byteSize = 3, createdAt = 1, updatedAt = 1))
        return rig to source
    }
    companion object {
        private val ENDPOINT = TrustedEndpointProfile.systemPki("https://replacement.example.test")
        private const val PHOTO = "33333333-3333-4333-8333-333333333333"
        private val BYTES = byteArrayOf(1, 2, 3)
    }
}
