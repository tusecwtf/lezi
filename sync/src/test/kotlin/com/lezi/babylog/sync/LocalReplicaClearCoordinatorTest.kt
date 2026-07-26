package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.OutboxEntity
import com.lezi.babylog.core.database.RecordEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalReplicaClearCoordinatorTest {
    @Test
    fun failureBeforeDomainCommitLeavesReplicaCleanupUntouched() = runTest {
        val rig = ClearRig(
            session = joinedClearSession().copy(
                pullCursor = 9,
                pullGeneration = "known-generation",
            ),
        )
        rig.outbox.enqueue(outbox(entityType = "record", clientUuid = "record-local"))

        val failure = rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) {
            error("domain transaction failed")
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().isEqualTo("domain transaction failed")
        assertThat(rig.outbox.peek("family-a", 10).map(OutboxEntity::clientUuid))
            .containsExactly("record-local")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(9)
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("known-generation")
    }

    @Test
    fun missingCommitMarkerFailsClosedBeforeReplicaCleanup() = runTest {
        val rig = ClearRig(
            session = joinedClearSession().copy(
                pullCursor = 9,
                pullGeneration = "known-generation",
            ),
        )
        rig.outbox.enqueue(outbox(entityType = "record", clientUuid = "record-local"))

        val failure = rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) {
            // Domain callback returned without confirming its transaction.
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo("本机记录清除未确认领域事务已提交")
        assertThat(rig.outbox.peek("family-a", 10).map(OutboxEntity::clientUuid))
            .containsExactly("record-local")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(9)
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("known-generation")
    }

    @Test
    fun committedFileCleanupFailureIsReportedAfterCompensationRetry() = runTest {
        val rig = ClearRig()
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = "log-media",
                kind = "log",
                recordId = 1,
                localUri = "photos/log.jpg",
                createdAt = 1,
            ),
        )
        val fileFailure = IllegalStateException("file delete failed")
        rig.mediaFiles.deleteFailures += fileFailure

        val failure = rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) { committed ->
            committed()
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat((failure as LocalClearCommittedException).familyServerRetained).isTrue()
        assertThat(failure.cause).isSameInstanceAs(fileFailure)
        assertThat(rig.mediaFiles.deleted).containsExactly(
            "photos/log.jpg",
            "photos/log.jpg",
        )
        assertThat(rig.media.getByClientUuid("log-media")).isNull()
    }

    @Test
    fun checkpointFailureRetriesEveryCleanupStepIdempotently() = runTest {
        val rig = ClearRig(
            session = joinedClearSession().copy(
                pullCursor = 9,
                pullGeneration = "known-generation",
            ),
        )
        rig.outbox.enqueue(outbox(entityType = "media", clientUuid = "log-media"))
        rig.media.seed(
            media(
                clientUuid = "log-media",
                kind = "log",
                localUri = "photos/log.jpg",
            ),
        )
        rig.preferences.failUpdateCursorAttempts = 1

        val failure = rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) { it() }
            .exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat(failure!!.cause).hasMessageThat().isEqualTo("cursor update failed")
        assertThat(rig.outbox.peek("family-a", 10)).isEmpty()
        assertThat(rig.media.getByClientUuid("log-media")).isNull()
        assertThat(rig.mediaFiles.deleted).containsExactly(
            "photos/log.jpg",
            "photos/log.jpg",
        )
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("known-generation")

        assertThat(
            rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) { it() }.isSuccess,
        ).isTrue()
    }

    @Test
    fun committedCleanupPreservesSuppressedRetryFailureAndCanBeRetried() = runTest {
        val rig = ClearRig()
        rig.outbox.enqueue(outbox(entityType = "record", clientUuid = "record-local"))
        rig.outbox.failDeleteTypeAttempts = 2

        val failure = rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) { committed ->
            committed()
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat((failure as LocalClearCommittedException).familyServerRetained).isTrue()
        assertThat(failure.cause).hasMessageThat().isEqualTo("outbox delete failed")
        assertThat(failure.cause!!.suppressed.asList()).hasSize(1)
        assertThat(failure.cause!!.suppressed.single())
            .hasMessageThat()
            .isEqualTo("outbox delete failed")
        assertThat(rig.outbox.peek("family-a", 10).map(OutboxEntity::clientUuid))
            .containsExactly("record-local")

        assertThat(
            rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) { it() }.isSuccess,
        ).isTrue()
        assertThat(rig.outbox.peek("family-a", 10)).isEmpty()
    }

    @Test
    fun committedSnapshotSurvivesDoubleFailureUntilTheNextClearCall() = runTest {
        val rig = ClearRig()
        val babyId = rig.babies.seed(baby(avatarPath = "avatars/payload-only.jpg"))
        rig.records.seed(
            record(
                babyId = babyId,
                payloadJson = """{"photos":["photos/payload-only.jpg"]}""",
            ),
        )
        val firstFailure = IllegalStateException("first file delete failed")
        val retryFailure = IllegalStateException("retry file delete failed")
        rig.mediaFiles.deleteFailures += firstFailure
        rig.mediaFiles.deleteFailures += retryFailure
        var callbackCalls = 0
        val clearDomain: suspend (() -> Unit) -> Unit = { committed ->
            callbackCalls++
            rig.records.deleteAll()
            rig.babies.deleteAll()
            committed()
        }

        val failure = rig.coordinator.clear(LocalReplicaClearScope.AllLocal, clearDomain)
            .exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat(failure!!.cause).isSameInstanceAs(firstFailure)
        assertThat(failure.cause!!.suppressed.asList()).containsExactly(retryFailure)
        assertThat(rig.records.listAllIncludingDeleted()).isEmpty()
        assertThat(rig.babies.listAllIncludingDeleted()).isEmpty()

        assertThat(
            rig.coordinator.clear(LocalReplicaClearScope.AllLocal, clearDomain).isSuccess,
        ).isTrue()
        assertThat(callbackCalls).isEqualTo(2)
        assertThat(rig.mediaFiles.deleted).containsExactly(
            "photos/payload-only.jpg",
            "photos/payload-only.jpg",
            "photos/payload-only.jpg",
            "avatars/payload-only.jpg",
        ).inOrder()
    }

    @Test
    fun recordsOnlyScopeClearsRecordReplicaAndPreservesAvatarReplica() = runTest {
        val rig = ClearRig(
            session = joinedClearSession().copy(
                pullCursor = 9,
                pullGeneration = "known-generation",
            ),
        )
        rig.records.seed(record(payloadJson = """{"photos":["photos/inline.jpg"]}"""))
        rig.media.seed(media(clientUuid = "log-media", kind = "log", localUri = "photos/log.jpg"))
        rig.media.seed(
            media(
                clientUuid = "avatar-media",
                kind = "avatar",
                localUri = "avatars/baby.jpg",
            ),
        )
        listOf(
            outbox(entityType = "record", clientUuid = "record-local"),
            outbox(entityType = "care_plan", clientUuid = "plan-local"),
            outbox(entityType = "fulfillment_candidate", clientUuid = "candidate-local"),
            outbox(entityType = "media", clientUuid = "log-media"),
            outbox(entityType = "media", clientUuid = "avatar-media"),
            outbox(entityType = "baby", clientUuid = "baby-local"),
        ).forEach { rig.outbox.enqueue(it) }

        val result = rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) { committed ->
            rig.records.deleteAll()
            committed()
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("known-generation")
        assertThat(rig.records.listAllIncludingDeleted()).isEmpty()
        assertThat(rig.media.getByClientUuid("log-media")).isNull()
        assertThat(rig.media.getByClientUuid("avatar-media")).isNotNull()
        assertThat(rig.mediaFiles.deleted).containsExactly(
            "photos/log.jpg",
            "photos/inline.jpg",
        )
        assertThat(rig.outbox.peek("family-a", 10).map(OutboxEntity::clientUuid))
            .containsExactly("avatar-media", "baby-local")
    }

    @Test
    fun allLocalScopeClearsEveryReplicaAndDropsGeneration() = runTest {
        val rig = ClearRig(
            session = joinedClearSession().copy(
                pullCursor = 11,
                pullGeneration = "known-generation",
            ),
        )
        val babyId = rig.babies.seed(baby(avatarPath = "avatars/baby.jpg"))
        rig.records.seed(
            record(
                babyId = babyId,
                payloadJson = """{"photos":["photos/inline.jpg"]}""",
            ),
        )
        rig.media.seed(media(clientUuid = "log-media", kind = "log", localUri = "photos/log.jpg"))
        rig.media.seed(
            media(
                clientUuid = "avatar-media",
                kind = "avatar",
                localUri = "avatars/baby.jpg",
            ),
        )
        rig.outbox.enqueue(outbox(entityType = "record", clientUuid = "record-local"))
        rig.outbox.enqueue(
            outbox(
                familyId = "family-b",
                entityType = "baby",
                clientUuid = "other-family-baby",
            ),
        )

        val result = rig.coordinator.clear(LocalReplicaClearScope.AllLocal) { committed ->
            rig.records.deleteAll()
            rig.babies.deleteAll()
            committed()
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
        assertThat(rig.preferences.current().pullGeneration).isEmpty()
        assertThat(rig.records.listAllIncludingDeleted()).isEmpty()
        assertThat(rig.babies.listAllIncludingDeleted()).isEmpty()
        assertThat(rig.media.listAllIncludingDeleted()).isEmpty()
        assertThat(rig.outbox.peek("family-a", 10)).isEmpty()
        assertThat(rig.outbox.peek("family-b", 10)).isEmpty()
        assertThat(rig.mediaFiles.deleted).containsExactly(
            "photos/log.jpg",
            "avatars/baby.jpg",
            "photos/inline.jpg",
        )
    }

    @Test
    fun recordsOnlyScopeChunksLargeMediaOutboxDeletes() = runTest {
        val rig = ClearRig()
        repeat(1_005) { index ->
            val uuid = "log-media-$index"
            rig.media.seed(
                media(
                    clientUuid = uuid,
                    kind = "log",
                    localUri = "photos/$index.jpg",
                ),
            )
            rig.outbox.enqueue(outbox(entityType = "media", clientUuid = uuid))
        }

        val result = rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) { it() }

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.outbox.deleteEntityBatchSizes).containsExactly(400, 400, 205).inOrder()
        assertThat(rig.outbox.peek("family-a", 2_000)).isEmpty()
    }

    @Test
    fun clearWaitsForItsSharedBarrier() = runTest {
        val rig = ClearRig()
        rig.barrier.lock()

        val clearing = async {
            rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) { it() }
        }
        runCurrent()
        assertThat(clearing.isCompleted).isFalse()

        rig.barrier.unlock()

        assertThat(clearing.await().isSuccess).isTrue()
    }
}

