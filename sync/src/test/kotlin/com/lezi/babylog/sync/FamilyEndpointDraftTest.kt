package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FamilyEndpointDraftTest {
    @Test
    fun emptyDraftContainsNoBuiltInServerAddress() {
        val draft = FamilyEndpointDraft.fromConfig(FamilyEndpointConfig.emptyDraft())

        assertThat(draft.host).isEmpty()
        assertThat(draft.portText).isEqualTo(DEFAULT_SERVER_PORT.toString())
        assertThat(draft.scheme).isEqualTo("https")
        assertThat(runCatching { draft.toConfig() }.exceptionOrNull())
            .hasMessageThat()
            .contains("请填写服务器主机")
    }

    @Test
    fun completeHttpsUrlOverridesSeparatePort() {
        val config = FamilyEndpointDraft(
            host = "https://family.example:9443",
            portText = "443",
        ).toConfig()

        assertThat(config.baseUrl).isEqualTo("https://family.example:9443")
    }

    @Test
    fun savedConfigReplacesEndpointWithoutAuthenticationMaterial() {
        val draft = FamilyEndpointDraft(host = "old.example", portText = "443")

        assertThat(
            draft.mergeFromSaved(
                FamilyEndpointConfig(host = "family.example", port = 9443, scheme = "https"),
            ),
        ).isEqualTo(
            FamilyEndpointDraft(host = "family.example", portText = "9443", scheme = "https"),
        )
    }
}
