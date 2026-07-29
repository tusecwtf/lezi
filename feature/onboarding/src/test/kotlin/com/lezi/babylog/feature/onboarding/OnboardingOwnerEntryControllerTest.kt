package com.lezi.babylog.feature.onboarding

import com.lezi.babylog.sync.CreateFamilyResult
import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.SyncSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OnboardingOwnerEntryControllerTest {
    @Test
    fun historicalFamilyReclaimFinishesInsteadOfEnteringAPlaceholderBabyStep() = runTest {
        val gateway = RecordingOwnerEntryGateway(
            createResult = Result.success(
                CreateFamilyResult(
                    session = ownerSession(),
                    reclaimed = true,
                    dataRecovery = InitialFamilyDataRecovery.Complete,
                ),
            ),
        )
        val controller = OnboardingOwnerEntryController(gateway)

        controller.submit(input(), bootstrapSecret = "one-shot-secret")

        assertEquals(
            OnboardingOwnerEntryState.Reclaimed(InitialFamilyDataRecovery.Complete),
            controller.state.value,
        )
        assertEquals(1, gateway.createCalls)
        assertFalse(controller.state.value.toString().contains("one-shot-secret"))
    }

    @Test
    fun invalidOwnerFormStaysRetryableWithoutPublishingNetworkOrFamily() = runTest {
        val gateway = RecordingOwnerEntryGateway(
            createResult = Result.success(
                CreateFamilyResult(ownerSession(), reclaimed = false),
            ),
        )
        val controller = OnboardingOwnerEntryController(gateway)
        val invalid = input().copy(displayName = "  ")

        controller.submit(invalid, bootstrapSecret = "not-retained")

        assertEquals(
            OnboardingOwnerEntryState.RetryableFailure(invalid, "请填写家庭称呼"),
            controller.state.value,
        )
        assertEquals(0, gateway.saveCalls)
        assertEquals(0, gateway.createCalls)
    }

    @Test
    fun completedCreateOrReclaimIgnoresARecreatedEffectSubmission() = runTest {
        val gateway = RecordingOwnerEntryGateway(
            createResult = Result.success(
                CreateFamilyResult(
                    session = ownerSession(),
                    reclaimed = true,
                    dataRecovery = InitialFamilyDataRecovery.Complete,
                ),
            ),
        )
        val controller = OnboardingOwnerEntryController(gateway)

        controller.submit(input(), bootstrapSecret = "first-secret")
        controller.submit(input(), bootstrapSecret = "recreated-effect-secret")

        assertEquals(1, gateway.saveCalls)
        assertEquals(1, gateway.createCalls)
    }

    @Test
    fun incompleteHomeNetworkStaysRetryableWithoutPublishingAnything() = runTest {
        val gateway = RecordingOwnerEntryGateway(
            createResult = Result.success(CreateFamilyResult(ownerSession(), reclaimed = false)),
        )
        val controller = OnboardingOwnerEntryController(gateway)
        val incomplete = input().copy(
            homeLanConfig = HomeLanServerConfig(host = "", allowedSsids = emptyList()),
        )

        controller.submit(incomplete, bootstrapSecret = "bootstrap")

        assertEquals(
            OnboardingOwnerEntryState.RetryableFailure(incomplete, "请填写服务器主机"),
            controller.state.value,
        )
        assertEquals(0, gateway.saveCalls)
        assertEquals(0, gateway.createCalls)
    }

    @Test
    fun onlyCreatedOrConfirmedEmptyReclaimedFamiliesEnterTheBabyStep() {
        assertEquals(
            OnboardingOwnerEntryTransition(
                finishRecovery = false,
                nextStep = OnboardingStep.CreateBaby,
            ),
            onboardingOwnerEntryTransition(
                OnboardingOwnerEntryState.Created(input()),
                reclaimedFamilyEmpty = null,
            ),
        )
        assertEquals(
            OnboardingOwnerEntryTransition(
                finishRecovery = true,
                nextStep = OnboardingStep.RecoveryComplete,
            ),
            onboardingOwnerEntryTransition(
                OnboardingOwnerEntryState.Reclaimed(InitialFamilyDataRecovery.Complete),
                reclaimedFamilyEmpty = false,
            ),
        )
        assertEquals(
            OnboardingOwnerEntryTransition(
                finishRecovery = true,
                nextStep = OnboardingStep.CreateBaby,
            ),
            onboardingOwnerEntryTransition(
                OnboardingOwnerEntryState.Reclaimed(InitialFamilyDataRecovery.Complete),
                reclaimedFamilyEmpty = true,
            ),
        )
    }

    @Test
    fun freshInstallStartsWithOnlyCreateAndJoinAsPrimaryActions() {
        assertEquals(
            listOf(
                OnboardingPrimaryAction.CreateFamily,
                OnboardingPrimaryAction.JoinFamily,
            ),
            onboardingPrimaryActions(OnboardingStep.ChooseFamily),
        )
    }

    @Test
    fun failedHistoricalPullStaysInRecoveryInsteadOfOfferingABabyUpload() {
        assertEquals(
            OnboardingOwnerEntryTransition(
                finishRecovery = true,
                nextStep = OnboardingStep.RecoveryPending,
            ),
            onboardingOwnerEntryTransition(
                OnboardingOwnerEntryState.Reclaimed(InitialFamilyDataRecovery.RetryRequired),
                reclaimedFamilyEmpty = null,
            ),
        )
    }

    @Test
    fun recoveryRetryPullsTheJoinedFamilyWithoutSubmittingCreateAgain() = runTest {
        val gateway = RecordingOwnerEntryGateway(
            createResult = Result.success(
                CreateFamilyResult(
                    ownerSession(),
                    reclaimed = true,
                    dataRecovery = InitialFamilyDataRecovery.RetryRequired,
                ),
            ),
        )
        val controller = OnboardingOwnerEntryController(gateway)
        controller.submit(input(), bootstrapSecret = "bootstrap")

        controller.retryDataRecovery()

        assertEquals(1, gateway.createCalls)
        assertEquals(1, gateway.recoveryCalls)
        assertEquals(
            OnboardingOwnerEntryState.Reclaimed(InitialFamilyDataRecovery.Complete),
            controller.state.value,
        )
    }

    @Test
    fun processRecreatedRecoveryResumesTheDurablePullWithoutSubmittingCreate() = runTest {
        val gateway = RecordingOwnerEntryGateway(
            createResult = Result.success(CreateFamilyResult(ownerSession(), reclaimed = true)),
        )
        val recreated = OnboardingOwnerEntryController(gateway)

        recreated.restorePendingRecovery()
        recreated.retryDataRecovery()

        assertEquals(0, gateway.createCalls)
        assertEquals(1, gateway.recoveryCalls)
        assertEquals(
            OnboardingOwnerEntryState.Reclaimed(InitialFamilyDataRecovery.Complete),
            recreated.state.value,
        )
    }

    @Test
    fun firstFamilyCreationEntersTheBabyStep() = runTest {
        val gateway = RecordingOwnerEntryGateway(
            createResult = Result.success(CreateFamilyResult(ownerSession(), reclaimed = false)),
        )
        val controller = OnboardingOwnerEntryController(gateway)

        controller.submit(input(), bootstrapSecret = "bootstrap")

        assertEquals(OnboardingOwnerEntryState.Created(input()), controller.state.value)
        assertEquals(1, gateway.createCalls)
    }

    @Test
    fun failedCreateKeepsNonSensitiveInputAndCanRetryWithoutRetainingTheSecret() = runTest {
        val gateway = RecordingOwnerEntryGateway(
            createResult = Result.failure(IllegalStateException("NAS 暂时不可用")),
        )
        val controller = OnboardingOwnerEntryController(gateway)

        controller.submit(input(), bootstrapSecret = "first-secret")

        val failure = controller.state.value as OnboardingOwnerEntryState.RetryableFailure
        assertEquals(input(), failure.input)
        assertFalse(failure.toString().contains("first-secret"))

        gateway.createResult = Result.success(CreateFamilyResult(ownerSession(), reclaimed = false))
        controller.submit(input(), bootstrapSecret = "retry-secret")

        assertEquals(OnboardingOwnerEntryState.Created(input()), controller.state.value)
        assertEquals(2, gateway.createCalls)
    }

    @Test
    fun busyControllerRejectsASecondTap() = runTest {
        val gateway = BlockingOwnerEntryGateway()
        val controller = OnboardingOwnerEntryController(gateway)

        val first = async { controller.submit(input(), bootstrapSecret = "first") }
        gateway.started.await()
        val second = async { controller.submit(input(), bootstrapSecret = "second") }
        second.await()

        assertEquals(1, gateway.createCalls)
        assertEquals(OnboardingOwnerEntryState.Submitting(input()), controller.state.value)

        gateway.release.complete(Unit)
        first.await()
        assertEquals(1, gateway.createCalls)
    }
}

