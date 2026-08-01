package com.lezi.babylog.feature.timer

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/**
 * Public stop-policy seams for local-clear FGS finalization.
 *
 * [NursingTimerServiceRuntime] is the process witness the cleanup adapter consults
 * before deciding whether to stopService for a captured session token.
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
    fun capturedStopPolicyLeavesNewerSessionAlone() {
        NursingTimerServiceRuntime.markActive("session-new")
        val active = NursingTimerServiceRuntime.activeSession()
        val captured = "session-old"
        val shouldStop = active == null || active == captured
        assertThat(shouldStop).isFalse()
    }

    @Test
    fun capturedStopPolicyStopsWhenActiveMatchesOrIsUnknown() {
        NursingTimerServiceRuntime.markActive("session-old")
        assertThat(
            NursingTimerServiceRuntime.activeSession() == null ||
                NursingTimerServiceRuntime.activeSession() == "session-old",
        ).isTrue()

        NursingTimerServiceRuntime.clear()
        assertThat(
            NursingTimerServiceRuntime.activeSession() == null ||
                NursingTimerServiceRuntime.activeSession() == "session-old",
        ).isTrue()
    }
}
