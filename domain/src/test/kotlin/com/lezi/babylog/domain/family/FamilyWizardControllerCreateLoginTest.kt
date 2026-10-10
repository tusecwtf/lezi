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
class FamilyWizardControllerCreateLoginTest {
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
                .isEqualTo(FamilyWizardOutcome.Created(ownerSession().toPresentation()))
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
                ownerSession().toPresentation(),
                InitialFamilyDataRecovery.Complete,
            ),
            FamilyWizardOutcome.Reclaimed(
                ownerSession().toPresentation(),
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
                        ownerSession().toPresentation(),
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
                failureKind = com.lezi.babylog.core.common.failure.FailureKind.InvalidInput,
                field = FamilyWizardField.Address,
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
                failureKind = com.lezi.babylog.core.common.failure.FailureKind.InvalidInput,
                field = FamilyWizardField.DisplayName,
            ),
        )

        gateway.createResult = Result.failure(IllegalArgumentException("初始化口令不正确"))
        val valid = snapshot(FamilyWizardEntry.Onboarding, FamilyWizardMode.Create)
        controller.submit(valid, "secret")

        val failure = controller.state.value as FamilyWizardState.RetryableFailure
        assertThat(failure.snapshot).isEqualTo(valid.copy(step = FamilyWizardStep.Identity))
        assertThat(failure.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.InvalidInput)
        assertThat(failure.message).isEqualTo("创建家庭失败，请重试")
        assertThat(failure.committedOutcome).isNull()
        assertThat(
            com.lezi.babylog.core.common.failure.failureExplanation(failure.failureKind!!).title,
        ).isEqualTo("填写的内容不对")

        gateway.createResult = Result.success(CreateFamilyResult(ownerSession(), reclaimed = false))
        controller.submit(valid.copy(familyName = "新家庭"), "new-secret")
        assertThat((controller.state.value as FamilyWizardState.Completed).outcome)
            .isEqualTo(FamilyWizardOutcome.Created(ownerSession().toPresentation()))
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
                    dataRecovery = InitialFamilyDataRecovery.RetryRequired(),
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
                ownerSession().toPresentation(),
                InitialFamilyDataRecovery.RetryRequired(),
            ),
        )
        assertThat(failure.message)
            .isEqualTo("首次同步失败，可继续离线使用并稍后重试")
        assertThat(gateway.createCalls).isEqualTo(1)

        gateway.recoveryResult = Result.success(Unit)
        controller.retryReclaimedDataRecovery()

        assertThat((controller.state.value as FamilyWizardState.Completed).outcome).isEqualTo(
            FamilyWizardOutcome.Created(
                ownerSession().toPresentation(),
                InitialFamilyDataRecovery.NotRequired,
            ),
        )
        assertThat(gateway.createCalls).isEqualTo(1)
        assertThat(gateway.recoveryCalls).isEqualTo(2)
    }

    @Test
    fun firstSyncRetryKeepsTransportKindInsteadOfAlwaysSyncTookTooLong() = runTest {
        val gateway = RecordingFamilyWizardGateway(
            createResult = Result.success(
                CreateFamilyResult(
                    session = ownerSession(),
                    reclaimed = false,
                    dataRecovery = InitialFamilyDataRecovery.RetryRequired(),
                ),
            ),
            recoveryResult = Result.failure(
                com.lezi.babylog.sync.backend.deadline.FamilyHttpException(
                    com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind.ResponseTimedOut,
                ),
            ),
        )
        val controller = FamilyWizardController(gateway)
        controller.submit(snapshot(FamilyWizardEntry.Onboarding, FamilyWizardMode.Create), "once")

        controller.retryReclaimedDataRecovery()

        val failure = controller.state.value as FamilyWizardState.RetryableFailure
        assertThat(failure.committedOutcome).isEqualTo(
            FamilyWizardOutcome.Created(
                ownerSession().toPresentation(),
                InitialFamilyDataRecovery.RetryRequired(),
            ),
        )
        assertThat(failure.message).contains("离线")
        assertThat(failure.isBusy).isFalse()
        assertThat(failure.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.ResponseTimedOut)
        assertThat(failure.failureKind)
            .isNotEqualTo(com.lezi.babylog.core.common.failure.FailureKind.SyncTookTooLong)
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
            .isEqualTo(FamilyWizardOutcome.Created(ownerSession().toPresentation()))
        assertThat(gateway.createCalls).isEqualTo(2)
    }

    @Test
    fun createOwnerAndMemberRequestShareTheSameTransportKind() = runTest {
        val timedOut = com.lezi.babylog.sync.backend.deadline.FamilyHttpException(
            com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind.ResponseTimedOut,
        )
        val createGateway = RecordingFamilyWizardGateway(
            createResult = Result.failure(timedOut),
        )
        val create = FamilyWizardController(createGateway)
        create.submit(snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Create), "once")
        val createFailure = create.state.value as FamilyWizardState.RetryableFailure
        assertThat(createFailure.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.ResponseTimedOut)
        assertThat(createFailure.isBusy).isFalse()

        val ownerGateway = RecordingFamilyWizardGateway(
            ownerLoginResult = Result.failure(timedOut),
        )
        val owner = FamilyWizardController(ownerGateway)
        val ownerInput = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Join).copy(
            joinRole = FamilyWizardJoinRole.Owner,
        )
        owner.submit(ownerInput, "root-password")
        val ownerFailure = owner.state.value as FamilyWizardState.RetryableFailure
        assertThat(ownerFailure.failureKind).isEqualTo(createFailure.failureKind)

        val checkGateway = RecordingFamilyWizardGateway().apply {
            memberCheckResult = Result.failure(timedOut)
        }
        val check = FamilyWizardController(checkGateway)
        val memberInput = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Join).copy(
            joinRole = FamilyWizardJoinRole.Member,
        )
        check.submit(memberInput)
        check.checkMemberApproval()
        val waiting = check.state.value as FamilyWizardState.WaitingForMemberApproval
        assertThat(waiting.feedback).isNotEmpty()
        assertThat(waiting.failureKind).isNull()
        assertThat(familyWizardFailurePresentation(waiting).overlayKind).isNull()
    }

    @Test
    fun dismissingExplanationKeepsKindSoASecondMessageDoesNotStack() = runTest {
        val gateway = RecordingFamilyWizardGateway(
            createResult = Result.failure(
                com.lezi.babylog.sync.backend.deadline.FamilyHttpException(
                    com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind.Unreachable,
                ),
            ),
        )
        val controller = FamilyWizardController(gateway)
        controller.submit(snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Create), "once")
        val failure = controller.state.value as FamilyWizardState.RetryableFailure
        assertThat(familyWizardFailurePresentation(failure).overlayKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.Unreachable)

        controller.clearPresentedFailure()

        val dismissed = controller.state.value as FamilyWizardState.RetryableFailure
        assertThat(dismissed.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.Unreachable)
        assertThat(dismissed.explanationDismissed).isTrue()
        assertThat(familyWizardFailurePresentation(dismissed).overlayKind).isNull()
    }

    @Test
    fun retryLastStepReplaysCreateWithTheSameSnapshot() = runTest {
        val gateway = RecordingFamilyWizardGateway(
            createResult = Result.failure(
                com.lezi.babylog.sync.backend.deadline.FamilyHttpException(
                    com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind.Unreachable,
                ),
            ),
        )
        val controller = FamilyWizardController(gateway)
        val input = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Create)
        controller.submit(input, "once")
        assertThat(gateway.createCalls).isEqualTo(1)

        gateway.createResult = Result.success(CreateFamilyResult(ownerSession(), reclaimed = false))
        controller.retryLastStep(bootstrapSecret = "once")

        assertThat(gateway.createCalls).isEqualTo(2)
        assertThat((controller.state.value as FamilyWizardState.Completed).outcome)
            .isEqualTo(FamilyWizardOutcome.Created(ownerSession().toPresentation()))
    }

    @Test
    fun cancellationDuringSubmitOrApprovalCheckRestoresRetryableChromeAndAllowsBegin() = runTest {
        val submitGateway = RecordingFamilyWizardGateway().apply {
            createStarted = CompletableDeferred()
            createRelease = CompletableDeferred()
        }
        val submitController = FamilyWizardController(submitGateway)
        val createInput = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Create)
        val submitJob = launch { submitController.submit(createInput, "once") }
        submitGateway.createStarted!!.await()
        submitJob.cancel()
        submitJob.join()

        val cancelled = submitController.state.value as FamilyWizardState.RetryableFailure
        assertThat(cancelled.failureKind).isNull()
        assertThat(cancelled.message).contains("已取消")
        val fresh = FamilyWizardSnapshot.empty(FamilyWizardEntry.Account)
        submitController.begin(fresh)
        assertThat(submitController.state.value).isEqualTo(FamilyWizardState.Editing(fresh))

        val checkGateway = RecordingFamilyWizardGateway().apply {
            memberCheckStarted = CompletableDeferred()
            memberCheckRelease = CompletableDeferred()
        }
        val checkController = FamilyWizardController(checkGateway)
        val memberInput = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Join).copy(
            joinRole = FamilyWizardJoinRole.Member,
        )
        checkController.submit(memberInput)
        val originalWaiting =
            checkController.state.value as FamilyWizardState.WaitingForMemberApproval
        val checkJob = launch { checkController.checkMemberApproval() }
        checkGateway.memberCheckStarted!!.await()
        checkJob.cancel()
        checkJob.join()

        assertThat(checkController.state.value).isEqualTo(originalWaiting)
        checkController.begin(fresh)
        assertThat(checkController.state.value).isEqualTo(FamilyWizardState.Editing(fresh))
    }
}
