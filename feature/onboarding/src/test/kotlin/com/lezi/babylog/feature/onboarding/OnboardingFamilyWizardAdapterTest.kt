package com.lezi.babylog.feature.onboarding

import com.lezi.babylog.domain.FamilyWizardEntry
import com.lezi.babylog.domain.FamilyWizardMode
import com.lezi.babylog.domain.FamilyWizardJoinRole
import com.lezi.babylog.domain.FamilyWizardOutcome
import com.lezi.babylog.domain.FamilyWizardState
import com.lezi.babylog.domain.FamilyWizardStep
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

    private fun completed(
        snapshot: com.lezi.babylog.domain.FamilyWizardSnapshot,
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
