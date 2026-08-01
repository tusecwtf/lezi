package com.lezi.babylog.feature.onboarding

import com.lezi.babylog.domain.family.FamilyWizardController
import com.lezi.babylog.domain.family.FamilyWizardEntry
import com.lezi.babylog.domain.family.FamilyWizardGateway
import com.lezi.babylog.domain.family.FamilyWizardMode
import com.lezi.babylog.domain.family.FamilyWizardState
import com.lezi.babylog.domain.family.isBusy
import com.lezi.babylog.feature.onboarding.steps.ConnectServerPrimary
import com.lezi.babylog.feature.onboarding.steps.connectServerPrimaryDecision
import com.lezi.babylog.feature.onboarding.steps.connectServerStepModel
import com.lezi.babylog.sync.CreateFamilyResult
import com.lezi.babylog.sync.MemberLoginQrResult
import com.lezi.babylog.sync.OwnerLoginResult
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.session.CertificateTrustCandidate
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior seams for ticket 29 host (not reflection method inventories).
 * Adapter / QR parse / family transitions stay in sibling tests; this covers
 * connect projection consistency, shared busy predicate, identity gates, and
 * thin QR verify delegation onto [FamilyWizardController] the same way the shell wires it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingHostCommandSurfaceTest {
    @Test
    fun connectStepModelSharesPrimaryDecisionWithActionWiring() {
        val ready = FamilyWizardState.EndpointReady(
            snapshot = emptySnapshot().copy(mode = FamilyWizardMode.Join),
            endpoint = TrustedEndpointProfile.systemPki("https://192.168.50.4:8765"),
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
                TrustedEndpointProfile.systemPki("https://192.168.50.4:8765"),
            ).isBusy,
        )
    }

    @Test
    fun memberLoginQrVerifyThinDelegatesOntoFamilyWizardController() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://192.168.50.4:8765")
        val gateway = object : FamilyWizardGateway {
            var verifyCalls = 0
            override suspend fun probeEndpoint(endpointDraft: String) =
                SetupProbeResult.Failed.Unreachable
            override suspend fun trustCertificate(candidate: CertificateTrustCandidate) =
                SetupProbeResult.Failed.Unreachable
            override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile) =
                Result.success(Unit)
            override suspend fun forgetEndpoint() = Result.success(Unit)
            override suspend fun currentVerifiedEndpoint() = endpoint
            override suspend fun saveEndpointConfig(config: FamilyEndpointConfig) =
                Result.success(Unit)
            override suspend fun createFamily(
                config: FamilyEndpointConfig,
                displayName: String,
                deviceName: String,
                bootstrapSecret: String,
                familyName: String?,
            ) = Result.failure<CreateFamilyResult>(IllegalStateException("unused"))
            override suspend fun ownerLogin(
                config: FamilyEndpointConfig,
                deviceName: String,
                rootPassword: String,
                takeover: Boolean,
            ) = Result.failure<OwnerLoginResult>(IllegalStateException("unused"))
            override suspend fun verifyMemberLoginEndpoint(
                endpoint: TrustedEndpointProfile,
            ): SetupProbeResult {
                verifyCalls += 1
                return SetupProbeResult.Ready(endpoint, SetupFamilyState.Configured)
            }
            override suspend fun claimMemberLoginQr(
                payload: MemberLoginQrPayload,
                deviceName: String,
            ) = Result.failure<MemberLoginQrResult>(IllegalStateException("unused"))
            override suspend fun retryReclaimedDataRecovery() = Result.success(Unit)
        }
        val controller = FamilyWizardController(gateway)
        // OnboardingViewModel.verifyMemberLoginQr is a thin launch of this controller API.
        controller.verifyMemberLoginQr(FamilyWizardEntry.Onboarding, samplePayload(endpoint))
        advanceUntilIdle()
        assertEquals(1, gateway.verifyCalls)
        assertNotNull(controller.state.value)
        assertFalse(controller.state.value is FamilyWizardState.Editing)
    }

    private fun emptySnapshot() =
        com.lezi.babylog.domain.family.FamilyWizardSnapshot.empty(FamilyWizardEntry.Onboarding)

    private fun samplePayload(
        endpoint: TrustedEndpointProfile =
            TrustedEndpointProfile.systemPki("https://192.168.50.4:8765"),
    ) = MemberLoginQrPayload(
        endpoint = endpoint,
        grant = "grant-0000000000000000000000000000000000000",
        familyName = "乐乐一家",
        memberDisplayName = "妈妈",
        expiresAtEpochSeconds = System.currentTimeMillis() / 1_000 + 3_600,
    )
}
