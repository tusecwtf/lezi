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
        rig.mediaFiles.afterSaveDownloaded = {
            val current = requireNotNull(rig.media.getByClientUuid(peerUuid))
            rig.media.update(
                current.copy(
                    localUri = "photos/between-downloads.jpg",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 3,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.Foreground)

        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri)
            .isEqualTo("downloaded/$mediaUuid")
        assertThat(rig.media.getByClientUuid(peerUuid)?.localUri)
            .isEqualTo("photos/between-downloads.jpg")
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
