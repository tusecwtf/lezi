package com.lezi.babylog.domain.family
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import org.junit.Test

class MemberLoginQrDialogProjectionTest {
    private val payload = MemberLoginQrPayload(
        endpoint = TrustedEndpointProfile.systemPki("https://nas.home"),
        grant = "grant-0000000000000000000000000000000000000",
        familyName = "乐乐一家",
        memberDisplayName = "妈妈",
        expiresAtEpochSeconds = 1_753_419_000,
    )
    private val snapshot = FamilyWizardSnapshot(
        entry = FamilyWizardEntry.Onboarding,
        mode = FamilyWizardMode.Join,
        step = FamilyWizardStep.Identity,
        host = "nas.home",
        portText = "443",
        scheme = "https",
        displayName = "妈妈",
        familyName = "乐乐一家",
        joinRole = FamilyWizardJoinRole.Member,
        endpointDraft = "https://nas.home",
    )

    @Test
    fun verificationFailedExposesEnabledRetryConfirmForBothEntries() {
        for (entry in listOf(FamilyWizardEntry.Account, FamilyWizardEntry.Onboarding)) {
            val failed = FamilyWizardState.MemberLoginQrVerificationFailed(
                snapshot = snapshot.copy(entry = entry),
                payload = payload,
                message = "暂时无法确认二维码中的家庭服务器，请稍后重试",
            )
            val model = projectMemberLoginQrDialog(failed)!!
            assertThat(model.verificationRetryRequired).isTrue()
            assertThat(model.confirmEnabled).isTrue()
            assertThat(model.confirmLabel).isEqualTo("重新确认")
            assertThat(model.payload).isEqualTo(payload)
            assertThat(model.feedback).contains("暂时无法确认")
        }
    }

    @Test
    fun readyFeedbackIsProjectedForDeviceNameAndClaimErrors() {
        val ready = FamilyWizardState.MemberLoginQrReady(
            snapshot = snapshot,
            payload = payload,
            feedback = "请填写设备称呼",
        )
        val model = projectMemberLoginQrDialog(ready)!!
        assertThat(model.verificationRetryRequired).isFalse()
        assertThat(model.confirmEnabled).isTrue()
        assertThat(model.confirmLabel).isEqualTo("在这台设备登录")
        assertThat(model.feedback).isEqualTo("请填写设备称呼")
        assertThat(model.payload).isEqualTo(payload)
    }

    @Test
    fun postClaimRecoveryUsesDisplayInfoWithoutFabricatingGrantPayload() {
        val completed = FamilyWizardState.Completed(
            snapshot = snapshot,
            outcome = FamilyWizardOutcome.MemberLoginQrClaimed(
                memberSession(),
                InitialFamilyDataRecovery.RetryRequired,
            ),
        )
        val model = projectMemberLoginQrDialog(completed)!!
        assertThat(model.recoveryRetryRequired).isTrue()
        assertThat(model.payload).isNull()
        assertThat(model.display.familyName).isEqualTo("乐乐一家")
        assertThat(model.display.memberDisplayName).isEqualTo("妈妈")
        assertThat(model.confirmLabel).isEqualTo("重试首次同步")
    }

    private fun memberSession() = SyncSession(
        familyId = "family-member",
        accessToken = "member-token",
        role = FamilyRole.Member,
        membershipId = "member-membership",
    )
}
