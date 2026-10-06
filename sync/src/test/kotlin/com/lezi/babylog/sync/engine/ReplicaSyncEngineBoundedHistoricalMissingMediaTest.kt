package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.receiptFor
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Public seam: [ReplicaSyncEngine.synchronize] plus recording
 * [com.lezi.babylog.sync.MemoryMediaDao.listMissingLocalBytes] and
 * [com.lezi.babylog.sync.RecordingSyncBackend.getMedia].
 *
 * Historical missing media is a bounded full-cycle queue outside the pull
 * page loop. This-page 记录同步包 / 计划同步包 still stage bytes before apply.
 */
class ReplicaSyncEngineBoundedHistoricalMissingMediaTest {

    @Test
    fun emptyPullPagesDoNotListMissingLocalBytes() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 4)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedHistoricalMissing(rig, HISTORICAL_A)
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 5,
            generation = session.pullGeneration,
            hasMore = true,
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 5,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.Foreground)

        assertThat(rig.media.listMissingLocalBytesCalls).isEqualTo(1)
        assertThat(rig.backend.pullCount).isEqualTo(2)
    }

    @Test
    fun localWriteDoesNotGetHistoricalMissingMedia() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 9,
        )
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedHistoricalMissing(rig, HISTORICAL_A, HISTORICAL_B)
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-localwrite-no-historical-get",
                babyId = requireNotNull(rig.babies.getByClientUuid("baby-local")).id,
                type = "formula",
                timestamp = 200,
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 200,
                syncDirty = true,
                baseVersion = "v-r1",
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(remoteReplicaRecord("must-not-pull")),
            cursor = session.pullCursor,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.mediaGets).isEmpty()
        assertThat(rig.media.listMissingLocalBytesCalls).isEqualTo(0)
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.media.getByClientUuid(HISTORICAL_A)?.localUri).isEmpty()
        assertThat(rig.media.getByClientUuid(HISTORICAL_B)?.localUri).isEmpty()
    }

    @Test
    fun fullCycleConsumesAtMostThreeHistoricalGetsOutsidePageLoop() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 2)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        // Seed newest-id first so id order would GET E before A.
        seedHistoricalMissing(
            rig,
            HISTORICAL_E,
            HISTORICAL_D,
            HISTORICAL_C,
            HISTORICAL_B,
            HISTORICAL_A,
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 3,
            generation = session.pullGeneration,
            hasMore = true,
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 3,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.Foreground)

        assertThat(rig.media.listMissingLocalBytesCalls).isEqualTo(1)
        assertThat(rig.backend.mediaGets)
            .containsExactly(HISTORICAL_A, HISTORICAL_B, HISTORICAL_C)
            .inOrder()
        assertThat(rig.media.getByClientUuid(HISTORICAL_A)?.localUri)
            .isEqualTo("downloaded/$HISTORICAL_A")
        assertThat(rig.media.getByClientUuid(HISTORICAL_B)?.localUri)
            .isEqualTo("downloaded/$HISTORICAL_B")
        assertThat(rig.media.getByClientUuid(HISTORICAL_C)?.localUri)
            .isEqualTo("downloaded/$HISTORICAL_C")
        assertThat(rig.media.getByClientUuid(HISTORICAL_D)?.localUri).isEmpty()
        assertThat(rig.media.getByClientUuid(HISTORICAL_E)?.localUri).isEmpty()
        assertThat(rig.backend.pullCount).isEqualTo(2)
    }

    @Test
    fun fullCycleStopsHistoricalGetsOnceDecodedBytesReachEightMebibytes() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 6)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedHistoricalMissing(rig, HISTORICAL_A, HISTORICAL_B)
        rig.backend.mediaBytesByUuid[HISTORICAL_A] = ByteArray(8 * 1024 * 1024) { 1 }
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 6,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        assertThat(rig.backend.mediaGets).containsExactly(HISTORICAL_A)
        assertThat(rig.media.getByClientUuid(HISTORICAL_A)?.localUri)
            .isEqualTo("downloaded/$HISTORICAL_A")
        assertThat(rig.media.getByClientUuid(HISTORICAL_B)?.localUri).isEmpty()
    }

    @Test
    fun thisPageRecordAndPlanPackagesStageMediaBeforeApply() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 1)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee00"
        seedHistoricalMissing(rig, HISTORICAL_A, babyClientUuid = babyUuid)
        val recordUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee01"
        val planUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee02"
        val recordMediaUuid = "11111111-1111-4111-8111-111111111111"
        val planMediaUuid = "22222222-2222-4222-8222-222222222222"
        val visibleDuringGets = mutableListOf<Pair<Boolean, Boolean>>()
        rig.backend.onGetMedia = { _ ->
            visibleDuringGets += (
                rig.records.getByClientUuid(recordUuid) != null
            ) to (
                rig.carePlans.getByClientUuid(planUuid) != null
            )
        }
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord(recordUuid, babyClientUuid = babyUuid),
                remoteReplicaMedia(recordMediaUuid, recordUuid),
                remoteReplicaCarePlan(planUuid, babyClientUuid = babyUuid),
                remoteReplicaPlanMedia(planMediaUuid, planUuid),
            ),
            cursor = 2,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.Foreground)

        assertThat(rig.backend.mediaGets)
            .containsExactly(recordMediaUuid, planMediaUuid, HISTORICAL_A)
            .inOrder()
        assertThat(visibleDuringGets).containsExactly(
            false to false,
            false to false,
            true to true,
        )
        assertThat(rig.records.getByClientUuid(recordUuid)).isNotNull()
        assertThat(rig.carePlans.getByClientUuid(planUuid)).isNotNull()
        assertThat(rig.media.getByClientUuid(recordMediaUuid)?.localUri)
            .isEqualTo("downloaded/$recordMediaUuid")
        assertThat(rig.media.getByClientUuid(planMediaUuid)?.localUri)
            .isEqualTo("downloaded/$planMediaUuid")
        assertThat(rig.media.getByClientUuid(HISTORICAL_A)?.localUri)
            .isEqualTo("downloaded/$HISTORICAL_A")
    }

    @Test
    fun historical404SkipsIdentityAndContinuesSameCycleWithoutPermanentSkip() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 8)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedHistoricalMissing(rig, HISTORICAL_A, HISTORICAL_B, HISTORICAL_C)
        rig.backend.getMediaFailureByUuid[HISTORICAL_A] =
            SyncHttpException(404, "historical missing blob")
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 8,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.Foreground)

        assertThat(rig.backend.mediaGets)
            .containsExactly(HISTORICAL_A, HISTORICAL_B, HISTORICAL_C)
            .inOrder()
        assertThat(rig.media.getByClientUuid(HISTORICAL_A)?.localUri).isEmpty()
        assertThat(rig.media.getByClientUuid(HISTORICAL_B)?.localUri)
            .isEqualTo("downloaded/$HISTORICAL_B")
        assertThat(rig.media.getByClientUuid(HISTORICAL_C)?.localUri)
            .isEqualTo("downloaded/$HISTORICAL_C")

        rig.backend.mediaGets.clear()
        rig.backend.getMediaFailureByUuid.remove(HISTORICAL_A)
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 8,
            generation = session.pullGeneration,
            hasMore = false,
        )
        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        assertThat(rig.backend.mediaGets).contains(HISTORICAL_A)
        assertThat(rig.media.getByClientUuid(HISTORICAL_A)?.localUri)
            .isEqualTo("downloaded/$HISTORICAL_A")
    }

    private fun seedHistoricalMissing(
        rig: ReplicaEngineRig,
        vararg clientUuids: String,
        babyClientUuid: String = "baby-local",
    ) {
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = babyClientUuid,
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = "record-historical-host",
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
        clientUuids.forEach { uuid ->
            rig.media.seed(
                MediaAssetEntity(
                    recordId = recordId,
                    clientUuid = uuid,
                    kind = "log",
                    localUri = "",
                    remoteUri = rig.preferences.current().receiptFor(uuid),
                    mime = "image/jpeg",
                    byteSize = 12,
                    createdAt = 100,
                    updatedAt = 100,
                    syncDirty = false,
                ),
            )
        }
    }

    private companion object {
        const val HISTORICAL_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"
        const val HISTORICAL_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2"
        const val HISTORICAL_C = "cccccccc-cccc-4ccc-8ccc-ccccccccccc3"
        const val HISTORICAL_D = "dddddddd-dddd-4ddd-8ddd-ddddddddddd4"
        const val HISTORICAL_E = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee5"
    }
}
