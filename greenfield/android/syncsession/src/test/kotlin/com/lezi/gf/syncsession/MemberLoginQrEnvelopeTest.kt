package com.lezi.gf.syncsession

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MemberLoginQrEnvelopeTest {
    @Test
    fun roundTripFragmentAndLandingUrl() {
        val grant = MemberLoginQrEnvelope.MemberLoginGrant(
            code = "qr-abc",
            endpoint = "https://192.168.50.10:18765",
            trustedSpkiSha256 = "deadbeef",
        )
        val landing = MemberLoginQrEnvelope.buildLandingUrl(grant.endpoint!!, grant)
        assertThat(landing).startsWith("https://192.168.50.10:18765/join#v1.")
        val frag = landing.substringAfter("#")
        val decoded = MemberLoginQrEnvelope.decodeFragment(frag)
        assertThat(decoded).isInstanceOf(MemberLoginQrEnvelope.ParseResult.Ok::class.java)
        val g = (decoded as MemberLoginQrEnvelope.ParseResult.Ok).grant
        assertThat(g.code).isEqualTo("qr-abc")
        assertThat(g.endpoint).isEqualTo("https://192.168.50.10:18765")
        assertThat(g.trustedSpkiSha256).isEqualTo("deadbeef")
    }

    @Test
    fun parseScanInviteUrlDoesNotLoseCode() {
        val grant = MemberLoginQrEnvelope.MemberLoginGrant(
            code = "qr-1",
            endpoint = "https://127.0.0.1:18765",
        )
        val url = MemberLoginQrEnvelope.buildLandingUrl(grant.endpoint!!, grant)
        val p = MemberLoginQrEnvelope.parseScan(url)
        assertThat(p).isInstanceOf(MemberLoginQrEnvelope.ParseResult.Ok::class.java)
        val ok = p as MemberLoginQrEnvelope.ParseResult.Ok
        assertThat(ok.isInviteUrl).isTrue()
        assertThat(ok.grant.code).isEqualTo("qr-1")
        assertThat(MemberLoginQrEnvelope.isClaimable(ok.grant)).isTrue()
    }

    @Test
    fun parseLegacyRawJsonAndBareCode() {
        val json = """{"code":"qr-legacy","endpoint":"https://10.0.0.2:18765"}"""
        val a = MemberLoginQrEnvelope.parseScan(json)
        assertThat(a).isInstanceOf(MemberLoginQrEnvelope.ParseResult.Ok::class.java)
        assertThat((a as MemberLoginQrEnvelope.ParseResult.Ok).isInviteUrl).isFalse()
        assertThat(a.grant.code).isEqualTo("qr-legacy")

        val bare = MemberLoginQrEnvelope.parseScan("qr-1")
        assertThat((bare as MemberLoginQrEnvelope.ParseResult.Ok).grant.code).isEqualTo("qr-1")
    }

    @Test
    fun rejectTruncatedAndEmpty() {
        assertThat(MemberLoginQrEnvelope.parseScan(""))
            .isInstanceOf(MemberLoginQrEnvelope.ParseResult.Err::class.java)
        assertThat(MemberLoginQrEnvelope.decodeFragment("v1.!!!"))
            .isInstanceOf(MemberLoginQrEnvelope.ParseResult.Err::class.java)
        assertThat(MemberLoginQrEnvelope.parseScan("not a code!!"))
            .isInstanceOf(MemberLoginQrEnvelope.ParseResult.Err::class.java)
        // Invite page without fragment — invite-only, empty code
        val pageOnly = MemberLoginQrEnvelope.parseScan("https://127.0.0.1:18765/join")
        assertThat(pageOnly).isInstanceOf(MemberLoginQrEnvelope.ParseResult.Ok::class.java)
        val ok = pageOnly as MemberLoginQrEnvelope.ParseResult.Ok
        assertThat(ok.isInviteUrl).isTrue()
        assertThat(MemberLoginQrEnvelope.isClaimable(ok.grant)).isFalse()
    }

    @Test
    fun landingNeverContainsBootstrapSecret() {
        val url = MemberLoginQrEnvelope.buildLandingUrl(
            "https://h:18765",
            MemberLoginQrEnvelope.MemberLoginGrant("c1", "https://h:18765"),
        )
        assertThat(url.lowercase()).doesNotContain("bootstrap")
        assertThat(url.lowercase()).doesNotContain("secret")
        assertThat(url.lowercase()).doesNotContain("password")
    }
}
