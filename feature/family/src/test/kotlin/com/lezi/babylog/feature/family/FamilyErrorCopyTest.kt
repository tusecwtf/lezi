package com.lezi.babylog.feature.family

import com.lezi.babylog.feature.family.members.formatFamilyDeviceLastUsed

import com.lezi.babylog.feature.family.components.*

import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.domain.family.FamilyWizardOutcome
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.SyncNotEnabledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy tests for family UI rules: save-result branching, dialog exclusivity,
 * member-list synthesis, control/primary/wizard surfaces, avatar roles, and
 * error desensitization. Exact multi-sentence product marketing copy is not
 * asserted here.
 */
class FamilyErrorCopyTest {
    @Test
    fun reclaimedFamilyCopyDistinguishesRecoveredAndRetryableData() {
        val session = SyncSession(
            familyId = "family-a",
            accessToken = "owner-token",
            deviceId = "device-a",
            role = FamilyRole.Owner,
            membershipId = "owner-membership",
        )

        assertEquals(
            "已接回家庭，数据恢复完成",
            familyWizardOutcomeCopy(
                FamilyWizardOutcome.Reclaimed(
                    session = session,
                    dataRecovery = InitialFamilyDataRecovery.Complete,
                ),
            ),
        )
        assertEquals(
            "已接回家庭，但数据同步失败，请在记录、汇总或成长页下拉重试",
            familyWizardOutcomeCopy(
                FamilyWizardOutcome.Reclaimed(
                    session = session,
                    dataRecovery = InitialFamilyDataRecovery.RetryRequired,
                ),
            ),
        )
    }

    @Test
    fun familyDialogStateIsMutuallyExclusive() {
        val wizard = FamilyDialog.Wizard(FamilyWizardMode.Join, FamilyWizardStep.Identity)
        val message = FamilyDialog.Message("保存失败", resume = wizard)

        assertEquals(wizard, familyDialogAfterDismiss(message))
        assertNull(
            familyDialogAfterDismiss(
                FamilyDialog.DeleteFamily(FamilyDialog.DeleteStage.Final),
            ),
        )

        assertEquals(
            wizard,
            familyDialogAfterDismiss(FamilyDialog.Message("已扫入邀请", resume = wizard)),
        )
        assertNull(familyDialogAfterDismiss(wizard))
    }

    @Test
    fun memberListOnlySynthesizesThisDeviceBeforeTheServerListLoads() {
        val fallback = familyMembersForDisplay(
            members = emptyList(),
            localDisplayName = LOCAL_FAMILY_DISPLAY_NAME,
            localRole = FamilyRole.Owner,
            localMembershipId = "self-membership",
            membersLoaded = false,
        )

        assertEquals(1, fallback.size)
        assertTrue(fallback.single().isSelf)
        assertEquals("self-membership", fallback.single().membershipId)

        val serverMembers = listOf(
            FamilyMember("妈妈", FamilyRole.Owner, isSelf = true, membershipId = "owner"),
            FamilyMember("爸爸", FamilyRole.Member, isSelf = false, membershipId = "member"),
        )
        assertEquals(
            serverMembers,
            familyMembersForDisplay(
                serverMembers,
                "忽略",
                FamilyRole.Owner,
                localMembershipId = "ignored",
                membersLoaded = true,
            ),
        )
        val loadedWithoutSelf = listOf(
            FamilyMember("家人", FamilyRole.Member, isSelf = false, membershipId = "other"),
        )
        assertEquals(
            loadedWithoutSelf,
            familyMembersForDisplay(
                loadedWithoutSelf,
                "不应合成",
                FamilyRole.Owner,
                localMembershipId = "self",
                membersLoaded = true,
            ),
        )
        assertTrue(
            familyMembersForDisplay(
                emptyList(),
                "不应合成",
                FamilyRole.Owner,
                localMembershipId = "self",
                membersLoaded = true,
            ).isEmpty(),
        )
    }

