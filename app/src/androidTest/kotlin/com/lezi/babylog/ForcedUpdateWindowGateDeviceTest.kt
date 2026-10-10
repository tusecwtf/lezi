package com.lezi.babylog

import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.window.Dialog
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class)
class ForcedUpdateWindowGateDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun forcedUpdateRemovesExistingIndependentBusinessWindows() {
        val forced = mutableStateOf(false)
        compose.setContent {
            LeziTheme {
                RootBusinessWindowGate(blocked = forced.value, recovering = false, recovery = {}) {
                    ModalBottomSheet(onDismissRequest = {}) { Button(onClick = {}) { Text("保存记录") } }
                    Dialog(onDismissRequest = {}) { Button(onClick = {}) { Text("删除家庭") } }
                }
            }
        }
        compose.onNodeWithText("删除家庭").assertIsDisplayed()
        compose.runOnIdle { forced.value = true }
        compose.onNodeWithText("保存记录").assertDoesNotExist()
        compose.onNodeWithText("删除家庭").assertDoesNotExist()
    }

    @Test fun recoveryExceptionExposesOnlyRecoveryContent() {
        compose.setContent {
            LeziTheme {
                RootBusinessWindowGate(
                    blocked = true, recovering = true,
                    recovery = { Text("恢复家庭登录") },
                ) { Dialog(onDismissRequest = {}) { Text("保存记录") } }
            }
        }
        compose.onNodeWithText("恢复家庭登录").assertIsDisplayed()
        compose.onNodeWithText("保存记录").assertDoesNotExist()
    }
}
