package com.lezi.babylog.feature.family

import com.lezi.babylog.domain.FamilyWizardEntry
import com.lezi.babylog.domain.FamilyWizardJoinRole
import com.lezi.babylog.domain.FamilyWizardMode
import com.lezi.babylog.domain.FamilyWizardOutcome
import com.lezi.babylog.domain.FamilyWizardStep
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.JoinFamilyDraft
import com.lezi.babylog.sync.SyncSession
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountFamilyWizardAdapterTest {
    @Test
    fun accountProjectsTheSharedActionsAndSnapshot() {
        val draft = JoinFamilyDraft.fromConfig(
            HomeLanServerConfig(host = "nas.home"),
            invitation = "INVITE-1234",
        )

        val snapshot = accountFamilyWizardSnapshot(
            mode = FamilyWizardMode.Join,
            step = FamilyWizardStep.Identity,
            draft = draft,
            displayName = "妈妈",
            joinRole = FamilyWizardJoinRole.Owner,
        )

        assertEquals(FamilyWizardEntry.Account, snapshot.entry)
        assertEquals(listOf("连接家庭服务器"), accountFamilyActions())
        assertEquals(draft, snapshot.toJoinDraft())
        assertEquals("妈妈", snapshot.displayName)
        assertEquals(FamilyWizardJoinRole.Owner, snapshot.joinRole)
    }

    @Test
    fun accountUsesOneOutcomeProjectionForCreatedReclaimedAndJoined() {
        assertEquals(
            "家庭已创建",
            familyWizardOutcomeCopy(FamilyWizardOutcome.Created(ownerSession())),
        )
        assertEquals(
            "已接回家庭，数据恢复完成",
            familyWizardOutcomeCopy(
                FamilyWizardOutcome.Reclaimed(
                    ownerSession(),
                    InitialFamilyDataRecovery.Complete,
                ),
            ),
        )
        assertEquals(
            "已加入家庭",
            familyWizardOutcomeCopy(FamilyWizardOutcome.Joined(memberSession())),
        )
        assertEquals(
            "管理员已确认，家庭数据同步完成",
            familyWizardOutcomeCopy(
                FamilyWizardOutcome.MemberApproved(
                    memberSession(),
                    InitialFamilyDataRecovery.Complete,
                ),
            ),
        )
    }
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
