package com.lezi.babylog.feature.timer

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompletionSheetSavingDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savingCannotDragSheetHiddenAndFailureKeepsDraftVisible() {
        var saving by mutableStateOf(true)
        var dismissals = 0
        var backDispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            LeziTheme {
                TimerCompletionModalSheet(saving = saving, onDismiss = { dismissals++ }) {
                    backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                    Box(Modifier.fillMaxWidth().height(300.dp).testTag("completion")) {
                        Text(if (saving) "保存中的草稿" else "保存失败，草稿仍在")
                    }
                }
            }
        }
        compose.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        compose.onNodeWithText("保存中的草稿").assertIsDisplayed()
        compose.onNodeWithTag("completion").performTouchInput { swipeDown() }
        compose.onNodeWithText("保存中的草稿").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, dismissals); saving = false }
        compose.onNodeWithText("保存失败，草稿仍在").assertIsDisplayed()
    }
}
