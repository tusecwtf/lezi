package com.lezi.babylog.sync.heartbeat

import com.lezi.babylog.sync.availability.FamilyServerAvailability
import com.lezi.babylog.sync.availability.FamilyServerAvailabilityPolicy
import com.lezi.babylog.sync.availability.FamilyServerUnavailableReason
import com.lezi.babylog.sync.availability.causeChainContains
import com.lezi.babylog.sync.availability.isAvailabilityTransportFailure
import com.lezi.babylog.sync.availability.toAvailabilityUnavailableReason
import com.lezi.babylog.sync.backend.ClientUpdateRequiredException
import com.lezi.babylog.sync.backend.ReauthRequiredException
import com.lezi.babylog.sync.backend.RemoteDeviceRemovedException
import com.lezi.babylog.sync.backend.RemoteFamilyDeletedException
import com.lezi.babylog.sync.backend.RemoteMembershipDeletedException
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncHeartbeat
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.SpkiPinMismatchException
import com.lezi.babylog.sync.session.normalizeHttpsOrigin
import kotlin.math.floor
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val FamilyServerAvailability.lastHealthyAtOrNull: Long?
    get() = when (this) {
        is FamilyServerAvailability.Available -> lastHealthyAtMillis
        is FamilyServerAvailability.Checking -> lastHealthyAtMillis
        is FamilyServerAvailability.Unavailable -> lastHealthyAtMillis
        FamilyServerAvailability.Disabled -> null
    }

private fun SyncSession.normalizedOriginOrNull(): String? =
    runCatching { normalizeHttpsOrigin(baseUrl) }.getOrNull()

/**
 * 0.5 foreground heartbeat probe policy (wire §1.5; 0.4.8 was 30s/120s): the
 * 60s healthy baseline, the ±20%-jittered no-change backoff capped at 300s,
 * the +8s foreground first-beat debounce, and the doubling schedule math. The
 * degraded cadence deliberately has no numbers here — it reuses the existing
 * [FamilyServerAvailabilityPolicy] retry ladder (30s/120s/600s) so no second
 * timer family exists. [MAX_NO_CHANGE_INTERVAL_MILLIS] is also the tip-skip
 * freshness window (0.5 ticket 04) — the two MUST keep sharing this constant.
 */
object SyncHeartbeatPolicy {
    const val BASELINE_INTERVAL_MILLIS = 60_000L
    const val MAX_NO_CHANGE_INTERVAL_MILLIS = 300_000L
    const val FOREGROUND_FIRST_BEAT_DEBOUNCE_MILLIS = 8_000L
    const val JITTER_FRACTION = 0.20

    /** Doubling schedule from the baseline after consecutive no-change beats. */
    fun noChangeIntervalMillis(consecutiveNoChangeBeats: Int): Long {
        val exponent = consecutiveNoChangeBeats.coerceIn(0, 20)
        return (BASELINE_INTERVAL_MILLIS shl exponent)
            .coerceAtMost(MAX_NO_CHANGE_INTERVAL_MILLIS)
    }

    fun jitterBoundMillis(scheduledIntervalMillis: Long): Long =
        floor(scheduledIntervalMillis * JITTER_FRACTION).toLong()
}

/**
 * Pure trigger verdict (research §4.2): one [SyncHeartbeat] response compared
 * against the local session snapshot. Every key triggers independently and the
 * comparison is `!=`, never `>` — disaster recovery re-inserts entities and
 * resets `family_meta.rev` back to the cursor, so a REGRESSED watermark must
 * still kick the sync loop (grilled correction, non-revertible).
 */
enum class HeartbeatVerdict {
    /** All three keys equal the session snapshot; keep waiting quietly. */
    NoAction,

    /** At least one key differs in either direction; kick the existing foreground loop. */
    NeedsSync,
}

/**
 * Local session inputs of [heartbeatVerdict]. `cachedDirectoryGeneration` is
 * the last directory snapshot generation persisted by the client ("" before
 * the first snapshot — the first beat then reports NeedsSync once and the
 * ordinary foreground round's handshake populates the cache).
 */
data class HeartbeatSessionSnapshot(
    val pullCursor: Long,
    val pullGeneration: String,
    val cachedDirectoryGeneration: String,
)

