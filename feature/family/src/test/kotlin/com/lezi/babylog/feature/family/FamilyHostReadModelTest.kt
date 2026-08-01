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
    fun accountOverviewReadModelExcludesRosterAndEndpointCredentials() {
        val overview = AccountOverviewUi(
            identity = FamilyIdentityUi(
                displayName = "管理员",
                enabled = true,
                role = FamilyRole.Owner,
                familyName = "乐乐一家",
            ),
            status = SyncStatus.Idle,
        )
        assertThat(overview.familyName).isEqualTo("乐乐一家")
        assertThat(overview.displayName).isEqualTo("管理员")
        assertThat(overview.status).isEqualTo(SyncStatus.Idle)
        assertThat(overview.familyNameLabel).isEqualTo("乐乐一家")
        // Product AC: technical endpoint credentials must not live on overview.
        val fieldNames = overview::class.java.declaredFields.map { it.name }
        assertThat(fieldNames).containsNoneOf(
            "members", "membersLoaded", "membersLoading", "membersError",
            "baseUrl", "serverHost", "serverPort", "serverScheme",
        )
        // Nested identity also has no endpoint fields.
        val identityFields = overview.identity::class.java.declaredFields.map { it.name }
        assertThat(identityFields).containsNoneOf(
            "baseUrl", "serverHost", "serverPort", "serverScheme",
        )
    }

    @Test
    fun membersDevicesReadModelOwnsRosterAndPendingApprovals() {
        val members = MembersDevicesUi(
            identity = FamilyIdentityUi(
                displayName = "管理员",
                enabled = true,
                role = FamilyRole.Owner,
                familyName = "乐乐一家",
            ),
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
        assertThat(members.displayName).isEqualTo("管理员")
        // No app-update or baby surface on members host UI.
        val fieldNames = members::class.java.declaredFields.map { it.name }
        assertThat(fieldNames).containsNoneOf("optionalAppUpdate", "babies", "current", "status")
    }

    @Test
    fun shellMergesOverviewAndMembersPreferringOverviewIdentity() {
        val overview = AccountOverviewUi(
            identity = FamilyIdentityUi(
                displayName = "妈妈",
                enabled = true,
                role = FamilyRole.Member,
                familyName = "乐乐一家",
            ),
            status = SyncStatus.Idle,
        )
        val members = MembersDevicesUi(
            identity = FamilyIdentityUi(
                displayName = "stale-members-name",
                enabled = true,
                role = FamilyRole.Member,
            ),
            members = listOf(
                FamilyMember("妈妈", FamilyRole.Member, true, "m-1"),
                FamilyMember("爸爸", FamilyRole.Owner, false, "m-2"),
            ),
            membersLoaded = true,
        )
        val ui = familyUiFromHosts(overview, members)
        assertThat(ui.familyName).isEqualTo("乐乐一家")
        assertThat(ui.displayName).isEqualTo("妈妈") // overview wins identity
        assertThat(ui.members).hasSize(2)
        assertThat(ui.membersLoaded).isTrue()
        assertThat(ui.optionalAppUpdate).isNull()
    }
}
