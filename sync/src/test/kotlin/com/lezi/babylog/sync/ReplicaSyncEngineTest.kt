package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.FamilyEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ReplicaSyncEngineTest {
    @Test
    fun pullToRefreshAppliesEveryPageAndPersistsTheCompletedCheckpoint() = runTest {
        val rig = ReplicaEngineRig(joinedReplicaSession())
        rig.backend.pullResults += PullResult(
            entities = listOf(remoteReplicaBaby()),
            cursor = 1,
            generation = "generation-a",
            hasMore = true,
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 2,
            generation = "generation-a",
            hasMore = false,
        )

        val outcome = rig.engine.synchronize(
            session = rig.preferences.current(),
            trigger = SyncTrigger.PullToRefresh,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.backend.pullCursors).containsExactly(0L, 1L).inOrder()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(2)
        assertThat(rig.babies.getByClientUuid("baby-remote")).isNotNull()
    }

    @Test
    fun fullPageWithoutContinuationMarkerFailsClosedBeforeApply() = runTest {
        val rig = ReplicaEngineRig(joinedReplicaSession())
        rig.backend.nextPull = PullResult(
            entities = List(200) { index ->
                remoteReplicaBaby().copy(clientUuid = "baby-full-page-$index")
            },
            cursor = 200,
            hasMore = null,
        )

        val failure = runCatching {
            rig.engine.synchronize(
                session = rig.preferences.current(),
                trigger = SyncTrigger.PullToRefresh,
            )
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("缺少 has_more")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
        assertThat(rig.babies.getByClientUuid("baby-full-page-0")).isNull()
    }

    @Test
    fun continuationCannotExceedTheBoundedPageLimit() = runTest {
        val rig = ReplicaEngineRig(joinedReplicaSession())
        repeat(500) { index ->
            rig.backend.pullResults += PullResult(
                entities = emptyList(),
                cursor = index.toLong() + 1,
                hasMore = true,
            )
        }

        val failure = runCatching {
            rig.engine.synchronize(
                session = rig.preferences.current(),
                trigger = SyncTrigger.PullToRefresh,
            )
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("500 页上限")
        assertThat(rig.backend.pullCount).isEqualTo(500)
    }

    @Test
    fun continuationMustAdvanceTheCursor() = runTest {
        val session = joinedReplicaSession().copy(
            pullCursor = 5,
            pullGeneration = "generation-a",
        )
        val rig = ReplicaEngineRig(session)
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 5,
            generation = "generation-a",
            hasMore = true,
        )

        val failure = runCatching {
            rig.engine.synchronize(
                session = session,
                trigger = SyncTrigger.PullToRefresh,
            )
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("cursor 未推进")
        assertThat(rig.backend.pullCursors).containsExactly(5L)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(5)
    }

    @Test
    fun cursorAheadRequeuesTheCleanReplicaBeforeTheAuthoritativePull() = runTest {
        val session = joinedReplicaSession().copy(
            pullCursor = 9,
            pullGeneration = "old-generation",
        )
        val rig = ReplicaEngineRig(session)
        rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        rig.backend.pullFailures += SyncHttpException(
            statusCode = 409,
            responseBody = """
                {
                  "detail":{
                    "code":"cursor_ahead",
                    "action":"full_resync",
                    "reset_cursor":0,
                    "server_cursor":1
                  }
                }
            """.trimIndent(),
        )
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 2,
            generation = "new-generation",
        )

        val outcome = rig.engine.synchronize(
            session = session,
            trigger = SyncTrigger.PullToRefresh,
        )

        assertThat(rig.backend.pullCursors).containsExactly(9L, 0L, 2L).inOrder()
        assertThat(
            rig.backend.pushes
                .flatMap(PushedBatch::entities)
                .map(SyncEntity::clientUuid),
        ).contains("baby-local")
        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(2)
    }

    @Test
    fun failedAtomicMediaDownloadRetriesWithoutPublishingAPartialReplica() = runTest {
        val rig = ReplicaEngineRig(joinedReplicaSession())
        rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val mediaUuid = "56565656-5656-5656-5656-565656565656"
        val recordUuid = "record-media-retry"
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord(recordUuid),
                remoteReplicaMedia(mediaUuid, recordUuid),
            ),
            cursor = 1,
            generation = "generation-a",
        )
        rig.backend.getMediaFailure = IllegalStateException("download interrupted")

        val firstFailure = runCatching {
            rig.engine.synchronize(
                session = rig.preferences.current(),
                trigger = SyncTrigger.PullToRefresh,
            )
        }.exceptionOrNull()

        assertThat(firstFailure).hasMessageThat().contains("download interrupted")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
        assertThat(rig.records.getByClientUuid(recordUuid)).isNull()
        assertThat(rig.media.getByClientUuid(mediaUuid)).isNull()

        rig.backend.getMediaFailure = null
        val outcome = rig.engine.synchronize(
            session = rig.preferences.current(),
            trigger = SyncTrigger.PullToRefresh,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
        assertThat(rig.records.getByClientUuid(recordUuid)).isNotNull()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri)
            .isEqualTo("downloaded/$mediaUuid")
    }

    @Test
    fun cancellationEscapesAndDoesNotAdvanceThePullCheckpoint() = runTest {
        val rig = ReplicaEngineRig(joinedReplicaSession())
        rig.backend.pullStarted = CompletableDeferred()
        rig.backend.releasePull = CompletableDeferred()
        val syncing = async {
            rig.engine.synchronize(
                session = rig.preferences.current(),
                trigger = SyncTrigger.PullToRefresh,
            )
        }
        rig.backend.pullStarted!!.await()

        syncing.cancel()
        val failure = runCatching { syncing.await() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
    }
}