fun heartbeatVerdict(
    heartbeat: SyncHeartbeat,
    snapshot: HeartbeatSessionSnapshot,
): HeartbeatVerdict = if (
    heartbeat.headRev != snapshot.pullCursor ||
    heartbeat.generation != snapshot.pullGeneration ||
    heartbeat.directoryGeneration != snapshot.cachedDirectoryGeneration
) {
    HeartbeatVerdict.NeedsSync
} else {
    HeartbeatVerdict.NoAction
}

/**
 * Source of the signed ±20% healthy-cadence jitter. Injectable so engine tests
 * are deterministic; the engine clamps the applied result into
 * `[0.8x, 1.2x]` of the scheduled interval regardless of the source.
 */
fun interface HeartbeatJitterSource {
    fun jitterMillis(scheduledIntervalMillis: Long): Long
}

object RandomHeartbeatJitterSource : HeartbeatJitterSource {
    override fun jitterMillis(scheduledIntervalMillis: Long): Long {
        val bound = SyncHeartbeatPolicy.jitterBoundMillis(scheduledIntervalMillis)
        if (bound <= 0L) return 0L
        return Random.Default.nextLong(from = -bound, until = bound + 1)
    }
}

/**
 * Capability gate of the engine. Two arming routes exist and the compat
 * matrix (research §6) accepts EITHER signal: the setup-status advertisement
 * ([SyncHeartbeatEngine.onServerCapabilityAdvertised], the settings-path
 * accelerator) or the one-shot discovery beat
 * ([SyncHeartbeatEngine.allowDiscoveryBeat]) — so a user who never opens the
 * network settings is still covered (「全程零用户操作」).
 */
sealed interface HeartbeatGate {
    /** Default. No setup-status advertisement observed and no discovery beat permitted. */
    data object NotAdvertised : HeartbeatGate

    /**
     * Exactly ONE un-advertised discovery beat is permitted. That beat's own
     * outcome decides: 2xx → [Active], 404 → [EndpointMissing], anything else
     * → back to [NotAdvertised] for a later retry on the failure's own
     * degraded cadence (never capability evidence either way).
     */
    data object DiscoveryPending : HeartbeatGate

    /**
     * Server advertised `sync_heartbeat_v1`, or the discovery beat answered
     * 2xx; beats are allowed.
     */
    data object Active : HeartbeatGate

    /**
     * The probe answered 404: the server predates the endpoint. Permanent and
     * silent — no retry, no backoff, and never an availability failure. A late
     * advertisement cannot re-arm; total cost vs an old server is exactly one
     * request per process.
     */
    data object EndpointMissing : HeartbeatGate
}

/** Closed observable outcome of one [SyncHeartbeatEngine.beat]. */
sealed interface SyncHeartbeatBeat {
    /** Absolute epoch millis of the next beat; null while the engine is disabled. */
    val nextBeatAtMillis: Long?

    /**
     * Server answered 2xx. [signal] is the immutable observed remote change
     * keys (the wire §1.5 closed three-key snapshot) exactly as this probe
     * answered them: the port-side cross-beat fuse keys on it to tell a
     * persistently-same difference (zero-progress suppression holds) from a
     * new change — any key differing, watermark regression included, which
     * releases the suppression. [availabilityUpdate] is the rehabilitated
     * [FamilyServerAvailability.Available] to apply, or null when the feeder
     * cannot truthfully construct one (no remembered metadata for this origin).
     */
    data class Answered(
        val verdict: HeartbeatVerdict,
        val signal: SyncHeartbeat,
        val availabilityUpdate: FamilyServerAvailability?,
        override val nextBeatAtMillis: Long,
    ) : SyncHeartbeatBeat

    /**
     * Transport-classified failure (same classification as the existing
     * feeders, single failure — no new hysteresis). Carries the degraded
     * state to apply; the next beat follows the existing retry ladder.
     */
    data class Degraded(
        val unavailable: FamilyServerAvailability.Unavailable,
        override val nextBeatAtMillis: Long,
    ) : SyncHeartbeatBeat

    /**
     * Failed without an availability-facing classification (e.g. 429
     * throttling, malformed success payload): availability untouched, but the
     * deadline still advances so the loop never spins.
     */
    data class NotAnswered(
        override val nextBeatAtMillis: Long,
    ) : SyncHeartbeatBeat

    /** Probe hit 404; the engine permanently disabled itself. */
    data object EndpointMissing : SyncHeartbeatBeat {
        override val nextBeatAtMillis: Long? = null
    }
}

