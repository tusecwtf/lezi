package com.lezi.babylog.feature.family

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScanner
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScannerFailure
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScannerOutcome
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.feature.family.wizard.FamilyJoinRoleDialog
import com.lezi.babylog.feature.family.wizard.rememberFamilyMemberLoginQrScanAction
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.qr.MemberLoginQrScanOutcome
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemberLoginQrScanSurfaceDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun joinRoleScanButtonUsesTheProductionBindingForReadyAndLocalFailureCopy() {
        val scanner = EmittingScanner(MemberLoginQrScannerOutcome.Scanned(readyOutcome()))
        val ready = mutableListOf<MemberLoginQrPayload>()
        val messages = mutableListOf<String>()

        compose.setContent {
            LeziTheme {
                val launchScan = rememberFamilyMemberLoginQrScanAction(
                    onReady = ready::add,
                    onMessage = messages::add,
                    scannerFactory = { onOutcome -> scanner.bind(onOutcome) },
                )
                FamilyJoinRoleDialog(
                    busy = false,
                    onOwner = {},
                    onMember = {},
                    onScanMemberLoginQr = launchScan,
                    onBackToEndpoint = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("扫描成员登录二维码").performClick()
        compose.runOnIdle {
            assertThat(ready.single().grant).isEqualTo(GRANT)
            assertThat(messages).isEmpty()
            scanner.next = MemberLoginQrScannerOutcome.Failed(
                MemberLoginQrScannerFailure.NoCamera,
            )
        }
        compose.onNodeWithText("扫描成员登录二维码").performClick()
        compose.runOnIdle {
            assertThat(messages).containsExactly(
                "此设备没有可用相机，请使用家庭服务器地址手动申请加入",
            )
        }
    }

    private class EmittingScanner(
        var next: MemberLoginQrScannerOutcome,
    ) : MemberLoginQrScanner {
        private var onOutcome: ((MemberLoginQrScannerOutcome) -> Unit)? = null

        fun bind(callback: (MemberLoginQrScannerOutcome) -> Unit): MemberLoginQrScanner = apply {
            onOutcome = callback
        }

        override fun launch() {
            onOutcome?.invoke(next)
        }

        override fun dispose() = Unit
    }

    private companion object {
        const val GRANT = "grant-0000000000000000000000000000000000000"

        fun readyOutcome() = MemberLoginQrScanOutcome.Ready(
            MemberLoginQrPayload(
                endpoint = TrustedEndpointProfile.systemPki("https://nas.home"),
                grant = GRANT,
                familyName = "乐乐一家",
                memberDisplayName = "妈妈",
                expiresAtEpochSeconds = 1_753_419_000L,
            ),
        )
    }
}
