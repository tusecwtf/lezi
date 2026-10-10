package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.deadline.ElapsedBudgetContext
import com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind
import com.lezi.babylog.sync.backend.deadline.ForegroundSyncCycle
import com.lezi.babylog.sync.backend.retry.SyncRetryClock
import com.lezi.babylog.sync.backend.retry.SyncRetryTime
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.receiptFor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Public seam: [ReplicaSyncEngine.synchronize] with recording
 * [com.lezi.babylog.sync.RecordingSyncBackend.getMedia] hooks.
 *
 * Historical missing media backfill runs with bounded (2) parallelism:
 * caps stay respected, a 404 only skips its own media, any other failure
 * cancels the in-flight worker and fails the whole cycle, and the cycle
 * budget checkpoint leaves un-downloaded media queued.
 */
class ReplicaSyncEngineParallelHistoricalMissingMediaTest {

    @Test
    fun historicalBackfillKeepsPeakGetConcurrencyAtMostTwo() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 1)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedHistoricalMissing(rig, HISTORICAL_A, HISTORICAL_B, HISTORICAL_C)
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 1,
            generation = session.pullGeneration,
            hasMore = false,
        )
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val arrived = AtomicInteger(0)
        val twoArrived = CompletableDeferred<Unit>()
        rig.backend.onGetMedia = { _ ->
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now, ::maxOf)
            if (arrived.incrementAndGet() >= 2) twoArrived.complete(Unit)
            // Deterministic overlap: the first two GETs only proceed together, so
            // a third concurrent GET would be visible in the peak.
            withTimeout(5_000) { twoArrived.await() }
            inFlight.decrementAndGet()
        }

        rig.engine.synchronize(session, SyncTrigger.Foreground)

        assertThat(peak.get()).isEqualTo(2)
        assertThat(rig.backend.mediaGets).containsExactly(
            HISTORICAL_A,
            HISTORICAL_B,
            HISTORICAL_C,
        )
        assertThat(rig.media.getByClientUuid(HISTORICAL_A)?.localUri)
            .isEqualTo("downloaded/$HISTORICAL_A")
        assertThat(rig.media.getByClientUuid(HISTORICAL_B)?.localUri)
            .isEqualTo("downloaded/$HISTORICAL_B")
        assertThat(rig.media.getByClientUuid(HISTORICAL_C)?.localUri)
            .isEqualTo("downloaded/$HISTORICAL_C")
    }

    @Test
    fun attemptAndByteCapsHoldWithTwoWorkersDownloadingConcurrently() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 1)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        // Each in-flight response alone reaches the 8 MiB decoded-byte budget.
        rig.backend.mediaBytesByUuid[HISTORICAL_A] = ByteArray(8 * 1024 * 1024)
        rig.backend.mediaBytesByUuid[HISTORICAL_B] = ByteArray(8 * 1024 * 1024)
        seedHistoricalMissing(rig, HISTORICAL_A, HISTORICAL_B, HISTORICAL_C, HISTORICAL_D)
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 1,
            generation = session.pullGeneration,
            hasMore = false,
        )
        val arrived = AtomicInteger(0)
        val twoArrived = CompletableDeferred<Unit>()
        rig.backend.onGetMedia = { _ ->
            if (arrived.incrementAndGet() >= 2) twoArrived.complete(Unit)
            withTimeout(5_000) { twoArrived.await() }
        }

        rig.engine.synchronize(session, SyncTrigger.Foreground)

        // Both budget-exhausting responses were legitimately in flight (bounded
        // overshoot = the two permits); once either has accounted its bytes the
        // shared counter blocks every further GET.
        assertThat(rig.backend.mediaGets).containsExactly(HISTORICAL_A, HISTORICAL_B)
        assertThat(rig.media.getByClientUuid(HISTORICAL_A)?.localUri)
            .isEqualTo("downloaded/$HISTORICAL_A")
        assertThat(rig.media.getByClientUuid(HISTORICAL_B)?.localUri)
            .isEqualTo("downloaded/$HISTORICAL_B")
        assertThat(rig.media.getByClientUuid(HISTORICAL_C)?.localUri).isEmpty()
        assertThat(rig.media.getByClientUuid(HISTORICAL_D)?.localUri).isEmpty()
    }

    @Test
    fun singleNon404FailureCancelsInFlightWorkerAndVoidsTheCycle() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 1)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedHistoricalMissing(rig, HISTORICAL_A, HISTORICAL_B)
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 1,
            generation = session.pullGeneration,
            hasMore = false,
        )
        val failure = SyncHttpException(500, """{"detail":"server error"}""")
        rig.backend.getMediaFailureByUuid[HISTORICAL_A] = failure
        val secondArrived = CompletableDeferred<Unit>()
        val neverCompleted = CompletableDeferred<Unit>()
        var inFlightWorkerCancelled = false
        rig.backend.onGetMedia = { uuid ->
            when (uuid) {
                HISTORICAL_A -> secondArrived.await()
                else -> {
                    secondArrived.complete(Unit)
                    try {
                        withTimeout(5_000) { neverCompleted.await() }
                    } catch (cancelled: CancellationException) {
                        inFlightWorkerCancelled = true
                        throw cancelled
                    }
                }
            }
        }

        val outcome = runCatching { rig.engine.synchronize(session, SyncTrigger.Foreground) }

        // Auth/server/transport failures still fail the whole cycle (never a
        // "successful sync"), the sibling GET is cancelled, nothing is adopted,
        // and the pull cursor does not move.
        assertThat(outcome.exceptionOrNull()).isSameInstanceAs(failure)
        assertThat(inFlightWorkerCancelled).isTrue()
        assertThat(rig.backend.mediaGets).containsExactly(HISTORICAL_A, HISTORICAL_B)
        assertThat(rig.media.getByClientUuid(HISTORICAL_A)?.localUri).isEmpty()
        assertThat(rig.media.getByClientUuid(HISTORICAL_B)?.localUri).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
        assertThat(rig.media.listMissingLocalBytes().map { it.clientUuid })
            .containsExactly(HISTORICAL_A, HISTORICAL_B)
    }

    @Test
    fun cycleBudgetExhaustedDuringBackfillFailsCycleAndLeavesMediaQueued() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 1)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedHistoricalMissing(rig, HISTORICAL_A, HISTORICAL_B, HISTORICAL_C)
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 1,
            generation = session.pullGeneration,
            hasMore = false,
        )
        val clock = BackfillElapsedClock(elapsedMillis = 0L)
        rig.backend.onGetMedia = { _ ->
            // The first GET burns the whole foreground-cycle budget; every later
            // budget checkpoint (including the per-media one) must refuse.
            clock.elapsedMillis = ForegroundSyncCycle.MAX_ELAPSED_MILLIS
        }

        val outcome = withBackfillCycle(clock) {
            runCatching { rig.engine.synchronize(session, SyncTrigger.Foreground) }
        }

        assertThat(outcome.exceptionOrNull())
            .isInstanceOf(com.lezi.babylog.sync.backend.deadline.FamilyHttpException::class.java)
        assertThat(rig.backend.mediaGets).containsExactly(HISTORICAL_A)
        assertThat(rig.media.getByClientUuid(HISTORICAL_A)?.localUri)
            .isEqualTo("downloaded/$HISTORICAL_A")
        assertThat(rig.media.getByClientUuid(HISTORICAL_B)?.localUri).isEmpty()
        assertThat(rig.media.getByClientUuid(HISTORICAL_C)?.localUri).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
        assertThat(rig.media.listMissingLocalBytes().map { it.clientUuid })
            .containsExactly(HISTORICAL_B, HISTORICAL_C)
    }

    private fun seedHistoricalMissing(
        rig: ReplicaEngineRig,
        vararg clientUuids: String,
    ) {
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
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
                    byteSize = (rig.backend.mediaBytesByUuid[uuid] ?: rig.backend.mediaBytes).size.toLong(),
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
    }
}

private class BackfillElapsedClock(
    var elapsedMillis: Long = 0L,
) : SyncRetryClock {
    override fun snapshot(): SyncRetryTime =
        SyncRetryTime(1_700_000_000_000L + elapsedMillis, elapsedMillis)
}

private suspend fun <T> withBackfillCycle(
    clock: BackfillElapsedClock,
    block: suspend () -> T,
): T = withContext(
    ElapsedBudgetContext(
        kind = FamilyHttpFailureKind.SyncTookTooLong,
        startedAtMillis = 0L,
        clock = clock,
        maxElapsedMillis = ForegroundSyncCycle.MAX_ELAPSED_MILLIS,
    ),
) {
    block()
}
