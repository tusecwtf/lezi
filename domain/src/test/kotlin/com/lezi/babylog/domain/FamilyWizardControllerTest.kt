package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.CreateFamilyResult
import com.lezi.babylog.sync.CertificateTrustCandidate
import com.lezi.babylog.sync.FamilyEndpointConfig
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.OwnerLoginResult
import com.lezi.babylog.sync.MemberLoginCheckResult
import com.lezi.babylog.sync.MemberLoginStatus
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.SetupFamilyState
import com.lezi.babylog.sync.SetupProbeResult
import com.lezi.babylog.sync.SyncSession
import com.lezi.babylog.sync.TrustedEndpointProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FamilyWizardControllerTest {
    @Test
    fun selfSignedCertificateMustBeAcceptedBeforeProbeRoutingAndAnyLoginSecret() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://192.168.50.4:8765")
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
            TrustedEndpointProfile.systemPki("https://192.168.50.4:8765"),
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
        assertThat(failure.message).isEqualTo("家庭服务器需要更新")
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
    fun probeFailuresExposeDistinctUserFacingReasons() = runTest {
        val gateway = RecordingFamilyWizardGateway()
        val controller = FamilyWizardController(gateway)
        val messages = listOf(
            SetupProbeResult.Failed.Unreachable,
            SetupProbeResult.Failed.NotLezi,
            SetupProbeResult.Failed.Incompatible,
            SetupProbeResult.Failed.Maintenance,
        ).map { failure ->
            gateway.probeResult = failure
            controller.connectEndpoint(FamilyWizardEntry.Account, "https://family.example.com")
            (controller.state.value as FamilyWizardState.EndpointFailure).message
        }

        assertThat(messages.toSet()).hasSize(messages.size)
    }

    @Test
    fun sameCreateInputFromBothEntriesProducesSameRequestAndCreatedOutcome() = runTest {
        val requests = FamilyWizardEntry.entries.map { entry ->
            val gateway = RecordingFamilyWizardGateway(
                createResult = Result.success(
                    CreateFamilyResult(ownerSession(), reclaimed = false),
                ),
            )
            val controller = FamilyWizardController(gateway)

            controller.submit(snapshot(entry, FamilyWizardMode.Create), "bootstrap-once")

            val completed = controller.state.value as FamilyWizardState.Completed
            assertThat(completed.outcome)
                .isEqualTo(FamilyWizardOutcome.Created(ownerSession()))
            assertThat(controller.consumeCompletion()).isEqualTo(completed.outcome)
            assertThat(controller.consumeCompletion()).isNull()
            gateway.request
        }

        assertThat(requests[0]).isEqualTo(requests[1])
        assertThat(requests.first()!!.bootstrapSecret).isEqualTo("bootstrap-once")
    }

    @Test
    fun sameCreateInputFromBothEntriesProducesSameReclaimedOutcome() = runTest {
        val outcomes = FamilyWizardEntry.entries.map { entry ->
            val gateway = RecordingFamilyWizardGateway(
                createResult = Result.success(
                    CreateFamilyResult(
                        session = ownerSession(),
                        reclaimed = true,
                        dataRecovery = InitialFamilyDataRecovery.Complete,
                    ),
                ),
            )
            val controller = FamilyWizardController(gateway)

            controller.submit(snapshot(entry, FamilyWizardMode.Create), "bootstrap-once")

            (controller.state.value as FamilyWizardState.Completed).outcome
        }

        assertThat(outcomes).containsExactly(
            FamilyWizardOutcome.Reclaimed(
                ownerSession(),
                InitialFamilyDataRecovery.Complete,
            ),
            FamilyWizardOutcome.Reclaimed(
                ownerSession(),
                InitialFamilyDataRecovery.Complete,
            ),
        )
    }


    @Test
    fun configuredFamilyOffersOwnerRoleAndBothEntriesShareLoginAndTakeoverContract() = runTest {
        val requests = FamilyWizardEntry.entries.flatMap { entry ->
            listOf(false, true).map { takeover ->
                val gateway = RecordingFamilyWizardGateway(
                    ownerLoginResult = Result.success(
                        OwnerLoginResult(
                            ownerSession(),
                            InitialFamilyDataRecovery.Complete,
                        ),
                    ),
                )
                val controller = FamilyWizardController(gateway)
                val role = snapshot(entry, FamilyWizardMode.Join).copy(
                    step = FamilyWizardStep.Role,
                    joinRole = FamilyWizardJoinRole.Owner,
                )

                controller.submit(
                    snapshot = role.copy(step = FamilyWizardStep.Identity),
                    bootstrapSecret = "root-password-secret",
                    ownerTakeover = takeover,
                )

                val completed = controller.state.value as FamilyWizardState.Completed
                assertThat(completed.outcome).isEqualTo(
                    FamilyWizardOutcome.OwnerLoggedIn(
                        ownerSession(),
                        InitialFamilyDataRecovery.Complete,
                    ),
                )
                assertThat(controller.state.value.toString())
                    .doesNotContain("root-password-secret")
                requireNotNull(gateway.ownerLoginRequest)
            }
        }

        assertThat(requests.map(OwnerLoginRequest::takeover))
            .containsExactly(false, true, false, true)
        assertThat(requests.map(OwnerLoginRequest::deviceName).toSet())
            .containsExactly("Pixel")
        assertThat(requests.map(OwnerLoginRequest::rootPassword).toSet())
            .containsExactly("root-password-secret")
    }

    @Test
    fun validationAndGatewayFailuresStayOnRecoverableStepsWithRetainedInput() = runTest {
        val gateway = RecordingFamilyWizardGateway()
        val controller = FamilyWizardController(gateway)
        val invalidEndpoint = snapshot(
            FamilyWizardEntry.Onboarding,
            FamilyWizardMode.Create,
        ).copy(host = "")

        assertThat(
            FamilyWizardEntry.entries.map { entry ->
                familyWizardEndpointValidationError(invalidEndpoint.copy(entry = entry))
            },
        ).containsExactly("请填写服务器主机", "请填写服务器主机")

        controller.submit(invalidEndpoint, "not-retained")

        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.RetryableFailure(
                snapshot = invalidEndpoint.copy(step = FamilyWizardStep.Endpoint),
                message = "请填写服务器主机",
                committedOutcome = null,
            ),
        )
        assertThat(gateway.events).isEmpty()

        val invalidIdentity = invalidEndpoint.copy(
            host = "nas.home",
            displayName = "  ",
            step = FamilyWizardStep.Identity,
        )
        controller.submit(invalidIdentity, "not-retained")

        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.RetryableFailure(
                snapshot = invalidIdentity,
                message = "请填写家庭称呼",
                committedOutcome = null,
            ),
        )

        gateway.createResult = Result.failure(IllegalArgumentException("初始化口令不正确"))
        val valid = snapshot(FamilyWizardEntry.Onboarding, FamilyWizardMode.Create)
        controller.submit(valid, "secret")

        val failure = controller.state.value as FamilyWizardState.RetryableFailure
        assertThat(failure.snapshot).isEqualTo(valid.copy(step = FamilyWizardStep.Identity))
        assertThat(failure.message).isEqualTo("初始化口令不正确")
        assertThat(failure.committedOutcome).isNull()

        gateway.createResult = Result.success(CreateFamilyResult(ownerSession(), reclaimed = false))
        controller.submit(valid.copy(familyName = "新家庭"), "new-secret")
        assertThat((controller.state.value as FamilyWizardState.Completed).outcome)
            .isEqualTo(FamilyWizardOutcome.Created(ownerSession()))
    }

    @Test
    fun busyControllerDeduplicatesTapsAndNeverRetainsSecret() = runTest {
        val gateway = BlockingFamilyWizardGateway()
        val controller = FamilyWizardController(gateway)
        val input = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Create)

        val first = async { controller.submit(input, "super-secret") }
        gateway.started.await()
        val second = async { controller.submit(input, "other-secret") }
        second.await()

        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.Submitting(input.copy(step = FamilyWizardStep.Identity)),
        )
        assertThat(controller.state.value.toString()).doesNotContain("super-secret")
        assertThat(controller.state.value.toString()).doesNotContain("other-secret")
        assertThat(gateway.createCalls).isEqualTo(1)

        gateway.release.complete(Unit)
        first.await()
        assertThat(gateway.createCalls).isEqualTo(1)
    }

    @Test
    fun freshCreateRecoveryFailureKeepsCommittedSessionAndRetriesWithoutCreate() = runTest {
        val gateway = RecordingFamilyWizardGateway(
            createResult = Result.success(
                CreateFamilyResult(
                    session = ownerSession(),
                    reclaimed = false,
                    dataRecovery = InitialFamilyDataRecovery.RetryRequired,
                ),
            ),
            recoveryResult = Result.failure(IllegalStateException("offline")),
        )
        val controller = FamilyWizardController(gateway)
        controller.submit(snapshot(FamilyWizardEntry.Onboarding, FamilyWizardMode.Create), "once")

        controller.retryReclaimedDataRecovery()

        val failure = controller.state.value as FamilyWizardState.RetryableFailure
        assertThat(failure.committedOutcome).isEqualTo(
            FamilyWizardOutcome.Created(
                ownerSession(),
                InitialFamilyDataRecovery.RetryRequired,
            ),
        )
        assertThat(failure.message)
            .isEqualTo("首次同步失败，可继续离线使用并稍后重试")
        assertThat(gateway.createCalls).isEqualTo(1)

        gateway.recoveryResult = Result.success(Unit)
        controller.retryReclaimedDataRecovery()

        assertThat((controller.state.value as FamilyWizardState.Completed).outcome).isEqualTo(
            FamilyWizardOutcome.Created(
                ownerSession(),
                InitialFamilyDataRecovery.Complete,
            ),
        )
        assertThat(gateway.createCalls).isEqualTo(1)
        assertThat(gateway.recoveryCalls).isEqualTo(2)
    }

    @Test
    fun accountCanBeginANewWizardSessionAfterAnEarlierCompletionWasConsumed() = runTest {
        val gateway = RecordingFamilyWizardGateway()
        val controller = FamilyWizardController(gateway)
        val create = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Create)
        controller.submit(create, "once")
        assertThat(controller.consumeCompletion()).isNotNull()

        val anotherCreate = create.copy(displayName = "爸爸")
        controller.begin(anotherCreate)
        controller.submit(anotherCreate, "twice")

        assertThat((controller.state.value as FamilyWizardState.Completed).outcome)
            .isEqualTo(FamilyWizardOutcome.Created(ownerSession()))
        assertThat(gateway.createCalls).isEqualTo(2)
    }

    @Test
    fun explicitMemberJoinCreatesAPendingRequestAndChecksWithoutLegacyInviteJoin() = runTest {
        val gateway = RecordingFamilyWizardGateway()
        val controller = FamilyWizardController(gateway)
        val input = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Join).copy(
            joinRole = FamilyWizardJoinRole.Member,
        )

        controller.submit(input)

        val waiting = controller.state.value as FamilyWizardState.WaitingForMemberApproval
        assertThat(waiting.request).isEqualTo(gateway.pendingRequest)
        assertThat(gateway.events).containsExactly("save", "member-request").inOrder()
        assertThat(gateway.memberRequestCalls).isEqualTo(1)

        gateway.memberCheckResult = Result.success(MemberLoginCheckResult.Waiting(waiting.request))
        controller.checkMemberApproval()
        assertThat(controller.state.value).isEqualTo(waiting)
    }

    @Test
    fun approvedMemberPublishesCommittedSessionAndRetriesOnlyDataRecovery() = runTest {
        val gateway = RecordingFamilyWizardGateway()
        val controller = FamilyWizardController(gateway)
        val input = snapshot(FamilyWizardEntry.Onboarding, FamilyWizardMode.Join).copy(
            joinRole = FamilyWizardJoinRole.Member,
        )
        controller.submit(input)
        gateway.memberCheckResult = Result.success(
            MemberLoginCheckResult.Joined(
                memberSession(),
                InitialFamilyDataRecovery.RetryRequired,
            ),
        )

        controller.checkMemberApproval()

        assertThat((controller.state.value as FamilyWizardState.Completed).outcome).isEqualTo(
            FamilyWizardOutcome.MemberApproved(
                memberSession(),
                InitialFamilyDataRecovery.RetryRequired,
            ),
        )
        gateway.recoveryResult = Result.success(Unit)
        controller.retryReclaimedDataRecovery()
        assertThat((controller.state.value as FamilyWizardState.Completed).outcome).isEqualTo(
            FamilyWizardOutcome.MemberApproved(
                memberSession(),
                InitialFamilyDataRecovery.Complete,
            ),
        )
        assertThat(gateway.memberRequestCalls).isEqualTo(1)
    }

    @Test
    fun foregroundMemberCheckCompletesAnOpenWaitingWizardWithoutReplayingStaleResults() = runTest {
        val gateway = RecordingFamilyWizardGateway()
        val controller = FamilyWizardController(gateway)
        val input = snapshot(FamilyWizardEntry.Onboarding, FamilyWizardMode.Join).copy(
            joinRole = FamilyWizardJoinRole.Member,
        )
        controller.submit(input)

        controller.observeMemberLoginCheck(
            MemberLoginCheckResult.Joined(
                memberSession(),
                InitialFamilyDataRecovery.RetryRequired,
            ),
        )

        val completed = controller.state.value
        assertThat((completed as FamilyWizardState.Completed).outcome).isEqualTo(
            FamilyWizardOutcome.MemberApproved(
                memberSession(),
                InitialFamilyDataRecovery.RetryRequired,
            ),
        )
        controller.observeMemberLoginCheck(
            MemberLoginCheckResult.Terminal(MemberLoginStatus.Expired),
        )
        assertThat(controller.state.value).isEqualTo(completed)
    }

    @Test
    fun rejectedAndCancelledMemberRequestsRemainOfflineAndRecoverable() = runTest {
        val gateway = RecordingFamilyWizardGateway()
        val input = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Join).copy(
            joinRole = FamilyWizardJoinRole.Member,
        )
        val rejected = FamilyWizardController(gateway)
        rejected.submit(input)
        gateway.memberCheckResult = Result.success(
            MemberLoginCheckResult.Terminal(MemberLoginStatus.Rejected),
        )
        rejected.checkMemberApproval()
        assertThat((rejected.state.value as FamilyWizardState.RetryableFailure).message)
            .isEqualTo("管理员已拒绝这条加入申请")

        val cancelledGateway = RecordingFamilyWizardGateway()
        val cancelled = FamilyWizardController(cancelledGateway)
        cancelled.submit(input)
        cancelled.cancelMemberApproval()
        assertThat(cancelled.state.value).isInstanceOf(FamilyWizardState.Editing::class.java)
        assertThat(cancelledGateway.cancelMemberCalls).isEqualTo(1)
    }


    private fun snapshot(
        entry: FamilyWizardEntry,
        mode: FamilyWizardMode,
    ) = FamilyWizardSnapshot(
        entry = entry,
        mode = mode,
        step = FamilyWizardStep.Identity,
        host = "nas.home",
        portText = "443",
        scheme = "https",
        displayName = "  妈妈  ",
        familyName = "  我家  ",
        deviceName = "  Pixel  ",
    )
}

