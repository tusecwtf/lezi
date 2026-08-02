package com.lezi.babylog.feature.family

import com.lezi.babylog.feature.family.wizard.MemberLoginQrConfirmDialog

import com.lezi.babylog.feature.family.wizard.MemberApprovalWaitingDialog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.feature.family.members.FamilyMembersListSheet
import com.lezi.babylog.feature.family.members.PendingMemberDecisionDialog
import com.lezi.babylog.feature.family.members.MemberLoginQrCodeDialog
import com.lezi.babylog.feature.family.members.MembersDevicesUi
import com.lezi.babylog.feature.family.FamilyIdentityUi
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.backend.PendingMemberLoginRequest
import com.lezi.babylog.sync.qr.MemberLoginQrCode
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import org.junit.Rule
import org.junit.Test

class MemberApprovalWaitingDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun waitingDialogExposesOnlyExplicitCheckCancelAndOfflineActions() {
        var checks = 0
        var cancels = 0
        var offline = 0
        compose.setContent {
            LeziTheme {
                MemberApprovalWaitingDialog(
                    request = PendingMemberLogin(
                        requestId = "request-1",
                        displayName = "奶奶",
                        deviceName = "Pixel 10",
                        expiresAtEpochSeconds = 1,
                    ),
                    checking = false,
                    onCheck = { checks += 1 },
                    onCancel = { cancels += 1 },
                    onKeepOffline = { offline += 1 },
                )
            }
        }

        compose.onNodeWithText("等待管理员确认").assertIsDisplayed()
        compose.onNodeWithText("已申请：奶奶").assertIsDisplayed()
        compose.onNodeWithText("设备：Pixel 10").assertIsDisplayed()
        compose.onNodeWithText("检查结果").performClick()
        compose.onNodeWithText("取消申请").performClick()
        compose.onNodeWithText("暂不连接，保持离线").performClick()

