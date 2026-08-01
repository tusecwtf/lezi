package com.lezi.babylog.feature.timer

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.lezi.babylog.core.model.shouldStopCapturedNursingTimerSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

@Singleton
class NursingTimerServiceController @Inject constructor(
    @ApplicationContext private val app: Context,
) {
    internal suspend fun startAndConfirm(state: TimerState): TimerServiceStartResult {
        val result = withTimeoutOrNull(START_CONFIRM_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        val result = if (resultCode == NursingTimerService.RESULT_STARTED) {
                            TimerServiceStartResult.Started
                        } else {
                            val failure = resultData
                                ?.getString(NursingTimerService.EXTRA_START_FAILURE)
                                ?.let { raw ->
                                    TimerServiceFailure.entries.firstOrNull { it.name == raw }
                                }
                                ?: TimerServiceFailure.RUNTIME
                            TimerServiceStartResult.Failed(failure)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
                val snapshotElapsed = SystemClock.elapsedRealtime()
                val intent = Intent(app, NursingTimerService::class.java).apply {
                    action = NursingTimerService.ACTION_UPDATE
                    putExtra(NursingTimerService.EXTRA_LEFT_MS, state.leftMs(snapshotElapsed))
                    putExtra(NursingTimerService.EXTRA_RIGHT_MS, state.rightMs(snapshotElapsed))
                    putExtra(NursingTimerService.EXTRA_LEFT_RUNNING, state.leftRunning)
                    putExtra(NursingTimerService.EXTRA_RIGHT_RUNNING, state.rightRunning)
                    putExtra(NursingTimerService.EXTRA_SNAPSHOT_ELAPSED, snapshotElapsed)
                    putExtra(
                        NursingTimerService.EXTRA_SESSION_TOKEN,
                        state.completionClientUuid,
                    )
                    putExtra(NursingTimerService.EXTRA_START_RECEIVER, receiver)
                }
                try {
                    ContextCompat.startForegroundService(app, intent)
                } catch (failure: RuntimeException) {
                    if (continuation.isActive) {
                        continuation.resume(
                            TimerServiceStartResult.Failed(failure.toTimerServiceFailure()),
                        )
                    }
                }
            }
        }
        if (result != null) return result
        stop()
        return TimerServiceStartResult.Failed(TimerServiceFailure.TIMEOUT)
    }

    /**
     * Unconditional stop for ViewModel teardown (user finish / timeout / start fail).
     * Stop failures must not manufacture running truth.
     */
    internal fun stop() {
        try {
            requestNursingTimerServiceStop(app)
        } catch (_: RuntimeException) {
            // State is already non-running.
        }
    }

    /**
     * Session-scoped stop for local-clear finalization.
     *
     * Only stops when the process witness matches [sessionToken]. Unknown active
     * (null, including STARTING-before-markActive) is a no-op so a post-commit
     * newer session is not ABA-stopped. Rethrows stop failures unless a newer
     * session is already proven active; clears the durable runtime marker only
     * after the captured session is no longer claimed.
     */
    internal fun stopCapturedSession(sessionToken: String) {
        if (
            !shouldStopCapturedNursingTimerSession(
                activeSession = NursingTimerServiceRuntime.activeSession(),
                capturedSession = sessionToken,
            )
        ) {
            return
        }
        try {
            requestNursingTimerServiceStop(app)
        } catch (failure: RuntimeException) {
            val activeAfter = NursingTimerServiceRuntime.activeSession()
            if (activeAfter == sessionToken) throw failure
            // Newer session (or already cleared): do not rethrow.
            return
        }
        NursingTimerServiceRuntime.clear(sessionToken)
        // Belt-and-suspenders if the service process path left the notification.
        if (NursingTimerServiceRuntime.activeSession() != sessionToken) {
            cancelNursingTimerNotification(app)
        }
        check(NursingTimerServiceRuntime.activeSession() != sessionToken) {
            "Nursing timer session $sessionToken is still marked active after stop"
        }
    }

    private companion object {
        const val START_CONFIRM_TIMEOUT_MS = 4_500L
    }
}

/** Shared Intent glue for FGS stop; used by ViewModel and local-clear cleanup. */
internal fun requestNursingTimerServiceStop(app: Context) {
    app.stopService(Intent(app, NursingTimerService::class.java))
}

internal fun cancelNursingTimerNotification(app: Context) {
    try {
        app.getSystemService(NotificationManager::class.java)
            ?.cancel(NursingTimerService.NOTIF_ID)
    } catch (_: RuntimeException) {
        // Notification manager may be unavailable in rare process states.
    }
}

@Suppress("DEPRECATION")
internal fun Intent.timerStartResultReceiver(): ResultReceiver? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(NursingTimerService.EXTRA_START_RECEIVER, ResultReceiver::class.java)
    } else {
        getParcelableExtra(NursingTimerService.EXTRA_START_RECEIVER)
    }
