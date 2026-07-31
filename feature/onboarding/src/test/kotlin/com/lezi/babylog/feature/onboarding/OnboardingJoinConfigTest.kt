package com.lezi.babylog.feature.onboarding

import com.lezi.babylog.sync.FamilyEndpointDraft
import com.lezi.babylog.sync.FamilyEndpointConfig
import com.lezi.babylog.sync.memberDisplayNameValidationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingJoinConfigTest {
    @Test
    fun trustedHttpsEndpointRemainsHttpsWhenOnboardingBuildsConfig() {
        val draft = FamilyEndpointDraft(
            host = "https://lezi.home:9443",
            portText = "443",
        )

        assertEquals("https", draft.scheme)
        assertEquals("https://lezi.home:9443", draft.toConfig().baseUrl)
    }

    @Test
    fun memberRequestUsesSameDisplayNameRulesAsAccountWizard() {
        assertEquals("请填写家庭称呼", memberDisplayNameValidationError("  "))
        assertEquals(
            "请填写家庭称呼，不能使用本机占位名",
            memberDisplayNameValidationError("我（本机）"),
        )
        assertNull(memberDisplayNameValidationError("干妈"))

        val draft = FamilyEndpointDraft.fromConfig(FamilyEndpointConfig.emptyDraft())
        assertEquals("", draft.host)
    }
}