private class ReplicaEngineRig(
    session: SyncSession,
) {
    val backend = RecordingSyncBackend()
    val preferences = MemorySyncPreferences(session)
    val outbox = MemoryOutboxDao()
    val records = MemoryRecordDao()
    val carePlans = MemoryCarePlanDao()
    val fulfillmentCandidates = MemoryFulfillmentCandidateDao()
    val babies = MemoryBabyDao()
    val media = MemoryMediaDao()
    val customItems = MemoryCustomItemDao()
    val mediaFiles = TestMediaFileStore()
    val transactions = RecordingTransactionRunner()
    val families = MemoryFamilyDao().apply {
        seed(FamilyEntity(id = 1, ownerUserId = 1, createdAt = 0))
    }
    val engine = ReplicaSyncEngine(
        backend = backend,
        preferences = preferences,
        outboxDao = outbox,
        recordDao = records,
        carePlanDao = carePlans,
        babyDao = babies,
        mediaDao = media,
        customItemDao = customItems,
        familyDao = families,
        clock = object : PolicyClock {
            override fun nowMillis(): Long = 1_000
        },
        mediaFiles = mediaFiles,
        transactionRunner = transactions,
        carePlanAppliedListener = NoOpCarePlanFamilyAppliedListener(),
        fulfillmentCandidateDao = fulfillmentCandidates,
        requireRemoteAllowed = {},
        remoteCapabilities = { setOf(CAPABILITY_ATOMIC_BUNDLE) },
    )
}

private fun joinedReplicaSession() = SyncSession(
    familyId = "family-a",
    familyToken = "token-a",
    deviceId = "device-a",
    role = FamilyRole.Owner,
    serverHost = "192.168.50.4",
    serverPort = 8765,
    allowedSsids = listOf("Home"),
)

private fun remoteReplicaBaby() = SyncEntity(
    type = "baby",
    clientUuid = "baby-remote",
    updatedAt = 100,
    deletedAt = null,
    payloadJson = """
        {
          "nickname":"远端宝宝",
          "birthday":"2024-01-01",
          "sort_order":0
        }
    """.trimIndent(),
)

private fun localReplicaBaby() = BabyEntity(
    familyId = 1,
    nickname = "本地宝宝",
    birthdayEpochDay = 20_000,
    themeColorArgb = 0,
    clientUuid = "baby-local",
    updatedAt = 100,
)

private fun remoteReplicaRecord(clientUuid: String) = SyncEntity(
    type = "record",
    clientUuid = clientUuid,
    payloadJson = """
        {
          "baby_client_uuid":"baby-local",
          "created_by_device_id":"device-b",
          "type":"formula",
          "timestamp":210,
          "payload_json":{"amount_ml":90},
          "schema_version":1
        }
    """.trimIndent(),
    updatedAt = 210,
)

private fun remoteReplicaMedia(
    clientUuid: String,
    recordClientUuid: String,
) = SyncEntity(
    type = "media",
    clientUuid = clientUuid,
    payloadJson = """
        {
          "kind":"log",
          "record_client_uuid":"$recordClientUuid",
          "mime":"image/jpeg",
          "byte_size":4
        }
    """.trimIndent(),
    updatedAt = 210,
)