        compose.runOnIdle {
            assertThat(checks).isEqualTo(1)
            assertThat(cancels).isEqualTo(1)
            assertThat(offline).isEqualTo(1)
        }
    }

    @Test
    fun ownerPendingDeviceRowOpensAnExplicitDecision() {
        var reviewed: PendingMemberLoginRequest? = null
        val request = PendingMemberLoginRequest(
            requestId = "request-2",
            displayName = "外婆",
            deviceName = "外婆的平板",
            createdAtEpochSeconds = 1,
            expiresAtEpochSeconds = 2,
        )
        compose.setContent {
            LeziTheme {
                FamilyMembersListSheet(
                    ui = MembersDevicesUi(
                        identity = FamilyIdentityUi(
                            enabled = true,
                            role = FamilyRole.Owner,
                        ),
                        membersLoaded = true,
                        pendingMemberRequests = listOf(request),
                    ),
                    onRefreshMembers = {},
                    onEditMyDisplayName = {},
                    onReviewPending = { reviewed = it },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("待确认设备（1）").assertIsDisplayed()
        compose.onNodeWithText("外婆").assertIsDisplayed()
        compose.onNodeWithText("外婆的平板", substring = true).assertIsDisplayed()
        compose.onNodeWithText("处理申请").performClick()

        compose.runOnIdle {
            assertThat(reviewed).isEqualTo(request)
        }
    }

    @Test
    fun ownerDecisionOffersNewExistingAndRejectButNeverBindsToOwner() {
        val request = PendingMemberLoginRequest(
            requestId = "request-3",
            displayName = "外婆",
            deviceName = "外婆的平板",
            createdAtEpochSeconds = 1,
            expiresAtEpochSeconds = 2,
        )
        val bound = mutableListOf<String>()
        var approvedNew = 0
        var rejected = 0
        compose.setContent {
            LeziTheme {
                PendingMemberDecisionDialog(
                    request = request,
                    members = listOf(
                        FamilyMember("管理员", FamilyRole.Owner, true, "membership-owner"),
                        FamilyMember("妈妈", FamilyRole.Member, false, "membership-mom"),
                    ),
                    busy = false,
                    onBindExisting = { bound += it },
                    onApproveNew = { approvedNew += 1 },
                    onReject = { rejected += 1 },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("确认这台设备").assertIsDisplayed()
        compose.onNodeWithText("对方声明称呼：外婆").assertIsDisplayed()
        compose.onNodeWithText("设备：外婆的平板").assertIsDisplayed()
        compose.onNodeWithText("绑定到现有「管理员」").assertDoesNotExist()
        compose.onNodeWithText("绑定到现有「妈妈」").performClick()
        compose.onNodeWithText("用此称呼添加新成员").performClick()
        compose.onNodeWithText("拒绝").performClick()

        compose.runOnIdle {
            assertThat(bound).containsExactly("membership-mom")
            assertThat(approvedNew).isEqualTo(1)
            assertThat(rejected).isEqualTo(1)
        }
    }

    @Test
    fun duplicateDeclaredNameRequiresExplicitExistingMemberBinding() {
        compose.setContent {
            LeziTheme {
                PendingMemberDecisionDialog(
                    request = PendingMemberLoginRequest(
                        requestId = "request-4",
                        displayName = "妈妈",
                        deviceName = "新手机",
                        createdAtEpochSeconds = 1,
                        expiresAtEpochSeconds = 2,
                    ),
                    members = listOf(
                        FamilyMember("妈妈", FamilyRole.Member, false, "membership-mom"),
                    ),
                    busy = false,
                    onBindExisting = {},
                    onApproveNew = {},
                    onReject = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("绑定到现有「妈妈」").assertIsDisplayed()
        compose.onNodeWithText("用此称呼添加新成员").assertIsNotEnabled()
        compose.onNodeWithText("该家庭称呼已存在，请绑定到现有成员").assertIsDisplayed()
    }

    @Test
    fun ownerCanGenerateLoginQrOnlyForOrdinaryMemberAndQrIsTalkBackReadable() {
        var target: String? = null
        val payload = MemberLoginQrPayload(
            endpoint = TrustedEndpointProfile.systemPki("https://family.example.com"),
            grant = "grant-0000000000000000000000000000000000000",
            familyName = "乐乐一家",
            memberDisplayName = "妈妈",
            expiresAtEpochSeconds = 1_753_419_000,
        )
        val showQr = mutableStateOf(false)
        compose.setContent {
            LeziTheme {
                if (showQr.value) {
                    MemberLoginQrCodeDialog(
                        code = MemberLoginQrCode(
                            payload = payload,
                            landingUrl = "http://family.example.com:8767/join",
                        ),
                        onDismiss = {},
                    )
                } else {
                    FamilyMembersListSheet(
                        ui = MembersDevicesUi(
                            identity = FamilyIdentityUi(
                                enabled = true,
                                role = FamilyRole.Owner,
                            ),
                            membersLoaded = true,
                            members = listOf(
                                FamilyMember("管理员", FamilyRole.Owner, true, "membership-owner"),
                                FamilyMember("妈妈", FamilyRole.Member, false, "membership-mom"),
                            ),
                        ),
                        onRefreshMembers = {},
                        onEditMyDisplayName = {},
                        onCreateMemberLoginQr = {
                            target = it
                            showQr.value = true
                        },
                        onDismiss = {},
                    )
                }
            }
        }

        compose.onNodeWithText("为这个成员生成登录二维码").performClick()
        compose.runOnIdle { assertThat(target).isEqualTo("membership-mom") }

        compose.onNodeWithText("成员登录二维码").assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "成员登录二维码，已授权妈妈在十分钟内登录；未安装乐记可用系统相机下载",
        ).assertIsDisplayed()
        compose.onNodeWithText("安装后请用乐记重新扫描", substring = true).assertIsDisplayed()
        compose.onNodeWithText(payload.grant).assertDoesNotExist()
    }

    @Test
    fun scannedMemberQrConfirmsTargetTrustEditableDeviceAndManualFallback() {
        var deviceName: String? = null
        var manualFallback = 0
        val payload = MemberLoginQrPayload(
            endpoint = TrustedEndpointProfile.tofuSpki(
                "https://family.example.com:9443",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            ),
            grant = "grant-0000000000000000000000000000000000000",
            familyName = "乐乐一家",
            memberDisplayName = "妈妈",
            expiresAtEpochSeconds = 1_753_419_000,
        )
        compose.setContent {
            LeziTheme {
                MemberLoginQrConfirmDialog(
                    payload = payload,
                    deviceName = "Pixel 10",
                    onDeviceNameChange = { deviceName = it },
                    feedback = "这个二维码已失效，请让管理员重新生成",
                    submitting = false,
                    onLogin = { deviceName = "submitted" },
                    onManualJoin = { manualFallback += 1 },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("登录家庭").assertIsDisplayed()
        compose.onNodeWithText("已由家庭管理员授权：妈妈").assertIsDisplayed()
        compose.onNodeWithText("乐乐一家").assertIsDisplayed()
        compose.onNodeWithText("将信任管理员提供的家庭服务器配置。").assertIsDisplayed()
        compose.onNodeWithText("Pixel 10").performTextReplacement("妈妈的平板")
        compose.onNodeWithText("改用加入家庭").performClick()
        compose.runOnIdle {
            assertThat(deviceName).isEqualTo("妈妈的平板")
            assertThat(manualFallback).isEqualTo(1)
        }
    }

    @Test
    fun failedMemberQrVerificationOffersRetryAndManualJoinWithoutLogin() {
        var retried = 0
        var manualFallback = 0
        val payload = MemberLoginQrPayload(
            endpoint = TrustedEndpointProfile.systemPki("https://family.example.com"),
            grant = "grant-0000000000000000000000000000000000000",
            familyName = "乐乐一家",
            memberDisplayName = "妈妈",
            expiresAtEpochSeconds = 1_753_419_000,
        )
        compose.setContent {
            LeziTheme {
                MemberLoginQrConfirmDialog(
                    payload = payload,
                    deviceName = "Pixel 10",
                    onDeviceNameChange = {},
                    feedback = "暂时无法确认二维码中的家庭服务器",
                    submitting = false,
                    verificationRetryRequired = true,
                    onLogin = { throw AssertionError("unverified QR must not log in") },
                    onRetryVerification = { retried += 1 },
                    onManualJoin = { manualFallback += 1 },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("重新确认").performClick()
        compose.onNodeWithText("改用加入家庭").performClick()

        compose.runOnIdle {
            assertThat(retried).isEqualTo(1)
            assertThat(manualFallback).isEqualTo(1)
        }
    }
}
