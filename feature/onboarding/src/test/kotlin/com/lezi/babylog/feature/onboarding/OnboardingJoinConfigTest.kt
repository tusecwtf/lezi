package com.lezi.babylog.feature.onboarding

import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.JoinFamilyDraft
import com.lezi.babylog.sync.memberDisplayNameValidationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingJoinConfigTest {
    @Test
    fun httpsQrPrefillRemainsHttpsWhenOnboardingBuildsSavedConfig() {
        val draft = JoinFamilyDraft.fromConfig(
            HomeLanServerConfig.noviceUiDefaults(),
        ).prefillInvitation(
            """{"v":1,"baseUrl":"https://lezi.home:443","host":"lezi.home","port":443,"code":"ABCD1234","ssids":["Home"]}""",
        )

        val config = draft.toCommand(displayName = "妈妈").homeLanConfig

        assertEquals("https", draft.scheme)
        assertEquals("https://lezi.home:443", config.baseUrl)
        assertEquals("ABCD1234", draft.invitation)
        assertEquals("ABCD1234", draft.toCommand(displayName = "妈妈").invitation)
    }

    @Test
    fun joinUsesSameDisplayNameRulesAsAccountWizard() {
        assertEquals("请填写家庭称呼", memberDisplayNameValidationError("  "))
        assertEquals(
            "请填写家庭称呼，不能使用本机占位名",
            memberDisplayNameValidationError("我（本机）"),
        )
        assertNull(memberDisplayNameValidationError("干妈"))

        val draft = JoinFamilyDraft.fromConfig(
            HomeLanServerConfig.noviceUiDefaults(),
        ).copy(invitation = "CODE1234")

        val missingName = runCatching { draft.toCommand(displayName = "") }.exceptionOrNull()
        assertNotNull(missingName)
        assertTrue(missingName!!.message!!.contains("称呼"))

        val ok = draft.toCommand(displayName = "  爸爸  ")
        assertEquals("爸爸", ok.displayName)
        assertEquals("CODE1234", ok.invitation)
    }
}
