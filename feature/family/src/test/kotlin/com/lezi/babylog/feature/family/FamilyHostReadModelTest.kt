package com.lezi.babylog.feature.family

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.feature.family.members.MembersDevicesUi
import com.lezi.babylog.feature.family.overview.AccountOverviewUi
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.FamilyRole
import org.junit.Test

/**
 * Read-model contracts for the three family hosts (ticket 24).
 * Account overview stays free of technical credentials and roster command state.
 */
class FamilyHostReadModelTest {
    @Test
    fun accountOverviewReadModelExcludesRosterAndCredentials() {
        val overview = AccountOverviewUi(
            displayName = "管理员",
            status = SyncStatus.Idle,
            enabled = true,
            role = FamilyRole.Owner,
            familyName = "乐乐一家",
            serverHost = "192.168.50.4",
            baseUrl = "https://192.168.50.4:8765",
        )
        // Overview product surface: family name, self title inputs, sync status, no roster list.
        assertThat(overview.familyName).isEqualTo("乐乐一家")
        assertThat(overview.displayName).isEqualTo("管理员")
        assertThat(overview.status).isEqualTo(SyncStatus.Idle)
        assertThat(overview.familyNameLabel).isEqualTo("乐乐一家")
        // Technical endpoint fields may exist for wizard draft seed but do not appear on the
        // account overview card (see FamilyAccountAffordanceSemanticsTest).
        assertThat(overview::class.java.declaredFields.map { it.name })
            .containsNoneOf("members", "membersLoaded", "membersLoading", "membersError")
    }

    @Test
    fun membersDevicesReadModelOwnsRosterAndPendingApprovals() {
        val members = MembersDevicesUi(
            displayName = "管理员",
            enabled = true,
            role = FamilyRole.Owner,
            familyName = "乐乐一家",
            members = listOf(
                FamilyMember("管理员", FamilyRole.Owner, true, "m-owner"),
            ),
            membersLoaded = true,
            membersLoading = false,
            pendingMemberRequests = emptyList(),
            pendingMemberRenameRequests = emptyList(),
        )
        assertThat(members.members).hasSize(1)
        assertThat(members.membersLoaded).isTrue()
        assertThat(members.membersError).isNull()
        // No app-update or baby surface on members host UI.
        assertThat(members::class.java.declaredFields.map { it.name })
            .containsNoneOf("optionalAppUpdate", "babies", "current", "status")
    }

    @Test
    fun shellMergesOverviewAndMembersWithoutInventingCredentialsOnCard() {
        val overview = AccountOverviewUi(
            displayName = "妈妈",
            status = SyncStatus.Idle,
            enabled = true,
            role = FamilyRole.Member,
            familyName = "乐乐一家",
        )
        val members = MembersDevicesUi(
            displayName = "妈妈",
            enabled = true,
            role = FamilyRole.Member,
            members = listOf(
                FamilyMember("妈妈", FamilyRole.Member, true, "m-1"),
                FamilyMember("爸爸", FamilyRole.Owner, false, "m-2"),
            ),
            membersLoaded = true,
        )
        val ui = familyUiFromHosts(overview, members)
        assertThat(ui.familyName).isEqualTo("乐乐一家")
        assertThat(ui.displayName).isEqualTo("妈妈")
        assertThat(ui.members).hasSize(2)
        assertThat(ui.membersLoaded).isTrue()
        assertThat(ui.optionalAppUpdate).isNull()
    }
}
