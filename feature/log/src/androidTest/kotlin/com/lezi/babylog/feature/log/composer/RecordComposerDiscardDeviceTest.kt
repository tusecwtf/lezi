package com.lezi.babylog.feature.log.composer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.core.model.RecordType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

@RunWith(AndroidJUnit4::class)
class RecordComposerDiscardDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun promptSurvivesRestorationAndContinueKeepsTheDraftSurface() {
        val restoration = StateRestorationTester(compose)
        var continued = false
        restoration.setContent {
            MaterialTheme {
                var prompt by rememberRecordComposerDiscardPrompt("request-7")
                Text("未保存备注仍在")
                Button(onClick = { prompt = true }) { Text("请求退出") }
                if (prompt) {
                    RecordComposerDiscardDialog(
                        busy = false,
                        onContinueEditing = {
                            continued = true
                            prompt = false
                        },
                        onDiscard = {},
                    )
                }
            }
        }

        compose.onNodeWithText("请求退出").performClick()
        compose.onNodeWithText("放弃未保存的更改？").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("放弃未保存的更改？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()

        assertTrue(continued)
        compose.onNodeWithText("未保存备注仍在").assertIsDisplayed()
        compose.onNodeWithText("放弃未保存的更改？").assertDoesNotExist()
    }

    @Test
    fun discardIsExplicitAndBusyStateCannotCloseTheDraft() {
        var discarded = false
        var continued = false
        var busy by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                RecordComposerDiscardDialog(
                    busy = busy,
                    onContinueEditing = { continued = true },
                    onDiscard = { discarded = true },
                )
            }
        }

        compose.onNodeWithText("继续编辑").assertIsNotEnabled()
        compose.onNodeWithText("放弃").assertIsNotEnabled()
        assertFalse(continued)
        assertFalse(discarded)

        compose.runOnIdle { busy = false }
        compose.onNodeWithText("放弃").assertIsEnabled().performClick()
        assertTrue(discarded)
    }

    @Test
    fun continueEditingKeepsTheCurrentFieldTextAndFocus() {
        val focusRequester = FocusRequester()
        var body by mutableStateOf("焦点中的草稿")
        var prompt by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                TextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier
                        .testTag("dirty_draft_field")
                        .focusRequester(focusRequester),
                )
                if (prompt) {
                    RecordComposerDiscardDialog(
                        busy = false,
                        onContinueEditing = { prompt = false },
                        onDiscard = {},
                    )
                }
            }
        }

        compose.runOnIdle { focusRequester.requestFocus() }
        compose.onNodeWithTag("dirty_draft_field").assertIsFocused()
        compose.runOnIdle { prompt = true }
        compose.onNodeWithText("继续编辑").performClick()

        compose.onNodeWithTag("dirty_draft_field")
            .assertTextContains("焦点中的草稿")
            .assertIsFocused()
    }

    @Test
    fun headerCloseAndFooterCancelReportTheirExactSharedGateSources() {
        val source = AtomicReference<ComposerDismissSource?>()
        setSheet { source.set(it) }

        compose.onNodeWithText("关闭").performClick()
        assertEquals(ComposerDismissSource.HeaderClose, source.get())

        source.set(null)
        compose.onNodeWithText("取消").performClick()
        assertEquals(ComposerDismissSource.FooterCancel, source.get())
    }

    @Test
    fun savingDisablesTimeNoteRecentNoteAndWakeControls() {
        compose.setContent {
            MaterialTheme {
                QuickRecordSheet(
                    draft = QuickRecordDraft.create(
                        type = RecordType.SLEEP,
                        timestamp = 1_000L,
                        recentNotes = listOf("昨日状态稳定"),
                    ),
                    interactionKey = "busy-fields",
                    amountStepMl = 5,
                    timeStepMin = 1,
                    saving = true,
                    deleting = false,
                    saveError = null,
                    canStartNursingTimer = false,
                    onDraftChange = {},
                    onDismiss = {},
                    onDelete = null,
                    onConfirm = {},
                    onStartNursingTimer = {},
                    onImportPhotos = {},
                    onRemovePhoto = {},
                )
            }
        }

        val renderedTime = Instant.ofEpochMilli(1_000L)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
        compose.onNodeWithContentDescription("睡下，$renderedTime，选择时间")
            .assertIsNotEnabled()
        compose.onNodeWithTag(RECORD_COMPOSER_NOTE_FIELD_TAG).assertIsNotEnabled()
        compose.onNodeWithText("昨日状态稳定").assertIsNotEnabled()
        compose.onNode(isToggleable()).assertIsNotEnabled()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun discardPromptContinueKeepsTheModalSheetVisible() {
        var prompt by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                val sheetState = rememberModalBottomSheetState(
                    skipPartiallyExpanded = RECORD_COMPOSER_SKIP_PARTIALLY_EXPANDED,
                )
                RecordComposerModalSheet(
                    sheetState = sheetState,
                    systemBackEnabled = true,
                    modalOverlayVisible = prompt,
                    onSystemBack = { prompt = !prompt },
                    onDismissRequest = { prompt = true },
                    overlay = {
                        if (prompt) {
                            RecordComposerDiscardDialog(
                                busy = false,
                                onContinueEditing = { prompt = false },
                                onDiscard = {},
                            )
                        }
                    },
                ) {
                    Text("Modal 中的未保存草稿")
                    Button(onClick = { prompt = true }) {
                        Text("请求退出")
                    }
                }
            }
        }

        compose.onNodeWithText("Modal 中的未保存草稿").assertIsDisplayed()
        compose.onNodeWithText("请求退出").performClick()
        compose.onNodeWithText("放弃未保存的更改？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()

        compose.onNodeWithText("Modal 中的未保存草稿").assertIsDisplayed()
        compose.onNodeWithText("放弃未保存的更改？").assertDoesNotExist()
    }

    private fun setSheet(onDismiss: (ComposerDismissSource) -> Unit) {
        compose.setContent {
            MaterialTheme {
                QuickRecordSheet(
                    draft = QuickRecordDraft.create(RecordType.DIARY, 1_000L),
                    interactionKey = "discard-device",
                    amountStepMl = 5,
                    timeStepMin = 1,
                    saving = false,
                    deleting = false,
                    saveError = null,
                    canStartNursingTimer = false,
                    onDraftChange = {},
                    onDismiss = onDismiss,
                    onDelete = null,
                    onConfirm = {},
                    onStartNursingTimer = {},
                    onImportPhotos = {},
                    onRemovePhoto = {},
                )
            }
        }
    }
}
