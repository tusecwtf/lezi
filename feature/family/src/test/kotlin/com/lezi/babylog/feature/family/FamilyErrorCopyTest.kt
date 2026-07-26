package com.lezi.babylog.feature.family

import com.lezi.babylog.core.model.SyncStatus
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
            LOCAL_FAMILY_DISPLAY_NAME,
            familyMemberDisplayName(FamilyMember(null, FamilyRole.Owner, isSelf = true)),
        )
    }

    @Test
    fun memberListAlwaysShowsThisDeviceWithoutExposingAnIdentifier() {
        val fallback = familyMembersForDisplay(
            members = emptyList(),
            localDisplayName = "我（本机）",
            localRole = FamilyRole.Owner,
        )

        assertEquals(1, fallback.size)
        assertTrue(fallback.single().isSelf)
        assertEquals("我（本机）", familyMemberDisplayName(fallback.single()))

        val serverMembers = listOf(
            FamilyMember("妈妈", FamilyRole.Owner, isSelf = true),
            FamilyMember(null, FamilyRole.Member, isSelf = false),
        )
        assertEquals(serverMembers, familyMembersForDisplay(serverMembers, "忽略", FamilyRole.Owner))
        assertEquals("家庭成员", familyMemberDisplayName(serverMembers.last()))
        assertEquals(
            "家庭管理员",
            familyMemberDisplayName(FamilyMember(null, FamilyRole.Owner, isSelf = false)),
        )
        assertEquals(
            "家庭成员",
            familyMemberDisplayName(FamilyMember("我（本机）", FamilyRole.Member, isSelf = false)),
        )
        assertEquals(
            "家庭管理员",
            familyMemberDisplayName(FamilyMember("我（本机）", FamilyRole.Owner, isSelf = false)),
        )
        assertEquals("待刷新 · 管理员", familyMemberSummary(1, FamilyRole.Owner, loaded = false))
        assertEquals("2 位 · 管理员", familyMemberSummary(2, FamilyRole.Owner, loaded = true))
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
    fun onlyMembersLoseAvatarEditingCapability() {
        assertEquals(true, canEditFamilyAvatar(FamilyRole.None))
        assertEquals(true, canEditFamilyAvatar(FamilyRole.Owner))
        assertEquals(false, canEditFamilyAvatar(FamilyRole.Member))
    }

    @Test
    fun overviewCardShowsResultSyncAndFamilyIdentityNotDeviceId() {
        assertEquals(
            "还没和家人一起记" to "新建或加入家庭后即可一起记录",
            overviewSyncStatusCopy(SyncStatus.Disabled, isJoined = false),
        )
        assertEquals(
            "家人记录已对齐" to "打开应用或下拉即可更新",
            overviewSyncStatusCopy(SyncStatus.Idle, isJoined = true),
        )
        assertEquals(
            "连上家里 Wi‑Fi 后才能同步" to "出门在外时记录会先留在本机",
            overviewSyncStatusCopy(SyncStatus.BlockedOfflineHome, isJoined = true),
        )
        assertEquals(
            "同步遇到问题" to "可在网络设置中查看并重试",
            overviewSyncStatusCopy(SyncStatus.Error, isJoined = true),
        )

        assertEquals(
            "暂无宝宝档案" to "仅本机 · 还没有家人一起记",
            overviewFamilyIdentityCopy(
                isJoined = false,
                babyNicknames = emptyList(),
                memberCount = 0,
                membersLoaded = false,
                myDisplayName = "我（本机）",
                role = FamilyRole.None,
            ),
        )
        assertEquals(
            "乐乐、豆豆" to "2 位家人 · 我是妈妈（管理员 ★）",
            overviewFamilyIdentityCopy(
                isJoined = true,
                babyNicknames = listOf("乐乐", "豆豆"),
                memberCount = 2,
                membersLoaded = true,
                myDisplayName = "妈妈",
                role = FamilyRole.Owner,
            ),
        )
        assertEquals(
            "乐乐" to "家人待刷新 · 我是爸爸（成员）",
            overviewFamilyIdentityCopy(
                isJoined = true,
                babyNicknames = listOf("乐乐"),
                memberCount = 1,
                membersLoaded = false,
                myDisplayName = "爸爸",
                role = FamilyRole.Member,
            ),
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

        val joinedConfigured = familyPrimarySurface(
            isJoined = true,
            role = FamilyRole.Owner,
            networkConfigured = true,
        )
        assertTrue(joinedConfigured.compactJoined)
        assertTrue(joinedConfigured.showInvite)
        assertTrue(joinedConfigured.showJoinedActions)
        assertFalse(joinedConfigured.showCreateJoin)
        // compact owner primary = invite + sync/delete path, not create/join stack
        assertTrue(joinedConfigured.showInvite && joinedConfigured.showJoinedActions)
        assertFalse(joinedConfigured.showLeave)

        val joinedMember = familyPrimarySurface(
            isJoined = true,
            role = FamilyRole.Member,
            networkConfigured = true,
        )
        assertTrue(joinedMember.compactJoined)
        assertFalse(joinedMember.showInvite)
        assertTrue(joinedMember.showLeave)

        val unjoined = familyPrimarySurface(
            isJoined = false,
            role = FamilyRole.None,
            networkConfigured = false,
        )
        assertFalse(unjoined.compactJoined)
        assertTrue(unjoined.showCreateJoin)
    }

    @Test
    fun syncSessionDeviceIdIsTheDisplayedReplicationIdentity() {
        assertEquals(
            "sync-device",
            familyDeviceId(syncDeviceId = "sync-device", localDeviceId = "legacy-device"),
        )
        assertEquals(
            "legacy-device",
            familyDeviceId(syncDeviceId = "", localDeviceId = "legacy-device"),
        )
    }

    @Test
    fun publicCleartextServerAddressUsesSharedWarningCopy() {
        assertTrue(isPublicCleartextBaseUrl("http://example.com:8765"))
        assertFalse(isPublicCleartextBaseUrl("http://192.168.50.4:8765"))
        assertTrue(PUBLIC_CLEARTEXT_WARNING.contains("公网 HTTP"))
    }
}
