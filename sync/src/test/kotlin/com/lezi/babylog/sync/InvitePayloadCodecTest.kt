package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InvitePayloadCodecTest {
    @Test
    fun qrPayloadRoundTripsHttpsServerAddressAndCode() {
        val payload = InvitePayloadCodec.encode(
            InvitePayload(
                baseUrl = "https://192.168.50.4:8765/",
                code = "abcd1234",
                host = "192.168.50.4",
                port = 8765,
            ),
        )

        assertThat(payload).contains("\"host\"")
        assertThat(payload).doesNotContain("ssid")
        assertThat(InvitePayloadCodec.decode(payload)).isEqualTo(
            InvitePayload(
                baseUrl = "https://192.168.50.4:8765",
                code = "ABCD1234",
                host = "192.168.50.4",
                port = 8765,
            ),
        )
    }

    @Test
    fun baseUrlOnlyInviteIsRejected() {
        val result = runCatching {
            InvitePayloadCodec.decode(
                """{"v":1,"baseUrl":"http://192.168.50.4:8765","code":"ABCD1234"}""",
            )
        }

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun httpsInviteKeepsSchemeInHomeLanConfig() {
        val decoded = InvitePayloadCodec.decode(
            """{"v":1,"baseUrl":"https://lezi.home:443","host":"lezi.home","port":443,"code":"ABCD1234"}""",
        )

        assertThat(decoded.homeLanConfig.baseUrl).isEqualTo("https://lezi.home:443")
    }

    @Test
    fun plainCodeStillDecodes() {
        val decoded = InvitePayloadCodec.decode("ab12cd34")
        assertThat(decoded.code).isEqualTo("AB12CD34")
        assertThat(decoded.baseUrl).isEmpty()
    }

    @Test
    fun hostPortWithoutBaseUrlBuildsBaseUrl() {
        val payload = InvitePayloadCodec.encode(
            InvitePayload(
                baseUrl = "",
                code = "ABCD1234",
                host = "192.168.50.4",
                port = 8765,
            ),
        )
        val decoded = InvitePayloadCodec.decode(payload)
        assertThat(decoded.baseUrl).isEqualTo("https://192.168.50.4:8765")
        assertThat(decoded.host).isEqualTo("192.168.50.4")
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
