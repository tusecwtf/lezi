package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.testPreparedMedia
import com.lezi.babylog.sync.disasterrecovery.*
import com.lezi.babylog.sync.engine.ForegroundSyncGate
import com.lezi.babylog.sync.media.*
import com.lezi.babylog.sync.session.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Test

/** Public file-backed restores and explicit historical spool controls, with durable DAOs across restart. */
class ExpiredRestoreArtifactRetirementTest {
    @Test fun expiredCapturedRestoreReclaimsDedicatedSnapshotWhenCanonicalBytesSurvive() = runBlocking {
        for (code in listOf(401, 404, 410)) fixture { rig, _, directory ->
            val captured = capture(rig, directory)
            val before = requireNotNull(rig.media.getByClientUuid(PHOTO))
            rig.backend.disasterRestoreStatusFailure = SyncHttpException(code)

            assertThat(rig.port.resumeDisasterRecovery().exceptionOrNull()).hasMessageThat().contains("批次已失效")

            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            assertThat(rig.preferences.disasterRestoreToken()).isEmpty()
            assertThat(rig.conflictDetails.getTransportJournal(captured.key)).isNull()
            assertThat(rig.conflictDetails.listRestoreFileOwners()).isEmpty()
            assertThat(rig.conflictDetails.listFrozenMediaSpoolManifests()).isEmpty()
            assertThat(captured.directory.exists()).isFalse()
            assertThat(File(before.localUri).readBytes()).isEqualTo(RAW)
            assertThat(rig.media.getByClientUuid(PHOTO)).isEqualTo(before)
            assertThat(rig.preferences.current().familyId).isEqualTo("family-a")
            assertThat(rig.backend.disasterRestoreCommitRootPasswords).isEmpty()
            assertTerminalCannotReplay(directory, captured.request)
        }
    }

    @Test fun restartFinishesLocalRetirementWhenCheckpointClearFailedAfterDurableOwner() = runBlocking {
        fixture { rig, _, directory ->
            val captured = capture(rig, directory)
            rig.preferences.failClearDisasterRestoreCheckpointAttempts = 1
            rig.backend.disasterRestoreStatusFailure = SyncHttpException(401)
            assertThat(rig.port.resumeDisasterRecovery().exceptionOrNull())
                .hasMessageThat().contains("checkpoint clear failure")
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isEqualTo(captured.checkpoint)
            assertThat(journal(rig, captured.key).getValue("phase").jsonPrimitive.content).isEqualTo("retiring")
            assertThat(rig.conflictDetails.listRestoreFileOwners()).hasSize(1)
            assertCompacted(captured)

            val restarted = restart(rig, directory)

            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            assertThat(rig.conflictDetails.getTransportJournal(captured.key)).isNull()
            assertThat(rig.conflictDetails.listRestoreFileOwners()).isEmpty()
            assertThat(captured.directory.exists()).isFalse()
            assertThat(restarted.resumeDisasterRecovery().isFailure).isTrue()
            assertThat(rig.backend.disasterRestoreStartRequestIds).hasSize(1)
            assertThat(rig.backend.disasterRestoreCommitRootPasswords).isEmpty()
            assertThat(rig.preferences.current().familyId).isEqualTo("family-a")
        }
    }

    @Test fun unavailableTokenNeverRetiresAnUncertainOrAcknowledgedCommit() = runBlocking {
        for (phase in listOf("preference_uncertain", "request_uncertain", "acknowledged")) {
            for (code in listOf(401, 404, 410)) fixture { rig, _, directory ->
                val captured = capture(rig, directory)
                if (phase == "preference_uncertain") {
                    // Durable preference boundary precedes the Room phase write and dispatch.
                    rig.preferences.saveDisasterRestoreCheckpoint(captured.checkpoint.copy(status = "commit_uncertain"),
                        rig.preferences.disasterRestoreToken())
                } else {
                    if (phase == "request_uncertain") rig.backend.disasterRestoreCommitFailure = IOException("reply lost")
                    else rig.preferences.failPendingReplicaCredentialWriteAttempts = 1
                    assertThat(rig.port.commitDisasterRecovery("root").isFailure).isTrue()
                }
                val checkpoint = requireNotNull(rig.preferences.disasterRestoreCheckpoint.first())
                val snapshotRow = rig.conflictDetails.getTransportJournal(captured.key)
                val files = inventory(captured.directory)
                rig.backend.disasterRestoreStatusFailure = SyncHttpException(code)

                assertThat(rig.port.resumeDisasterRecovery().isFailure).isTrue()

                assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isEqualTo(checkpoint)
                assertThat(rig.preferences.disasterRestoreToken()).isNotEmpty()
                assertThat(rig.conflictDetails.getTransportJournal(captured.key)).isEqualTo(snapshotRow)
                assertThat(rig.conflictDetails.listRestoreFileOwners()).isEmpty()
                assertThat(rig.conflictDetails.listFrozenMediaSpoolManifests()).isEmpty()
                assertThat(inventory(captured.directory)).isEqualTo(files)
                assertThat(captured.store.ownedFile(captured.snapshot, captured.snapshot.media.single()).readBytes())
                    .isEqualTo(RAW)
                restart(rig, directory, expectBlocked = true)
                assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isEqualTo(checkpoint)
                assertThat(rig.conflictDetails.getTransportJournal(captured.key)).isEqualTo(snapshotRow)
                assertThat(inventory(captured.directory)).isEqualTo(files)
            }
        }
    }

