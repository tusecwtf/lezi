package com.lezi.babylog.sync.heartbeat

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.MutablePolicyClock
import com.lezi.babylog.sync.availability.FamilyServerAvailability
import com.lezi.babylog.sync.availability.FamilyServerAvailabilityPolicy
import com.lezi.babylog.sync.availability.FamilyServerUnavailableReason
import com.lezi.babylog.sync.backend.AtomicBundleDraft
import com.lezi.babylog.sync.backend.AuthenticatedSyncHandshake
import com.lezi.babylog.sync.backend.BundleCommitResult
import com.lezi.babylog.sync.backend.BundleStageStatus
import com.lezi.babylog.sync.backend.DisplayNameUpdateResult
import com.lezi.babylog.sync.backend.FamilyMemberDirectorySnapshot
import com.lezi.babylog.sync.backend.PullPageRequest
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.ReauthRequiredException
import com.lezi.babylog.sync.backend.RemoteDeviceRemovedException
import com.lezi.babylog.sync.backend.SessionBootstrapResult
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncHeartbeat
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind
import com.lezi.babylog.sync.backend.deadline.FamilyHttpException
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SpkiPinMismatchException
import com.lezi.babylog.sync.session.SyncSession
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Engine-level contract of the 0.4.8 unified probe engine (ticket 03): fake
 * clock + fake backend, asserting observable beats, availability transitions
 * and cadence deadlines — never coroutine internals.
 */
