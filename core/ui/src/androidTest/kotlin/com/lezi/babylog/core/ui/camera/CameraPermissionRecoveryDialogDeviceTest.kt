package com.lezi.babylog.core.ui.camera

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CameraPermissionRecoveryDialogDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun permanentDenialOffersSettingsGalleryAndCancelWithoutInvokingRetry() {
        var retry = 0
        var settings = 0
        var gallery = 0
        var dismiss = 0
        compose.setContent {
            MaterialTheme {
                CameraPermissionRecoveryDialogContent(
                    recovery = CameraPermissionRecovery.OpenSettings,
                    enabled = true,
                    settingsError = false,
                    onRetry = { retry++ },
                    onSettings = { settings++ },
                    onChooseGallery = { gallery++ },
                    onDismiss = { dismiss++ },
                )
            }
        }
        compose.onNodeWithText("打开设置").assertIsDisplayed().performClick()
        compose.onNodeWithText("从相册选择").performClick()
        compose.onNodeWithText("暂不拍照").performClick()
        assertEquals(0, retry)
        assertEquals(1, settings)
        assertEquals(1, gallery)
        assertEquals(1, dismiss)
    }

    @Test
    fun retryAndSaveLockPreserveUserControl() {
        val enabled = mutableStateOf(false)
        var retry = 0
        compose.setContent {
            MaterialTheme {
                CameraPermissionRecoveryDialogContent(
                    recovery = CameraPermissionRecovery.RetryPermission,
                    enabled = enabled.value,
                    settingsError = false,
                    onRetry = { retry++ },
                    onSettings = { error("retryable denial must not open settings") },
                    onChooseGallery = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("再次申请").assertIsNotEnabled()
        compose.onNodeWithText("从相册选择").assertIsNotEnabled()
        compose.onNodeWithText("暂不拍照").assertIsNotEnabled()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithText("再次申请").performClick()
        assertEquals(1, retry)
    }
}