    @Test fun terminalSoleCopySurvivesRestartUntilVerifiedCanonicalReplacementExists() = runBlocking {
        fixture { rig, _, directory ->
            val captured = capture(rig, directory)
            val before = requireNotNull(rig.media.getByClientUuid(PHOTO))
            val source = File(before.localUri)
            check(source.delete())
            rig.backend.disasterRestoreStatusFailure = SyncHttpException(404)
            assertThat(rig.port.resumeDisasterRecovery().isFailure).isTrue()
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            val adopted = requireNotNull(rig.media.getByClientUuid(PHOTO))
            assertThat(adopted.localUri).isEqualTo(captured.media.path)
            assertThat(adopted.copy(localUri = before.localUri)).isEqualTo(before)
            val restarted = restart(rig, directory)
            assertRetainedBytes(rig, captured)
            assertThat(restarted.resumeDisasterRecovery().isFailure).isTrue()
            assertThat(restarted.commitDisasterRecovery("root").isFailure).isTrue()
            assertThat(rig.backend.disasterRestoreCommitRootPasswords).isEmpty()

            // The current row must name the replacement; a file at its former path is not enough.
            source.writeBytes(byteArrayOf(9, 9, 9))
            rig.media.update(adopted.copy(localUri = source.path))
            lifecycle(rig, directory).reclaimRetired(force = true)
            restart(rig, directory)
            assertRetainedBytes(rig, captured) // Equal length does not establish equal bytes.
            source.writeBytes(RAW)
            lifecycle(rig, directory).reclaimRetired(force = true)
            restart(rig, directory)
            assertThat(captured.directory.exists()).isFalse()
            assertThat(rig.conflictDetails.getTransportJournal(captured.key)).isNull()
            assertThat(rig.conflictDetails.listRestoreFileOwners()).isEmpty()
            assertThat(source.readBytes()).isEqualTo(RAW)
        }
    }

    @Test fun deletedCurrentPhotoAllowsTerminalSoleCopyRetirementWithoutRecreatingTheFact() = runBlocking {
        fixture { rig, _, directory ->
            val captured = capture(rig, directory)
            val photo = requireNotNull(rig.media.getByClientUuid(PHOTO))
            check(File(photo.localUri).delete())
            rig.backend.disasterRestoreStatusFailure = SyncHttpException(410)
            assertThat(rig.port.resumeDisasterRecovery().isFailure).isTrue()
            assertRetainedBytes(rig, captured)
            val deleted = requireNotNull(rig.media.getByClientUuid(PHOTO)).copy(deletedAt = 121, updatedAt = 121)
            rig.media.update(deleted)
            lifecycle(rig, directory).reclaimRetired(force = true)
            restart(rig, directory)
            assertThat(captured.directory.exists()).isFalse()
            assertThat(rig.media.getByClientUuid(PHOTO)).isEqualTo(deleted)
            assertThat(rig.conflictDetails.listRestoreFileOwners()).isEmpty()
            assertThat(rig.conflictDetails.listFrozenMediaSpoolManifests()).isEmpty()
        }
    }

    @Test fun actualUnlinkFailureKeepsDurablePruneOwnerAndRestartRetriesTheExactFiles() = runBlocking {
        fixture { rig, _, directory ->
            val captured = capture(rig, directory)
            // Enter the same terminal owner used by an unavailable prepared batch, then interrupt
            // after the pruning owner commits so the failure is the actual owned-media unlink.
            lifecycle(rig, directory).retirePrepared(captured.request, RestoreFileRetirementReason.Unavailable)
            rig.preferences.clearDisasterRestoreCheckpoint()
            val failingOwner = lifecycle(rig, directory) { point ->
                if (point == RestoreFileLifecycleFaultPoint.AfterPruneOwnerCommitted)
                    check(captured.directory.setWritable(false, false))
            }
            val failure = try { runCatching { failingOwner.reclaimRetired(force = true) }.exceptionOrNull() }
                finally { check(captured.directory.setWritable(true, true)) }
            assertThat(failure).isInstanceOf(java.nio.file.AccessDeniedException::class.java)
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            assertThat(journal(rig, RestoreFileLifecycleOwner.ownerKey(captured.request))
                .getValue("phase").jsonPrimitive.content).isEqualTo("pruning")
            assertThat(captured.media.readBytes()).isEqualTo(RAW)
            assertThat(rig.conflictDetails.listFrozenMediaSpoolManifests()).isEmpty()

            restart(rig, directory)

            assertThat(captured.directory.exists()).isFalse()
            assertThat(rig.conflictDetails.listRestoreFileOwners()).isEmpty()
            assertThat(rig.conflictDetails.getTransportJournal(captured.key)).isNull()
            assertThat(File(requireNotNull(rig.media.getByClientUuid(PHOTO)).localUri).readBytes()).isEqualTo(RAW)
        }
    }

    @Test fun repeatedUniqueUnavailableBatchesKeepOnlyBoundedTerminalReceipts() = runBlocking {
        fixture { rig, _, directory ->
            val requests = mutableSetOf<String>()
            for (code in listOf(401, 404, 410, 401, 404, 410)) {
                rig.preferences.nextDisasterRestoreRequestIds = newRequestIds()
                rig.backend.disasterRestoreStatusFailure = null
                val captured = capture(rig, directory)
                check(requests.add(captured.request))
                rig.backend.disasterRestoreStatusFailure = SyncHttpException(code)
                assertThat(rig.port.resumeDisasterRecovery().isFailure).isTrue()
                assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
                assertThat(rig.conflictDetails.listFrozenMediaSpoolManifests()).isEmpty()
                assertThat(rig.conflictDetails.listRestoreFileOwners()).isEmpty()
                assertThat(rig.conflictDetails.getTransportJournal(RestoreArtifactRetirement.KEY)).isNull()
                requests.forEach { request ->
                    assertThat(rig.conflictDetails.getTransportJournal(RestoreSnapshotJournal.key(request))).isNull()
                    assertTerminalCannotReplay(directory, request)
                }
                val retained = requireNotNull(File(directory, "restore-snapshots").listFiles()).toList()
                assertThat(retained.map { it.name }).containsExactlyElementsIn(requests.map { "$it.terminal" })
                assertThat(retained.all { it.isFile && it.length() < 1024 }).isTrue()
                assertThat(File(directory, "spool").listFiles()?.toList().orEmpty()).isEmpty()
            }
        }
    }

