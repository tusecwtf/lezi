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
class FamilyWizardControllerMemberJoinTest {
    @Test
    fun queuedOldOperationEventsCannotCancelTheReplacementWaitingUi() = runTest {
        for (entry in listOf(FamilyWizardEntry.Account, FamilyWizardEntry.Onboarding)) {
            val controller = FamilyWizardController(RecordingFamilyWizardGateway())
            val state = snapshot(entry, FamilyWizardMode.Join).copy(joinRole = FamilyWizardJoinRole.Member)
            val newer = PendingMemberLogin("server-b", "妈妈", "Phone", 20, "operation-b")
            controller.restorePendingMemberApproval(state, newer)
            controller.observeMemberLoginCheck(MemberLoginCheckResult.Terminal(MemberLoginStatus.Cancelled, "operation-a"))
            controller.observeMemberLoginCheck(MemberLoginCheckResult.Waiting(newer.copy(requestId = "server-a", operationId = "operation-a")))
            val waiting = controller.state.value as FamilyWizardState.WaitingForMemberApproval
            assertThat(waiting.request).isEqualTo(newer)
            assertThat(waiting.feedback).isNull()
        }
    }

    @Test
    fun retiredCheckFailureCannotReplaceANewerWaitingOperationInEitherHost() = runTest {
        for (entry in listOf(FamilyWizardEntry.Account, FamilyWizardEntry.Onboarding)) {
            val gateway = RecordingFamilyWizardGateway().apply {
                memberCheckStarted = CompletableDeferred()
                memberCheckRelease = CompletableDeferred()
                memberCheckResult = Result.failure(com.lezi.babylog.sync.MemberLoginAttemptRetiredException())
            }
            val controller = FamilyWizardController(gateway)
            val state = snapshot(entry, FamilyWizardMode.Join).copy(joinRole = FamilyWizardJoinRole.Member)
            controller.restorePendingMemberApproval(state, gateway.pendingRequest)
            val oldCheck = async { controller.checkMemberApproval() }
            gateway.memberCheckStarted!!.await()
            controller.cancelMemberApproval()
            val newer = gateway.pendingRequest.copy(requestId = "server-b", operationId = "operation-b")
            controller.reconcilePendingMemberApproval(state, newer)
            gateway.memberCheckRelease!!.complete(Unit)
            oldCheck.await()
            val waiting = controller.state.value as FamilyWizardState.WaitingForMemberApproval
            assertThat(waiting.request).isEqualTo(newer)
            assertThat(waiting.feedback).isNull()
        }
    }

    @Test
    fun sameOperationUnknownToKnownReceiptUpdatesTheWaitingPhaseForBothHosts() = runTest {
        for (entry in listOf(FamilyWizardEntry.Account, FamilyWizardEntry.Onboarding)) {
            val controller = FamilyWizardController(RecordingFamilyWizardGateway())
            val state = snapshot(entry, FamilyWizardMode.Join).copy(joinRole = FamilyWizardJoinRole.Member)
            val unknown = PendingMemberLogin("", "妈妈", "Phone", 0, "operation-a", "https://family.example.com", true)
            controller.restorePendingMemberApproval(state, unknown)
            val known = unknown.copy(requestId = "server-r", remoteOutcomeUnknown = false, expiresAtEpochSeconds = 20)
            controller.reconcilePendingMemberApproval(state, known)
            val waiting = controller.state.value as FamilyWizardState.WaitingForMemberApproval
            assertThat(waiting.request).isEqualTo(known)
            assertThat(waiting.request.remoteOutcomeUnknown).isFalse()
        }
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
        assertThat(waiting.failureKind).isNull()
        assertThat(gateway.events).containsExactly("save", "member-request").inOrder()
        assertThat(gateway.memberRequestCalls).isEqualTo(1)

        gateway.memberCheckResult = Result.success(MemberLoginCheckResult.Waiting(waiting.request))
        controller.checkMemberApproval()
        val stillWaiting = controller.state.value as FamilyWizardState.WaitingForMemberApproval
        assertThat(stillWaiting.request).isEqualTo(waiting.request)
        assertThat(stillWaiting.failureKind).isNull()
    }