    @Test
    fun localPlaceholderDisplayNameIsNotTreatedAsCaregiverName() {
        assertEquals(
            "妈妈",
            familyMemberDisplayName(
                FamilyMember("妈妈", FamilyRole.Owner, isSelf = true, membershipId = "self"),
            ),
        )
        assertEquals(
            "家庭管理员",
            familyMemberDisplayName(
                FamilyMember(LOCAL_FAMILY_DISPLAY_NAME, FamilyRole.Owner, false, "owner-placeholder"),
            ),
        )
        assertEquals(
            "家庭成员",
            familyMemberDisplayName(
                FamilyMember(LOCAL_FAMILY_DISPLAY_NAME, FamilyRole.Member, false, "member-placeholder"),
            ),
        )
        assertTrue(familyMemberTitle(FamilyMember("妈妈", FamilyRole.Owner, true, "self")).endsWith(" ★"))
        assertFalse(familyMemberTitle(FamilyMember("爸爸", FamilyRole.Member, false, "other")).contains("★"))
        assertTrue(validateFamilyDisplayNameInput("  ") != null)
        assertTrue(validateFamilyDisplayNameInput(LOCAL_FAMILY_DISPLAY_NAME) != null)
        assertNull(validateFamilyDisplayNameInput("干爹"))
        assertTrue(validateFamilyNameInput("") != null)
        assertNull(validateFamilyNameInput("  我家  "))
        assertTrue(validateFamilyNameInput("家".repeat(65)) != null)
        assertTrue(validateFamilyNameInput("坏\n名") != null)
    }

    @Test
    fun hidesNetworkAndAddressDetailsFromFamilyCopy() {
        val leaky = listOf(
            "Failed to connect to /10.0.2.2:8765",
            "连接失败：Failed to connect to /10.0.2.2:8765",
            "连接失败：nas.local:8765",
            "同步错误 /data/user/0/com.lezi/files",
        )
        for (message in leaky) {
            val copy = familySyncError(IllegalStateException(message), "同步失败")
            assertFalse(copy.contains("10.0.2.2"))
            assertFalse(copy.contains("nas.local"))
            assertFalse(copy.contains("/data/user"))
            assertFalse(copy.contains("8765"))
            assertTrue(copy.isNotBlank())
        }
    }

    @Test
    fun keepsProductFacingFailureAndDeferredState() {
        val inviteExpired = "邀请码已失效"
        assertEquals(
            inviteExpired,
            familySyncError(IllegalArgumentException(inviteExpired), "加入失败"),
        )
        val deferred = familySyncError(SyncNotEnabledException(), "同步失败")
        assertTrue(deferred.isNotBlank())
        assertFalse(deferred.contains("Exception"))
        assertFalse(deferred.contains("SyncNotEnabled"))
    }

    @Test
    fun onlyMembersLoseAvatarEditingCapability() {
        assertTrue(canEditFamilyAvatar(FamilyRole.None))
        assertTrue(canEditFamilyAvatar(FamilyRole.Owner))
        assertFalse(canEditFamilyAvatar(FamilyRole.Member))
        assertTrue(canManageFamilyBabies(FamilyRole.None))
        assertTrue(canManageFamilyBabies(FamilyRole.Owner))
        assertFalse(canManageFamilyBabies(FamilyRole.Member))
    }

    @Test
    fun unjoinedOverviewCopyAllowsLocalUseWithoutRequiringSyncFirst() {
        val label = overviewSyncStatusLabel(
            status = com.lezi.babylog.core.model.SyncStatus.Disabled,
            isJoined = false,
            lastSuccessAt = null,
            waitingForApproval = false,
        )
        assertTrue(label.contains("本机"))
        assertFalse(label.contains("必须"))
        assertFalse(label.contains("先同步"))
        assertTrue(unjoinedFamilyCardSubtitle().contains("新建或加入"))

        val card = buildFamilyOverviewCard(
            isJoined = false,
            role = FamilyRole.None,
            endpointConfigured = false,
            familyName = null,
            babyNickname = "年年",
            localDisplayName = "",
            memberCount = 0,
            membersLoaded = false,
            status = com.lezi.babylog.core.model.SyncStatus.Disabled,
            lastSuccessAt = null,
            waitingForApproval = false,
        )
        assertTrue(card.showCreateJoin)
        assertEquals(label, card.syncStatusLabel)
    }