private data class CreateRequest(
    val config: FamilyEndpointConfig,
    val displayName: String,
    val deviceName: String,
    val bootstrapSecret: String,
    val familyName: String?,
)

private data class OwnerLoginRequest(
    val config: FamilyEndpointConfig,
    val deviceName: String,
    val rootPassword: String,
    val takeover: Boolean,
)

private class RecordingFamilyWizardGateway(
    var createResult: Result<CreateFamilyResult> = Result.success(
        CreateFamilyResult(ownerSession(), reclaimed = false),
    ),
    var ownerLoginResult: Result<OwnerLoginResult> = Result.success(
        OwnerLoginResult(ownerSession(), InitialFamilyDataRecovery.Complete),
    ),
    var recoveryResult: Result<Unit> = Result.success(Unit),
    var probeResult: SetupProbeResult = SetupProbeResult.Failed.Unreachable,
    var certificateAcceptanceResult: SetupProbeResult = SetupProbeResult.Failed.Unreachable,
) : FamilyWizardGateway {
    val pendingRequest = PendingMemberLogin(
        requestId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        displayName = "妈妈",
        deviceName = "Pixel",
        expiresAtEpochSeconds = 1_753_504_800,
    )
    var memberCheckResult: Result<MemberLoginCheckResult> = Result.success(
        MemberLoginCheckResult.Waiting(pendingRequest),
    )
    var memberRequestCalls = 0
    var cancelMemberCalls = 0
    val events = mutableListOf<String>()
    var request: CreateRequest? = null
    var ownerLoginRequest: OwnerLoginRequest? = null
    var createCalls = 0
    var recoveryCalls = 0
    var probeStarted: CompletableDeferred<Unit>? = null
    var probeRelease: CompletableDeferred<Unit>? = null
    var rememberStarted: CompletableDeferred<Unit>? = null
    var rememberRelease: CompletableDeferred<Unit>? = null
    val rememberedEndpoints = mutableListOf<TrustedEndpointProfile>()

    override suspend fun probeEndpoint(endpointDraft: String): SetupProbeResult {
        events += "probe"
        probeStarted?.complete(Unit)
        probeRelease?.await()
        return probeResult
    }

    override suspend fun trustCertificate(candidate: CertificateTrustCandidate): SetupProbeResult {
        events += "accept-certificate"
        return certificateAcceptanceResult
    }

    override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile): Result<Unit> {
        events += "remember"
        rememberStarted?.complete(Unit)
        rememberRelease?.await()
        rememberedEndpoints += endpoint
        return Result.success(Unit)
    }

    override suspend fun forgetEndpoint(): Result<Unit> {
        events += "forget"
        return Result.success(Unit)
    }

    override suspend fun saveEndpointConfig(config: FamilyEndpointConfig): Result<Unit> {
        events += "save"
        return Result.success(Unit)
    }

    override suspend fun createFamily(
        config: FamilyEndpointConfig,
        displayName: String,
        deviceName: String,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult> {
        events += "create"
        createCalls += 1
        request = CreateRequest(config, displayName, deviceName, bootstrapSecret, familyName)
        return createResult
    }

    override suspend fun ownerLogin(
        config: FamilyEndpointConfig,
        deviceName: String,
        rootPassword: String,
        takeover: Boolean,
    ): Result<OwnerLoginResult> {
        events += "owner-login"
        ownerLoginRequest = OwnerLoginRequest(config, deviceName, rootPassword, takeover)
        return ownerLoginResult
    }

    override suspend fun requestMemberLogin(
        config: FamilyEndpointConfig,
        displayName: String,
        deviceName: String,
    ): Result<PendingMemberLogin> {
        events += "member-request"
        memberRequestCalls++
        return Result.success(
            pendingRequest.copy(displayName = displayName, deviceName = deviceName),
        )
    }

    override suspend fun checkMemberLogin(): Result<MemberLoginCheckResult> = memberCheckResult

    override suspend fun cancelMemberLogin(): Result<Unit> {
        cancelMemberCalls++
        return Result.success(Unit)
    }

    override suspend fun retryReclaimedDataRecovery(): Result<Unit> {
        events += "recover"
        recoveryCalls += 1
        return recoveryResult
    }
}

