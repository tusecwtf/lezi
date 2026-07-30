package com.lezi.babylog.feature.onboarding

import com.lezi.babylog.domain.FamilyWizardEntry
import com.lezi.babylog.domain.FamilyWizardMode
import com.lezi.babylog.domain.FamilyWizardOutcome
import com.lezi.babylog.domain.FamilyWizardState
import com.lezi.babylog.domain.FamilyWizardStep
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.JoinFamilyDraft
import com.lezi.babylog.sync.SyncSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFamilyWizardAdapterTest {
    @Test
    fun onboardingProjectsTheSharedActionsAndSnapshot() {
        val draft = JoinFamilyDraft.fromConfig(
            HomeLanServerConfig(host = "nas.home", allowedSsids = listOf("Home")),
            invitation = "INVITE-1234",
        )

        val snapshot = onboardingFamilyWizardSnapshot(
            mode = FamilyWizardMode.Join,
            step = FamilyWizardStep.Identity,
            draft = draft,
            displayName = "妈妈",
        )

        assertEquals(FamilyWizardEntry.Onboarding, snapshot.entry)
        assertEquals(listOf(FamilyWizardMode.Create, FamilyWizardMode.Join), onboardingFamilyActions())
        assertEquals(draft, snapshot.toJoinDraft())
        assertEquals("妈妈", snapshot.displayName)
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
            draft = JoinFamilyDraft.fromConfig(
                HomeLanServerConfig(host = "nas.home", allowedSsids = listOf("Home")),
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
            OnboardingFamilyTransition(true, OnboardingStep.ChooseFamily),
            onboardingFamilyWizardTransition(
                completed(
                    snapshot.copy(mode = FamilyWizardMode.Join),
                    FamilyWizardOutcome.Joined(memberSession()),
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
            draft = JoinFamilyDraft.fromConfig(
                HomeLanServerConfig(host = "nas.home", allowedSsids = listOf("Home")),
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
                    "历史数据恢复失败，请保持连接家庭 Wi‑Fi 后重试",
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
        draft = JoinFamilyDraft.fromConfig(
            HomeLanServerConfig(host = "nas.home", allowedSsids = listOf("Home")),
        ),
        displayName = "妈妈",
    )
}

private fun ownerSession() = SyncSession(
    familyId = "family-owner",
    familyToken = "owner-token",
    role = FamilyRole.Owner,
    membershipId = "owner-membership",
)

private fun memberSession() = SyncSession(
    familyId = "family-member",
    familyToken = "member-token",
    role = FamilyRole.Member,
    membershipId = "member-membership",
)
