package com.lezi.babylog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.lezi.babylog.core.common.LocalDataUpgradeBlockReason
import com.lezi.babylog.core.common.LocalDataUpgradeState
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeRecoveryPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderCredentialRecoveryActionsAreScrollReachableAndCancellableAcrossViewports() {
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        var clearCount = 0
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_recovery_page"),
                    ) {
                        LocalDataUpgradeScreen(
                            state = LocalDataUpgradeState.Blocked(
                                reason = LocalDataUpgradeBlockReason.CredentialsUnavailable,
                                detail = "java.io.IOException: disk full at /data/user/0/com.lezi.babylog",
                            ),
                            onRetry = {},
                            onShareDiagnostics = {},
                            onClearApplicationData = { clearCount += 1 },
                        )
                    }
                }
            }
        }

        LeziDeviceViewports.styled().forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()

            // The long device-switch body must scroll every action into a
            // clickable position, from 720p elder-l1 up to 4K elder-l3.
            compose.onNodeWithTag("local_data_recovery_clear")
                .performScrollTo()
                .performClick()
            compose.onNodeWithTag("local_data_recovery_clear_continue").performClick()
            compose.onNodeWithTag("local_data_recovery_clear_cancel").performClick()

            // Cancelling either confirmation layer must never trigger the clear.
            assertWithMessage(next.name).that(clearCount).isEqualTo(0)
        }

        // The hostile verification detail never appears on any styled surface.
        compose.onNodeWithText("disk full", substring = true).assertDoesNotExist()
        assertWithMessage("clear must stay unreachable without double confirmation")
            .that(clearCount).isEqualTo(0)
    }
}
