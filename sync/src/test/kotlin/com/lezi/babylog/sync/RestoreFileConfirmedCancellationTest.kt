package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.sync.backend.DisasterRestoreStatus
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.disasterrecovery.RestoreFileLifecycleFaultPoint
import com.lezi.babylog.sync.disasterrecovery.RestoreFileLifecycleOwner
import com.lezi.babylog.sync.disasterrecovery.RestoreFileRetirementReason
import com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotStore
import com.lezi.babylog.sync.disasterrecovery.RestoreSnapshotJournal
import com.lezi.babylog.sync.session.DisasterRestoreCheckpoint
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbe
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class RestoreFileConfirmedCancellationTest {
    @Test(timeout = 30_000)
    fun confirmedCancellationSettlesUncertainCommitAndReclaimsOnlyDuplicateBytes() = withUncertainRestore { fixture ->
        val originalSession = fixture.rig.preferences.current()
        fixture.rig.port.cancelDisasterRecovery().getOrThrow()

        assertThat(fixture.rig.preferences.disasterRestoreCheckpoint.first()).isNull()
        assertThat(fixture.rig.preferences.disasterRestoreToken()).isEmpty()
        assertThat(fixture.rig.preferences.current()).isEqualTo(originalSession)
        assertThat(fixture.original.readBytes()).isEqualTo(RAW)
        assertThat(fixture.snapshotCopies()).isEmpty()
        assertThat(fixture.backend.calls).containsExactly(fixture.checkpoint.endpoint to fixture.checkpoint.batchId)
        assertThat(fixture.backend.delegate.disasterRestoreCommitRootPasswords).hasSize(1)
    }

    @Test(timeout = 30_000)
    fun confirmedCancellationOfUncertainCommitPreservesAndAdoptsTheSoleCopy() = withUncertainRestore { fixture ->
        val before = requireNotNull(fixture.rig.media.getByClientUuid(PHOTO))
        assertThat(fixture.original.delete()).isTrue()
        fixture.rig.port.cancelDisasterRecovery().getOrThrow()

        val after = requireNotNull(fixture.rig.media.getByClientUuid(PHOTO))
        assertThat(after.copy(localUri = before.localUri)).isEqualTo(before)
        assertThat(after.localUri).isNotEqualTo(before.localUri)
        assertThat(requireNotNull(fixture.rig.mediaFiles.readableFile(after.localUri)).readBytes()).isEqualTo(RAW)
        assertThat(fixture.snapshotCopies()).hasSize(1)
        assertThat(fixture.rig.preferences.disasterRestoreCheckpoint.first()).isNull()
    }

    @Test(timeout = 30_000)
    fun lostCancellationResponsePreservesUncertainCommitUntilExactRetrySucceeds() = withUncertainRestore { fixture ->
        val originalJournal = fixture.journal()
        fixture.backend.failure = IOException("cancel response lost")
        assertThat(fixture.rig.port.cancelDisasterRecovery().exceptionOrNull())
            .hasMessageThat().contains("cancel response lost")
        fixture.assertUnsettled(originalJournal)

        fixture.backend.failure = null
        fixture.rig.port.cancelDisasterRecovery().getOrThrow()
        assertThat(fixture.backend.calls).containsExactly(
            fixture.checkpoint.endpoint to fixture.checkpoint.batchId,
            fixture.checkpoint.endpoint to fixture.checkpoint.batchId,
        ).inOrder()
        assertThat(fixture.rig.preferences.disasterRestoreCheckpoint.first()).isNull()
    }

    @Test(timeout = 30_000)
    fun unknownOrUnconfirmedCancellationNeverSettlesUncertainCommit() = withUncertainRestore { fixture ->
        val originalJournal = fixture.journal()
        for (failure in listOf(SyncHttpException(401), SyncHttpException(404), SyncHttpException(410))) {
            fixture.backend.failure = failure
            assertThat(fixture.rig.port.cancelDisasterRecovery().isFailure).isTrue()
            fixture.assertUnsettled(originalJournal)
        }
        fixture.backend.failure = null
        fixture.backend.result = fixture.backend.delegate.disasterRestoreStatus.copy(status = "committed")
        assertThat(fixture.rig.port.cancelDisasterRecovery().isFailure).isTrue()
        fixture.assertUnsettled(originalJournal)
        for (reason in listOf(RestoreFileRetirementReason.Cancelled, RestoreFileRetirementReason.Unavailable)) {
            assertThat(runCatching { fixture.owner().retirePrepared(fixture.checkpoint.startRequestId, reason) }.isFailure)
                .isTrue()
            fixture.assertUnsettled(originalJournal)
        }
    }

    @Test(timeout = 30_000)
    fun cancellationForAnotherBatchCannotRetireUncertainCommit() = withUncertainRestore { fixture ->
        val originalJournal = fixture.journal()
        fixture.backend.result = fixture.backend.delegate.disasterRestoreStatus.copy(
            batchId = "different-batch", status = "cancelled",
        )
        assertThat(fixture.rig.port.cancelDisasterRecovery().isFailure).isTrue()
        fixture.assertUnsettled(originalJournal)
    }

    @Test(timeout = 30_000)
    fun changedTargetCannotDispatchCancellationForTheCapturedRequest() = withUncertainRestore { fixture ->
        val originalJournal = fixture.journal()
        val changed = fixture.checkpoint.copy(endpoint = TrustedEndpointProfile.systemPki("https://other.example.test"))
        fixture.rig.preferences.saveDisasterRestoreCheckpoint(changed, TOKEN)
        assertThat(fixture.rig.port.cancelDisasterRecovery().isFailure).isTrue()
        assertThat(fixture.backend.calls).isEmpty()
        assertThat(fixture.journal()).isEqualTo(originalJournal)
        assertThat(fixture.rig.preferences.disasterRestoreCheckpoint.first()).isEqualTo(changed)
        assertThat(fixture.snapshotCopies()).hasSize(1)
    }

    @Test(timeout = 30_000)
    fun changedRequestCannotDispatchCancellationForAnotherJournal() = withUncertainRestore { fixture ->
        val changed = JsonObject(fixture.journal() + ("request" to JsonPrimitive("another-request")))
        fixture.saveJournal(changed)
        assertThat(fixture.rig.port.cancelDisasterRecovery().isFailure).isTrue()
        assertThat(fixture.backend.calls).isEmpty()
        fixture.assertUnsettled(changed)
    }

    @Test(timeout = 30_000)
    fun authorityCommittedWhileCancellationWasInFlightIsNeverRetired() = withUncertainRestore { fixture ->
        val committed = JsonObject(fixture.journal() + mapOf(
            "phase" to JsonPrimitive("committed"),
            "target" to buildJsonObject {
                put("family", "family-a"); put("membership", "new-owner")
                put("device", "new-device"); put("generation", "new-generation")
                put("endpoint", fixture.checkpoint.endpoint.origin)
            },
        ))
        fixture.backend.beforeReturn = { fixture.saveJournal(committed) }
        assertThat(fixture.rig.port.cancelDisasterRecovery().isFailure).isTrue()
        fixture.assertUnsettled(committed)
        fixture.backend.beforeReturn = {}
        assertThat(fixture.rig.port.cancelDisasterRecovery().isFailure).isTrue()
        assertThat(fixture.backend.calls).hasSize(1)
        fixture.assertUnsettled(committed)
    }

    @Test(timeout = 30_000)
    fun checkpointChangedDuringCancellationCannotSettleTheNewBatch() = withUncertainRestore { fixture ->
        val originalJournal = fixture.journal()
        val changed = fixture.checkpoint.copy(batchId = "new-batch")
        fixture.backend.beforeReturn = { fixture.rig.preferences.saveDisasterRestoreCheckpoint(changed, TOKEN) }
        assertThat(fixture.rig.port.cancelDisasterRecovery().isFailure).isTrue()
        assertThat(fixture.rig.preferences.disasterRestoreCheckpoint.first()).isEqualTo(changed)
        assertThat(fixture.journal()).isEqualTo(originalJournal)
        assertThat(fixture.rig.conflictDetails.listRestoreFileOwners()).isEmpty()
        assertThat(fixture.snapshotCopies()).hasSize(1)
    }

    @Test(timeout = 30_000)
    fun familyChangedDuringCancellationCannotRetireThePreviousAuthority() = withUncertainRestore { fixture ->
        val originalJournal = fixture.journal()
        fixture.backend.beforeReturn = { fixture.rig.preferences.saveSession(joinedSession("family-b")) }
        assertThat(fixture.rig.port.cancelDisasterRecovery().isFailure).isTrue()
        fixture.assertUnsettled(originalJournal)
        assertThat(fixture.rig.preferences.current().familyId).isEqualTo("family-b")
    }

    @Test(timeout = 30_000)
    fun durableConfirmedCancellationRecoversBeforeFileRetirementWithoutAnotherRemoteCall() = withUncertainRestore { fixture ->
        val interrupted = fixture.owner { point ->
            if (point == RestoreFileLifecycleFaultPoint.AfterConfirmedCancellationCommitted) {
                throw IOException("interrupted after positive cancellation")
            }
        }
        assertThat(runCatching {
            interrupted.retireAfterConfirmedCancellation(fixture.checkpoint,
                currentCheckpoint = { fixture.rig.preferences.disasterRestoreCheckpoint.first() }) {
                fixture.backend.cancelDisasterRestore(fixture.checkpoint.endpoint, fixture.checkpoint.batchId, TOKEN)
            }
        }.exceptionOrNull()).hasMessageThat().contains("after positive cancellation")
        assertThat(fixture.journal()["phase"]?.jsonPrimitive?.content).isEqualTo("cancelled")
        assertThat(fixture.rig.conflictDetails.listRestoreFileOwners()).isEmpty()
        assertThat(fixture.store.preparedRetirement(requireNotNull(fixture.store.completed(fixture.checkpoint.startRequestId))))
            .isNull()

        fixture.backend.failure = IOException("must finish locally")
        fixture.rig.port.cancelDisasterRecovery().getOrThrow()
        assertThat(fixture.rig.preferences.disasterRestoreCheckpoint.first()).isNull()
        assertThat(fixture.backend.calls).hasSize(1)
        assertThat(fixture.original.readBytes()).isEqualTo(RAW)
        assertThat(fixture.snapshotCopies()).isEmpty()
    }

    private fun withUncertainRestore(block: suspend (Fixture) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("restore-confirmed-cancel").toFile()
        try {
            val backend = CancellationBackend()
            val rig = SyncRig(joinedSession("family-a"), syncBackend = backend, appUpdateCacheDir = directory,
                setupProbe = SetupProbe { _, trusted -> SetupProbeResult.Ready(requireNotNull(trusted),
                    SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1")) })
            rig.awaitStartupRecovery()
            val original = File(directory, "original.jpg").apply { writeBytes(RAW) }
            val baby = rig.babies.seed(localBaby().copy(clientUuid = "11111111-1111-4111-8111-111111111111"))
            val record = rig.records.seed(localRecord(baby).copy(clientUuid = "22222222-2222-4222-8222-222222222222"))
            rig.media.seed(MediaAssetEntity(clientUuid = PHOTO, recordId = record, localUri = original.path,
                mime = "image/jpeg", byteSize = RAW.size.toLong(), createdAt = 120, updatedAt = 120))
            rig.port.startDisasterRecovery(TrustedEndpointProfile.systemPki("https://replacement.example.test"),
                "Owner", "Phone", "root").getOrThrow()
            backend.delegate.disasterRestoreCommitFailure = IOException("commit response lost")
            assertThat(rig.port.commitDisasterRecovery("root").exceptionOrNull())
                .hasMessageThat().contains("commit response lost")
            val checkpoint = requireNotNull(rig.preferences.disasterRestoreCheckpoint.first())
            assertThat(checkpoint.status).isEqualTo("commit_uncertain")
            val fixture = Fixture(rig, backend, original, File(directory, "restore-snapshots"), checkpoint)
            assertThat(fixture.journal()["phase"]?.jsonPrimitive?.content).isEqualTo("commit_uncertain")
            block(fixture)
        } finally { directory.deleteRecursively() }
    }

    private class Fixture(
        val rig: SyncRig,
        val backend: CancellationBackend,
        val original: File,
        val root: File,
        val checkpoint: DisasterRestoreCheckpoint,
    ) {
        val store = RestoreFileSnapshotStore(root)
        suspend fun journal(): JsonObject = Json.parseToJsonElement(requireNotNull(
            rig.conflictDetails.getTransportJournal(RestoreSnapshotJournal.key(checkpoint.startRequestId)),
        ).payloadJson).jsonObject
        suspend fun saveJournal(value: JsonObject) = rig.conflictDetails.putTransportJournal(
            RestoreSnapshotJournal.key(checkpoint.startRequestId), value.toString(), 0,
        )
        fun owner(fault: (RestoreFileLifecycleFaultPoint) -> Unit = {}) = RestoreFileLifecycleOwner(
            rig.conflictDetails, store, rig.transactions, rig.media, rig.mediaFiles, rig.mediaFileCleanup, rig.babies,
            currentFamilyId = { rig.preferences.current().familyId }, fault = fault,
        )
        fun snapshotCopies(): List<File> = root.walkTopDown()
            .filter { it.isFile && it.length() == RAW.size.toLong() && it.readBytes().contentEquals(RAW) }.toList()
        suspend fun assertUnsettled(expectedJournal: JsonObject) {
            assertThat(journal()).isEqualTo(expectedJournal)
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isEqualTo(checkpoint)
            assertThat(rig.preferences.disasterRestoreToken()).isEqualTo(TOKEN)
            assertThat(rig.conflictDetails.listRestoreFileOwners()).isEmpty()
            assertThat(snapshotCopies()).hasSize(1)
        }
    }

    private class CancellationBackend(val delegate: RecordingSyncBackend = RecordingSyncBackend()) : SyncBackend by delegate {
        val calls = mutableListOf<Pair<TrustedEndpointProfile, String>>()
        var failure: Throwable? = null
        var result: DisasterRestoreStatus? = null
        var beforeReturn: suspend () -> Unit = {}

        override suspend fun cancelDisasterRestore(
            endpoint: TrustedEndpointProfile,
            batchId: String,
            recoveryToken: String,
        ): DisasterRestoreStatus {
            calls += endpoint to batchId
            beforeReturn()
            failure?.let { throw it }
            return result ?: delegate.cancelDisasterRestore(endpoint, batchId, recoveryToken)
        }
    }

    companion object {
        private const val PHOTO = "33333333-3333-4333-8333-333333333333"
        private const val TOKEN = "recovery-token-secret"
        private val RAW = byteArrayOf(11, 22, 33)
    }
}
