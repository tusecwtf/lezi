package com.lezi.babylog.feature.timer

import android.app.ActivityManager
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
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.domain.localdata.LocalDataMutationEpoch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Ongoing nursing-timer foreground service.
 *
 * Ticket 12: the running notification uses the system chronometer (`setUsesChronometer` +
 * `setWhen`), so the service never rebuilds it every second — it re-notifies only when a
 * transition arrives (side switch / app-driven start). The lock-screen 「暂停/继续」 action
 * is a service intent that freezes or restarts the durable session here (the FGS stays up
 * so 「已暂停」/「继续」 remain actionable); 「结束」 opens the in-app completion form so the
 * timer is never silently dropped.
 */
@AndroidEntryPoint
class NursingTimerService : Service(), NursingTimerRuntimeStopper {
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var localDataMutationEpoch: LocalDataMutationEpoch

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeSessionToken: String? = null
    private var requestedSessionToken: String? = null

    /** Main-thread-confident snapshot backing the ongoing notification. */
    private var snapshot: NursingNotificationSnapshot? = null

    /** Side that notification-「继续」 restarts; set by notification-「暂停」 only. */
    private var notificationPausedSide: String? = null

    /** Data generation captured with the session, mirroring TimerViewModel's guard. */
    private var sessionGeneration: Long = 0L

