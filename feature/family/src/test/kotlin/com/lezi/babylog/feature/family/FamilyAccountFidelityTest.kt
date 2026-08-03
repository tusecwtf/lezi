package com.lezi.babylog.feature.family

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.feature.family.components.buildFamilyOverviewCard
import com.lezi.babylog.feature.family.components.familyNameSupportingCopy
import com.lezi.babylog.feature.family.components.familyRosterEntryPresentation
import com.lezi.babylog.feature.family.overview.familyAccountConnectionPresentation
import com.lezi.babylog.sync.session.ShallowSyncState
import com.lezi.babylog.sync.session.FamilyRole
import org.junit.Test

class FamilyAccountFidelityTest {
    @Test
    fun reauthRetainsFamilyChromeAndOffersOneHonestRecoveryAction() {
        val recovery = familyAccountConnectionPresentation(
            isJoined = false,
            retainedFamilyIdentity = true,
            shallowState = ShallowSyncState.ReauthRequired,
            waitingForApproval = false,
            showCreateJoin = true,
        )

        assertThat(recovery.showFamilyContext).isTrue()
        assertThat(recovery.showRoster).isFalse()
        assertThat(recovery.primaryCtaLabel).isEqualTo("重新登录或申请")

        val waiting = familyAccountConnectionPresentation(
            isJoined = false,
            retainedFamilyIdentity = false,
            shallowState = ShallowSyncState.WaitingForAdmin,
            waitingForApproval = true,
            showCreateJoin = true,
        )
        assertThat(waiting.showFamilyContext).isFalse()
        assertThat(waiting.primaryCtaLabel).isEqualTo("查看加入申请")
    }

    @Test
    fun ownerOverviewCardUsesHonestPathAWithoutPromisingInlineRename() {
        val card = buildFamilyOverviewCard(
            isJoined = true,
            role = FamilyRole.Owner,
            endpointConfigured = true,
            familyName = "乐乐一家",
            babyNickname = "乐乐",
            localDisplayName = "妈妈",
            memberCount = 2,
            membersLoaded = true,
            status = SyncStatus.Idle,
        )

        assertThat(card.showRenameFamily).isFalse()
        assertThat(familyNameSupportingCopy(FamilyRole.Owner)).isEqualTo("共享家庭名")
    }

    @Test
    fun rosterEntryDistinguishesLoadingFailureAndLoadedCount() {
        assertThat(
            familyRosterEntryPresentation(
                visibleCount = 2,
                loaded = false,
                loading = true,
                error = null,
            ).label,
        ).isEqualTo("正在读取家人…")

        val failed = familyRosterEntryPresentation(
            visibleCount = 2,
            loaded = false,
            loading = false,
            error = "暂时无法读取成员与设备，请重试",
        )
        assertThat(failed.label).isEqualTo("读取失败，点此重试")
        assertThat(failed.isError).isTrue()

        assertThat(
            familyRosterEntryPresentation(
                visibleCount = 3,
                loaded = true,
                loading = false,
                error = null,
            ).label,
        ).isEqualTo("3 位家人")
    }
}
