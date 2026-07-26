package com.lezi.babylog.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingJoinConfigTest {
    @Test
    fun httpsQrPrefillRemainsHttpsWhenOnboardingBuildsSavedConfig() {
        val prefill = decodeOnboardingInvitePrefill(
            """{"v":1,"baseUrl":"https://lezi.home:443","code":"ABCD1234","ssids":["Home"]}""",
        )

        val config = buildOnboardingHomeLanConfig(
            host = prefill.host,
            portText = prefill.portText,
            scheme = prefill.scheme,
            ssids = prefill.ssids,
        )

        assertEquals("https", prefill.scheme)
        assertEquals("https://lezi.home:443", config.baseUrl)
        assertEquals(listOf("Home"), config.allowedSsids)
    }
}
