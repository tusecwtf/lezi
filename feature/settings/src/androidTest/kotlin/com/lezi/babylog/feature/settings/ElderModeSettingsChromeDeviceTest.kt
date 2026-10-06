package com.lezi.babylog.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.foundation.layout.Arrangement
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziFilterChip
import com.lezi.babylog.designsystem.LeziSwitch
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalLayoutApi::class)
class ElderModeSettingsChromeDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderSettingsToggleAndLevelChipsStayReadableAcrossConfigs() {
        var config by mutableStateOf(SettingsConfigs.first())
        compose.setContent {
            val current = config
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(Modifier.requiredWidth(current.width)) {
                        Column {
                            SettingsMenuRow(
                                title = "长辈模式",
                                subtitle = "大字、无衬线、大按钮、强对比",
                                modifier = Modifier.testTag("elder_settings_row"),
                                trailing = {
                                    LeziSwitch(checked = true, onCheckedChange = {})
                                },
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                listOf("大", "特大", "超大").forEach { label ->
                                    LeziFilterChip(
                                        selected = label == "超大",
                                        onClick = {},
                                        label = label,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        SettingsConfigs.forEach { next ->
            compose.runOnIdle { config = next }
            compose.waitForIdle()
            compose.onNodeWithText("长辈模式").assertExists()
            compose.onNodeWithText("大").assertExists()
            compose.onNodeWithText("特大").assertExists()
            compose.onNodeWithText("超大").assertExists()
            if (next.physicalWidthPx <= 1080) {
                val node = compose.onNodeWithTag("elder_settings_row")
                val clipped = node.getBoundsInRoot()
                val unclipped = node.getUnclippedBoundsInRoot()
                assertEquals(
                    "${next.name} row width",
                    unclipped.width.value,
                    clipped.width.value,
                    0.5f,
                )
                assertEquals(
                    "${next.name} row height",
                    unclipped.height.value,
                    clipped.height.value,
                    0.5f,
                )
            }
            val big = compose.onNodeWithText("大").getBoundsInRoot()
            val huge = compose.onNodeWithText("特大").getBoundsInRoot()
            assertTrue(
                "${next.name} level chips overlap",
                big.right.value <= huge.left.value + 0.5f ||
                    big.bottom.value <= huge.top.value + 0.5f,
            )
        }
    }

    private companion object {
        val SettingsConfigs = LeziDeviceViewports.styledWidths()
    }
}
