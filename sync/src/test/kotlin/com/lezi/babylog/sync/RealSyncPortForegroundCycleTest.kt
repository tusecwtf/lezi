package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.deadline.ForegroundSyncCycle
import com.lezi.babylog.sync.backend.retry.RetryingSyncBackend
import com.lezi.babylog.sync.backend.retry.SyncRetryClock
import com.lezi.babylog.sync.backend.retry.SyncRetryDelay
import com.lezi.babylog.sync.backend.retry.SyncRetryRandom
import com.lezi.babylog.sync.backend.retry.SyncRetryTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * Public seam: [SyncPort.sync] foreground / pull-to-refresh cycle cap.
 * An expired cycle with durable progress continues silently; copy stays off InvalidInput.
 * A single-request timeout must leave the [requestSync] consumer alive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealSyncPortForegroundCycleTest {
    @Test
    fun pullToRefreshCycleExpiryContinuesInForegroundWithoutInvalidInput() = runTest {
        val session = joinedSession("family-a").copy(pullCursor = 4)
        val rig = SyncRig(session = session)
        rig.awaitStartupRecovery()
        val clock = MutableElapsedClock()
        rig.port.familyElapsedClock = clock
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 5,
            generation = session.pullGeneration,
            hasMore = true,
            familyName = "乐乐一家",
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 6,
            generation = session.pullGeneration,
            hasMore = false,
            familyName = "乐乐一家",
        )
        rig.backend.beforePullReturn = {
            clock.elapsedMillis = ForegroundSyncCycle.MAX_ELAPSED_MILLIS
        }

        val result = rig.port.sync(SyncTrigger.PullToRefresh)

        clock.elapsedMillis = 0L
        rig.backend.beforePullReturn = null
        assertThat(result.isSuccess).isTrue()
        assertThat(rig.port.lastFailureKind().first())
            .isNotEqualTo(FailureKind.InvalidInput)
        assertThat(rig.port.status().first()).isNotEqualTo(SyncStatus.Error)
        val shallow = rig.port.shallowStatus().first()
        assertThat(shallow.text).doesNotContain("称呼")
        assertThat(shallow.text).doesNotContain("草稿")
        assertThat(rig.preferences.current().pullCursor).isAtLeast(5)

        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                while (rig.port.status().first() != SyncStatus.Idle) {
                    delay(20)
                }
            }
        }
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
        assertThat(rig.port.lastFailureKind().first()).isNull()
        assertThat(rig.preferences.current().pullCursor).isAtLeast(5)
        assertThat(rig.backend.pullCount).isAtLeast(1)
    }

    @Test
    fun localWriteDoesNotInstallTheForegroundCycleAndStillDoesNotPull() = runTest {
        val session = joinedSession("family-a").copy(pullCursor = 19)
        val rig = SyncRig(session = session)
        rig.awaitStartupRecovery()
        rig.backend.enableCausal = true
        val clock = MutableElapsedClock(elapsedMillis = ForegroundSyncCycle.MAX_ELAPSED_MILLIS)
        rig.port.familyElapsedClock = clock
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 20,
            generation = session.pullGeneration,
            hasMore = false,
            familyName = "乐乐一家",
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(19)
    }

    @Test
    fun requestSyncForegroundTimeoutLeavesConsumerAliveForLocalWrite() = runTest {
        val recording = RecordingSyncBackend()
        recording.enableCausal = true
        repeat(3) { recording.pullFailures += timeoutCancellation() }
        val session = joinedSession("family-a").copy(pullCursor = 4)
        val rig = SyncRig(
            session = session,
            syncBackend = RetryingSyncBackend(
                recording,
                clock = MutableElapsedClock(),
                random = SyncRetryRandom { 0 },
                delay = SyncRetryDelay { },
            ),
        )
        rig.awaitStartupRecovery()

        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntil {
            rig.port.lastFailureKind().first() == FailureKind.ResponseTimedOut &&
                rig.port.status().first() == SyncStatus.Error
        }
        assertThat(rig.port.lastFailureKind().first()).isEqualTo(FailureKind.ResponseTimedOut)
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Error)

        val babyId = rig.babies.seed(
            localBaby().copy(syncDirty = false, familyAuthority = true, baseVersion = "v-baby"),
        )
        rig.records.seed(localRecord(babyId))
        recording.enableCausal = true

        rig.port.requestSync(SyncTrigger.LocalWrite)
        pumpUntil { recording.causalCommittedUnits.isNotEmpty() }

        assertThat(recording.causalCommittedUnits).isNotEmpty()
    }

    @Test
    fun backgroundContinuationSignalDoesNotLeaveStatusSyncing() = runTest {
        val session = joinedSession("family-a")
        val rig = SyncRig(session = session)
        rig.awaitStartupRecovery()

        val parked = CompletableDeferred<Unit>()
        rig.backend.handshakeGate = parked
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntil { rig.port.status().first() == SyncStatus.Syncing }

        rig.foreground.setForeground(false)
        rig.backend.handshakeGate = null
        parked.complete(Unit)
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntil { rig.port.status().first() != SyncStatus.Syncing }

        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    private suspend fun TestScope.pumpUntil(
        timeoutMillis: Long = 10_000,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            Thread.sleep(10)
            runCurrent()
            if (condition()) return
            check(System.nanoTime() < deadline) { "foreground-cycle condition not met in time" }
        }
    }
}

private fun timeoutCancellation(): TimeoutCancellationException =
    runCatching {
        runBlocking { withTimeout(1) { delay(50) } }
    }.exceptionOrNull() as TimeoutCancellationException

private class MutableElapsedClock(
    var elapsedMillis: Long = 0L,
) : SyncRetryClock {
    override fun snapshot(): SyncRetryTime =
        SyncRetryTime(1_700_000_000_000L + elapsedMillis, elapsedMillis)
}
