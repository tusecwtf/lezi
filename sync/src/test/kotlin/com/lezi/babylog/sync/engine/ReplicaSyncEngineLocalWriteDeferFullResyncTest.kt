package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.AuthorityProofException
import com.lezi.babylog.sync.backend.CausalCommitBatchResult
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalCommitUnitResult
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.syncHttpCodeOrNull
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.familyFailureKind
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Public seam: [ReplicaSyncEngine.synchronize] plus [com.lezi.babylog.sync.RecordingSyncBackend]
 * request order and [com.lezi.babylog.sync.MemorySyncPreferences] durability.
 *
 * LocalWrite that hits generation mismatch / 409 / [AuthorityProofException] must not
 * recover a full resync. It keeps dirty roots, writes an independent durable pending
 * (never the reset-receipt journal), and ends as a retryable failure.
 */
class ReplicaSyncEngineLocalWriteDeferFullResyncTest {

    @Test
    fun localWriteCommitGenerationDriftDoesNotPullOrResync() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 17,
        )
        val rig = seedDirtyRecordRig(session)
        driftNextCommitGeneration(rig)

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AuthorityProofException::class.java)
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.backend.pullCursors).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(17)
        assertThat(rig.preferences.current().pullGeneration).isEqualTo(session.pullGeneration)
        assertThat(rig.records.getByClientUuid(DIRTY_RECORD_UUID)?.syncDirty).isTrue()
        assertThat(rig.conflictDetails.getTransportJournal("replica-reset-receipt:current"))
            .isNull()
        assertThat(rig.preferences.hasPendingGenerationResync()).isTrue()
    }

    @Test
    fun subsequentLocalWriteWithPendingStillDoesNotPull() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 18,
        )
        val rig = seedDirtyRecordRig(session)
        driftNextCommitGeneration(rig)

        runCatching { rig.engine.synchronize(session, SyncTrigger.LocalWrite) }
        assertThat(rig.preferences.hasPendingGenerationResync()).isTrue()
        val handshakeAfterFirst = rig.backend.handshakeCalls
        val commitsAfterFirst = rig.backend.causalCommittedUnits.size

        driftNextCommitGeneration(rig)
        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AuthorityProofException::class.java)
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.backend.handshakeCalls).isEqualTo(handshakeAfterFirst + 1)
        assertThat(rig.backend.causalCommittedUnits.size).isEqualTo(commitsAfterFirst + 1)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(18)
        assertThat(rig.records.getByClientUuid(DIRTY_RECORD_UUID)?.syncDirty).isTrue()
        assertThat(rig.preferences.hasPendingGenerationResync()).isTrue()
    }

    @Test
    fun localWriteStoredVersionReloadKeepsPendingDirtyMutation() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 24,
        )
        val rig = seedDirtyRecordRig(session)
        rig.backend.onCausalCommit = {
            throw SyncHttpException(
                statusCode = 409,
                responseBody =
                    """{"code":"invalid_stored_payload","detail":"stored entity payload is invalid"}""",
            )
        }

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SyncHttpException::class.java)
        assertThat((failure as SyncHttpException).statusCode).isEqualTo(409)
        assertThat(syncHttpCodeOrNull(failure.responseBody))
            .isEqualTo("invalid_stored_payload")
        assertThat(familyFailureKind(failure)).isEqualTo(FailureKind.HouseholdStateChanged)
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.records.getByClientUuid(DIRTY_RECORD_UUID)?.syncDirty).isTrue()
        assertThat(rig.preferences.hasPendingGenerationResync()).isFalse()
    }

    @Test
    fun localWriteCommit409DoesNotPullAndKeepsPending() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 19,
        )
        val rig = seedDirtyRecordRig(session)
        rig.backend.onCausalCommit = {
            throw generationChanged409("other-generation")
        }

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SyncHttpException::class.java)
        assertThat((failure as SyncHttpException).statusCode).isEqualTo(409)
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.records.getByClientUuid(DIRTY_RECORD_UUID)?.syncDirty).isTrue()
        assertThat(rig.conflictDetails.getTransportJournal("replica-reset-receipt:current"))
            .isNull()
        assertThat(rig.preferences.hasPendingGenerationResync()).isTrue()
    }

    @Test
    fun pendingSurvivesNewEngineProcessDeath() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 20,
        )
        val rig = seedDirtyRecordRig(session)
        driftNextCommitGeneration(rig)
        runCatching { rig.engine.synchronize(session, SyncTrigger.LocalWrite) }
        assertThat(rig.preferences.hasPendingGenerationResync()).isTrue()

        val restarted = rig.newEngine()
        driftNextCommitGeneration(rig)
        val failure = runCatching {
            restarted.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AuthorityProofException::class.java)
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.hasPendingGenerationResync()).isTrue()
        assertThat(rig.records.getByClientUuid(DIRTY_RECORD_UUID)?.syncDirty).isTrue()
    }

    @Test
    fun foregroundPullToRefreshRecoversAndClearsPending() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 21,
        )
        val rig = seedDirtyRecordRig(session)
        driftNextCommitGeneration(rig)
        runCatching { rig.engine.synchronize(session, SyncTrigger.LocalWrite) }
        assertThat(rig.preferences.hasPendingGenerationResync()).isTrue()
        assertThat(rig.backend.pullCount).isEqualTo(0)

        rig.backend.onCausalCommit = null
        rig.backend.pullFailures += generationChanged409("other-generation")
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 3,
            generation = "other-generation",
            hasMore = false,
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 3,
            generation = "other-generation",
            hasMore = false,
        )

        val outcome = rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.backend.pullCount).isAtLeast(1)
        assertThat(rig.backend.pullCursors).contains(0L)
        assertThat(rig.preferences.hasPendingGenerationResync()).isFalse()
        assertThat(rig.records.getByClientUuid(DIRTY_RECORD_UUID)?.syncDirty).isFalse()
        assertThat(rig.conflictDetails.getTransportJournal("replica-reset-receipt:current"))
            .isNull()
    }

    @Test
    fun foregroundIncrementalPullClearsPendingWithoutForcedResync() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 23,
        )
        val rig = seedDirtyRecordRig(session)
        driftNextCommitGeneration(rig)
        runCatching { rig.engine.synchronize(session, SyncTrigger.LocalWrite) }
        assertThat(rig.preferences.hasPendingGenerationResync()).isTrue()

        rig.backend.onCausalCommit = null
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 23,
            generation = session.pullGeneration,
            hasMore = false,
        )

        val outcome = rig.engine.synchronize(session, SyncTrigger.Foreground)

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.backend.pullCount).isAtLeast(1)
        assertThat(rig.backend.pullCursors).doesNotContain(0L)
        assertThat(rig.preferences.hasPendingGenerationResync()).isFalse()
        assertThat(rig.records.getByClientUuid(DIRTY_RECORD_UUID)?.syncDirty).isFalse()
    }

    @Test
    fun causalLocalWriteSuccessStillDoesNotPullOrAdvanceCursor() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 22,
        )
        val rig = seedDirtyRecordRig(session)
        rig.backend.nextPull = PullResult(
            entities = listOf(remoteReplicaRecord("must-not-pull")),
            cursor = session.pullCursor,
            generation = session.pullGeneration,
            hasMore = false,
        )

        val outcome = rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(22)
        assertThat(rig.preferences.hasPendingGenerationResync()).isFalse()
        assertThat(rig.records.getByClientUuid(DIRTY_RECORD_UUID)?.syncDirty).isFalse()
    }
}

