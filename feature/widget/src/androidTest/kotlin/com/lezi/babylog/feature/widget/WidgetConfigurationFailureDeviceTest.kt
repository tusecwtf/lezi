package com.lezi.babylog.feature.widget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WidgetConfigurationFailureDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadFailureOffersRetryAndThenShowsChoices() {
        var ui by mutableStateOf(WidgetConfigurationUi(error = "加载失败，请重试"))
        compose.setContent {
            LeziTheme {
                WidgetConfigurationContent(
                    ui = ui,
                    onStateChange = {},
                    onSave = {},
                    onRetry = {
                        ui = WidgetConfigurationUi(selection = WidgetConfigurationScreenState(
                            widgetId = 7,
                            babies = listOf(WidgetBabyOption(42, "年年")),
                            selectedBabyId = 42,
                            selectedTypes = DEFAULT_WIDGET_QUICK_TYPES,
                        ))
                    },
                )
            }
        }
        compose.onNodeWithText("加载失败，请重试").assertExists()
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithText("年年").assertExists()
        compose.onNodeWithText("保存小组件").performScrollTo().assertExists()
    }

    @Test fun failedSaveKeepsSelectionAndBusySaveCannotBeRepeated() {
        var saving by mutableStateOf(false)
        var attempts = 0
        val selection = WidgetConfigurationScreenState(
            widgetId = 7,
            babies = listOf(WidgetBabyOption(42, "年年")),
            selectedBabyId = 42,
            selectedTypes = DEFAULT_WIDGET_QUICK_TYPES,
        )
        compose.setContent {
            LeziTheme {
                WidgetConfigurationScreen(
                    state = selection,
                    saving = saving,
                    saveError = if (saving) null else "保存失败，请重试",
                    onStateChange = {},
                    onSave = { attempts++; saving = true },
                )
            }
        }
        compose.onNodeWithText("保存失败，请重试").performScrollTo().assertExists()
        compose.onNodeWithText("保存小组件").performScrollTo().performClick()
        compose.onNodeWithText("保存中…").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, attempts); saving = false }
        compose.onNodeWithText("保存小组件").performClick()
        compose.runOnIdle { assertEquals(2, attempts) }
    }
}
