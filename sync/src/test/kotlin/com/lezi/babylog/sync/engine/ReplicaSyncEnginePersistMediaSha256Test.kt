package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.CausalCommitBatchResult
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalCommitUnitResult
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.receiptFor
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Public seam: [ReplicaSyncEngine.synchronize] plus [com.lezi.babylog.sync.MemoryMediaDao].
 *
 * Ticket 06: persist 64 lowercase hex on download success and publish freeze.
 * Opening / empty incremental must not fill old null rows.
 */
class ReplicaSyncEnginePersistMediaSha256Test {

    @Test
    fun thisPageDownloadSuccessPersistsKnownSha256() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 1)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee10"
        rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = babyUuid,
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val recordUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee11"
        val mediaUuid = "11111111-1111-4111-8111-111111111111"
        rig.backend.mediaBytes = byteArrayOf(1, 2, 3, 4)
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord(recordUuid, babyClientUuid = babyUuid),
                remoteReplicaMedia(mediaUuid, recordUuid),
            ),
            cursor = 2,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.Foreground)

        val stored = requireNotNull(rig.media.getByClientUuid(mediaUuid))
        assertThat(stored.localUri).isEqualTo("downloaded/$mediaUuid")
        assertThat(stored.sha256).isEqualTo(KNOWN_BYTES_1234_SHA256)
        assertThat(stored.syncDirty).isFalse()
        assertThat(rig.backend.mediaGets).containsExactly(mediaUuid)
    }

    @Test
    fun historicalDownloadSuccessPersistsKnownSha256() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 4)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = "record-historical-sha",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = false,
                baseVersion = "v-r0",
            ),
        )
        val mediaUuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = session.receiptFor(mediaUuid),
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.mediaBytes = byteArrayOf(1, 2, 3, 4)
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 4,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.Foreground)

        val stored = requireNotNull(rig.media.getByClientUuid(mediaUuid))
        assertThat(stored.localUri).isEqualTo("downloaded/$mediaUuid")
        assertThat(stored.sha256).isEqualTo(KNOWN_BYTES_1234_SHA256)
        assertThat(stored.updatedAt).isEqualTo(100)
        assertThat(stored.syncDirty).isFalse()
    }

    @Test
    fun freezePersistsKnownSha256WithoutBumpingUpdatedAt() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 9)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val recordUuid = "record-freeze-sha"
        val mediaUuid = "00000000-0000-4000-8000-000000000062"
        val mediaBytes = byteArrayOf(8, 6, 7, 5)
        val mediaUri = "/private/freeze-sha.jpg"
        rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = true,
                baseVersion = "v-r0",
            ),
        )
        rig.mediaFiles.preparedUploadBytes[mediaUri] = mediaBytes
        rig.media.seed(
            MediaAssetEntity(
                recordId = requireNotNull(rig.records.getByClientUuid(recordUuid)).id,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = mediaUri,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.backend.onCausalCommit = { units ->
            val unit = units.single()
            rig.backend.nextCausalCommit = CausalCommitBatchResult(
                generation = session.pullGeneration,
                results = listOf(
                    CausalCommitUnitResult(
                        status = CausalCommitStatus.ACCEPTED,
                        mutationId = unit.mutationId,
                        requestHash = causalMutationContentHash(unit),
                        stableVersionId = "v-r1",
                        stableRootJson = unit.rootJson,
                        stableMedia = unit.media,
                    ),
                ),
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val stored = requireNotNull(rig.media.getByClientUuid(mediaUuid))
        assertThat(stored.sha256).isEqualTo(KNOWN_BYTES_8675_SHA256)
        assertThat(stored.updatedAt).isEqualTo(100)
        assertThat(stored.localUri).isEqualTo(mediaUri)
        assertThat(rig.records.getByClientUuid(recordUuid)?.syncDirty).isFalse()
    }

    @Test
    fun emptyIncrementalDoesNotFillLegacyNullSha256() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 8)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val file = File.createTempFile("legacy-media", ".jpg")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val babyId = rig.babies.seed(
                localReplicaBaby().copy(
                    syncDirty = false,
                    familyAuthority = true,
                    baseVersion = "v-baby",
                ),
            )
            val recordId = rig.records.seed(
                RecordEntity(
                    clientUuid = "record-legacy-null-sha",
                    babyId = babyId,
                    type = "formula",
                    timestamp = 100,
                    payloadJson = """{"amount_ml":90}""",
                    schemaVersion = 2,
                    updatedAt = 100,
                    syncDirty = false,
                    baseVersion = "v-r0",
                ),
            )
            val mediaUuid = "35353535-3535-3535-3535-353535353535"
            rig.media.seed(
                MediaAssetEntity(
                    recordId = recordId,
                    clientUuid = mediaUuid,
                    kind = "log",
                    localUri = file.absolutePath,
                    remoteUri = session.receiptFor(mediaUuid),
                    mime = "image/jpeg",
                    byteSize = 4,
                    createdAt = 100,
                    updatedAt = 100,
                    syncDirty = false,
                ),
            )
            val persistCallsBefore = rig.media.persistSha256IfAbsentCalls
            rig.backend.nextPull = PullResult(
                entities = emptyList(),
                cursor = 8,
                generation = session.pullGeneration,
                hasMore = false,
            )

            rig.engine.synchronize(session, SyncTrigger.Foreground)

            val stored = requireNotNull(rig.media.getByClientUuid(mediaUuid))
            assertThat(stored.sha256).isNull()
            assertThat(stored.updatedAt).isEqualTo(100)
            assertThat(stored.syncDirty).isFalse()
            assertThat(rig.media.persistSha256IfAbsentCalls - persistCallsBefore).isEqualTo(0)
            assertThat(rig.backend.mediaGets).isEmpty()
            assertThat(rig.backend.pullCount).isEqualTo(1)
        } finally {
            file.delete()
        }
    }

    @Test
    fun thisPageSkipCandidateHashesRelativeUriOnceAndDoesNotRehash() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 3)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val filesRoot = File.createTempFile("media-files-root", "").apply {
            check(delete())
            check(mkdirs())
        }
        val relativeUri = "record-media/skip-candidate.jpg"
        val onDisk = File(filesRoot, relativeUri)
        try {
            check(onDisk.parentFile?.mkdirs() == true || onDisk.parentFile?.isDirectory == true)
            onDisk.writeBytes(byteArrayOf(1, 2, 3, 4))
            rig.mediaFiles.filesRoot = filesRoot
            val babyUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee20"
            val babyId = rig.babies.seed(
                localReplicaBaby().copy(
                    clientUuid = babyUuid,
                    syncDirty = false,
                    familyAuthority = true,
                    baseVersion = "v-baby",
                ),
            )
            val recordUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee21"
            val recordId = rig.records.seed(
                RecordEntity(
                    clientUuid = recordUuid,
                    babyId = babyId,
                    type = "formula",
                    timestamp = 100,
                    payloadJson = """{"amount_ml":90}""",
                    schemaVersion = 2,
                    updatedAt = 210,
                    syncDirty = false,
                    baseVersion = "v-r0",
                ),
            )
            val mediaUuid = "11111111-1111-4111-8111-111111111122"
            rig.media.seed(
                MediaAssetEntity(
                    recordId = recordId,
                    clientUuid = mediaUuid,
                    kind = "log",
                    localUri = relativeUri,
                    remoteUri = session.receiptFor(mediaUuid),
                    mime = "image/jpeg",
                    byteSize = 4,
                    createdAt = 210,
                    updatedAt = 210,
                    syncDirty = false,
                ),
            )
            rig.backend.nextPull = PullResult(
                entities = listOf(
                    remoteReplicaRecord(recordUuid, babyClientUuid = babyUuid),
                    remoteReplicaMedia(mediaUuid, recordUuid),
                ),
                cursor = 3,
                generation = session.pullGeneration,
                hasMore = false,
            )

            rig.engine.synchronize(session, SyncTrigger.Foreground)

            val first = requireNotNull(rig.media.getByClientUuid(mediaUuid))
            assertThat(first.sha256).isEqualTo(KNOWN_BYTES_1234_SHA256)
            assertThat(first.updatedAt).isEqualTo(210)
            assertThat(first.syncDirty).isFalse()
            assertThat(first.localUri).isEqualTo(relativeUri)
            assertThat(rig.backend.mediaGets).isEmpty()
            assertThat(rig.media.persistSha256IfAbsentCalls).isEqualTo(1)

            rig.backend.nextPull = PullResult(
                entities = listOf(
                    remoteReplicaRecord(recordUuid, babyClientUuid = babyUuid),
                    remoteReplicaMedia(mediaUuid, recordUuid),
                ),
                cursor = 3,
                generation = session.pullGeneration,
                hasMore = false,
            )
            rig.engine.synchronize(session, SyncTrigger.Foreground)

            val second = requireNotNull(rig.media.getByClientUuid(mediaUuid))
            assertThat(second.sha256).isEqualTo(KNOWN_BYTES_1234_SHA256)
            assertThat(rig.backend.mediaGets).isEmpty()
            assertThat(rig.media.persistSha256IfAbsentCalls).isEqualTo(1)
        } finally {
            onDisk.delete()
            onDisk.parentFile?.delete()
            filesRoot.delete()
        }
    }

    companion object {
        const val KNOWN_BYTES_1234_SHA256 =
            "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
        const val KNOWN_BYTES_8675_SHA256 =
            "148ad5eadb29c70c19bf3de855a16faff7bf73d9739819d3b553060f12b0ccf1"
    }
}
