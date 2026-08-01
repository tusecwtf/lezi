package com.lezi.babylog.feature.family

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.feature.family.members.MembersDevicesHost
import com.lezi.babylog.feature.family.overview.AccountOverviewHost
import com.lezi.babylog.feature.family.wizard.AccountFamilyWizardHost
import java.lang.reflect.Modifier
import org.junit.Test

/**
 * Public seams for ticket 24 (self-confirmed design notes):
 *
 * - [AccountOverviewHost]: 账户概览 read model, one-line 同步状态 projection inputs,
 *   optional app-update entry, and thin baby-profile adapters. Does not own roster
 *   commands or wizard lifecycle.
 * - [MembersDevicesHost]: roster/device refresh, approval, rename, revoke, remove,
 *   invite-QR create, leave/logout/delete family. Does not own wizard or app-update.
 * - [AccountFamilyWizardHost]: thin lifecycle over FamilyWizardController including
 *   ticket-23 member-login QR ops. Does not own member roster commands.
 *
 * Tests hit declared public methods only — not private helpers or file paths.
 */
class FamilyHostCommandSurfaceTest {
    @Test
    fun accountOverviewHostOwnsReadModelAppUpdateAndBabyThinCommandsOnly() {
        val surface = publicMethods(AccountOverviewHost::class.java)

        assertThat(surface).containsAtLeastElementsIn(
            listOf(
                "getUi",
                "getAppUpdateOutcome",
                "getCheckingAppUpdate",
                "getInstallingAppUpdate",
                "openOptionalAppUpdate",
                "dismissOptionalAppUpdate",
                "dismissAppUpdateOutcome",
                "checkAppUpdate",
                "installOptionalUpdate",
                "setCurrent",
                "updateBaby",
                "deleteBaby",
                "previewMerge",
                "merge",
            ),
        )
        assertThat(surface).containsNoneOf(
            "refreshMembers",
            "removeMember",
            "createMemberLoginQr",
            "submitFamilyWizard",
            "verifyMemberLoginQr",
            "claimMemberLoginQr",
            "connectEndpoint",
            "leave",
            "deleteFamily",
        )
    }

    @Test
    fun membersDevicesHostOwnsRosterRefreshApprovalsRenamesAndInviteEntryOnly() {
        val surface = publicMethods(MembersDevicesHost::class.java)

        assertThat(surface).containsAtLeastElementsIn(
            listOf(
                "getUi",
                "refreshMembers",
                "refreshFamilyForDeletion",
                "approveNewMemberLogin",
                "bindExistingMemberLogin",
                "rejectMemberLogin",
                "createMemberLoginQr",
                "removeMember",
                "renameFamily",
                "updateMyDisplayName",
                "addFamilyMember",
                "renameFamilyMember",
                "renameFamilyDevice",
                "decideMemberRename",
                "cancelMyMemberRename",
                "leave",
                "logoutCurrentDevice",
                "revokeFamilyDevice",
                "deleteFamily",
            ),
        )
        assertThat(surface).containsNoneOf(
            "openOptionalAppUpdate",
            "checkAppUpdate",
            "installOptionalUpdate",
            "submitFamilyWizard",
            "verifyMemberLoginQr",
            "claimMemberLoginQr",
            "connectEndpoint",
            "updateBaby",
            "deleteBaby",
            "setCurrent",
        )
    }

    @Test
    fun accountFamilyWizardHostThinDelegatesControllerIncludingMemberLoginQr() {
        val surface = publicMethods(AccountFamilyWizardHost::class.java)

        assertThat(surface).containsAtLeastElementsIn(
            listOf(
                "getFamilyWizardState",
                "getVerifiedEndpoint",
                "submitFamilyWizard",
                "beginFamilyWizard",
                "connectEndpoint",
                "trustCertificate",
                "keepOffline",
                "forgetEndpoint",
                "consumeFamilyWizardCompletion",
                "checkMemberApproval",
                "cancelMemberApproval",
                "verifyMemberLoginQr",
                "cancelMemberLoginQr",
                "claimMemberLoginQr",
                "retryMemberLoginQrRecovery",
            ),
        )
        assertThat(surface).containsNoneOf(
            "refreshMembers",
            "removeMember",
            "createMemberLoginQr",
            "openOptionalAppUpdate",
            "updateBaby",
            "deleteFamily",
            "leave",
        )
    }

    private fun publicMethods(type: Class<*>): Set<String> =
        type.declaredMethods
            .asSequence()
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .toSet()
}
