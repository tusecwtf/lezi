package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.OutboxEntity
import com.lezi.babylog.core.database.PendingReplicaCleanup
import com.lezi.babylog.core.database.PendingReplicaCleanupScope
import com.lezi.babylog.core.database.PendingReplicaCleanupStore
import com.lezi.babylog.core.database.RecordEntity
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalReplicaClearCoordinatorTest {
    @Test
    fun domainRecoveryAndFinalizerStayInsideOrderedExclusionOnReplicaFailure() = runTest {
        val rig = ClearRig()
        rig.media.seed(media("log-media", "log", "photos/log.jpg"))
        rig.mediaFiles.deleteFailures += IllegalStateException("replica cleanup failed")
        val events = mutableListOf<String>()
        var excluded = false
        val workflow = object : LocalClearWorkflow {
            override suspend fun <T> withLocalExclusion(block: suspend () -> T): T {
                events += "exclude"
                excluded = true
                return try {
                    block()
                } finally {
                    excluded = false
                }
            }

            override suspend fun clearRoom() {
                assertThat(excluded).isTrue()
                events += "room"
            }

            override suspend fun finishCommitted() {
                assertThat(excluded).isTrue()
                events += "domain-finish"
            }
        }

        val failure = rig.coordinator.clear(
            scope = LocalReplicaClearScope.RecordsOnly,
            workflow = workflow,
            recoverDomain = {
                events += "recover-domain"
                null
            },
        ).exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat(events)
            .containsExactly("recover-domain", "exclude", "room", "domain-finish")
            .inOrder()
    }

    @Test
    fun domainRecoveryFailurePreventsReplicaMarkerAndRoomClear() = runTest {
        val rig = ClearRig()
        var roomCalls = 0

        val failure = rig.coordinator.clear(
            scope = LocalReplicaClearScope.RecordsOnly,
            workflow = testLocalClearWorkflow { roomCalls += 1 },
            recoverDomain = { error("domain recovery failed") },
        ).exceptionOrNull()

        assertThat(failure).hasMessageThat().isEqualTo("domain recovery failed")
        assertThat(roomCalls).isEqualTo(0)
        assertThat(rig.pending.pending).isNull()
        assertThat(rig.transactions.runCount).isEqualTo(0)
    }

    @Test
    fun successfulCommittedRecoveryStillExecutesTheNewExplicitClear() = runTest {
        val rig = ClearRig()
        var roomCalls = 0

        val result = rig.coordinator.clear(
            scope = LocalReplicaClearScope.RecordsOnly,
            workflow = testLocalClearWorkflow { roomCalls += 1 },
            recoverDomain = { LocalClearRecoveryScope.RecordsOnly },
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(roomCalls).isEqualTo(1)
        assertThat(rig.pending.pending).isNull()
        assertThat(rig.transactions.runCount).isEqualTo(2)
    }

    @Test
    fun recordsOnlyRecoveryDoesNotShortCircuitARequestedAllLocalClear() = runTest {
        val rig = ClearRig()
        var roomCalls = 0

        val result = rig.coordinator.clear(
            scope = LocalReplicaClearScope.AllLocal,
            workflow = testLocalClearWorkflow { roomCalls += 1 },
            recoverDomain = { LocalClearRecoveryScope.RecordsOnly },
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(roomCalls).isEqualTo(1)
        assertThat(rig.transactions.runCount).isEqualTo(2)
    }

    @Test
    fun domainCancellationWinsWhenReplicaFinalizationAlsoFails() = runTest {
        val rig = ClearRig()
        rig.media.seed(media("log-media", "log", "photos/log.jpg"))
        val replicaFailure = IllegalStateException("replica cleanup failed")
        rig.mediaFiles.deleteFailures += replicaFailure
        val cancellation = CancellationException("domain finalizer cancelled")

        val failure = rig.coordinator.clear(
            scope = LocalReplicaClearScope.RecordsOnly,
            workflow = testLocalClearWorkflow(
                finishCommitted = { throw cancellation },
            ),
            recoverDomain = { null },
        ).exceptionOrNull()

        assertThat(failure).isSameInstanceAs(cancellation)
        assertThat(failure!!.suppressed.asList()).hasSize(1)
        assertThat(failure.suppressed.single()).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat(failure.suppressed.single().cause).isSameInstanceAs(replicaFailure)
    }

    @Test
    fun markerAndDomainClearShareOneTransactionBeforeFinalizationTransaction() = runTest {
        val rig = ClearRig()
        var callbackDepth = 0

        val result = rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) {
            callbackDepth = rig.transactions.depth
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(callbackDepth).isEqualTo(1)
        assertThat(rig.pending.stageDepths).containsExactly(1)
        assertThat(rig.pending.deleteDepths).containsExactly(1)
        assertThat(rig.transactions.runCount).isEqualTo(2)
        assertThat(rig.pending.pending).isNull()
    }

    @Test
    fun ownershipRecheckAndPhysicalDeleteShareTheFinalRoomWriteLease() = runTest {
        lateinit var rig: ClearRig
        val observingFiles = TransactionObservingMediaFileStore { rig.transactions.depth }
        rig = ClearRig(mediaFiles = observingFiles)
        rig.media.seed(media("log-media", "log", "photos/log.jpg"))

        val result = rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) {}

        assertThat(result.isSuccess).isTrue()
        assertThat(observingFiles.deleteDepths).containsExactly(1)
    }

    @Test
    fun markerStageFailurePreventsDomainClearAndReplicaCleanup() = runTest {
        val rig = ClearRig(
            session = joinedClearSession().copy(
                pullCursor = 9,
                pullGeneration = "known-generation",
            ),
        )
        rig.outbox.enqueue(outbox(entityType = "record", clientUuid = "record-local"))
        rig.pending.stageFailure = IllegalStateException("marker write failed")
        var callbackCalls = 0

        val failure = rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) {
            callbackCalls += 1
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().isEqualTo("marker write failed")
        assertThat(callbackCalls).isEqualTo(0)
        assertThat(rig.outbox.peek("family-a", 10).map(OutboxEntity::clientUuid))
            .containsExactly("record-local")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(9)
    }

    @Test
    fun committedFileFailureRetainsDurableMarkerAndRoomReplicaRows() = runTest {
        val rig = ClearRig()
        rig.media.seed(media("log-media", "log", "photos/log.jpg"))
        rig.outbox.enqueue(outbox(entityType = "media", clientUuid = "log-media"))
        val fileFailure = IllegalStateException("file delete failed")
        rig.mediaFiles.deleteFailures += fileFailure

        val failure = rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) {}
            .exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat((failure as LocalClearCommittedException).familyServerRetained).isTrue()
        assertThat(failure.cause).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure.cause).hasMessageThat().isEqualTo(fileFailure.message)
        assertThat(rig.pending.pending).isNotNull()
        assertThat(rig.media.getByClientUuid("log-media")).isNotNull()
        assertThat(rig.outbox.peek("family-a", 10)).isNotEmpty()
        assertThat(rig.mediaFiles.deleted).containsExactly("photos/log.jpg")
    }

    @Test
    fun cursorFailureRetainsMarkerAndFinalRoomRowsForRecreationRecovery() = runTest {
        val rig = ClearRig(
            session = joinedClearSession().copy(
                pullCursor = 9,
                pullGeneration = "known-generation",
            ),
        )
        rig.media.seed(media("log-media", "log", "photos/log.jpg"))
        rig.outbox.enqueue(outbox(entityType = "media", clientUuid = "log-media"))
        rig.preferences.failUpdateCursorAttempts = 1
        var callbackCalls = 0

        val failure = rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) {
            callbackCalls += 1
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat(rig.pending.pending).isNotNull()
        assertThat(rig.media.getByClientUuid("log-media")).isNotNull()
        assertThat(rig.outbox.peek("family-a", 10)).isNotEmpty()

        assertThat(rig.newCoordinator().recoverPending().isSuccess).isTrue()
        assertThat(callbackCalls).isEqualTo(1)
        assertThat(rig.pending.pending).isNull()
        assertThat(rig.media.getByClientUuid("log-media")).isNull()
        assertThat(rig.outbox.peek("family-a", 10)).isEmpty()
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("known-generation")
    }

    @Test
    fun finalRoomTransactionFailureRetainsMarkerForRetry() = runTest {
        val rig = ClearRig()
        rig.media.seed(media("log-media", "log", "photos/log.jpg"))
        rig.outbox.enqueue(outbox(entityType = "media", clientUuid = "log-media"))
        rig.transactions.failRunNumbers += 2

        val failure = rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) {}
            .exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat(failure!!.cause).hasMessageThat().isEqualTo("transaction 2 failed")
        assertThat(rig.pending.pending).isNotNull()
        assertThat(rig.media.getByClientUuid("log-media")).isNotNull()
        assertThat(rig.outbox.peek("family-a", 10)).isNotEmpty()

        assertThat(rig.newCoordinator().recoverPending().isSuccess).isTrue()
        assertThat(rig.pending.pending).isNull()
    }

    @Test
    fun recoveryDeletesOnlyCapturedMediaCreatedBeforeTheClear() = runTest {
        val rig = ClearRig()
        rig.media.seed(media("old-log-media", "log", "photos/old.jpg"))
        rig.mediaFiles.deleteFailures += IllegalStateException("process stopped")

        assertThat(
            rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) {}.isFailure,
        ).isTrue()
        rig.media.seed(media("new-log-media", "log", "photos/new.jpg"))

        assertThat(rig.newCoordinator().recoverPending().isSuccess).isTrue()

        assertThat(rig.media.getByClientUuid("old-log-media")).isNull()
        assertThat(rig.media.getByClientUuid("new-log-media")).isNotNull()
        assertThat(rig.mediaFiles.deleted)
            .containsExactly("photos/old.jpg", "photos/old.jpg")
    }

    @Test
    fun recoveryDoesNotDeleteACapturedPathReownedByNewMedia() = runTest {
        val rig = ClearRig()
        val reusedPath = "photos/reused.jpg"
        rig.media.seed(media("old-log-media", "log", reusedPath))
        rig.mediaFiles.deleteFailures += IllegalStateException("process stopped")

        assertThat(
            rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) {}.isFailure,
        ).isTrue()
        rig.media.seed(media("new-log-media", "log", reusedPath))

        assertThat(rig.newCoordinator().recoverPending().isSuccess).isTrue()

        assertThat(rig.media.getByClientUuid("old-log-media")).isNull()
        assertThat(rig.media.getByClientUuid("new-log-media")).isNotNull()
        assertThat(rig.mediaFiles.deleted).containsExactly(reusedPath)
    }

    @Test
    fun recordsOnlyClearsRecordAndPlanReplicaButPreservesAvatarAndGeneration() = runTest {
        val originalSession = joinedClearSession().copy(
            pullCursor = 9,
            pullGeneration = "known-generation",
            membershipId = "membership-a",
        )
        val rig = ClearRig(session = originalSession)
        rig.records.seed(record(payloadJson = """{"photos":["photos/record-inline.jpg"]}"""))
        rig.carePlans.seed(carePlan("""{"photos":["photos/plan-inline.jpg"]}"""))
        rig.media.seed(media("record-media", "log", "photos/record.jpg"))
        rig.media.seed(media("plan-media", "log", "photos/plan.jpg", carePlanId = 1))
        rig.media.seed(media("avatar-media", "avatar", "avatars/baby.jpg"))
        listOf(
            outbox("record", "record-local"),
            outbox("care_plan", "plan-local"),
            outbox("fulfillment_candidate", "candidate-local"),
            outbox("media", "record-media"),
            outbox("media", "plan-media"),
            outbox("media", "avatar-media"),
            outbox("baby", "baby-local"),
        ).forEach { rig.outbox.enqueue(it) }

        val result = rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) {
            rig.records.deleteAll()
            rig.carePlans.deleteAll()
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.preferences.current()).isEqualTo(originalSession.copy(pullCursor = 0))
        assertThat(rig.media.getByClientUuid("record-media")).isNull()
        assertThat(rig.media.getByClientUuid("plan-media")).isNull()
        assertThat(rig.media.getByClientUuid("avatar-media")).isNotNull()
        assertThat(rig.mediaFiles.deleted).containsExactly(
            "photos/record.jpg",
            "photos/plan.jpg",
            "photos/record-inline.jpg",
            "photos/plan-inline.jpg",
        )
        assertThat(rig.outbox.peek("family-a", 10).map(OutboxEntity::clientUuid))
            .containsExactly("avatar-media", "baby-local")
    }

    @Test
    fun recordsOnlyNeverDeletesAPathStillOwnedByAnAvatar() = runTest {
        val rig = ClearRig()
        val sharedPath = "avatars/shared.jpg"
        rig.records.seed(record(payloadJson = """{"photos":["$sharedPath"]}"""))
        rig.media.seed(media("record-shared", "log", sharedPath))
        rig.media.seed(media("avatar-shared", "avatar", sharedPath))

        val result = rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) {
            rig.records.deleteAll()
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.media.getByClientUuid("record-shared")).isNull()
        assertThat(rig.media.getByClientUuid("avatar-shared")).isNotNull()
        assertThat(rig.mediaFiles.deleted).doesNotContain(sharedPath)
    }

    @Test
    fun allLocalClearsEveryCapturedReplicaAndDropsGeneration() = runTest {
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
        rig.media.seed(media("log-media", "log", "photos/log.jpg"))
        rig.media.seed(media("avatar-media", "avatar", "avatars/baby.jpg"))
        rig.outbox.enqueue(outbox("record", "record-local"))
        rig.outbox.enqueue(outbox("baby", "other-family-baby", familyId = "family-b"))

        val result = rig.coordinator.clear(LocalReplicaClearScope.AllLocal) {
            rig.records.deleteAll()
            rig.babies.deleteAll()
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
        assertThat(rig.preferences.current().pullGeneration).isEmpty()
        assertThat(rig.media.listAllIncludingDeleted()).isEmpty()
        assertThat(rig.outbox.peek("family-a", 10)).isEmpty()
        assertThat(rig.outbox.peek("family-b", 10)).isEmpty()
        assertThat(rig.pending.pending).isNull()
    }

    @Test
    fun recordsOnlyChunksLargeCapturedMediaDeletes() = runTest {
        val rig = ClearRig()
        repeat(1_005) { index ->
            val uuid = "log-media-$index"
            rig.media.seed(media(uuid, "log", "photos/$index.jpg"))
            rig.outbox.enqueue(outbox("media", uuid))
        }

        val result = rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) {}

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.outbox.deleteEntityBatchSizes).containsExactly(400, 400, 205).inOrder()
        assertThat(rig.outbox.peek("family-a", 2_000)).isEmpty()
    }

    @Test
    fun recordsOnlyClearsRelevantOutboxAcrossStaleFamiliesWhenUnjoined() = runTest {
        val rig = ClearRig(session = SyncSession())
        rig.media.seed(media("stale-media", "log", "photos/stale.jpg"))
        listOf(
            outbox("record", "old-record", familyId = "family-old"),
            outbox("care_plan", "old-plan", familyId = "family-old"),
            outbox("fulfillment_candidate", "old-candidate", familyId = "family-old"),
            outbox("media", "stale-media", familyId = "family-old"),
            outbox("baby", "old-baby", familyId = "family-old"),
        ).forEach { rig.outbox.enqueue(it) }

        assertThat(rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) {}.isSuccess)
            .isTrue()

        assertThat(rig.outbox.peek("family-old", 10).map(OutboxEntity::clientUuid))
            .containsExactly("old-baby")
    }

    @Test
    fun committedCleanupRunsNonCancellableBeforePropagatingCancellation() = runTest {
        val gatedFiles = GatedDeleteMediaFileStore()
        val rig = ClearRig(mediaFiles = gatedFiles)
        rig.media.seed(media("log-media", "log", "photos/log.jpg"))

        val clearing = launch {
            rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) {}
        }
        gatedFiles.deleteStarted.await()
        clearing.cancel()
        gatedFiles.allowDelete.complete(Unit)
        clearing.join()

        assertThat(rig.pending.pending).isNull()
        assertThat(rig.media.getByClientUuid("log-media")).isNull()
        assertThat(clearing.isCancelled).isTrue()
    }

    @Test
    fun callerCancellationWinsWhenNonCancellableReplicaFinalizationFails() = runTest {
        val gatedFiles = GatedDeleteMediaFileStore()
        val rig = ClearRig(mediaFiles = gatedFiles)
        rig.media.seed(media("log-media", "log", "photos/log.jpg"))
        val replicaFailure = IllegalStateException("replica cleanup failed")
        gatedFiles.deleteFailures += replicaFailure
        val cancellation = CancellationException("caller stopped")
        var result: Result<Unit>? = null

        val clearing = launch {
            result = rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) {}
        }
        gatedFiles.deleteStarted.await()
        clearing.cancel(cancellation)
        gatedFiles.allowDelete.complete(Unit)
        clearing.join()

        val failure = result!!.exceptionOrNull()
        assertThat(failure).isSameInstanceAs(cancellation)
        assertThat(failure!!.suppressed.asList()).hasSize(1)
        assertThat(failure.suppressed.single()).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat(failure.suppressed.single().cause).isSameInstanceAs(replicaFailure)
        assertThat(rig.pending.pending).isNotNull()
    }

    @Test
    fun clearWaitsForItsSharedBarrier() = runTest {
        val rig = ClearRig()
        rig.barrier.lock()

        val clearing = async {
            rig.coordinator.clear(LocalReplicaClearScope.RecordsOnly) {}
        }
        runCurrent()
        assertThat(clearing.isCompleted).isFalse()

        rig.barrier.unlock()

        assertThat(clearing.await().isSuccess).isTrue()
    }
}

