package com.lezi.babylog.feature.family

import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.PUBLIC_CLEARTEXT_WARNING
import com.lezi.babylog.sync.SyncNotEnabledException
import com.lezi.babylog.sync.isPublicCleartextBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyErrorCopyTest {
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
                showServerSetup = false,
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
                showServerSetup = false,
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
                showServerSetup = false,
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
        assertFalse(joinedConfigured.showNetworkEditorsOnPrimary)
        assertTrue(joinedConfigured.showNetworkSecondaryEntry)
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
        assertFalse(joinedMember.showNetworkEditorsOnPrimary)

        val unjoined = familyPrimarySurface(
            isJoined = false,
            role = FamilyRole.None,
            networkConfigured = false,
        )
        assertFalse(unjoined.compactJoined)
        assertFalse(unjoined.showNetworkEditorsOnPrimary)
        assertTrue(unjoined.showCreateJoin)
        assertTrue(unjoined.showNetworkSecondaryEntry)
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
