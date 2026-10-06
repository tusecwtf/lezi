package com.lezi.babylog.sync.qr

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import java.util.Base64
import org.junit.Assert.assertThrows
import org.junit.Test

class MemberLoginQrContentCodecTest {
    @Test
    fun landingPageQrWrapsTheRawV1PayloadInAnUnpaddedBase64UrlFragment() {
        val code = MemberLoginQrCode(
            payload = samplePayload(),
            landingUrl = "http://nas.home:8767/join",
        )

        val encoded = MemberLoginQrContentCodec.encode(code)

        assertThat(encoded).isEqualTo(
            "http://nas.home:8767/join#v1." +
                "eyJ2IjoxLCJ0eXBlIjoibWVtYmVyX2xvZ2luIiwiZW5kcG9pbnQiOiJodHRwczovL25hcy5ob21lIiwidHJ1c3QiOiJzeXN0ZW1fcGtpIiwiZ3JhbnQiOiJncmFudC0wMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwIiwiZmFtaWx5X25hbWUiOiLkuZDkuZDkuIDlrrYiLCJtZW1iZXJfZGlzcGxheV9uYW1lIjoi5aaI5aaIIiwiZXhwaXJlc19hdCI6MTc1MzQxOTAwMH0",
        )
    }

    @Test
    fun appScannerRecoversThePayloadFromTheLandingPageFragment() {
        val decoded = MemberLoginQrContentCodec.decode(
            "http://nas.home:8767/join#v1." +
                "eyJ2IjoxLCJ0eXBlIjoibWVtYmVyX2xvZ2luIiwiZW5kcG9pbnQiOiJodHRwczovL25hcy5ob21lIiwidHJ1c3QiOiJzeXN0ZW1fcGtpIiwiZ3JhbnQiOiJncmFudC0wMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwIiwiZmFtaWx5X25hbWUiOiLkuZDkuZDkuIDlrrYiLCJtZW1iZXJfZGlzcGxheV9uYW1lIjoi5aaI5aaIIiwiZXhwaXJlc19hdCI6MTc1MzQxOTAwMH0",
        )

        assertThat(decoded).isEqualTo(
            MemberLoginQrCode(
                payload = samplePayload(),
                landingUrl = "http://nas.home:8767/join",
            ),
        )
    }

    @Test
    fun missingLandingUrlKeepsTheLegacyRawJsonQrReadable() {
        val code = MemberLoginQrCode(payload = samplePayload(), landingUrl = null)

        val encoded = MemberLoginQrContentCodec.encode(code)

        assertThat(encoded).startsWith("{\"v\":1,\"type\":\"member_login\"")
        assertThat(MemberLoginQrContentCodec.decode(encoded)).isEqualTo(code)
    }

    @Test
    fun landingUrlMustStayOnTheMatchingLanDownloadOrigin() {
        val invalidLandingUrls = listOf(
            "https://nas.home:8767/join",
            "http://evil.home:8767/join",
            "http://nas.home/join",
            "http://nas.home:8766/join",
            "http://nas.home:8767/other",
            "http://user@nas.home:8767/join",
            "http://nas.home:8767/join?grant=leak",
            "http://nas.home:8767/join#unexpected",
        )

        invalidLandingUrls.forEach { landingUrl ->
            assertThrows(IllegalArgumentException::class.java) {
                MemberLoginQrContentCodec.encode(
                    MemberLoginQrCode(samplePayload(), landingUrl),
                )
            }
        }
    }

    @Test
    fun httpsPublicLandingUrlIsAcceptedWhenEndpointMatches() {
        val payload = publicHttpsPayload()
        val code = MemberLoginQrCode(
            payload = payload,
            landingUrl = "https://invite.example.invalid/join",
        )

        val encoded = MemberLoginQrContentCodec.encode(code)

        assertThat(encoded).startsWith("https://invite.example.invalid/join#v1.")
        assertThat(MemberLoginQrContentCodec.decode(encoded)).isEqualTo(code)
        MemberLoginQrContentCodec.encode(
            MemberLoginQrCode(payload, "https://invite.example.invalid:443/join"),
        )
    }

    @Test
    fun httpsPublicLandingUrlRejectsHttpAndMismatchedHosts() {
        val payload = publicHttpsPayload()
        val invalidLandingUrls = listOf(
            "http://invite.example.invalid/join",
            "http://invite.example.invalid:8767/join",
            "https://example.com/join",
            "https://invite.example.invalid:8443/join",
            "https://invite.example.invalid/other",
            "https://user@invite.example.invalid/join",
            "https://invite.example.invalid/join?grant=leak",
        )

        invalidLandingUrls.forEach { landingUrl ->
            assertThrows(IllegalArgumentException::class.java) {
                MemberLoginQrContentCodec.encode(MemberLoginQrCode(payload, landingUrl))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemberLoginQrContentCodec.encode(
                MemberLoginQrCode(samplePayload(), "https://invite.example.invalid/join"),
            )
        }
    }


    @Test
    fun landingUrlRejectsIpv6BecauseTheNasDistributionListenerIsIpv4Only() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            MemberLoginQrContentCodec.encode(
                MemberLoginQrCode(samplePayload(), "http://[fd00::1]:8767/join"),
            )
        }

        assertThat(error).hasMessageThat().contains("仅支持 IPv4 或 DNS 主机")
    }

    @Test
    fun urlEnvelopeRejectsPaddingAndContentTooLargeForOneQrCode() {
        val valid = MemberLoginQrContentCodec.encode(
            MemberLoginQrCode(samplePayload(), "http://nas.home:8767/join"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            MemberLoginQrContentCodec.decode("$valid=")
        }

        val oversizedRawJson = MemberLoginQrPayloadCodec.encode(samplePayload()) + " ".repeat(3_000)
        val oversizedEnvelope = "http://nas.home:8767/join#v1." +
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(oversizedRawJson.toByteArray(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) {
            MemberLoginQrContentCodec.decode(oversizedEnvelope)
        }
    }

    @Test
    fun urlEnvelopeRejectsPayloadBytesThatAreNotUtf8() {
        val rawBytes = MemberLoginQrPayloadCodec.encode(samplePayload())
            .toByteArray(Charsets.UTF_8)
        val firstNonAscii = rawBytes.indexOfFirst { byte -> byte.toInt() and 0x80 != 0 }
        rawBytes[firstNonAscii] = 0xff.toByte()
        val invalidUtf8Envelope = "http://nas.home:8767/join#v1." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes)

        assertThrows(IllegalArgumentException::class.java) {
            MemberLoginQrContentCodec.decode(invalidUtf8Envelope)
        }
    }

    private fun samplePayload() = MemberLoginQrPayload(
        endpoint = TrustedEndpointProfile.systemPki("https://nas.home"),
        grant = "grant-0000000000000000000000000000000000000",
        familyName = "乐乐一家",
        memberDisplayName = "妈妈",
        expiresAtEpochSeconds = 1_753_419_000,
    )

    private fun publicHttpsPayload() = samplePayload().copy(
        endpoint = TrustedEndpointProfile.systemPki("https://invite.example.invalid"),
    )
}
