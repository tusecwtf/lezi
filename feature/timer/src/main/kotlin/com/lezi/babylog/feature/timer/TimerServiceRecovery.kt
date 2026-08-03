package com.lezi.babylog.feature.timer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal fun interface NursingTimerRuntimeStopper {
    fun stopIfOwned(session: String): Boolean
}

internal object NursingTimerServiceRuntime {
    @Volatile
    private var activeSession: String? = null
    @Volatile
    private var startingSession: String? = null
    private val monitor = Any()
    private val stopRequests = mutableSetOf<String>()
    private var stopper: NursingTimerRuntimeStopper? = null

    fun markStarting(session: String) = synchronized(monitor) {
        startingSession = session
    }

    fun markActive(session: String) = synchronized(monitor) {
        if (startingSession == session) startingSession = null
        activeSession = session
    }

    fun clear(session: String? = null) = synchronized(monitor) {
        if (session == null) {
            activeSession = null
            startingSession = null
            stopRequests.clear()
        } else {
            if (activeSession == session) activeSession = null
            if (startingSession == session) startingSession = null
            stopRequests.remove(session)
        }
    }

    fun activeSession(): String? = activeSession

    /** Latest requested session owns stop decisions even before foreground ack. */
    fun currentSession(): String? = startingSession ?: activeSession

    fun isStopRequested(session: String): Boolean = synchronized(monitor) {
        session in stopRequests
    }

    /** Token-scoped stop: a newer STARTING session cannot be killed by an old clear. */
    fun requestStop(session: String): Boolean {
        val (currentStopper, wasStarting) = synchronized(monitor) {
            if (currentSession() != session) return false
            stopRequests += session
            stopper to (startingSession == session)
        }
        val stoppedByOwner = currentStopper?.stopIfOwned(session) == true
        if (!wasStarting && !stoppedByOwner) {
            synchronized(monitor) {
                // A RUNNING witness without a live matching owner is stale. Drop only the
                // captured token after re-checking: a same-token STARTING race must retain
                // its pending stop so onStartCommand can consume it, and a newer token wins.
                if (startingSession != session && activeSession == session) {
                    activeSession = null
                    stopRequests.remove(session)
                }
            }
        }
        return true
    }

    fun registerStopper(value: NursingTimerRuntimeStopper) {
        val pending = synchronized(monitor) {
            stopper = value
            currentSession()?.takeIf { it in stopRequests }
        }
        pending?.let(value::stopIfOwned)
    }

    fun unregisterStopper(value: NursingTimerRuntimeStopper) = synchronized(monitor) {
        if (stopper === value) stopper = null
    }
}

internal sealed interface TimerServiceStartResult {
    data object Started : TimerServiceStartResult

    data class Failed(val failure: TimerServiceFailure) : TimerServiceStartResult
}

/** Marks failures from channel/notification construction separately from platform start rejection. */
internal class TimerNotificationCreationException(cause: RuntimeException) : RuntimeException(cause)

internal fun RuntimeException.toTimerServiceFailure(): TimerServiceFailure = when {
    this is TimerNotificationCreationException -> TimerServiceFailure.NOTIFICATION
    this is SecurityException -> TimerServiceFailure.PERMISSION
    this is IllegalStateException ||
        javaClass.simpleName.contains("ForegroundServiceStartNotAllowed") ->
        TimerServiceFailure.RESTRICTED
    else -> TimerServiceFailure.RUNTIME
}

internal inline fun confirmTimerServiceStartup(action: () -> Unit): TimerServiceStartResult =
    try {
        action()
        TimerServiceStartResult.Started
    } catch (failure: RuntimeException) {
        TimerServiceStartResult.Failed(failure.toTimerServiceFailure())
    }

