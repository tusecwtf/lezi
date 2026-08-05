package com.lezi.gf.app

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.app.invite.InviteScanHandler
import com.lezi.gf.family.JoinState
import com.lezi.gf.syncsession.FakeWireTransport
import com.lezi.gf.syncsession.MemberLoginQrEnvelope
import com.lezi.gf.syncsession.SyncSessionService
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Drives shipped [InviteScanHandler] + real [SyncSessionService]/[FakeWireTransport].
 * Invite URL must not markJoined; grant claim only after successful session.
 */
class InviteScanHandlerTest {
    private fun containerWithFake(): Pair<AppContainer, FakeWireTransport> {
        val dir = createTempDirectory("gf-invite").toFile()
        val fake = FakeWireTransport().also {
            it.familyConfigured = true
            it.bootstrapSecret = "x"
        }
        // Owner session seed for createMemberQr on fake
        val sync = SyncSessionService(http = fake)
        // AppContainer wires its own sync — pass transport
        val c = AppContainer(context = null, wireTransport = fake, dataDirOverride = dir)
        return c to fake
    }

    @Test
    fun invitePageOnlyUrlDoesNotMarkJoined() {
        val (c, _) = containerWithFake()
        assertThat(c.family.account().joinState).isEqualTo(JoinState.UNJOINED)
        var opened: String? = null
        val o = InviteScanHandler.handle(
            c,
            "https://127.0.0.1:18765/join",
        ) { opened = it }
        assertThat(o).isInstanceOf(InviteScanHandler.Outcome.OpenInviteInstall::class.java)
        assertThat(opened).contains("/join")
        assertThat(c.family.account().joinState).isEqualTo(JoinState.UNJOINED)
        assertThat(c.family.account().accessToken).isNull()
    }

    @Test
    fun fullLandingUrlWithGrantClaimsWhenHandledInApp() {
        // Post-install re-scan of the same system-camera URL
        val (c, _) = containerWithFake()
        val grant = MemberLoginQrEnvelope.MemberLoginGrant(
            code = "qr-1",
            endpoint = "https://127.0.0.1:18765",
        )
        val url = MemberLoginQrEnvelope.buildLandingUrl(grant.endpoint!!, grant)
        val o = InviteScanHandler.handle(c, url)
        assertThat(o).isInstanceOf(InviteScanHandler.Outcome.Joined::class.java)
        assertThat(c.family.account().joinState).isEqualTo(JoinState.JOINED)
    }

    @Test
    fun bareGrantClaimsAndMarksJoined() {
        val (c, _) = containerWithFake()
        // Fake claim-qr returns session for any code
        val o = InviteScanHandler.handle(c, "qr-1")
        assertThat(o).isInstanceOf(InviteScanHandler.Outcome.Joined::class.java)
        assertThat(c.family.account().joinState).isEqualTo(JoinState.JOINED)
        assertThat(c.family.account().accessToken).isNotNull()
        val j = o as InviteScanHandler.Outcome.Joined
        assertThat(j.familyName).isNotEmpty()
    }

    @Test
    fun grantJsonWithEndpointClaims() {
        val (c, _) = containerWithFake()
        val json = """{"code":"qr-1","endpoint":"https://10.0.0.5:18765"}"""
        val o = InviteScanHandler.handle(c, json)
        assertThat(o).isInstanceOf(InviteScanHandler.Outcome.Joined::class.java)
        assertThat(c.family.account().endpoint).contains("10.0.0.5")
        assertThat(c.family.account().joinState).isEqualTo(JoinState.JOINED)
    }

    @Test
    fun malformedLeavesUnjoined() {
        val (c, _) = containerWithFake()
        val o = InviteScanHandler.handle(c, "%%%not-valid%%%")
        assertThat(o).isInstanceOf(InviteScanHandler.Outcome.Failed::class.java)
        assertThat(c.family.account().joinState).isEqualTo(JoinState.UNJOINED)
    }

    @Test
    fun adminDisplayPayloadIsSystemCameraUrl() {
        val payload = InviteScanHandler.adminDisplayPayload(
            "https://192.168.1.8:18765",
            "qr-zz",
            trustedSpki = null,
        )
        assertThat(payload).startsWith("https://192.168.1.8:18765/join#v1.")
        assertThat(payload.lowercase()).doesNotContain("bootstrap")
        val parsed = MemberLoginQrEnvelope.parseScan(payload)
        assertThat((parsed as MemberLoginQrEnvelope.ParseResult.Ok).grant.code).isEqualTo("qr-zz")
    }

    @Test
    fun structuralScanJoinAndCameraPermission() {
        val account = File("src/main/java/com/lezi/gf/app/ui/account/AccountStack.kt").readText()
        assertThat(account).contains("ScanJoinScreen")
        assertThat(account).contains("InviteScanHandler")
        assertThat(account).contains("SCAN_RESULT")
        assertThat(account).contains("处理扫描内容")
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertThat(manifest).contains("android.permission.CAMERA")
        val joinHtml = File("../../sync-server/src/main.rs").readText()
        assertThat(joinHtml).contains("/join")
        assertThat(joinHtml).contains("不完成家庭登录")
    }
}
