package com.lezi.babylog.feature.timer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal object NursingTimerServiceRuntime {
    @Volatile
    private var activeSession: String? = null

    fun markActive(session: String) {
        activeSession = session
    }

    fun clear(session: String? = null) {
        if (session == null || activeSession == session) activeSession = null
    }

    fun activeSession(): String? = activeSession
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
 * Publish durable state; on non-cancel write failure fall back to memory only.
 * CancellationException always propagates unchanged (caller stops the service).
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
    } catch (_: Exception) {
        publishMemoryOnly(state)
    }
}

/**
 * Pause / clear path: always stop FGS. Persist PAUSED/empty; on write failure settle
 * FAILED when session data remains so the UI is never a false PAUSED while disk is wrong.
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
        stopService()
    } catch (cancelled: CancellationException) {
        stopService()
        throw cancelled
    } catch (_: Exception) {
        stopService()
        if (paused.hasTimerData() || paused.completionClientUuid != null) {
            val failed = paused.toFailedRetryable(TimerServiceFailure.RUNTIME)
            // Prefer a concrete side when pause cleared requestedSide.
            val withSide = if (failed.requestedSide != null) {
                failed
            } else {
                failed.copy(
                    requestedSide = when (paused.lastSide) {
                        "L", "R" -> paused.lastSide
                        else -> "L"
                    },
                )
            }
            publishMemoryOnly(withSide)
        } else {
            publishMemoryOnly(paused)
        }
    }
}

/** Init / restore outcome after reading durable timer JSON (or failing to). */
internal data class TimerRestoreDecision(
    val state: TimerState,
    val shouldStopService: Boolean,
)

/**
 * Same stop policy as transitions: only a confirmed live RUNNING session may keep FGS.
 * Read IO / corrupt storage → empty + stop (no fabricated RUNNING).
 */
internal fun restoreTimerAfterStorageFault(
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
 * cover: stop FGS once, settle FAILED (memory first when durable write fails).
 * CancellationException stops then rethrows unchanged.
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
    } catch (_: Exception) {
        stopService()
        val failed = pending.toFailedRetryable(TimerServiceFailure.RUNTIME)
        publishMemoryOnly(failed)
        return TimerServiceStartResult.Failed(TimerServiceFailure.RUNTIME)
    }

    val result = try {
        startService()
    } catch (cancelled: CancellationException) {
        stopService()
        throw cancelled
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
            } catch (_: Exception) {
                // Service already acknowledged — must stop so UI cannot stay STARTING while FGS runs.
                stopService()
                val failed = pending.toFailedRetryable(TimerServiceFailure.RUNTIME)
                publishMemoryOnly(failed)
                TimerServiceStartResult.Failed(TimerServiceFailure.RUNTIME)
            }
        }
        is TimerServiceStartResult.Failed -> {
            // Shared cover with timeout / nack / platform throw: idempotent stop, then FAILED.
            stopService()
            val failed = pending.copy(
                serviceState = TimerServiceState.FAILED,
                serviceFailure = result.failure,
            )
            try {
                publish(failed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishMemoryOnly(failed)
            }
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
        TimerServiceFailure.RUNTIME, null -> "计时服务启动失败，已安全暂停；可重试启动。"
    }
}
