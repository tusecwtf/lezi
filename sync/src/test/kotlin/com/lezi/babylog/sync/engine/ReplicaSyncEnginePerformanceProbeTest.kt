package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.performance.SyncPerformanceProbe
import com.lezi.babylog.sync.performance.SyncPerformanceStage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Exercises production phase call sites, not calls to measure made by a test wrapper. */
class ReplicaSyncEnginePerformanceProbeTest {
    @Before
    @After
    fun disableCapture() {
        SyncPerformanceProbe.stopCapture()
    }

    @Test
    fun localWriteCapturesRealPhasesWithoutInventingAPull() = runTest {
        val session = joinedReplicaSession()
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        SyncPerformanceProbe.startCapture()

        assertThat(rig.engine.synchronize(session, SyncTrigger.LocalWrite))
            .isEqualTo(ReplicaSyncOutcome.Synchronized)

        val rows = requireNotNull(SyncPerformanceProbe.stopCapture())
        assertThat(rows[SyncPerformanceStage.ReplicaRound.id].completedCount).isEqualTo(1)
        assertThat(rows[SyncPerformanceStage.Handshake.id].completedCount)
            .isEqualTo(rig.backend.handshakeCalls.toLong())
        assertThat(rig.backend.handshakeCalls).isEqualTo(1)
        assertThat(rows[SyncPerformanceStage.PullRequest.id].completedCount).isEqualTo(0)
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rows[SyncPerformanceStage.CaptureLocalChanges.id].completedCount).isAtLeast(1)
        assertThat(rows[SyncPerformanceStage.SettlePublish.id].completedCount).isEqualTo(1)
        assertThat(rows[SyncPerformanceStage.CleanupMedia.id].completedCount).isAtLeast(1)
        assertThat(rows.sumOf { it.failedCount }).isEqualTo(0)
        assertThat(rows.sumOf { it.activeSpans }).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(session.pullCursor)
    }

    @Test
    fun handshakeFailureIsTimedAndPropagatedBeforePullOrPublication() = runTest {
        val session = joinedReplicaSession()
        val rig = ReplicaEngineRig(session)
        val expected = IdentityPreservingIOException("synthetic handshake failure")
        rig.backend.handshakeFailure = expected
        SyncPerformanceProbe.startCapture()

        val actual = runCatching {
            rig.engine.synchronize(session, SyncTrigger.Foreground)
        }.exceptionOrNull()

        assertThat(actual).isSameInstanceAs(expected)
        val rows = requireNotNull(SyncPerformanceProbe.stopCapture())
        assertThat(rows[SyncPerformanceStage.ReplicaRound.id].failedCount).isEqualTo(1)
        assertThat(rows[SyncPerformanceStage.Handshake.id].failedCount).isEqualTo(1)
        assertThat(rows[SyncPerformanceStage.PullRequest.id].completedCount).isEqualTo(0)
        assertThat(rows[SyncPerformanceStage.SettlePublish.id].completedCount).isEqualTo(0)
        assertThat(rows.sumOf { it.activeSpans }).isEqualTo(0)
    }

    @Test
    fun remoteMediaStagingAndPageApplyAreSeparateCompletedPhases() = runTest {
        val session = joinedReplicaSession()
        val rig = ReplicaEngineRig(session)
        rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        rig.backend.mediaBytes = byteArrayOf(1, 2, 3, 4)
        val recordId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee61"
        val mediaId = "11111111-1111-4111-8111-111111111161"
        SyncPerformanceProbe.startCapture()

        rig.engine.applyInitialEntities(
            session,
            listOf(remoteReplicaRecord(recordId), remoteReplicaMedia(mediaId, recordId)),
        )

        val rows = requireNotNull(SyncPerformanceProbe.stopCapture())
        assertThat(rig.backend.mediaGets).containsExactly(mediaId)
        assertThat(rig.records.getByClientUuid(recordId)).isNotNull()
        assertThat(rig.media.getByClientUuid(mediaId)?.localUri).isNotEmpty()
        assertThat(rows[SyncPerformanceStage.StageMediaDownload.id].completedCount).isEqualTo(1)
        assertThat(rows[SyncPerformanceStage.ApplyPage.id].completedCount).isEqualTo(1)
        assertThat(rows.sumOf { it.failedCount }).isEqualTo(0)
        assertThat(rows.sumOf { it.activeSpans }).isEqualTo(0)
    }

    @Test
    fun mediaStagingFailureDoesNotRecordAPageApplyThatNeverRan() = runTest {
        val session = joinedReplicaSession()
        val rig = ReplicaEngineRig(session)
        rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val expected = IdentityPreservingIOException("synthetic media failure")
        rig.backend.getMediaFailure = expected
        val recordId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee62"
        val mediaId = "11111111-1111-4111-8111-111111111162"
        SyncPerformanceProbe.startCapture()

        val actual = runCatching {
            rig.engine.applyInitialEntities(
                session,
                listOf(remoteReplicaRecord(recordId), remoteReplicaMedia(mediaId, recordId)),
            )
        }.exceptionOrNull()

        assertThat(actual).isSameInstanceAs(expected)
        val rows = requireNotNull(SyncPerformanceProbe.stopCapture())
        assertThat(rows[SyncPerformanceStage.StageMediaDownload.id].failedCount).isEqualTo(1)
        assertThat(rows[SyncPerformanceStage.ApplyPage.id].completedCount).isEqualTo(0)
        assertThat(rig.records.getByClientUuid(recordId)).isNull()
        assertThat(rig.media.getByClientUuid(mediaId)).isNull()
        assertThat(rows.sumOf { it.activeSpans }).isEqualTo(0)
    }
}
