package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SourceCommandLogoutConsentTest {
    @Test
    fun unsupportedPreparationCannotPretendNoSourceCommandNeedsConfirmation() = runTest {
        val port: SyncPort = NoOpSyncPort()

        val result = port.prepareSourceCommandLogout()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun unsupportedConsentedLogoutCannotFallThroughToOrdinaryLogout() = runTest {
        val consent = SourceCommandLogoutConsent(
            requestIds = listOf("source-request-a"),
            serverOrigin = "https://nas.example:8765",
            state = SourceCommandLogoutState.Unknown,
            exactEvidence = Any(),
        )

        val result = NoOpSyncPort().logoutCurrentDevice(consent)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun consentKeepsTheDisplayedRequestsFrozen() {
        val requests = mutableListOf("source-request-a")
        val consent = SourceCommandLogoutConsent(
            requestIds = requests,
            serverOrigin = null,
            state = SourceCommandLogoutState.Unknown,
            exactEvidence = Any(),
        )

        requests += "source-request-b"

        assertThat(consent.requestIds).containsExactly("source-request-a")
        assertThat(consent.serverOrigin).isNull()
    }
}
