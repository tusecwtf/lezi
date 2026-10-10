package com.lezi.babylog.domain.family
import com.lezi.babylog.sync.session.toPresentation
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
                memberSession().toPresentation(),
                InitialFamilyDataRecovery.Complete,
            ),
        )
        assertThat(gateway.events).containsExactly(
            "verify-member-login-qr",
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
        assertThat(failed.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.Unreachable)
        assertThat(failed.payload).isEqualTo(payload)

        gateway.memberLoginQrVerifyResult = SetupProbeResult.Ready(
            endpoint,
            SetupFamilyState.Empty,
        )
        controller.verifyMemberLoginQr(FamilyWizardEntry.Onboarding, payload)
        val notConfigured =
            controller.state.value as FamilyWizardState.MemberLoginQrVerificationFailed
        assertThat(notConfigured.message).isEqualTo("这个二维码对应的服务器尚未配置家庭")
        assertThat(notConfigured.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.ServerHasNoFamily)

        gateway.memberLoginQrVerifyResult = SetupProbeResult.Failed.CertificateChanged
        controller.verifyMemberLoginQr(FamilyWizardEntry.Onboarding, payload)
        val certChanged =
            controller.state.value as FamilyWizardState.MemberLoginQrVerificationFailed
        assertThat(certChanged.message).isEqualTo("家庭服务器安全信息不一致，登录已停止")
        assertThat(certChanged.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.CertificateChanged)

        controller.cancelMemberLoginQr()
        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.Editing(FamilyWizardSnapshot.empty(FamilyWizardEntry.Onboarding)),
        )
        assertThat(gateway.memberLoginQrClaimCalls).isEqualTo(0)
        assertThat(gateway.rememberedEndpoints).isEmpty()
    }

    @Test
    fun memberLoginQrClaimFailureUsesCatalogKindAndAllowsRetry() = runTest {
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
        assertThat(ready.feedback).isEmpty()
        assertThat(ready.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.QrExpired)
        assertThat(
            com.lezi.babylog.core.common.failure.failureExplanation(ready.failureKind!!).title,
        ).isEqualTo("这个二维码不能用了")
        assertThat(gateway.memberLoginQrClaimCalls).isEqualTo(1)

        gateway.memberLoginQrClaimResult = Result.success(
            MemberLoginQrResult(memberSession(), InitialFamilyDataRecovery.RetryRequired()),
        )
        controller.claimMemberLoginQr(payload, "Pixel")
        assertThat((controller.state.value as FamilyWizardState.Completed).outcome).isEqualTo(
            FamilyWizardOutcome.MemberLoginQrClaimed(
                memberSession().toPresentation(),
                InitialFamilyDataRecovery.RetryRequired(),
            ),
        )
        gateway.recoveryResult = Result.success(Unit)
        controller.retryReclaimedDataRecovery()
        assertThat((controller.state.value as FamilyWizardState.Completed).outcome).isEqualTo(
            FamilyWizardOutcome.MemberLoginQrClaimed(
                memberSession().toPresentation(),
                InitialFamilyDataRecovery.NotRequired,
            ),
        )
    }

    @Test
    fun memberLoginQrCancelDuringVerifyDropsLateResultAndClaimFailureIsProductCopy() =
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
            gateway.memberLoginQrClaimResult = Result.failure(MemberLoginQrUnavailableException())
            controller.claimMemberLoginQr(payload, "Pixel")
            val ready = controller.state.value as FamilyWizardState.MemberLoginQrReady
            assertThat(ready.feedback).isEmpty()
            assertThat(ready.failureKind)
                .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.QrExpired)
            assertThat(gateway.memberLoginQrClaimCalls).isEqualTo(1)
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
    fun failedQrClaimPreservesAbsentOrExplicitPriorTrustAcrossCancelAndNextEntry() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
        val priorProfiles = listOf(
            null,
            TrustedEndpointProfile.systemPki("https://previous.home"),
            TrustedEndpointProfile.tofuSpki("https://nas.home", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
        )
        for (priorTrust in priorProfiles) {
            val gateway = RecordingFamilyWizardGateway(
                memberLoginQrVerifyResult = SetupProbeResult.Ready(endpoint, SetupFamilyState.Configured),
                memberLoginQrClaimResult = Result.failure(MemberLoginQrUnavailableException()),
            ).apply { verifiedEndpoint = priorTrust }
            val controller = FamilyWizardController(gateway)
            val payload = memberLoginQrPayload(endpoint)
            controller.verifyMemberLoginQr(FamilyWizardEntry.Account, payload)
            controller.claimMemberLoginQr(payload, "Pixel")

            assertThat(controller.state.value).isInstanceOf(FamilyWizardState.MemberLoginQrReady::class.java)
            assertThat(gateway.verifiedEndpoint).isEqualTo(priorTrust)
            controller.cancelMemberLoginQr()
            controller.begin(FamilyWizardSnapshot.empty(FamilyWizardEntry.Account))
            controller.verifyMemberLoginQr(FamilyWizardEntry.Account, payload)

            assertThat(gateway.verifiedEndpoint).isEqualTo(priorTrust)
            assertThat(gateway.events).doesNotContain("remember")
            assertThat(gateway.events).doesNotContain("forget")
            assertThat(gateway.memberLoginQrClaimCalls).isEqualTo(1)
        }
    }

    @Test
    fun cancellingAnOldQrClaimPreservesTheExactNewerTrustInstalledWhileItWasBlocked() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
        val newerTrust = TrustedEndpointProfile.tofuSpki(
            "https://replacement.home", "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        )
        val gateway = RecordingFamilyWizardGateway(
            memberLoginQrVerifyResult = SetupProbeResult.Ready(endpoint, SetupFamilyState.Configured),
        ).apply {
            memberLoginQrClaimStarted = CompletableDeferred()
            memberLoginQrClaimRelease = CompletableDeferred()
        }
        val priorTrust = gateway.verifiedEndpoint
        val controller = FamilyWizardController(gateway)
        val payload = memberLoginQrPayload(endpoint)
        controller.verifyMemberLoginQr(FamilyWizardEntry.Onboarding, payload)
        val claim = launch { controller.claimMemberLoginQr(payload, "Pixel") }
        gateway.memberLoginQrClaimStarted!!.await()
        assertThat(gateway.verifiedEndpoint).isEqualTo(priorTrust)
        gateway.verifiedEndpoint = newerTrust // Another owner completed a trust change.

        controller.cancelMemberLoginQr()
        gateway.memberLoginQrClaimRelease!!.complete(Unit)
        claim.join()
        controller.begin(FamilyWizardSnapshot.empty(FamilyWizardEntry.Onboarding))
        controller.verifyMemberLoginQr(FamilyWizardEntry.Onboarding, payload)

        assertThat(gateway.verifiedEndpoint).isEqualTo(newerTrust)
        assertThat(gateway.events).doesNotContain("remember")
        assertThat(gateway.events).doesNotContain("forget")
    }

    @Test
    fun memberLoginQrClaimFailureThenCancelDoesNotWriteOrForgetTrust() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
        val payload = memberLoginQrPayload(endpoint)
        val gateway = RecordingFamilyWizardGateway(
            memberLoginQrVerifyResult = SetupProbeResult.Ready(
                endpoint,
                SetupFamilyState.Configured,
            ),
            memberLoginQrClaimResult = Result.failure(MemberLoginQrUnavailableException()),
        )
        // This shared fixture starts with an already-trusted System PKI endpoint.
        val priorTrust = gateway.verifiedEndpoint
        assertThat(priorTrust).isEqualTo(endpoint)
        val controller = FamilyWizardController(gateway)
        controller.verifyMemberLoginQr(FamilyWizardEntry.Account, payload)
        controller.claimMemberLoginQr(payload, "Pixel")

        val ready = controller.state.value as FamilyWizardState.MemberLoginQrReady
        assertThat(ready.feedback).isEmpty()
        assertThat(ready.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.QrExpired)
        assertThat(gateway.events).doesNotContain("remember")
        assertThat(gateway.verifiedEndpoint).isEqualTo(priorTrust)

        controller.cancelMemberLoginQr()

        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.Editing(FamilyWizardSnapshot.empty(FamilyWizardEntry.Account)),
        )
        assertThat(gateway.events).doesNotContain("forget")
        assertThat(gateway.verifiedEndpoint).isEqualTo(priorTrust)
    }

    @Test
    fun memberLoginQrCancelDuringClaimDropsJobWithoutTrustWrites() = runTest {
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
        // This shared fixture starts with an already-trusted System PKI endpoint.
        val priorTrust = gateway.verifiedEndpoint
        assertThat(priorTrust).isEqualTo(endpoint)
        val controller = FamilyWizardController(gateway)
        controller.verifyMemberLoginQr(FamilyWizardEntry.Onboarding, payload)

        val claim = launch {
            controller.claimMemberLoginQr(payload, "Pixel")
        }
        gateway.memberLoginQrClaimStarted!!.await()
        assertThat(controller.state.value)
            .isInstanceOf(FamilyWizardState.ClaimingMemberLoginQr::class.java)
        assertThat(gateway.verifiedEndpoint).isEqualTo(priorTrust)

        controller.cancelMemberLoginQr()
        gateway.memberLoginQrClaimRelease!!.complete(Unit)
        claim.join()
        advanceUntilIdle()

        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.Editing(FamilyWizardSnapshot.empty(FamilyWizardEntry.Onboarding)),
        )
        assertThat(gateway.events).doesNotContain("remember")
        assertThat(gateway.events).doesNotContain("forget")
        assertThat(gateway.verifiedEndpoint).isEqualTo(priorTrust)
    }

    @Test
    fun memberLoginQrSuccessDoesNotRunTrustCleanup() = runTest {
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
        // This shared fixture starts with an already-trusted System PKI endpoint.
        val priorTrust = gateway.verifiedEndpoint
        assertThat(priorTrust).isEqualTo(endpoint)
        val controller = FamilyWizardController(gateway)
        controller.verifyMemberLoginQr(FamilyWizardEntry.Account, payload)
        controller.claimMemberLoginQr(payload, "Pixel")

        assertThat(controller.state.value).isInstanceOf(FamilyWizardState.Completed::class.java)
        assertThat(gateway.events).doesNotContain("remember")
        assertThat(gateway.events).doesNotContain("forget")
        assertThat(gateway.verifiedEndpoint).isEqualTo(priorTrust)
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
    fun memberLoginQrClaimTransportFailureLeavesExistingTrustUntouched() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
        val payload = memberLoginQrPayload(endpoint)
        val gateway = RecordingFamilyWizardGateway(
            memberLoginQrVerifyResult = SetupProbeResult.Ready(
                endpoint,
                SetupFamilyState.Configured,
            ),
            memberLoginQrClaimResult = Result.failure(
                com.lezi.babylog.sync.backend.deadline.FamilyHttpException(
                    com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind.ResponseTimedOut,
                ),
            ),
        )
        // This shared fixture starts with an already-trusted System PKI endpoint.
        val priorTrust = gateway.verifiedEndpoint
        assertThat(priorTrust).isEqualTo(endpoint)
        val controller = FamilyWizardController(gateway)
        controller.verifyMemberLoginQr(FamilyWizardEntry.Account, payload)
        controller.claimMemberLoginQr(payload, "Pixel")

        val ready = controller.state.value as FamilyWizardState.MemberLoginQrReady
        assertThat(ready.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.ResponseTimedOut)
        assertThat(ready.isBusy).isFalse()
        assertThat(gateway.verifiedEndpoint).isEqualTo(priorTrust)

        controller.cancelMemberLoginQr()
        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.Editing(FamilyWizardSnapshot.empty(FamilyWizardEntry.Account)),
        )
        assertThat(gateway.events).doesNotContain("remember")
        assertThat(gateway.events).doesNotContain("forget")
        assertThat(gateway.verifiedEndpoint).isEqualTo(priorTrust)
    }

    @Test
    fun memberLoginQrVerifyTransportFailureLeavesFailedStateSoKeepOfflineCanLeave() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
        val payload = memberLoginQrPayload(endpoint)
        val gateway = RecordingFamilyWizardGateway(
            memberLoginQrVerifyResult = SetupProbeResult.Failed.Unreachable,
        )
        val controller = FamilyWizardController(gateway)
        controller.verifyMemberLoginQr(FamilyWizardEntry.Onboarding, payload)

        val failed = controller.state.value as FamilyWizardState.MemberLoginQrVerificationFailed
        assertThat(failed.payload).isEqualTo(payload)
        assertThat(failed.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.Unreachable)
        assertThat(failed.isBusy).isFalse()

        controller.keepOffline(FamilyWizardEntry.Onboarding)
        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.Editing(FamilyWizardSnapshot.empty(FamilyWizardEntry.Onboarding)),
        )
        assertThat(gateway.rememberedEndpoints).isEmpty()
    }

    @Test
    fun memberLoginQrClaimAndDeviceNameFailuresDoNotCreateCleanupOwnership() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
        val payload = memberLoginQrPayload(endpoint)
        val gateway = RecordingFamilyWizardGateway(
            memberLoginQrVerifyResult = SetupProbeResult.Ready(
                endpoint,
                SetupFamilyState.Configured,
            ),
            memberLoginQrClaimResult = Result.failure(MemberLoginQrUnavailableException()),
        )
        // This shared fixture starts with an already-trusted System PKI endpoint.
        val priorTrust = gateway.verifiedEndpoint
        assertThat(priorTrust).isEqualTo(endpoint)
        val controller = FamilyWizardController(gateway)
        controller.verifyMemberLoginQr(FamilyWizardEntry.Account, payload)
        controller.claimMemberLoginQr(payload, "Pixel")
        assertThat(gateway.verifiedEndpoint).isEqualTo(priorTrust)

        // Invalid retry has no temporary trust to clean up.
        controller.claimMemberLoginQr(payload, "")
        val ready = controller.state.value as FamilyWizardState.MemberLoginQrReady
        assertThat(ready.feedback).isEqualTo("请填写设备称呼")

        controller.cancelMemberLoginQr()
        assertThat(gateway.events).doesNotContain("remember")
        assertThat(gateway.events).doesNotContain("forget")
        assertThat(gateway.verifiedEndpoint).isEqualTo(priorTrust)
    }
}
