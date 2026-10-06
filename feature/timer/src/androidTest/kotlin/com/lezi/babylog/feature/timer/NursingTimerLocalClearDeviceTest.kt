package com.lezi.babylog.feature.timer

import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side evidence that local-clear stop tears down a real nursing timer
 * FGS + ongoing notification for the captured session, and leaves a newer
 * session alone (ABA).
 */
@RunWith(AndroidJUnit4::class)
class NursingTimerLocalClearDeviceTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @After
    fun tearDown() {
        try {
            app.stopService(Intent(app, NursingTimerService::class.java))
        } catch (_: RuntimeException) {
            // Best-effort teardown between tests.
        }
        cancelNursingTimerNotification(app)
        NursingTimerServiceRuntime.clear()
        // Allow service onDestroy to settle.
        SystemClock.sleep(200L)
    }

    @Test
    fun stopCapturedSessionClearsMatchingRuntimeAndLeavesNewerSession() {
        val cleanup = NursingTimerCleanupAdapter(app)

        NursingTimerServiceRuntime.markActive("session-old")
        cleanup.stopCapturedSession("session-old")
        assertThat(NursingTimerServiceRuntime.activeSession()).isNull()

        NursingTimerServiceRuntime.markActive("session-new")
        cleanup.stopCapturedSession("session-old")
        assertThat(NursingTimerServiceRuntime.activeSession()).isEqualTo("session-new")
    }

    @Test
    fun stopCapturedSessionTearsDownRealServiceAndNotification() {
        val cleanup = NursingTimerCleanupAdapter(app)
        val nm = app.getSystemService(NotificationManager::class.java)

        startTimerService(sessionToken = "session-old")
        awaitCondition(timeoutMs = 8_000L) {
            NursingTimerServiceRuntime.activeSession() == "session-old"
        }
        // When notifications are enabled, the FGS ongoing notification must appear.
        if (nm.areNotificationsEnabled()) {
            awaitCondition(timeoutMs = 8_000L) { hasTimerNotification(nm) }
        }

        cleanup.stopCapturedSession("session-old")

        awaitCondition(timeoutMs = 8_000L) {
            NursingTimerServiceRuntime.activeSession() == null
        }
        awaitCondition(timeoutMs = 8_000L) { !hasTimerNotification(nm) }

        // Newer session after clear must not be ABA-stopped by old cleanup.
        startTimerService(sessionToken = "session-new")
        awaitCondition(timeoutMs = 8_000L) {
            NursingTimerServiceRuntime.activeSession() == "session-new"
        }
        if (nm.areNotificationsEnabled()) {
            awaitCondition(timeoutMs = 8_000L) { hasTimerNotification(nm) }
        }

        cleanup.stopCapturedSession("session-old")
        assertThat(NursingTimerServiceRuntime.activeSession()).isEqualTo("session-new")
        if (nm.areNotificationsEnabled()) {
            assertThat(hasTimerNotification(nm)).isTrue()
        }
    }

    @Test
    fun stopCapturedSessionNoOpsWhenActiveUnknownStartingRace() {
        // STARTING-before-markActive: runtime is null; old cleanup must not stopService.
        val cleanup = NursingTimerCleanupAdapter(app)
        NursingTimerServiceRuntime.clear()
        assertThat(NursingTimerServiceRuntime.activeSession()).isNull()

        // Manually mark a "new" session after a no-op old stop would have killed it.
        cleanup.stopCapturedSession("session-old")
        NursingTimerServiceRuntime.markActive("session-new")
        assertThat(NursingTimerServiceRuntime.activeSession()).isEqualTo("session-new")
        cleanup.stopCapturedSession("session-old")
        assertThat(NursingTimerServiceRuntime.activeSession()).isEqualTo("session-new")
    }

    private fun startTimerService(sessionToken: String) {
        val snapshotElapsed = SystemClock.elapsedRealtime()
        val intent = Intent(app, NursingTimerService::class.java).apply {
            action = NursingTimerService.ACTION_UPDATE
            putExtra(NursingTimerService.EXTRA_LEFT_MS, 1_000L)
            putExtra(NursingTimerService.EXTRA_RIGHT_MS, 0L)
            putExtra(NursingTimerService.EXTRA_LEFT_RUNNING, true)
            putExtra(NursingTimerService.EXTRA_RIGHT_RUNNING, false)
            putExtra(NursingTimerService.EXTRA_SNAPSHOT_ELAPSED, snapshotElapsed)
            putExtra(NursingTimerService.EXTRA_SESSION_TOKEN, sessionToken)
        }
        ContextCompat.startForegroundService(app, intent)
    }

    private fun hasTimerNotification(nm: NotificationManager): Boolean =
        nm.activeNotifications.any { notification ->
            notification.packageName == app.packageName &&
                notification.id == NursingTimerService.NOTIF_ID
        }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        check(condition()) { "Condition not met within ${timeoutMs}ms" }
    }
}
