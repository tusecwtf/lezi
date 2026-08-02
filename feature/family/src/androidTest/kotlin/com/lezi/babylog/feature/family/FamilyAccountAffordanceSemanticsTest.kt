package com.lezi.babylog.feature.family

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.feature.family.overview.FamilySharingContent
import com.lezi.babylog.feature.family.overview.FamilySyncStatusEntry
import com.lezi.babylog.feature.family.overview.FamilyMemberRosterEntry
import com.lezi.babylog.feature.family.overview.AccountBottomActions
import com.lezi.babylog.feature.family.overview.AccountOverviewUi
import com.lezi.babylog.feature.family.overview.AccountPageContent
import com.lezi.babylog.feature.family.members.MembersDevicesUi
import com.lezi.babylog.feature.family.FamilyIdentityUi
import com.lezi.babylog.feature.family.components.familyPrimarySurface
import com.lezi.babylog.feature.family.wizard.FamilyJoinRoleDialog
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.session.FamilyRole
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FamilyAccountAffordanceSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun offlineSyncEntryNamesStateWithoutRestoringASettingsAction() {
        compose.setContent {
            LeziTheme {
                    FamilySyncStatusEntry(
                        statusLabel = OFFLINE_RESULT,
                        isError = true,
                        modifier = Modifier.testTag(SYNC_ENTRY),
                )
            }
        }

        val entry = compose.onNodeWithTag(SYNC_ENTRY)
            .assertTextContains(OFFLINE_RESULT)
        val semantics = entry.fetchSemanticsNode().config
        assertThat(semantics.contains(SemanticsActions.OnClick)).isFalse()
    }

    @Test
    fun accountPageOrdersFamilyBeforeBabiesAndKeepsAccountActionsAtTheBottom() {
        val baby = Baby(
            id = 1,
            familyId = 1,
            nickname = "年年",
            birthdayEpochDay = 20_000,
            themeColorArgb = 0xff6688aa.toInt(),
            clientUuid = "baby-1",
            updatedAt = 1,
        )
        compose.setContent {
            LeziTheme {
                AccountPageContent(
                    overview = AccountOverviewUi(
                        hydrated = true,
                        identity = FamilyIdentityUi(
                            displayName = "管理员",
                            enabled = true,
                            role = FamilyRole.Owner,
                            familyName = "乐乐一家",
                        ),
                        status = SyncStatus.Idle,
                        current = baby,
                        babies = listOf(baby),
                    ),
                    members = MembersDevicesUi(
                        members = listOf(
                            FamilyMember("管理员", FamilyRole.Owner, true, "membership-owner"),
                        ),
                        membersLoaded = true,
                    ),
                    primary = familyPrimarySurface(true, FamilyRole.Owner, true),
                    endpointConfigured = true,
                )
            }
        }

        val familyTop = compose.onNodeWithText("乐乐一家").fetchSemanticsNode().boundsInRoot.top
        val babyTop = compose.onNodeWithText("宝宝档案").fetchSemanticsNode().boundsInRoot.top
        val actionsTop = compose.onNodeWithText("家庭网络设置").fetchSemanticsNode().boundsInRoot.top
        assertThat(familyTop).isLessThan(babyTop)
        assertThat(babyTop).isLessThan(actionsTop)
    }

    @Test
    fun accountPageDoesNotExposeFalseColdStartActionsBeforeHydration() {
        compose.setContent {
            LeziTheme {
                AccountPageContent(
                    overview = AccountOverviewUi(),
                    members = MembersDevicesUi(),
                    primary = familyPrimarySurface(false, FamilyRole.None, false),
                    endpointConfigured = false,
                )
            }
        }

        compose.onNodeWithText("正在读取账户…").assertIsDisplayed()
        compose.onAllNodesWithText("连接家庭服务器").assertCountEquals(0)
        compose.onAllNodesWithText("当前宝宝").assertCountEquals(0)
        compose.onAllNodesWithText("—").assertCountEquals(0)
    }

    @Test
    fun memberLoginQrScanLivesInsideJoinRoleChoiceInsteadOfTheAccountOverview() {
        val showJoinRole = mutableStateOf(false)
        var scans = 0
        compose.setContent {
            LeziTheme {
                if (showJoinRole.value) {
                    FamilyJoinRoleDialog(
                        busy = false,
                        onOwner = {},
                        onMember = {},
                        onScanMemberLoginQr = { scans += 1 },
                        onBackToEndpoint = {},
                        onDismiss = {},
                    )
                } else {
                    AccountPageContent(
                        overview = AccountOverviewUi(hydrated = true),
                        members = MembersDevicesUi(),
                        primary = familyPrimarySurface(false, FamilyRole.None, false),
                        endpointConfigured = false,
                    )
                }
            }
        }
        compose.onNodeWithText("连接家庭服务器").assertIsDisplayed()
        compose.onAllNodesWithText("家庭").assertCountEquals(1)
        compose.onAllNodesWithText("扫描成员登录二维码").assertCountEquals(0)

        compose.runOnIdle { showJoinRole.value = true }
        compose.onNodeWithText("扫描成员登录二维码").assertIsDisplayed().performClick()
        compose.runOnIdle { assertThat(scans).isEqualTo(1) }
    }

    @Test
    fun pendingMemberRequestHasOneReadableResultAndARecoverableEntry() {
        compose.setContent {
            LeziTheme {
                AccountPageContent(
                    overview = AccountOverviewUi(
                        hydrated = true,
                        status = SyncStatus.Idle,
                        pendingMemberLogin = PendingMemberLogin(
                            requestId = "request-1",
                            displayName = "爸爸",
                            deviceName = "Pixel 9",
                            expiresAtEpochSeconds = 2_000,
                        ),
                    ),
                    members = MembersDevicesUi(),
                    primary = familyPrimarySurface(false, FamilyRole.None, true),
                    endpointConfigured = true,
                )
            }
        }

        compose.onNodeWithText("等待管理员确认").assertIsDisplayed()
        compose.onNodeWithText("查看加入申请").assertIsDisplayed()
        compose.onAllNodesWithText("家人记录已对齐").assertCountEquals(0)
    }

    @Test
    fun memberAccountKeepsRequiredBottomActionsWithoutOwnerDeletion() {
        var logoutCalls = 0
        var leaveCalls = 0
        compose.setContent {
            LeziTheme {
                AccountPageContent(
                    overview = AccountOverviewUi(
                        hydrated = true,
                        identity = FamilyIdentityUi(
                            displayName = "爸爸",
                            enabled = true,
                            role = FamilyRole.Member,
                            familyName = "乐乐一家",
                        ),
                        status = SyncStatus.Idle,
                    ),
                    members = MembersDevicesUi(
                        members = listOf(
                            FamilyMember("爸爸", FamilyRole.Member, true, "membership-member"),
                        ),
                        membersLoaded = true,
                    ),
                    primary = familyPrimarySurface(true, FamilyRole.Member, true),
                    endpointConfigured = true,
                    bottomActions = AccountBottomActions(
                        logoutCurrentDevice = { logoutCalls += 1 },
                        leaveFamily = { leaveCalls += 1 },
                    ),
                )
            }
        }

        compose.onNodeWithText("家庭网络设置").assertExists()
        compose.onNodeWithText("退出这台设备").assertIsDisplayed().performClick()
        compose.onNodeWithText("退出家庭").assertIsDisplayed().performClick()
        compose.onAllNodesWithText("删除家庭").assertCountEquals(0)
        compose.runOnIdle {
            assertThat(logoutCalls).isEqualTo(1)
            assertThat(leaveCalls).isEqualTo(1)
        }
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
                    overview = AccountOverviewUi(
                        identity = FamilyIdentityUi(
                            displayName = "管理员",
                            enabled = true,
                            role = FamilyRole.Owner,
                            familyName = "乐乐一家",
                        ),
                        hydrated = true,
                        status = SyncStatus.Idle,
                    ),
                    members = MembersDevicesUi(
                        identity = FamilyIdentityUi(
                            displayName = "管理员",
                            enabled = true,
                            role = FamilyRole.Owner,
                            familyName = "乐乐一家",
                        ),
                        members = listOf(
                            FamilyMember("管理员", FamilyRole.Owner, true, "membership-owner"),
                        ),
                        membersLoaded = true,
                    ),
                    primary = familyPrimarySurface(true, FamilyRole.Owner, true),
                    endpointConfigured = true,
                    onOpenMembers = {},
                    onConnectFamily = {},
                )
            }
        }

        for (copy in listOf(
            "乐乐一家",
            "管理员 ★",
            "尚无成功同步",
        )) {
            compose.onNodeWithText(copy).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("家庭成员与设备：1 位家人").assertIsDisplayed()
        compose.onAllNodesWithText("退出家庭").assertCountEquals(0)
        for (extra in listOf("改名", "改称呼", "邀请家人", "点同步状态")) {
            compose.onAllNodesWithText(extra, substring = true).assertCountEquals(0)
        }
    }

    @Test
    fun familyOverviewDoesNotDuplicateAccountBottomActions() {
        compose.setContent {
            LeziTheme {
                FamilySharingContent(
                    overview = AccountOverviewUi(
                        identity = FamilyIdentityUi(
                            displayName = "爸爸",
                            enabled = true,
                            role = FamilyRole.Member,
                            familyName = "乐乐一家",
                        ),
                        hydrated = true,
                        status = SyncStatus.Idle,
                    ),
                    members = MembersDevicesUi(
                        identity = FamilyIdentityUi(
                            displayName = "爸爸",
                            enabled = true,
                            role = FamilyRole.Member,
                            familyName = "乐乐一家",
                        ),
                        members = listOf(
                            FamilyMember("爸爸", FamilyRole.Member, true, "membership-member"),
                        ),
                        membersLoaded = true,
                    ),
                    primary = familyPrimarySurface(true, FamilyRole.Member, true),
                    endpointConfigured = true,
                    onOpenMembers = {},
                    onConnectFamily = {},
                )
            }
        }

        compose.onAllNodesWithText("退出这台设备").assertCountEquals(0)
        compose.onAllNodesWithText("退出家庭").assertCountEquals(0)
        compose.onAllNodesWithText("删除家庭").assertCountEquals(0)
    }

    private companion object {
        const val SYNC_ENTRY = "family_sync_entry"
        const val OFFLINE_RESULT = "未连接家庭服务器，请连接家里 Wi-Fi 后同步"
    }
}
