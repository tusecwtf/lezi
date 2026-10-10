package com.lezi.babylog.sync.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.net.URI
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrustedEndpointOriginDeviceTest {
    @Test
    fun androidUriAndManualAddressPreserveOneIpv6BracketPair() {
        val origins = mapOf(
            "https://[2001:db8::1]:8765/" to "https://[2001:db8::1]:8765",
            "https://[::1]" to "https://[::1]",
            "https://[::1]:443/" to "https://[::1]",
            "https://192.0.2.1:8765/" to "https://192.0.2.1:8765",
            " HTTPS://Family.Example:443/ " to "https://family.example",
        )

        origins.forEach { (raw, expected) ->
            val endpoint = TrustedEndpointProfile.systemPki(raw)

            assertThat(endpoint.origin).isEqualTo(expected)
            assertThat(URI(endpoint.origin).host).isNotEmpty()
            assertThat(TrustedEndpointProfile.systemPki(endpoint.origin)).isEqualTo(endpoint)
        }
        val config = FamilyEndpointConfig.fromUserInput("[::1]", 8765)
        assertThat(TrustedEndpointProfile.systemPki(config.baseUrl).origin)
            .isEqualTo("https://[::1]:8765")
    }
}
