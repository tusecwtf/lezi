package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NursingTimerClearEpochTest {
    @Test
    fun captureFromJsonExtractsSessionToken() {
        val epoch = NursingTimerClearEpoch.captureFromJson(
            """{"schemaVersion":1,"completionClientUuid":"session-old","savedElapsed":1}""",
        )
        assertThat(epoch.json).contains("session-old")
        assertThat(epoch.sessionToken).isEqualTo("session-old")
        assertThat(epoch.isEmpty).isFalse()
    }

    @Test
    fun captureFromJsonTokenLessYieldsNullToken() {
        val epoch = NursingTimerClearEpoch.captureFromJson(
            """{"schemaVersion":1,"leftRunning":true}""",
        )
        assertThat(epoch.json).isNotNull()
        assertThat(epoch.sessionToken).isNull()
    }

    @Test
    fun casRemoveMatchesSameSessionWithRewrittenJson() {
        val captured = NursingTimerClearEpoch(
            json = """{"schemaVersion":1,"completionClientUuid":"session-old","savedElapsed":1}""",
            sessionToken = "session-old",
        )
        val rewritten =
            """{"schemaVersion":1,"completionClientUuid":"session-old","savedElapsed":999,"leftRunning":false}"""
        assertThat(shouldCasRemoveNursingTimerJson(captured, rewritten)).isTrue()
    }

    @Test
    fun casRemovePreservesDifferentSessionToken() {
        val captured = NursingTimerClearEpoch(
            json = """{"schemaVersion":1,"completionClientUuid":"session-old"}""",
            sessionToken = "session-old",
        )
        val newer =
            """{"schemaVersion":1,"completionClientUuid":"session-new","savedElapsed":1}"""
        assertThat(shouldCasRemoveNursingTimerJson(captured, newer)).isFalse()
    }

    @Test
    fun casRemoveExactJsonMatchWithoutTokenIsSecondary() {
        val raw = """{"schemaVersion":1,"leftRunning":true}"""
        val captured = NursingTimerClearEpoch(json = raw, sessionToken = null)
        assertThat(shouldCasRemoveNursingTimerJson(captured, raw)).isTrue()
        assertThat(
            shouldCasRemoveNursingTimerJson(
                captured,
                """{"schemaVersion":1,"leftRunning":false}""",
            ),
        ).isFalse()
    }

    @Test
    fun stopDecisionOnlyWhenActiveExactlyMatchesCaptured() {
        assertThat(shouldStopCapturedNursingTimerSession(null, "session-old")).isFalse()
        assertThat(
            shouldStopCapturedNursingTimerSession("session-new", "session-old"),
        ).isFalse()
        assertThat(
            shouldStopCapturedNursingTimerSession("session-old", "session-old"),
        ).isTrue()
        assertThat(shouldStopCapturedNursingTimerSession("session-old", null)).isFalse()
        assertThat(shouldStopCapturedNursingTimerSession("session-old", "")).isFalse()
    }
}