    @Test
    fun overviewSyncSummaryNeverClaimsAlignmentBeforeTheFirstSuccessfulSync() {
        assertEquals(
            "尚无成功同步",
            overviewSyncStatusLabel(
                status = SyncStatus.Idle,
                isJoined = true,
                lastSuccessAt = null,
                waitingForApproval = false,
            ),
        )
        assertEquals(
            "等待管理员确认",
            overviewSyncStatusLabel(
                status = SyncStatus.Idle,
                isJoined = false,
                lastSuccessAt = null,
                waitingForApproval = true,
            ),
        )
    }

    @Test
    fun reauthRequiredKeepsLocalDataCopyAndNeverClaimsDeviceDeletion() {
        val overview = overviewSyncStatusLabel(
            status = SyncStatus.ReauthRequired,
            isJoined = false,
            lastSuccessAt = null,
            waitingForApproval = false,
        )
        assertTrue(overview.contains("重新登录或申请"))
        assertFalse(overview.contains("设备已删除"))
    }

    @Test
    fun familyControlVisibilityByJoinAndRole() {
        val ownerJoined = familyControlVisibility(isJoined = true, role = FamilyRole.Owner)
        assertFalse(ownerJoined.showJoin)
        assertFalse(ownerJoined.showCreateFamily)
        assertTrue(ownerJoined.showJoinedActions)
        assertTrue(ownerJoined.showRemoveMember)

        val memberJoined = familyControlVisibility(isJoined = true, role = FamilyRole.Member)
        assertFalse(memberJoined.showJoin)
        assertFalse(memberJoined.showCreateFamily)
        assertTrue(memberJoined.showJoinedActions)
        assertFalse(memberJoined.showRemoveMember)

        val unjoined = familyControlVisibility(isJoined = false, role = FamilyRole.None)
        assertTrue(unjoined.showJoin)
        assertTrue(unjoined.showCreateFamily)
        assertFalse(unjoined.showJoinedActions)
        assertFalse(unjoined.showRemoveMember)
    }

    @Test
    fun canRemoveFamilyMemberOnlyForOwnerOnOtherMembers() {
        val member = FamilyMember("爸爸", FamilyRole.Member, isSelf = false, membershipId = "m1")
        val selfMember = FamilyMember("妈妈", FamilyRole.Owner, isSelf = true, membershipId = "o1")
        val otherOwner = FamilyMember("妈妈", FamilyRole.Owner, isSelf = false, membershipId = "o1")
        assertTrue(canRemoveFamilyMember(viewerIsOwner = true, member = member))
        assertFalse(canRemoveFamilyMember(viewerIsOwner = false, member = member))
        assertFalse(canRemoveFamilyMember(viewerIsOwner = true, member = selfMember))
        assertFalse(canRemoveFamilyMember(viewerIsOwner = true, member = otherOwner))
    }

    @Test
    fun deviceLastUsedCopyIsRelativeAndFallsBackToACalendarDate() {
        val now = 1_754_000_000L
        assertEquals("刚刚", formatFamilyDeviceLastUsed(now, now))
        assertEquals("2 分钟前", formatFamilyDeviceLastUsed(now - 120, now))
        assertEquals("3 小时前", formatFamilyDeviceLastUsed(now - 10_800, now))
        assertEquals("昨天", formatFamilyDeviceLastUsed(now - 90_000, now))
        assertEquals("3 天前", formatFamilyDeviceLastUsed(now - 259_200, now))
        assertTrue(formatFamilyDeviceLastUsed(now - 700_000, now).matches(Regex("\\d{2}-\\d{2}")))
    }

