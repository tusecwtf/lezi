package com.lezi.babylog.feature.onboarding

import com.lezi.babylog.domain.family.FamilyWizardEntry
import com.lezi.babylog.domain.family.FamilyWizardMode
import com.lezi.babylog.domain.family.FamilyWizardJoinRole
import com.lezi.babylog.domain.family.FamilyWizardOutcome
import com.lezi.babylog.domain.family.FamilyWizardState
import com.lezi.babylog.domain.family.FamilyWizardStep
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.FamilyEndpointConfig
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.FamilyEndpointDraft
import com.lezi.babylog.sync.SyncSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFamilyWizardAdapterTest {
    @Test
    fun onboardingProjectsTheSharedActionsAndSnapshot() {
        val draft = FamilyEndpointDraft.fromConfig(
            FamilyEndpointConfig(host = "nas.home"),
        )

        val snapshot = onboardingFamilyWizardSnapshot(
            mode = FamilyWizardMode.Join,
            step = FamilyWizardStep.Identity,
            draft = draft,
            displayName = "妈妈",
            joinRole = FamilyWizardJoinRole.Member,
        )

        assertEquals(FamilyWizardEntry.Onboarding, snapshot.entry)
        assertEquals(listOf("连接家庭服务器"), onboardingFamilyActions())
        assertEquals(draft, snapshot.toEndpointDraft())
        assertEquals("妈妈", snapshot.displayName)
        assertEquals(FamilyWizardJoinRole.Member, snapshot.joinRole)
        assertTrue(onboardingChooseFamilyBody().contains("离线模式"))
    }

    @Test
    fun offlineModeCreateBabyCopyDoesNotRequireFamilySession() {
        assertEquals(
            OnboardingCreateBabySource.OfflineMode,
            onboardingCreateBabySource(FamilyWizardState.Editing(emptySnapshot())),
        )
        assertTrue(onboardingCreateBabyBody(OnboardingCreateBabySource.OfflineMode).contains("离线模式"))
        assertTrue(onboardingCreateBabyBody(OnboardingCreateBabySource.OfflineMode).contains("账户"))
        assertEquals(
            OnboardingCreateBabySource.AfterFamilyCreate,
            onboardingCreateBabySource(
                completed(emptySnapshot(), FamilyWizardOutcome.Created(ownerSession())),
            ),
        )
        assertEquals(
            OnboardingCreateBabySource.AfterFamilyReclaim,
            onboardingCreateBabySource(
                completed(
                    emptySnapshot(),
                    FamilyWizardOutcome.Reclaimed(
                        ownerSession(),
                        InitialFamilyDataRecovery.Complete,
                    ),
                ),
            ),
        )
    }

    @Test
    fun onboardingProjectsCreatedReclaimedAndJoinedWithoutARecoveryMode() {
        val snapshot = onboardingFamilyWizardSnapshot(
            mode = FamilyWizardMode.Create,
            step = FamilyWizardStep.Identity,
            draft = FamilyEndpointDraft.fromConfig(
                FamilyEndpointConfig(host = "nas.home"),
            ),
            displayName = "妈妈",
        )
        assertEquals(
            OnboardingFamilyTransition(false, OnboardingStep.CreateBaby),
            onboardingFamilyWizardTransition(
                completed(snapshot, FamilyWizardOutcome.Created(ownerSession())),
                reclaimedFamilyEmpty = null,
            ),
        )
        assertEquals(
            OnboardingFamilyTransition(true, OnboardingStep.RecoveryComplete),
            onboardingFamilyWizardTransition(
                completed(
                    snapshot,
                    FamilyWizardOutcome.Reclaimed(
                        ownerSession(),
                        InitialFamilyDataRecovery.Complete,
                    ),
                ),
                reclaimedFamilyEmpty = false,
            ),
        )
        assertEquals(
            OnboardingFamilyTransition(true, OnboardingStep.RecoveryComplete),
            onboardingFamilyWizardTransition(
                completed(
                    snapshot.copy(mode = FamilyWizardMode.Join),
                    FamilyWizardOutcome.OwnerLoggedIn(
                        ownerSession(),
                        InitialFamilyDataRecovery.Complete,
                    ),
                ),
                reclaimedFamilyEmpty = false,
            ),
        )
        assertEquals(
            OnboardingFamilyTransition(true, OnboardingStep.ChooseFamily),
            onboardingFamilyWizardTransition(
                completed(
                    snapshot.copy(mode = FamilyWizardMode.Join),
                    FamilyWizardOutcome.MemberApproved(
                        memberSession(),
                        InitialFamilyDataRecovery.Complete,
                    ),
                ),
                reclaimedFamilyEmpty = null,
            ),
        )
        assertEquals(
            OnboardingFamilyTransition(false, OnboardingStep.RecoveryPending),
            onboardingFamilyWizardTransition(
                completed(
                    snapshot.copy(mode = FamilyWizardMode.Join),
                    FamilyWizardOutcome.MemberApproved(
                        memberSession(),
                        InitialFamilyDataRecovery.RetryRequired,
                    ),
                ),
                reclaimedFamilyEmpty = null,
            ),
        )
        assertEquals(
            OnboardingFamilyTransition(true, OnboardingStep.ChooseFamily),
            onboardingFamilyWizardTransition(
                completed(
                    snapshot.copy(mode = FamilyWizardMode.Join),
                    FamilyWizardOutcome.MemberLoginQrClaimed(
                        memberSession(),
                        InitialFamilyDataRecovery.Complete,
                    ),
                ),
                reclaimedFamilyEmpty = null,
            ),
        )
        assertEquals(
            OnboardingFamilyTransition(false, OnboardingStep.RecoveryPending),
            onboardingFamilyWizardTransition(
                completed(
                    snapshot.copy(mode = FamilyWizardMode.Join),
                    FamilyWizardOutcome.MemberLoginQrClaimed(
                        memberSession(),
                        InitialFamilyDataRecovery.RetryRequired,
                    ),
                ),
                reclaimedFamilyEmpty = null,
            ),
        )
    }

    @Test
    fun committedReclaimFailureStaysOnRecoveryGate() {
        val snapshot = onboardingFamilyWizardSnapshot(
            mode = FamilyWizardMode.Create,
            step = FamilyWizardStep.Identity,
            draft = FamilyEndpointDraft.fromConfig(
                FamilyEndpointConfig(host = "nas.home"),
            ),
            displayName = "妈妈",
        )
        val committed = FamilyWizardOutcome.Reclaimed(
            ownerSession(),
            InitialFamilyDataRecovery.RetryRequired,
        )

        assertEquals(
            OnboardingFamilyTransition(false, OnboardingStep.RecoveryPending),
            onboardingFamilyWizardTransition(
                FamilyWizardState.RetryableFailure(
                    snapshot,
                    "历史数据恢复失败，请确认家庭服务器可访问后重试",
                    committed,
                ),
                reclaimedFamilyEmpty = null,
            ),
        )
    }

    @Test
    fun memberLoginQrVerificationFailedAndReadyFeedbackProjectSharedRetryPolicy() {
        val payload = com.lezi.babylog.sync.MemberLoginQrPayload(
            endpoint = com.lezi.babylog.sync.TrustedEndpointProfile.systemPki("https://nas.home"),
            grant = "grant-0000000000000000000000000000000000000",
            familyName = "乐乐一家",
            memberDisplayName = "妈妈",
            expiresAtEpochSeconds = 1_753_419_000,
        )
        val snapshot = onboardingFamilyWizardSnapshot(
            mode = FamilyWizardMode.Join,
            step = FamilyWizardStep.Identity,
            draft = FamilyEndpointDraft.fromConfig(
                FamilyEndpointConfig(host = "nas.home"),
            ),
            displayName = "妈妈",
            joinRole = FamilyWizardJoinRole.Member,
        )

        val failed = com.lezi.babylog.domain.family.projectMemberLoginQrDialog(
            FamilyWizardState.MemberLoginQrVerificationFailed(
                snapshot = snapshot,
                payload = payload,
                message = "暂时无法确认二维码中的家庭服务器，请稍后重试",
            ),
        )!!
        assertEquals(true, failed.verificationRetryRequired)
        assertEquals(true, failed.confirmEnabled)
        assertEquals("重新确认", failed.confirmLabel)

        val ready = com.lezi.babylog.domain.family.projectMemberLoginQrDialog(
            FamilyWizardState.MemberLoginQrReady(
                snapshot = snapshot,
                payload = payload,
                feedback = "请填写设备称呼",
            ),
        )!!
        assertEquals("请填写设备称呼", ready.feedback)
        assertEquals(true, ready.confirmEnabled)
        assertEquals("在这台设备登录", ready.confirmLabel)
    }

    private fun completed(
        snapshot: com.lezi.babylog.domain.family.FamilyWizardSnapshot,
        outcome: FamilyWizardOutcome,
    ) = FamilyWizardState.Completed(snapshot, outcome)

    private fun emptySnapshot() = onboardingFamilyWizardSnapshot(
        mode = FamilyWizardMode.Create,
        step = FamilyWizardStep.Identity,
        draft = FamilyEndpointDraft.fromConfig(
            FamilyEndpointConfig(host = "nas.home"),
        ),
        displayName = "妈妈",
    )
}

private fun ownerSession() = SyncSession(
    familyId = "family-owner",
    accessToken = "owner-token",
    role = FamilyRole.Owner,
    membershipId = "owner-membership",
)

private fun memberSession() = SyncSession(
    familyId = "family-member",
    accessToken = "member-token",
    role = FamilyRole.Member,
    membershipId = "member-membership",
)
