package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class MemberLoginQrPayloadCodecTest {
    @Test
    fun onePayloadCarriesPinnedEndpointAndShortLivedGrantWithoutIdentityCredentials() {
        val payload = MemberLoginQrPayload(
            endpoint = TrustedEndpointProfile.tofuSpki(
                "https://Family.Example.com:9443/",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            ),
            grant = "grant-0000000000000000000000000000000000000",
            familyName = "乐乐一家",
            memberDisplayName = "妈妈",
            expiresAtEpochSeconds = 1_753_419_000,
        )

        val encoded = MemberLoginQrPayloadCodec.encode(payload)
        val decoded = MemberLoginQrPayloadCodec.decode(encoded)

        assertThat(decoded).isEqualTo(payload)
        assertThat(encoded).contains("\"type\":\"member_login\"")
        assertThat(encoded).contains("\"endpoint\":\"https://family.example.com:9443\"")
        assertThat(encoded).contains("\"trust\":\"tofu_spki\"")
        assertThat(encoded).contains("\"spki_sha256\"")
        assertThat(encoded).doesNotContain("membership_id")
        assertThat(encoded).doesNotContain("access_token")
        assertThat(encoded).doesNotContain("refresh_token")
        assertThat(encoded).doesNotContain("root_password")
        assertThat(payload.toString()).doesNotContain(payload.grant)
    }

    @Test
    fun systemPkiPayloadHasNoPinAndUnknownOrCredentialFieldsFailClosed() {
        val encoded = MemberLoginQrPayloadCodec.encode(
            MemberLoginQrPayload(
                endpoint = TrustedEndpointProfile.systemPki("https://family.example.com"),
                grant = "grant-0000000000000000000000000000000000000",
                familyName = null,
                memberDisplayName = "爸爸",
                expiresAtEpochSeconds = 1_753_419_000,
            ),
        )

        assertThat(encoded).doesNotContain("spki_sha256")
        assertThat(MemberLoginQrPayloadCodec.decode(encoded).endpoint.trustMode)
            .isEqualTo(EndpointTrustMode.SystemPki)
        assertThrows(IllegalArgumentException::class.java) {
            MemberLoginQrPayloadCodec.decode(
                encoded.dropLast(1) + ",\"access_token\":\"forged\"}",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemberLoginQrPayloadCodec.decode(encoded.replace("system_pki", "tofu_spki"))
        }
    }

    @Test
    fun malformedGrantAndExpiredMetadataAreRejectedByTheCodecBoundary() {
        val valid =
            """{"v":1,"type":"member_login","endpoint":"https://family.example.com","trust":"system_pki","grant":"grant-0000000000000000000000000000000000000","family_name":"乐乐一家","member_display_name":"妈妈","expires_at":1753419000}"""

        assertThrows(IllegalArgumentException::class.java) {
            MemberLoginQrPayloadCodec.decode(valid.replace("grant-0000000000000000000000000000000000000", "short"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemberLoginQrPayloadCodec.decode(valid.replace("1753419000", "0"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemberLoginQrPayloadCodec.decode(valid.replace("https://", "http://"))
        }
    }
}
