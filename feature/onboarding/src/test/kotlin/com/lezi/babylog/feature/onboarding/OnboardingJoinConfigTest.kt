package com.lezi.babylog.feature.onboarding

import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.JoinFamilyDraft
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingJoinConfigTest {
    @Test
    fun httpsQrPrefillRemainsHttpsWhenOnboardingBuildsSavedConfig() {
        val draft = JoinFamilyDraft.fromConfig(
            HomeLanServerConfig.noviceUiDefaults("CurrentHome"),
        ).prefillInvitation(
            """{"v":1,"baseUrl":"https://lezi.home:443","code":"ABCD1234","ssids":["Home"]}""",
        )

        val config = draft.toCommand().homeLanConfig

        assertEquals("https", draft.scheme)
        assertEquals("https://lezi.home:443", config.baseUrl)
        assertEquals(listOf("Home"), config.allowedSsids)
    }
}
