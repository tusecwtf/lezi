package com.lezi.babylog.feature.family

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
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.feature.family.networksettings.FamilyNetworkSettingsScreen
import com.lezi.babylog.feature.family.networksettings.FamilyNetworkSettingsUi
import com.lezi.babylog.sync.session.FamilyRole
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeNetworkSettingsPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderNetworkSettingsFits720pTo4k() {
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_network_page"),
                    ) {
                        FamilyNetworkSettingsScreen(
                            ui = FamilyNetworkSettingsUi(
                                currentEndpoint = "https://192.168.77.4:8765",
                                role = FamilyRole.Owner,
                                availabilityStatus = "家庭服务器可用",
                                endpointDraft = "https://192.168.77.4:8765",
                            ),
                            onBack = {},
                            onEndpointDraftChange = {},
                            onProbeCandidate = {},
                            onTrustCandidate = {},
                            onRefreshAvailability = {},
                            onReconnectOwner = { _, _ -> },
                            onRequestReconnectMember = { _, _ -> },
                            onCheckReconnectMember = {},
                            onCancelReconnectMember = {},
                            onPrepareDisasterRecovery = {},
                            onStartDisasterRecovery = { _, _, _ -> },
                            onCommitDisasterRecovery = {},
                            onCancelDisasterRecovery = {},
                        )
                    }
                }
            }
        }

        LeziDeviceViewports.styled().forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val page = compose.onNodeWithTag("elder_network_page").getUnclippedBoundsInRoot()
            assertTrue("${next.name} network page collapsed: $page", page.height >= 48.dp)
            compose.onNodeWithText("家庭网络设置").assertExists()
            compose.onNodeWithText("当前连接").assertExists()
        }
    }
}