private class BlockingFamilyWizardGateway : FamilyWizardGateway {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var createCalls = 0

    override suspend fun probeEndpoint(endpointDraft: String): SetupProbeResult =
        SetupProbeResult.Failed.Unreachable

    override suspend fun trustCertificate(candidate: CertificateTrustCandidate): SetupProbeResult =
        SetupProbeResult.Failed.Unreachable

    override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile): Result<Unit> =
        Result.success(Unit)

    override suspend fun forgetEndpoint(): Result<Unit> = Result.success(Unit)

    override suspend fun saveEndpointConfig(config: FamilyEndpointConfig): Result<Unit> =
        Result.success(Unit)

    override suspend fun createFamily(
        config: FamilyEndpointConfig,
        displayName: String,
        deviceName: String,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult> {
        createCalls += 1
        started.complete(Unit)
        release.await()
        return Result.success(CreateFamilyResult(ownerSession(), reclaimed = false))
    }

    override suspend fun ownerLogin(
        config: FamilyEndpointConfig,
        deviceName: String,
        rootPassword: String,
        takeover: Boolean,
    ): Result<OwnerLoginResult> = Result.success(
        OwnerLoginResult(ownerSession(), InitialFamilyDataRecovery.Complete),
    )

    override suspend fun retryReclaimedDataRecovery(): Result<Unit> = Result.success(Unit)
}

private fun ownerSession() = SyncSession(
    familyId = "family-owner",
    accessToken = "owner-token",
    role = com.lezi.babylog.sync.FamilyRole.Owner,
    membershipId = "owner-membership",
    serverHost = "nas.home",
)

private fun memberSession() = SyncSession(
    familyId = "family-member",
    accessToken = "member-token",
    role = com.lezi.babylog.sync.FamilyRole.Member,
    membershipId = "member-membership",
    serverHost = "nas.home",
)
