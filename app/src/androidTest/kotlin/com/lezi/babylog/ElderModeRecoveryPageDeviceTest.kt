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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.core.common.LocalDataUpgradeBlockReason
import com.lezi.babylog.core.common.LocalDataUpgradeState
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeRecoveryPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderRecoveryPageFits720pTo4k() {
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
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
                                reason = LocalDataUpgradeBlockReason.InconsistentData,
                                detail = "checksum",
                            ),
                            onRetry = {},
                            onShareDiagnostics = {},
                            onClearApplicationData = {},
                        )
                    }
                }
            }
        }

        LeziDeviceViewports.styled().forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val page = compose.onNodeWithTag("elder_recovery_page").getUnclippedBoundsInRoot()
            assertTrue("${next.name} recovery collapsed: $page", page.height >= 48.dp)
            compose.onNodeWithText("本地数据状态不一致").assertExists()
            compose.onNodeWithText("重试安全检查").assertExists()
        }
    }
}
