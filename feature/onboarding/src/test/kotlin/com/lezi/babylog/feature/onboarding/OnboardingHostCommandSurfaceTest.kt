package com.lezi.babylog.feature.onboarding

import com.lezi.babylog.domain.family.FamilyWizardEntry
import com.lezi.babylog.domain.family.FamilyWizardMode
import com.lezi.babylog.domain.family.FamilyWizardState
import com.lezi.babylog.domain.family.isBusy
import com.lezi.babylog.feature.onboarding.steps.ConnectServerPrimary
import com.lezi.babylog.feature.onboarding.steps.connectServerPrimaryDecision
import com.lezi.babylog.feature.onboarding.steps.connectServerStepModel
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior seams for ticket 29 host (not reflection method inventories).
 * Adapter / QR parse / family transitions stay in sibling tests; this covers
 * connect projection consistency, shared busy predicate, and identity gates.
 */
class OnboardingHostCommandSurfaceTest {
    @Test
    fun connectStepModelSharesPrimaryDecisionWithActionWiring() {
        val ready = FamilyWizardState.EndpointReady(
            snapshot = emptySnapshot().copy(mode = FamilyWizardMode.Join),
            endpoint = TrustedEndpointProfile.systemPki("https://192.168.77.4:8765"),
        )
        val model = connectServerStepModel(ready)
        val decision = connectServerPrimaryDecision(ready)

        assertEquals(decision, model.primary)
        assertTrue(model.primary is ConnectServerPrimary.ContinueWithReady)
        assertEquals("加入家庭", model.primaryLabel(busy = false))
        assertTrue(model.primaryEnabled(busy = false, endpointDraft = ""))
        assertFalse(model.primaryEnabled(busy = true, endpointDraft = "https://x"))
    }

    @Test
    fun createAndJoinIdentityGatesShareAccountDisplayNameRules() {
        assertEquals(
            "请填写家庭称呼",
            onboardingMemberJoinSubmitError(displayName = "  ", deviceName = "手机"),
        )
        assertEquals(
            "请填写家庭称呼，不能使用本机占位名",
            onboardingMemberJoinSubmitError(displayName = "我（本机）", deviceName = "手机"),
        )
        assertEquals(
            "请填写设备称呼",
            onboardingMemberJoinSubmitError(displayName = "干妈", deviceName = "  "),
        )
        assertEquals(
            "请输入家庭名",
            onboardingCreateFamilySubmitError(
                displayName = "妈妈",
                familyName = "",
                deviceName = "Pixel",
                bootstrapSecret = "secret-long-enough",
            ),
        )
        assertEquals(
            "请填写管理员根密码",
            onboardingCreateFamilySubmitError(
                displayName = "妈妈",
                familyName = "乐乐一家",
                deviceName = "Pixel",
                bootstrapSecret = "",
            ),
        )
        assertNull(
            onboardingCreateFamilySubmitError(
                displayName = "妈妈",
                familyName = "乐乐一家",
                deviceName = "Pixel",
                bootstrapSecret = "secret-long-enough",
            ),
        )
        assertEquals(onboardingFamilyActions().first(), onboardingConnectFamilyAction())
        assertEquals("连接家庭服务器", onboardingConnectFamilyAction())
    }

    @Test
    fun busyPredicateMatchesInFlightWizardStatesOnly() {
        val editing = FamilyWizardState.Editing(emptySnapshot())
        assertFalse(editing.isBusy)
        assertTrue(FamilyWizardState.Submitting(emptySnapshot()).isBusy)
        assertTrue(FamilyWizardState.ProbingEndpoint(emptySnapshot()).isBusy)
        assertTrue(
            FamilyWizardState.VerifyingMemberLoginQr(emptySnapshot(), samplePayload()).isBusy,
        )
        assertTrue(
            FamilyWizardState.ClaimingMemberLoginQr(emptySnapshot(), samplePayload()).isBusy,
        )
        assertFalse(
            FamilyWizardState.EndpointReady(
                emptySnapshot(),
                TrustedEndpointProfile.systemPki("https://192.168.77.4:8765"),
            ).isBusy,
        )
        val waiting = FamilyWizardState.WaitingForMemberApproval(
            emptySnapshot(),
            com.lezi.babylog.sync.PendingMemberLogin(
                requestId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                displayName = "妈妈",
                deviceName = "Pixel",
                expiresAtEpochSeconds = 1,
            ),
        )
        assertFalse(waiting.isBusy)
        assertTrue(
            connectServerStepModel(FamilyWizardState.ProbingEndpoint(emptySnapshot()))
                .keepOfflineEnabled,
        )
        val validation = FamilyWizardState.RetryableFailure(
            snapshot = emptySnapshot(),
            message = "请填写家庭称呼",
            failureKind = com.lezi.babylog.core.common.failure.FailureKind.InvalidInput,
            field = com.lezi.babylog.domain.family.FamilyWizardField.DisplayName,
        )
        val presentation = com.lezi.babylog.domain.family.familyWizardFailurePresentation(validation)
        assertEquals(
            com.lezi.babylog.domain.family.FamilyWizardField.DisplayName,
            presentation.field,
        )
        assertNull(presentation.overlayKind)
        val wait = FamilyWizardState.WaitingForMemberApproval(
            emptySnapshot(),
            com.lezi.babylog.sync.PendingMemberLogin(
                requestId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                displayName = "妈妈",
                deviceName = "Pixel",
                expiresAtEpochSeconds = 1,
            ),
            feedback = "检查失败，请稍后重试",
            failureKind = com.lezi.babylog.core.common.failure.FailureKind.ResponseTimedOut,
        )
        assertNull(com.lezi.babylog.domain.family.familyWizardFailurePresentation(wait).overlayKind)
    }
    private fun emptySnapshot() =
        com.lezi.babylog.domain.family.FamilyWizardSnapshot.empty(FamilyWizardEntry.Onboarding)

    private fun samplePayload(
        endpoint: TrustedEndpointProfile =
            TrustedEndpointProfile.systemPki("https://192.168.77.4:8765"),
    ) = MemberLoginQrPayload(
        endpoint = endpoint,
        grant = "grant-0000000000000000000000000000000000000",
        familyName = "乐乐一家",
        memberDisplayName = "妈妈",
        expiresAtEpochSeconds = System.currentTimeMillis() / 1_000 + 3_600,
    )

}
