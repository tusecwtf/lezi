package com.lezi.babylog.feature.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NursingTimerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null
    private var activeSessionToken: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_UPDATE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val receiver = intent.timerStartResultReceiver()
        val sessionToken = intent.getStringExtra(EXTRA_SESSION_TOKEN)?.takeIf { it.isNotBlank() }
        if (sessionToken == null) {
            stopSelf(startId)
            sendStartResult(
                receiver,
                RESULT_FAILED,
                Bundle().apply { putString(EXTRA_START_FAILURE, TimerServiceFailure.RUNTIME.name) },
            )
            return START_NOT_STICKY
        }
        val snapshot = NursingNotificationSnapshot(
            leftMs = intent.getLongExtra(EXTRA_LEFT_MS, 0L),
            rightMs = intent.getLongExtra(EXTRA_RIGHT_MS, 0L),
            leftRunning = intent.getBooleanExtra(EXTRA_LEFT_RUNNING, false),
            rightRunning = intent.getBooleanExtra(EXTRA_RIGHT_RUNNING, false),
            capturedElapsed = intent.getLongExtra(
                EXTRA_SNAPSHOT_ELAPSED,
                SystemClock.elapsedRealtime(),
            ),
        )
        val initial = snapshot.at(SystemClock.elapsedRealtime())
        val startup = confirmTimerServiceStartup {
            val notification = try {
                ensureChannel()
                buildNotification(initial.first, initial.second)
            } catch (failure: RuntimeException) {
                throw TimerNotificationCreationException(failure)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }
        if (startup is TimerServiceStartResult.Failed) {
            NursingTimerServiceRuntime.clear(sessionToken)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            sendStartResult(
                receiver = receiver,
                resultCode = RESULT_FAILED,
                data = Bundle().apply { putString(EXTRA_START_FAILURE, startup.failure.name) },
            )
            return START_NOT_STICKY
        }
        activeSessionToken = sessionToken
        NursingTimerServiceRuntime.markActive(sessionToken)
        sendStartResult(receiver, RESULT_STARTED, Bundle())
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            val manager = getSystemService(NotificationManager::class.java)
            while (true) {
                delay(1_000L)
                val current = snapshot.at(SystemClock.elapsedRealtime())
                manager.notify(NOTIF_ID, buildNotification(current.first, current.second))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NursingTimerServiceRuntime.clear(activeSessionToken)
        activeSessionToken = null
        tickerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "喂奶计时", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(leftMs: Long, rightMs: Long): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "左 ${formatTimerMs(leftMs)} · 右 ${formatTimerMs(rightMs)}"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("乐记 · 喂奶计时中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun sendStartResult(receiver: android.os.ResultReceiver?, resultCode: Int, data: Bundle) {
        try {
            receiver?.send(resultCode, data)
        } catch (_: RuntimeException) {
            // The caller also has a timeout fail-safe; a dead receiver must not crash the service.
        }
    }

    companion object {
        const val CHANNEL_ID = "nursing_timer"
        const val NOTIF_ID = 42
        const val ACTION_UPDATE = "com.lezi.babylog.timer.UPDATE"
        const val EXTRA_LEFT_MS = "left_ms"
        const val EXTRA_RIGHT_MS = "right_ms"
        const val EXTRA_LEFT_RUNNING = "left_running"
        const val EXTRA_RIGHT_RUNNING = "right_running"
        const val EXTRA_SNAPSHOT_ELAPSED = "snapshot_elapsed"
        const val EXTRA_SESSION_TOKEN = "session_token"
        const val EXTRA_START_RECEIVER = "start_receiver"
        const val EXTRA_START_FAILURE = "start_failure"
        const val RESULT_STARTED = 1
        const val RESULT_FAILED = 2
    }
}

internal data class NursingNotificationSnapshot(
    val leftMs: Long,
    val rightMs: Long,
    val leftRunning: Boolean,
    val rightRunning: Boolean,
    val capturedElapsed: Long,
) {
    fun at(nowElapsed: Long): Pair<Long, Long> {
        val delta = (nowElapsed - capturedElapsed).coerceAtLeast(0L)
        return (leftMs + if (leftRunning) delta else 0L) to
            (rightMs + if (rightRunning) delta else 0L)
    }
}
