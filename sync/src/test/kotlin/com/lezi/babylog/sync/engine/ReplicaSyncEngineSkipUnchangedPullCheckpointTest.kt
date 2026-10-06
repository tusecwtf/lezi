package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.session.FamilyRole
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Public seam: [ReplicaSyncEngine.synchronize] plus recording
 * [com.lezi.babylog.sync.MemorySyncPreferences.updatePullCheckpoint] writes.
 *
 * Empty increments must not rewrite the durable pull checkpoint when cursor,
 * generation, and 家庭名 are unchanged. A page that actually advances still
 * persists. markSuccess is out of this ticket.
 */
class ReplicaSyncEngineSkipUnchangedPullCheckpointTest {

    @Test
    fun emptyIncrementWithUnchangedCursorDoesNotWritePullCheckpoint() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 4,
            pullGeneration = "generation-a",
            familyName = "乐记",
        )
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 4,
            generation = "generation-a",
            hasMore = false,
            familyName = "乐记",
        )

        val outcome = rig.engine.synchronize(session, SyncTrigger.Foreground)

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.preferences.updatePullCheckpointCalls).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(4)
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("generation-a")
        assertThat(rig.preferences.current().familyName).isEqualTo("乐记")
    }

    @Test
    fun appliedPageThatAdvancesCursorPersistsPullCheckpoint() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 4,
            pullGeneration = "generation-a",
            familyName = "乐记",
        )
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        rig.backend.nextPull = PullResult(
            entities = listOf(remoteReplicaBaby()),
            cursor = 5,
            generation = "generation-a",
            hasMore = false,
            familyName = "乐记",
        )

        val outcome = rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.preferences.updatePullCheckpointCalls).isEqualTo(1)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(5)
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("generation-a")
        assertThat(rig.preferences.current().familyName).isEqualTo("乐记")
        assertThat(rig.babies.getByClientUuid("baby-remote")).isNotNull()
    }

    @Test
    fun emptyIncrementStillWritesWhenFamilyNameChanges() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 4,
            pullGeneration = "generation-a",
            familyName = "乐记",
        )
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 4,
            generation = "generation-a",
            hasMore = false,
            familyName = "新家",
        )

        val outcome = rig.engine.synchronize(session, SyncTrigger.Foreground)

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.preferences.updatePullCheckpointCalls).isEqualTo(1)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(4)
        assertThat(rig.preferences.current().familyName).isEqualTo("新家")
    }

    @Test
    fun localFamilyNameOutsideWireRulesDoesNotFailUnchangedCursorCompare() = runTest {
        val localName = "x".repeat(65)
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 4,
            pullGeneration = "generation-a",
            familyName = localName,
        )
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 4,
            generation = "generation-a",
            hasMore = false,
            familyName = "乐记",
        )

        val outcome = rig.engine.synchronize(session, SyncTrigger.Foreground)

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.preferences.updatePullCheckpointCalls).isEqualTo(1)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(4)
        assertThat(rig.preferences.current().familyName).isEqualTo("乐记")
    }
}
