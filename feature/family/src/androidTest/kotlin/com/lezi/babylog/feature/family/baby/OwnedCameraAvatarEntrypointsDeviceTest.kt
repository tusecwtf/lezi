package com.lezi.babylog.feature.family.baby

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.ui.CameraCaptureLauncher
import com.lezi.babylog.core.ui.CameraCaptureOutcome
import com.lezi.babylog.core.ui.OwnedCameraCapture
import com.lezi.babylog.designsystem.LeziTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies typed launcher outcomes in both avatar shells and completed-state restoration wiring. */
@RunWith(AndroidJUnit4::class)
class OwnedCameraAvatarEntrypointsDeviceTest {
    @get:Rule
    val compose = createComposeRule()
    private val testFiles = mutableListOf<File>()

    @After
    fun removeExternalPickerFixtures() {
        testFiles.forEach(File::delete)
    }

    @Test
    fun createAvatarCameraEntryReleasesCancelAndCroppedCaptureAcrossRestoration() {
        val restoration = StateRestorationTester(compose)
        val camera = FakeCameraCapture(createTestAvatarUri("create"))
        restoration.setContent {
            LeziTheme {
                BabyCreateDialog(
                    busy = false,
                    onDismiss = {},
                    onCreate = { _, _, _, _, _, _, _ -> },
                    cameraCaptureLauncherFactory = { key, outcome ->
                        camera.rememberLauncher(key, outcome)
                    },
                )
            }
        }

        compose.onNodeWithText("拍照").assertIsDisplayed().performClick()
        assertEquals(1, camera.launchCount)
        compose.runOnIdle { camera.emitCancelled() }
        assertEquals(1, camera.releaseCount)

        compose.onNodeWithText("拍照").performClick()
        compose.runOnIdle { camera.emitCaptured() }
        confirmCropAndAwaitRelease(camera, expectedReleaseCount = 2)

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("拍照").assertIsDisplayed()
    }

    @Test
    fun editAvatarCameraEntryReleasesCancelAndCroppedCaptureAcrossRestoration() {
        val restoration = StateRestorationTester(compose)
        val camera = FakeCameraCapture(createTestAvatarUri("edit"))
        restoration.setContent {
            LeziTheme {
                BabyEditDialog(
                    baby = BABY,
                    canEditProfile = true,
                    canEditAvatar = true,
                    isCurrent = true,
                    canMoveEarlier = false,
                    canMoveLater = false,
                    onDismiss = {},
                    onSaveProfile = { _, _, _, _, _, _, done -> done() },
                    onLocalTheme = { _, done -> done(null) },
                    onMoveLocal = { _, done -> done(null) },
                    onSetCurrent = {},
                    cameraCaptureLauncherFactory = { key, outcome ->
                        camera.rememberLauncher(key, outcome)
                    },
                )
            }
        }

        compose.onNodeWithText("拍照").assertIsDisplayed().performClick()
        assertEquals(1, camera.launchCount)
        compose.runOnIdle { camera.emitCancelled() }
        assertEquals(1, camera.releaseCount)

        compose.onNodeWithText("拍照").performClick()
        compose.runOnIdle { camera.emitCaptured() }
        confirmCropAndAwaitRelease(camera, expectedReleaseCount = 2)

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("拍照").assertIsDisplayed()
    }

    private fun confirmCropAndAwaitRelease(
        camera: FakeCameraCapture,
        expectedReleaseCount: Int,
    ) {
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                compose.onNodeWithText("使用此头像").assertIsEnabled()
            }.isSuccess
        }
        compose.onNodeWithText("使用此头像").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            camera.releaseCount == expectedReleaseCount
        }
        assertEquals(expectedReleaseCount, camera.releaseCount)
    }

    private fun createTestAvatarUri(name: String): Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "owned-camera-avatar-$name.jpg")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(80, 130, 190))
        }
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
        }
        bitmap.recycle()
        testFiles += file
        return Uri.fromFile(file)
    }

    private class FakeCameraCapture(
        private val captureUri: Uri,
    ) {
        var launchCount: Int = 0
            private set
        var releaseCount: Int = 0
            private set
        private lateinit var onOutcome: (CameraCaptureOutcome) -> Unit
        private var pending: FakeOwnedCapture? = null

        private val launcher = object : CameraCaptureLauncher {
            override fun launch() {
                pending?.release()
                launchCount += 1
                pending = FakeOwnedCapture(
                    token = "fake-avatar-$launchCount",
                    uri = captureUri,
                    onReleased = { releaseCount += 1 },
                )
            }

            override fun dispose() {
                pending?.release()
                pending = null
            }
        }

        @Composable
        fun rememberLauncher(
            ownershipKey: Any?,
            outcome: (CameraCaptureOutcome) -> Unit,
        ): CameraCaptureLauncher {
            onOutcome = outcome
            return launcher
        }

        fun emitCancelled() {
            pending?.release()
            pending = null
            onOutcome(CameraCaptureOutcome.Cancelled)
        }

        fun emitCaptured() {
            val capture = requireNotNull(pending)
            pending = null
            onOutcome(CameraCaptureOutcome.Captured(capture))
        }
    }

    private class FakeOwnedCapture(
        override val token: String,
        override val uri: Uri,
        private val onReleased: () -> Unit,
    ) : OwnedCameraCapture {
        private var released = false

        override fun release() {
            if (released) return
            released = true
            onReleased()
        }
    }

    private companion object {
        val BABY = Baby(
            id = 9,
            familyId = 1,
            nickname = "乐乐",
            birthdayEpochDay = 20_000,
            themeColorArgb = 0xff6688aa.toInt(),
            clientUuid = "baby-camera-9",
            updatedAt = 1,
        )
    }
}
