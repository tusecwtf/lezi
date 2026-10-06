package com.lezi.babylog.sync.heartbeat

import com.lezi.babylog.sync.backend.SyncHeartbeat
import com.lezi.babylog.sync.session.SyncSession

/** Identity boundaries reset suppression; ordinary token/cursor updates do not. */
internal data class ForegroundFuseIdentity(
    val familyId: String,
    val deviceId: String,
    val baseUrl: String,
    val membershipId: String,
    val joined: Boolean,
)

internal fun SyncSession.foregroundFuseIdentity(): ForegroundFuseIdentity =
    ForegroundFuseIdentity(
        familyId = familyId,
        deviceId = deviceId,
        baseUrl = baseUrl,
        membershipId = membershipId,
        joined = isJoined,
    )

/** The historical silent-continue budget of one incomplete foreground cluster. */
private const val MAX_ZERO_PROGRESS_FOREGROUND_CONTINUES = 3

/**
 * Owns both the zero-progress round budget and suppression across heartbeat beats.
 * Exhausting the budget suppresses only the observed signal for this identity;
 * probes and availability updates continue. A changed signal (including regression),
 * real user trigger, or durable progress releases suppression and resets the budget.
 *
 * Heartbeat kicks and internal continuations must not use [onRealUserTrigger].
 * All decisions are non-suspending and share one monitor.
 */
internal class ForegroundRoundFuse {
    private val lock = Any()
    private var identity: ForegroundFuseIdentity? = null
    private var zeroProgressContinues = 0

    /** Observed signal the currently-running cluster is chasing, if any. */
    private var clusterSignal: SyncHeartbeat? = null

    /** Armed suppression: an identical future beat must not kick a round. */
    private var fusedSignal: SyncHeartbeat? = null

    /** Residency/identity observation: any switch retires the state wholesale. */
    fun onSessionObserved(current: ForegroundFuseIdentity) {
        synchronized(lock) {
            if (current == identity) return
            resetAllLocked(current)
        }
    }

    /** Explicit retry authorization, not a heartbeat kick or internal continuation. */
    fun onRealUserTrigger() {
        synchronized(lock) {
            zeroProgressContinues = 0
            clusterSignal = null
            fusedSignal = null
        }
    }

    /** A successful round or durable progress: full release. */
    fun onDurableProgress(current: ForegroundFuseIdentity) {
        synchronized(lock) {
            if (current != identity) {
                resetAllLocked(current)
                return
            }
            zeroProgressContinues = 0
            clusterSignal = null
            fusedSignal = null
        }
    }

    /** Stop a cluster without arming a new fuse or releasing existing suppression. */
    fun resetRoundCounter(current: ForegroundFuseIdentity) {
        synchronized(lock) {
            if (current != identity) {
                resetAllLocked(current)
                return
            }
            zeroProgressContinues = 0
            clusterSignal = null
        }
    }

    /** After publishing probe availability, decide whether this signal may start a round. */
    fun shouldKickRound(signal: SyncHeartbeat, current: ForegroundFuseIdentity): Boolean {
        synchronized(lock) {
            if (current != identity) resetAllLocked(current)
            if (fusedSignal == signal) return false
            if (signal != clusterSignal) {
                zeroProgressContinues = 0
                fusedSignal = null
            }
            clusterSignal = signal
            return true
        }
    }

    /** Exhaustion suppresses the chased signal; otherwise allow silent continuation. */
    fun onZeroProgressRound(current: ForegroundFuseIdentity): Boolean {
        synchronized(lock) {
            if (current != identity) resetAllLocked(current)
            zeroProgressContinues += 1
            if (zeroProgressContinues >= MAX_ZERO_PROGRESS_FOREGROUND_CONTINUES) {
                fusedSignal = clusterSignal
                clusterSignal = null
                return false
            }
            return true
        }
    }

    private fun resetAllLocked(current: ForegroundFuseIdentity) {
        identity = current
        zeroProgressContinues = 0
        clusterSignal = null
        fusedSignal = null
    }
}
