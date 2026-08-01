package com.lezi.babylog.feature.family

import com.lezi.babylog.feature.family.wizard.OwnerTakeoverConfirmationDialog

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class OwnerTakeoverConfirmationDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun dangerousCopyRequiresExplicitConfirmationAndCancelDoesNotTakeOver() {
        var confirms = 0
        var cancels = 0
        compose.setContent {
            MaterialTheme {
                OwnerTakeoverConfirmationDialog(
                    submitting = false,
                    onConfirm = { confirms += 1 },
                    onDismiss = { cancels += 1 },
                )
            }
        }

        compose.onNodeWithText("接管管理员身份？").assertIsDisplayed()
        compose.onNodeWithText(
            "所有旧管理员设备都会退出家庭；普通成员不会退出。只有确定旧设备已丢失时才使用。",
        ).assertIsDisplayed()

        compose.onNodeWithText("取消").performClick()
        assertThat(cancels).isEqualTo(1)
        assertThat(confirms).isEqualTo(0)

        compose.onNodeWithText("确认接管").performClick()
        assertThat(confirms).isEqualTo(1)
    }
}
