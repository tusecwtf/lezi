package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JoinFamilyDraftTest {
    private val fullInvite =
        """{"v":1,"baseUrl":"https://qr.home:443","host":"qr.home","port":443,"code":"ABCD1234"}"""

    @Test
    fun fullInvitePrefillsOnlyTrustedEndpointFields() {
        val draft = JoinFamilyDraft.fromConfig(HomeLanServerConfig.noviceUiDefaults())
            .prefillInvitation(fullInvite)

        assertThat(draft.invitation).isEqualTo("ABCD1234")
        assertThat(draft.host).isEqualTo("qr.home")
        assertThat(draft.portText).isEqualTo("443")
        assertThat(draft.scheme).isEqualTo("https")
        assertThat(draft.hasJoinNetwork()).isTrue()
    }

    @Test
    fun malformedInviteNeverMutatesDraft() {
        val draft = JoinFamilyDraft(host = "family.example", portText = "443")
        val result = draft.applyInvitationInput("{broken")

        assertThat(result.error).isNotNull()
        assertThat(result.draft).isEqualTo(draft)
    }

    @Test
    fun plainCodePreservesEndpointAndBuildsNormalizedCommand() {
        val result = JoinFamilyDraft(
            host = "family.example",
            portText = "443",
        ).applyInvitationInput("abcd1234")
        val command = result.draft.toCommand("  妈妈  ")

        assertThat(command.invitation).isEqualTo("ABCD1234")
        assertThat(command.displayName).isEqualTo("妈妈")
        assertThat(command.homeLanConfig.baseUrl).isEqualTo("https://family.example:443")
    }

    @Test
    fun savedEndpointMergePreservesInvitation() {
        val merged = JoinFamilyDraft(invitation = "ABCD1234", host = "old")
            .mergeFromSaved(HomeLanServerConfig(host = "new.example", port = 9443))

        assertThat(merged.invitation).isEqualTo("ABCD1234")
        assertThat(merged.host).isEqualTo("new.example")
        assertThat(merged.portText).isEqualTo("9443")
        assertThat(merged.scheme).isEqualTo("https")
    }

    @Test
    fun endpointReadinessAndProvenanceAreEndpointOnly() {
        val ready = JoinFamilyDraft(
            invitation = "ABCD1234",
            host = "family.example",
            portText = "443",
        )
        val scanned = ready.applyInvitationInput(fullInvite)

        assertThat(joinDraftReadyForIdentity(ready)).isTrue()
        assertThat(joinNetworkPartialPrefillHint(JoinFamilyDraft(invitation = "ABCD1234")))
            .contains("服务器地址")
        assertThat(provenanceAfterInviteInput(JoinNetworkProvenance.None, scanned))
            .isEqualTo(JoinNetworkProvenance.ScannedFull)
        assertThat(identityNetworkSummary(ready, JoinNetworkProvenance.ScannedFull))
            .contains("服务器已从邀请带入")
        assertThat(joinConfirmEnabled(ready, "妈妈")).isTrue()
        assertThat(joinConfirmEnabled(ready.copy(host = ""), "妈妈")).isFalse()
    }
}
