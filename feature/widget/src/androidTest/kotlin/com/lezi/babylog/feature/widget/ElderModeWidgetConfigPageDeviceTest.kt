package com.lezi.babylog.feature.widget

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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeWidgetConfigPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderWidgetConfigFits720pTo4k() {
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_widget_config_page"),
                    ) {
                        WidgetConfigurationScreen(
                            state = WidgetConfigurationScreenState(
                                widgetId = 1,
                                babies = listOf(WidgetBabyOption(1L, "乐乐")),
                                selectedBabyId = 1L,
                                selectedTypes = DEFAULT_WIDGET_QUICK_TYPES,
                            ),
                            onStateChange = {},
                            onSave = {},
                        )
                    }
                }
            }
        }

        LeziDeviceViewports.styled().forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val page = compose.onNodeWithTag("elder_widget_config_page").getUnclippedBoundsInRoot()
            assertTrue("${next.name} widget config collapsed: $page", page.height >= 48.dp)
            compose.onNodeWithText("设置乐记小组件").assertExists()
            compose.onNodeWithText("保存小组件").performScrollTo().assertExists()
        }
    }
}