    @Test fun changedFamilyCannotResumeOrRetireTheOtherFamilysPreparedSnapshot() = runBlocking {
        fixture { rig, _, directory ->
            val captured = capture(rig, directory)
            val snapshotRow = rig.conflictDetails.getTransportJournal(captured.key)
            val files = inventory(captured.directory)
            rig.preferences.saveSession(rig.preferences.current().copy(familyId = "family-b"))
            rig.backend.disasterRestoreStatusFailure = SyncHttpException(401)
            assertThat(rig.port.resumeDisasterRecovery().exceptionOrNull()).hasMessageThat().contains("本机家庭身份已变化")
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isEqualTo(captured.checkpoint)
            assertThat(rig.conflictDetails.listRestoreFileOwners()).isEmpty()
            assertThat(inventory(captured.directory)).isEqualTo(files)
            val restarted = restart(rig, directory)
            assertThat(restarted.resumeDisasterRecovery().exceptionOrNull()).hasMessageThat().contains("本机家庭身份已变化")
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isEqualTo(captured.checkpoint)
            assertThat(rig.conflictDetails.getTransportJournal(captured.key)).isEqualTo(snapshotRow)
            assertThat(inventory(captured.directory)).isEqualTo(files)
            assertThat(rig.backend.disasterRestoreCommitRootPasswords).isEmpty()
        }
    }

    @Test fun publishedRestoreDropsFullFileSnapshotWhileSolePhotoStaysUnderCompactOwner() = runBlocking {
        fixture { rig, _, directory ->
            val record = requireNotNull(rig.records.getByClientUuid(RECORD))
            rig.records.update(record.copy(note = "captured care note ".repeat(256)))
            val captured = capture(rig, directory)
            val before = inventory(captured.directory).values.sumOf { it.size }
            check(File(requireNotNull(rig.media.getByClientUuid(PHOTO)).localUri).delete())
            rig.foreground.setForeground(false)
            var compactAtClear = false
            rig.preferences.afterClearDisasterRestoreCheckpoint = {
                compactAtClear = !File(captured.directory, "manifest.json").exists() &&
                    !File(captured.directory, "sources.json").exists() && captured.media.exists()
            }

            rig.port.commitDisasterRecovery("root").getOrThrow()

            assertThat(compactAtClear).isTrue()
            assertThat(rig.preferences.current().isJoined).isTrue()
            assertThat(rig.preferences.pendingReplicaResetPrevious()).isNull()
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            assertThat(rig.records.getByClientUuid(RECORD)?.baseVersion).isEqualTo("e0bec011-0509-5e15-98e1-0f7e7a49ee87")
            assertThat(rig.conflictDetails.getTransportJournal(captured.key)).isNull()
            assertThat(rig.conflictDetails.listFrozenMediaSpoolManifests()).isEmpty()
            assertThat(rig.media.getByClientUuid(PHOTO)?.localUri).isEqualTo(captured.media.path)
            assertRetainedBytes(rig, captured)
            assertThat(inventory(captured.directory).values.sumOf { it.size }).isLessThan(before)
            assertThat(rig.conflictDetails.journalPayloadBytesForTest(RestoreFileLifecycleOwner.ownerKey(captured.request)))
                .isLessThan(1024)
        }
    }

