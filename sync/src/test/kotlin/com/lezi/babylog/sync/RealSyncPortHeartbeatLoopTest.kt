package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.sync.availability.AvailabilityProbeReason
import com.lezi.babylog.sync.availability.FamilyServerAvailability
import com.lezi.babylog.sync.availability.FamilyServerUnavailableReason
import com.lezi.babylog.sync.backend.RemoteDeviceRemovedException
import com.lezi.babylog.sync.backend.SyncHeartbeat
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.deadline.ForegroundSyncCycle
import com.lezi.babylog.sync.backend.deadline.LOCAL_WRITE_MAX_ELAPSED_MILLIS
import com.lezi.babylog.sync.heartbeat.HeartbeatJitterSource
import com.lezi.babylog.sync.session.CAPABILITY_SYNC_HEARTBEAT_V1
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbe
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.SpkiPinMismatchException
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/**
 * RealSyncPort integration contract of the 0.4.8 foreground heartbeat loop
 * (ticket 04): the engine's NeedsSync verdict kicks the EXISTING conflated
 * foreground loop (never a second signal channel), the loop is cancel-style
 * foreground-only (background and session loss tear it down — zero probes),
 * `Syncing` skips due beats without accumulation, terminal 401 codes route
 * through the existing device-removed mapping, availability feeds the shared
 * StateFlow both ways, and heartbeat-triggered rounds ride the same
 * zero-progress continue fuse and 120s cycle budget as any Foreground round.
 * Arming is residency-driven (compat matrix research §6, either signal
 * decides): the loop opens with the engine's one-shot discovery beat when no
 * settings visit ever advertised the capability — exactly one request
 * decides against an old server — and a 404 disable outranks a late
 * setup-status advertisement.
 *
 * Determinism discipline: [pumpUntil] blocks the test thread with
 * [Thread.sleep] — never suspending the coroutine, so the runTest driver
 * never auto-advances virtual time — and drains only currently-due scheduler
 * tasks with [runCurrent]. [advanceHeartbeatTime] moves the port clock and
 * the virtual scheduler in lockstep, so beats fire exactly when the test
 * decides and never while a pump is parked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealSyncPortHeartbeatLoopTest {

    /** Observes how many times the rig's /v1/setup-status probe executed. */
    private var setupProbeCalls = 0

    @Test
    fun needsSyncVerdictKicksExactlyOneConflatedForegroundRoundPerSignal() = runTest {
        val rig = heartbeatRig()
        val session = joinedSession("family-a")

        // NoAction stays silent: one probe, no round.
        rig.backend.nextHeartbeat = quietHeartbeat(session)
        advanceHeartbeatTime(rig, 8_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)
        assertThat(rig.backend.handshakeCalls).isEqualTo(0)

        // NeedsSync kicks the existing foreground loop: exactly one round.
        rig.backend.nextHeartbeat = needsSyncHeartbeat(session)
        advanceHeartbeatTime(rig, 60_000)
        pumpUntil {
            rig.backend.handshakeCalls == 1 && rig.port.status().first() == SyncStatus.Idle
        }
        assertThat(rig.backend.heartbeatCalls).isEqualTo(3)
        assertThat(rig.backend.pullCount).isEqualTo(1)
        rig.backend.nextHeartbeat = quietHeartbeat(session)
        settleIdle(rig)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(3)
        assertThat(rig.backend.handshakeCalls).isEqualTo(1)
    }

    @Test
    fun twoNeedsSyncBeatsWhileARoundIsPendingStillProduceOneConflatedRound() = runTest {
        val rig = heartbeatRig()
        val session = joinedSession("family-a")

        // Hold the FIRST sync in the port's pre-round recovery seam: the
        // conflated-signal consumer is busy while beats are not suppressed
        // (status is not Syncing yet) — the deterministic conflation window.
        val holdSync = CompletableDeferred<Unit>()
        rig.pendingDomainRecovery.holdNextCall = holdSync
        rig.backend.nextHeartbeat = needsSyncHeartbeat(session)

        advanceHeartbeatTime(rig, 8_000)
        pumpUntil { rig.pendingDomainRecovery.calls >= 2 }
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)
        assertThat(rig.backend.handshakeCalls).isEqualTo(0)

        // Second NeedsSync beat while the first round is still pending.
        advanceHeartbeatTime(rig, 60_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(3)
        assertThat(rig.backend.handshakeCalls).isEqualTo(0)

        // Release: round 1 runs; the conflated channel holds ONE pending kick.
        rig.backend.nextHeartbeat = quietHeartbeat(session)
        holdSync.complete(Unit)
        pumpUntil {
            rig.pendingDomainRecovery.calls >= 3 &&
                rig.port.status().first() == SyncStatus.Idle
        }
        settleIdle(rig)

        // A queued (non-conflated) channel would have produced a third round.
        assertThat(rig.backend.handshakeCalls).isEqualTo(2)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(3)
    }

    @Test
    fun backgroundTearsTheLoopDownAndForegroundReturnDebouncesFirstBeatByEightSeconds() = runTest {
        val rig = heartbeatRig()
        rig.backend.nextHeartbeat = quietHeartbeat(joinedSession("family-a"))

        advanceHeartbeatTime(rig, 8_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)

        // Cancellation is immediate; do not advance to the old scheduled beat.
        rig.foreground.setForeground(false)
        runCurrent()
        pumpUntil { !rig.port.heartbeatLoopActive }
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)

        // 回前台 (LeziApp.onStart: setForeground(true) + requestSync(Foreground)).
        rig.foreground.setForeground(true)
        val kickAt = rig.clock.now
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntil { rig.port.heartbeatLoopActive }
        // The relaunched loop's first beat is debounced by exactly +8s.
        assertThat(rig.port.heartbeatNextBeatAtMillis.value).isEqualTo(kickAt + 8_000)

        advanceHeartbeatTime(rig, 7_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)
        advanceHeartbeatTime(rig, 1_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(3)
    }

    @Test
    fun backgroundCancelsBlockedPullWithoutKillingTheSignalHost() = runTest {
        val rig = heartbeatRig()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        rig.backend.beforePullReturn = {
            entered.complete(Unit)
            try { kotlinx.coroutines.awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntil { entered.isCompleted }
        rig.foreground.setForeground(false)
        pumpUntil { cancelled.isCompleted }
        val pullsBeforeReturn = rig.backend.pullCount
        rig.foreground.setForeground(true)
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntil { rig.backend.pullCount > pullsBeforeReturn }
        settleIdle(rig)
    }

    @Test
    fun syncingRoundSkipsDueBeatsWithoutQueueingABurstAfterwards() = runTest {
        val rig = heartbeatRig()
        rig.backend.nextHeartbeat = quietHeartbeat(joinedSession("family-a"))

        advanceHeartbeatTime(rig, 8_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)
        assertThat(rig.backend.handshakeCalls).isEqualTo(0)

        // A long round parks inside the handshake and spans several deadlines.
        val parked = CompletableDeferred<Unit>()
        rig.backend.handshakeGate = parked
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntil { rig.port.status().first() == SyncStatus.Syncing }

        advanceHeartbeatTime(rig, 5 * 60_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)

        // After the round the loop re-derives from nextBeatAtMillis: exactly
        // ONE overdue beat fires — no probe queue, no accumulation.
        rig.backend.handshakeGate = null
        parked.complete(Unit)
        pumpUntil { rig.backend.heartbeatCalls == 3 }
        settleIdle(rig)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(3)
        assertThat(rig.backend.handshakeCalls).isEqualTo(1)
    }

    @Test
    fun syncingWaitCapExpiresWithoutKillingTheLoopAndABeatStillFiresAfterTheRound() = runTest {
        val rig = heartbeatRig()
        rig.backend.nextHeartbeat = quietHeartbeat(joinedSession("family-a"))

        advanceHeartbeatTime(rig, 8_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)
        assertThat(rig.backend.handshakeCalls).isEqualTo(0)

        val parked = CompletableDeferred<Unit>()
        rig.backend.handshakeGate = parked
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntil { rig.port.status().first() == SyncStatus.Syncing }

        advanceHeartbeatTime(
            rig,
            ForegroundSyncCycle.MAX_ELAPSED_MILLIS + LOCAL_WRITE_MAX_ELAPSED_MILLIS + 1_000,
        )
        assertThat(rig.port.heartbeatLoopActive).isTrue()
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)

        rig.backend.handshakeGate = null
        parked.complete(Unit)
        pumpUntil { rig.backend.heartbeatCalls == 3 }
        settleIdle(rig)
        assertThat(rig.port.heartbeatLoopActive).isTrue()
        assertThat(rig.backend.heartbeatCalls).isEqualTo(3)
        assertThat(rig.backend.handshakeCalls).isEqualTo(1)
    }

    @Test
    fun sessionLossCancelsTheLoopImmediatelyAndRejoinRelaunchesIt() = runTest {
        val rig = heartbeatRig()
        rig.backend.nextHeartbeat = quietHeartbeat(joinedSession("family-a"))

        advanceHeartbeatTime(rig, 8_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)

        // Unjoined mid-loop: the session watcher cancels immediately.
        rig.preferences.saveSession(SyncSession())
        pumpUntil { !rig.port.heartbeatLoopActive }
        advanceHeartbeatTime(rig, 60 * 60_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)

        // Re-joined → eligible again; the relaunch debounces its first beat.
        rig.preferences.saveSession(joinedSession("family-a"))
        pumpUntil { rig.port.heartbeatLoopActive }
        assertThat(rig.port.heartbeatNextBeatAtMillis.value).isEqualTo(rig.clock.now + 8_000)
        advanceHeartbeatTime(rig, 8_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(3)

        // reauthRequired mid-loop cancels again: never probe a dead endpoint.
        rig.preferences.clearDeviceCredentialsForReauth()
        pumpUntil { !rig.port.heartbeatLoopActive }
        val before = rig.backend.heartbeatCalls
        advanceHeartbeatTime(rig, 60 * 60_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(before)
    }

    @Test
    fun terminalDeviceRemovedFromABeatRoutesThroughTheExistingRemovalMapping() = runTest {
        val removalGate = TestRemovedDeviceLocalClearGate()
        val rig = heartbeatRig(removedDeviceLocalClearGate = removalGate)
        rig.backend.heartbeatFailure = RemoteDeviceRemovedException()
        val availabilityBeforeBeat = rig.port.availability().first()

        advanceHeartbeatTime(rig, 8_000)

        // Not swallowed: the SAME handling as sync() invalidates the session
        // (durable terminal clear → unjoined → Disabled).
        pumpUntil { rig.port.status().first() == SyncStatus.Disabled }
        pumpUntil { !rig.port.heartbeatLoopActive }
        assertThat(removalGate.calls).isAtLeast(1)
        assertThat(rig.preferences.current().isJoined).isFalse()
        assertThat(rig.preferences.hasPendingDeviceRemovalClear()).isFalse()
        // The terminal itself is not an availability failure.
        assertThat(rig.port.availability().first()).isEqualTo(availabilityBeforeBeat)
        // And the dead loop never probes again.
        advanceHeartbeatTime(rig, 60 * 60_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)
    }

    @Test
    fun discoveryAloneCostsAnOldServerExactlyOneRequestThenStaysSilentForever() = runTest {
        // Fresh joined client, foreground, endpoint pinned — the network
        // settings page is NEVER opened (no probe call in this rig).
        val rig = discoveryRig(advertised = false)
        rig.backend.heartbeatFailure = SyncHttpException(404, """{"detail":"Not Found"}""")

        pumpUntil { rig.port.heartbeatLoopActive }
        advanceHeartbeatTime(rig, 8_000)
        pumpUntil { !rig.port.heartbeatLoopActive }

        // The un-advertised discovery beat hit 404: permanent silent disable —
        // never an availability failure, no FailureKind, no status noise.
        assertThat(rig.backend.heartbeatCalls).isEqualTo(1)
        assertThat(rig.port.availability().first())
            .isEqualTo(FamilyServerAvailability.Disabled)
        assertThat(rig.port.lastFailureKind().first()).isNull()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)

        // A long foreground session (incl. a foreground-return kick) never
        // retries: total cost vs an old server is exactly one request.
        advanceHeartbeatTime(rig, 60 * 60_000)
        rig.port.requestSync(SyncTrigger.Foreground)
        advanceHeartbeatTime(rig, 60 * 60_000)
        pumpUntil { !rig.port.heartbeatLoopActive }
        assertThat(rig.backend.heartbeatCalls).isEqualTo(1)
    }

    @Test
    fun discoveryAloneArmsTheEngineAndHeartbeatsOnCadenceWithoutUserAction() = runTest {
        val rig = discoveryRig()
        val session = joinedSession("family-a")
        rig.backend.nextHeartbeat = quietHeartbeat(session)

        pumpUntil { rig.port.heartbeatLoopActive }
        advanceHeartbeatTime(rig, 8_000)
        pumpUntil { rig.backend.heartbeatCalls == 1 }

        // The discovery beat answered 2xx: armed by probe evidence alone —
        // zero user action beyond foreground, and the loop stays resident.
        assertThat(rig.port.heartbeatLoopActive).isTrue()

        // Cadence continues unprompted: the next quiet beat one baseline later.
        advanceHeartbeatTime(rig, 60_000)
        pumpUntil { rig.backend.heartbeatCalls == 2 }
        assertThat(rig.port.heartbeatLoopActive).isTrue()
        assertThat(rig.backend.handshakeCalls).isEqualTo(0)
    }

    @Test
    fun lateSetupStatusAdvertisementCannotReArmA404DisabledDiscovery() = runTest {
        val rig = discoveryRig(advertised = true)
        rig.backend.heartbeatFailure = SyncHttpException(404, """{"detail":"Not Found"}""")

        pumpUntil { rig.port.heartbeatLoopActive }
        advanceHeartbeatTime(rig, 8_000)
        pumpUntil { !rig.port.heartbeatLoopActive }
        assertThat(rig.backend.heartbeatCalls).isEqualTo(1)

        // The user NOW opens the network settings and the (mistakenly
        // advertising) setup-status answers Ready with sync_heartbeat_v1.
        rig.backend.heartbeatFailure = null
        val result = rig.port.probeServerAvailability(AvailabilityProbeReason.PullToRefresh)
        assertThat(result.isSuccess).isTrue()
        pumpUntil { rig.port.availability().first() is FamilyServerAvailability.Available }

        // The 404 gate outranks the late advertisement: no re-arm, no loop,
        // no further traffic.
        assertThat(rig.port.heartbeatLoopActive).isFalse()
        advanceHeartbeatTime(rig, 60 * 60_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(1)
        assertThat(rig.port.heartbeatLoopActive).isFalse()
    }

    @Test
    fun localWriteRequestResetsTheBackedOffHeartbeatDeadlineToBaseline() = runTest {
        val rig = heartbeatRig()
        rig.backend.nextHeartbeat = quietHeartbeat(joinedSession("family-a"))

        // Four quiet no-change beats walk 60s → 120s → 240s → the 300s cap.
        advanceHeartbeatTime(rig, 8_000)
        pumpUntil { rig.backend.heartbeatCalls == 2 }
        advanceHeartbeatTime(rig, 60_000)
        pumpUntil { rig.backend.heartbeatCalls == 3 }
        advanceHeartbeatTime(rig, 120_000)
        pumpUntil { rig.backend.heartbeatCalls == 4 }
        advanceHeartbeatTime(rig, 240_000)
        pumpUntil { rig.backend.heartbeatCalls == 5 }
        assertThat(rig.port.heartbeatNextBeatAtMillis.value)
            .isEqualTo(rig.clock.now + 300_000)

        // Write ten seconds into an already parked five-minute deadline.
        advanceHeartbeatTime(rig, 10_000)
        rig.port.requestSync(SyncTrigger.LocalWrite)
        assertThat(rig.port.heartbeatNextBeatAtMillis.value)
            .isEqualTo(rig.clock.now + 60_000)

        settleIdle(rig)
        advanceHeartbeatTime(rig, 59_999)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(5)
        advanceHeartbeatTime(rig, 1)
        pumpUntil { rig.backend.heartbeatCalls == 6 }
        settleIdle(rig)
    }

    @Test
    fun heartbeatFeedsTheSharedAvailabilityStateBothWays() = runTest {
        val rig = heartbeatRig()
        val session = joinedSession("family-a")

        // The anonymous probe established Available (first-establishment stays
        // its authority); beats then feed it as a resident feeder.
        assertThat(rig.port.availability().first()).isEqualTo(
            FamilyServerAvailability.Available(
                endpointOrigin = "https://192.168.1.20:8787",
                serverVersion = "0.3.3",
                lastHealthyAtMillis = rig.clock.now,
                leaseUntilMillis = rig.clock.now + 30_000,
                familyState = SetupFamilyState.Configured,
            ),
        )

        // Success rehabilitates in place: refreshed healthy timestamps.
        rig.backend.nextHeartbeat = quietHeartbeat(session)
        advanceHeartbeatTime(rig, 8_000)
        pumpUntil { rig.backend.heartbeatCalls == 2 }
        val refreshed = rig.port.availability().first()
            as FamilyServerAvailability.Available
        assertThat(refreshed.endpointOrigin).isEqualTo("https://192.168.1.20:8787")
        assertThat(refreshed.serverVersion).isEqualTo("0.3.3")
        assertThat(refreshed.lastHealthyAtMillis).isEqualTo(rig.clock.now)
        assertThat(refreshed.leaseUntilMillis).isEqualTo(rig.clock.now + 30_000)

        // A transport failure degrades immediately — single failure, no new
        // hysteresis — on the same StateFlow the settings page observes.
        rig.backend.heartbeatFailure = IOException("connection reset")
        advanceHeartbeatTime(rig, 60_000)
        pumpUntil {
            rig.port.availability().first() is FamilyServerAvailability.Unavailable
        }
        val degraded = rig.port.availability().first()
            as FamilyServerAvailability.Unavailable
        assertThat(degraded.reason).isEqualTo(FamilyServerUnavailableReason.Unreachable)
        assertThat(degraded.consecutiveFailures).isEqualTo(1)
        assertThat(degraded.lastHealthyAtMillis).isEqualTo(refreshed.lastHealthyAtMillis)

        // Recovery after the degraded ladder rehabilitates Available again,
        // and probe failures never write a FailureKind or a status change.
        rig.backend.heartbeatFailure = null
        advanceHeartbeatTime(rig, degraded.nextProbeAtMillis - rig.clock.now)
        pumpUntil { rig.port.availability().first() is FamilyServerAvailability.Available }
        assertThat(rig.port.lastFailureKind().first()).isNull()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun notFoundEndpointPermanentlyDisablesTheLoopWithoutAvailabilityDamage() = runTest {
        val rig = heartbeatRig()
        val availabilityBeforeBeat = rig.port.availability().first()
        rig.backend.heartbeatFailure = SyncHttpException(404, """{"detail":"Not Found"}""")

        advanceHeartbeatTime(rig, 8_000)
        pumpUntil { !rig.port.heartbeatLoopActive }

        // Old server behind a stale capability: permanent silent disable — no
        // retry, no availability degradation, no sync-failure path.
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)
        assertThat(rig.port.availability().first()).isEqualTo(availabilityBeforeBeat)
        rig.port.requestSync(SyncTrigger.Foreground)
        advanceHeartbeatTime(rig, 60 * 60_000)
        pumpUntil { !rig.port.heartbeatLoopActive }
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)
    }

    @Test
    fun zeroProgressFuseSilencesIdenticalSignalsAcrossBeatsWhileProbesContinue() = runTest {
        val rig = heartbeatRig()
        val session = joinedSession("family-a")

        armZeroProgressFuse(rig, session)

        // The fuse is armed for the observed signal. SEVERAL beats later the
        // SAME difference never opens another cluster: probes continue on
        // cadence (heartbeat count grows, availability refreshes) while
        // handshake/pull stay frozen at the fused cluster — the review-P2-6
        // 「一直在同步」 churn is gone.
        repeat(5) { index ->
            advanceHeartbeatTime(rig, 60_000)
            pumpUntil { rig.backend.heartbeatCalls == 3 + index }
        }
        assertThat(rig.backend.handshakeCalls).isEqualTo(3)
        assertThat(rig.backend.pullCount).isEqualTo(0)
        pumpUntil {
            val available =
                rig.port.availability().first() as? FamilyServerAvailability.Available
            available?.lastHealthyAtMillis == rig.clock.now
        }

        // Still silent one more beat later: the suppression is not a
        // one-beat accident.
        advanceHeartbeatTime(rig, 60_000)
        pumpUntil { rig.backend.heartbeatCalls == 8 }
        assertThat(rig.backend.handshakeCalls).isEqualTo(3)
    }

    @Test
    fun changedAndRegressedSignalsReleaseTheFuseIntoExactlyOneRound() = runTest {
        val rig = heartbeatRig()
        val session = joinedSession("family-a")
        armZeroProgressFuse(rig, session)

        // A new signal gets a fresh budget even when it also makes no progress.
        rig.backend.nextHeartbeat =
            needsSyncHeartbeat(session).copy(headRev = session.pullCursor + 2)
        advanceHeartbeatTime(rig, 60_000)
        pumpUntil { rig.backend.handshakeCalls == 6 }
        advanceHeartbeatTime(rig, 60_000)
        pumpUntil { rig.backend.heartbeatCalls == 4 }
        assertThat(rig.backend.handshakeCalls).isEqualTo(6)

        // A regressed watermark is different too, and success stops continuation.
        rig.backend.handshakeFailure = null
        rig.backend.nextHeartbeat =
            needsSyncHeartbeat(session).copy(headRev = session.pullCursor - 3)
        advanceHeartbeatTime(rig, 60_000)
        pumpUntil { rig.backend.handshakeCalls == 7 }
        settleIdle(rig)
        assertThat(rig.backend.pullCount).isEqualTo(1)

        rig.backend.nextHeartbeat = quietHeartbeat(session)
        advanceHeartbeatTime(rig, 60_000)
        pumpUntil { rig.backend.heartbeatCalls == 6 }
        assertThat(rig.backend.handshakeCalls).isEqualTo(7)
    }

    @Test
    fun realForegroundReturnReleasesTheFuseAndResetsTheZeroProgressBudget() = runTest {
        val rig = heartbeatRig()
        val session = joinedSession("family-a")

        armZeroProgressFuse(rig, session)

        // 回前台 (LeziApp.onStart → requestSync(Foreground)) is a REAL
        // trigger: retries are allowed again AND the zero-progress budget
        // resets — the user's cluster runs its FULL budget (rounds 4,5,6),
        // not a single exhausted round.
        rig.port.requestSync(SyncTrigger.Foreground)
        pumpUntil { rig.backend.handshakeCalls == 6 }

        // The user's cluster chased no observed signal, so it arms nothing:
        // the NEXT identical beat opens one more signal-chasing cluster
        // (rounds 7,8,9), which arms the fuse — silence follows.
        advanceHeartbeatTime(rig, 60_000)
        pumpUntil { rig.backend.handshakeCalls == 9 }
        repeat(3) { index ->
            advanceHeartbeatTime(rig, 60_000)
            pumpUntil { rig.backend.heartbeatCalls == 4 + index }
        }
        assertThat(rig.backend.handshakeCalls).isEqualTo(9)
        assertThat(rig.backend.pullCount).isEqualTo(0)
    }

    @Test
    fun localWriteReleasesTheFuseAndTheNextIdenticalBeatRunsAFreshCluster() = runTest {
        val rig = heartbeatRig()
        val session = joinedSession("family-a")

        armZeroProgressFuse(rig, session)

        // 真实本地写: requestSync(LocalWrite) is a REAL trigger. Its own
        // push-only round fails at the same 409 handshake (LocalWrite never
        // auto-continues and never pulls), and the fuse is released.
        rig.port.requestSync(SyncTrigger.LocalWrite)
        pumpUntil { rig.backend.handshakeCalls == 4 }
        assertThat(rig.backend.pullCount).isEqualTo(0)

        // The budget was reset: the next identical beat opens a FULL fresh
        // cluster (rounds 5,6,7) — one round would mean a stale counter —
        // which then arms the fuse again.
        advanceHeartbeatTime(rig, 60_000)
        pumpUntil { rig.backend.handshakeCalls == 7 }
        repeat(3) { index ->
            advanceHeartbeatTime(rig, 60_000)
            pumpUntil { rig.backend.heartbeatCalls == 4 + index }
        }
        assertThat(rig.backend.handshakeCalls).isEqualTo(7)
        assertThat(rig.backend.pullCount).isEqualTo(0)
    }

    @Test
    fun identitySwitchNeverReusesTheOldFuseForAnIdenticalSignal() = runTest {
        val rig = heartbeatRig()

        armZeroProgressFuse(rig, joinedSession("family-a"))

        // Another family whose stuck server reports the byte-identical
        // three-key signal: the suppression must NOT cross the identity —
        // the new session gets its own full budget (rounds 4,5,6) and then
        // arms its own fuse.
        rig.preferences.saveSession(joinedSession("family-b"))
        pumpUntil { rig.port.heartbeatLoopActive }
        advanceHeartbeatTime(rig, 60_000)
        pumpUntil { rig.backend.handshakeCalls == 6 }
        repeat(2) { index ->
            advanceHeartbeatTime(rig, 60_000)
            pumpUntil { rig.backend.heartbeatCalls == 4 + index }
        }
        assertThat(rig.backend.handshakeCalls).isEqualTo(6)
        assertThat(rig.backend.pullCount).isEqualTo(0)
    }

    // --- ticket 05: two-formed manual refresh routing ----------------------------

    @Test
    fun manualRefreshWithActiveGateSendsOneAuthenticatedHeartbeatAndZeroAnonymousCalls() = runTest {
        val rig = heartbeatRig()
        val session = joinedSession("family-a")
        // One beat already fired: the rig's joined establishment refresh rides
        // the same manual-heartbeat form under test (discovery beat + tail).
        assertThat(rig.backend.heartbeatCalls).isEqualTo(1)
        // Anonymous baselines after the rig's one-time establishment tail.
        val anonymousHealthBefore = rig.backend.anonymousHealthCalls
        val anonymousReadyBefore = rig.backend.anonymousReadyCalls

        // Move off the rig-establishment timestamp so the refresh is visible.
        advanceHeartbeatTime(rig, 1_000)
        val before = rig.clock.now
        rig.backend.nextHeartbeat = quietHeartbeat(session)

        val result = rig.port.probeServerAvailability(AvailabilityProbeReason.PullToRefresh)
            .getOrThrow() as FamilyServerAvailability.Available

        // Exactly one authenticated beat; the anonymous triple never fires.
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(anonymousHealthBefore)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(anonymousReadyBefore)
        assertThat(setupProbeCalls).isEqualTo(1) // only the rig's establishment tail

        // Availability reflects the beat: rehabilitation refreshes the healthy
        // timestamps in place and persists them like the historical probe.
        assertThat(result.endpointOrigin).isEqualTo("https://192.168.1.20:8787")
        assertThat(result.serverVersion).isEqualTo("0.3.3")
        assertThat(result.lastHealthyAtMillis).isEqualTo(before)
        assertThat(result.leaseUntilMillis).isEqualTo(before + 30_000)
        assertThat(rig.port.availability().first()).isEqualTo(result)
        assertThat(rig.preferences.lastServerHealthyAt.first()).isEqualTo(before)

        // The manual beat is an ordinary engine beat: its bookkeeping
        // reschedules the loop deadline (quiet beat → one 60s baseline, jitter 0).
        assertThat(rig.port.heartbeatNextBeatAtMillis.value).isEqualTo(before + 60_000)
    }

    @Test
    fun manualRefreshDoesNotKickASyncRoundEvenWhenTheBeatReportsNeedsSync() = runTest {
        val rig = heartbeatRig()
        rig.backend.nextHeartbeat = needsSyncHeartbeat(joinedSession("family-a"))

        val result = rig.port.probeServerAvailability(AvailabilityProbeReason.PullToRefresh)

        // A settings-page availability refresh never launches data rounds —
        // the historical anonymous path did not either. The verdict is pinned
        // here: zero handshake/pull directly from the manual probe.
        assertThat(result.isSuccess).isTrue()
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)
        assertThat(rig.backend.handshakeCalls).isEqualTo(0)
        assertThat(rig.backend.pullCount).isEqualTo(0)

        // The signal is not lost: the resident loop re-derives the SAME
        // verdict at the beat-rescheduled baseline deadline and kicks the
        // existing conflated round there.
        advanceHeartbeatTime(rig, 60_000)
        pumpUntil { rig.backend.handshakeCalls == 1 && rig.backend.pullCount == 1 }
        settleIdle(rig)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(3)
    }

    @Test
    fun unjoinedManualRefreshStaysOnTheAnonymousTripleWithZeroHeartbeats() = runTest {
        // Unjoined but with a pinned endpoint (settings page before joining).
        val rig = SyncRig(
            session = SyncSession().copy(
                serverHost = "192.168.1.20",
                serverPort = 8787,
            ),
            setupProbe = SetupProbe { _, trusted ->
                setupProbeCalls++
                SetupProbeResult.Ready(
                    requireNotNull(trusted),
                    SetupFamilyState.Configured,
                    setOf(CAPABILITY_SYNC_HEARTBEAT_V1),
                )
            },
        )
        rig.port.heartbeatJitterOverride = HeartbeatJitterSource { 0 }
        rig.awaitStartupRecovery()
        val probedAt = rig.clock.now

        val result = rig.port.probeServerAvailability(AvailabilityProbeReason.PullToRefresh)
            .getOrThrow() as FamilyServerAvailability.Available

        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(1)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(1)
        assertThat(setupProbeCalls).isEqualTo(1)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(0)
        assertThat(result.endpointOrigin).isEqualTo("https://192.168.1.20:8787")
        // The setup-status capability observation still arms the engine as
        // the accelerator (deadline scheduled even though no loop can run).
        assertThat(rig.port.heartbeatNextBeatAtMillis.value).isEqualTo(probedAt)
    }

    @Test
    fun joinedOldServerManualRefreshFallsBackToTheAnonymousTriple() = runTest {
        val rig = baseHeartbeatRig(advertised = true)
        rig.backend.heartbeatFailure = SyncHttpException(404, """{"detail":"Not Found"}""")
        pumpUntil { rig.port.heartbeatLoopActive }
        advanceHeartbeatTime(rig, 8_000)
        pumpUntil { !rig.port.heartbeatLoopActive }
        assertThat(rig.backend.heartbeatCalls).isEqualTo(1)

        // The gate is EndpointMissing: the manual refresh takes the anonymous
        // form and the (mistakenly advertising) setup-status can never re-arm.
        rig.backend.heartbeatFailure = null
        val result = rig.port.probeServerAvailability(AvailabilityProbeReason.PullToRefresh)

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.port.availability().first()).isInstanceOf(
            FamilyServerAvailability.Available::class.java,
        )
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(1)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(1)
        assertThat(setupProbeCalls).isEqualTo(1)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(1)
        assertThat(rig.port.heartbeatNextBeatAtMillis.value).isNull()
        assertThat(rig.port.heartbeatLoopActive).isFalse()

        // Old server still costs exactly one heartbeat per process.
        advanceHeartbeatTime(rig, 60 * 60_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(1)
        assertThat(rig.port.heartbeatLoopActive).isFalse()
    }

    @Test
    fun notAdvertisedManualRefreshPerformsTheArmingDiscoveryBeatThenEstablishes() = runTest {
        // Joined, foreground, endpoint pinned — the settings visit is the
        // FIRST capability event, so the manual refresh itself must run the
        // one-shot discovery beat.
        val rig = baseHeartbeatRig(advertised = true)
        val session = joinedSession("family-a")
        rig.backend.nextHeartbeat = quietHeartbeat(session)
        pumpUntil { rig.port.heartbeatLoopActive }

        val result = rig.port.probeServerAvailability(AvailabilityProbeReason.PullToRefresh)

        // The discovery beat answered 2xx: armed by probe evidence (the loop
        // keeps running afterwards), and the answer carries no remembered
        // metadata, so this same refresh completes first-establishment via
        // the anonymous triple — the documented user-initiated tail.
        assertThat(rig.backend.heartbeatCalls).isEqualTo(1)
        assertThat(result.isSuccess).isTrue()
        assertThat(rig.port.availability().first()).isInstanceOf(
            FamilyServerAvailability.Available::class.java,
        )
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(1)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(1)
        assertThat(setupProbeCalls).isEqualTo(1)
        assertThat(rig.port.heartbeatLoopActive).isTrue()
        // The beat's own bookkeeping overwrote the +8s permit debounce.
        assertThat(rig.port.heartbeatNextBeatAtMillis.value)
            .isEqualTo(rig.clock.now + 60_000)

        // Steady state reached: the loop beats on cadence, and the NEXT
        // manual refresh is a single authenticated beat with zero anonymous
        // calls (metadata now remembered from Available).
        advanceHeartbeatTime(rig, 60_000)
        pumpUntil { rig.backend.heartbeatCalls == 2 }
        val second = rig.port.probeServerAvailability(AvailabilityProbeReason.PullToRefresh)
        assertThat(second.isSuccess).isTrue()
        assertThat(rig.backend.heartbeatCalls).isEqualTo(3)
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(1)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(1)
        assertThat(setupProbeCalls).isEqualTo(1)
        assertThat(rig.port.availability().first()).isInstanceOf(
            FamilyServerAvailability.Available::class.java,
        )
    }

    @Test
    fun notAdvertisedManualRefreshWith404DiscoveryFallsBackWithinTheSameRefresh() = runTest {
        val rig = baseHeartbeatRig(advertised = false)
        rig.backend.heartbeatFailure = SyncHttpException(404, """{"detail":"Not Found"}""")
        pumpUntil { rig.port.heartbeatLoopActive }

        val result = rig.port.probeServerAvailability(AvailabilityProbeReason.PullToRefresh)

        // The manual discovery beat hit 404: the engine permanently disabled
        // itself (exactly one request), the parked loop is retired, and this
        // same user-initiated refresh completes on the anonymous triple —
        // which cannot re-arm the 404 gate.
        assertThat(rig.backend.heartbeatCalls).isEqualTo(1)
        assertThat(result.isSuccess).isTrue()
        pumpUntil { !rig.port.heartbeatLoopActive }
        assertThat(rig.port.availability().first()).isInstanceOf(
            FamilyServerAvailability.Available::class.java,
        )
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(1)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(1)
        assertThat(setupProbeCalls).isEqualTo(1)
        assertThat(rig.port.heartbeatNextBeatAtMillis.value).isNull()
        assertThat(rig.port.lastFailureKind().first()).isNull()

        advanceHeartbeatTime(rig, 60 * 60_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(1)
        assertThat(rig.port.heartbeatLoopActive).isFalse()
    }

    @Test
    fun manualHeartbeatRefreshSurfacesTrustChangedExactlyLikeTheAnonymousPath() = runTest {
        val rig = heartbeatRig()
        val established = rig.port.availability().first()
            as FamilyServerAvailability.Available
        val anonymousHealthBefore = rig.backend.anonymousHealthCalls
        val anonymousReadyBefore = rig.backend.anonymousReadyCalls
        rig.backend.heartbeatFailure = SpkiPinMismatchException()

        val result = rig.port.probeServerAvailability(AvailabilityProbeReason.PullToRefresh)
            .getOrThrow() as FamilyServerAvailability.Unavailable

        // Same classification, hysteresis math, and StateFlow the anonymous
        // probe uses — the settings recovery flow sees the identical state.
        assertThat(result.reason).isEqualTo(FamilyServerUnavailableReason.TrustChanged)
        assertThat(result.consecutiveFailures).isEqualTo(1)
        assertThat(result.lastHealthyAtMillis).isEqualTo(established.lastHealthyAtMillis)
        assertThat(result.nextProbeAtMillis).isEqualTo(rig.clock.now + 30_000)
        assertThat(rig.port.availability().first()).isEqualTo(result)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(anonymousHealthBefore)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(anonymousReadyBefore)
        assertThat(setupProbeCalls).isEqualTo(1)
    }

    @Test
    fun manualHeartbeatRefreshRoutesTerminalDeviceRemovalThroughTheExistingMapping() = runTest {
        val removalGate = TestRemovedDeviceLocalClearGate()
        val rig = heartbeatRig(removedDeviceLocalClearGate = removalGate)
        val availabilityBefore = rig.port.availability().first()
        val anonymousHealthBefore = rig.backend.anonymousHealthCalls
        val anonymousReadyBefore = rig.backend.anonymousReadyCalls
        rig.backend.heartbeatFailure = RemoteDeviceRemovedException()

        val result = rig.port.probeServerAvailability(AvailabilityProbeReason.PullToRefresh)

        // Not swallowed: the SAME handling as sync() runs the durable terminal
        // clear and flips the session to unjoined/Disabled.
        assertThat(result.isSuccess).isTrue()
        pumpUntil { rig.preferences.current().isJoined == false }
        pumpUntil { rig.port.status().first() == SyncStatus.Disabled }
        assertThat(removalGate.calls).isAtLeast(1)
        // And the terminal is not an availability failure.
        assertThat(rig.port.availability().first()).isEqualTo(availabilityBefore)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(anonymousHealthBefore)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(anonymousReadyBefore)
    }

    @Test
    fun manualHeartbeatRefreshLeavesAvailabilityUntouchedOnNotAnswered() = runTest {
        val rig = heartbeatRig()
        val established = rig.port.availability().first()
        val anonymousHealthBefore = rig.backend.anonymousHealthCalls
        val anonymousReadyBefore = rig.backend.anonymousReadyCalls
        rig.backend.heartbeatFailure = SyncHttpException(429, """{"detail":"throttled"}""")

        val result = rig.port.probeServerAvailability(AvailabilityProbeReason.PullToRefresh)

        // A beat that failed without an availability-facing classification
        // leaves the shared state untouched (no transient Checking pollution,
        // no degradation) and never falls back to the anonymous triple.
        assertThat(result.getOrThrow()).isEqualTo(established)
        assertThat(rig.port.availability().first()).isEqualTo(established)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(anonymousHealthBefore)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(anonymousReadyBefore)
        assertThat(setupProbeCalls).isEqualTo(1)
    }

    // --- fixtures ----------------------------------------------------------------

    /**
     * A joined rig whose availability probe advertises (or not)
     * `sync_heartbeat_v1` — the capability accelerator under test. Constructed
     * UNJOINED so the port's construction-time session collector cannot launch
     * the residency-driven discovery loop onto the real IO scope before the
     * test-scope overrides exist; joining below is the first residency event
     * and launches the loop deterministically on [backgroundScope]. The
     * directory snapshot is seeded to match the recording backend's handshake
     * so no round refetches it and the quiet heartbeat stays quiet regardless
     * of round timing.
     *
     * Ticket 05: the establishment probe below runs while JOINED, so it takes
     * the manual-heartbeat form under test — one discovery beat (arming the
     * engine by probe evidence) plus, because the answer carries no remembered
     * metadata yet, the anonymous first-establishment triple. Every
     * [RecordingSyncBackend.heartbeatCalls] baseline in this file therefore
     * starts at 1.
     */
    private suspend fun TestScope.heartbeatRig(
        advertised: Boolean = true,
        removedDeviceLocalClearGate: RemovedDeviceLocalClearGate =
            NoOpRemovedDeviceLocalClearGate(),
    ): SyncRig {
        val rig = baseHeartbeatRig(
            advertised = advertised,
            removedDeviceLocalClearGate = removedDeviceLocalClearGate,
        )
        // Capability accelerator (ticket 05 form): the joined refresh fires
        // the one-shot discovery beat first; the anonymous setup-status
        // observation below is the redundant second signal and the
        // first-establishment authority.
        val result = rig.port.probeServerAvailability(AvailabilityProbeReason.PullToRefresh)
        assertThat(result.isSuccess).isTrue()
        pumpUntil { rig.port.availability().first() is FamilyServerAvailability.Available }
        pumpUntil { rig.port.heartbeatLoopActive }
        return rig
    }

    /**
     * A joined rig that NEVER opens the network settings: joining is the only
     * residency event, so the loop launches with the gate still NotAdvertised
     * and arming can only come from the loop's own one-shot discovery beat.
     * [advertised] only shapes what a LATE settings probe would observe.
     */
    private suspend fun TestScope.discoveryRig(advertised: Boolean = true): SyncRig {
        val rig = baseHeartbeatRig(advertised = advertised)
        pumpUntil { rig.port.heartbeatLoopActive }
        return rig
    }

    /** Shared construction of [heartbeatRig] / [discoveryRig] (unjoined → join). */
    private suspend fun TestScope.baseHeartbeatRig(
        advertised: Boolean,
        removedDeviceLocalClearGate: RemovedDeviceLocalClearGate =
            NoOpRemovedDeviceLocalClearGate(),
    ): SyncRig {
        val setupProbe = SetupProbe { draft, trusted ->
            setupProbeCalls++
            SetupProbeResult.Ready(
                endpoint = trusted ?: TrustedEndpointProfile.systemPki(draft),
                familyState = SetupFamilyState.Configured,
                capabilities = if (advertised) {
                    setOf(CAPABILITY_SYNC_HEARTBEAT_V1)
                } else {
                    emptySet()
                },
            )
        }
        val rig = SyncRig(
            // familyId blank: joined-shaped (pinned baseUrl) but not joined,
            // so no loop can launch during construction.
            session = joinedSession("family-a").copy(familyId = ""),
            setupProbe = setupProbe,
            removedDeviceLocalClearGate = removedDeviceLocalClearGate,
        )
        rig.port.heartbeatLoopScopeOverride = backgroundScope
        rig.port.heartbeatJitterOverride = HeartbeatJitterSource { 0 }
        rig.awaitStartupRecovery()

        // Joining is the first residency event: the heartbeat loop launches
        // on the test scope from here on (gate still NotAdvertised — the
        // loop's discovery beat is what decides when settings never open).
        rig.preferences.saveSession(joinedSession("family-a"))
        // The join wiped the seeded directory; re-seed it to the handshake's
        // generation so the quiet heartbeat stays quiet.
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
        return rig
    }

    /**
     * Arms the cross-beat fuse: every heartbeat-triggered round fails at the
     * handshake with zero durable progress (HouseholdStateChanged is an
     * incomplete foreground cycle), so the cluster rides the zero-progress
     * budget, stops after exactly 3 rounds, and suppresses the scripted
     * signal. Leaves `handshakeFailure`/`nextHeartbeat` scripted for whatever
     * the caller does next.
     */
    private suspend fun TestScope.armZeroProgressFuse(
        rig: SyncRig,
        session: SyncSession,
    ) {
        rig.backend.handshakeFailure = SyncHttpException(409, """{"detail":"conflict"}""")
        rig.backend.nextHeartbeat = needsSyncHeartbeat(session)
        advanceHeartbeatTime(rig, 8_000)
        pumpUntil { rig.port.status().first() == SyncStatus.Error }
        assertThat(rig.backend.handshakeCalls).isEqualTo(3)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(2)
        assertThat(rig.backend.pullCount).isEqualTo(0)
    }

    /** Quiet three keys: identical to the rig's session snapshot → NoAction. */
    private fun quietHeartbeat(session: SyncSession) = SyncHeartbeat(
        generation = session.pullGeneration,
        headRev = session.pullCursor,
        directoryGeneration = "directory-test",
    )

    private fun needsSyncHeartbeat(session: SyncSession) =
        quietHeartbeat(session).copy(headRev = session.pullCursor + 1)

    /**
     * Non-suspending wait: [Thread.sleep] never yields the coroutine back to
     * the runTest driver (so pending loop delays never auto-fire), while
     * [runCurrent] drains only currently-due scheduler tasks. Real-IO port
     * collectors (kick consumer, session watcher, sync rounds) progress
     * during the sleeps.
     */
    private suspend fun TestScope.pumpUntil(
        timeoutMillis: Long = 10_000,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            // Settle real-IO work first, then drain due scheduler tasks, and
            // only then evaluate: a freshly launched loop coroutine has
            // always run to its first suspension by the time we look.
            Thread.sleep(10)
            runCurrent()
            if (condition()) return
            check(System.nanoTime() < deadline) { "heartbeat loop condition not met in time" }
        }
    }

    /** Moves the port clock and the virtual scheduler in lockstep. */
    private fun TestScope.advanceHeartbeatTime(rig: SyncRig, millis: Long) {
        rig.clock.now += millis
        testScheduler.advanceTimeBy(millis)
        runCurrent()
    }

    /** Settles any queued rounds, then requires a stable Idle status. */
    private suspend fun TestScope.settleIdle(rig: SyncRig) {
        pumpUntil { rig.port.status().first() == SyncStatus.Idle }
        withContext(Dispatchers.IO) { delay(150) }
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }
}
