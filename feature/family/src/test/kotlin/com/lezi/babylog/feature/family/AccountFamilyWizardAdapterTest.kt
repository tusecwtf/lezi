package com.lezi.babylog.feature.family

import com.lezi.babylog.feature.family.components.*

import com.lezi.babylog.domain.family.FamilyWizardEntry
import com.lezi.babylog.domain.family.FamilyWizardJoinRole
import com.lezi.babylog.domain.family.FamilyWizardMode
import com.lezi.babylog.domain.family.FamilyWizardOutcome
import com.lezi.babylog.domain.family.FamilyWizardStep
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.session.FamilyEndpointDraft
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.domain.family.FamilyWizardState
import com.lezi.babylog.feature.family.wizard.memberApprovalCancelSucceeded
import com.lezi.babylog.feature.family.wizard.memberApprovalRequestForDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountFamilyWizardAdapterTest {
    @Test
    fun durableOverviewRequestIsTheOnlyPendingAuthority() {
        val snapshot = accountFamilyWizardSnapshot(
            mode = FamilyWizardMode.Join,
            step = FamilyWizardStep.Identity,
            draft = FamilyEndpointDraft.fromConfig(FamilyEndpointConfig(host = "nas.home")),
            displayName = "奶奶",
            joinRole = FamilyWizardJoinRole.Member,
        )
        val overviewRequest = PendingMemberLogin(
            requestId = "stale",
            displayName = "旧称呼",
            deviceName = "旧设备",
            expiresAtEpochSeconds = 10,
        )
        val stateRequest = PendingMemberLogin(
            requestId = "current",
            displayName = "奶奶",
            deviceName = "新手机",
            expiresAtEpochSeconds = 20,
        )
        val waiting = FamilyWizardState.WaitingForMemberApproval(snapshot, stateRequest)

        assertEquals(overviewRequest, memberApprovalRequestForDisplay(overviewRequest))
        assertEquals(null, memberApprovalRequestForDisplay(null))
        assertEquals(false, memberApprovalCancelSucceeded(waiting))
        assertEquals(true, memberApprovalCancelSucceeded(FamilyWizardState.Editing(snapshot)))
    }

    @Test
    fun accountProjectsTheSharedActionsAndSnapshot() {
        val draft = FamilyEndpointDraft.fromConfig(
            FamilyEndpointConfig(host = "nas.home"),
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
        assertEquals(draft, snapshot.toEndpointDraft())
        assertEquals("妈妈", snapshot.displayName)
        assertEquals(FamilyWizardJoinRole.Owner, snapshot.joinRole)
    }

    @Test
    fun accountUsesOneOutcomeProjectionForCreatedReclaimedAndCurrentLogins() {
        assertEquals(
            "家庭已创建",
            familyWizardOutcomeCopy(FamilyWizardOutcome.Created(ownerSession())),
        )
        assertEquals(
            "已重新连上家庭，数据恢复完成",
            familyWizardOutcomeCopy(
                FamilyWizardOutcome.Reclaimed(
                    ownerSession(),
                    InitialFamilyDataRecovery.Complete,
                ),
            ),
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
        assertEquals(
            "已在这台设备登录家庭",
            familyWizardOutcomeCopy(
                FamilyWizardOutcome.MemberLoginQrClaimed(
                    memberSession(),
                    InitialFamilyDataRecovery.Complete,
                ),
            ),
        )
        assertEquals(
            "已登录；首次同步失败，可稍后重试",
            familyWizardOutcomeCopy(
                FamilyWizardOutcome.MemberLoginQrClaimed(
                    memberSession(),
                    InitialFamilyDataRecovery.RetryRequired(),
                ),
            ),
        )
    }

    @Test
    fun memberLoginQrVerificationFailedAndReadyFeedbackProjectSharedRetryPolicy() {
        val payload = com.lezi.babylog.sync.qr.MemberLoginQrPayload(
            endpoint = com.lezi.babylog.sync.session.TrustedEndpointProfile.systemPki("https://nas.home"),
            grant = "grant-0000000000000000000000000000000000000",
            familyName = "乐乐一家",
            memberDisplayName = "妈妈",
            expiresAtEpochSeconds = 1_753_419_000,
        )
        val snapshot = accountFamilyWizardSnapshot(
            mode = FamilyWizardMode.Join,
            step = FamilyWizardStep.Identity,
            draft = FamilyEndpointDraft.fromConfig(
                FamilyEndpointConfig(host = "nas.home"),
            ),
            displayName = "妈妈",
            joinRole = FamilyWizardJoinRole.Member,
        )

        val failed = com.lezi.babylog.domain.family.projectMemberLoginQrDialog(
            com.lezi.babylog.domain.family.FamilyWizardState.MemberLoginQrVerificationFailed(
                snapshot = snapshot,
                payload = payload,
                message = "暂时无法确认二维码中的家庭服务器，请稍后重试",
            ),
        )!!
        assertEquals(true, failed.verificationRetryRequired)
        assertEquals(true, failed.confirmEnabled)
        assertEquals("重新确认", failed.confirmLabel)

        val ready = com.lezi.babylog.domain.family.projectMemberLoginQrDialog(
            com.lezi.babylog.domain.family.FamilyWizardState.MemberLoginQrReady(
                snapshot = snapshot,
                payload = payload,
                feedback = "请填写设备称呼",
            ),
        )!!
        assertEquals("请填写设备称呼", ready.feedback)
        assertEquals(true, ready.confirmEnabled)
    }
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