/**
 * `Service.startForeground()` may return normally even when a platform AppOp silently ignores
 * the promotion. Do not acknowledge startup until Android exposes both the foreground-service
 * bit and, when the user permits app notifications, its active notification. Android 13+
 * still permits a foreground service when notification permission is denied; in that case the
 * system surfaces the service outside the normal notification drawer.
 */
internal suspend fun awaitTimerServicePublication(
    maxAttempts: Int = 20,
    pauseBetweenAttempts: suspend () -> Unit = { delay(50L) },
    isActuallyForeground: () -> Boolean,
    notificationsEnabled: () -> Boolean = { true },
    hasActiveNotification: () -> Boolean,
): TimerServiceStartResult {
    require(maxAttempts > 0)
    repeat(maxAttempts) { attempt ->
        val accepted = try {
            val foreground = isActuallyForeground()
            val notificationPermission = notificationsEnabled()
            val notification = hasActiveNotification()
            foreground && (!notificationPermission || notification)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            return TimerServiceStartResult.Failed(failure.toTimerServiceFailure())
        }
        if (accepted) return TimerServiceStartResult.Started
        if (attempt < maxAttempts - 1) pauseBetweenAttempts()
    }
    return TimerServiceStartResult.Failed(TimerServiceFailure.RESTRICTED)
}

internal fun TimerState.pausedForServiceStart(): TimerState {
    val side = when {
        leftRunning -> "L"
        rightRunning -> "R"
        else -> requestedSide
    }
    require(side != null) { "No timer side requested" }
    return copy(
        leftRunning = false,
        rightRunning = false,
        // Do not count any unconfirmed startup interval as nursing time. Accumulated values
        // already include a previously running opposite side because toggle transitions freeze it.
        leftAccumMs = leftAccumMs,
        rightAccumMs = rightAccumMs,
        leftStartedElapsed = null,
        rightStartedElapsed = null,
        serviceState = TimerServiceState.STARTING,
        requestedSide = side,
        serviceFailure = null,
    )
}

internal fun TimerState.canRequestServiceStart(): Boolean =
    serviceState != TimerServiceState.STARTING

internal fun TimerState.retryServiceStartCandidate(
    nowElapsed: Long,
    nowWall: Long,
): TimerState? {
    if (serviceState != TimerServiceState.FAILED && serviceState != TimerServiceState.RECOVERABLE) {
        return null
    }
    val retryable = copy(
        serviceState = TimerServiceState.PAUSED,
        requestedSide = null,
        serviceFailure = null,
    )
    return when (requestedSide) {
        "L" -> retryable.withToggleLeft(nowElapsed = nowElapsed, nowWall = nowWall)
        "R" -> retryable.withToggleRight(nowElapsed = nowElapsed, nowWall = nowWall)
        else -> null
    }
}

/**
 * Durable FAILED identity after any non-cancel transition fault.
 * Freezes sides without inventing elapsed time; keeps accum, session, and retry side.
 * Never invents a side — [requestedSide] stays null when no real L/R intent exists.
 */
internal fun TimerState.toFailedRetryable(
    failure: TimerServiceFailure = TimerServiceFailure.RUNTIME,
): TimerState {
    val side = when {
        requestedSide == "L" || requestedSide == "R" -> requestedSide
        leftRunning -> "L"
        rightRunning -> "R"
        lastSide == "L" || lastSide == "R" -> lastSide
        else -> null
    }
    return copy(
        leftRunning = false,
        rightRunning = false,
        leftStartedElapsed = null,
        rightStartedElapsed = null,
        serviceState = TimerServiceState.FAILED,
        requestedSide = side,
        serviceFailure = failure,
    )
}

/**
 * Publish durable state; on non-cancel [Exception] fall back to memory only.
 * [CancellationException] and [Error] always propagate unchanged (caller owns stop).
 */
internal suspend fun publishTimerStateBestEffort(
    state: TimerState,
    publish: suspend (TimerState) -> Unit,
    publishMemoryOnly: (TimerState) -> Unit,
) {
    try {
        publish(state)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Error) {
        throw error
    } catch (_: Exception) {
        publishMemoryOnly(state)
    }
}

