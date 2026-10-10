package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.sync.backend.testPreparedMedia
import com.lezi.babylog.sync.media.*
import com.lezi.babylog.sync.session.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Real filesystem and production spool, through SyncPort; timestamps deliberately stay unchanged. */
class RestoreSoleCopySpoolTest {
    @Test fun latePhotoWithOnlyOldSpoolCopyPublishesUnderNewMutationAfterSpoolRestart() = runBlocking {
        scenario(deleteDuringCopy = false)
    }

    @Test fun attachmentDeletionDuringAdoptionNeverPublishesOrResurrectsCopiedPhoto() = runBlocking {
        scenario(deleteDuringCopy = true)
    }

    @Test fun sameTimestampMetadataEditDuringCopyCannotReuseStaleSidecarsAfterRestart() = runBlocking {
        scenario(deleteDuringCopy = false, metadataDuringCopy = true)
    }

    @Test fun automaticRestoreFollowupPublishesTheSoleCopyWithoutManualSync() = runBlocking {
        scenario(deleteDuringCopy = false, automaticFollowup = true)
    }

    private suspend fun scenario(deleteDuringCopy: Boolean, metadataDuringCopy: Boolean = false,
        automaticFollowup: Boolean = false) {
        val directory = java.nio.file.Files.createTempDirectory("restore-sole-copy").toFile()
        try {
            val source = File(directory, "late.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val files = object : TestMediaFileStore() {
                override fun statLength(localUri: String) = readableFile(localUri)?.length()
                override suspend fun inspect(localUri: String) = readableFile(localUri)?.let {
                    LocalMediaInfo(it.length(), "image/jpeg", 1, 1)
                }
                override suspend fun prepareUpload(localUri: String): PreparedMedia {
                    val file = requireNotNull(readableFile(localUri)) { "original source missing" }
                    return testPreparedMedia(file.readBytes(), "image/jpeg", 1, 1)
                }
                override suspend fun saveDownloaded(clientUuid: String, kind: String, bytes: ByteArray, mime: String?): String =
                    File(directory, "canonical-$clientUuid.bin").apply { writeBytes(bytes) }.path
                override suspend fun preparePublishedUpload(localUri: String, identity: PublishedMediaIdentity): PreparedMedia {
                    check(readableFile(localUri) != null) { "original source missing" }
                    return super.preparePublishedUpload(localUri, identity)
                }
            }
            var onCopy: (() -> Unit)? = null
            fun spool() = FileImmutableMediaSpool(files, File(directory, "spool"), 1024 * 1024, 1024,
                faultInjector = { point ->
                    if (point == ImmutableMediaSpoolFaultPoint.AfterMediaTempSync) onCopy?.invoke()
                })
            val durable = RestartableSpool(spool())
            val rig = SyncRig(joinedSession("family-a"), mediaFileStore = files,
                immutableMediaSpoolOverride = durable, setupProbe = SetupProbe { _, trusted ->
                    SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1"))
                })
            rig.awaitStartupRecovery()
            val babyId = rig.babies.seed(localBaby().copy(clientUuid = BABY, syncDirty = false))
            val recordId = rig.records.seed(localRecord(babyId).copy(clientUuid = RECORD, syncDirty = false))
            rig.port.startDisasterRecovery(TrustedEndpointProfile.systemPki("https://replacement.example.test"),
                "Owner", "Phone", "root").getOrThrow()
            val record = requireNotNull(rig.records.getByClientUuid(RECORD))
            rig.records.update(record.copy(note = "late attachment", syncDirty = true))
            rig.media.seed(MediaAssetEntity(clientUuid = PHOTO, recordId = recordId, localUri = source.path,
                mime = "image/jpeg", width = 1, height = 1, byteSize = 3, createdAt = 120, updatedAt = 120))
            rig.backend.failAfterCausalMediaPreimageUpload = IOException("old prepare reply lost")
            check(rig.port.sync(SyncTrigger.LocalWrite).isFailure)
            val oldMutation = requireNotNull(rig.records.getByClientUuid(RECORD)?.mutationId)
            val oldGroup = requireNotNull(durable.recoverGroup(oldMutation)).group
            assertThat(oldGroup.items.single().mediaUuid).isEqualTo(PHOTO)
            val currentSource = File(requireNotNull(rig.media.getByClientUuid(PHOTO)).localUri)
            check(currentSource.delete())
            source.delete()
            check(files.readableFile(requireNotNull(rig.media.getByClientUuid(PHOTO)).localUri) == null)
            val followup = RestoreFollowupTestGate(rig)
            try {
                rig.port.commitDisasterRecovery("root").getOrThrow()
                followup.awaitEntered()
                // The automatic round really is live; stop it at this observable
                // boundary before fault-injecting the separately exercised retry.
                if (!automaticFollowup) followup.cancelRound()
            } finally { followup.resume() }
            if (automaticFollowup) {
                rig.awaitAutomaticRecordPublication(RECORD)
                val currentMedia = requireNotNull(rig.media.getByClientUuid(PHOTO))
                assertThat(requireNotNull(files.readableFile(currentMedia.localUri)).readBytes())
                    .isEqualTo(byteArrayOf(1, 2, 3))
                assertThat(currentMedia.deletedAt).isNull()
                assertThat(rig.records.getByClientUuid(RECORD)?.note).isEqualTo("late attachment")
                assertThat(rig.backend.causalCommittedUnits.flatten().last { it.clientUuid == RECORD }
                    .media.single().mediaUuid).isEqualTo(PHOTO)
                return
            }
            val newMutation = requireNotNull(rig.records.getByClientUuid(RECORD)?.mutationId)
            assertThat(newMutation).isNotEqualTo(oldMutation)
            assertThat(rig.records.getByClientUuid(RECORD)?.baseVersion)
                .isEqualTo("e0bec011-0509-5e15-98e1-0f7e7a49ee87")
            durable.current = spool()
            if (metadataDuringCopy) {
                onCopy = {
                    onCopy = null
                    runBlocking {
                        val current = requireNotNull(rig.media.getByClientUuid(PHOTO))
                        rig.media.update(current.copy(mime = "changed metadata", width = 2, height = 3))
                    }
                }
                rig.port.sync(SyncTrigger.LocalWrite)
                assertThat(rig.backend.causalCommittedUnits.flatten().filter { it.mutationId == newMutation }).isEmpty()
                durable.current = spool()
                // The first retry quarantines an unbound stale preparation; the next freezes
                // explicitly changed local metadata into distinct new ownership.
                rig.port.sync(SyncTrigger.LocalWrite)
                durable.current = spool()
                rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()
                val published = rig.backend.causalCommittedUnits.flatten().last { it.clientUuid == RECORD }
                assertThat(published.mutationId).isNotEqualTo(newMutation)
                assertThat(published.media.single().mime).isEqualTo("changed metadata")
                assertThat(published.media.single().width).isEqualTo(2)
                assertThat(published.media.single().height).isEqualTo(3)
                assertThat(rig.media.getByClientUuid(PHOTO)?.mime).isEqualTo("changed metadata")
                return
            }
            if (deleteDuringCopy) {
                onCopy = {
                    onCopy = null
                    runBlocking {
                        val current = requireNotNull(rig.media.getByClientUuid(PHOTO))
                        rig.media.update(current.copy(deletedAt = 121, updatedAt = 121, syncDirty = true))
                        val root = requireNotNull(rig.records.getByClientUuid(RECORD))
                        rig.records.update(root.copy(note = "removed during copy", updatedAt = 121, syncDirty = true))
                    }
                }
                rig.port.sync(SyncTrigger.LocalWrite)
                assertThat(rig.media.getByClientUuid(PHOTO)?.deletedAt).isEqualTo(121)
                assertThat(rig.backend.causalCommittedUnits.flatten().filter { it.mutationId == newMutation }
                    .flatMap { it.media }).isEmpty()
                durable.current = spool()
                rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()
                assertThat(rig.records.getByClientUuid(RECORD)?.note).isEqualTo("removed during copy")
                assertThat(rig.backend.causalCommittedUnits.flatten().last { it.clientUuid == RECORD }.media).isEmpty()
                return
            }
            rig.backend.nextCausalCommitFailure = IOException("new commit reply lost")
            val interruptedCommit = rig.port.sync(SyncTrigger.LocalWrite)
            check(interruptedCommit.isFailure) {
                "expected lost commit: mutation=$newMutation; pending=${rig.records.getByClientUuid(RECORD)}; " +
                    "attempts=${rig.backend.causalCommittedUnits.flatten().map { it.mutationId }}; " +
                    "faultStillPending=${rig.backend.nextCausalCommitFailure != null}"
            }
            val adopted = requireNotNull(durable.recoverGroup(newMutation)).group
            assertThat(durable.open(newMutation, adopted.items.single()).openStream().use { it.readBytes() })
                .isEqualTo(byteArrayOf(1, 2, 3))
            // Donor retirement is legal only after the replacement has both durable Room
            // ownership and restart-readable verified bytes. It need not remain forever.
            assertThat(rig.conflictDetails.getFrozenMediaSpoolManifest(newMutation)).isNotNull()
            durable.current = spool()
            val restartedReplacement = requireNotNull(durable.recoverGroup(newMutation)).group
            assertThat(durable.open(newMutation, restartedReplacement.items.single()).openStream().use { it.readBytes() })
                .isEqualTo(byteArrayOf(1, 2, 3))
            rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()
            assertThat(rig.records.getByClientUuid(RECORD)?.note).isEqualTo("late attachment")
            assertThat(rig.records.getByClientUuid(RECORD)?.syncDirty).isFalse()
            assertThat(durable.recoverGroup(oldMutation)).isNull()
            val published = rig.backend.causalCommittedUnits.flatten().last { it.clientUuid == RECORD }
            assertThat(published.mutationId).isEqualTo(newMutation)
            assertThat(published.media.single().sha256)
                .isEqualTo("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81")
        } finally { directory.deleteRecursively() }
    }

    private class RestartableSpool(var current: ImmutableMediaSpool) : ImmutableMediaSpool {
        override suspend fun freezeGroup(mutationId: String, sources: List<ImmutableMediaSpoolSource>) = current.freezeGroup(mutationId, sources)
        override suspend fun recoverGroup(mutationId: String) = current.recoverGroup(mutationId)
        override suspend fun open(mutationId: String, item: ImmutableMediaSpoolItem) = current.open(mutationId, item)
        override suspend fun discardGroup(mutationId: String) = current.discardGroup(mutationId)
        override suspend fun recoverAndSweep(retainedMutationIds: Set<String>) = current.recoverAndSweep(retainedMutationIds)
    }
    companion object {
        const val BABY = "33333333-3333-4333-8333-333333333333"
        const val RECORD = "22222222-2222-4222-8222-222222222222"
        const val PHOTO = "44444444-4444-4444-8444-444444444444"
    }
}
