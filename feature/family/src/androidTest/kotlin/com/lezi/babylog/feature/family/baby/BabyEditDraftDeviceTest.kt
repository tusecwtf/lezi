package com.lezi.babylog.feature.family.baby

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.ui.CameraCaptureLauncher
import com.lezi.babylog.core.ui.OwnedCameraCapture
import com.lezi.babylog.designsystem.LeziBabyTheme
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

/** Controlled composition rebuilding keeps the same in-memory owner; it is not process recovery. */
class BabyEditDraftDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unchangedCancelClosesImmediately() {
        var closes = 0
        compose.setContent { Editor(onClose = { closes += 1 }) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
        compose.onNodeWithText("放弃未保存的档案修改？").assertDoesNotExist()
    }

    @Test
    fun dirtyCancelKeepsDraftUntilDiscardIsConfirmed() {
        var closes = 0
        compose.setContent { Editor(onClose = { closes += 1 }) }
        nickname("晨晨")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("晨晨").assertExists()
        compose.runOnIdle { assertEquals(0, closes) }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("放弃档案修改").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun dirtyBackAndOutsideTapUseTheSameDiscardGate() {
        var closes = 0
        compose.setContent { Editor(onClose = { closes += 1 }) }
        nickname("晨晨")
        compose.onNodeWithText("头像").performScrollTo().performClick()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("放弃未保存的档案修改？").assertExists()
        compose.onNodeWithText("继续编辑").performClick()
        tapOutsideDialog()
        compose.onNodeWithText("放弃未保存的档案修改？").assertExists()
        compose.runOnIdle { assertEquals(0, closes) }
    }

    @Test
    fun localPreferencesSaveImmediatelyAndDoNotMakeTheProfileDirty() {
        var themes = 0
        var moves = 0
        var closes = 0
        compose.setContent {
            Editor(
                onClose = { closes += 1 },
                onTheme = { themes += 1 },
                onMove = { moves += 1 },
            )
        }
        compose.onNode(SemanticsMatcher("second local theme") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label ==
                "本机主题色：${LeziBabyTheme.Labels[1]}"
        }).performScrollTo().performClick()
        compose.onNodeWithText("后移").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertEquals(1, themes)
            assertEquals(1, moves)
            assertEquals(1, closes)
        }
        compose.onNodeWithText("放弃未保存的档案修改？").assertDoesNotExist()
    }

    @Test
    fun rebuildingDuringSaveRetainsLockFailureDraftAndRoutesSuccessToCurrentEditor() {
        val host = BabyEditDraftHost()
        val restoration = StateRestorationTester(compose)
        var complete: ((String?) -> Unit)? = null
        var success = 0
        var closes = 0
        var visible by mutableStateOf(true)
        restoration.setContent {
            if (visible) {
                Editor(
                    host = host,
                    onClose = { closes += 1; visible = false },
                    onSaved = { success += 1; visible = false },
                    onSave = { complete = it },
                )
            }
        }
        nickname("晨晨")
        compose.onNodeWithText("保存档案").performClick()
        compose.onNodeWithText("取消").assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("取消").assertIsNotEnabled()
        compose.onNodeWithText("晨晨").assertExists()
        compose.runOnIdle { requireNotNull(complete)("保存失败，请重试") }
        compose.onNodeWithText("保存失败，请重试").assertExists()
        compose.onNodeWithText("晨晨").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("保存档案").performClick()
        val savedDraft = host.open(BabyFixture)
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { requireNotNull(complete)(null) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, success)
            assertEquals(0, closes)
            val reopened = host.open(BabyFixture)
            assertNotSame(savedDraft, reopened)
            assertFalse(reopened.dirty)
        }
    }

    @Test
    fun rebuildingRetainsCroppedBytesAndOwnedCaptureWithoutBundleSerialization() {
        val host = BabyEditDraftHost()
        val draft = host.open(BabyFixture)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val jpeg = byteArrayOf(1, 2, 3)
        val capture = FakeCapture()
        draft.nickname = "晨晨"
        draft.croppedAvatar = CroppedAvatar(bitmap, jpeg)
        draft.replaceOwnedCapture(capture)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Editor(host = host) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertSame(draft, host.open(BabyFixture))
            assertSame(bitmap, draft.croppedAvatar?.bitmap)
            assertArrayEquals(jpeg, draft.croppedAvatar?.jpegBytes)
            assertEquals(0, capture.releases)
        }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("放弃档案修改").performClick()
        compose.runOnIdle { assertEquals(1, capture.releases) }
    }

    @Test
    fun permissionLossKeepsUnsavedProfileAndStillRequiresDiscardConfirmation() {
        var canEdit by mutableStateOf(true)
        var closes = 0
        compose.setContent { Editor(canEdit = canEdit, onClose = { closes += 1 }) }
        nickname("晨晨")
        compose.runOnIdle { canEdit = false }
        compose.onNodeWithText(
            "档案编辑权限已变化，未保存的修改仍保留。恢复权限后可继续保存，或放弃档案修改。",
        ).assertExists()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { canEdit = true }
        compose.onNodeWithText("晨晨").assertExists()
        compose.runOnIdle { assertEquals(0, closes) }
    }

    private fun nickname(value: String) {
        compose.onNode(hasText("昵称（不可重复）") and hasSetTextAction())
            .performScrollTo().performTextReplacement(value)
    }

    private fun tapOutsideDialog() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val height = instrumentation.targetContext.resources.displayMetrics.heightPixels
        val down = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1f, height / 2f, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Editor(
        host: BabyEditDraftHost? = null,
        canEdit: Boolean = true,
        onClose: () -> Unit = {},
        onSaved: () -> Unit = {},
        onSave: ((String?) -> Unit) -> Unit = { it(null) },
        onTheme: () -> Unit = {},
        onMove: () -> Unit = {},
    ) {
        LeziTheme {
            val draftOwner = host ?: androidx.lifecycle.viewmodel.compose.viewModel<BabyEditDraftHost> {
                BabyEditDraftHost()
            }
            BabyEditDialog(
                baby = BabyFixture,
                canEditProfile = canEdit,
                canEditAvatar = true,
                isCurrent = true,
                canMoveEarlier = true,
                canMoveLater = true,
                onDismiss = onClose,
                onSaved = onSaved,
                onSaveProfile = { _, _, _, _, _, _, done -> onSave(done) },
                onLocalTheme = { _, done -> onTheme(); done(null) },
                onMoveLocal = { _, done -> onMove(); done(null) },
                onSetCurrent = {},
                cameraCaptureLauncherFactory = { _, _ -> NoCamera },
                draftHost = draftOwner,
            )
        }
    }

    private class FakeCapture : OwnedCameraCapture {
        override val token = "fake-owned-profile-capture"
        override val uri = Uri.parse("content://test-owned-camera/capture.jpg")
        var releases = 0
        override fun release() { releases += 1 }
    }

    private companion object {
        val NoCamera = object : CameraCaptureLauncher {
            override fun launch() = Unit
            override fun dispose() = Unit
        }
        val BabyFixture = Baby(
            id = 9,
            familyId = 1,
            nickname = "乐乐",
            birthdayEpochDay = 20_000,
            birthWeightGrams = 3_200,
            themeColorArgb = 0xff6688aa.toInt(),
            clientUuid = "baby-draft-device-9",
            updatedAt = 1,
        )
    }
}