private class RecordingOwnerEntryGateway(
    var createResult: Result<CreateFamilyResult>,
) : OnboardingOwnerEntryGateway {
    var saveCalls = 0
    var createCalls = 0
    var recoveryCalls = 0

    override suspend fun saveHomeLanConfig(config: HomeLanServerConfig): Result<Unit> {
        saveCalls += 1
        return Result.success(Unit)
    }

    override suspend fun createFamily(
        displayName: String,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult> {
        createCalls += 1
        return createResult
    }

    override suspend fun retryDataRecovery(): Result<Unit> {
        recoveryCalls += 1
        return Result.success(Unit)
    }
}

private class BlockingOwnerEntryGateway : OnboardingOwnerEntryGateway {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var createCalls = 0

    override suspend fun saveHomeLanConfig(config: HomeLanServerConfig): Result<Unit> =
        Result.success(Unit)

    override suspend fun createFamily(
        displayName: String,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult> {
        createCalls += 1
        started.complete(Unit)
        release.await()
        return Result.success(CreateFamilyResult(ownerSession(), reclaimed = false))
    }

    override suspend fun retryDataRecovery(): Result<Unit> = Result.success(Unit)
}

private fun input() = OnboardingOwnerEntryInput(
    homeLanConfig = HomeLanServerConfig(
        host = "192.168.50.4",
        port = 8765,
        allowedSsids = listOf("Home"),
    ),
    displayName = "妈妈",
    familyName = "年年家",
)

private fun ownerSession() = SyncSession(
    familyId = "family-a",
    familyToken = "owner-token",
    membershipId = "owner-membership",
)
