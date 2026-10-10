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
class FamilyWizardControllerProbeTrustTest {
    @Test
    fun selfSignedCertificateMustBeAcceptedBeforeProbeRoutingAndAnyLoginSecret() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://192.168.77.4:8765")
        val candidate = CertificateTrustCandidate.fromSpki(
            endpoint,
            "stable-nas-key".toByteArray(),
        )
        val trusted = candidate.trustedEndpoint()
        val gateway = RecordingFamilyWizardGateway(
            probeResult = SetupProbeResult.CertificateApprovalRequired(candidate),
            certificateAcceptanceResult = SetupProbeResult.Ready(
                trusted,
                SetupFamilyState.Empty,
            ),
        )
        val controller = FamilyWizardController(gateway)

        controller.connectEndpoint(FamilyWizardEntry.Account, endpoint.origin)

        val approval = controller.state.value as FamilyWizardState.CertificateApprovalRequired
        assertThat(approval.candidate).isEqualTo(candidate)
        assertThat(gateway.events).containsExactly("probe")
        assertThat(gateway.createCalls).isEqualTo(0)
        assertThat(gateway.memberRequestCalls).isEqualTo(0)

        controller.trustCertificate(FamilyWizardEntry.Account, candidate)

        val ready = controller.state.value as FamilyWizardState.EndpointReady
        assertThat(ready.endpoint).isEqualTo(trusted)
        assertThat(ready.snapshot.mode).isEqualTo(FamilyWizardMode.Create)
        assertThat(gateway.events).containsExactly("probe", "accept-certificate").inOrder()
        assertThat(gateway.createCalls).isEqualTo(0)
        assertThat(gateway.memberRequestCalls).isEqualTo(0)
    }

    @Test
    fun rejectingCertificateLeavesNoTrustedProfileOrLateFamilyOperation() = runTest {
        val candidate = CertificateTrustCandidate.fromSpki(
            TrustedEndpointProfile.systemPki("https://192.168.77.4:8765"),
            "unaccepted-nas-key".toByteArray(),
        )
        val gateway = RecordingFamilyWizardGateway(
            probeResult = SetupProbeResult.CertificateApprovalRequired(candidate),
        )
        val controller = FamilyWizardController(gateway)

        controller.connectEndpoint(FamilyWizardEntry.Onboarding, candidate.endpointOrigin)
        controller.keepOffline(FamilyWizardEntry.Onboarding)

        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.Editing(FamilyWizardSnapshot.empty(FamilyWizardEntry.Onboarding)),
        )
        assertThat(gateway.events).containsExactly("probe")
        assertThat(gateway.rememberedEndpoints).isEmpty()
        assertThat(gateway.createCalls).isEqualTo(0)
        assertThat(gateway.memberRequestCalls).isEqualTo(0)
    }

    @Test
    fun successfulEmptyProbeRemembersEndpointThenRoutesToCreateWithoutSendingSecrets() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://family.example.com")
        val gateway = RecordingFamilyWizardGateway(
            probeResult = SetupProbeResult.Ready(
                endpoint = endpoint,
                familyState = SetupFamilyState.Empty,
            ),
        )
        val controller = FamilyWizardController(gateway)

        controller.connectEndpoint(
            entry = FamilyWizardEntry.Onboarding,
            endpointDraft = "  https://family.example.com/  ",
        )

        val ready = controller.state.value as FamilyWizardState.EndpointReady
        assertThat(ready.endpoint).isEqualTo(endpoint)
        assertThat(ready.snapshot.mode).isEqualTo(FamilyWizardMode.Create)
        assertThat(ready.snapshot.step).isEqualTo(FamilyWizardStep.Identity)
        assertThat(gateway.events).containsExactly("probe", "remember").inOrder()
        assertThat(gateway.createCalls).isEqualTo(0)
        assertThat(gateway.memberRequestCalls).isEqualTo(0)
    }

    @Test
    fun configuredProbeRoutesOnlyToJoinAndFailureOrOfflineNeverPersistsDraft() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://family.example.com")
        val gateway = RecordingFamilyWizardGateway(
            probeResult = SetupProbeResult.Ready(
                endpoint = endpoint,
                familyState = SetupFamilyState.Configured,
            ),
        )
        val controller = FamilyWizardController(gateway)

        controller.connectEndpoint(FamilyWizardEntry.Account, endpoint.origin)

        val ready = controller.state.value as FamilyWizardState.EndpointReady
        assertThat(ready.snapshot.mode).isEqualTo(FamilyWizardMode.Join)
        assertThat(ready.snapshot.step).isEqualTo(FamilyWizardStep.Role)
        assertThat(gateway.events).containsExactly("probe", "remember").inOrder()

        gateway.events.clear()
        gateway.probeResult = SetupProbeResult.Failed.Incompatible
        controller.connectEndpoint(FamilyWizardEntry.Account, "https://old.example.com")

        val failure = controller.state.value as FamilyWizardState.EndpointFailure
        assertThat(failure.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.AppUpdateRequired)
        assertThat(
            com.lezi.babylog.core.common.failure.failureExplanation(failure.failureKind).title,
        ).isEqualTo("需要更新乐记")
        assertThat(gateway.events).containsExactly("probe")

        controller.keepOffline(FamilyWizardEntry.Account)

        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.Editing(FamilyWizardSnapshot.empty(FamilyWizardEntry.Account)),
        )
        assertThat(gateway.events).containsExactly("probe")
    }

    @Test
    fun keepOfflineWhileProbeIsInFlightIgnoresItsLateSuccess() = runTest {
        val gateway = RecordingFamilyWizardGateway()
        gateway.probeStarted = CompletableDeferred()
        gateway.probeRelease = CompletableDeferred()
        gateway.probeResult = SetupProbeResult.Ready(
            TrustedEndpointProfile.systemPki("https://family.example.com"),
            SetupFamilyState.Empty,
        )
        val controller = FamilyWizardController(gateway)

        val connecting = async {
            controller.connectEndpoint(
                FamilyWizardEntry.Onboarding,
                "https://family.example.com",
            )
        }
        gateway.probeStarted!!.await()

        controller.keepOffline(FamilyWizardEntry.Onboarding)
        gateway.probeRelease!!.complete(Unit)
        connecting.join()

        assertThat(connecting.isCancelled).isTrue()
        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.Editing(FamilyWizardSnapshot.empty(FamilyWizardEntry.Onboarding)),
        )
        assertThat(gateway.events).containsExactly("probe")
    }

    @Test
    fun keepOfflineWhileVerifiedEndpointIsBeingRememberedCancelsTheWriteAndLateReadyState() =
        runTest {
            val endpoint = TrustedEndpointProfile.systemPki("https://family.example.com")
            val gateway = RecordingFamilyWizardGateway(
                probeResult = SetupProbeResult.Ready(endpoint, SetupFamilyState.Empty),
            ).apply {
                rememberStarted = CompletableDeferred()
                rememberRelease = CompletableDeferred()
            }
            val controller = FamilyWizardController(gateway)
            val connecting = async {
                controller.connectEndpoint(FamilyWizardEntry.Onboarding, endpoint.origin)
            }
            gateway.rememberStarted!!.await()

            controller.keepOffline(FamilyWizardEntry.Onboarding)
            gateway.rememberRelease!!.complete(Unit)
            connecting.join()

            assertThat(connecting.isCancelled).isTrue()
            assertThat(gateway.rememberedEndpoints).isEmpty()
            assertThat(controller.state.value).isEqualTo(
                FamilyWizardState.Editing(FamilyWizardSnapshot.empty(FamilyWizardEntry.Onboarding)),
            )
        }

    @Test
    fun probeTransportFailureLeavesTheWizardIdleAndKeepOfflineStaysAvailable() = runTest {
        val gateway = RecordingFamilyWizardGateway(
            probeResult = SetupProbeResult.Failed.AddressNotFound,
        )
        val controller = FamilyWizardController(gateway)
        controller.connectEndpoint(FamilyWizardEntry.Account, "https://family.example.com")

        val failure = controller.state.value as FamilyWizardState.EndpointFailure
        assertThat(failure.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.AddressNotFound)
        assertThat(failure.failureKind)
            .isNotEqualTo(com.lezi.babylog.core.common.failure.FailureKind.Unreachable)
        assertThat(failure.isBusy).isFalse()

        controller.keepOffline(FamilyWizardEntry.Account)
        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.Editing(FamilyWizardSnapshot.empty(FamilyWizardEntry.Account)),
        )
    }

    @Test
    fun trustCertificatePersistFailureIsNotANetworkKindAndDoesNotAskToChangeAddress() = runTest {
        val candidate = CertificateTrustCandidate.fromSpki(
            TrustedEndpointProfile.systemPki("https://192.168.77.4:8765"),
            "stable-nas-key".toByteArray(),
        )
        val gateway = RecordingFamilyWizardGateway(
            trustCertificateError = com.lezi.babylog.core.common.failure.LocalPersistException(
                IllegalStateException("disk full"),
            ),
        )
        val controller = FamilyWizardController(gateway)

        controller.trustCertificate(FamilyWizardEntry.Account, candidate)

        val failure = controller.state.value as FamilyWizardState.RetryableFailure
        assertThat(failure.message).isEqualTo("保存家庭服务器失败，请重试")
        assertThat(failure.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.LocalSaveFailed)
        val copy = com.lezi.babylog.core.common.failure.failureExplanation(failure.failureKind!!)
        assertThat(copy.category)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureCategory.LocalData)
        assertThat(copy.actions).doesNotContain(
            com.lezi.babylog.core.common.failure.FailureAction.ChangeAddress,
        )
        assertThat(controller.state.value).isNotInstanceOf(
            FamilyWizardState.EndpointFailure::class.java,
        )
    }

    @Test
    fun localTrustPersistFailureIsNotANetworkKind() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://family.example.com")
        val gateway = RecordingFamilyWizardGateway(
            probeResult = SetupProbeResult.Ready(endpoint, SetupFamilyState.Empty),
            rememberEndpointResult = Result.failure(IllegalStateException("disk full")),
        )
        val controller = FamilyWizardController(gateway)
        controller.connectEndpoint(FamilyWizardEntry.Account, endpoint.origin)

        val failure = controller.state.value as FamilyWizardState.RetryableFailure
        assertThat(failure.message).isEqualTo("保存家庭服务器失败，请重试")
        assertThat(failure.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.LocalSaveFailed)
        assertThat(
            com.lezi.babylog.core.common.failure.failureExplanation(failure.failureKind!!)
                .category,
        ).isEqualTo(com.lezi.babylog.core.common.failure.FailureCategory.LocalData)
    }

    @Test
    fun probeFailuresExposeDistinctUserFacingReasons() = runTest {
        val gateway = RecordingFamilyWizardGateway()
        val controller = FamilyWizardController(gateway)
        val failures = listOf(
            SetupProbeResult.Failed.AddressNotFound,
            SetupProbeResult.Failed.Unreachable,
            SetupProbeResult.Failed.NotLezi,
            SetupProbeResult.Failed.Incompatible,
            SetupProbeResult.Failed.Maintenance,
        ).map { failure ->
            gateway.probeResult = failure
            controller.connectEndpoint(FamilyWizardEntry.Account, "https://family.example.com")
            controller.state.value as FamilyWizardState.EndpointFailure
        }

        assertThat(failures.map { it.failureKind }.toSet()).hasSize(failures.size)
        assertThat(failures[0].failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.AddressNotFound)
        assertThat(failures[1].failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.Unreachable)
        assertThat(failures[0].failureKind)
            .isNotEqualTo(failures[1].failureKind)
        assertThat(
            com.lezi.babylog.core.common.failure.failureExplanation(failures[0].failureKind).dialogTitle,
        ).isNotEqualTo(
            com.lezi.babylog.core.common.failure.failureExplanation(failures[1].failureKind).dialogTitle,
        )
    }

    @Test
    fun submitUsesOnlyTheVerifiedOriginAndNeverCallsGatewayForAMutatedDraft() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
        val gateway = RecordingFamilyWizardGateway(
            probeResult = SetupProbeResult.Ready(endpoint, SetupFamilyState.Empty),
        )
        val controller = FamilyWizardController(gateway)
        controller.connectEndpoint(FamilyWizardEntry.Account, endpoint.origin)
        controller.begin(snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Create))

        controller.submit(
            snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Create).copy(
                host = "attacker.example.com",
            ),
            "must-not-leave-device",
        )

        val failure = controller.state.value as FamilyWizardState.RetryableFailure
        assertThat(failure.message).contains("重新确认家庭服务器")
        assertThat(gateway.createCalls).isEqualTo(0)
        assertThat(gateway.events).doesNotContain("save")
        assertThat(gateway.events).doesNotContain("create")
    }
}
