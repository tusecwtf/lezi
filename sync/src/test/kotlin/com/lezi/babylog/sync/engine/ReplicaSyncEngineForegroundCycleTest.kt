package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.deadline.ElapsedBudgetContext
import com.lezi.babylog.sync.backend.deadline.FamilyHttpException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind
import com.lezi.babylog.sync.backend.deadline.ForegroundSyncCycle
import com.lezi.babylog.sync.backend.retry.SyncRetryClock
import com.lezi.babylog.sync.backend.retry.SyncRetryTime
import com.lezi.babylog.sync.session.FamilyRole
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/**
 * 0.4.4 ticket 04: foreground / pull-to-refresh whole-cycle elapsed cap.
 * Next page or next file must not start; a persisted checkpoint stays put.
 */
class ReplicaSyncEngineForegroundCycleTest {
    @Test
    fun foregroundCycleExpiryStopsTheNextPageAndKeepsThePersistedCheckpoint() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 4,
            familyName = "乐记",
        )
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val clock = MutableElapsedClock()
        rig.backend.pullResults += PullResult(
            entities = listOf(remoteReplicaBaby()),
            cursor = 5,
            generation = "generation-a",
            hasMore = true,
            familyName = "乐记",
        )
        rig.backend.pullResults += PullResult(
            entities = listOf(
                remoteReplicaBaby().copy(clientUuid = "baby-remote-2", updatedAt = 101),
            ),
            cursor = 6,
            generation = "generation-a",
            hasMore = false,
            familyName = "乐记",
        )
        rig.backend.beforePullReturn = {
            clock.elapsedMillis = ForegroundSyncCycle.MAX_ELAPSED_MILLIS
        }

        val failure = runCatching {
            withForegroundCycle(clock) {
                rig.engine.synchronize(session, SyncTrigger.PullToRefresh)
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(FamilyHttpException::class.java)
        assertThat((failure as FamilyHttpException).kind)
            .isEqualTo(FamilyHttpFailureKind.SyncTookTooLong)
        assertThat(failure.message).isEqualTo("family-http:SyncTookTooLong")
        assertThat(
            com.lezi.babylog.core.common.failure.failureExplanation(
                com.lezi.babylog.core.common.failure.FailureKind.SyncTookTooLong,
            ).title,
        ).isEqualTo("这次同步时间太长，已先停下来")
        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(5)
        assertThat(rig.babies.getByClientUuid("baby-remote")).isNotNull()
        assertThat(rig.babies.getByClientUuid("baby-remote-2")).isNull()
    }

    @Test
    fun mediaGetFailureOnALaterPageDoesNotAdvanceTheCheckpoint() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 4,
            familyName = "乐记",
        )
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        val firstRecord = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee51"
        val secondRecord = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee52"
        val mediaUuid = "11111111-1111-4111-8111-111111111152"
        rig.backend.pullResults += PullResult(
            entities = listOf(remoteReplicaRecord(firstRecord)),
            cursor = 10,
            generation = "generation-a",
            hasMore = true,
            familyName = "乐记",
        )
        rig.backend.pullResults += PullResult(
            entities = listOf(
                remoteReplicaRecord(secondRecord),
                remoteReplicaMedia(mediaUuid, secondRecord),
            ),
            cursor = 11,
            generation = "generation-a",
            hasMore = false,
            familyName = "乐记",
        )
        rig.backend.getMediaFailure = FamilyHttpException(FamilyHttpFailureKind.SyncTookTooLong)

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.Foreground)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(FamilyHttpException::class.java)
        assertThat((failure as FamilyHttpException).kind)
            .isEqualTo(FamilyHttpFailureKind.SyncTookTooLong)
        assertThat(rig.backend.mediaGets).containsExactly(mediaUuid)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(10)
        assertThat(rig.records.getByClientUuid(firstRecord)).isNotNull()
        assertThat(rig.records.getByClientUuid(secondRecord)).isNull()
        assertThat(rig.media.getByClientUuid(mediaUuid)).isNull()
    }

    @Test
    fun localWriteIgnoresAnExpiredForegroundCycleAndDoesNotPull() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 19,
            familyName = "乐记",
        )
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        rig.backend.pullResults += PullResult(
            entities = listOf(remoteReplicaBaby()),
            cursor = 20,
            generation = "generation-a",
            hasMore = false,
            familyName = "乐记",
        )
        val clock = MutableElapsedClock(elapsedMillis = ForegroundSyncCycle.MAX_ELAPSED_MILLIS)

        withForegroundCycle(clock) {
            assertThat(rig.engine.synchronize(session, SyncTrigger.LocalWrite))
                .isEqualTo(ReplicaSyncOutcome.Synchronized)
        }

        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(19)
        assertThat(rig.babies.getByClientUuid("baby-remote")).isNull()
    }
}

private class MutableElapsedClock(
    var elapsedMillis: Long = 0L,
) : SyncRetryClock {
    override fun snapshot(): SyncRetryTime =
        SyncRetryTime(1_700_000_000_000L + elapsedMillis, elapsedMillis)
}

private suspend fun <T> withForegroundCycle(
    clock: MutableElapsedClock,
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