    override fun onCreate() {
        super.onCreate()
        NursingTimerServiceRuntime.registerStopper(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_NOTIFICATION_PAUSE -> {
                handleNotificationPause(startId)
                return START_NOT_STICKY
            }
            ACTION_NOTIFICATION_CONTINUE -> {
                handleNotificationContinue(startId)
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> Unit
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
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
        requestedSessionToken = sessionToken
        NursingTimerServiceRuntime.markStarting(sessionToken)
        if (NursingTimerServiceRuntime.isStopRequested(sessionToken)) {
            NursingTimerServiceRuntime.clear(sessionToken)
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
        this.snapshot = snapshot
        notificationPausedSide = null
        sessionGeneration = localDataMutationEpoch.currentGeneration()
        val initial = snapshot.at(SystemClock.elapsedRealtime())
        val startup = confirmTimerServiceStartup {
            val notification = try {
                ensureChannel()
                buildNotification(snapshot)
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
            failStartup(sessionToken, startId, receiver, startup.failure)
            return START_NOT_STICKY
        }

        serviceScope.launch {
            val publication = awaitTimerServicePublication(
                isActuallyForeground = ::isActuallyForeground,
                notificationsEnabled = ::notificationsEnabled,
                hasActiveNotification = ::hasActiveTimerNotification,
            )
            if (publication is TimerServiceStartResult.Failed) {
                failStartup(sessionToken, startId, receiver, publication.failure)
            } else {
                activeSessionToken = sessionToken
                NursingTimerServiceRuntime.markActive(sessionToken)
                sendStartResult(receiver, RESULT_STARTED, Bundle())
                // No per-second ticker: the system chronometer advances the running
                // notification; every later transition re-notifies exactly once.
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Drop the ongoing FGS notification on every teardown path (stopService,
        // failStartup already removes it; cleanup relies on this for local clear).
        stopForeground(STOP_FOREGROUND_REMOVE)
        NursingTimerServiceRuntime.unregisterStopper(this)
        requestedSessionToken?.let(NursingTimerServiceRuntime::clear)
        activeSessionToken?.let(NursingTimerServiceRuntime::clear)
        requestedSessionToken = null
        activeSessionToken = null
        snapshot = null
        notificationPausedSide = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun stopIfOwned(session: String): Boolean {
        if (requestedSessionToken != session && activeSessionToken != session) return false
        stopForeground(STOP_FOREGROUND_REMOVE)
        NursingTimerServiceRuntime.clear(session)
        stopSelf()
        return true
    }

    /**
     * Lock-screen 「暂停」: freeze the counting side, persist the pause durably, then
     * re-notify once with the static 「已暂停」 form. The FGS intentionally stays up so
     * 「继续」 remains one tap away. On a durable-write failure the previous (running)
     * notification is kept — the pause is only ever surfaced after it is committed.
     */
    private fun handleNotificationPause(startId: Int) {
        val token = activeSessionToken
        if (token == null) {
            // Stray action with no owned session (stale PendingIntent): nothing to pause.
            // A still-confirming STARTING session keeps the service alive untouched.
            if (requestedSessionToken == null) stopSelf(startId)
            return
        }
        val current = snapshot
        if (current == null) {
            if (requestedSessionToken == null) stopSelf(startId)
            return
        }
        if (!current.leftRunning && !current.rightRunning) return
        val nowElapsed = SystemClock.elapsedRealtime()
        serviceScope.launch {
            val frozen = persistNotificationPause(token, nowElapsed) ?: return@launch
            notifyTimerSafely(buildNotification(frozen))
            NursingTimerServiceRuntime.noteRemoteTimerAdjustment()
        }
    }

    /**
     * Lock-screen 「继续」: restart the paused side at now, persist RUNNING durably, then
     * re-notify once with the chronometer form. Kept paused (no visual change) when the
     * durable write fails, so the notification never claims a running timer it does not have.
     */
    private fun handleNotificationContinue(startId: Int) {
        val token = activeSessionToken
        val side = notificationPausedSide
        if (token == null || side == null || snapshot == null) {
            if (requestedSessionToken == null) stopSelf(startId)
            return
        }
        val current = snapshot
        if (current != null && (current.leftRunning || current.rightRunning)) return
        val nowElapsed = SystemClock.elapsedRealtime()
        serviceScope.launch {
            val resumed = persistNotificationContinue(token, side, nowElapsed) ?: return@launch
            notifyTimerSafely(buildNotification(resumed))
            NursingTimerServiceRuntime.noteRemoteTimerAdjustment()
        }
    }

    /** Read–modify–write the durable session into the frozen pause; null = do not re-notify. */
    private suspend fun persistNotificationPause(
        token: String,
        nowElapsed: Long,
    ): NursingNotificationSnapshot? {
        val current = restoreDurableSession(token, nowElapsed) ?: return null
        val paused = current.pausedByNotificationAction(nowElapsed) ?: return null
        if (!writeDurableSession(paused, nowElapsed)) return null
        return NursingNotificationSnapshot(
            leftMs = paused.leftAccumMs,
            rightMs = paused.rightAccumMs,
            leftRunning = false,
            rightRunning = false,
            capturedElapsed = nowElapsed,
        ).also {
            snapshot = it
            notificationPausedSide = if (current.leftRunning) "L" else "R"
        }
    }

    /** Read–modify–write the durable session back to RUNNING; null = keep paused form. */
    private suspend fun persistNotificationContinue(
        token: String,
        side: String,
        nowElapsed: Long,
    ): NursingNotificationSnapshot? {
        val current = restoreDurableSession(token, nowElapsed) ?: return null
        val resumed = current.resumedByNotificationAction(side, nowElapsed) ?: return null
        if (!writeDurableSession(resumed, nowElapsed)) return null
        return NursingNotificationSnapshot(
            leftMs = resumed.leftAccumMs,
            rightMs = resumed.rightAccumMs,
            leftRunning = side == "L",
            rightRunning = side == "R",
            capturedElapsed = nowElapsed,
        ).also {
            snapshot = it
            notificationPausedSide = null
        }
    }

    /**
     * Durable session for the pause/continue read–modify–write. `activeServiceSession`
     * is this service's own live token, so a RUNNING snapshot stays confirmed and its
     * accum freeze uses the same elapsed basis the app-side clock uses.
     */
    private suspend fun restoreDurableSession(
        token: String,
        nowElapsed: Long,
    ): TimerState? = try {
        TimerState.fromJson(
            raw = settingsStore.nursingTimerJson.first(),
            nowElapsed = nowElapsed,
            activeServiceSession = token,
        ).takeIf { state -> state.completionClientUuid == token }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun writeDurableSession(state: TimerState, nowElapsed: Long): Boolean = try {
        localDataMutationEpoch.withMutationInGeneration(sessionGeneration) {
            settingsStore.setNursingTimerJson(
                state.toJson(
                    savedElapsed = nowElapsed,
                    savedWall = System.currentTimeMillis(),
                    savedBootCount = currentBootCount(this@NursingTimerService),
                ),
            )
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun notifyTimerSafely(notification: Notification) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, notification)
        } catch (_: RuntimeException) {
            // A failed re-notify must not crash the foreground service.
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "喂奶计时", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(snapshot: NursingNotificationSnapshot): Notification {
        val nowElapsed = SystemClock.elapsedRealtime()
        val (leftMs, rightMs) = snapshot.at(nowElapsed)
        val paused = !snapshot.leftRunning && !snapshot.rightRunning
        val spec = nursingTimerNotificationSpec(
            leftMs = leftMs,
            rightMs = rightMs,
            leftRunning = snapshot.leftRunning,
            rightRunning = snapshot.rightRunning,
            nowWallMs = System.currentTimeMillis(),
            smallIconResId = R.drawable.ic_nursing_timer,
        )
        return buildNursingTimerNotification(
            context = this,
            spec = spec,
            contentIntent = timerContentIntent(),
            toggleIntent = notificationToggleIntent(paused = paused),
            finishIntent = notificationFinishIntent(),
        )
    }

    private fun timerContentIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(
                this,
                CONTENT_INTENT_REQUEST_CODE,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

    /** 「暂停/继续」 acts on this service directly via a service intent. */
    private fun notificationToggleIntent(paused: Boolean): PendingIntent =
        PendingIntent.getService(
            this,
            TOGGLE_INTENT_REQUEST_CODE,
            Intent(this, NursingTimerService::class.java).setAction(
                if (paused) ACTION_NOTIFICATION_CONTINUE else ACTION_NOTIFICATION_PAUSE,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 「结束」 opens the app's completion form (existing completion path) — never a
     * silent discard. Extras only; MainActivity routes the request to the timer page.
     */
    private fun notificationFinishIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launch.putExtra(EXTRA_NOTIFICATION_FINISH, true)
            PendingIntent.getActivity(
                this,
                FINISH_INTENT_REQUEST_CODE,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

    @Suppress("DEPRECATION")
    private fun isActuallyForeground(): Boolean =
        getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { service ->
                service.service.packageName == packageName &&
                    service.service.className == javaClass.name &&
                    service.foreground
            }

    private fun hasActiveTimerNotification(): Boolean =
        getSystemService(NotificationManager::class.java)
            .activeNotifications
            .any { notification ->
                notification.packageName == packageName && notification.id == NOTIF_ID
            }

    private fun notificationsEnabled(): Boolean =
        getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun failStartup(
        sessionToken: String,
        startId: Int,
        receiver: android.os.ResultReceiver?,
        failure: TimerServiceFailure,
    ) {
        NursingTimerServiceRuntime.clear(sessionToken)
        if (requestedSessionToken == sessionToken) requestedSessionToken = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        sendStartResult(
            receiver = receiver,
            resultCode = RESULT_FAILED,
            data = Bundle().apply { putString(EXTRA_START_FAILURE, failure.name) },
        )
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
        const val ACTION_NOTIFICATION_PAUSE = "com.lezi.babylog.timer.NOTIFICATION_PAUSE"
        const val ACTION_NOTIFICATION_CONTINUE = "com.lezi.babylog.timer.NOTIFICATION_CONTINUE"

        /**
         * Present on the launcher intent carried by the notification 「结束」 action;
         * MainActivity consumes it to open the timer page with the completion form.
         */
        const val EXTRA_NOTIFICATION_FINISH = "nursing_timer_notification_finish"
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

        // Distinct PendingIntent identities: identical MAIN/LAUNCHER intents would
        // otherwise collide on request code with FLAG_UPDATE_CURRENT.
        private const val CONTENT_INTENT_REQUEST_CODE = 0
        private const val TOGGLE_INTENT_REQUEST_CODE = 1
        private const val FINISH_INTENT_REQUEST_CODE = 2
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
