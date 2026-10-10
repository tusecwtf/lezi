package com.lezi.babylog.sync.session

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.qr.MemberLoginQrCode
import com.lezi.babylog.sync.qr.MemberLoginQrContentCodec
import org.junit.Assert.assertThrows
import org.junit.Test

class TrustedEndpointOriginTest {
    @Test
    fun supportedHttpsOriginsNormalizeOnceAndRemainStable() {
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
            assertThat(TrustedEndpointProfile.systemPki(endpoint.origin)).isEqualTo(endpoint)
        }
    }

    @Test
    fun manualAddressAndRawMemberQrEnterTrustWithTheSameIpv6Origin() {
        val config = FamilyEndpointConfig.fromUserInput("[2001:db8::1]:8765", null)
        val manualEndpoint = TrustedEndpointProfile.systemPki(config.baseUrl)
        val qr = MemberLoginQrContentCodec.decode(
            """{"v":1,"type":"member_login","endpoint":"https://[2001:db8::1]:8765/","trust":"system_pki","grant":"grant-0000000000000000000000000000000000000","family_name":null,"member_display_name":"家人","expires_at":1753419000}""",
        )

        assertThat(manualEndpoint.origin).isEqualTo("https://[2001:db8::1]:8765")
        assertThat(qr.payload.endpoint).isEqualTo(manualEndpoint)
        assertThat(MemberLoginQrContentCodec.decode(MemberLoginQrContentCodec.encode(qr)))
            .isEqualTo(qr)
        assertThrows(IllegalArgumentException::class.java) {
            MemberLoginQrCode(qr.payload, "http://[2001:db8::1]:8767/join")
        }
    }

    @Test
    fun malformedOrNonOriginAddressesCannotBecomeTrustedProfiles() {
        listOf(
            "https://[[::1]]:8765",
            "https://[::1]:0",
            "https://[::1]:65536",
            "https://user:password@[::1]:8765",
            "https://[::1]:8765/path",
            "https://[::1]:8765?query",
            "https://[::1]:8765#fragment",
            "http://[::1]:8765",
        ).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                TrustedEndpointProfile.systemPki(raw)
            }
        }
    }
}