private suspend fun LocalReplicaClearCoordinator.clear(
    scope: LocalReplicaClearScope,
    clearRoom: suspend () -> Unit,
): Result<Unit> = clear(
    scope = scope,
    workflow = testLocalClearWorkflow(clearRoom = clearRoom),
    recoverDomain = { null },
)

private fun testLocalClearWorkflow(
    clearRoom: suspend () -> Unit = {},
    finishCommitted: suspend () -> Unit = {},
): LocalClearWorkflow = object : LocalClearWorkflow {
    override suspend fun <T> withLocalExclusion(block: suspend () -> T): T = block()
    override suspend fun clearRoom() = clearRoom.invoke()
    override suspend fun finishCommitted() = finishCommitted.invoke()
}

private class ClearRig(
    session: SyncSession = joinedClearSession(),
    val mediaFiles: TestMediaFileStore = TestMediaFileStore(),
) {
    val barrier = Mutex()
    val preferences = MemorySyncPreferences(session)
    val outbox = MemoryOutboxDao()
    val records = MemoryRecordDao()
    val carePlans = MemoryCarePlanDao()
    val babies = MemoryBabyDao()
    val media = MemoryMediaDao()
    val transactions = ClearTransactionRunner()
    val pending = MemoryPendingReplicaCleanupStore { transactions.depth }
    val coordinator = newCoordinator()

    fun newCoordinator() = LocalReplicaClearCoordinator(
        barrier = barrier,
        preferences = preferences,
        outboxDao = outbox,
        recordDao = records,
        carePlanDao = carePlans,
        babyDao = babies,
        mediaDao = media,
        mediaFiles = mediaFiles,
        transactionRunner = transactions,
        pendingStore = pending,
    )
}

