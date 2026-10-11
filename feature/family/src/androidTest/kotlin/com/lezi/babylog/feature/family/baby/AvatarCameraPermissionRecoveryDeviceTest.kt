package com.lezi.babylog.feature.family.baby

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.ui.CameraCaptureLauncher
import com.lezi.babylog.core.ui.CameraCaptureOutcome
import com.lezi.babylog.core.ui.camera.CameraPermissionRecovery
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Avatar shells retain their fields while the common launcher owns permission recovery. */
@RunWith(AndroidJUnit4::class)
class AvatarCameraPermissionRecoveryDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun createDenialShowsRecoveryAndCancelKeepsTheDraftWithoutAutomaticCapture() {
        val camera = DeniedCameraLauncher()
        compose.setContent {
            LeziTheme {
                BabyCreateDialog(
                    busy = false,
                    onDismiss = {},
                    onCreate = { _, _, _, _, _, _, _ -> },
                    cameraCaptureLauncherFactory = { _, onOutcome ->
                        camera.rememberLauncher(onOutcome)
                    },
                )
            }
        }
        assertDenialKeepsFieldsAndNeedsAnotherCameraTap(camera)
    }

    @Test
    fun editDenialShowsRecoveryAndCancelKeepsTheDraftWithoutAutomaticCapture() {
        val camera = DeniedCameraLauncher()
        val host = BabyEditDraftHost()
        compose.setContent {
            LeziTheme {
                BabyEditDialog(
                    baby = BabyFixture,
                    canEditProfile = true,
                    canEditAvatar = true,
                    isCurrent = true,
                    canMoveEarlier = false,
                    canMoveLater = false,
                    onDismiss = {},
                    onSaveProfile = { _, _, _, _, _, _, _ -> },
                    onLocalTheme = { _, done -> done(null) },
                    onMoveLocal = { _, done -> done(null) },
                    onSetCurrent = {},
                    cameraCaptureLauncherFactory = { _, onOutcome ->
                        camera.rememberLauncher(onOutcome)
                    },
                    draftHost = host,
                )
            }
        }
        assertDenialKeepsFieldsAndNeedsAnotherCameraTap(camera)
    }

    private fun assertDenialKeepsFieldsAndNeedsAnotherCameraTap(camera: DeniedCameraLauncher) {
        field(NicknameLabel).performScrollTo().performTextReplacement(DraftNickname)
        field(WeightLabel).performScrollTo().performTextReplacement(DraftWeight)
        compose.onNodeWithText("拍照").performScrollTo().performClick()
        compose.runOnIdle { camera.deny() }
        compose.onNodeWithText("拍照需要相机权限").assertIsDisplayed()
        compose.onNodeWithText("打开设置").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, camera.launches) }

        compose.onNodeWithText("暂不拍照").performClick()
        compose.onNodeWithText("拍照需要相机权限").assertDoesNotExist()
        field(NicknameLabel).performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(DraftNickname)),
        )
        field(WeightLabel).performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(DraftWeight)),
        )
        compose.runOnIdle { assertEquals(1, camera.launches) }
        compose.onNodeWithText("拍照").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, camera.launches) }
    }

    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction())

    /** Denial never fabricates a captured Uri or an owned source. */
    private class DeniedCameraLauncher {
        var launches = 0
            private set
        private var recovery by mutableStateOf<CameraPermissionRecovery?>(null)
        private lateinit var onOutcome: (CameraCaptureOutcome) -> Unit
        private val launcher = object : CameraCaptureLauncher {
            override val permissionRecovery: CameraPermissionRecovery? get() = recovery

            override fun launch() {
                recovery = null
                launches += 1
            }

            override fun dismissPermissionRecovery() {
                recovery = null
            }

            override fun dispose() {
                recovery = null
            }
        }

        @Composable
        fun rememberLauncher(outcome: (CameraCaptureOutcome) -> Unit): CameraCaptureLauncher {
            onOutcome = outcome
            return launcher
        }

        fun deny() {
            recovery = CameraPermissionRecovery.OpenSettings
            onOutcome(CameraCaptureOutcome.PermissionDenied)
        }
    }

    private companion object {
        const val NicknameLabel = "昵称（不可重复）"
        const val WeightLabel = "出生体重（kg，可选）"
        const val DraftNickname = "晨晨"
        const val DraftWeight = " 3.25 "
        val BabyFixture = Baby(
            id = 9,
            familyId = 1,
            nickname = "乐乐",
            birthdayEpochDay = 20_000,
            themeColorArgb = 0xff6688aa.toInt(),
            clientUuid = "avatar-permission-9",
            updatedAt = 1,
        )
    }
}
