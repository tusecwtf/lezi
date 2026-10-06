package com.lezi.babylog.feature.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScanner
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScannerFailure
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScannerOutcome
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.feature.onboarding.qr.rememberOnboardingMemberLoginQrScanAction
import com.lezi.babylog.feature.onboarding.steps.OnboardingChooseFamilyStep
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.qr.MemberLoginQrScanOutcome
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemberLoginQrScanSurfaceDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun chooseFamilyScanButtonUsesTheProductionBindingForReadyAndLocalFailureCopy() {
        val scanner = EmittingScanner(MemberLoginQrScannerOutcome.Scanned(readyOutcome()))
        val ready = mutableListOf<MemberLoginQrPayload>()
        val messages = mutableListOf<String>()

        compose.setContent {
            LeziTheme {
                val launchScan = rememberOnboardingMemberLoginQrScanAction(
                    onReady = { ready.add(it) },
                    onMessage = { messages.add(it) },
                    scannerFactory = { onOutcome ->
                        scanner.bind(onOutcome)
                    },
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    OnboardingChooseFamilyStep(
                        verifiedEndpoint = null,
                        pendingMemberLogin = null,
                        onConnectOrResume = {},
                        onScanMemberLogin = launchScan,
                        onForgetEndpoint = {},
                        onOfflineMode = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("onboarding_scan_member_login")
            .assertHasClickAction()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) { ready.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(GRANT, ready.single().grant)
            assertTrue(messages.isEmpty())
            scanner.next = MemberLoginQrScannerOutcome.Failed(
                MemberLoginQrScannerFailure.NoCamera,
            )
        }
        compose.onNodeWithTag("onboarding_scan_member_login").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { messages.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(
                listOf("这台设备没有可用相机，也可以输入家庭服务器地址继续"),
                messages,
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