    @Test fun ordinaryCaptureProofIsNotACompactableRestoreSnapshot() = runBlocking {
        fixture { rig, spool, directory ->
            val photo = requireNotNull(rig.media.getByClientUuid(PHOTO))
            val mutation = java.util.UUID.randomUUID().toString()
            val group = spool.freezeGroup(mutation, listOf(ImmutableMediaSpoolSource(
                PHOTO, CausalMediaRole.Log, photo.localUri,
                PublishedMediaIdentity("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                    3, "image/jpeg", 1, 1))))
            rig.conflictDetails.putFrozenMediaSpoolManifest(mutation, encodeImmutableMediaSpoolGroup(group), 0)
            val captureKey = "media-freeze-capture-v1:$mutation"
            val capture = "{\"format\":1,\"source\":{\"proof\":\"retained\"},\"current\":{\"proof\":\"retained\"}}"
            rig.conflictDetails.putTransportJournal(captureKey, capture, 0)
            rig.conflictDetails.putTransportJournal(RestoreArtifactRetirement.KEY,
                RestoreArtifactRetirement.encode(captureKey, listOf(mutation)), 0)
            check(File(photo.localUri).delete())
            val reopened = FileImmutableMediaSpool(rig.mediaFiles, File(directory, "spool"), 1024 * 1024, 1024)
            restart(rig, directory)
            assertThat(rig.conflictDetails.getTransportJournal(captureKey)?.payloadJson).isEqualTo(capture)
            assertThat(reopened.open(mutation, group.items.single()).openStream().use { it.readBytes() })
                .isEqualTo(byteArrayOf(1, 2, 3))
        }
    }

    @Test fun historicalEmptySnapshotReferenceMergesExplicitCancellationWithoutLosingDonors() = runBlocking {
        for (mergeCancellation in listOf(false, true)) fixture { rig, spool, directory ->
            val photo = requireNotNull(rig.media.getByClientUuid(PHOTO))
            val old = seedPlainGroup(rig, spool)
            rig.conflictDetails.putTransportJournal(RestoreArtifactRetirement.KEY,
                RestoreArtifactRetirement.encode("", listOf(old.mutationId)), 0)
            check(File(photo.localUri).delete())
            restart(rig, directory)
            assertThat(spool.open(old.mutationId, old.items.single()).openStream().use { it.readBytes() }).isEqualTo(RAW)
            if (mergeCancellation) {
                File(photo.localUri).writeBytes(RAW)
                val historical = seedHistoricalSnapshot(rig, spool)
                check(File(photo.localUri).delete())
                val ids = rig.conflictDetails.listFrozenMediaSpoolManifests().map {
                    com.lezi.babylog.sync.engine.decodeFrozenMediaSpoolManifest(it.payloadJson).mutationId
                }
                rig.port.cancelDisasterRecovery().getOrThrow()
                val marker = requireNotNull(rig.conflictDetails.getTransportJournal(RestoreArtifactRetirement.KEY)).payloadJson
                ids.forEach { assertThat(marker).contains(it) }
                assertThat(marker).contains("\"snapshot_key\":\"\"")
                assertThat(rig.conflictDetails.getTransportJournal(RestoreSnapshotJournal.key(historical.startRequestId))).isNull()
                assertThat(spool.recoverGroup(old.mutationId)).isNotNull()
            }
            File(photo.localUri).writeBytes(RAW)
            restart(rig, directory)
            assertThat(rig.conflictDetails.getTransportJournal(RestoreArtifactRetirement.KEY)).isNull()
            assertThat(rig.conflictDetails.listFrozenMediaSpoolManifests()).isEmpty()
            assertThat(spool.recoverGroup(old.mutationId)).isNull()
            assertThat(rig.conflictDetails.listRestoreFileOwners()).isEmpty()
        }
    }

    @Test fun ordinaryQuarantineMergePreservesAnEmptySnapshotReferenceAndAllDonors() = runBlocking {
        fixture { rig, spool, directory ->
            val photo = requireNotNull(rig.media.getByClientUuid(PHOTO))
            val donorUuid = java.util.UUID.randomUUID().toString()
            val donorFile = File(directory, "other-photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            rig.media.seed(photo.copy(id = 0, clientUuid = donorUuid, localUri = donorFile.path,
                sha256 = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"))
            val old = seedPlainGroup(rig, spool, donorUuid)
            val unbound = seedPlainGroup(rig, spool)
            rig.conflictDetails.putTransportJournal(RestoreArtifactRetirement.KEY,
                RestoreArtifactRetirement.encode("", listOf(old.mutationId)), 0)
            val record = requireNotNull(rig.records.getByClientUuid(RECORD))
            rig.records.update(record.copy(syncDirty = true, mutationId = unbound.mutationId))
            rig.media.update(photo.copy(sha256 = unbound.items.single().sha256))
            check(File(photo.localUri).delete())
            check(donorFile.delete())
            rig.backend.onCausalCommit = { throw IOException("hold subsequent publication for donor inspection") }
            rig.port.sync(SyncTrigger.LocalWrite)
            val marker = requireNotNull(rig.conflictDetails.getTransportJournal(RestoreArtifactRetirement.KEY)).payloadJson
            assertThat(marker).contains("\"snapshot_key\":\"\"")
            assertThat(marker).contains(old.mutationId)
            assertThat(marker).contains(unbound.mutationId)
            assertThat(rig.conflictDetails.getFrozenMediaSpoolManifest(old.mutationId)).isNotNull()
            assertThat(rig.conflictDetails.getFrozenMediaSpoolManifest(unbound.mutationId)).isNotNull()
            assertThat(spool.open(old.mutationId, old.items.single()).openStream().use { it.readBytes() })
                .isEqualTo(byteArrayOf(1, 2, 3))
        }
    }

    @Test fun postPublicationDeleteFailureReportsWarningAndRestartCompactsOnlyOnce() = runBlocking {
        fixture { rig, _, directory ->
            val captured = capture(rig, directory)
            check(File(requireNotNull(rig.media.getByClientUuid(PHOTO)).localUri).delete())
            rig.foreground.setForeground(false)
            rig.preferences.afterClearDisasterRestoreCheckpoint = {
                rig.conflictDetails.deleteFailure = IOException("post-publication database delete failed")
            }

            rig.port.commitDisasterRecovery("root").getOrThrow()

            assertThat(rig.preferences.current().isJoined).isTrue()
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            assertThat(rig.preferences.pendingReplicaResetPrevious()).isNull()
            assertThat(rig.port.status().first()).isEqualTo(com.lezi.babylog.core.model.SyncStatus.Error)
            assertThat(rig.port.lastFailureKind().first()).isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.UnexpectedError)
            assertThat(rig.conflictDetails.getTransportJournal(captured.key)).isNotNull()
            assertThat(rig.conflictDetails.getTransportJournal(RestoreArtifactRetirement.KEY)?.payloadJson).contains(captured.key)
            assertRetainedBytes(rig, captured)
            rig.preferences.afterClearDisasterRestoreCheckpoint = null
            rig.conflictDetails.deleteFailure = null
            rig.conflictDetails.trackJournalWork = true
            rig.conflictDetails.journalPointDeletes.clear()

            restart(rig, directory)
            assertThat(rig.conflictDetails.getTransportJournal(captured.key)).isNull()
            restart(rig, directory)

            assertThat(rig.conflictDetails.journalPointDeletes.filter { it == captured.key }).hasSize(1)
            assertThat(rig.conflictDetails.journalPointDeletes).doesNotContain("")
            assertRetainedBytes(rig, captured)
        }
    }

    @Test fun switchedSnapshotStaysWholeUntilDurablePublicationAndCheckpointClear() = runBlocking {
        fixture { rig, _, directory ->
            val captured = capture(rig, directory)
            val files = inventory(captured.directory)
            check(File(requireNotNull(rig.media.getByClientUuid(PHOTO)).localUri).delete())
            rig.foreground.setForeground(false)
            rig.preferences.failCompleteRestoreSessionAttempts = 100
            assertThat(rig.port.commitDisasterRecovery("root").isFailure).isTrue()
            val pendingSnapshot = requireNotNull(rig.conflictDetails.getTransportJournal(captured.key))
            assertThat(pendingSnapshot.payloadJson).contains("\"phase\":\"switched\"")
            val switchedRecord = rig.records.getByClientUuid(RECORD)
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNotNull()
            assertThat(rig.conflictDetails.listRestoreFileOwners()).isEmpty()
            assertThat(inventory(captured.directory)).isEqualTo(files)
            assertThat(captured.store.ownedFile(captured.snapshot, captured.snapshot.media.single()).readBytes()).isEqualTo(RAW)
            rig.preferences.failCompleteRestoreSessionAttempts = 0
            rig.port.commitDisasterRecovery("root").getOrThrow()
            assertThat(rig.preferences.current().isJoined).isTrue()
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            assertThat(rig.conflictDetails.getTransportJournal(captured.key)).isNull()
            assertThat(rig.records.getByClientUuid(RECORD)).isEqualTo(switchedRecord)
            restart(rig, directory)
            assertRetainedBytes(rig, captured)
            assertThat(rig.backend.disasterRestoreCommitRootPasswords).hasSize(1)
        }
    }

    @Test fun confirmedPreparedCancellationCompactsOnlyTheSnapshotAndKeepsSoleBytes() = runBlocking {
        fixture { rig, _, directory ->
            val captured = capture(rig, directory)
            val photo = requireNotNull(rig.media.getByClientUuid(PHOTO))
            check(File(photo.localUri).delete())
            rig.port.cancelDisasterRecovery().getOrThrow()
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            assertThat(rig.preferences.current().familyId).isEqualTo("family-a")
            assertThat(journal(rig, captured.key).getValue("phase").jsonPrimitive.content).isEqualTo("retiring")
            assertThat(journal(rig, RestoreFileLifecycleOwner.ownerKey(captured.request))
                .getValue("reason").jsonPrimitive.content).isEqualTo("cancelled")
            assertThat(rig.media.getByClientUuid(PHOTO)?.copy(localUri = photo.localUri)).isEqualTo(photo)
            assertRetainedBytes(rig, captured)
            restart(rig, directory)
            assertRetainedBytes(rig, captured)
            assertThat(rig.conflictDetails.listFrozenMediaSpoolManifests()).isEmpty()
            assertThat(rig.backend.disasterRestoreCommitRootPasswords).isEmpty()
        }
    }

    @Test fun consumedSnapshotPointerCannotDeleteALaterUncheckpointedPreparation() = runBlocking {
        fixture { rig, _, directory ->
            val captured = capture(rig, directory)
            check(File(requireNotNull(rig.media.getByClientUuid(PHOTO)).localUri).delete())
            rig.foreground.setForeground(false)
            rig.port.commitDisasterRecovery("root").getOrThrow()
            assertThat(rig.conflictDetails.getTransportJournal(captured.key)).isNull()
            assertRetainedBytes(rig, captured)
            val record = requireNotNull(rig.records.getByClientUuid(RECORD))
            rig.records.update(record.copy(note = "later preparation must survive", syncDirty = true))
            val next = newRequestIds()
            rig.preferences.nextDisasterRestoreRequestIds = next
            rig.backend.disasterRestoreStartFailure = IOException("start response unavailable before checkpoint")
            assertThat(rig.port.startDisasterRecovery(endpoint, "Owner", "Phone", "root").isFailure).isTrue()
            val laterKey = RestoreSnapshotJournal.key(next.start)
            val later = requireNotNull(rig.conflictDetails.getTransportJournal(laterKey))
            val store = RestoreFileSnapshotStore(File(directory, "restore-snapshots"))
            val laterSnapshot = store.read(requireNotNull(store.completed(next.start)))
            assertThat(laterSnapshot.manifestJson).contains("later preparation must survive")
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            val laterFiles = inventory(File(directory, "restore-snapshots/${next.start}"))

            restart(rig, directory)

            assertThat(rig.conflictDetails.getTransportJournal(laterKey)).isEqualTo(later)
            assertThat(inventory(File(directory, "restore-snapshots/${next.start}"))).isEqualTo(laterFiles)
            assertThat(store.ownedFile(laterSnapshot, laterSnapshot.media.single()).readBytes()).isEqualTo(RAW)
            assertRetainedBytes(rig, captured)
        }
    }

    @Test fun retiredRequestIdentityCannotBeReboundToALaterCapture() = runBlocking {
        fixture { rig, _, directory ->
            val captured = capture(rig, directory)
            rig.port.cancelDisasterRecovery().getOrThrow()
            rig.preferences.nextDisasterRestoreRequestIds = DisasterRestoreRequestIds(captured.request,
                captured.checkpoint.manifestRequestId, captured.checkpoint.commitRequestId)
            val record = requireNotNull(rig.records.getByClientUuid(RECORD))
            rig.records.update(record.copy(note = "newer local data"))

            assertThat(rig.port.startDisasterRecovery(endpoint, "Owner", "Phone", "root").exceptionOrNull())
                .isInstanceOf(RestoreFileSnapshotRetirementPendingException::class.java)

            assertThat(rig.backend.disasterRestoreStartRequestIds).containsExactly(captured.request)
            assertThat(rig.conflictDetails.getTransportJournal(captured.key)).isNull()
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            assertThat(rig.records.getByClientUuid(RECORD)?.note).isEqualTo("newer local data")
            assertTerminalCannotReplay(directory, captured.request)
        }
    }

    @Test fun postPublicationCompactionDoesNotConvertCancellationIntoSuccess() = runBlocking {
        for (nested in listOf(false, true)) fixture { rig, _, directory ->
            val captured = capture(rig, directory)
            check(File(requireNotNull(rig.media.getByClientUuid(PHOTO)).localUri).delete())
            rig.foreground.setForeground(false)
            val cancellation = kotlinx.coroutines.CancellationException("compaction scope ended")
            rig.preferences.afterClearDisasterRestoreCheckpoint = {
                rig.conflictDetails.deleteFailure = if (nested) IOException("wrapped cancellation", cancellation)
                    else cancellation
            }
            val result = rig.port.commitDisasterRecovery("root")
            assertThat(result.exceptionOrNull()).isSameInstanceAs(cancellation)
            assertThat(rig.preferences.current().isJoined).isTrue()
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            assertThat(rig.conflictDetails.getTransportJournal(captured.key)).isNotNull()
            assertRetainedBytes(rig, captured)
        }
    }

    @Test fun historicalFormatOneWithoutSourceRelationProofStopsAndPreservesEveryArtifact() = runBlocking {
        for (code in listOf(401, 404, 410)) fixture { rig, spool, directory ->
            val checkpoint = seedHistoricalSnapshot(rig, spool)
            val key = RestoreSnapshotJournal.key(checkpoint.startRequestId)
            val original = rig.conflictDetails.getTransportJournal(key)
            val groups = rig.conflictDetails.listFrozenMediaSpoolManifests()
            val files = inventory(File(directory, "spool"))
            val photo = rig.media.getByClientUuid(PHOTO)
            rig.backend.disasterRestoreStatusFailure = SyncHttpException(code)

            assertThat(rig.port.resumeDisasterRecovery().exceptionOrNull())
                .hasMessageThat().contains("旧恢复快照缺少完整来源关系证据")
            assertThat(rig.port.commitDisasterRecovery("root").exceptionOrNull())
                .hasMessageThat().contains("旧恢复快照缺少完整来源关系证据")
            restart(rig, directory)

            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isEqualTo(checkpoint)
            assertThat(rig.preferences.disasterRestoreToken()).isEqualTo("recovery-token-secret")
            assertThat(rig.conflictDetails.getTransportJournal(key)).isEqualTo(original)
            assertThat(rig.conflictDetails.listFrozenMediaSpoolManifests()).isEqualTo(groups)
            assertThat(inventory(File(directory, "spool"))).isEqualTo(files)
            assertThat(rig.media.getByClientUuid(PHOTO)).isEqualTo(photo)
            assertThat(rig.backend.disasterRestoreStartRequestIds).isEmpty()
            assertThat(rig.backend.disasterRestoreCommitRootPasswords).isEmpty()
            assertThat(rig.conflictDetails.listRestoreFileOwners()).isEmpty()
            assertThat(rig.conflictDetails.getTransportJournal(RestoreArtifactRetirement.KEY)).isNull()
        }
    }

    @Test fun historicalFormatOneExplicitCancellationRetainsSoleBytesWithoutInventingEvidence() = runBlocking {
        fixture { rig, spool, directory ->
            val checkpoint = seedHistoricalSnapshot(rig, spool)
            val photo = requireNotNull(rig.media.getByClientUuid(PHOTO))
            val group = com.lezi.babylog.sync.engine.decodeFrozenMediaSpoolManifest(
                rig.conflictDetails.listFrozenMediaSpoolManifests().single().payloadJson)
            check(File(photo.localUri).delete())

            rig.port.cancelDisasterRecovery().getOrThrow()
            restart(rig, directory)

            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            assertThat(rig.conflictDetails.getTransportJournal(RestoreSnapshotJournal.key(checkpoint.startRequestId))).isNull()
            assertThat(rig.conflictDetails.getTransportJournal(RestoreArtifactRetirement.KEY)?.payloadJson)
                .contains("\"snapshot_key\":\"\"")
            assertThat(rig.media.getByClientUuid(PHOTO)).isEqualTo(photo)
            assertThat(spool.open(group.mutationId, group.items.single()).openStream().use { it.readBytes() }).isEqualTo(RAW)
            assertThat(rig.conflictDetails.listRestoreFileOwners()).isEmpty()
            assertThat(rig.backend.disasterRestoreCommitRootPasswords).isEmpty()
        }
    }

    @Test fun historicalTerminalSnapshotCompactionDoesNotScanFactsOrCanonicalBytes() = runBlocking {
        fixture { rig, spool, _ ->
            val record = requireNotNull(rig.records.getByClientUuid(RECORD))
            rig.records.update(record.copy(note = "historical care note ".repeat(256)))
            val checkpoint = seedHistoricalSnapshot(rig, spool)
            val key = RestoreSnapshotJournal.key(checkpoint.startRequestId)
            // Explicit cancellation is the supported terminal boundary for this historical format.
            RestoreSnapshotJournal(rig.conflictDetails, spool, rig.transactions).cancel(checkpoint.startRequestId)
            rig.preferences.clearDisasterRestoreCheckpoint()
            val before = rig.conflictDetails.journalPayloadBytesForTest(key)
            val mediaReads = rig.media.listAllIncludingDeletedCalls
            val fileReads = rig.mediaFiles.readableFileCalls
            rig.conflictDetails.trackJournalWork = true
            rig.conflictDetails.journalPointReads.clear()
            rig.conflictDetails.journalPointDeletes.clear()
            rig.conflictDetails.journalPointPuts.clear()

            RestoreArtifactRetirement(rig.conflictDetails, rig.media, rig.mediaFiles, spool, rig.transactions)
                .compactSnapshot(key)

            assertThat(rig.conflictDetails.journalPayloadBytesForTest(key)).isEqualTo(0)
            assertThat(before).isGreaterThan(4096)
            assertThat(rig.conflictDetails.journalPointReads).containsExactly(RestoreArtifactRetirement.KEY)
            assertThat(rig.conflictDetails.journalPointDeletes).containsExactly(key)
            assertThat(rig.conflictDetails.journalPointPuts).containsExactly(RestoreArtifactRetirement.KEY)
            assertThat(rig.media.listAllIncludingDeletedCalls).isEqualTo(mediaReads)
            assertThat(rig.mediaFiles.readableFileCalls).isEqualTo(fileReads)
            assertThat(rig.conflictDetails.listFrozenMediaSpoolManifests()).hasSize(1)
        }
    }

    private data class Captured(
        val checkpoint: DisasterRestoreCheckpoint,
        val store: RestoreFileSnapshotStore,
        val snapshot: RestoreFileSnapshot,
    ) {
        val request get() = checkpoint.startRequestId
        val key get() = RestoreSnapshotJournal.key(request)
        val media get() = snapshot.ownedPath(snapshot.media.single())
        val directory get() = media.parentFile
    }

    private suspend fun capture(rig: SyncRig, directory: File): Captured {
        rig.port.startDisasterRecovery(endpoint, "Owner", "Phone", "root").getOrThrow()
        val checkpoint = requireNotNull(rig.preferences.disasterRestoreCheckpoint.first())
        val store = RestoreFileSnapshotStore(File(directory, "restore-snapshots"))
        val snapshot = store.read(requireNotNull(store.completed(checkpoint.startRequestId)))
        assertThat(journal(rig, RestoreSnapshotJournal.key(checkpoint.startRequestId))
            .getValue("format").jsonPrimitive.int).isEqualTo(2)
        assertThat(snapshot.media).hasSize(1)
        assertThat(store.ownedFile(snapshot, snapshot.media.single()).readBytes()).isEqualTo(RAW)
        assertThat(rig.conflictDetails.listFrozenMediaSpoolManifests()).isEmpty()
        return Captured(checkpoint, store, snapshot)
    }

    private suspend fun journal(rig: SyncRig, key: String) =
        Json.parseToJsonElement(requireNotNull(rig.conflictDetails.getTransportJournal(key)).payloadJson).jsonObject

    private fun inventory(directory: File): Map<String, List<Byte>> = directory.walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(directory).path to it.readBytes().toList() }

    private fun assertCompacted(captured: Captured) {
        for (file in listOf("manifest.json", "complete.json", "sources.json"))
            assertThat(File(captured.directory, file).exists()).isFalse()
    }

    private suspend fun assertRetainedBytes(rig: SyncRig, captured: Captured) {
        val owner = journal(rig, RestoreFileLifecycleOwner.ownerKey(captured.request))
        val pointer = RestoreFileRetirementPointer(captured.request,
            owner.getValue("ownership_sha256").jsonPrimitive.content,
            owner.getValue("ownership_bytes").jsonPrimitive.long)
        val retirement = captured.store.readRetirement(pointer)
        assertThat(retirement.activePointer).isEqualTo(captured.snapshot.pointer)
        assertThat(retirement.media.map { it.clientUuid }).containsExactly(PHOTO)
        assertThat(captured.store.ownedFile(retirement, retirement.media.single()).readBytes()).isEqualTo(RAW)
        assertCompacted(captured)
    }

    private suspend fun assertTerminalCannotReplay(directory: File, requestId: String) {
        assertThat(runCatching { RestoreFileSnapshotStore(File(directory, "restore-snapshots")).completed(requestId) }
            .exceptionOrNull()).isInstanceOf(RestoreFileSnapshotRetirementPendingException::class.java)
    }

    private fun lifecycle(rig: SyncRig, directory: File,
        fault: (RestoreFileLifecycleFaultPoint) -> Unit = {}) = RestoreFileLifecycleOwner(
        rig.conflictDetails, RestoreFileSnapshotStore(File(directory, "restore-snapshots")), rig.transactions,
        rig.media, rig.mediaFiles, rig.mediaFileCleanup, rig.babies,
        currentFamilyId = { rig.preferences.current().familyId }, fault = fault,
    )

    /** The historical writer deliberately omits source-relations/evidence; never retrofit new proof. */
    private suspend fun seedHistoricalSnapshot(rig: SyncRig, spool: ImmutableMediaSpool): DisasterRestoreCheckpoint {
        val requests = rig.preferences.ensureDisasterRestoreRequestIds()
        DisasterRecoverySnapshotBuilder(rig.babies, rig.records, rig.carePlans, rig.customItems,
            rig.fulfillmentCandidates, rig.media, rig.wakeObservations, rig.mediaFiles, rig.transactions)
            .build().use { snapshot ->
                RestoreSnapshotJournal(rig.conflictDetails, spool, rig.transactions).pin(requests.start, snapshot)
            }
        val value = journal(rig, RestoreSnapshotJournal.key(requests.start))
        assertThat(value.getValue("format").jsonPrimitive.int).isEqualTo(1)
        assertThat(value.containsKey("source_relations")).isFalse()
        assertThat(value.containsKey("source_relation_evidence")).isFalse()
        assertThat(value.containsKey("evidence_version")).isFalse()
        val checkpoint = DisasterRestoreCheckpoint(rig.backend.disasterRestoreStatus.batchId,
            endpoint, "family-a", requests.start, requests.manifest, requests.commit,
            rig.backend.disasterRestoreStatus.expiresAtEpochSeconds, "ready_to_commit", emptyList())
        rig.preferences.saveDisasterRestoreCheckpoint(checkpoint, "recovery-token-secret")
        return checkpoint
    }

    private fun newRequestIds() = DisasterRestoreRequestIds(
        java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString())

    private suspend fun seedPlainGroup(rig: SyncRig, spool: ImmutableMediaSpool,
        mediaUuid: String = PHOTO): ImmutableMediaSpoolGroup {
        val photo = requireNotNull(rig.media.getByClientUuid(mediaUuid))
        val mutation = java.util.UUID.randomUUID().toString()
        return spool.freezeGroup(mutation, listOf(ImmutableMediaSpoolSource(
            mediaUuid, CausalMediaRole.Log, photo.localUri,
            PublishedMediaIdentity("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                3, "image/jpeg", 1, 1)))).also {
            rig.conflictDetails.putFrozenMediaSpoolManifest(mutation, encodeImmutableMediaSpoolGroup(it), 0)
        }
    }

    private suspend fun restart(rig: SyncRig, directory: File, expectBlocked: Boolean = false): RealSyncPort {
        val spool = FileImmutableMediaSpool(rig.mediaFiles, File(directory, "spool"), 1024 * 1024, 1024)
        val ready = CompletableDeferred<Unit>()
        val port = RealSyncPort(
            backend = rig.backend, preferences = rig.preferences,
            setupProbe = SetupProbe { _, trusted -> SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1")) },
            foregroundSyncGate = ForegroundSyncGate(), pendingPublishDao = rig.pendingPublish,
            recordDao = rig.records, carePlanDao = rig.carePlans, babyDao = rig.babies,
            mediaDao = rig.media, customItemDao = rig.customItems, familyDao = rig.families,
            clock = rig.clock, foregroundState = rig.foreground, mediaFiles = rig.mediaFiles,
            immutableMediaSpool = spool, mediaFileCleanup = rig.mediaFileCleanup,
            transactionRunner = rig.transactions, pendingReplicaCleanupStore = rig.pendingReplicaCleanup,
            localClearRecoveryGate = LocalClearRecoveryGate {
                ready.complete(Unit); null
            },
            fulfillmentCandidateDao = rig.fulfillmentCandidates,
            fulfillmentAuthoritySettlement = rig.fulfillmentAuthoritySettlement,
            wakeObservationDao = rig.wakeObservations, conflictSummaryDao = rig.conflictSummaries,
            conflictSnapshotCacheDao = rig.conflictDetails,
            sourceRelationDao = rig.sourceRelations,
            restoreSnapshotsDir = File(directory, "restore-snapshots"),
        )
        withTimeout(5_000) {
            if (expectBlocked) assertThat(port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
            else ready.await()
        }
        return port
    }

    private suspend fun fixture(block: suspend (SyncRig, FileImmutableMediaSpool, File) -> Unit) {
        val directory = java.nio.file.Files.createTempDirectory("expired-restore-artifacts").toFile()
        try {
            val source = File(directory, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val files = object : TestMediaFileStore() {
                override fun statLength(localUri: String) = readableFile(localUri)?.length()
                override suspend fun inspect(localUri: String) = readableFile(localUri)?.let {
                    LocalMediaInfo(it.length(), "image/jpeg", 1, 1)
                }
                override suspend fun prepareUpload(localUri: String): PreparedMedia =
                    testPreparedMedia(requireNotNull(readableFile(localUri)).readBytes(), "image/jpeg", 1, 1)
            }
            val spool = FileImmutableMediaSpool(files, File(directory, "spool"), 1024 * 1024, 1024)
            val rig = SyncRig(joinedSession("family-a"), mediaFileStore = files, appUpdateCacheDir = directory,
                immutableMediaSpoolOverride = spool, setupProbe = SetupProbe { _, trusted ->
                    SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1"))
                })
            rig.awaitStartupRecovery()
            val baby = rig.babies.seed(localBaby().copy(clientUuid = BABY, syncDirty = false))
            val record = rig.records.seed(localRecord(baby).copy(clientUuid = RECORD, syncDirty = false))
            rig.media.seed(MediaAssetEntity(clientUuid = PHOTO, recordId = record, localUri = source.path,
                mime = "image/jpeg", width = 1, height = 1, byteSize = 3,
                createdAt = 120, updatedAt = 120, syncDirty = false))
            block(rig, spool, directory)
        } finally { directory.deleteRecursively() }
    }

    companion object {
        private val RAW = byteArrayOf(1, 2, 3)
        private val endpoint = TrustedEndpointProfile.systemPki("https://replacement.example.test")
        private const val BABY = "33333333-3333-4333-8333-333333333333"
        private const val RECORD = "22222222-2222-4222-8222-222222222222"
        private const val PHOTO = "44444444-4444-4444-8444-444444444444"
    }
}