/**
 * The unified foreground probe engine (ticket 03). One authenticated
 * `GET /v1/sync/heartbeat` per beat simultaneously (a) produces the change
 * verdict for the existing foreground sync loop and (b) feeds
 * `FamilyServerAvailability` as a resident feeder — success rehabilitates
 * Available and refreshes the healthy timestamps, a transport-classified
 * failure degrades immediately (single failure, no new hysteresis) using the
 * existing failure classification. The engine never runs a sync round, never
 * writes a FailureKind, never holds the foreground keep-alive, and never
 * flips the status line to Syncing: 401 terminal codes propagate as the
 * existing typed exceptions and are not availability failures.
 *
 * Rehabilitation merge design: the wire's closed three keys carry no
 * `endpointOrigin` / `serverVersion` / `familyState`, so a heartbeat cannot
 * truthfully construct an Available from nothing. The engine remembers the
 * last observed Available metadata (from the [availabilitySnapshot] provider
 * and from its own rehabilitations) for the current origin and only refreshes
 * the timestamps on success. Without remembered metadata for the probing
 * origin the beat reports success but leaves the existing availability state
 * untouched; the anonymous probe path remains the authority for
 * first-establishment and stays enabled.
 *
 * Cadence: healthy baseline 60s backing off (±20% jitter, schedule capped at
 * 300s) while beats observe no change; reset to baseline by
 * [onLocalWriteCompleted], a NeedsSync verdict, or [onForegroundReturned]
 * (which additionally debounces the first beat by 8s). While degraded, the
 * next beat follows the published [FamilyServerAvailabilityPolicy] ladder.
 * The single absolute deadline in [nextBeatAtMillis] is recomputed from `now`
 * at every event — beats are never queued and missed beats never accumulate.
 *
 * Arming (compat matrix research §6 — either signal decides): the engine
 * arms on a setup-status advertisement ([onServerCapabilityAdvertised], the
 * settings-page accelerator) OR on its own one-shot discovery beat
 * ([allowDiscoveryBeat]): without any advertisement the loop probes once —
 * success arms from probe evidence, a 404 permanently disables
 * ([HeartbeatGate.EndpointMissing]; exactly one request per process against
 * an old server, never an availability failure), and any other failure
 * re-opens discovery on the failure's degraded cadence. A 404 outranks a
 * later advertisement forever.
 */