/**
 * Pause / clear path: always stop FGS once.
 * Persist PAUSED/empty; on write failure settle FAILED only when a real retry side exists
 * (requestedSide / was-running / lastSide). Plan-bind or ambiguous identity → memory PAUSED
 * (never invent "L"). Use [TimerServiceFailure.STORAGE] so copy is not startup-oriented.
 */
internal suspend fun settleNonRunningTimerTransition(
    next: TimerState,
    publish: suspend (TimerState) -> Unit,
    stopService: () -> Unit,
    publishMemoryOnly: (TimerState) -> Unit,
) {
    require(!next.leftRunning && !next.rightRunning)
    val paused = next.copy(
        serviceState = TimerServiceState.PAUSED,
        requestedSide = null,
        serviceFailure = null,
    )
    try {
        publish(paused)
    } catch (cancelled: CancellationException) {
        stopService()
        throw cancelled
    } catch (error: Error) {
        stopService()
        throw error
    } catch (_: Exception) {
        stopService()
        // Prefer lastSide from the pre-clear paused snapshot (publish cleared requestedSide).
        val failed = paused.toFailedRetryable(TimerServiceFailure.STORAGE)
        if (failed.requestedSide != null) {
            publishMemoryOnly(failed)
        } else {
            // Care-plan bind / uuid-only / no concrete side: keep PAUSED identity, not false FAILED.
            publishMemoryOnly(paused)
        }
        return
    }
    // Publish succeeded — stop once outside the publish failure path so a stop Exception
    // cannot be misclassified as a persist fault or overwrite disk PAUSED with FAILED.
    stopService()
}

/**
 * Post-fact clear differs from an ordinary pause: the service and in-memory
 * session must end even when DataStore fails, while the caller keeps a durable
 * retry flag until [clearDurable] eventually succeeds.
 */
internal suspend fun clearCommittedTimerSnapshot(
    clearDurable: suspend () -> Unit,
    stopService: () -> Unit,
    publishMemoryEmpty: () -> Unit,
): Boolean {
    val cleared = try {
        clearDurable()
        true
    } catch (cancelled: CancellationException) {
        false
    } catch (error: Error) {
        stopService()
        publishMemoryEmpty()
        throw error
    } catch (_: Exception) {
        false
    }
    stopService()
    publishMemoryEmpty()
    return cleared
}

/** Init / restore outcome after reading durable timer JSON (or failing to). */
internal data class TimerRestoreDecision(
    val state: TimerState,
    val shouldStopService: Boolean,
)

/**
 * Decide UI/service restore from durable timer JSON (happy path) or a failed read.
 *
 * Stop policy matches transitions: only a confirmed live RUNNING session may keep FGS.
 * Init is intentionally fail-closed on read IO / corrupt JSON: empty + stop (stronger than
 * transition keep-session FAILED). Callers may durable-clear via persistLocal(empty); that
 * wipe is intentional when disk is untrusted, not the same identity as transition FAILED.
 *
 * @param readFailed true only when the storage read itself threw; wording is fault-only.
 */
internal fun decideTimerRestore(
    raw: String?,
    nowElapsed: Long,
    nowWall: Long,
    nowBootCount: Long?,
    activeServiceSession: String?,
    readFailed: Boolean,
): TimerRestoreDecision {
    if (readFailed) {
        return TimerRestoreDecision(state = TimerState(), shouldStopService = true)
    }
    val restored = TimerState.fromJson(
        raw = raw,
        nowElapsed = nowElapsed,
        nowWall = nowWall,
        nowBootCount = nowBootCount,
        activeServiceSession = activeServiceSession,
    )
    return TimerRestoreDecision(
        state = restored,
        shouldStopService = restored.serviceState != TimerServiceState.RUNNING,
    )
}

