package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JoinFamilyDraftTest {
    @Test
    fun qrPrefillUpdatesTheSharedDraftAndManualEditsOwnTheCommand() {
        val draft = JoinFamilyDraft.fromConfig(
            HomeLanServerConfig.noviceUiDefaults("CurrentHome"),
        ).prefillInvitation(
            """{"v":1,"baseUrl":"https://qr.home:443","host":"qr.home","port":443,"code":"ABCD1234","ssids":["QrHome"]}""",
        ).copy(
            host = "edited.home",
            portText = "9443",
            ssid1 = "EditedHome",
        )

        val command = draft.toCommand(displayName = "爸爸")

        assertThat(command.invitation).contains("ABCD1234")
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
}
