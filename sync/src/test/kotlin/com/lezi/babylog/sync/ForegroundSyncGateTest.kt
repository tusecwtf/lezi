package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ForegroundSyncGateTest {
    private val endpoint = TrustedEndpointProfile.systemPki("https://family.example.com:443")
    private val config = FamilyEndpointConfig.fromBaseUrl(endpoint.origin)

    @Test
    fun trustedEndpointIsAllowedWithoutAnyNetworkTransportInput() {
        assertThat(ForegroundSyncGate().evaluate(config, endpoint, isForeground = true))
            .isEqualTo(ForegroundSyncDecision.Allowed)
    }

    @Test
    fun backgroundMissingAndChangedEndpointFailBeforeRemoteIo() {
        val gate = ForegroundSyncGate()

        assertThat(gate.evaluate(config, endpoint, isForeground = false))
            .isEqualTo(ForegroundSyncDecision.Background)
        assertThat(gate.evaluate(FamilyEndpointConfig(), endpoint, isForeground = true))
            .isEqualTo(ForegroundSyncDecision.MissingEndpoint)
        assertThat(
            gate.evaluate(
                config,
                TrustedEndpointProfile.systemPki("https://other.example.com:443"),
                isForeground = true,
            ),
        ).isEqualTo(ForegroundSyncDecision.UntrustedEndpoint)
    }
}
