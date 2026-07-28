package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JoinFamilyDraftTest {
    private val fullQr =
        """{"v":1,"baseUrl":"https://qr.home:443","host":"qr.home","port":443,"code":"ABCD1234","ssids":["QrHome"]}"""
    private val qrHostOnly =
        """{"v":1,"baseUrl":"http://192.168.50.4:8765","host":"192.168.50.4","port":8765,"code":"HOSTONLY1"}"""

    @Test
    fun qrPrefillStoresShortCodeNotFullJson() {
        val draft = JoinFamilyDraft.fromConfig(
            HomeLanServerConfig.noviceUiDefaults("CurrentHome"),
        ).prefillInvitation(fullQr)

        assertThat(draft.invitation).isEqualTo("ABCD1234")
        assertThat(draft.invitation).doesNotContain("{")
        assertThat(draft.host).isEqualTo("qr.home")
        assertThat(draft.ssid1).isEqualTo("QrHome")
        assertThat(draft.hasJoinNetwork()).isTrue()
    }

    @Test
    fun qrPrefillUpdatesTheSharedDraftAndManualEditsOwnTheCommand() {
        val draft = JoinFamilyDraft.fromConfig(
            HomeLanServerConfig.noviceUiDefaults("CurrentHome"),
        ).prefillInvitation(fullQr).copy(
            host = "edited.home",
            portText = "9443",
            ssid1 = "EditedHome",
        )

        val command = draft.toCommand(displayName = "爸爸")

        assertThat(command.invitation).isEqualTo("ABCD1234")
        assertThat(command.homeLanConfig).isEqualTo(
            HomeLanServerConfig(
                host = "edited.home",
                port = 9443,
                allowedSsids = listOf("EditedHome"),
                scheme = "https",
            ),
        )
        assertThat(command.displayName).isEqualTo("爸爸")
    }

    @Test
    fun plainInviteDoesNotReplaceAnEditedEndpoint() {
        val draft = JoinFamilyDraft(
            host = "edited.home",
            portText = "8765",
            scheme = "http",
            ssid1 = "Home",
        ).prefillInvitation("ABCD1234")

        assertThat(draft.invitation).isEqualTo("ABCD1234")
        assertThat(draft.host).isEqualTo("edited.home")
        assertThat(draft.ssid1).isEqualTo("Home")
    }

    @Test
    fun applyInvitationInputFullQrPrefillsAndNeverThrowsOnBadJson() {
        val base = JoinFamilyDraft.fromConfig(HomeLanServerConfig.noviceUiDefaults("Local"))
        val ok = base.applyInvitationInput(fullQr)
        assertThat(ok.error).isNull()
        assertThat(ok.fromFullPayload).isTrue()
        assertThat(ok.decodedHost).isTrue()
        assertThat(ok.decodedSsidsFromPayload).isTrue()
        assertThat(ok.draft.invitation).isEqualTo("ABCD1234")
        assertThat(ok.draft.host).isEqualTo("qr.home")
        assertThat(ok.draft.ssid1).isEqualTo("QrHome")

        val broken = base.applyInvitationInput("""{"v":1,"code":"BAD""")
        assertThat(broken.error).isNotNull()
        assertThat(broken.draft).isEqualTo(base)
        assertThat(broken.draft.invitation).isEmpty()
    }

    @Test
    fun applyInvitationInputHostOnlyKeepsLocalSsids() {
        val base = JoinFamilyDraft(host = "old", portText = "8765", ssid1 = "LocalWifi")
        val result = base.applyInvitationInput(qrHostOnly)
        assertThat(result.error).isNull()
        assertThat(result.decodedSsidsFromPayload).isFalse()
        assertThat(result.draft.invitation).isEqualTo("HOSTONLY1")
        assertThat(result.draft.host).isEqualTo("192.168.50.4")
        assertThat(result.draft.ssid1).isEqualTo("LocalWifi")
        assertThat(result.draft.hasJoinNetwork()).isTrue()
    }

    @Test
    fun applyInvitationInputPlainCodeDoesNotTouchNetwork() {
        val base = JoinFamilyDraft(host = "h", portText = "8765", ssid1 = "S", invitation = "")
        val result = base.applyInvitationInput("ab12cd34")
        assertThat(result.error).isNull()
        assertThat(result.fromFullPayload).isFalse()
        assertThat(result.draft.invitation).isEqualTo("AB12CD34")
        assertThat(result.draft.host).isEqualTo("h")
        assertThat(result.draft.ssid1).isEqualTo("S")
    }

    @Test
    fun mergeFromSavedPreservesInvitation() {
        val draft = JoinFamilyDraft(
            invitation = "KEEPCODE1",
            host = "old.home",
            portText = "1",
            ssid1 = "Old",
        )
        val merged = draft.mergeFromSaved(
            HomeLanServerConfig(
                host = "new.home",
                port = 9443,
                allowedSsids = listOf("NewWifi"),
                scheme = "https",
            ),
        )
        assertThat(merged.invitation).isEqualTo("KEEPCODE1")
        assertThat(merged.host).isEqualTo("new.home")
        assertThat(merged.portText).isEqualTo("9443")
        assertThat(merged.ssid1).isEqualTo("NewWifi")
        assertThat(merged.scheme).isEqualTo("https")
    }

    @Test
    fun joinDraftReadyAndPartialHints() {
        assertThat(joinDraftReadyForIdentity(JoinFamilyDraft(host = "h", ssid1 = "S"))).isTrue()
        assertThat(joinDraftReadyForIdentity(JoinFamilyDraft(host = "h"))).isFalse()

        assertThat(
            joinNetworkPartialPrefillHint(JoinFamilyDraft(host = "192.168.1.1")),
        ).contains("请绑定家庭 Wi‑Fi")
        assertThat(
            joinNetworkPartialPrefillHint(JoinFamilyDraft(invitation = "ABCD1234")),
        ).contains("邀请码已填入")
        assertThat(
            joinNetworkPartialPrefillHint(JoinFamilyDraft(host = "h", ssid1 = "S")),
        ).isNull()
    }

    @Test
    fun identitySummaryHonestAboutScannedHost() {
        val draft = JoinFamilyDraft(
            host = "qr.home",
            portText = "443",
            ssid1 = "LocalWifi",
            invitation = "ABCD1234",
            scheme = "https",
        )
        val full = identityNetworkSummary(draft, JoinNetworkProvenance.ScannedFull)!!
        assertThat(full).contains("网络已从邀请带入")
        assertThat(full).contains("qr.home")

        val hostOnly = identityNetworkSummary(draft, JoinNetworkProvenance.ScannedHost)!!
        assertThat(hostOnly).contains("服务器已从邀请带入")
        assertThat(hostOnly).contains("Wi‑Fi 使用本机预填")
        assertThat(hostOnly).doesNotContain("网络已从邀请带入")

        assertThat(identityNetworkMissingHint(JoinFamilyDraft())).isNotNull()
        assertThat(identityNetworkMissingHint(draft)).isNull()
    }

    @Test
    fun provenanceHelpers() {
        val full = JoinFamilyDraft().applyInvitationInput(fullQr)
        assertThat(
            provenanceAfterInviteInput(JoinNetworkProvenance.None, full),
        ).isEqualTo(JoinNetworkProvenance.ScannedFull)

        val hostOnly = JoinFamilyDraft(ssid1 = "Local").applyInvitationInput(qrHostOnly)
        assertThat(
            provenanceAfterInviteInput(JoinNetworkProvenance.NoviceHint, hostOnly),
        ).isEqualTo(JoinNetworkProvenance.ScannedHost)

        val plain = JoinFamilyDraft(host = "h", ssid1 = "S").applyInvitationInput("ABCD1234")
        assertThat(
            provenanceAfterInviteInput(JoinNetworkProvenance.UserEdited, plain),
        ).isEqualTo(JoinNetworkProvenance.UserEdited)

        assertThat(provenanceAfterManualNetworkEdit(JoinNetworkProvenance.ScannedFull))
            .isEqualTo(JoinNetworkProvenance.UserEdited)
        assertThat(provenanceAfterManualNetworkEdit(JoinNetworkProvenance.PrefsSaved))
            .isEqualTo(JoinNetworkProvenance.PrefsSaved)

        assertThat(
            initialProvenanceAfterDismiss(
                prefsConfigured = false,
                draft = JoinFamilyDraft(
                    host = DEFAULT_SERVER_HOST,
                    portText = DEFAULT_SERVER_PORT.toString(),
                    ssid1 = "Home",
                ),
            ),
        ).isEqualTo(JoinNetworkProvenance.NoviceHint)
        assertThat(
            initialProvenanceAfterDismiss(prefsConfigured = true, draft = JoinFamilyDraft()),
        ).isEqualTo(JoinNetworkProvenance.PrefsSaved)
    }

    @Test
    fun joinConfirmEnabledRequiresNetworkCodeAndDisplayName() {
        val ready = JoinFamilyDraft(
            invitation = "ABCD1234",
            host = "h",
            ssid1 = "S",
        )
        assertThat(joinConfirmEnabled(ready, "妈妈")).isTrue()
        assertThat(joinConfirmEnabled(ready, "妈妈", joining = true)).isFalse()
        assertThat(joinConfirmEnabled(ready, "  ")).isFalse()
        assertThat(joinConfirmEnabled(ready.copy(invitation = ""), "妈妈")).isFalse()
        assertThat(joinConfirmEnabled(ready.copy(ssid1 = ""), "妈妈")).isFalse()
    }

    @Test
    fun toCommandNormalizesRequiredDisplayNameForAccountAndOnboarding() {
        val draft = JoinFamilyDraft(
            invitation = "CODE99",
            host = "192.168.50.4",
            portText = "8765",
            ssid1 = "Home",
        )
        assertThat(draft.toCommand(displayName = "  姥姥  ").displayName).isEqualTo("姥姥")
        assertThat(runCatching { draft.toCommand(displayName = "我（本机）") }.exceptionOrNull())
            .hasMessageThat()
            .contains("本机")
        assertThat(memberDisplayNameValidationError("")).isEqualTo("请填写家庭称呼")
        assertThat(memberDisplayNameValidationError("月嫂小王")).isNull()
    }

    @Test
    fun toCommandAcceptsShortCodeFromPrefill() {
        val draft = JoinFamilyDraft.fromConfig(
            HomeLanServerConfig(host = "h", port = 8765, allowedSsids = listOf("S")),
        ).prefillInvitation(fullQr)
        val command = draft.toCommand("爸爸")
        assertThat(command.invitation).isEqualTo("ABCD1234")
        val decoded = InvitePayloadCodec.decode(command.invitation)
        assertThat(decoded.code).isEqualTo("ABCD1234")
    }
}
