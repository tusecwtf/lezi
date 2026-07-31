package com.lezi.babylog.feature.family

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.FamilyRole
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FamilyAccountAffordanceSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun offlineSyncEntryNamesStateActionAndUsesFortyEightDpTarget() {
        var openNetworkCount = 0
        compose.setContent {
            LeziTheme {
                FamilySyncStatusEntry(
                    statusLabel = OFFLINE_RESULT,
                    isError = true,
                    onOpenNetwork = { openNetworkCount += 1 },
                    modifier = Modifier.testTag(SYNC_ENTRY),
                )
            }
        }

        val entry = compose.onNodeWithTag(SYNC_ENTRY)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assertTextContains(OFFLINE_RESULT)
        val semantics = entry.fetchSemanticsNode().config
        assertThat(semantics[SemanticsProperties.Role]).isEqualTo(Role.Button)
        assertThat(semantics[SemanticsProperties.ContentDescription])
            .containsExactly("同步状态：$OFFLINE_RESULT")
        assertThat(semantics[SemanticsActions.OnClick].label).isEqualTo("打开网络设置")

        entry.performClick()
        compose.runOnIdle { assertThat(openNetworkCount).isEqualTo(1) }
    }

    @Test
    fun focusTraversalMovesFromMemberRosterToSyncStatus() {
        compose.setContent {
            LeziTheme {
                Column {
                    FamilyMemberRosterEntry(
                        label = "3 位家人",
                        onOpenMembers = {},
                        modifier = Modifier.testTag(MEMBER_ENTRY),
                    )
                    FamilySyncStatusEntry(
                        statusLabel = "等待连接家庭网络",
                        isError = false,
                        onOpenNetwork = {},
                        modifier = Modifier.testTag(SYNC_ENTRY),
                    )
                }
            }
        }

        val member = compose.onNodeWithTag(MEMBER_ENTRY).fetchSemanticsNode().config
        val sync = compose.onNodeWithTag(SYNC_ENTRY).fetchSemanticsNode().config
        assertThat(member[SemanticsProperties.TraversalIndex]).isLessThan(
            sync[SemanticsProperties.TraversalIndex],
        )
    }

    @Test
    fun ownerPendingBadgeAnnouncesTheExactRequestCount() {
        var opens = 0
        compose.setContent {
            LeziTheme {
                FamilyMemberRosterEntry(
                    label = "3 位家人",
                    pendingCount = 2,
                    onOpenMembers = { opens += 1 },
                )
            }
        }

        compose.onNodeWithContentDescription(
            "家庭成员与设备：3 位家人，2 个待确认设备",
        ).assertIsDisplayed().performClick()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.runOnIdle { assertThat(opens).isEqualTo(1) }
    }

    @Test
    fun joinedAccountOverviewContainsOnlyIdentityResultAndMembersEntry() {
        compose.setContent {
            LeziTheme {
                FamilySharingContent(
                    ui = FamilyUi(
                        displayName = "管理员",
                        enabled = true,
                        role = FamilyRole.Owner,
                        familyName = "乐乐一家",
                        status = SyncStatus.Idle,
                        members = listOf(
                            FamilyMember("管理员", FamilyRole.Owner, true, "membership-owner"),
                        ),
                        membersLoaded = true,
                    ),
                    primary = familyPrimarySurface(true, FamilyRole.Owner, true),
                    networkConfigured = true,
                    onOpenMembers = {},
                    onConnectFamily = {},
                    onScanMemberLoginQr = {},
                )
            }
        }

        for (copy in listOf("乐乐一家", "管理员 ★", "家人记录已对齐", "退出这台设备", "删除家庭")) {
            compose.onNodeWithText(copy).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("家庭成员与设备：1 位家人").assertIsDisplayed()
        compose.onAllNodesWithText("退出家庭").assertCountEquals(0)
        for (extra in listOf("改名", "改称呼", "网络设置", "邀请家人", "点同步状态")) {
            compose.onAllNodesWithText(extra, substring = true).assertCountEquals(0)
        }
    }

    @Test
    fun memberSeesDistinctDeviceLogoutAndMembershipDeleteWhileOwnerDoesNotSeeLeaveFamily() {
        compose.setContent {
            LeziTheme {
                FamilySharingContent(
                    ui = FamilyUi(
                        displayName = "爸爸",
                        enabled = true,
                        role = FamilyRole.Member,
                        familyName = "乐乐一家",
                        status = SyncStatus.Idle,
                        members = listOf(
                            FamilyMember("爸爸", FamilyRole.Member, true, "membership-member"),
                        ),
                        membersLoaded = true,
                    ),
                    primary = familyPrimarySurface(true, FamilyRole.Member, true),
                    networkConfigured = true,
                    onOpenMembers = {},
                    onConnectFamily = {},
                    onScanMemberLoginQr = {},
                )
            }
        }

        compose.onNodeWithText("退出这台设备").assertIsDisplayed()
        compose.onNodeWithText("退出家庭").assertIsDisplayed()
        compose.onAllNodesWithText("删除家庭").assertCountEquals(0)
    }

    private companion object {
        const val MEMBER_ENTRY = "family_member_entry"
        const val SYNC_ENTRY = "family_sync_entry"
        const val OFFLINE_RESULT = "未连接家庭网络，请连接家里 Wi-Fi 后同步"
    }
}
