package com.lezi.babylog.feature.family

import com.lezi.babylog.feature.family.members.RemoveMemberConfirmDialog

import com.lezi.babylog.feature.family.members.LogoutCurrentDeviceDialog
import com.lezi.babylog.feature.family.members.DeviceRemovedReceiptDialog
import com.lezi.babylog.sync.DeviceRemovedCleanupReceipt

import com.lezi.babylog.feature.family.members.RevokeFamilyDeviceDialog

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.feature.family.members.FamilyMembersListSheet
import com.lezi.babylog.feature.family.members.MembersDevicesUi
import com.lezi.babylog.feature.family.FamilyIdentityUi
import com.lezi.babylog.feature.family.members.LeaveFamilyDialog
import com.lezi.babylog.feature.family.members.DeleteFamilyDialog
import com.lezi.babylog.feature.family.components.FamilyDialog
import com.lezi.babylog.sync.FamilyDevice
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.backend.PendingMemberLoginRequest
import com.lezi.babylog.sync.backend.PendingMemberRenameRequest
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FamilyMembersDevicesPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ownerSeesEveryAuthorizedDevicePendingRequestAndNoTechnicalFields() {
        val now = System.currentTimeMillis() / 1_000L
        var revokedDevice: String? = null
        compose.setContent {
            LeziTheme {
                FamilyMembersListSheet(
                    ui = familyUi(
                        role = FamilyRole.Owner,
                        members = listOf(
                            member(
                                "管理员",
                                FamilyRole.Owner,
                                isSelf = true,
                                devices = listOf(
                                    device("owner-phone", "我的 Pixel", now, isCurrent = true),
                                    device("owner-tablet", "家庭平板", now - 90),
                                ),
                            ),
                            member(
                                "妈妈",
                                FamilyRole.Member,
                                devices = listOf(device("mom-phone", "Pixel 10", now - 86_400)),
                            ),
                            member("奶奶", FamilyRole.Member, devices = emptyList()),
                        ),
                        pendingRequests = listOf(
                            PendingMemberLoginRequest(
                                requestId = "pending-request-secret-id",
                                displayName = "爷爷",
                                deviceName = "新手机",
                                createdAtEpochSeconds = now - 180,
                                expiresAtEpochSeconds = now + 3_600,
                            ),
                        ),
                        pendingRenameRequests = listOf(
                            PendingMemberRenameRequest(
                                requestId = "00000000-0000-0000-0000-000000000010",
                                membershipId = "membership-妈妈",
                                currentDisplayName = "妈妈",
                                requestedDisplayName = "妈咪",
                                createdAtEpochSeconds = now - 60,
                                expiresAtEpochSeconds = now + 3_600,
                            ),
                        ),
                    ),
                    onRefreshMembers = {},
                    onEditMyDisplayName = {},
                    onAddMember = {},
                    onRenameMember = { _, _ -> },
                    onRenameDevice = { _, _ -> },
                    onRevokeDevice = { deviceId, _, _ -> revokedDevice = deviceId },
                    onReviewRename = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("设备登录申请（1）").assertIsDisplayed()
        compose.onNodeWithText("待处理改名（1）").assertIsDisplayed()
        compose.onNodeWithText("妈妈 → 妈咪").assertIsDisplayed()
        compose.onNodeWithTag("members_manage_menu").performClick()
        compose.onNodeWithText("添加成员").assertIsDisplayed()
        compose.onNodeWithText("添加成员").performClick()
        compose.onNodeWithText("爷爷").fetchSemanticsNode()
        compose.onNodeWithContentDescription("我的 Pixel，这台设备，刚刚").fetchSemanticsNode()
        compose.onNodeWithText("家庭平板").fetchSemanticsNode()
        compose.onNodeWithTag("members_roster_list")
            .performScrollToNode(hasText("Pixel 10"))
        compose.onNodeWithText("Pixel 10").assertIsDisplayed()
        compose.onAllNodesWithTag("device_overflow_menu").assertCountEquals(3)
        compose.onAllNodesWithTag("device_overflow_menu")[0].performClick()
        compose.onNodeWithText("撤销设备").performClick()
        compose.runOnIdle { assertThat(revokedDevice).isEqualTo("owner-phone") }
        // Public empty-state copy; scroll via semantics (no product-only LazyColumn testTag).
        compose.onNodeWithText("暂无设备").performScrollTo().assertIsDisplayed()
        for (technical in listOf("owner-phone", "mom-phone", "pending-request-secret-id", "token", "SPKI")) {
            compose.onAllNodesWithText(technical, substring = true).assertCountEquals(0)
        }
    }

    @Test
    fun ordinaryMemberSeesEveryNameButOnlyOwnDeviceDetails() {
        val now = System.currentTimeMillis() / 1_000L
        compose.setContent {
            LeziTheme {
                FamilyMembersListSheet(
                    ui = familyUi(
                        role = FamilyRole.Member,
                        membershipId = "member-self",
                        members = listOf(
                            member(
                                "管理员",
                                FamilyRole.Owner,
                                devices = listOf(device("owner-tablet", "管理员平板", now)),
                            ),
                            member(
                                "妈妈",
                                FamilyRole.Member,
                                isSelf = true,
                                membershipId = "member-self",
                                devices = listOf(
                                    device("self-phone", "我的手机", now, isCurrent = true),
                                    device("self-tablet", "我的平板", now - 3_600),
                                ),
                            ),
                            member(
                                "奶奶",
                                FamilyRole.Member,
                                devices = listOf(device("grandma-phone", "奶奶手机", now)),
                            ),
                        ),
                    ),
                    onRefreshMembers = {},
                    onEditMyDisplayName = {},
                    onRenameDevice = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        for (name in listOf("管理员 ★", "妈妈（我）", "奶奶", "我的手机", "我的平板")) {
            compose.onNodeWithText(name).fetchSemanticsNode()
        }
        compose.onAllNodesWithText("管理员平板").assertCountEquals(0)
        compose.onAllNodesWithText("奶奶手机").assertCountEquals(0)
        compose.onAllNodesWithText("设备登录申请", substring = true).assertCountEquals(0)
        compose.onAllNodesWithTag("member_overflow_menu").assertCountEquals(1)
        compose.onNodeWithTag("member_overflow_menu").performClick()
        compose.onNodeWithText("申请改称呼").assertIsDisplayed()
        // Self devices remain independent lazy rows with rename (not revoke) overflow.
        compose.onAllNodesWithTag("device_overflow_menu").assertCountEquals(2)
        compose.onAllNodesWithTag("device_overflow_menu")[0].performClick()
        compose.onNodeWithText("改设备称呼").assertIsDisplayed()
        compose.onAllNodesWithText("撤销设备").assertCountEquals(0)
    }

    @Test
    fun loadedEmptyProjectionHasARecoverableEmptyState() {
        compose.setContent {
            LeziTheme {
                FamilyMembersListSheet(
                    ui = familyUi(role = FamilyRole.Member, members = emptyList()),
                    onRefreshMembers = {},
                    onEditMyDisplayName = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("暂时没有可显示的家庭成员").assertIsDisplayed()
        compose.onNodeWithTag("members_manage_menu").performClick()
        compose.onNodeWithText("刷新").assertIsDisplayed()
    }

    @Test
    fun errorStateRemainsReadableAtTwoHundredPercentFontScale() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LeziTheme {
                    FamilyMembersListSheet(
                        ui = familyUi(
                            role = FamilyRole.Owner,
                            members = emptyList(),
                            error = "暂时无法读取成员与设备，请稍后重试",
                        ),
                        onRefreshMembers = {},
                        onEditMyDisplayName = {},
                        onDismiss = {},
                    )
                }
            }
        }

        compose.onNodeWithText("家庭成员与设备").assertIsDisplayed()
        compose.onNodeWithText("暂时无法读取成员与设备，请稍后重试").fetchSemanticsNode()
        compose.onNodeWithTag("members_manage_menu").performClick()
        compose.onNodeWithText("刷新").fetchSemanticsNode()
    }

    @Test
    fun remoteRevokeConfirmationDoesNotPromiseInstantOfflineWipe() {
        compose.setContent {
            LeziTheme {
                RevokeFamilyDeviceDialog(
                    deviceName = "奶奶手机",
                    isCurrent = false,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("撤销「奶奶手机」？").assertIsDisplayed()
        compose.onNodeWithText("设备离线时不会即时收到通知", substring = true).assertIsDisplayed()
        compose.onNodeWithText("确认撤销").assertIsDisplayed()
    }

    @Test
    fun currentDeviceLogoutExplainsServerFirstFullLocalClear() {
        compose.setContent {
            LeziTheme {
                LogoutCurrentDeviceDialog(onConfirm = {}, onDismiss = {})
            }
        }

        compose.onNodeWithText("退出这台设备？").assertIsDisplayed()
        compose.onNodeWithText("服务器确认退出后", substring = true).assertIsDisplayed()
        compose.onNodeWithText("待同步内容", substring = true).assertIsDisplayed()
    }

    /**
     * S3 (0.5.4) logout guard. 未运行，待真机 — authored 2026-09-13 with no
     * connected Android device in this environment; run under
     * connectedDebugAndroidTest on the next device window.
     */
    @Test
    fun logoutDialogWithPendingCountShowsDisclosureAndSyncFirstChance() {
        val originalTimeZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        var syncFirstClicked = 0
        try {
            compose.setContent {
                LeziTheme {
                    LogoutCurrentDeviceDialog(
                        onConfirm = {},
                        onDismiss = {},
                        pendingPublishCount = 34,
                        onSyncFirst = { syncFirstClicked += 1 },
                    )
                }
            }

            compose.onNodeWithText("还有 34 条未同步，退出后将永久丢弃").assertIsDisplayed()
            compose.onNodeWithText("先同步再检查").assertIsDisplayed()
            compose.onNodeWithText("仍然退出")
                .assertIsDisplayed()
                .assertIsEnabled()
            // The sync-first chance runs one round and the dialog stays open —
            // confirm, not block.
            compose.onNodeWithText("先同步再检查").performClick()
            compose.runOnIdle { assertThat(syncFirstClicked).isEqualTo(1) }
            compose.onNodeWithText("仍然退出").assertIsDisplayed().assertIsEnabled()
        } finally {
            java.util.TimeZone.setDefault(originalTimeZone)
        }
    }

    /** 未运行，待真机（2026-09-13 无连接设备）。 */
    @Test
    fun logoutDialogWithoutPendingCountKeepsPlainConfirmWithoutDisclosure() {
        compose.setContent {
            LeziTheme {
                LogoutCurrentDeviceDialog(
                    onConfirm = {},
                    onDismiss = {},
                    pendingPublishCount = 0,
                )
            }
        }

        compose.onNodeWithText("退出这台设备？").assertIsDisplayed()
        compose.onNodeWithText("退出这台设备").assertIsDisplayed()
        compose.onAllNodesWithText("仍然退出").assertCountEquals(0)
        compose.onAllNodesWithText("未同步，退出后将永久丢弃", substring = true).assertCountEquals(0)
    }

    /** 未运行，待真机（2026-09-13 无连接设备）。 */
    @Test
    fun deviceRemovalReceiptDialogNamesRemovalTimeAndClearedLossOnce() {
        val originalTimeZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        var consumed = 0
        try {
            compose.setContent {
                LeziTheme {
                    DeviceRemovedReceiptDialog(
                        receipt = DeviceRemovedCleanupReceipt(
                            removedAtEpochMillis = 1_789_273_800_000L, // 2026-09-13 04:30 UTC
                            clearedPendingCount = 12,
                        ),
                        onConfirm = { consumed += 1 },
                    )
                }
            }

            compose.onNodeWithText(
                "该设备于 2026-09-13 04:30 被家庭管理员移除，已清理 12 条未同步内容。",
            ).assertIsDisplayed()
            compose.onNodeWithText("我知道了").performClick()
            compose.runOnIdle { assertThat(consumed).isEqualTo(1) }
        } finally {
            java.util.TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun membershipExitExplainsHardDeleteAnonymizationAndServerFirstClear() {
        compose.setContent {
            LeziTheme {
                LeaveFamilyDialog(onConfirm = {}, onDismiss = {})
            }
        }

        compose.onNodeWithText("退出家庭？").assertIsDisplayed()
        compose.onNodeWithText("成员身份、全部设备和登录信息会被彻底删除", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("作者显示为“家人”", substring = true).assertIsDisplayed()
        compose.onNodeWithText("退出家庭").assertIsDisplayed()
    }

    @Test
    fun ownerMemberDeleteExplainsRemoteClearAndAnonymousHistory() {
        compose.setContent {
            LeziTheme {
                RemoveMemberConfirmDialog(
                    displayName = "爸爸",
                    removing = false,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("删除成员？").assertIsDisplayed()
        compose.onNodeWithText("彻底删除「爸爸」的成员身份和全部设备", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("作者显示为“家人”", substring = true).assertIsDisplayed()
    }

    @Test
    fun ownerFamilyDeleteRequiresExactNameAndRootWithReadableIrreversibleError() {
        val enteredName = mutableStateOf("")
        val rootPassword = mutableStateOf("")
        compose.setContent {
            LeziTheme {
                DeleteFamilyDialog(
                    stage = FamilyDialog.DeleteStage.Final,
                    expectedFamilyName = "乐乐一家",
                    familyNameInput = enteredName.value,
                    onFamilyNameInputChange = { enteredName.value = it },
                    rootPassword = rootPassword.value,
                    onRootPasswordChange = { rootPassword.value = it },
                    errorMessage = "根密码不正确，本机数据未清除",
                    deleting = false,
                    onContinue = {},
                    onConfirm = {},
                    onRefreshFamilyInfo = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("删除整个家庭").assertIsDisplayed()
        compose.onNodeWithText("且无法恢复", substring = true).assertIsDisplayed()
        compose.onNodeWithText("根密码不正确，本机数据未清除")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Assertive,
                ),
            )
        compose.onNodeWithText("永久删除家庭").assertIsNotEnabled()

        compose.onNodeWithText("输入家庭名确认").performTextInput("乐乐一家")
        compose.onNodeWithText("管理员根密码").performTextInput("root-password-secret")
        compose.onNodeWithText("永久删除家庭").assertIsEnabled()
    }

    @Test
    fun blankDeleteFamilyNameProvidesARealRefreshAction() {
        var refreshed = 0
        compose.setContent {
            LeziTheme {
                DeleteFamilyDialog(
                    stage = FamilyDialog.DeleteStage.Final,
                    expectedFamilyName = "",
                    familyNameInput = "",
                    onFamilyNameInputChange = {},
                    rootPassword = "",
                    onRootPasswordChange = {},
                    errorMessage = null,
                    deleting = false,
                    onContinue = {},
                    onConfirm = { throw AssertionError("blank name must not delete") },
                    onRefreshFamilyInfo = { refreshed += 1 },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("返回并刷新").performClick()

        compose.runOnIdle { assertThat(refreshed).isEqualTo(1) }
    }

    private fun familyUi(
        role: FamilyRole,
        membershipId: String = "owner-self",
        members: List<FamilyMember>,
        pendingRequests: List<PendingMemberLoginRequest> = emptyList(),
        pendingRenameRequests: List<PendingMemberRenameRequest> = emptyList(),
        error: String? = null,
    ) = MembersDevicesUi(
        identity = FamilyIdentityUi(
            enabled = true,
            role = role,
            membershipId = membershipId,
        ),
        members = members,
        membersLoaded = true,
        membersError = error,
        pendingMemberRequests = pendingRequests,
        pendingMemberRenameRequests = pendingRenameRequests,
    )

    private fun member(
        name: String,
        role: FamilyRole,
        isSelf: Boolean = false,
        membershipId: String = "membership-$name",
        devices: List<FamilyDevice>?,
    ) = FamilyMember(name, role, isSelf, membershipId, devices)

    private fun device(
        id: String,
        name: String,
        lastUsedAt: Long,
        isCurrent: Boolean = false,
    ) = FamilyDevice(id, name, lastUsedAt, isCurrent)
}
