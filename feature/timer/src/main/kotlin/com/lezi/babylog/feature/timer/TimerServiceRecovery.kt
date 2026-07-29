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
 * bit and its active notification.
 */
internal suspend fun awaitTimerServicePublication(
    maxAttempts: Int = 20,
    pauseBetweenAttempts: suspend () -> Unit = { delay(50L) },
    isActuallyForeground: () -> Boolean,
    hasActiveNotification: () -> Boolean,
): TimerServiceStartResult {
    require(maxAttempts > 0)
    repeat(maxAttempts) { attempt ->
        val accepted = try {
            val foreground = isActuallyForeground()
            val notification = hasActiveNotification()
            foreground && notification
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
 * Persists a non-running pending snapshot first and a running snapshot only after service ack.
 * A platform exception is converted to the same durable failed state as an explicit service nack.
 */
internal suspend fun startTimerWithConfirmation(
    candidate: TimerState,
    publish: suspend (TimerState) -> Unit,
    startService: suspend () -> TimerServiceStartResult,
): TimerServiceStartResult {
    require(candidate.leftRunning.xor(candidate.rightRunning))
    val pending = candidate.pausedForServiceStart()
    publish(pending)
    val result = try {
        startService()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: RuntimeException) {
        TimerServiceStartResult.Failed(failure.toTimerServiceFailure())
    }
    val finalState = when (result) {
        TimerServiceStartResult.Started -> candidate.copy(
            serviceState = TimerServiceState.RUNNING,
            requestedSide = null,
            serviceFailure = null,
        )
        is TimerServiceStartResult.Failed -> pending.copy(
            serviceState = TimerServiceState.FAILED,
            serviceFailure = result.failure,
        )
    }
    publish(finalState)
    return result
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
