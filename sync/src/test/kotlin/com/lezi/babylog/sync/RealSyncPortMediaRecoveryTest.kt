package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.sync.backend.CausalCommitRejectedException
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Public SyncPort regressions. Database writes model the independent care-edit boundary. */
class RealSyncPortMediaRecoveryTest {
    @Test
    fun interruptedPrepareFinishesFrozenIntentBeforePublishingLaterNote() = runTest {
        val rig = SyncRig(joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val original = localRecord(babyId).copy(note = "original", updatedAt = 100)
        val recordId = rig.records.seed(original)
        val photo = "33333333-3333-3333-3333-333333333333"
        val uri = "photos/recovery.jpg"
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = photo, recordId = recordId, localUri = uri,
                createdAt = 100, updatedAt = 100,
            ),
        )
        rig.mediaFiles.preparedUploadBytes[uri] = byteArrayOf(3, 1, 4)
        rig.backend.failAfterCausalMediaPreimageUpload = IOException("prepare reply lost")
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()

        rig.records.update(original.copy(id = recordId, note = "later note", updatedAt = 101))
        rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()
        rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()

        val published = rig.backend.causalCommittedUnits.flatten().filter { it.clientUuid == original.clientUuid }
        assertThat(published).hasSize(2)
        assertThat(published.first().rootJson).contains("\"note\":\"original\"")
        assertThat(published.last().rootJson).contains("\"note\":\"later note\"")
        assertThat(published.last().mutationId).isNotEqualTo(published.first().mutationId)
        assertThat(published.last().baseVersion).isNotNull()
    }
    @Test
    fun expiredPreimageRestagesExactBytesAndReplaysTheSameMutation() = runTest {
        val rig = SyncRig(joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(localRecord(babyId))
        val photo = "44444444-4444-4444-4444-444444444444"
        val uri = "photos/expired.jpg"
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = photo, recordId = recordId, localUri = uri,
                createdAt = 100, updatedAt = 100,
            ),
        )
        rig.mediaFiles.preparedUploadBytes[uri] = byteArrayOf(8, 6, 7)
        rig.backend.nextCausalCommitFailure = IOException("commit response lost")
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
        val original = rig.backend.causalCommittedUnits.single().single()
        rig.mediaFiles.prepareUploadFailures += uri
        rig.backend.nextCausalCommitFailure =
            CausalCommitRejectedException(original.mutationId, "media_preimage_expired")

        rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()

        assertThat(rig.backend.causalCommittedUnits.flatten()).containsExactly(
            original, original, original,
        ).inOrder()
        assertThat(rig.backend.causalMediaPreimageBytes.map { it.second.toList() })
            .containsExactly(listOf<Byte>(8, 6, 7), listOf<Byte>(8, 6, 7))
    }

    @Test(timeout = 30_000)
    fun authoritativeResolutionRetiresBranchSpoolWithoutWaitingForAnotherRound() = runBlocking {
        val files = object : TestMediaFileStore() {
            override suspend fun inspect(localUri: String) =
                com.lezi.babylog.sync.media.LocalMediaInfo(1, "image/jpeg", 10, 10)
            override fun statLength(localUri: String): Long = 1
        }
        val directory = java.nio.file.Files.createTempDirectory("resolved-media").toFile()
        try {
            val spool = com.lezi.babylog.sync.media.FileImmutableMediaSpool(
                files, directory, 1024 * 1024, 1024,
            )
            val rig = SyncRig(
                joinedSession("family-a"), mediaFileStore = files,
                immutableMediaSpoolOverride = spool,
            )
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            val recordId = rig.records.seed(localRecord(babyId))
            val photo = "66666666-6666-6666-6666-666666666666"
            rig.media.seed(MediaAssetEntity(
                clientUuid = photo, recordId = recordId,
                localUri = "photos/branch.jpg", createdAt = 100, updatedAt = 100,
            ))
            rig.backend.onCausalCommit = { units ->
                val unit = units.single()
                rig.backend.nextCausalCommit = com.lezi.babylog.sync.backend.CausalCommitBatchResult(
                    generation = "current-generation",
                    results = listOf(com.lezi.babylog.sync.backend.CausalCommitUnitResult(
                        status = com.lezi.babylog.sync.backend.CausalCommitStatus.BRANCHED,
                        mutationId = unit.mutationId,
                        requestHash = com.lezi.babylog.sync.engine.causalMutationContentHash(unit),
                        stableVersionId = "stable-before", stableRootJson = unit.rootJson,
                        stableMedia = unit.media, conflictId = "conflict-a", branchVersionId = "branch-a",
                    )),
                )
            }
            rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()
            val branched = rig.backend.causalCommittedUnits.single().single()
            assertThat(directory.walkTopDown().any { it.extension == "media" }).isTrue()
            rig.backend.onCausalCommit = null
            val root = kotlinx.serialization.json.Json.parseToJsonElement(branched.rootJson)
                as kotlinx.serialization.json.JsonObject
            rig.backend.nextPull = com.lezi.babylog.sync.backend.PullResult(
                entities = listOf(com.lezi.babylog.sync.backend.SyncEntity(
                    type = "record", clientUuid = branched.clientUuid,
                    payloadJson = kotlinx.serialization.json.JsonObject(
                        (root - "updated_at") + ("created_by_membership_id" to
                            kotlinx.serialization.json.JsonPrimitive(rig.preferences.current().membershipId)),
                    ).toString(),
                    updatedAt = 200, versionId = "resolved-stable", media = branched.media,
                )), cursor = 1, generation = "current-generation", hasMore = false,
            )

            rig.port.sync(SyncTrigger.PullToRefresh).getOrThrow()

            assertThat(directory.walkTopDown().any { it.extension == "media" }).isFalse()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 30_000)
    fun noteEditReusesDownloadedIdentityWithoutNormalizingItsBytes() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("published-media").toFile()
        try {
            val source = java.io.File(directory, "downloaded.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val files = object : TestMediaFileStore() {
                override suspend fun inspect(localUri: String) =
                    com.lezi.babylog.sync.media.LocalMediaInfo(3, "image/jpeg", 1, 1)
                override fun statLength(localUri: String): Long = 3
            }
            files.preparedUploadBytes[source.path] = byteArrayOf(9, 9)
            val spool = com.lezi.babylog.sync.media.FileImmutableMediaSpool(
                files, java.io.File(directory, "spool"), 1024 * 1024, 1024,
            )
            val rig = SyncRig(
                joinedSession("family-a"), mediaFileStore = files,
                immutableMediaSpoolOverride = spool,
            )
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            val recordId = rig.records.seed(localRecord(babyId).copy(note = "text-only edit"))
            val photo = "77777777-7777-7777-7777-777777777777"
            rig.media.seed(MediaAssetEntity(
                clientUuid = photo, recordId = recordId, localUri = source.path,
                remoteUri = rig.preferences.current().expectedMediaReceipt(photo),
                mime = "image/jpeg", width = 1, height = 1, byteSize = 3,
                sha256 = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                createdAt = 100, updatedAt = 100, syncDirty = false,
            ))

            rig.conflictDetails.putTransportJournal("canonical-media-bytes-v1:$photo", source.path, 100)
            rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()

            val identity = rig.backend.causalCommittedUnits.flatten().single().media.single()
            assertThat(identity.mediaUuid).isEqualTo(photo)
            assertThat(identity.sha256).isEqualTo("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81")
            assertThat(identity.byteSize).isEqualTo(3)
            assertThat(source.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 30_000)
    fun noteEditPreservesSchema13WhitespaceAndUnicodeMetadataWithoutProbeRewrite() = runBlocking {
        for (mime in listOf("  ", "😀".repeat(32))) {
        val directory = java.nio.file.Files.createTempDirectory("published-media").toFile()
        try {
            val source = java.io.File(directory, "downloaded.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val files = object : TestMediaFileStore() {
                override suspend fun inspect(localUri: String) =
                    com.lezi.babylog.sync.media.LocalMediaInfo(3, "image/jpeg", 1, 1)
                override fun statLength(localUri: String): Long = 3
            }
            files.preparedUploadBytes[source.path] = byteArrayOf(9, 9)
            val spool = com.lezi.babylog.sync.media.FileImmutableMediaSpool(
                files, java.io.File(directory, "spool"), 1024 * 1024, 1024,
            )
            val rig = SyncRig(
                joinedSession("family-a"), mediaFileStore = files,
                immutableMediaSpoolOverride = spool,
            )
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            val recordId = rig.records.seed(localRecord(babyId).copy(note = "text-only edit"))
            val photo = "77777777-7777-7777-7777-777777777777"
            rig.media.seed(MediaAssetEntity(
                clientUuid = photo, recordId = recordId, localUri = source.path,
                remoteUri = rig.preferences.current().expectedMediaReceipt(photo),
                mime = mime, width = null, height = null, byteSize = 3,
                sha256 = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                createdAt = 100, updatedAt = 100, syncDirty = false,
            ))

            rig.conflictDetails.putTransportJournal("canonical-media-bytes-v1:$photo", source.path, 100)
            rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()

            val identity = rig.backend.causalCommittedUnits.flatten().single().media.single()
            assertThat(identity.mime).isEqualTo(mime)
            assertThat(identity.width).isNull()
            assertThat(identity.height).isNull()
            assertThat(rig.media.getByClientUuid(photo)?.mime).isEqualTo(mime)
            assertThat(identity.mediaUuid).isEqualTo(photo)
            assertThat(identity.sha256).isEqualTo("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81")
            assertThat(identity.byteSize).isEqualTo(3)
            assertThat(source.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        } finally {
            directory.deleteRecursively()
        }
        }
    }

    @Test
    fun newerIntentAfterTerminalRejectionDoesNotReuseARetiredMediaJournal() = runTest {
        val rig = SyncRig(joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val original = localRecord(babyId).copy(note = "rejected", updatedAt = 100)
        val recordId = rig.records.seed(original)
        rig.media.seed(MediaAssetEntity(
            clientUuid = "88888888-8888-8888-8888-888888888888",
            recordId = recordId, localUri = "photos/rejected.jpg", createdAt = 100, updatedAt = 100,
        ))
        rig.backend.nextCausalCommitFailure = CausalCommitRejectedException(null, "forbidden")
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
        rig.records.update(original.copy(id = recordId, note = "new allowed intent", updatedAt = 101))

        rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()

        val committed = rig.backend.causalCommittedUnits.flatten().last()
        assertThat(committed.rootJson).contains("\"note\":\"new allowed intent\"")
    }

    @Test
    fun unknownMediaCommitCannotDiscardItsOnlyReplayEvidence() = runTest {
        val rig = SyncRig(joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val record = localRecord(babyId)
        val recordId = rig.records.seed(record)
        rig.media.seed(MediaAssetEntity(
            clientUuid = "99999999-9999-9999-9999-999999999999",
            recordId = recordId, localUri = "photos/unknown.jpg", createdAt = 100, updatedAt = 100,
        ))
        rig.backend.nextCausalCommitFailure = IOException("commit reply unknown")
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
        val unknown = rig.backend.causalCommittedUnits.single().single()

        assertThat(rig.port.abandonRejectedMutation("record", record.clientUuid).isFailure).isTrue()
        rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()

        assertThat(rig.backend.causalCommittedUnits.flatten()).containsExactly(unknown, unknown).inOrder()
    }

}