private class ClearRig(
    session: SyncSession = joinedClearSession(),
) {
    val barrier = Mutex()
    val preferences = MemorySyncPreferences(session)
    val outbox = MemoryOutboxDao()
    val records = MemoryRecordDao()
    val babies = MemoryBabyDao()
    val media = MemoryMediaDao()
    val mediaFiles = TestMediaFileStore()
    val coordinator = LocalReplicaClearCoordinator(
        barrier = barrier,
        preferences = preferences,
        outboxDao = outbox,
        recordDao = records,
        babyDao = babies,
        mediaDao = media,
        mediaFiles = mediaFiles,
    )
}

private fun outbox(
    entityType: String,
    clientUuid: String,
    familyId: String = "family-a",
) = OutboxEntity(
    familyId = familyId,
    entityType = entityType,
    clientUuid = clientUuid,
    payloadJson = "{}",
    updatedAt = 1,
)

private fun media(
    clientUuid: String,
    kind: String,
    localUri: String,
) = MediaAssetEntity(
    clientUuid = clientUuid,
    kind = kind,
    recordId = 1,
    localUri = localUri,
    createdAt = 1,
)

private fun record(
    babyId: Long = 1,
    payloadJson: String = "{}",
) = RecordEntity(
    clientUuid = "record-local",
    babyId = babyId,
    type = "formula",
    timestamp = 120,
    createdByUserId = 1,
    payloadJson = payloadJson,
    updatedAt = 120,
)

private fun baby(avatarPath: String?) = BabyEntity(
    familyId = 1,
    nickname = "本地宝宝",
    birthdayEpochDay = 20_000,
    themeColorArgb = 0,
    clientUuid = "baby-local",
    updatedAt = 100,
    avatarPath = avatarPath,
)

private fun joinedClearSession() = SyncSession(
    familyId = "family-a",
    familyToken = "token",
    deviceId = "device-a",
    role = FamilyRole.Owner,
    serverHost = "192.168.1.20",
    serverPort = 8787,
    allowedSsids = listOf("Home"),
)
