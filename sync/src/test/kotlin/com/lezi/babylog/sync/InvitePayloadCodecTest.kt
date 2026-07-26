package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InvitePayloadCodecTest {
    @Test
    fun qrPayloadRoundTripsServerAddressCodeAndSsids() {
        val payload = InvitePayloadCodec.encode(
            InvitePayload(
                baseUrl = "http://192.168.50.4:8765/",
                code = "abcd1234",
                host = "192.168.50.4",
                port = 8765,
                ssids = listOf("Home-2.4G", "Home-5G", "extra-dropped"),
            ),
        )

        assertThat(payload).contains("\"host\"")
        assertThat(payload).contains("\"ssids\"")
        assertThat(InvitePayloadCodec.decode(payload)).isEqualTo(
            InvitePayload(
                baseUrl = "http://192.168.50.4:8765",
                code = "ABCD1234",
                host = "192.168.50.4",
                port = 8765,
                ssids = listOf("Home-2.4G", "Home-5G"),
            ),
        )
    }

    @Test
    fun legacyBaseUrlOnlyStillDecodes() {
        val decoded = InvitePayloadCodec.decode(
            """{"v":1,"baseUrl":"http://192.168.50.4:8765","code":"ABCD1234"}""",
        )
        assertThat(decoded.host).isEqualTo("192.168.50.4")
        assertThat(decoded.port).isEqualTo(8765)
        assertThat(decoded.ssids).isEmpty()
        assertThat(decoded.code).isEqualTo("ABCD1234")
    }

    @Test
    fun httpsInviteKeepsSchemeInHomeLanConfig() {
        val decoded = InvitePayloadCodec.decode(
            """{"v":1,"baseUrl":"https://lezi.home:443","code":"ABCD1234"}""",
        )

        assertThat(decoded.homeLanConfig.baseUrl).isEqualTo("https://lezi.home:443")
    }

    @Test
    fun plainCodeStillDecodes() {
        val decoded = InvitePayloadCodec.decode("ab12cd34")
        assertThat(decoded.code).isEqualTo("AB12CD34")
        assertThat(decoded.baseUrl).isEmpty()
        assertThat(decoded.ssids).isEmpty()
    }

    @Test
    fun hostPortWithoutBaseUrlBuildsBaseUrl() {
        val payload = InvitePayloadCodec.encode(
            InvitePayload(
                baseUrl = "",
                code = "ABCD1234",
                host = "192.168.50.4",
                port = 8765,
                ssids = listOf("Home"),
            ),
        )
        val decoded = InvitePayloadCodec.decode(payload)
        assertThat(decoded.baseUrl).isEqualTo("http://192.168.50.4:8765")
        assertThat(decoded.host).isEqualTo("192.168.50.4")
        assertThat(decoded.ssids).containsExactly("Home")
    }

    @Test
    fun rejectsUnsupportedSchemes() {
        val result = runCatching {
            InvitePayloadCodec.decode(
                """{"v":1,"baseUrl":"file:///tmp/lezi","code":"ABCD1234"}""",
            )
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }
}
