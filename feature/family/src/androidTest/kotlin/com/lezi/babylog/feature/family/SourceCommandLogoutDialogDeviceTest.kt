package com.lezi.babylog.feature.family

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.feature.family.members.LogoutCurrentDeviceDialog
import com.lezi.babylog.feature.family.members.SourceCommandLogoutPreview
import com.lezi.babylog.sync.SourceCommandLogoutConsent
import com.lezi.babylog.sync.SourceCommandLogoutState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceCommandLogoutDialogDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unavailableSourceCheckKeepsCancelAvailableWithoutEnablingLogout() {
        val preview = mutableStateOf<SourceCommandLogoutPreview>(SourceCommandLogoutPreview.Checking)
        var dismissed = false
        compose.setContent {
            LeziTheme {
                LogoutCurrentDeviceDialog(
                    sourcePreview = preview.value,
                    onConfirm = { error("An unverified source check must not authorize logout") },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("正在核对…").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsEnabled()
        compose.runOnIdle { preview.value = SourceCommandLogoutPreview.Failed() }
        compose.onNodeWithText("无法核对来源操作", substring = true).assertIsDisplayed()
        compose.onNodeWithText("退出这台设备").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertThat(dismissed).isTrue() }
    }

    @Test
    fun unknownResultNamesExactOriginRequestsAndLocalOnlyAbandonmentAlongsideDirtyLoss() {
        var confirmed = false
        compose.setContent {
            LeziTheme {
                LogoutCurrentDeviceDialog(
                    sourcePreview = SourceCommandLogoutPreview.Ready(
                        consent(SourceCommandLogoutState.Unknown, "https://old-nas.example:8765"),
                    ),
                    pendingPublishCount = 3,
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("还有 3 条未同步，退出后将永久丢弃").assertIsDisplayed()
        compose.onNodeWithText("来源操作结果尚未确认", substring = true).assertIsDisplayed()
        compose.onNodeWithText("https://old-nas.example:8765", substring = true).assertIsDisplayed()
        compose.onNodeWithText("request-a", substring = true).assertIsDisplayed()
        compose.onNodeWithText("request-b", substring = true).assertIsDisplayed()
        compose.onNodeWithText("本机核实；不会撤销服务器上可能已经生效的操作", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("先同步再检查").assertIsEnabled()
        compose.onNodeWithText("放弃核实并退出").assertIsEnabled().performClick()
        compose.runOnIdle { assertThat(confirmed).isTrue() }
    }

    @Test
    fun confirmedCommandExplainsRefreshAndBusyPreventsRepeatedConsentOrCancellation() {
        val busy = mutableStateOf(false)
        compose.setContent {
            LeziTheme {
                LogoutCurrentDeviceDialog(
                    sourcePreview = SourceCommandLogoutPreview.Ready(
                        consent(SourceCommandLogoutState.ConfirmedRefreshRequired, "https://nas.example:8765"),
                    ),
                    busy = busy.value,
                    onConfirm = { busy.value = true },
                    onDismiss = { error("Pending logout cannot be cancelled") },
                )
            }
        }

        compose.onNodeWithText("来源操作已确认，仍需刷新本机", substring = true).assertIsDisplayed()
        compose.onNodeWithText("本机刷新；不会撤销服务器上已经生效的操作", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("放弃刷新并退出").performClick()
        compose.onNodeWithText("退出中…").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
    }

    @Test
    fun legacyRequestWithoutProvenOriginDoesNotBorrowTheCurrentServer() {
        compose.setContent {
            LeziTheme {
                LogoutCurrentDeviceDialog(
                    sourcePreview = SourceCommandLogoutPreview.Ready(
                        consent(SourceCommandLogoutState.Unknown, null),
                    ),
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("服务器：未知的历史服务器", substring = true).assertIsDisplayed()
        compose.onNodeWithText("请求（2）", substring = true).assertIsDisplayed()
        compose.onNodeWithText("放弃核实并退出").assertIsEnabled()
    }

    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    private fun consent(state: SourceCommandLogoutState, origin: String?) = SourceCommandLogoutConsent(
        requestIds = listOf("request-a", "request-b"),
        serverOrigin = origin,
        state = state,
        exactEvidence = Any(),
    )
}
