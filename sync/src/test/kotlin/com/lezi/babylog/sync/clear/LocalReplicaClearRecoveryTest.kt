package com.lezi.babylog.sync.clear
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.PendingReplicaCleanup
import com.lezi.babylog.core.database.LocalDataClearScope
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
import com.lezi.babylog.sync.LocalClearWorkflow
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.MemoryBabyDao
import com.lezi.babylog.sync.MemoryCarePlanDao
import com.lezi.babylog.sync.MemoryMediaDao
import com.lezi.babylog.sync.MemoryRecordDao
import com.lezi.babylog.sync.MemorySyncPreferences
import com.lezi.babylog.sync.TestMediaFileStore

@OptIn(ExperimentalCoroutinesApi::class)
// Contract-cluster split (ticket 08).
class LocalReplicaClearRecoveryTest {
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
            scope = LocalDataClearScope.RecordsOnly,
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
            scope = LocalDataClearScope.RecordsOnly,
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
            scope = LocalDataClearScope.RecordsOnly,
            workflow = testLocalClearWorkflow { roomCalls += 1 },
            recoverDomain = { LocalDataClearScope.RecordsOnly },
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(roomCalls).isEqualTo(1)
        assertThat(rig.pending.pending).isNull()
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
            scope = LocalDataClearScope.RecordsOnly,
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

        val result = rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {
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

        val result = rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {}

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
        rig.pending.stageFailure = IllegalStateException("marker write failed")
        var callbackCalls = 0

        val failure = rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {
            callbackCalls += 1
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().isEqualTo("marker write failed")
        assertThat(callbackCalls).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(9)
    }
    @Test
    fun committedFileFailureRetainsDurableMarkerAndRoomReplicaRows() = runTest {
        val rig = ClearRig()
        rig.media.seed(media("log-media", "log", "photos/log.jpg"))
        val fileFailure = IllegalStateException("file delete failed")
        rig.mediaFiles.deleteFailures += fileFailure

        val failure = rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {}
            .exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat((failure as LocalClearCommittedException).familyServerRetained).isTrue()
        assertThat(failure.cause).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure.cause).hasMessageThat().isEqualTo(fileFailure.message)
        assertThat(rig.pending.pending).isNotNull()
        assertThat(rig.media.getByClientUuid("log-media")).isNotNull()
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
        rig.preferences.failUpdateCursorAttempts = 1
        var callbackCalls = 0

        val failure = rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {
            callbackCalls += 1
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat(rig.pending.pending).isNotNull()
        assertThat(rig.media.getByClientUuid("log-media")).isNotNull()

        assertThat(rig.newCoordinator().recoverPending().isSuccess).isTrue()
        assertThat(callbackCalls).isEqualTo(1)
        assertThat(rig.pending.pending).isNull()
        assertThat(rig.media.getByClientUuid("log-media")).isNull()
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("known-generation")
    }
    @Test
    fun finalRoomTransactionFailureRetainsMarkerForRetry() = runTest {
        val rig = ClearRig()
        rig.media.seed(media("log-media", "log", "photos/log.jpg"))
        rig.transactions.failRunNumbers += 2

        val failure = rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {}
            .exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat(failure!!.cause).hasMessageThat().isEqualTo("transaction 2 failed")
        assertThat(rig.pending.pending).isNotNull()
        assertThat(rig.media.getByClientUuid("log-media")).isNotNull()

        assertThat(rig.newCoordinator().recoverPending().isSuccess).isTrue()
        assertThat(rig.pending.pending).isNull()
    }
    @Test
    fun recoveryDeletesOnlyCapturedMediaCreatedBeforeTheClear() = runTest {
        val rig = ClearRig()
        rig.media.seed(media("old-log-media", "log", "photos/old.jpg"))
        rig.mediaFiles.deleteFailures += IllegalStateException("process stopped")

        assertThat(
            rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {}.isFailure,
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
            rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {}.isFailure,
        ).isTrue()
        rig.media.seed(media("new-log-media", "log", reusedPath))

        assertThat(rig.newCoordinator().recoverPending().isSuccess).isTrue()

        assertThat(rig.media.getByClientUuid("old-log-media")).isNull()
        assertThat(rig.media.getByClientUuid("new-log-media")).isNotNull()
        assertThat(rig.mediaFiles.deleted).containsExactly(reusedPath)
    }
    @Test
    fun committedCleanupRunsNonCancellableBeforePropagatingCancellation() = runTest {
        val gatedFiles = GatedDeleteMediaFileStore()
        val rig = ClearRig(mediaFiles = gatedFiles)
        rig.media.seed(media("log-media", "log", "photos/log.jpg"))

        val clearing = launch {
            rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {}
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
            result = rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {}
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
            rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {}
        }
        runCurrent()
        assertThat(clearing.isCompleted).isFalse()

        rig.barrier.unlock()

        assertThat(clearing.await().isSuccess).isTrue()
    }
}
