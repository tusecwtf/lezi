package com.lezi.babylog.feature.family

import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.domain.BabyMergePreview
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.PUBLIC_CLEARTEXT_WARNING
import com.lezi.babylog.sync.SyncNotEnabledException
import com.lezi.babylog.sync.isPublicCleartextBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyErrorCopyTest {
    @Test
    fun babyMergeSummaryIncludesRecordsAndCarePlans() {
        assertEquals(
            "3 条记录 · 2 个护理计划",
            mergeDataSummary(
                BabyMergePreview(
                    sourceBabyId = 1,
                    sourceNickname = "临时",
                    targetBabyId = 2,
                    targetNickname = "年年",
                    recordCount = 3,
                    carePlanCount = 2,
                ),
            ),
        )
    }

    @Test
    fun networkSaveContinuationUsesExplicitOutcomeInsteadOfMessageCopy() {
        val messages = mutableListOf<String>()
        var continuationCount = 0

        deliverNetworkSaveResult(
            NetworkSaveResult.Saved("文案已经换掉"),
            onMessage = messages::add,
            onSaved = { continuationCount += 1 },
        )
        deliverNetworkSaveResult(
            NetworkSaveResult.Failed("错误说明里即使写着已保存，也仍是失败"),
            onMessage = messages::add,
            onSaved = { continuationCount += 1 },
        )

        assertEquals(listOf("文案已经换掉", "错误说明里即使写着已保存，也仍是失败"), messages)
        assertEquals(1, continuationCount)
    }

    @Test
    fun familyDialogStateIsMutuallyExclusiveAndUsesOneLocalPlaceholder() {
        val network = FamilyDialog.NetworkSettings
        val message = FamilyDialog.Message("保存失败", resume = network)

        assertEquals(network, familyDialogAfterDismiss(message))
        assertNull(
            familyDialogAfterDismiss(
                FamilyDialog.DeleteFamily(FamilyDialog.DeleteStage.Final),
            ),
        )
        assertEquals("我（本机）", LOCAL_FAMILY_DISPLAY_NAME)
        assertEquals(
            "妈妈",
            familyMemberDisplayName(
                FamilyMember("妈妈", FamilyRole.Owner, isSelf = true, membershipId = "self"),
            ),
        )
    }

    @Test
    fun memberListOnlySynthesizesThisDeviceBeforeTheServerListLoads() {
        val fallback = familyMembersForDisplay(
            members = emptyList(),
            localDisplayName = "我（本机）",
            localRole = FamilyRole.Owner,
            localMembershipId = "self-membership",
            membersLoaded = false,
        )

        assertEquals(1, fallback.size)
        assertTrue(fallback.single().isSelf)
        assertEquals("self-membership", fallback.single().membershipId)
        assertEquals("我（本机）", familyMemberDisplayName(fallback.single()))

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
        assertEquals("爸爸", familyMemberDisplayName(serverMembers.last()))
        assertEquals(
            "家庭管理员",
            familyMemberDisplayName(
                FamilyMember("我（本机）", FamilyRole.Owner, false, "owner-placeholder"),
            ),
        )
        assertEquals(
            "家庭成员",
            familyMemberDisplayName(
                FamilyMember("我（本机）", FamilyRole.Member, false, "member-placeholder"),
            ),
        )
        assertEquals(
            "家庭管理员",
            familyMemberDisplayName(
                FamilyMember("我（本机）", FamilyRole.Owner, false, "owner-placeholder-2"),
            ),
        )
        assertEquals(
            "妈妈 ★",
            familyMemberTitle(FamilyMember("妈妈", FamilyRole.Owner, true, "self")),
        )
        assertEquals(
            "爸爸",
            familyMemberTitle(FamilyMember("爸爸", FamilyRole.Member, false, "other")),
        )
        assertEquals("请填写家庭称呼", validateFamilyDisplayNameInput("  "))
        assertEquals(
            "请填写家庭称呼，不能使用本机占位名",
            validateFamilyDisplayNameInput("我（本机）"),
        )
        assertEquals(null, validateFamilyDisplayNameInput("干爹"))
        assertEquals("待刷新 · 管理员", familyMemberSummary(1, FamilyRole.Owner, loaded = false))
        assertEquals("2 位 · 管理员", familyMemberSummary(2, FamilyRole.Owner, loaded = true))
        assertEquals("我的家庭", displayFamilyName(null))
        assertEquals("我的家庭", displayFamilyName("  "))
        assertEquals("乐乐一家", displayFamilyName("  乐乐一家  "))
        assertEquals("年年的家庭", displayFamilyName(null, babyNickname = "年年"))
        assertEquals("年年的家庭", displayFamilyName("", babyNickname = "  年年  "))
        assertEquals(null, validateFamilyNameInput(""))
        assertEquals(null, validateFamilyNameInput("  我家  "))
        assertEquals("家庭名最多 64 个字符", validateFamilyNameInput("家".repeat(65)))
        assertEquals("家庭名不能包含控制字符", validateFamilyNameInput("坏\n名"))
    }

    @Test
    fun hidesNetworkAndAddressDetailsFromFamilyCopy() {
        val copy = familySyncError(
            error = IllegalStateException("Failed to connect to /10.0.2.2:8765"),
            fallback = "生成共享码失败",
        )

        assertEquals("家庭同步服务暂未连接，请稍后重试", copy)
        assertFalse(copy.contains("10.0.2.2"))
        assertEquals(
            "家庭同步服务暂未连接，请稍后重试",
            familySyncError(
                IllegalStateException("连接失败：Failed to connect to /10.0.2.2:8765"),
                "同步失败",
            ),
        )
        // Localized messages containing host or path details must still be filtered.
        assertEquals(
            "家庭同步服务暂未连接，请稍后重试",
            familySyncError(
                IllegalStateException("连接失败：nas.local:8765"),
                "同步失败",
            ),
        )
        assertEquals(
            "家庭同步服务暂未连接，请稍后重试",
            familySyncError(
                IllegalStateException("同步错误 /data/user/0/com.lezi/files"),
                "同步失败",
            ),
        )
    }

    @Test
    fun keepsProductFacingChineseFailureAndDeferredState() {
        assertEquals(
            "邀请码已失效",
            familySyncError(IllegalArgumentException("邀请码已失效"), "加入失败"),
        )
        assertEquals(
            "请先填写家庭服务器地址并绑定 Wi‑Fi 名称后加入家庭",
            familySyncError(SyncNotEnabledException(), "同步失败"),
        )
    }

    @Test
    fun syncStatesUseProductFacingChineseLabels() {
        // Network-sheet detail labels (may be slightly technical for troubleshooting).
        assertEquals(
            "未加入家庭（请先保存服务器与 Wi‑Fi 名称）",
            syncStatusLabel(SyncStatus.Disabled),
        )
        assertEquals(
            "网络已配置 · 尚未加入家庭",
            syncStatusLabel(
                SyncStatus.Disabled,
                hasServer = true,
                hasSsid = true,
                isJoined = false,
            ),
        )
        assertEquals(
            "未加入家庭（请先保存 Wi‑Fi 名称）",
            syncStatusLabel(SyncStatus.Disabled, hasServer = true, hasSsid = false),
        )
        assertEquals(
            "等待家庭 Wi‑Fi 或服务器可达",
            syncStatusLabel(SyncStatus.BlockedOfflineHome),
        )
        assertEquals("空闲", syncStatusLabel(SyncStatus.Idle))
        assertEquals("同步中", syncStatusLabel(SyncStatus.Syncing))
        assertEquals("同步错误", syncStatusLabel(SyncStatus.Error))
    }

    @Test
    fun overviewSyncPhrasesAreResultOrientedWithoutTechEnums() {
        assertEquals(
            "还没和家人一起记",
            overviewSyncStatusLabel(SyncStatus.Disabled, isJoined = false),
        )
        assertEquals(
            "家人记录已对齐",
            overviewSyncStatusLabel(SyncStatus.Idle, isJoined = true),
        )
        assertEquals(
            "连上家里 Wi‑Fi 后才能同步",
            overviewSyncStatusLabel(SyncStatus.BlockedOfflineHome, isJoined = true),
        )
        assertEquals(
            "正在同步…",
            overviewSyncStatusLabel(SyncStatus.Syncing, isJoined = true),
        )
        assertEquals(
            "同步遇到问题",
            overviewSyncStatusLabel(SyncStatus.Error, isJoined = true),
        )
        for (status in SyncStatus.entries) {
            val phrase = overviewSyncStatusLabel(status, isJoined = status != SyncStatus.Disabled)
            assertFalse(overviewCopyLooksTechnical(phrase))
            assertFalse(phrase.contains("Idle"))
            assertFalse(phrase.contains("空闲"))
            assertFalse(phrase.contains("SSID"))
        }
    }

    @Test
    fun overviewCardForbidsNetworkTechDetailsAndKeepsPrimaryEntries() {
        val joined = buildFamilyOverviewCard(
            isJoined = true,
            role = FamilyRole.Owner,
            networkConfigured = true,
            familyName = "乐乐一家",
            babyNickname = "乐乐",
            localDisplayName = "妈妈",
            memberCount = 3,
            membersLoaded = true,
            status = SyncStatus.Idle,
        )
        assertEquals("乐乐一家", joined.familyNameLabel)
        assertEquals("3 位家人", joined.memberCountLabel)
        assertEquals("妈妈 ★", joined.selfTitle)
        assertEquals("家人记录已对齐", joined.syncStatusLabel)
        assertTrue(joined.showInvite)
        assertFalse(joined.showCreateJoin)
        assertTrue(joined.showMembersEntry)
        assertTrue(joined.showRenameFamily)
        assertEquals(
            FamilyPrimaryCta.INVITE,
            familyPrimarySurface(true, FamilyRole.Owner, true).inviteLabel,
        )
        listOf(
            joined.familyNameLabel,
            joined.memberCountLabel,
            joined.selfTitle,
            joined.syncStatusLabel,
        ).forEach { line ->
            assertFalse(overviewCopyLooksTechnical(line))
        }

        val unjoined = buildFamilyOverviewCard(
            isJoined = false,
            role = FamilyRole.None,
            networkConfigured = false,
            familyName = null,
            babyNickname = null,
            localDisplayName = LOCAL_FAMILY_DISPLAY_NAME,
            memberCount = 0,
            membersLoaded = false,
            status = SyncStatus.Disabled,
        )
        assertTrue(unjoined.showCreateJoin)
        assertFalse(unjoined.showInvite)
        assertFalse(unjoined.showMembersEntry)
        assertEquals("还没和家人一起记", unjoined.syncStatusLabel)
        assertFalse(overviewCopyLooksTechnical(unjoined.syncStatusLabel))
    }

    @Test
    fun onlyMembersLoseAvatarEditingCapability() {
        assertEquals(true, canEditFamilyAvatar(FamilyRole.None))
        assertEquals(true, canEditFamilyAvatar(FamilyRole.Owner))
        assertEquals(false, canEditFamilyAvatar(FamilyRole.Member))
    }

    @Test
    fun storageCopyReflectsWhetherFamilySyncIsActive() {
        assertEquals("仅本机", familyStorageCopy(false))
        assertEquals(
            "本机 + 家庭服务器",
            familyStorageCopy(true),
        )
    }

    @Test
    fun joinedFamilyHidesServerAndJoinControlsButKeepsRoleActions() {
        assertEquals(
            FamilyControlVisibility(
                showJoin = false,
                showCreateFamily = false,
                showInvite = true,
                showJoinedActions = true,
                showLeave = false,
            ),
            familyControlVisibility(isJoined = true, role = FamilyRole.Owner),
        )
        assertEquals(
            FamilyControlVisibility(
                showJoin = false,
                showCreateFamily = false,
                showInvite = false,
                showJoinedActions = true,
                showLeave = true,
            ),
            familyControlVisibility(isJoined = true, role = FamilyRole.Member),
        )
    }

    @Test
    fun localFamilySetupIsAvailableOnlyBeforeJoining() {
        assertEquals(
            FamilyControlVisibility(
                showJoin = true,
                showCreateFamily = true,
                showInvite = false,
                showJoinedActions = false,
                showLeave = false,
            ),
            familyControlVisibility(isJoined = false, role = FamilyRole.None),
        )
    }

    @Test
    fun primaryHidesNetworkEditorsWhenJoinedAndConfigured() {
        assertTrue(isHomeLanNetworkConfigured("192.168.50.4", "", listOf("Home")))
        assertFalse(isHomeLanNetworkConfigured("", "", emptyList()))
        assertFalse(isHomeLanNetworkConfigured("192.168.50.4", "", emptyList()))

        // Overview primary: invite only for owner; sync/leave/delete live in network sheet.
        val joinedConfigured = familyPrimarySurface(
            isJoined = true,
            role = FamilyRole.Owner,
            networkConfigured = true,
        )
        assertTrue(joinedConfigured.compactJoined)
        assertTrue(joinedConfigured.showInvite)
        assertFalse(joinedConfigured.showCreateJoin)
        assertEquals(FamilyPrimaryCta.INVITE, joinedConfigured.inviteLabel)

        val joinedMember = familyPrimarySurface(
            isJoined = true,
            role = FamilyRole.Member,
            networkConfigured = true,
        )
        assertTrue(joinedMember.compactJoined)
        assertFalse(joinedMember.showInvite)

        val unjoined = familyPrimarySurface(
            isJoined = false,
            role = FamilyRole.None,
            networkConfigured = false,
        )
        assertFalse(unjoined.compactJoined)
        assertTrue(unjoined.showCreateJoin)
        assertEquals(FamilyPrimaryCta.CREATE, unjoined.createLabel)
        assertEquals(FamilyPrimaryCta.JOIN, unjoined.joinLabel)

        // Network-sheet ops still available via control visibility.
        val ownerOps = familyControlVisibility(isJoined = true, role = FamilyRole.Owner)
        assertTrue(ownerOps.showJoinedActions)
        assertFalse(ownerOps.showLeave)
        val memberOps = familyControlVisibility(isJoined = true, role = FamilyRole.Member)
        assertTrue(memberOps.showJoinedActions)
        assertTrue(memberOps.showLeave)
    }

    @Test
    fun familyWizardStartsAtNetworkOnlyWhenNotConfigured() {
        assertEquals(
            FamilyWizardStep.Network,
            familyWizardInitialStep(networkConfigured = false),
        )
        assertEquals(
            FamilyWizardStep.Identity,
            familyWizardInitialStep(networkConfigured = true),
        )
        assertEquals(
            "配置家庭网络",
            familyWizardTitle(FamilyWizardMode.Create, FamilyWizardStep.Network),
        )
        assertEquals(
            FamilyPrimaryCta.CREATE,
            familyWizardTitle(FamilyWizardMode.Create, FamilyWizardStep.Identity),
        )
        assertEquals(
            FamilyPrimaryCta.JOIN,
            familyWizardTitle(FamilyWizardMode.Join, FamilyWizardStep.Identity),
        )
        // Wizard dialog is mutually exclusive; message can resume into identity step.
        val wizard = FamilyDialog.Wizard(FamilyWizardMode.Join, FamilyWizardStep.Identity)
        assertEquals(
            wizard,
            familyDialogAfterDismiss(FamilyDialog.Message("已扫入邀请", resume = wizard)),
        )
        assertNull(familyDialogAfterDismiss(wizard))
    }

    @Test
    fun overviewCtasNeverExposeSplitScanOrLegacyInviteCopy() {
        val owner = familyPrimarySurface(true, FamilyRole.Owner, networkConfigured = true)
        assertEquals("邀请家人", owner.inviteLabel)
        assertFalse(owner.inviteLabel.contains("生成邀请"))
        assertFalse(owner.inviteLabel.contains("二维码"))

        val unjoined = familyPrimarySurface(false, FamilyRole.None, networkConfigured = false)
        assertEquals("新建家庭", unjoined.createLabel)
        assertEquals("加入家庭", unjoined.joinLabel)
        // Scan is not a primary CTA string — it lives inside the join wizard only.
        assertFalse(unjoined.joinLabel.contains("扫码"))
        assertFalse(unjoined.joinLabel.contains("邀请码"))
    }

    @Test
    fun publicCleartextServerAddressUsesSharedWarningCopy() {
        assertTrue(isPublicCleartextBaseUrl("http://example.com:8765"))
        assertFalse(isPublicCleartextBaseUrl("http://192.168.50.4:8765"))
        assertTrue(PUBLIC_CLEARTEXT_WARNING.contains("公网 HTTP"))
    }
}