private const val DIRTY_RECORD_UUID = "record-localwrite-409"

private fun seedDirtyRecordRig(session: com.lezi.babylog.sync.session.SyncSession): ReplicaEngineRig {
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
            clientUuid = DIRTY_RECORD_UUID,
            babyId = babyId,
            type = "formula",
            timestamp = 100,
            payloadJson = """{"amount_ml":60}""",
            schemaVersion = 2,
            updatedAt = 10,
            syncDirty = true,
            baseVersion = null,
        ),
    )
    return rig
}

private fun driftNextCommitGeneration(rig: ReplicaEngineRig) {
    rig.backend.onCausalCommit = { units ->
        val unit = units.single()
        rig.backend.nextCausalCommit = CausalCommitBatchResult(
            generation = "other-generation",
            results = listOf(
                CausalCommitUnitResult(
                    status = CausalCommitStatus.ACCEPTED,
                    mutationId = unit.mutationId,
                    requestHash = "h",
                    stableVersionId = "ignored-drift-version",
                    stableRootJson = unit.rootJson,
                ),
            ),
        )
    }
}

private fun generationChanged409(serverGeneration: String) = SyncHttpException(
    statusCode = 409,
    responseBody = """
        {
          "detail":{
            "code":"generation_changed",
            "action":"full_resync",
            "reset_cursor":0,
            "server_cursor":1,
            "server_generation":"$serverGeneration"
          }
        }
    """.trimIndent(),
)