class SyncHeartbeatEngine(
    private val backend: SyncBackend,
    private val clock: PolicyClock,
    private val jitter: HeartbeatJitterSource = RandomHeartbeatJitterSource,
    private val availabilitySnapshot: () -> FamilyServerAvailability =
        { FamilyServerAvailability.Disabled },
) {
    // Lock discipline: [beatMutex] only serializes whole beats — it is held
    // across the suspend backend probe. Every mutation of cadence/schedule
    // state (`consecutiveNoChangeBeats`, `nextBeatAt`, the capability gate,
    // remembered metadata) happens under [stateLock]. The public hooks take
    // [stateLock] only, so they never suspend behind a slow probe, and a
    // hook's cadence reset can never be lost to an in-flight beat's
    // bookkeeping. Locks are only ever acquired in the order
    // beatMutex → stateLock, so the pair cannot deadlock.
    private val stateLock = Any()
    private val beatMutex = Mutex()

    private val gateState = MutableStateFlow<HeartbeatGate>(HeartbeatGate.NotAdvertised)

    /** Observable capability gate; ticket 04 drives beats only while Active. */
    val gate: StateFlow<HeartbeatGate> = gateState.asStateFlow()

    private val nextBeatAt = MutableStateFlow<Long?>(null)

    /**
     * Absolute epoch millis of the next beat, or null while disabled. A single
     * deadline, never a queue: consumers that wake up late just re-read it.
     */
    val nextBeatAtMillis: StateFlow<Long?> = nextBeatAt.asStateFlow()

    private var consecutiveNoChangeBeats = 0
    private var rememberedAvailable: RememberedAvailable? = null

    private data class RememberedAvailable(
        val endpointOrigin: String,
        val serverVersion: String,
        val familyState: SetupFamilyState?,
    )

    /**
     * setup-status advertised `sync_heartbeat_v1` (wiring in ticket 04). A
     * 404-disabled engine never re-arms, even if a later setup-status claims
     * the capability again.
     */
    fun onServerCapabilityAdvertised() {
        synchronized(stateLock) {
            if (gateState.value != HeartbeatGate.NotAdvertised) return
            gateState.value = HeartbeatGate.Active
            nextBeatAt.value = clock.nowMillis()
        }
    }

    /**
     * One-shot discovery permission (compat matrix research §6: either gate
     * signal may decide, so a joined foreground client that never opens the
     * network settings must still converge). From
     * [HeartbeatGate.NotAdvertised] this permits exactly ONE un-advertised
     * beat: a 2xx answer arms the engine ([HeartbeatGate.Active]), a 404
     * permanently disables it ([HeartbeatGate.EndpointMissing] — the total
     * cost against an old server is exactly one request per process), and any
     * other failure returns the gate to [HeartbeatGate.NotAdvertised],
     * available for a later discovery retry paced by the failure's own
     * degraded cadence — the failed answer is never capability evidence in
     * either direction.
     *
     * Idempotent: calls from any other state are no-ops, so repeated loop
     * iterations can never re-permit after the beat armed, after the 404
     * disable, or while a permitted beat is still pending. The first permit
     * schedules the +8s foreground first-beat debounce (the loop launch
     * coincides with app start / foreground return, research §4.3); a retry
     * keeps the deadline the failed beat already published.
     */
    fun allowDiscoveryBeat() {
        synchronized(stateLock) {
            if (gateState.value != HeartbeatGate.NotAdvertised) return
            gateState.value = HeartbeatGate.DiscoveryPending
            if (nextBeatAt.value == null) {
                nextBeatAt.value =
                    clock.nowMillis() + SyncHeartbeatPolicy.FOREGROUND_FIRST_BEAT_DEBOUNCE_MILLIS
            }
        }
    }

    /** Local-write-completed hook: reset the healthy cadence to the 60s baseline. */
    fun onLocalWriteCompleted() {
        synchronized(stateLock) {
            if (gateState.value != HeartbeatGate.Active) return
            consecutiveNoChangeBeats = 0
            scheduleHealthyBeatLocked()
        }
    }

    /**
     * Foreground-return hook: reset to baseline and debounce the first beat by
     * 8s so it avoids the onStart full sync and the 30s network-recovered
     * burst (research §4.3).
     */
    fun onForegroundReturned() {
        synchronized(stateLock) {
            if (gateState.value != HeartbeatGate.Active) return
            consecutiveNoChangeBeats = 0
            nextBeatAt.value =
                clock.nowMillis() + SyncHeartbeatPolicy.FOREGROUND_FIRST_BEAT_DEBOUNCE_MILLIS
        }
    }

    /**
     * One probe beat for [session]. Beats are mutex-serialized: a manual
     * refresh from settings and the periodic loop can never interleave — the
     * second caller suspends until the first beat closes. Returns a closed
     * [SyncHeartbeatBeat]; a null [SyncHeartbeatBeat.nextBeatAtMillis] means
     * nothing is scheduled because the engine disabled itself (the 404
     * endpoint gate) or was never enabled. Throws only real cancellations and
     * the existing terminal session exceptions ([RemoteDeviceRemovedException],
     * [RemoteMembershipDeletedException], [RemoteFamilyDeletedException],
     * [ReauthRequiredException], [ClientUpdateRequiredException]) — those are
     * never swallowed and never demote availability. Requires an Active gate,
     * or a DiscoveryPending gate consuming the one permitted discovery beat
     * ([allowDiscoveryBeat]).
     */
    suspend fun beat(
        session: SyncSession,
        snapshot: HeartbeatSessionSnapshot,
    ): SyncHeartbeatBeat = beatMutex.withLock {
        when (gateState.value) {
            HeartbeatGate.Active -> runBeat(session, snapshot)
            HeartbeatGate.DiscoveryPending -> runBeat(session, snapshot)
            HeartbeatGate.EndpointMissing -> SyncHeartbeatBeat.EndpointMissing
            HeartbeatGate.NotAdvertised ->
                throw IllegalStateException("心跳引擎未启用：setup-status 未广播 sync_heartbeat_v1")
        }
    }

    /**
     * Body of one serialized beat: [beatMutex] is held across the suspend
     * backend call, while every cadence/schedule mutation below runs under
     * [stateLock] — never across the suspension — so the public hooks stay
     * wait-free during a slow probe and a hook's reset always lands wholly
     * before or wholly after this beat's bookkeeping, never mid-window. A
     * discovery beat (gate [HeartbeatGate.DiscoveryPending] at entry) settles
     * the gate from its own outcome: success arms, a non-404 failure re-opens
     * discovery; only the beat's own bookkeeping can mutate the gate meanwhile
     * (beats are mutex-serialized and the advertisement accelerator only
     * transitions from NotAdvertised), so one capture at entry is sound.
     */
    private suspend fun runBeat(
        session: SyncSession,
        snapshot: HeartbeatSessionSnapshot,
    ): SyncHeartbeatBeat {
        val previous = availabilitySnapshot()
        val discovery = synchronized(stateLock) {
            gateState.value == HeartbeatGate.DiscoveryPending
        }
        synchronized(stateLock) {
            rememberAvailableMetadataLocked(previous, session)
        }
        try {
            val heartbeat = backend.heartbeat(session)
            return synchronized(stateLock) {
                onAnsweredLocked(session, snapshot, previous, heartbeat, discovery)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SyncHttpException) {
            return synchronized(stateLock) {
                if (failure.statusCode == 404) {
                    disableOnMissingEndpointLocked()
                    SyncHeartbeatBeat.EndpointMissing
                } else {
                    classifyFailureLocked(previous, failure, discovery)
                }
            }
        } catch (failure: Throwable) {
            return synchronized(stateLock) {
                classifyFailureLocked(previous, failure, discovery)
            }
        }
    }

    private fun onAnsweredLocked(
        session: SyncSession,
        snapshot: HeartbeatSessionSnapshot,
        previous: FamilyServerAvailability,
        heartbeat: SyncHeartbeat,
        discovery: Boolean,
    ): SyncHeartbeatBeat {
        // A discovery beat's 2xx IS the capability evidence: arm from probe
        // result (the advertisement accelerator never ran on this path).
        if (discovery) {
            gateState.value = HeartbeatGate.Active
        }
        val verdict = heartbeatVerdict(heartbeat, snapshot)
        val availabilityUpdate = rehabilitateLocked(previous, session)
        if (previous is FamilyServerAvailability.Unavailable) {
            // Leaving the degraded ladder: restart the healthy schedule at its
            // baseline. Disabled/Checking providers keep accumulating.
            consecutiveNoChangeBeats = 0
        }
        return when (verdict) {
            HeartbeatVerdict.NeedsSync -> {
                // Change detected is a reset-to-baseline event (research §4.3).
                consecutiveNoChangeBeats = 0
                SyncHeartbeatBeat.Answered(
                    verdict = verdict,
                    signal = heartbeat,
                    availabilityUpdate = availabilityUpdate,
                    nextBeatAtMillis = scheduleHealthyBeatLocked(),
                )
            }
            HeartbeatVerdict.NoAction -> {
                val interval =
                    SyncHeartbeatPolicy.noChangeIntervalMillis(consecutiveNoChangeBeats)
                consecutiveNoChangeBeats += 1
                SyncHeartbeatBeat.Answered(
                    verdict = verdict,
                    signal = heartbeat,
                    availabilityUpdate = availabilityUpdate,
                    nextBeatAtMillis = scheduleJitteredBeatLocked(interval),
                )
            }
        }
    }

    private fun classifyFailureLocked(
        previous: FamilyServerAvailability,
        failure: Throwable,
        discovery: Boolean = false,
    ): SyncHeartbeatBeat {
        // Terminal session signals surface unchanged and are never availability
        // transport failures.
        when (failure) {
            is RemoteDeviceRemovedException,
            is RemoteMembershipDeletedException,
            is RemoteFamilyDeletedException,
            is ReauthRequiredException,
            is ClientUpdateRequiredException,
            -> throw failure
        }
        if (discovery) {
            // Neither evidence nor disable: re-open discovery for a later
            // retry; the deadline published below paces it on the failure's
            // own ladder.
            gateState.value = HeartbeatGate.NotAdvertised
        }
        if (!isHeartbeatAvailabilityFailure(failure)) {
            return SyncHeartbeatBeat.NotAnswered(scheduleNotAnsweredBeatLocked())
        }
        val unavailable = publishDegradedLocked(previous, failure.toAvailabilityUnavailableReason())
        return SyncHeartbeatBeat.Degraded(
            unavailable = unavailable,
            nextBeatAtMillis = unavailable.nextProbeAtMillis,
        )
    }

    /**
     * Mirror of RealSyncPort's anonymous-probe failure publication: single
     * failure degrades immediately, consecutiveFailures accumulates from the
     * previous Unavailable state, and the next probe follows the existing
     * 30s/120s/600s ladder. No FailureKind exists on this path.
     */
    private fun publishDegradedLocked(
        previous: FamilyServerAvailability,
        reason: FamilyServerUnavailableReason,
    ): FamilyServerAvailability.Unavailable {
        val failures = (previous as? FamilyServerAvailability.Unavailable)
            ?.consecutiveFailures
            ?.plus(1)
            ?: 1
        val unavailable = FamilyServerAvailability.Unavailable(
            reason = reason,
            lastHealthyAtMillis = previous.lastHealthyAtOrNull,
            nextProbeAtMillis = clock.nowMillis() +
                FamilyServerAvailabilityPolicy.retryDelayMillis(failures),
            consecutiveFailures = failures,
        )
        nextBeatAt.value = unavailable.nextProbeAtMillis
        return unavailable
    }

    private fun rehabilitateLocked(
        previous: FamilyServerAvailability,
        session: SyncSession,
    ): FamilyServerAvailability.Available? {
        if (previous is FamilyServerAvailability.Available) {
            rememberedAvailable = RememberedAvailable(
                endpointOrigin = previous.endpointOrigin,
                serverVersion = previous.serverVersion,
                familyState = previous.familyState,
            )
            val now = clock.nowMillis()
            return previous.copy(
                lastHealthyAtMillis = now,
                leaseUntilMillis = now + FamilyServerAvailabilityPolicy.HEALTHY_LEASE_MILLIS,
            )
        }
        val memory = rememberedAvailable?.takeIf {
            matchesSessionOrigin(it.endpointOrigin, session)
        } ?: return null
        val now = clock.nowMillis()
        return FamilyServerAvailability.Available(
            endpointOrigin = memory.endpointOrigin,
            serverVersion = memory.serverVersion,
            lastHealthyAtMillis = now,
            leaseUntilMillis = now + FamilyServerAvailabilityPolicy.HEALTHY_LEASE_MILLIS,
            familyState = memory.familyState,
        )
    }

    private fun rememberAvailableMetadataLocked(
        previous: FamilyServerAvailability,
        session: SyncSession,
    ) {
        val available = previous as? FamilyServerAvailability.Available ?: return
        if (!matchesSessionOrigin(available.endpointOrigin, session)) return
        rememberedAvailable = RememberedAvailable(
            endpointOrigin = available.endpointOrigin,
            serverVersion = available.serverVersion,
            familyState = available.familyState,
        )
    }

    private fun scheduleHealthyBeatLocked(): Long = scheduleJitteredBeatLocked(
        SyncHeartbeatPolicy.noChangeIntervalMillis(consecutiveNoChangeBeats),
    )

    private fun scheduleJitteredBeatLocked(intervalMillis: Long): Long {
        val bound = SyncHeartbeatPolicy.jitterBoundMillis(intervalMillis)
        val jittered = (intervalMillis + jitter.jitterMillis(intervalMillis))
            .coerceIn(intervalMillis - bound, intervalMillis + bound)
            .coerceAtLeast(1L)
        val deadline = clock.nowMillis() + jittered
        nextBeatAt.value = deadline
        return deadline
    }

    /**
     * A beat that failed without an availability-facing classification still
     * backs the cadence off; an overdue deadline must never spin against a
     * throttling or misunderstanding server.
     */
    private fun scheduleNotAnsweredBeatLocked(): Long {
        val interval = SyncHeartbeatPolicy.noChangeIntervalMillis(consecutiveNoChangeBeats)
        consecutiveNoChangeBeats += 1
        return scheduleJitteredBeatLocked(interval)
    }

    private fun disableOnMissingEndpointLocked() {
        gateState.value = HeartbeatGate.EndpointMissing
        nextBeatAt.value = null
        consecutiveNoChangeBeats = 0
        rememberedAvailable = null
    }

    private fun isHeartbeatAvailabilityFailure(failure: Throwable): Boolean =
        // A TOFU pin mismatch is not an IOException, but TrustChanged must
        // surface exactly like the anonymous probe path, so trust failures
        // join the degradation set.
        failure.causeChainContains<SpkiPinMismatchException>() ||
            failure.isAvailabilityTransportFailure()

    private fun matchesSessionOrigin(endpointOrigin: String, session: SyncSession): Boolean =
        session.normalizedOriginOrNull() == endpointOrigin
}
