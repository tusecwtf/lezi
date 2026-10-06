package com.lezi.babylog.feature.log.layout
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.core.model.DEFAULT_QUICK_RECORD_SLOTS
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

@RunWith(AndroidJUnit4::class)
class LayoutEditEnterFocusDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun enteringAndExitingLayoutEditFiveTimesDoesNotThrowOnFirstFrameFocus() {
        var inLayoutEdit by mutableStateOf(false)
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                AnimatedContent(
                    targetState = inLayoutEdit,
                    label = "layoutEditEnterFocus",
                ) { editing ->
                    if (editing) {
                        LayoutEditCanvas(
                            prefs = DeviceLayoutPrefs(
                                quickRecordSlots = DEFAULT_QUICK_RECORD_SLOTS,
                                hiddenItems = emptySet(),
                                itemOrderJson = "[]",
                                categoryOrderJson = "[]",
                            ),
                            customItems = emptyList(),
                            onIntent = {},
                            onDone = {},
                            onOpenCustomManage = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        repeat(5) {
            composeRule.runOnIdle { inLayoutEdit = true }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("layout_edit_done").assertIsDisplayed()
            composeRule.runOnIdle { inLayoutEdit = false }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("layout_edit_done").assertDoesNotExist()
        }
    }
}
