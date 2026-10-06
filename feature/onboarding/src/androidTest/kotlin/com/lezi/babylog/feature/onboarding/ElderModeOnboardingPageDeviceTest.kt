package com.lezi.babylog.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.feature.onboarding.steps.OnboardingChooseFamilyStep
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeOnboardingPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderChooseFamilyStepFits720pTo4k() {
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_onboarding_page"),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(LeziSpacing.Page)
                                .testTag(UiTags.ONBOARDING),
                            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                        ) {
                            Text("欢迎使用乐记", style = LeziThemeExt.typography.Title)
                            OnboardingChooseFamilyStep(
                                verifiedEndpoint = null,
                                pendingMemberLogin = null,
                                onConnectOrResume = {},
                                onScanMemberLogin = {},
                                onForgetEndpoint = {},
                                onOfflineMode = {},
                            )
                        }
                    }
                }
            }
        }

        LeziDeviceViewports.styled().forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val page = compose.onNodeWithTag("elder_onboarding_page").getUnclippedBoundsInRoot()
            val content = compose.onNodeWithTag(UiTags.ONBOARDING).getUnclippedBoundsInRoot()
            assertTrue(
                "${next.name} onboarding overflow: $content vs $page",
                content.left.value >= page.left.value - 0.5f &&
                    content.top.value >= page.top.value - 0.5f &&
                    content.right.value <= page.right.value + 0.5f,
            )
            assertTrue("${next.name} onboarding collapsed: $content", content.height >= 48.dp)
            compose.onNodeWithText("欢迎使用乐记").assertExists()
            compose.onNodeWithText("连接家庭服务器").performScrollTo().assertExists()
            compose.onNodeWithText("离线模式").performScrollTo().assertExists()
        }
    }
}
