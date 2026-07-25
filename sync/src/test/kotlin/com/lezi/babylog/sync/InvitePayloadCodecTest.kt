package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InvitePayloadCodecTest {
    @Test
    fun qrPayloadRoundTripsServerAddressAndCode() {
        val payload = InvitePayloadCodec.encode(
            InvitePayload(
                baseUrl = "http://192.168.50.4:8765/",
                code = "abcd1234",
            ),
        )

        assertThat(InvitePayloadCodec.decode(payload)).isEqualTo(
            InvitePayload(
                baseUrl = "http://192.168.50.4:8765",
                code = "ABCD1234",
            ),
        )
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