private class ClearTransactionRunner : DatabaseTransactionRunner {
    var runCount = 0
    var depth = 0
    val failRunNumbers = mutableSetOf<Int>()

    override suspend fun <T> run(block: suspend () -> T): T {
        runCount += 1
        val currentRun = runCount
        if (failRunNumbers.remove(currentRun)) {
            error("transaction $currentRun failed")
        }
        depth += 1
        return try {
            block()
        } finally {
            depth -= 1
        }
    }
}

private class MemoryPendingReplicaCleanupStore(
    private val transactionDepth: () -> Int,
) : PendingReplicaCleanupStore {
    var pending: PendingReplicaCleanup? = null
    var stageFailure: Throwable? = null
    val stageDepths = mutableListOf<Int>()
    val deleteDepths = mutableListOf<Int>()

    override suspend fun load(): PendingReplicaCleanup? = pending

    override suspend fun stage(pending: PendingReplicaCleanup) {
        stageDepths += transactionDepth()
        stageFailure?.let { throw it }
        check(this.pending == null)
        this.pending = pending
    }

    override suspend fun delete() {
        deleteDepths += transactionDepth()
        pending = null
    }
}

private class GatedDeleteMediaFileStore : TestMediaFileStore() {
    val deleteStarted = CompletableDeferred<Unit>()
    val allowDelete = CompletableDeferred<Unit>()

    override suspend fun delete(localUri: String) {
        deleteStarted.complete(Unit)
        allowDelete.await()
        super.delete(localUri)
    }
}

private class TransactionObservingMediaFileStore(
    private val transactionDepth: () -> Int,
) : TestMediaFileStore() {
    val deleteDepths = mutableListOf<Int>()

    override suspend fun delete(localUri: String) {
        deleteDepths += transactionDepth()
        super.delete(localUri)
    }
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
    carePlanId: Long? = null,
) = MediaAssetEntity(
    clientUuid = clientUuid,
    kind = kind,
    recordId = if (carePlanId == null && kind == "log") 1 else null,
    carePlanId = carePlanId,
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

private fun carePlan(payloadJson: String) = CarePlanEntity(
    id = 1,
    clientUuid = "plan-local",
    babyId = 1,
    type = "formula",
    scheduledAt = 120,
    scheduledZoneId = "Asia/Shanghai",
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
