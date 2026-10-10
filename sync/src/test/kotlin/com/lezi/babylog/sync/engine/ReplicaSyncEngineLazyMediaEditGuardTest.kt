package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.receiptFor
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Public seam: [ReplicaSyncEngine.synchronize] plus recording
 * [com.lezi.babylog.sync.MemoryMediaDao] / [com.lezi.babylog.sync.MemoryBabyDao]
 * [listAllIncludingDeleted] counts.
 *
 * Causal LocalWrite that does not apply remote or adopt media must not pay a
 * full-table media/baby scan. Full cycles still materialize the edit guard.
 */
class ReplicaSyncEngineLazyMediaEditGuardTest {

    @Test
    fun causalLocalWriteWithoutMediaDoesNotScanAllMediaOrBabyRows() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 7,
        )
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-localwrite-no-media",
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
        rig.backend.nextPull = PullResult(
            entities = listOf(remoteReplicaRecord("must-not-pull")),
            cursor = session.pullCursor,
            generation = session.pullGeneration,
            hasMore = false,
        )
        val mediaScansBefore = rig.media.listAllIncludingDeletedCalls
        val babyScansBefore = rig.babies.listAllIncludingDeletedCalls

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.media.listAllIncludingDeletedCalls - mediaScansBefore).isEqualTo(0)
        assertThat(rig.babies.listAllIncludingDeletedCalls - babyScansBefore).isEqualTo(0)
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(7)
        assertThat(rig.records.getByClientUuid("record-localwrite-no-media")?.syncDirty).isFalse()
    }

    @Test
    fun memberCausalLocalWriteWithoutMediaDoesNotScanAllMediaOrBabyRows() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Member,
            pullCursor = 7,
        )
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-member-localwrite-no-media",
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
        val mediaScansBefore = rig.media.listAllIncludingDeletedCalls

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.media.listAllIncludingDeletedCalls - mediaScansBefore).isEqualTo(0)
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(7)
        assertThat(
            rig.records.getByClientUuid("record-member-localwrite-no-media")?.syncDirty,
        ).isFalse()
    }

    @Test
    fun fullCycleStillBlocksInCycleLocalMediaEditFromRemoteOverwrite() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 3)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        rig.backend.mediaBytes = ByteArray(12) { 1 } // Match existing and concurrently replaced file metadata.
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = "record-local",
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
        val peerUuid = "36363636-3636-3636-3636-363636363636"
        listOf(mediaUuid, peerUuid).forEach { uuid ->
            rig.media.seed(
                MediaAssetEntity(
                    recordId = recordId,
                    clientUuid = uuid,
                    kind = "log",
                    localUri = "",
                    remoteUri = session.receiptFor(uuid),
                    mime = "image/jpeg",
                    byteSize = 12,
                    createdAt = 100,
                    updatedAt = 100,
                    syncDirty = false,
                ),
            )
        }
        val replacementUuid = "37373737-3737-4737-8737-373737373737"
        val replacementBytes = byteArrayOf(7, 8, 9)
        rig.mediaFiles.preparedUploadBytes["photos/between-downloads.jpg"] = replacementBytes
        rig.mediaFiles.afterSaveDownloaded = {
            val current = requireNotNull(rig.media.getByClientUuid(peerUuid))
            rig.media.update(
                current.copy(
                    deletedAt = 101,
                    updatedAt = 101,
                    syncDirty = true,
                ),
            )
            rig.media.seed(MediaAssetEntity(
                recordId = recordId, clientUuid = replacementUuid, kind = "log",
                localUri = "photos/between-downloads.jpg", byteSize = 3, mime = "image/jpeg",
                createdAt = 101, updatedAt = 101, syncDirty = true,
            ))
        }
        rig.backend.nextPull = PullResult(
            entities = listOf(mediaUuid, peerUuid).map { uuid ->
                remoteReplicaMedia(uuid, "record-local").copy(
                    updatedAt = 100,
                    payloadJson = """{"kind":"log","record_client_uuid":"record-local","care_plan_client_uuid":null,"baby_client_uuid":null,"mime":"image/jpeg","width":null,"height":null,"byte_size":12}""",
                ).withAuthenticatedMediaBytes(rig.backend.mediaBytes)
            },
            cursor = 3,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.Foreground)
        assertThat(rig.media.getByClientUuid(peerUuid)?.deletedAt).isEqualTo(101)
        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.LocalWrite)

        assertThat(requireNotNull(rig.mediaFiles.readableFile(
            requireNotNull(rig.media.getByClientUuid(mediaUuid)).localUri,
        )).readBytes()).isEqualTo(rig.backend.mediaBytes)
        val relocated = requireNotNull(rig.media.getByClientUuid(replacementUuid))
        assertThat(relocated.recordId).isEqualTo(recordId)
        assertThat(relocated.updatedAt).isEqualTo(101)
        assertThat(relocated.deletedAt).isNull()
        assertThat(rig.mediaFiles.prepareUploadCounts["photos/between-downloads.jpg"]).isEqualTo(1)
        assertThat(rig.media.getByClientUuid(peerUuid)?.deletedAt).isEqualTo(101)
        assertThat(rig.mediaFiles.inspected).contains("photos/between-downloads.jpg")
        assertThat(requireNotNull(rig.mediaFiles.readableFile(relocated.localUri)).readBytes())
            .isEqualTo(replacementBytes)
        assertThat(rig.backend.pullCount).isEqualTo(1)
    }

    @Test
    fun fullCycleIdleCaptureStillRunsTechnicalMediaRepair() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 8,
        )
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 8,
            generation = session.pullGeneration,
            hasMore = false,
        )
        val mediaScansBefore = rig.media.listAllIncludingDeletedCalls

        rig.engine.synchronize(session, SyncTrigger.Foreground)

        // Guard snapshot + pre-commit technical repair.
        assertThat(rig.media.listAllIncludingDeletedCalls - mediaScansBefore).isAtLeast(2)
        assertThat(rig.backend.pullCount).isEqualTo(1)
    }
}
