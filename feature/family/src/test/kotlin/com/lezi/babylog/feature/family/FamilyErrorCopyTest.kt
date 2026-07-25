package com.lezi.babylog.feature.family

import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.PUBLIC_CLEARTEXT_WARNING
import com.lezi.babylog.sync.SyncNotEnabledException
import com.lezi.babylog.sync.isPublicCleartextBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyErrorCopyTest {
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
        // Chinese + host/path must not bypass the filter (ISS-017 / F-C-004).
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
            "请先填写家庭服务器地址并加入家庭",
            familySyncError(SyncNotEnabledException(), "同步失败"),
        )
    }

    @Test
    fun syncStatesUseProductFacingChineseLabels() {
        assertEquals("未启用", syncStatusLabel(SyncStatus.Disabled))
        assertEquals("等待家庭 Wi‑Fi", syncStatusLabel(SyncStatus.BlockedOfflineHome))
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
        assertEquals("数据仅保存在本机 · 无需登录", familyStorageCopy(false))
        assertEquals(
            "记录本地优先，并同步到家庭服务器 · 无需云账号",
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
                showServerSetup = true,
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