/**
 * Persists a non-running pending snapshot first and a running snapshot only after service ack.
 * Platform exceptions, timeouts, notification failures, and DataStore IOException share one
 * cover: stop FGS once, settle FAILED via [toFailedRetryable] (try durable, then memory).
 * CancellationException and Error stop then rethrow unchanged.
 */
internal suspend fun startTimerWithConfirmation(
    candidate: TimerState,
    publish: suspend (TimerState) -> Unit,
    startService: suspend () -> TimerServiceStartResult,
    stopService: () -> Unit = {},
    publishMemoryOnly: (TimerState) -> Unit = {},
): TimerServiceStartResult {
    require(candidate.leftRunning.xor(candidate.rightRunning))
    val pending = candidate.pausedForServiceStart()
    try {
        publish(pending)
    } catch (cancelled: CancellationException) {
        stopService()
        throw cancelled
    } catch (error: Error) {
        stopService()
        throw error
    } catch (_: Exception) {
        stopService()
        val failed = pending.toFailedRetryable(TimerServiceFailure.STORAGE)
        publishTimerStateBestEffort(failed, publish, publishMemoryOnly)
        return TimerServiceStartResult.Failed(TimerServiceFailure.STORAGE)
    }

    val result = try {
        startService()
    } catch (cancelled: CancellationException) {
        stopService()
        throw cancelled
    } catch (error: Error) {
        stopService()
        throw error
    } catch (failure: RuntimeException) {
        TimerServiceStartResult.Failed(failure.toTimerServiceFailure())
    } catch (_: Exception) {
        TimerServiceStartResult.Failed(TimerServiceFailure.RUNTIME)
    }

    return when (result) {
        TimerServiceStartResult.Started -> {
            val running = candidate.copy(
                serviceState = TimerServiceState.RUNNING,
                requestedSide = null,
                serviceFailure = null,
            )
            try {
                publish(running)
                result
            } catch (cancelled: CancellationException) {
                stopService()
                throw cancelled
            } catch (error: Error) {
                stopService()
                throw error
            } catch (_: Exception) {
                // Service already acknowledged — must stop so UI cannot stay STARTING while FGS runs.
                stopService()
                val failed = pending.toFailedRetryable(TimerServiceFailure.STORAGE)
                publishTimerStateBestEffort(failed, publish, publishMemoryOnly)
                TimerServiceStartResult.Failed(TimerServiceFailure.STORAGE)
            }
        }
        is TimerServiceStartResult.Failed -> {
            // Shared cover with timeout / nack / platform throw: idempotent stop, then FAILED.
            stopService()
            val failed = pending.toFailedRetryable(result.failure)
            publishTimerStateBestEffort(failed, publish, publishMemoryOnly)
            result
        }
    }
}

internal fun TimerState.serviceFeedbackText(): String? = when (serviceState) {
    TimerServiceState.PAUSED, TimerServiceState.RUNNING -> null
    TimerServiceState.STARTING -> "正在确认系统计时服务…"
    TimerServiceState.RECOVERABLE -> "上次计时未确认仍在运行，已安全暂停；可重试启动。"
    TimerServiceState.FAILED -> when (serviceFailure) {
        TimerServiceFailure.RESTRICTED -> "系统暂不允许启动计时服务，已安全暂停；可重试启动。"
        TimerServiceFailure.PERMISSION -> "计时服务权限不可用，已安全暂停；检查系统设置后重试。"
        TimerServiceFailure.NOTIFICATION -> "计时通知创建失败，已安全暂停；可重试启动。"
        TimerServiceFailure.TIMEOUT -> "系统未确认计时服务已启动，已安全暂停；可重试启动。"
        TimerServiceFailure.STORAGE -> "计时状态保存失败，已安全暂停；可重试启动。"
        TimerServiceFailure.RUNTIME, null -> "计时服务启动失败，已安全暂停；可重试启动。"
    }
}
