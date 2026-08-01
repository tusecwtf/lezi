package com.lezi.babylog.feature.onboarding

import com.lezi.babylog.domain.family.FamilyWizardEntry
import com.lezi.babylog.domain.family.FamilyWizardJoinRole
import com.lezi.babylog.domain.family.FamilyWizardMode
import com.lezi.babylog.domain.family.FamilyWizardOutcome
import com.lezi.babylog.domain.family.FamilyWizardSnapshot
import com.lezi.babylog.domain.family.FamilyWizardState
import com.lezi.babylog.domain.family.FamilyWizardStep
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.session.FamilyEndpointDraft
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal enum class OnboardingStep {
    ChooseFamily,
    ConnectServer,
    CreateFamily,
    CreateBaby,
    RecoveryPending,
    RecoveryComplete,
}

/** How the user reached CreateBaby: after family create/reclaim, or offline mode. */
internal enum class OnboardingCreateBabySource {
    AfterFamilyCreate,
    AfterFamilyReclaim,
    OfflineMode,
}

internal fun onboardingFamilyActions(): List<String> = listOf("连接家庭服务器")

internal fun onboardingChooseFamilyBody(): String =
    "可新建或加入家庭，也可先用离线模式在本机记录；连家庭之后再到账户里完成。"

internal fun onboardingCreateBabyBody(source: OnboardingCreateBabySource): String = when (source) {
    OnboardingCreateBabySource.AfterFamilyReclaim ->
        "家庭已接回；家庭中还没有宝宝，请创建第一个家庭宝宝。"
    OnboardingCreateBabySource.AfterFamilyCreate ->
        "家庭已建立，请创建第一个家庭宝宝。"
    OnboardingCreateBabySource.OfflineMode ->
        "离线模式：先在本机创建宝宝并记录。之后可在账户里新建或加入家庭再同步。"
}

internal fun onboardingCreateBabySource(
    familyWizardState: FamilyWizardState,
): OnboardingCreateBabySource {
    val completed = familyWizardState as? FamilyWizardState.Completed
    return when (completed?.outcome) {
        is FamilyWizardOutcome.Reclaimed -> OnboardingCreateBabySource.AfterFamilyReclaim
        is FamilyWizardOutcome.OwnerLoggedIn -> OnboardingCreateBabySource.AfterFamilyReclaim
        is FamilyWizardOutcome.Created -> OnboardingCreateBabySource.AfterFamilyCreate
        else -> OnboardingCreateBabySource.OfflineMode
    }
}

internal fun onboardingFamilyWizardSnapshot(
    mode: FamilyWizardMode,
    step: FamilyWizardStep,
    draft: FamilyEndpointDraft,
    displayName: String,
    familyName: String = "",
    deviceName: String = "",
    joinRole: FamilyWizardJoinRole? = null,
): FamilyWizardSnapshot = FamilyWizardSnapshot.fromDraft(
    entry = FamilyWizardEntry.Onboarding,
    mode = mode,
    step = step,
    draft = draft,
    displayName = displayName,
    familyName = familyName,
    deviceName = deviceName,
).copy(joinRole = joinRole)

internal data class OnboardingFamilyTransition(
    val finishRecovery: Boolean,
    val nextStep: OnboardingStep,
)

internal fun onboardingFamilyWizardTransition(
    state: FamilyWizardState,
    reclaimedFamilyEmpty: Boolean?,
): OnboardingFamilyTransition? = when (state) {
    is FamilyWizardState.Completed -> when (val outcome = state.outcome) {
        is FamilyWizardOutcome.Created -> when (outcome.dataRecovery) {
            InitialFamilyDataRecovery.Complete -> OnboardingFamilyTransition(
                finishRecovery = false,
                nextStep = OnboardingStep.CreateBaby,
            )
            InitialFamilyDataRecovery.RetryRequired,
            InitialFamilyDataRecovery.NotRequired,
            -> OnboardingFamilyTransition(
                finishRecovery = false,
                nextStep = OnboardingStep.RecoveryPending,
            )
        }
        is FamilyWizardOutcome.Reclaimed -> when (outcome.dataRecovery) {
            InitialFamilyDataRecovery.Complete ->
                reclaimedFamilyEmpty?.let { empty ->
                    OnboardingFamilyTransition(
                        finishRecovery = true,
                        nextStep = if (empty) {
                            OnboardingStep.CreateBaby
                        } else {
                            OnboardingStep.RecoveryComplete
                        },
                    )
                }
            InitialFamilyDataRecovery.RetryRequired,
            InitialFamilyDataRecovery.NotRequired,
            -> OnboardingFamilyTransition(
                finishRecovery = false,
                nextStep = OnboardingStep.RecoveryPending,
            )
        }
        is FamilyWizardOutcome.OwnerLoggedIn -> when (outcome.dataRecovery) {
            InitialFamilyDataRecovery.Complete ->
                reclaimedFamilyEmpty?.let { empty ->
                    OnboardingFamilyTransition(
                        finishRecovery = true,
                        nextStep = if (empty) OnboardingStep.CreateBaby else OnboardingStep.RecoveryComplete,
                    )
                }
            InitialFamilyDataRecovery.RetryRequired,
            InitialFamilyDataRecovery.NotRequired,
            -> OnboardingFamilyTransition(
                finishRecovery = false,
                nextStep = OnboardingStep.RecoveryPending,
            )
        }
        is FamilyWizardOutcome.MemberApproved -> when (outcome.dataRecovery) {
            InitialFamilyDataRecovery.Complete ->
                OnboardingFamilyTransition(
                    finishRecovery = true,
                    nextStep = OnboardingStep.ChooseFamily,
                )
            InitialFamilyDataRecovery.RetryRequired,
            InitialFamilyDataRecovery.NotRequired,
            -> OnboardingFamilyTransition(
                finishRecovery = false,
                nextStep = OnboardingStep.RecoveryPending,
            )
        }
        is FamilyWizardOutcome.MemberLoginQrClaimed -> when (outcome.dataRecovery) {
            InitialFamilyDataRecovery.Complete ->
                OnboardingFamilyTransition(
                    finishRecovery = true,
                    nextStep = OnboardingStep.ChooseFamily,
                )
            InitialFamilyDataRecovery.RetryRequired,
            InitialFamilyDataRecovery.NotRequired,
            -> OnboardingFamilyTransition(
                finishRecovery = false,
                nextStep = OnboardingStep.RecoveryPending,
            )
        }
    }
    is FamilyWizardState.RetryableFailure -> if (state.committedOutcome != null) {
        OnboardingFamilyTransition(
            finishRecovery = false,
            nextStep = OnboardingStep.RecoveryPending,
        )
    } else {
        null
    }
    is FamilyWizardState.Editing,
    is FamilyWizardState.CertificateApprovalRequired,
    is FamilyWizardState.EndpointFailure,
    is FamilyWizardState.EndpointReady,
    is FamilyWizardState.ProbingEndpoint,
    is FamilyWizardState.Submitting,
    is FamilyWizardState.WaitingForMemberApproval,
    is FamilyWizardState.VerifyingMemberLoginQr,
    is FamilyWizardState.MemberLoginQrReady,
    is FamilyWizardState.MemberLoginQrVerificationFailed,
    is FamilyWizardState.ClaimingMemberLoginQr,
    -> null
}

internal fun Long.toDatePickerMillis(): Long =
    LocalDate.ofEpochDay(this)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

internal fun Long.datePickerMillisToEpochDay(): Long =
    Instant.ofEpochMilli(this)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .toEpochDay()
