package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.CreateFamilyResult
import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.SyncSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FamilyWizardControllerTest {
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
    fun sameJoinInputFromBothEntriesProducesSameRequestAndJoinedOutcome() = runTest {
        val requests = FamilyWizardEntry.entries.map { entry ->
            val gateway = RecordingFamilyWizardGateway(
                joinResult = JoinFamilyResult.Joined(memberSession()),
            )
            val controller = FamilyWizardController(gateway)

            controller.submit(snapshot(entry, FamilyWizardMode.Join))

            val completed = controller.state.value as FamilyWizardState.Completed
            assertThat(completed.outcome)
                .isEqualTo(FamilyWizardOutcome.Joined(memberSession()))
            gateway.joinRequest
        }

        assertThat(requests[0]).isEqualTo(requests[1])
    }

    @Test
    fun validationAndGatewayFailuresStayOnRecoverableStepsWithRetainedInput() = runTest {
        val gateway = RecordingFamilyWizardGateway()
        val controller = FamilyWizardController(gateway)
        val invalidNetwork = snapshot(
            FamilyWizardEntry.Onboarding,
            FamilyWizardMode.Create,
        ).copy(host = "")

        assertThat(
            FamilyWizardEntry.entries.map { entry ->
                familyWizardNetworkValidationError(invalidNetwork.copy(entry = entry))
            },
        ).containsExactly("请填写服务器主机", "请填写服务器主机")

        controller.submit(invalidNetwork, "not-retained")

        assertThat(controller.state.value).isEqualTo(
            FamilyWizardState.RetryableFailure(
                snapshot = invalidNetwork.copy(step = FamilyWizardStep.Network),
                message = "请填写服务器主机",
                committedOutcome = null,
            ),
        )
        assertThat(gateway.events).isEmpty()

        val invalidIdentity = invalidNetwork.copy(
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
    fun reclaimedRecoveryFailureKeepsCommittedSessionAndCanRetryWithoutCreate() = runTest {
        val gateway = RecordingFamilyWizardGateway(
            createResult = Result.success(
                CreateFamilyResult(
                    session = ownerSession(),
                    reclaimed = true,
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
            FamilyWizardOutcome.Reclaimed(
                ownerSession(),
                InitialFamilyDataRecovery.RetryRequired,
            ),
        )
        assertThat(failure.message)
            .isEqualTo("历史数据恢复失败，请保持连接家庭 Wi‑Fi 后重试")
        assertThat(gateway.createCalls).isEqualTo(1)

        gateway.recoveryResult = Result.success(Unit)
        controller.retryReclaimedDataRecovery()

        assertThat((controller.state.value as FamilyWizardState.Completed).outcome).isEqualTo(
            FamilyWizardOutcome.Reclaimed(
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

        val join = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Join)
        controller.begin(join)
        controller.submit(join)

        assertThat((controller.state.value as FamilyWizardState.Completed).outcome)
            .isEqualTo(FamilyWizardOutcome.Joined(memberSession()))
        assertThat(gateway.createCalls).isEqualTo(1)
        assertThat(gateway.events.count { it == "join" }).isEqualTo(1)
    }

    @Test
    fun thrownJoinNetworkFailureStaysRetryableAndAUiRestoreCannotUndoCompletion() = runTest {
        val gateway = RecordingFamilyWizardGateway(
            joinThrowable = IllegalStateException("Failed to connect to nas.home:8765"),
        )
        val controller = FamilyWizardController(gateway)
        val join = snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Join)

        controller.submit(join)

        val failure = controller.state.value as FamilyWizardState.RetryableFailure
        assertThat(failure.snapshot.step).isEqualTo(FamilyWizardStep.Identity)
        assertThat(failure.message).isEqualTo("家庭同步服务暂未连接，请稍后重试")

        gateway.joinThrowable = null
        controller.submit(join)
        val completed = controller.state.value
        controller.restore(join.copy(displayName = "权限拒绝后的界面草稿"))

        assertThat(controller.state.value).isEqualTo(completed)
        assertThat((controller.state.value as FamilyWizardState.Completed).outcome)
            .isEqualTo(FamilyWizardOutcome.Joined(memberSession()))
    }

    private fun snapshot(
        entry: FamilyWizardEntry,
        mode: FamilyWizardMode,
    ) = FamilyWizardSnapshot(
        entry = entry,
        mode = mode,
        step = FamilyWizardStep.Identity,
        invitation = "INVITE-1234",
        host = "nas.home",
        portText = "8765",
        scheme = "http",
        ssid1 = "Home",
        ssid2 = "Home-5G",
        displayName = "  妈妈  ",
        familyName = "  我家  ",
    )
}

private data class CreateRequest(
    val config: HomeLanServerConfig,
    val displayName: String,
    val bootstrapSecret: String,
    val familyName: String?,
)

private class RecordingFamilyWizardGateway(
    var createResult: Result<CreateFamilyResult> = Result.success(
        CreateFamilyResult(ownerSession(), reclaimed = false),
    ),
    var joinResult: JoinFamilyResult = JoinFamilyResult.Joined(memberSession()),
    var recoveryResult: Result<Unit> = Result.success(Unit),
    var joinThrowable: Throwable? = null,
) : FamilyWizardGateway {
    val events = mutableListOf<String>()
    var request: CreateRequest? = null
    var joinRequest: JoinFamilyRequest? = null
    var createCalls = 0
    var recoveryCalls = 0

    override suspend fun saveHomeLanConfig(config: HomeLanServerConfig): Result<Unit> {
        events += "save"
        return Result.success(Unit)
    }

    override suspend fun createFamily(
        config: HomeLanServerConfig,
        displayName: String,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult> {
        events += "create"
        createCalls += 1
        request = CreateRequest(config, displayName, bootstrapSecret, familyName)
        return createResult
    }

    override suspend fun joinFamily(request: JoinFamilyRequest): JoinFamilyResult {
        events += "join"
        joinRequest = request
        joinThrowable?.let { throw it }
        return joinResult
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

    override suspend fun saveHomeLanConfig(config: HomeLanServerConfig): Result<Unit> =
        Result.success(Unit)

    override suspend fun createFamily(
        config: HomeLanServerConfig,
        displayName: String,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult> {
        createCalls += 1
        started.complete(Unit)
        release.await()
        return Result.success(CreateFamilyResult(ownerSession(), reclaimed = false))
    }

    override suspend fun joinFamily(request: JoinFamilyRequest): JoinFamilyResult =
        JoinFamilyResult.Joined(memberSession())

    override suspend fun retryReclaimedDataRecovery(): Result<Unit> = Result.success(Unit)
}

private fun ownerSession() = SyncSession(
    familyId = "family-owner",
    familyToken = "owner-token",
    role = com.lezi.babylog.sync.FamilyRole.Owner,
    membershipId = "owner-membership",
    serverHost = "nas.home",
    allowedSsids = listOf("Home", "Home-5G"),
)

private fun memberSession() = SyncSession(
    familyId = "family-member",
    familyToken = "member-token",
    role = com.lezi.babylog.sync.FamilyRole.Member,
    membershipId = "member-membership",
    serverHost = "nas.home",
    allowedSsids = listOf("Home", "Home-5G"),
)
