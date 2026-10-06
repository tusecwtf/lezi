package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.sync.availability.AvailabilityProbeReason
import com.lezi.babylog.sync.availability.FamilyServerAvailability
import com.lezi.babylog.sync.backend.SyncHeartbeat
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.heartbeat.HeartbeatJitterSource
import com.lezi.babylog.sync.heartbeat.SyncHeartbeatPolicy
import com.lezi.babylog.sync.session.CAPABILITY_SYNC_HEARTBEAT_V1
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbe
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.session.receiptFor
import com.lezi.babylog.sync.engine.localReplicaBaby
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/**
 * RealSyncPort integration contract of the 0.5 ticket 04 tip-skip (W3 安静轮
 * 零数据 RTT): a foreground-return round may skip the handshake and the
 * guaranteed-empty pull ONLY when the freshest heartbeat beat answered
 * no-change, its three keys match the current snapshot, the last successful
 * full cycle is inside the freshness window, and none of the five recovery
 * seams (pending generation resync, pending local-clear, replica reset
 * receipt, pending publish units, unconfirmed missing media) holds work.
 * Heartbeat kicks and zero-progress continuations are vetoed at the signal
 * consumer and never skip; skipped rounds touch no observable state (no
 * Syncing flip, no fuse release, no lastServerHealthyAt refresh); a 0.4.7
 * old server (heartbeat 404) never produces a beat proof, so behavior is
 * exactly the status quo.
 *
 * Determinism discipline matches [RealSyncPortHeartbeatLoopTest]: real-IO
 * port collectors progress during [Thread.sleep] pumps, virtual time only
 * moves through [advanceTipSkipTime], and the piggyback app-update call
 * counter doubles as the deterministic "the round finished" signal — a
 * skipped round also runs the tail, so "tail advanced, handshake/pull
 * frozen" proves a skip rather than a lost signal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealSyncPortTipSkipTest {

    private var setupProbeCalls = 0

    @Test
    fun quietForegroundReturnSkipsHandshakeAndPullWithZeroDataRequests() = runTest {
        val rig = tipSkipRig()

        roundThenQuietBeat(rig)

        // Second foreground return: fresh beat proof + fresh anchor + nothing
        // pending — the round finishes WITHOUT any data request.
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 1, pullCount = 1, appUpdateCalls = 2)

        assertThat(rig.backend.handshakeCalls).isEqualTo(1)
        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.backend.mediaGets).isEmpty()
        assertThat(rig.preferences.lastServerHealthyAt.first()).isNotNull()
        assertThat(rig.port.lastFailureKind().first()).isNull()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun heartbeatKickRoundIsNeverSwallowedByAFreshQuietProof() = runTest {
        val rig = tipSkipRig()
        val session = joinedSession("family-a")

        roundThenQuietBeat(rig)

        // The server changes: the NeedsSync beat kicks a round that must run
        // in full even though the quiet proof is seconds old.
        rig.backend.nextHeartbeat = needsSyncHeartbeat(session)
        advanceTipSkipTime(rig, SyncHeartbeatPolicy.BASELINE_INTERVAL_MILLIS)
        pumpUntilRoundCompleted(rig, handshakeCalls = 2, pullCount = 2, appUpdateCalls = 2)
        settleIdle(rig)
    }

    @Test
    fun zeroProgressContinuationRoundIsNeverSwallowed() = runTest {
        val rig = tipSkipRig()
        val session = joinedSession("family-a")

        roundThenQuietBeat(rig)

        // The server changes; the kicked round AND its zero-progress
        // continuations all fail at the handshake. None of the three cluster
        // rounds may be swallowed as a skip — a skipped round would report
        // success, run its piggyback tail, and end the cluster early.
        rig.backend.handshakeFailure = SyncHttpException(409, """{"detail":"conflict"}""")
        rig.backend.nextHeartbeat = needsSyncHeartbeat(session)
        advanceTipSkipTime(rig, SyncHeartbeatPolicy.BASELINE_INTERVAL_MILLIS)
        pumpUntil { rig.port.status().first() == SyncStatus.Error }
        assertThat(rig.backend.handshakeCalls).isEqualTo(4) // round 1 + cluster 2,3,4
        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.backend.getAppUpdateMetadataCalls).isEqualTo(1)

        // A real foreground return after the cluster also runs in full.
        rig.backend.handshakeFailure = null
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 5, pullCount = 2, appUpdateCalls = 2)
        settleIdle(rig)
    }

    @Test
    fun pendingGenerationResyncVetoesTheSkip() = runTest {
        val rig = tipSkipRig()

        roundThenQuietBeat(rig)

        rig.preferences.markPendingGenerationResync()
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 2, pullCount = 2, appUpdateCalls = 2)
        // The full round's convergence gate cleared the flag as always.
        assertThat(rig.preferences.hasPendingGenerationResync()).isFalse()
    }

    @Test
    fun pendingLocalClearVetoesTheSkip() = runTest {
        val rig = tipSkipRig()

        roundThenQuietBeat(rig)

        rig.preferences.markPendingDeviceRemovalClear()
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 2, pullCount = 2, appUpdateCalls = 2)
    }

    @Test
    fun unconsumedResetReceiptVetoesTheSkip() = runTest {
        val rig = tipSkipRig()

        roundThenQuietBeat(rig)

        // A reset receipt that does not belong to this session never blocks
        // the round itself but keeps the tip-skip vetoed (fail closed).
        rig.conflictDetails.putTransportJournal(
            journalKey = "replica-reset-receipt:current",
            payloadJson = RESET_RECEIPT_JSON,
            contentEpoch = 0L,
        )
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 2, pullCount = 2, appUpdateCalls = 2)
        // The unrelated receipt survives the round untouched.
        assertThat(rig.conflictDetails.getTransportJournal("replica-reset-receipt:current"))
            .isNotNull()
    }

    @Test
    fun undecodableResetReceiptVetoesTheSkip() = runTest {
        val rig = tipSkipRig()

        roundThenQuietBeat(rig)

        rig.conflictDetails.putTransportJournal(
            journalKey = "replica-reset-receipt:current",
            payloadJson = "{not-a-receipt}",
            contentEpoch = 0L,
        )
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 2, pullCount = 2, appUpdateCalls = 2)
        assertThat(rig.conflictDetails.getTransportJournal("replica-reset-receipt:current"))
            .isNotNull()
    }

    @Test
    fun pendingPublishUnitsVetoTheSkip() = runTest {
        val rig = tipSkipRig()

        roundThenQuietBeat(rig)

        val babyId = rig.babies.seed(localReplicaBaby().copy(clientUuid = "baby-local"))
        rig.records.seed(
            RecordEntity(
                clientUuid = "tip-skip-pending-record",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = true,
                baseVersion = "v-pending",
            ),
        )
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 2, pullCount = 2, appUpdateCalls = 2)
        // The full round published the row.
        assertThat(rig.records.listPendingSync()).isEmpty()
    }

    @Test
    fun unconfirmedMissingMediaVetoesTheSkipButConfirmed404DoesNot() = runTest {
        val rig = tipSkipRig()
        val session = joinedSession("family-a")
        rig.backend.nextHeartbeat = quietHeartbeat(session)
        seedMissingMedia(rig, MEDIA_A)
        rig.backend.getMediaFailureByUuid[MEDIA_A] = SyncHttpException(404, "half upload")

        // Round 1: full round; the download loop GETs A, 404 → durable marker.
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 1, pullCount = 1, appUpdateCalls = 1)
        assertThat(rig.backend.mediaGets).containsExactly(MEDIA_A)
        assertThat(rig.conflictDetails.getTransportJournal("media-404:$MEDIA_A")).isNotNull()

        // Fresh beat proof, then: the only missing media is a confirmed 404 —
        // a never-graduating resident (review A8) — so tip-skip is not 钝化.
        advanceTipSkipTime(rig, 8_000)
        pumpUntil { rig.backend.heartbeatCalls >= 2 }
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 1, pullCount = 1, appUpdateCalls = 2)
        assertThat(rig.backend.mediaGets).containsExactly(MEDIA_A)

        // The marker only informs tip-skip: once another seam forces a full
        // round, A is retried, adopts, and its marker dies in the adoption.
        rig.backend.getMediaFailureByUuid.remove(MEDIA_A)
        seedMissingMedia(rig, MEDIA_B)
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 2, pullCount = 2, appUpdateCalls = 3)
        // Round 3 retried A (markers never stop the download loop) and
        // downloaded B for the first time. The two round-3 GETs run in
        // parallel (0.5 W4), so only the multiset is asserted.
        assertThat(rig.backend.mediaGets).containsExactly(MEDIA_A, MEDIA_A, MEDIA_B)
        assertThat(rig.conflictDetails.getTransportJournal("media-404:$MEDIA_A")).isNull()
        assertThat(rig.conflictDetails.getTransportJournal("media-404:$MEDIA_B")).isNull()
        assertThat(rig.media.getByClientUuid(MEDIA_A)?.localUri).isEqualTo("downloaded/$MEDIA_A")
    }

    @Test
    fun missingMediaWhoseLocalSaveFailsGetsADurableMarkerAndStopsVetoing() = runTest {
        val rig = tipSkipRig()
        val session = joinedSession("family-a")
        rig.backend.nextHeartbeat = quietHeartbeat(session)
        seedMissingMedia(rig, MEDIA_A)
        rig.mediaFiles.saveDownloadedFailures += MEDIA_A

        // Round 1: full round; the GET succeeds but the local save fails —
        // previously an invisible swallow that re-GETted forever and vetoed
        // tip-skip permanently. Now the condition is durably journaled.
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 1, pullCount = 1, appUpdateCalls = 1)
        assertThat(rig.backend.mediaGets).containsExactly(MEDIA_A)
        assertThat(rig.conflictDetails.getTransportJournal("media-save-failed:$MEDIA_A")).isNotNull()

        // Fresh beat proof, then: the only missing media is a durable local
        // save failure — a device condition sync cannot graduate — so the
        // skip is not 钝化 (same rationale as confirmed 404, review A8).
        advanceTipSkipTime(rig, 8_000)
        pumpUntil { rig.backend.heartbeatCalls >= 2 }
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 1, pullCount = 1, appUpdateCalls = 2)
        assertThat(rig.backend.mediaGets).containsExactly(MEDIA_A)

        // Free the disk. A's marker cannot know that, so a second unmarked
        // missing media forces the full round (mirroring the 404 test); the
        // round retries A, adopts it, and its marker dies with the adoption.
        rig.mediaFiles.saveDownloadedFailures.clear()
        seedMissingMedia(rig, MEDIA_B)
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 2, pullCount = 2, appUpdateCalls = 3)
        // Round 3 retried A and downloaded B; the two GETs run in parallel,
        // so only the multiset is asserted.
        assertThat(rig.backend.mediaGets).containsExactly(MEDIA_A, MEDIA_A, MEDIA_B)
        assertThat(rig.conflictDetails.getTransportJournal("media-save-failed:$MEDIA_A")).isNull()
        assertThat(rig.media.getByClientUuid(MEDIA_A)?.localUri).isEqualTo("downloaded/$MEDIA_A")
        assertThat(rig.media.getByClientUuid(MEDIA_B)?.localUri).isEqualTo("downloaded/$MEDIA_B")
    }

    @Test
    fun expiredFreshnessWindowForcesAFullRound() = runTest {
        val rig = tipSkipRig()
        val session = joinedSession("family-a")
        rig.backend.nextHeartbeat = quietHeartbeat(session)
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 1, pullCount = 1, appUpdateCalls = 1)
        val anchorAt = rig.clock.now

        // Quiet beats continue on cadence without ever kicking a round; the
        // proof keys stay fresh but the window anchors at round 1. The window
        // under test IS the heartbeat policy's backoff cap — reading it from
        // SyncHeartbeatPolicy here pins the shared-source consistency (0.5
        // ticket 04 + 07): the test follows automatically when the policy
        // changes.
        val windowMillis = SyncHeartbeatPolicy.MAX_NO_CHANGE_INTERVAL_MILLIS
        var guard = 0
        while (rig.clock.now - anchorAt <= windowMillis && guard++ < 40) {
            advanceTipSkipTime(rig, 30_000)
            pumpUntil { rig.backend.handshakeCalls == 1 }
        }
        assertThat(rig.clock.now - anchorAt).isGreaterThan(windowMillis)
        assertThat(rig.backend.heartbeatCalls).isAtLeast(3)

        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 2, pullCount = 2, appUpdateCalls = 2)
        settleIdle(rig)
    }

    @Test
    fun oldServerWithoutHeartbeatNeverProducesAProofAndNeverSkips() = runTest {
        val rig = oldServerRig()
        pumpUntil { !rig.port.heartbeatLoopActive }
        assertThat(rig.backend.heartbeatCalls).isEqualTo(1) // one-shot discovery 404

        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 1, pullCount = 1, appUpdateCalls = 1)
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 2, pullCount = 2, appUpdateCalls = 2)
        settleIdle(rig)
    }

    @Test
    fun skippedRoundLeavesTheObservableStateUntouched() = runTest {
        val rig = tipSkipRig()

        roundThenQuietBeat(rig)
        val healthyAt = rig.preferences.lastServerHealthyAt.first()

        val seenStatuses = mutableListOf<SyncStatus>()
        val collector = launch {
            rig.port.status().collect { seenStatuses.add(it) }
        }
        try {
            rig.port.requestSync(SyncTrigger.Foreground)
            pumpUntilRoundCompleted(rig, handshakeCalls = 1, pullCount = 1, appUpdateCalls = 2)
        } finally {
            collector.cancel()
        }

        // The skip never flipped Syncing, never wrote a failure, never moved
        // the last-healthy timestamp. (The zero-progress fuse cannot be
        // dirtied by construction: only real-trigger rounds skip, and
        // requestSync released the fuse before the round; kicks and
        // continuations carry the veto and never reach this path.)
        assertThat(seenStatuses).doesNotContain(SyncStatus.Syncing)
        assertThat(rig.preferences.lastServerHealthyAt.first()).isEqualTo(healthyAt)
        assertThat(rig.port.lastFailureKind().first()).isNull()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    // --- fixtures ----------------------------------------------------------------

    /**
     * Canonical fresh-proof setup: one full foreground round (handshake +
     * pull, stamping the freshness anchor) followed by one quiet no-change
     * beat (minting the three-key proof).
     */
    private suspend fun TestScope.roundThenQuietBeat(
        rig: SyncRig,
        session: SyncSession = joinedSession("family-a"),
    ) {
        rig.backend.nextHeartbeat = quietHeartbeat(session)
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntilRoundCompleted(rig, handshakeCalls = 1, pullCount = 1, appUpdateCalls = 1)
        advanceTipSkipTime(rig, 8_000)
        pumpUntil { rig.backend.heartbeatCalls >= 2 }
    }

    /**
     * A joined foreground rig whose availability probe advertises
     * `sync_heartbeat_v1` (same shape as RealSyncPortHeartbeatLoopTest):
     * constructed unjoined so no loop launches before the test-scope
     * overrides exist; the establishment refresh below runs one discovery
     * beat plus the anonymous first-establishment triple.
     */
    private suspend fun TestScope.tipSkipRig(): SyncRig {
        val setupProbe = SetupProbe { draft, trusted ->
            setupProbeCalls++
            SetupProbeResult.Ready(
                endpoint = trusted ?: TrustedEndpointProfile.systemPki(draft),
                familyState = SetupFamilyState.Configured,
                capabilities = setOf(CAPABILITY_SYNC_HEARTBEAT_V1),
            )
        }
        val rig = SyncRig(
            session = joinedSession("family-a").copy(familyId = ""),
            setupProbe = setupProbe,
        )
        rig.port.heartbeatLoopScopeOverride = backgroundScope
        rig.port.heartbeatJitterOverride = HeartbeatJitterSource { 0 }
        rig.awaitStartupRecovery()
        rig.preferences.saveSession(joinedSession("family-a"))
        rig.preferences.seedFamilyMemberDirectory(
            generation = "directory-test",
            members = listOf(
                FamilyMember(
                    "管理员",
                    FamilyRole.Owner,
                    isSelf = true,
                    membershipId = "membership-a",
                ),
            ),
        )
        val result = rig.port.probeServerAvailability(AvailabilityProbeReason.PullToRefresh)
        assertThat(result.isSuccess).isTrue()
        pumpUntil { rig.port.availability().first() is FamilyServerAvailability.Available }
        pumpUntil { rig.port.heartbeatLoopActive }
        return rig
    }

    /** A joined rig whose one-shot discovery beat hits a 0.4.7 server (404). */
    private suspend fun TestScope.oldServerRig(): SyncRig {
        val setupProbe = SetupProbe { draft, trusted ->
            setupProbeCalls++
            SetupProbeResult.Ready(
                endpoint = trusted ?: TrustedEndpointProfile.systemPki(draft),
                familyState = SetupFamilyState.Configured,
                capabilities = emptySet(),
            )
        }
        val rig = SyncRig(
            session = joinedSession("family-a").copy(familyId = ""),
            setupProbe = setupProbe,
        )
        rig.port.heartbeatLoopScopeOverride = backgroundScope
        rig.port.heartbeatJitterOverride = HeartbeatJitterSource { 0 }
        rig.backend.heartbeatFailure = SyncHttpException(404, """{"detail":"Not Found"}""")
        rig.awaitStartupRecovery()
        rig.preferences.saveSession(joinedSession("family-a"))
        rig.preferences.seedFamilyMemberDirectory(
            generation = "directory-test",
            members = listOf(
                FamilyMember(
                    "管理员",
                    FamilyRole.Owner,
                    isSelf = true,
                    membershipId = "membership-a",
                ),
            ),
        )
        pumpUntil { rig.port.heartbeatLoopActive }
        // The loop's discovery beat fires after the +8s launch debounce.
        advanceTipSkipTime(rig, 8_000)
        pumpUntil { !rig.port.heartbeatLoopActive }
        return rig
    }

    private fun seedMissingMedia(rig: SyncRig, clientUuid: String) {
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = "baby-local",
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = "record-tip-skip-host",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = false,
                baseVersion = "v-host",
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = clientUuid,
                kind = "log",
                localUri = "",
                remoteUri = rig.preferences.current().receiptFor(clientUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
    }

    /**
     * Waits until the round triggered by the last requestSync fully finished,
     * including its piggyback app-update tail.
     */
    private suspend fun TestScope.pumpUntilRoundCompleted(
        rig: SyncRig,
        handshakeCalls: Int,
        pullCount: Int,
        appUpdateCalls: Int,
    ) {
        try {
            pumpUntil {
                rig.backend.getAppUpdateMetadataCalls >= appUpdateCalls &&
                    rig.backend.handshakeCalls == handshakeCalls &&
                    rig.backend.pullCount == pullCount
            }
        } catch (e: IllegalStateException) {
            throw IllegalStateException(
                "TIMEOUT state: appUpdate=${rig.backend.getAppUpdateMetadataCalls}/$appUpdateCalls " +
                    "handshake=${rig.backend.handshakeCalls}/$handshakeCalls " +
                    "pull=${rig.backend.pullCount}/$pullCount " +
                    "mediaGets=${rig.backend.mediaGets} " +
                    "status=${rig.port.status().first()} failure=${rig.port.lastFailureKind().first()}",
                e,
            )
        }
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
            check(System.nanoTime() < deadline) { "tip-skip condition not met in time" }
        }
    }

    private fun TestScope.advanceTipSkipTime(rig: SyncRig, millis: Long) {
        rig.clock.now += millis
        testScheduler.advanceTimeBy(millis)
        runCurrent()
    }

    private suspend fun TestScope.settleIdle(rig: SyncRig) {
        pumpUntil { rig.port.status().first() == SyncStatus.Idle }
        withContext(Dispatchers.IO) { delay(150) }
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    private fun quietHeartbeat(session: SyncSession) = SyncHeartbeat(
        generation = session.pullGeneration,
        headRev = session.pullCursor,
        directoryGeneration = "directory-test",
    )

    private fun needsSyncHeartbeat(session: SyncSession) =
        quietHeartbeat(session).copy(headRev = session.pullCursor + 1)

    private companion object {
        const val MEDIA_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"
        const val MEDIA_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2"

        /** Minimal valid encoded replica reset receipt (schema 1, no roots). */
        val RESET_RECEIPT_JSON = """
            {"schema":1,"previous_family_id":"family-z","previous_membership_id":"m-z",
             "previous_device_id":"d-z","crossing_family_boundary":false,
             "recovery_target":null,"roots":[]}
        """.trimIndent()
    }
}
