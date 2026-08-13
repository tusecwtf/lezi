package com.lezi.babylog.domain.family
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.CreateFamilyResult
import com.lezi.babylog.sync.session.CertificateTrustCandidate
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.OwnerLoginResult
import com.lezi.babylog.sync.MemberLoginCheckResult
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.MemberLoginQrResult
import com.lezi.babylog.sync.MemberLoginQrUnavailableException
import com.lezi.babylog.sync.backend.MemberLoginStatus
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FamilyWizardControllerMemberLoginQrTest {
    @Test
    fun memberLoginQrVerifySuccessReadyThenClaimCompletesWithFamilySyncErrorsOnFailure() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
        val payload = memberLoginQrPayload(endpoint)
        val gateway = RecordingFamilyWizardGateway(
            memberLoginQrVerifyResult = SetupProbeResult.Ready(
                endpoint,
                SetupFamilyState.Configured,
            ),
            memberLoginQrClaimResult = Result.success(
                MemberLoginQrResult(memberSession(), InitialFamilyDataRecovery.Complete),
            ),
        )
        val controller = FamilyWizardController(gateway)

        controller.verifyMemberLoginQr(FamilyWizardEntry.Account, payload)

        val ready = controller.state.value as FamilyWizardState.MemberLoginQrReady
        assertThat(ready.payload).isEqualTo(payload)
        assertThat(gateway.events).containsExactly("verify-member-login-qr")
        assertThat(gateway.rememberedEndpoints).isEmpty()

        controller.claimMemberLoginQr(payload, "  Pixel Tablet  ")

        val completed = controller.state.value as FamilyWizardState.Completed
        assertThat(completed.outcome).isEqualTo(
            FamilyWizardOutcome.MemberLoginQrClaimed(
                memberSession(),
                InitialFamilyDataRecovery.Complete,
            ),
        )
        assertThat(gateway.events).containsExactly(
            "verify-member-login-qr",
            "remember",
            "claim-member-login-qr",
        ).inOrder()
        assertThat(gateway.lastMemberLoginQrDeviceName).isEqualTo("Pixel Tablet")
        assertThat(controller.consumeCompletion()).isEqualTo(completed.outcome)
        assertThat(controller.consumeCompletion()).isNull()
    }

    @Test
    fun memberLoginQrVerifyFailureKeepsPayloadForRetryAndCancelClearsWithoutClaim() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
        val payload = memberLoginQrPayload(endpoint)
        val gateway = RecordingFamilyWizardGateway(
            memberLoginQrVerifyResult = SetupProbeResult.Failed.Unreachable,
        )
        val controller = FamilyWizardController(gateway)

        controller.verifyMemberLoginQr(FamilyWizardEntry.Onboarding, payload)
        val failed = controller.state.value as FamilyWizardState.MemberLoginQrVerificationFailed
        assertThat(failed.message).isEqualTo("暂时无法确认二维码中的家庭服务器，请稍后重试")
        assertThat(failed.payload).isEqualTo(payload)

        gateway.memberLoginQrVerifyResult = SetupProbeResult.Ready(
            endpoint,
            SetupFamilyState.Empty,
        )
        controller.verifyMemberLoginQr(FamilyWizardEntry.Onboarding, payload)
        val notConfigured =
            controller.state.value as FamilyWizardState.MemberLoginQrVerificationFailed
        assertThat(notConfigured.message).isEqualTo("这个二维码对应的服务器尚未配置家庭")

        gateway.memberLoginQrVerifyResult = SetupProbeResult.Failed.CertificateChanged
        controller.verifyMemberLoginQr(FamilyWizardEntry.Onboarding, payload)
        assertThat(
            (controller.state.value as FamilyWizardState.MemberLoginQrVerificationFailed).message,
        ).isEqualTo("家庭服务器安全信息不一致，登录已停止")

        controller.cancelMemberLoginQr()
        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.Editing(FamilyWizardSnapshot.empty(FamilyWizardEntry.Onboarding)),
        )
        assertThat(gateway.memberLoginQrClaimCalls).isEqualTo(0)
        assertThat(gateway.rememberedEndpoints).isEmpty()
    }

    @Test
    fun memberLoginQrClaimFailureUsesFamilySyncErrorAndAllowsRetry() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
        val payload = memberLoginQrPayload(endpoint)
        val gateway = RecordingFamilyWizardGateway(
            memberLoginQrVerifyResult = SetupProbeResult.Ready(
                endpoint,
                SetupFamilyState.Configured,
            ),
            memberLoginQrClaimResult = Result.failure(MemberLoginQrUnavailableException()),
        )
        val controller = FamilyWizardController(gateway)
        controller.verifyMemberLoginQr(FamilyWizardEntry.Account, payload)

        controller.claimMemberLoginQr(payload, "Pixel")

        val ready = controller.state.value as FamilyWizardState.MemberLoginQrReady
        assertThat(ready.feedback).isEqualTo("这个二维码已失效，请让管理员重新生成")
        assertThat(gateway.memberLoginQrClaimCalls).isEqualTo(1)

        gateway.memberLoginQrClaimResult = Result.success(
            MemberLoginQrResult(memberSession(), InitialFamilyDataRecovery.RetryRequired),
        )
        controller.claimMemberLoginQr(payload, "Pixel")
        assertThat((controller.state.value as FamilyWizardState.Completed).outcome).isEqualTo(
            FamilyWizardOutcome.MemberLoginQrClaimed(
                memberSession(),
                InitialFamilyDataRecovery.RetryRequired,
            ),
        )
        gateway.recoveryResult = Result.success(Unit)
        controller.retryReclaimedDataRecovery()
        assertThat((controller.state.value as FamilyWizardState.Completed).outcome).isEqualTo(
            FamilyWizardOutcome.MemberLoginQrClaimed(
                memberSession(),
                InitialFamilyDataRecovery.Complete,
            ),
        )
    }

    @Test
    fun memberLoginQrCancelDuringVerifyDropsLateResultAndLocalTrustFailureIsProductCopy() =
        runTest {
            val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
            val payload = memberLoginQrPayload(endpoint)
            val gateway = RecordingFamilyWizardGateway(
                memberLoginQrVerifyResult = SetupProbeResult.Ready(
                    endpoint,
                    SetupFamilyState.Configured,
                ),
            )
            gateway.memberLoginQrVerifyStarted = CompletableDeferred()
            gateway.memberLoginQrVerifyRelease = CompletableDeferred()
            val controller = FamilyWizardController(gateway)

            val verify = launch {
                controller.verifyMemberLoginQr(FamilyWizardEntry.Account, payload)
            }
            gateway.memberLoginQrVerifyStarted!!.await()
            assertThat(controller.state.value)
                .isInstanceOf(FamilyWizardState.VerifyingMemberLoginQr::class.java)
            controller.cancelMemberLoginQr()
            gateway.memberLoginQrVerifyRelease!!.complete(Unit)
            verify.join()
            advanceUntilIdle()

            assertThat(controller.state.value).isEqualTo(
                FamilyWizardState.Editing(FamilyWizardSnapshot.empty(FamilyWizardEntry.Account)),
            )
            assertThat(gateway.rememberedEndpoints).isEmpty()

            gateway.memberLoginQrVerifyStarted = null
            gateway.memberLoginQrVerifyRelease = null
            controller.verifyMemberLoginQr(FamilyWizardEntry.Account, payload)
            gateway.rememberEndpointResult = Result.failure(IllegalStateException("disk full"))
            controller.claimMemberLoginQr(payload, "Pixel")
            val ready = controller.state.value as FamilyWizardState.MemberLoginQrReady
            assertThat(ready.feedback).isEqualTo("无法保存家庭服务器信任信息，请重试")
            assertThat(gateway.memberLoginQrClaimCalls).isEqualTo(0)
        }

    @Test
    fun memberLoginQrCompletionSurvivesConfigRebuildDeliveryViaConsumeCompletion() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
        val payload = memberLoginQrPayload(endpoint)
        val gateway = RecordingFamilyWizardGateway(
            memberLoginQrVerifyResult = SetupProbeResult.Ready(
                endpoint,
                SetupFamilyState.Configured,
            ),
            memberLoginQrClaimResult = Result.success(
                MemberLoginQrResult(memberSession(), InitialFamilyDataRecovery.Complete),
            ),
        )
        val controller = FamilyWizardController(gateway)
        controller.verifyMemberLoginQr(FamilyWizardEntry.Onboarding, payload)
        controller.claimMemberLoginQr(payload, "Pixel")

        // Config rebuild re-collects state; completion is still deliverable once.
        assertThat(controller.state.value).isInstanceOf(FamilyWizardState.Completed::class.java)
        val first = controller.consumeCompletion()
        assertThat(first).isInstanceOf(FamilyWizardOutcome.MemberLoginQrClaimed::class.java)
        assertThat(controller.consumeCompletion()).isNull()
    }

    @Test
    fun memberLoginQrClaimFailureThenCancelForgetsHalfTrustedEndpoint() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
        val payload = memberLoginQrPayload(endpoint)
        val gateway = RecordingFamilyWizardGateway(
            memberLoginQrVerifyResult = SetupProbeResult.Ready(
                endpoint,
                SetupFamilyState.Configured,
            ),
            memberLoginQrClaimResult = Result.failure(MemberLoginQrUnavailableException()),
        )
        val controller = FamilyWizardController(gateway)
        controller.verifyMemberLoginQr(FamilyWizardEntry.Account, payload)
        controller.claimMemberLoginQr(payload, "Pixel")

        val ready = controller.state.value as FamilyWizardState.MemberLoginQrReady
        assertThat(ready.feedback).isEqualTo("这个二维码已失效，请让管理员重新生成")
        assertThat(gateway.events).contains("remember")
        assertThat(gateway.verifiedEndpoint).isEqualTo(endpoint)

        controller.cancelMemberLoginQr()

        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.Editing(FamilyWizardSnapshot.empty(FamilyWizardEntry.Account)),
        )
        assertThat(gateway.events).contains("forget")
        assertThat(gateway.verifiedEndpoint).isNull()
    }

    @Test
    fun memberLoginQrCancelDuringClaimAfterRememberForgetsAndDropsJob() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
        val payload = memberLoginQrPayload(endpoint)
        val gateway = RecordingFamilyWizardGateway(
            memberLoginQrVerifyResult = SetupProbeResult.Ready(
                endpoint,
                SetupFamilyState.Configured,
            ),
        )
        gateway.memberLoginQrClaimStarted = CompletableDeferred()
        gateway.memberLoginQrClaimRelease = CompletableDeferred()
        val controller = FamilyWizardController(gateway)
        controller.verifyMemberLoginQr(FamilyWizardEntry.Onboarding, payload)

        val claim = launch {
            controller.claimMemberLoginQr(payload, "Pixel")
        }
        gateway.memberLoginQrClaimStarted!!.await()
        assertThat(controller.state.value)
            .isInstanceOf(FamilyWizardState.ClaimingMemberLoginQr::class.java)
        assertThat(gateway.verifiedEndpoint).isEqualTo(endpoint)

        controller.cancelMemberLoginQr()
        gateway.memberLoginQrClaimRelease!!.complete(Unit)
        claim.join()
        advanceUntilIdle()

        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.Editing(FamilyWizardSnapshot.empty(FamilyWizardEntry.Onboarding)),
        )
        assertThat(gateway.events).contains("forget")
        assertThat(gateway.verifiedEndpoint).isNull()
    }

    @Test
    fun memberLoginQrSuccessDoesNotForgetRememberedEndpoint() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
        val payload = memberLoginQrPayload(endpoint)
        val gateway = RecordingFamilyWizardGateway(
            memberLoginQrVerifyResult = SetupProbeResult.Ready(
                endpoint,
                SetupFamilyState.Configured,
            ),
            memberLoginQrClaimResult = Result.success(
                MemberLoginQrResult(memberSession(), InitialFamilyDataRecovery.Complete),
            ),
        )
        val controller = FamilyWizardController(gateway)
        controller.verifyMemberLoginQr(FamilyWizardEntry.Account, payload)
        controller.claimMemberLoginQr(payload, "Pixel")

        assertThat(controller.state.value).isInstanceOf(FamilyWizardState.Completed::class.java)
        assertThat(gateway.events).doesNotContain("forget")
        assertThat(gateway.verifiedEndpoint).isEqualTo(endpoint)
    }

    @Test
    fun memberLoginQrInvalidDeviceNameSurfacesReadyFeedbackWithoutClaim() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
        val payload = memberLoginQrPayload(endpoint)
        val gateway = RecordingFamilyWizardGateway(
            memberLoginQrVerifyResult = SetupProbeResult.Ready(
                endpoint,
                SetupFamilyState.Configured,
            ),
        )
        val controller = FamilyWizardController(gateway)
        controller.verifyMemberLoginQr(FamilyWizardEntry.Onboarding, payload)

        controller.claimMemberLoginQr(payload, "   ")

        val ready = controller.state.value as FamilyWizardState.MemberLoginQrReady
        assertThat(ready.feedback).isEqualTo("请填写设备称呼")
        assertThat(gateway.memberLoginQrClaimCalls).isEqualTo(0)
        assertThat(gateway.events).doesNotContain("remember")
    }

    @Test
    fun memberLoginQrClaimFailureThenDeviceNameFailureKeepsForgetOnCancel() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
        val payload = memberLoginQrPayload(endpoint)
        val gateway = RecordingFamilyWizardGateway(
            memberLoginQrVerifyResult = SetupProbeResult.Ready(
                endpoint,
                SetupFamilyState.Configured,
            ),
            memberLoginQrClaimResult = Result.failure(MemberLoginQrUnavailableException()),
        )
        val controller = FamilyWizardController(gateway)
        controller.verifyMemberLoginQr(FamilyWizardEntry.Account, payload)
        controller.claimMemberLoginQr(payload, "Pixel")
        assertThat(gateway.verifiedEndpoint).isEqualTo(endpoint)

        // Second claim fails device-name validation; prior half-trust must still be forgettable.
        controller.claimMemberLoginQr(payload, "")
        val ready = controller.state.value as FamilyWizardState.MemberLoginQrReady
        assertThat(ready.feedback).isEqualTo("请填写设备称呼")

        controller.cancelMemberLoginQr()
        assertThat(gateway.events).contains("forget")
        assertThat(gateway.verifiedEndpoint).isNull()
    }
}