    @Test
    fun primarySurfaceFlagsByJoinRoleAndNetwork() {
        assertTrue(isEndpointConfigured("192.168.50.4", ""))
        assertFalse(isEndpointConfigured("", ""))

        val joinedConfigured = familyPrimarySurface(
            isJoined = true,
            role = FamilyRole.Owner,
            endpointConfigured = true,
        )
        assertTrue(joinedConfigured.compactJoined)
        assertFalse(joinedConfigured.showCreateJoin)

        val joinedMember = familyPrimarySurface(
            isJoined = true,
            role = FamilyRole.Member,
            endpointConfigured = true,
        )
        assertTrue(joinedMember.compactJoined)

        val unjoined = familyPrimarySurface(
            isJoined = false,
            role = FamilyRole.None,
            endpointConfigured = false,
        )
        assertFalse(unjoined.compactJoined)
        assertTrue(unjoined.showCreateJoin)
    }

    @Test
    fun familyWizardStartsAtNetworkOnlyWhenNotConfigured() {
        assertEquals(
            FamilyWizardStep.Endpoint,
            familyWizardInitialStep(endpointConfigured = false),
        )
        assertEquals(
            FamilyWizardStep.Identity,
            familyWizardInitialStep(endpointConfigured = true),
        )
        assertEquals(
            FamilyPrimaryCta.CREATE,
            familyWizardTitle(FamilyWizardMode.Create, FamilyWizardStep.Identity),
        )
        assertEquals(
            FamilyPrimaryCta.JOIN,
            familyWizardTitle(FamilyWizardMode.Join, FamilyWizardStep.Identity),
        )
        // Progress must not claim ✓ before the endpoint is actually trusted
        val dishonestWouldBe = familyWizardProgress(
            FamilyWizardMode.Join,
            endpointReady = false,
        )
        assertEquals("1 家庭服务器", dishonestWouldBe.first)
        val ready = familyWizardProgress(
            FamilyWizardMode.Join,
            endpointReady = true,
        )
        assertEquals("✓ 家庭服务器", ready.first)
        assertTrue(
            isWizardSessionDialog(
                FamilyDialog.Message("x", resume = FamilyDialog.Wizard(
                    FamilyWizardMode.Join,
                    FamilyWizardStep.Identity,
                )),
            ),
        )
        assertFalse(isWizardSessionDialog(FamilyDialog.MembersList))
    }

    @Test
    fun overviewCardPrimaryEntriesFollowJoinAndRole() {
        val joined = buildFamilyOverviewCard(
            isJoined = true,
            role = FamilyRole.Owner,
            endpointConfigured = true,
            familyName = "乐乐一家",
            babyNickname = "乐乐",
            localDisplayName = "妈妈",
            memberCount = 3,
            membersLoaded = true,
            status = SyncStatus.Idle,
        )
        assertFalse(joined.showCreateJoin)
        assertTrue(joined.showMembersEntry)
        // Path A: the overview card is honest/read-only; rename lives in members.
        assertFalse(joined.showRenameFamily)
        assertFalse(joined.syncStatusLabel.contains("Idle"))
        assertFalse(joined.syncStatusLabel.contains("SSID"))

        val unjoined = buildFamilyOverviewCard(
            isJoined = false,
            role = FamilyRole.None,
            endpointConfigured = false,
            familyName = null,
            babyNickname = null,
            localDisplayName = LOCAL_FAMILY_DISPLAY_NAME,
            memberCount = 0,
            membersLoaded = false,
            status = SyncStatus.Disabled,
        )
        assertTrue(unjoined.showCreateJoin)
        assertFalse(unjoined.showMembersEntry)
        assertFalse(unjoined.showRenameFamily)
    }
}
