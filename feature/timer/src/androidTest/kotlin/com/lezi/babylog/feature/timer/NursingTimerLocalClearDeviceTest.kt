package com.lezi.babylog.feature.timer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side evidence that local-clear stop clears the process timer witness
 * for the captured session (and leaves a newer session alone).
 *
 * Full FGS + notification teardown is exercised when a real service was started;
 * this test always covers the session-scoped runtime contract used by revoke/clear.
 */
@RunWith(AndroidJUnit4::class)
class NursingTimerLocalClearDeviceTest {
    @After
    fun tearDown() {
        NursingTimerServiceRuntime.clear()
    }

    @Test
    fun stopCapturedSessionClearsMatchingRuntimeAndLeavesNewerSession() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val cleanup = NursingTimerCleanupAdapter(app)

        NursingTimerServiceRuntime.markActive("session-old")
        cleanup.stopCapturedSession("session-old")
        assertThat(NursingTimerServiceRuntime.activeSession()).isNull()

        NursingTimerServiceRuntime.markActive("session-new")
        cleanup.stopCapturedSession("session-old")
        assertThat(NursingTimerServiceRuntime.activeSession()).isEqualTo("session-new")
    }
}
