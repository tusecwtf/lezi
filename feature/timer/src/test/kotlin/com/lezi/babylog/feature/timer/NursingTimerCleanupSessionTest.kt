package com.lezi.babylog.feature.timer

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.shouldStopCapturedNursingTimerSession
import org.junit.After
import org.junit.Test

/**
 * Runtime witness + pure stop-policy seams for local-clear FGS finalization.
 *
 * Stop decision is [shouldStopCapturedNursingTimerSession] (shared with the
 * controller); these tests exercise that function and Runtime.clear matching.
 */
class NursingTimerCleanupSessionTest {
    @After
    fun tearDown() {
        NursingTimerServiceRuntime.clear()
    }

    @Test
    fun clearOnlyDropsMatchingActiveSession() {
        NursingTimerServiceRuntime.markActive("session-old")
        NursingTimerServiceRuntime.clear("session-new")
        assertThat(NursingTimerServiceRuntime.activeSession()).isEqualTo("session-old")

        NursingTimerServiceRuntime.clear("session-old")
        assertThat(NursingTimerServiceRuntime.activeSession()).isNull()
    }

    @Test
    fun clearWithoutTokenDropsAnyActiveSession() {
        NursingTimerServiceRuntime.markActive("session-any")
        NursingTimerServiceRuntime.clear()
        assertThat(NursingTimerServiceRuntime.activeSession()).isNull()
    }

    @Test
    fun stopPolicyLeavesNewerAndUnknownActiveAlone() {
        assertThat(
            shouldStopCapturedNursingTimerSession(
                activeSession = "session-new",
                capturedSession = "session-old",
            ),
        ).isFalse()
        // STARTING-before-markActive: active is still null — must not ABA-stop.
        assertThat(
            shouldStopCapturedNursingTimerSession(
                activeSession = null,
                capturedSession = "session-old",
            ),
        ).isFalse()
    }

    @Test
    fun stopPolicyStopsOnlyWhenActiveMatchesCaptured() {
        assertThat(
            shouldStopCapturedNursingTimerSession(
                activeSession = "session-old",
                capturedSession = "session-old",
            ),
        ).isTrue()
        NursingTimerServiceRuntime.markActive("session-old")
        assertThat(
            shouldStopCapturedNursingTimerSession(
                activeSession = NursingTimerServiceRuntime.activeSession(),
                capturedSession = "session-old",
            ),
        ).isTrue()
    }
}
