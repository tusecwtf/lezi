package com.lezi.babylog.sync.qr

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Test

class MemberLoginQrScanPolicyTest {
    private val now = 1_753_418_000L
    private val clock = Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC)
    private val policy = MemberLoginQrScanPolicy(clock)

    @Test
    fun untrustedInputUsesOneTypedDecisionTable() {
        val cases = listOf(
            Case("blank", "  ", MemberLoginQrScanOutcome.Empty),
            Case(
                "malformed",
                "not-a-member-login-qr",
                MemberLoginQrScanOutcome.Rejected(MemberLoginQrRejection.Unavailable),
            ),
            Case(
                "wrong kind",
                payloadJson(version = 1, type = "owner_login", expiresAt = now + 1),
                MemberLoginQrScanOutcome.Rejected(MemberLoginQrRejection.Unavailable),
            ),
            Case(
                "wrong version",
                payloadJson(version = 2, type = "member_login", expiresAt = now + 1),
                MemberLoginQrScanOutcome.Rejected(MemberLoginQrRejection.Unavailable),
            ),
            Case(
                "expiry equality",
                encodedPayload(expiresAt = now),
                MemberLoginQrScanOutcome.Rejected(MemberLoginQrRejection.Expired),
            ),
            Case(
                "expired",
                encodedPayload(expiresAt = now - 1),
                MemberLoginQrScanOutcome.Rejected(MemberLoginQrRejection.Expired),
            ),
        )

        cases.forEach { case ->
            assertWithMessage(case.name)
                .that(policy.evaluate(case.raw))
                .isEqualTo(case.expected)
        }
    }

    @Test
    fun validPayloadIsReadyWithoutChangingTheCapability() {
        val outcome = policy.evaluate(encodedPayload(expiresAt = now + 1))

        assertThat(outcome).isInstanceOf(MemberLoginQrScanOutcome.Ready::class.java)
        val payload = (outcome as MemberLoginQrScanOutcome.Ready).payload
        assertThat(payload.endpoint.origin).isEqualTo("https://nas.home")
        assertThat(payload.memberDisplayName).isEqualTo("妈妈")
        assertThat(payload.grant).isEqualTo(GRANT)
    }

    private fun encodedPayload(expiresAt: Long): String =
        MemberLoginQrPayloadCodec.encode(samplePayload(expiresAt))

    private fun samplePayload(expiresAt: Long) = MemberLoginQrPayload(
        endpoint = TrustedEndpointProfile.systemPki("https://nas.home"),
        grant = GRANT,
        familyName = "乐乐一家",
        memberDisplayName = "妈妈",
        expiresAtEpochSeconds = expiresAt,
    )

    private fun payloadJson(version: Int, type: String, expiresAt: Long): String =
        """{"v":$version,"type":"$type","endpoint":"https://nas.home","trust":"system_pki","grant":"$GRANT","family_name":"乐乐一家","member_display_name":"妈妈","expires_at":$expiresAt}"""

    private data class Case(
        val name: String,
        val raw: String,
        val expected: MemberLoginQrScanOutcome,
    )

    private companion object {
        const val GRANT = "grant-0000000000000000000000000000000000000"
    }
}