class SyncHeartbeatEngineTest {
    @Test
    fun noChangeBeatsBackOffOnTheDoublingScheduleWithJitter() = runTest {
        val rig = rig()
        rig.engine.onServerCapabilityAdvertised()

        val first = rig.answered(quiet())
        assertThat(first.verdict).isEqualTo(HeartbeatVerdict.NoAction)
        assertThat(first.availabilityUpdate).isNull()
        assertThat(first.nextBeatAtMillis).isEqualTo(66_000)
        rig.clock.now = 66_000

        val second = rig.answered(quiet())
        assertThat(second.nextBeatAtMillis).isEqualTo(66_000 + 132_000)
        rig.clock.now = 198_000

        val third = rig.answered(quiet())
        assertThat(third.nextBeatAtMillis).isEqualTo(198_000 + 264_000)
        rig.clock.now = 462_000

        val fourth = rig.answered(quiet())
        assertThat(fourth.nextBeatAtMillis).isEqualTo(462_000 + 330_000)
        assertThat(rig.engine.nextBeatAtMillis.value).isEqualTo(792_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(4)
    }

    @Test
    fun healthyJitterIsClampedToTwentyPercentBoundsAroundTheSchedule() = runTest {
        // Out-of-contract jitter sources are clamped so the [0.8x, 1.2x]
        // invariant holds regardless of the injected source.
        val high = rig(jitter = fixedJitter(1_000_000))
        high.engine.onServerCapabilityAdvertised()
        assertThat(high.answered(quiet()).nextBeatAtMillis).isEqualTo(0 + 72_000)

        val low = rig(jitter = fixedJitter(-1_000_000))
        low.engine.onServerCapabilityAdvertised()
        assertThat(low.answered(quiet()).nextBeatAtMillis).isEqualTo(0 + 48_000)

        // The cap applies to the schedule; jitter still surrounds the rung.
        val capped = rig(jitter = fractionJitter(0.20))
        capped.engine.onServerCapabilityAdvertised()
        capped.answered(quiet())
        capped.clock.now = 72_000
        capped.answered(quiet())
        capped.clock.now = 216_000
        assertThat(capped.answered(quiet()).nextBeatAtMillis).isEqualTo(216_000 + 288_000)
    }

    @Test
    fun singleTransportFailureDegradesImmediatelyWithoutHysteresis() = runTest {
        val rig = rig(availability = availableAt(lastHealthyAtMillis = 1_000))
        rig.engine.onServerCapabilityAdvertised()
        rig.clock.now = 61_000
        rig.backend.failure = IOException("connection reset")

        val beat = rig.beat() as SyncHeartbeatBeat.Degraded

        assertThat(beat.unavailable.reason).isEqualTo(FamilyServerUnavailableReason.Unreachable)
        assertThat(beat.unavailable.consecutiveFailures).isEqualTo(1)
        assertThat(beat.unavailable.lastHealthyAtMillis).isEqualTo(1_000)
        assertThat(beat.unavailable.nextProbeAtMillis).isEqualTo(91_000)
        assertThat(rig.engine.nextBeatAtMillis.value).isEqualTo(91_000)
        assertThat(rig.engine.gate.value).isEqualTo(HeartbeatGate.Active)
    }

    @Test
    fun degradedCadenceClimbsTheExistingThirtyOneTwentySixHundredLadder() = runTest {
        val rig = rig(availability = availableAt(lastHealthyAtMillis = 1_000))
        rig.engine.onServerCapabilityAdvertised()
        rig.backend.failure = IOException("connection reset")
        rig.clock.now = 61_000

        val first = rig.beat() as SyncHeartbeatBeat.Degraded
        assertThat(first.unavailable.nextProbeAtMillis).isEqualTo(61_000 + 30_000)

        rig.availability = first.unavailable
        rig.clock.now = 91_000
        val second = rig.beat() as SyncHeartbeatBeat.Degraded
        assertThat(second.unavailable.consecutiveFailures).isEqualTo(2)
        assertThat(second.unavailable.nextProbeAtMillis).isEqualTo(91_000 + 120_000)

        rig.availability = second.unavailable
        rig.clock.now = 211_000
        val third = rig.beat() as SyncHeartbeatBeat.Degraded
        assertThat(third.unavailable.consecutiveFailures).isEqualTo(3)
        assertThat(third.unavailable.nextProbeAtMillis).isEqualTo(211_000 + 600_000)
        assertThat(rig.engine.nextBeatAtMillis.value).isEqualTo(811_000)
    }

    @Test
    fun successRehabilitatesUnavailableWithRememberedMetadataAndBaselineCadence() = runTest {
        val rig = rig(availability = availableAt(lastHealthyAtMillis = 1_000))
        rig.engine.onServerCapabilityAdvertised()
        rig.clock.now = 61_000
        rig.backend.failure = IOException("connection reset")
        val degraded = rig.beat() as SyncHeartbeatBeat.Degraded

        rig.availability = degraded.unavailable
        rig.clock.now = 91_000
        rig.backend.failure = null
        val beat = rig.answered(quiet()) as SyncHeartbeatBeat.Answered

        assertThat(beat.verdict).isEqualTo(HeartbeatVerdict.NoAction)
        val rehabilitated = beat.availabilityUpdate as FamilyServerAvailability.Available
        assertThat(rehabilitated.endpointOrigin).isEqualTo("https://192.168.1.20:8787")
        assertThat(rehabilitated.serverVersion).isEqualTo("0.4.8")
        assertThat(rehabilitated.familyState).isEqualTo(SetupFamilyState.Configured)
        assertThat(rehabilitated.lastHealthyAtMillis).isEqualTo(91_000)
        assertThat(rehabilitated.leaseUntilMillis).isEqualTo(91_000 + 30_000)
        // Rehabilitation leaves the degraded ladder and restarts at the baseline.
        assertThat(beat.nextBeatAtMillis).isEqualTo(91_000 + 66_000)
    }

    @Test
    fun rehabilitationWithoutRememberedMetadataLeavesAvailabilityUntouched() = runTest {
        val rig = rig(
            availability = FamilyServerAvailability.Unavailable(
                reason = FamilyServerUnavailableReason.Unreachable,
                lastHealthyAtMillis = null,
                nextProbeAtMillis = 50_000,
                consecutiveFailures = 2,
            ),
        )
        rig.engine.onServerCapabilityAdvertised()
        rig.clock.now = 50_000

        val beat = rig.answered(quiet())

        assertThat(beat.verdict).isEqualTo(HeartbeatVerdict.NoAction)
        assertThat(beat.availabilityUpdate).isNull()
        assertThat(beat.nextBeatAtMillis).isEqualTo(50_000 + 66_000)
    }

    @Test
    fun rehabilitationRefreshesAnExistingAvailableInPlace() = runTest {
        val rig = rig(availability = availableAt(lastHealthyAtMillis = 1_000))
        rig.engine.onServerCapabilityAdvertised()
        rig.clock.now = 61_000

        val beat = rig.answered(quiet())

        val refreshed = beat.availabilityUpdate as FamilyServerAvailability.Available
        assertThat(refreshed.lastHealthyAtMillis).isEqualTo(61_000)
        assertThat(refreshed.leaseUntilMillis).isEqualTo(61_000 + 30_000)
        assertThat(refreshed.serverVersion).isEqualTo("0.4.8")
        assertThat(refreshed.endpointOrigin).isEqualTo("https://192.168.1.20:8787")
    }

    @Test
    fun verdictAllKeysEqualIsNoAction() {
        assertThat(
            heartbeatVerdict(
                heartbeat = SyncHeartbeat("gen-a", 7, "dir-1"),
                snapshot = HeartbeatSessionSnapshot(7, "gen-a", "dir-1"),
            ),
        ).isEqualTo(HeartbeatVerdict.NoAction)
    }

    @Test
    fun verdictHeadRevForwardTriggersSync() {
        assertThat(
            heartbeatVerdict(
                heartbeat = SyncHeartbeat("gen-a", 8, "dir-1"),
                snapshot = HeartbeatSessionSnapshot(7, "gen-a", "dir-1"),
            ),
        ).isEqualTo(HeartbeatVerdict.NeedsSync)
    }

    @Test
    fun verdictHeadRevRegressionTriggersSync() {
        assertThat(
            heartbeatVerdict(
                heartbeat = SyncHeartbeat("gen-a", 3, "dir-1"),
                snapshot = HeartbeatSessionSnapshot(7, "gen-a", "dir-1"),
            ),
        ).isEqualTo(HeartbeatVerdict.NeedsSync)
    }

    @Test
    fun verdictDirectoryGenerationDriftTriggersSync() {
        assertThat(
            heartbeatVerdict(
                heartbeat = SyncHeartbeat("gen-a", 7, "dir-2"),
                snapshot = HeartbeatSessionSnapshot(7, "gen-a", "dir-1"),
            ),
        ).isEqualTo(HeartbeatVerdict.NeedsSync)
    }

    @Test
    fun verdictGenerationDriftTriggersSync() {
        assertThat(
            heartbeatVerdict(
                heartbeat = SyncHeartbeat("gen-b", 7, "dir-1"),
                snapshot = HeartbeatSessionSnapshot(7, "gen-a", "dir-1"),
            ),
        ).isEqualTo(HeartbeatVerdict.NeedsSync)
    }

    @Test
    fun engineWithoutAdvertisedCapabilityNeverProbes() = runTest {
        val rig = rig()
        rig.engine.onLocalWriteCompleted()
        rig.engine.onForegroundReturned()

        val failure = runCatching { rig.beat() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(0)
        assertThat(rig.engine.nextBeatAtMillis.value).isNull()
        assertThat(rig.engine.gate.value).isEqualTo(HeartbeatGate.NotAdvertised)
    }

    @Test
    fun firstProbeNotFoundPermanentlyDisablesSilently() = runTest {
        val rig = rig(availability = availableAt(lastHealthyAtMillis = 1_000))
        rig.engine.onServerCapabilityAdvertised()
        rig.backend.failure = SyncHttpException(404, """{"detail":"Not Found"}""")

        assertThat(rig.beat()).isEqualTo(SyncHeartbeatBeat.EndpointMissing)
        assertThat(rig.engine.gate.value).isEqualTo(HeartbeatGate.EndpointMissing)
        assertThat(rig.engine.nextBeatAtMillis.value).isNull()
        // Capability-gate outcomes never feed availability as failures.
        assertThat(rig.availability).isEqualTo(availableAt(lastHealthyAtMillis = 1_000))

        // Permanent: a later capability broadcast cannot re-arm the engine.
        rig.engine.onServerCapabilityAdvertised()
        assertThat(rig.engine.gate.value).isEqualTo(HeartbeatGate.EndpointMissing)

        assertThat(rig.beat()).isEqualTo(SyncHeartbeatBeat.EndpointMissing)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(1)
    }

    @Test
    fun discoveryBeatAloneArmsTheGateWithoutAnyAdvertisement() = runTest {
        val rig = rig()
        rig.engine.allowDiscoveryBeat()

        // The permit alone schedules the +8s foreground first-beat debounce.
        assertThat(rig.engine.gate.value).isEqualTo(HeartbeatGate.DiscoveryPending)
        assertThat(rig.engine.nextBeatAtMillis.value).isEqualTo(0 + 8_000)

        // The permitted beat answers 2xx: armed from probe evidence alone.
        val beat = rig.answered(quiet()) as SyncHeartbeatBeat.Answered
        assertThat(beat.verdict).isEqualTo(HeartbeatVerdict.NoAction)
        assertThat(rig.engine.gate.value).isEqualTo(HeartbeatGate.Active)

        // Idempotent: repeated calls never re-permit after the beat armed.
        rig.engine.allowDiscoveryBeat()
        assertThat(rig.engine.gate.value).isEqualTo(HeartbeatGate.Active)
        assertThat(rig.engine.nextBeatAtMillis.value).isEqualTo(66_000)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(1)
    }

    @Test
    fun discoveryBeatNotFoundPermanentlyDisablesWithoutRePermission() = runTest {
        val rig = rig(availability = availableAt(lastHealthyAtMillis = 1_000))
        rig.engine.allowDiscoveryBeat()
        rig.backend.failure = SyncHttpException(404, """{"detail":"Not Found"}""")

        assertThat(rig.beat()).isEqualTo(SyncHeartbeatBeat.EndpointMissing)
        assertThat(rig.engine.gate.value).isEqualTo(HeartbeatGate.EndpointMissing)
        assertThat(rig.engine.nextBeatAtMillis.value).isNull()
        // Capability-gate outcomes never feed availability as failures.
        assertThat(rig.availability).isEqualTo(availableAt(lastHealthyAtMillis = 1_000))

        // Exactly one request per process vs an old server: neither a
        // re-permit nor a late setup-status advertisement can re-arm.
        rig.engine.allowDiscoveryBeat()
        rig.engine.onServerCapabilityAdvertised()
        assertThat(rig.engine.gate.value).isEqualTo(HeartbeatGate.EndpointMissing)
        assertThat(rig.beat()).isEqualTo(SyncHeartbeatBeat.EndpointMissing)
        assertThat(rig.backend.heartbeatCalls).isEqualTo(1)
    }

    @Test
    fun failedDiscoveryBeatReOpensDiscoveryOnTheFailedLadder() = runTest {
        val rig = rig(availability = availableAt(lastHealthyAtMillis = 1_000))
        rig.engine.allowDiscoveryBeat()
        rig.clock.now = 10_000
        rig.backend.failure = IOException("connection reset")

        val degraded = rig.beat() as SyncHeartbeatBeat.Degraded
        // No capability evidence either way: discovery re-opens, while the
        // failure still degrades availability on the existing ladder.
        assertThat(rig.engine.gate.value).isEqualTo(HeartbeatGate.NotAdvertised)
        assertThat(degraded.unavailable.reason).isEqualTo(FamilyServerUnavailableReason.Unreachable)
        assertThat(degraded.unavailable.consecutiveFailures).isEqualTo(1)
        assertThat(degraded.unavailable.nextProbeAtMillis).isEqualTo(10_000 + 30_000)
        assertThat(rig.engine.nextBeatAtMillis.value).isEqualTo(10_000 + 30_000)

        // The retry permit keeps the failed beat's published deadline instead
        // of restarting the +8s debounce over it.
        rig.engine.allowDiscoveryBeat()
        assertThat(rig.engine.gate.value).isEqualTo(HeartbeatGate.DiscoveryPending)
        assertThat(rig.engine.nextBeatAtMillis.value).isEqualTo(10_000 + 30_000)

        // The retry answers: armed at last, healthy cadence from here.
        rig.clock.now = 40_000
        rig.backend.failure = null
        val armed = rig.answered(quiet()) as SyncHeartbeatBeat.Answered
        assertThat(rig.engine.gate.value).isEqualTo(HeartbeatGate.Active)
        assertThat(armed.nextBeatAtMillis).isEqualTo(40_000 + 66_000)
    }

    @Test
    fun terminalDeviceRemovalPropagatesWithoutDegradingAvailability() = runTest {
        val rig = rig(availability = availableAt(lastHealthyAtMillis = 1_000))
        rig.engine.onServerCapabilityAdvertised()
        rig.backend.failure = RemoteDeviceRemovedException()

        val failure = runCatching { rig.beat() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(RemoteDeviceRemovedException::class.java)
        assertThat(rig.availability).isEqualTo(availableAt(lastHealthyAtMillis = 1_000))
        assertThat(rig.engine.nextBeatAtMillis.value).isEqualTo(0)
    }

    @Test
    fun reauthRequiredPropagatesWithoutDegradingAvailability() = runTest {
        val rig = rig(availability = availableAt(lastHealthyAtMillis = 1_000))
        rig.engine.onServerCapabilityAdvertised()
        rig.backend.failure = ReauthRequiredException()

        val failure = runCatching { rig.beat() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ReauthRequiredException::class.java)
        assertThat(rig.availability).isEqualTo(availableAt(lastHealthyAtMillis = 1_000))
    }

    @Test
    fun trustChangedSurfacesAsAvailabilityDegradation() = runTest {
        val rig = rig(availability = availableAt(lastHealthyAtMillis = 1_000))
        rig.engine.onServerCapabilityAdvertised()
        rig.clock.now = 10_000
        rig.backend.failure = SpkiPinMismatchException()

        val beat = rig.beat() as SyncHeartbeatBeat.Degraded

        assertThat(beat.unavailable.reason).isEqualTo(FamilyServerUnavailableReason.TrustChanged)
        assertThat(beat.unavailable.consecutiveFailures).isEqualTo(1)
        assertThat(rig.engine.nextBeatAtMillis.value).isEqualTo(10_000 + 30_000)
    }

    @Test
    fun transportFailureReasonsFollowTheExistingFeederClassification() = runTest {
        val maintenance = rig(availability = availableAt(1_000))
        maintenance.engine.onServerCapabilityAdvertised()
        maintenance.backend.failure = SyncHttpException(503, "maintenance")
        val maintenanceBeat = maintenance.beat() as SyncHeartbeatBeat.Degraded
        assertThat(maintenanceBeat.unavailable.reason)
            .isEqualTo(FamilyServerUnavailableReason.Maintenance)

        val timedOut = rig(availability = availableAt(1_000))
        timedOut.engine.onServerCapabilityAdvertised()
        timedOut.backend.failure = FamilyHttpException(FamilyHttpFailureKind.ResponseTimedOut)
        val timedOutBeat = timedOut.beat() as SyncHeartbeatBeat.Degraded
        assertThat(timedOutBeat.unavailable.reason)
            .isEqualTo(FamilyServerUnavailableReason.ResponseTimedOut)

        val throttled = rig(availability = availableAt(1_000))
        throttled.engine.onServerCapabilityAdvertised()
        throttled.backend.failure = SyncHttpException(429, "slow down")
        // The server answered; availability stays untouched and no failure kind
        // exists on the engine surface — but the cadence still backs off.
        assertThat(throttled.beat()).isInstanceOf(SyncHeartbeatBeat.NotAnswered::class.java)
        assertThat(throttled.engine.nextBeatAtMillis.value).isEqualTo(66_000)
        assertThat(throttled.availability).isEqualTo(availableAt(1_000))
    }

    @Test
    fun needsSyncVerdictResetsBackoffToBaseline() = runTest {
        val rig = rig()
        rig.engine.onServerCapabilityAdvertised()
        rig.answered(quiet())
        rig.clock.now = 66_000
        rig.answered(quiet())
        rig.clock.now = 198_000

        val changed = rig.answered(heartbeat(pullCursor = 9))

        assertThat(changed.verdict).isEqualTo(HeartbeatVerdict.NeedsSync)
        assertThat(changed.nextBeatAtMillis).isEqualTo(198_000 + 66_000)
    }

    @Test
    fun localWriteCompletedResetsCadenceWithoutAccumulatingMissedBeats() = runTest {
        val rig = rig()
        rig.engine.onServerCapabilityAdvertised()
        rig.answered(quiet())
        rig.clock.now = 66_000
        rig.answered(quiet())
        // Miss the deadline entirely, then write locally: the deadline is
        // recomputed from `now` at the baseline — never queued or accumulated.
        rig.clock.now = 500_000
        rig.engine.onLocalWriteCompleted()

        assertThat(rig.engine.nextBeatAtMillis.value).isEqualTo(500_000 + 66_000)
    }

    @Test
    fun foregroundReturnDebouncesFirstBeatByEightSeconds() = runTest {
        val rig = rig()
        rig.engine.onServerCapabilityAdvertised()
        rig.engine.onForegroundReturned()

        assertThat(rig.engine.nextBeatAtMillis.value).isEqualTo(0 + 8_000)
    }

    @Test
    fun foregroundReturnResetsBackoffBeforeTheDebouncedFirstBeat() = runTest {
        val rig = rig()
        rig.engine.onServerCapabilityAdvertised()
        rig.answered(quiet())
        rig.clock.now = 66_000
        rig.answered(quiet())
        rig.clock.now = 198_000

        rig.engine.onForegroundReturned()
        assertThat(rig.engine.nextBeatAtMillis.value).isEqualTo(198_000 + 8_000)

        rig.clock.now = 198_000 + 8_000
        val resumed = rig.answered(quiet())
        assertThat(resumed.nextBeatAtMillis).isEqualTo(198_000 + 8_000 + 66_000)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun localWriteCompletedWhileAProbeIsInFlightDoesNotBlockAndKeepsTheBaselineReset() = runTest {
        val rig = rig()
        rig.engine.onServerCapabilityAdvertised()
        rig.answered(quiet())
        rig.clock.now = 66_000
        rig.answered(quiet())
        rig.clock.now = 198_000
        // The cadence is backed off: the in-flight beat's own bookkeeping
        // would schedule the 240s rung if it ignored the reset.
        val gate = CompletableDeferred<Unit>()
        rig.backend.holdNextHeartbeat = gate
        var resumed: SyncHeartbeatBeat? = null
        launch { resumed = rig.beat() }
        runCurrent()
        assertThat(rig.backend.heartbeatCalls).isEqualTo(3)

        // Fires while the probe is parked inside the backend call: the hook
        // must return without waiting on the in-flight beat.
        rig.engine.onLocalWriteCompleted()
        assertThat(rig.engine.nextBeatAtMillis.value).isEqualTo(198_000 + 66_000)

        rig.backend.enqueue(quiet())
        gate.complete(Unit)
        runCurrent()
        // The beat's post-answer bookkeeping must not resurrect the backed-off
        // schedule over the hook's baseline reset.
        val answered = resumed as SyncHeartbeatBeat.Answered
        assertThat(answered.verdict).isEqualTo(HeartbeatVerdict.NoAction)
        assertThat(answered.nextBeatAtMillis).isEqualTo(198_000 + 66_000)
        assertThat(rig.engine.nextBeatAtMillis.value).isEqualTo(198_000 + 66_000)
        // The counter went through the reset: the next no-change rung is 120s,
        // not the 240s rung.
        rig.clock.now = 264_000
        assertThat(rig.answered(quiet()).nextBeatAtMillis).isEqualTo(264_000 + 132_000)
    }

    @Test
    fun inFlightBeatBookkeepingCannotOverwriteAConcurrentLocalWriteReset() = runTest {
        val jitter = HookRaceJitter()
        val rig = rig(jitter = jitter)
        rig.engine.onServerCapabilityAdvertised()
        rig.answered(quiet())
        rig.clock.now = 66_000
        rig.answered(quiet())
        rig.clock.now = 198_000

        // Two no-change beats leave the cadence backed off; the next no-change
        // schedule would be the 240s rung (+10% jitter). The hook is fired
        // from a helper thread exactly inside the beat's post-answer
        // bookkeeping — the window where a beat not serialized under the same
        // lock as the hooks loses the reset.
        jitter.arm { rig.engine.onLocalWriteCompleted() }
        rig.answered(quiet())

        assertThat(jitter.awaitHook()).isTrue()
        // The reset wins: the baseline 66s, not the beat's resurrected 264s.
        assertThat(rig.engine.nextBeatAtMillis.value).isEqualTo(198_000 + 66_000)
        // The counter went through the reset: the next no-change rung is the
        // baseline 60s, not the 240s rung.
        rig.clock.now = 264_000
        assertThat(rig.answered(quiet()).nextBeatAtMillis).isEqualTo(264_000 + 66_000)
    }

    @Test
    fun degradedRetryLadderThirtyOneTwentySixHundredStaysUntouched() {
        // 0.5 ticket 07 explicitly forbids touching the failure-recovery
        // ladder when relaxing the healthy cadence (no accidental wholesale
        // constant rewrite).
        assertThat(FamilyServerAvailabilityPolicy.retryDelayMillis(0)).isEqualTo(30_000)
        assertThat(FamilyServerAvailabilityPolicy.retryDelayMillis(1)).isEqualTo(30_000)
        assertThat(FamilyServerAvailabilityPolicy.retryDelayMillis(2)).isEqualTo(120_000)
        assertThat(FamilyServerAvailabilityPolicy.retryDelayMillis(3)).isEqualTo(600_000)
        assertThat(FamilyServerAvailabilityPolicy.retryDelayMillis(99)).isEqualTo(600_000)
    }

    @Test
    fun ticketSevenConstantsAndTipSkipWindowShareTheBackoffCap() {
        // 0.5 ticket 07: healthy cadence 60s/300s; ±20% jitter and the +8s
        // foreground debounce stay; the tip-skip freshness window (ticket 04)
        // MUST keep sharing MAX_NO_CHANGE_INTERVAL_MILLIS so the two can
        // never drift apart.
        assertThat(SyncHeartbeatPolicy.BASELINE_INTERVAL_MILLIS).isEqualTo(60_000)
        assertThat(SyncHeartbeatPolicy.MAX_NO_CHANGE_INTERVAL_MILLIS).isEqualTo(300_000)
        assertThat(SyncHeartbeatPolicy.JITTER_FRACTION).isEqualTo(0.20)
        assertThat(SyncHeartbeatPolicy.FOREGROUND_FIRST_BEAT_DEBOUNCE_MILLIS).isEqualTo(8_000)
        // The doubling schedule walks 60s → 120s → 240s and binds at the cap.
        assertThat(SyncHeartbeatPolicy.noChangeIntervalMillis(0)).isEqualTo(60_000)
        assertThat(SyncHeartbeatPolicy.noChangeIntervalMillis(1)).isEqualTo(120_000)
        assertThat(SyncHeartbeatPolicy.noChangeIntervalMillis(2)).isEqualTo(240_000)
        assertThat(SyncHeartbeatPolicy.noChangeIntervalMillis(3)).isEqualTo(300_000)
        assertThat(SyncHeartbeatPolicy.noChangeIntervalMillis(20)).isEqualTo(300_000)
    }

    // --- fixtures ----------------------------------------------------------------

    private class HeartbeatFakeBackend : SyncBackend {
        var heartbeatCalls = 0
        var failure: Throwable? = null

        /**
         * When set, the next heartbeat parks on it before answering — the
         * engine's beat is then provably mid-flight while the test acts.
         */
        var holdNextHeartbeat: CompletableDeferred<Unit>? = null
        private val responses = ArrayDeque<SyncHeartbeat>()

        fun enqueue(heartbeat: SyncHeartbeat) {
            responses += heartbeat
        }

        override suspend fun heartbeat(session: SyncSession): SyncHeartbeat {
            heartbeatCalls += 1
            holdNextHeartbeat?.let { gate ->
                holdNextHeartbeat = null
                gate.await()
            }
            failure?.let { throw it }
            return responses.removeFirstOrNull() ?: error("no scripted heartbeat response")
        }

        override suspend fun create(
            baseUrl: String,
            deviceId: String,
            displayName: String?,
            createRequestId: String,
            bootstrapSecret: String?,
            familyName: String?,
        ): SessionBootstrapResult = error("unused in heartbeat engine tests")

        override suspend fun pull(session: SyncSession, page: PullPageRequest): PullResult =
            error("unused in heartbeat engine tests")

        override suspend fun authenticatedHandshake(
            session: SyncSession,
        ): AuthenticatedSyncHandshake = error("unused in heartbeat engine tests")

        override suspend fun memberDirectory(
            session: SyncSession,
        ): FamilyMemberDirectorySnapshot = error("unused in heartbeat engine tests")

        override suspend fun updateMyDisplayName(
            session: SyncSession,
            displayName: String,
        ): DisplayNameUpdateResult = error("unused in heartbeat engine tests")

        override suspend fun renameFamily(session: SyncSession, familyName: String?) =
            error("unused in heartbeat engine tests")

        override suspend fun leave(session: SyncSession) =
            error("unused in heartbeat engine tests")

        override suspend fun removeMember(session: SyncSession, membershipId: String) =
            error("unused in heartbeat engine tests")

        override suspend fun deleteFamily(
            session: SyncSession,
            familyName: String,
            rootPassword: String,
        ) = error("unused in heartbeat engine tests")

        override suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray =
            error("unused in heartbeat engine tests")

        override suspend fun stageBundle(
            session: SyncSession,
            draft: AtomicBundleDraft,
        ): BundleStageStatus = error("unused in heartbeat engine tests")

        override suspend fun commitBundle(
            session: SyncSession,
            bundleId: String,
        ): BundleCommitResult = error("unused in heartbeat engine tests")
    }

    private class Rig(
        val backend: HeartbeatFakeBackend,
        val clock: MutablePolicyClock,
        jitter: HeartbeatJitterSource,
        var availability: FamilyServerAvailability,
    ) {
        val engine = SyncHeartbeatEngine(
            backend = backend,
            clock = clock,
            jitter = jitter,
            availabilitySnapshot = { availability },
        )

        fun session(
            pullCursor: Long = 7,
            pullGeneration: String = "gen-a",
        ) = SyncSession(
            familyId = "family-a",
            accessToken = "token",
            deviceId = "device-a",
            role = FamilyRole.Owner,
            pullCursor = pullCursor,
            pullGeneration = pullGeneration,
            membershipId = "membership-a",
            serverHost = "192.168.1.20",
            serverPort = 8787,
        )

        suspend fun beat(): SyncHeartbeatBeat = engine.beat(session(), snapshot())

        private fun snapshot() = HeartbeatSessionSnapshot(
            pullCursor = 7,
            pullGeneration = "gen-a",
            cachedDirectoryGeneration = "dir-1",
        )

        suspend fun answered(heartbeat: SyncHeartbeat): SyncHeartbeatBeat.Answered {
            backend.enqueue(heartbeat)
            return beat() as SyncHeartbeatBeat.Answered
        }
    }

    private fun rig(
        jitter: HeartbeatJitterSource = fractionJitter(0.10),
        availability: FamilyServerAvailability = FamilyServerAvailability.Disabled,
    ) = Rig(
        backend = HeartbeatFakeBackend(),
        clock = MutablePolicyClock(now = 0),
        jitter = jitter,
        availability = availability,
    )

    private fun quiet() = SyncHeartbeat("gen-a", 7, "dir-1")
    private fun heartbeat(pullCursor: Long) = SyncHeartbeat("gen-a", pullCursor, "dir-1")

    private fun availableAt(
        lastHealthyAtMillis: Long,
        origin: String = "https://192.168.1.20:8787",
    ) = FamilyServerAvailability.Available(
        endpointOrigin = origin,
        serverVersion = "0.4.8",
        lastHealthyAtMillis = lastHealthyAtMillis,
        leaseUntilMillis = lastHealthyAtMillis + 30_000,
        familyState = SetupFamilyState.Configured,
    )

    private fun fixedJitter(millis: Long) = HeartbeatJitterSource { millis }

    private fun fractionJitter(fraction: Double) = HeartbeatJitterSource { interval ->
        (interval * fraction).toLong()
    }

    /**
     * Deterministic window injector for the hook-vs-beat bookkeeping race: the
     * first jitter call after [arm] fires the hook on a helper thread exactly
     * inside the beat's post-answer scheduling. Under a discipline where the
     * beat's bookkeeping does not share the hooks' lock, the hook runs
     * uncontended mid-window and the beat then clobbers its reset; under the
     * stateLock discipline the hook waits for the beat's critical section to
     * close and then applies its reset last.
     */
    private class HookRaceJitter : HeartbeatJitterSource {
        private val hookStarted = CountDownLatch(1)
        private val hookDone = CountDownLatch(1)

        @Volatile
        private var fire: (() -> Unit)? = null

        fun arm(fire: () -> Unit) {
            this.fire = fire
        }

        fun awaitHook(): Boolean = hookDone.await(5, TimeUnit.SECONDS)

        override fun jitterMillis(scheduledIntervalMillis: Long): Long {
            val fire = this.fire
            if (fire != null) {
                this.fire = null
                thread(name = "lezi-heartbeat-local-write-hook") {
                    hookStarted.countDown()
                    fire()
                    hookDone.countDown()
                }
                // Give the hook the chance to land while the window is open.
                hookStarted.await(5, TimeUnit.SECONDS)
                Thread.sleep(100)
            }
            return (scheduledIntervalMillis * 0.10).toLong()
        }
    }
}