    @Test
    fun memberRequestTransportFailureReturnsToARetryableStateInsteadOfStayingBusy() = runTest {
        val gateway = RecordingFamilyWizardGateway(
            memberRequestResult = Result.failure(
                com.lezi.babylog.sync.backend.deadline.FamilyHttpException(
                    com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind.ResponseTimedOut,
                ),
            ),
        )
        val controller = FamilyWizardController(gateway)
        val input = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Join).copy(
            joinRole = FamilyWizardJoinRole.Member,
        )

        controller.submit(input)

        val failure = controller.state.value as FamilyWizardState.RetryableFailure
        assertThat(failure.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.ResponseTimedOut)
        assertThat(failure.isBusy).isFalse()
        assertThat(familyWizardFailurePresentation(failure).overlayKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.ResponseTimedOut)
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
                InitialFamilyDataRecovery.RetryRequired(),
            ),
        )

        controller.checkMemberApproval()

        assertThat((controller.state.value as FamilyWizardState.Completed).outcome).isEqualTo(
            FamilyWizardOutcome.MemberApproved(
                memberSession().toPresentation(),
                InitialFamilyDataRecovery.RetryRequired(),
            ),
        )
        gateway.recoveryResult = Result.success(Unit)
        controller.retryReclaimedDataRecovery()
        assertThat((controller.state.value as FamilyWizardState.Completed).outcome).isEqualTo(
            FamilyWizardOutcome.MemberApproved(
                memberSession().toPresentation(),
                InitialFamilyDataRecovery.NotRequired,
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
                InitialFamilyDataRecovery.RetryRequired(),
            ),
        )

        val completed = controller.state.value
        assertThat((completed as FamilyWizardState.Completed).outcome).isEqualTo(
            FamilyWizardOutcome.MemberApproved(
                memberSession().toPresentation(),
                InitialFamilyDataRecovery.RetryRequired(),
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

    @Test
    fun failedLocalAbandonmentKeepsTheWaitingRequestAndShowsFeedback() = runTest {
        val gateway = RecordingFamilyWizardGateway().apply {
            cancelMemberResult = Result.failure(IllegalStateException("secure clear failed"))
        }
        val controller = FamilyWizardController(gateway)
        val input = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Join).copy(
            joinRole = FamilyWizardJoinRole.Member,
        )
        controller.submit(input)
        val original = controller.state.value as FamilyWizardState.WaitingForMemberApproval

        controller.cancelMemberApproval()

        val waiting = controller.state.value as FamilyWizardState.WaitingForMemberApproval
        assertThat(waiting.request).isEqualTo(original.request)
        assertThat(waiting.feedback).contains("取消失败")
        assertThat(gateway.cancelMemberCalls).isEqualTo(1)
    }

    @Test
    fun abandoningPendingApprovalIsVisiblyBusyAndSerializesRepeatedTaps() = runTest {
        val gateway = RecordingFamilyWizardGateway().apply {
            cancelMemberStarted = CompletableDeferred()
            cancelMemberRelease = CompletableDeferred()
        }
        val controller = FamilyWizardController(gateway)
        val input = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Join).copy(
            joinRole = FamilyWizardJoinRole.Member,
        )
        controller.submit(input)

        val firstCancel = launch { controller.cancelMemberApproval() }
        gateway.cancelMemberStarted!!.await()

        val cancelling = controller.state.value as FamilyWizardState.WaitingForMemberApproval
        assertThat(cancelling.cancelling).isTrue()
        assertThat(cancelling.isBusy).isTrue()
        controller.cancelMemberApproval()
        assertThat(gateway.cancelMemberCalls).isEqualTo(1)

        gateway.cancelMemberRelease!!.complete(Unit)
        firstCancel.join()
        assertThat(controller.state.value).isInstanceOf(FamilyWizardState.Editing::class.java)
    }

    @Test
    fun pendingApprovalRestoreOnlyClaimsIdleOrExistingWaitingState() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home")
        val gateway = RecordingFamilyWizardGateway(
            probeResult = SetupProbeResult.Ready(endpoint, SetupFamilyState.Empty),
        )
        val controller = FamilyWizardController(gateway)
        val pendingSnapshot = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Join).copy(
            joinRole = FamilyWizardJoinRole.Member,
        )

        controller.connectEndpoint(FamilyWizardEntry.Account, endpoint.origin)
        val ready = controller.state.value
        controller.restorePendingMemberApproval(pendingSnapshot, gateway.pendingRequest)
        assertThat(controller.state.value).isEqualTo(ready)

        controller.begin(snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Create))
        controller.submit(snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Create), "once")
        val completed = controller.state.value
        controller.restorePendingMemberApproval(pendingSnapshot, gateway.pendingRequest)
        assertThat(controller.state.value).isEqualTo(completed)

        controller.begin(FamilyWizardSnapshot.empty(FamilyWizardEntry.Account))
        controller.restorePendingMemberApproval(pendingSnapshot, gateway.pendingRequest)
        assertThat(controller.state.value)
            .isInstanceOf(FamilyWizardState.WaitingForMemberApproval::class.java)
    }

    @Test
    fun durablePendingSlotRetractsAControllerOnlyWaitingStateWhenItBecomesEmpty() = runTest {
        val gateway = RecordingFamilyWizardGateway()
        val controller = FamilyWizardController(gateway)
        val pendingSnapshot = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Join).copy(
            joinRole = FamilyWizardJoinRole.Member,
        )
        controller.restorePendingMemberApproval(pendingSnapshot, gateway.pendingRequest)
        val waiting = controller.state.value as FamilyWizardState.WaitingForMemberApproval

        controller.reconcilePendingMemberApproval(pendingSnapshot, null)

        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.Editing(waiting.snapshot),
        )
    }

    @Test
    fun failedManualApprovalCheckKeepsPendingRequestAndShowsRetryableFeedback() = runTest {
        val gateway = RecordingFamilyWizardGateway()
        val controller = FamilyWizardController(gateway)
        val input = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Join).copy(
            joinRole = FamilyWizardJoinRole.Member,
        )
        controller.submit(input)
        gateway.memberCheckResult = Result.failure(IllegalStateException("offline"))

        controller.checkMemberApproval()

        val waiting = controller.state.value as FamilyWizardState.WaitingForMemberApproval
        assertThat(waiting.request.requestId).isEqualTo(gateway.pendingRequest.requestId)
        assertThat(waiting.feedback).contains("检查失败")
        assertThat(waiting.failureKind).isNull()
        assertThat(familyWizardFailurePresentation(waiting).overlayKind).isNull()
    }

    @Test
    fun idleWaitingForAdminDoesNotAutoFailOrBecomeBusy() = runTest {
        val gateway = RecordingFamilyWizardGateway()
        val controller = FamilyWizardController(gateway)
        val input = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Join).copy(
            joinRole = FamilyWizardJoinRole.Member,
        )
        controller.submit(input)
        val waiting = controller.state.value as FamilyWizardState.WaitingForMemberApproval
        assertThat(waiting.isBusy).isFalse()

        advanceTimeBy(60_000)
        runCurrent()

        assertThat(controller.state.value).isEqualTo(waiting)
        assertThat(controller.state.value.isBusy).isFalse()
        controller.keepOffline(FamilyWizardEntry.Account)
        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.Editing(FamilyWizardSnapshot.empty(FamilyWizardEntry.Account)),
        )
    }

    @Test
    fun abandonDuringApprovalCheckClearsLocalSlotAndIgnoresTheInFlightCheck() = runTest {
        val gateway = RecordingFamilyWizardGateway().apply {
            memberCheckStarted = CompletableDeferred()
            memberCheckRelease = CompletableDeferred()
            memberCheckResult = Result.success(
                MemberLoginCheckResult.Joined(
                    memberSession(),
                    InitialFamilyDataRecovery.RetryRequired(),
                ),
            )
        }
        val controller = FamilyWizardController(gateway)
        val input = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Join).copy(
            joinRole = FamilyWizardJoinRole.Member,
        )
        controller.submit(input)

        val check = launch { controller.checkMemberApproval() }
        gateway.memberCheckStarted!!.await()

        controller.cancelMemberApproval()

        assertThat(gateway.cancelMemberCalls).isEqualTo(1)
        assertThat(controller.state.value).isInstanceOf(FamilyWizardState.Editing::class.java)

        gateway.memberCheckRelease!!.complete(Unit)
        check.join()
        assertThat(controller.state.value).isInstanceOf(FamilyWizardState.Editing::class.java)

        controller.observeMemberLoginCheck(
            MemberLoginCheckResult.Joined(
                memberSession(),
                InitialFamilyDataRecovery.Complete,
            ),
        )
        assertThat(controller.state.value).isInstanceOf(FamilyWizardState.Editing::class.java)
    }
}
