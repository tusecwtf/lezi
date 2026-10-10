package com.lezi.babylog.feature.log.layout

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.lezi.babylog.domain.CustomRecordItem
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.core.ui.CustomItemSaveCommand
import com.lezi.babylog.core.ui.CustomItemSaveDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LayoutCustomSaveDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val retainedScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun closeRetainedScope() = retainedScope.cancel()

    @Test fun retainedSaveSurvivesRestorationAndReboundCallbackCannotClearTheNextDraft() {
        val owner = CustomItemSaveCommand()
        val finish = CompletableDeferred<String?>()
        val restoration = StateRestorationTester(compose)
        val existing = CustomRecordItem(7, "项目C", 0, 0)
        var writes = 0
        var oldCallback: ((String?) -> Unit)? = null
        restoration.setContent {
            val command by owner.state.collectAsState()
            LeziTheme {
                LayoutCustomManageDialog(
                    items = listOf(existing), onDismiss = {},
                    onAdd = { name, icon, done ->
                        oldCallback = done
                        retainedScope.launch {
                            owner.save(CustomItemSaveDraft(null, name, icon)) {
                                writes++
                                finish.await()
                            }
                        }
                    },
                    onUpdate = { _, _ -> error("Editing must stay locked during the add") },
                    onDelete = { _, _ -> error("Deleting must stay locked during the add") },
                    saveCommand = command, onConsumeSaveResult = owner::consume,
                )
            }
        }
        compose.onNodeWithTag("layout_custom_name").performTextReplacement("草稿A")
        compose.onNodeWithTag("layout_custom_save").performScrollTo().performClick()
        compose.onNodeWithTag("layout_custom_save").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("layout_custom_name").assertIsNotEnabled()
        compose.onNodeWithContentDescription("编辑项目C").assertIsNotEnabled()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("layout_custom_name").assert(hasText("草稿A")).assertIsNotEnabled()
        compose.onNodeWithTag("layout_custom_save").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, writes)
            assertEquals(CustomItemSaveDraft(null, "草稿A", 0), owner.state.value.draft)
            finish.complete(null)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("layout_custom_name").assert(hasText(""))
        // Capture a callback from this restored, still-live composition. With a
        // retained saveCommand, production ignores callbacks before checking draft
        // identity. This proves that retained-command mode cannot let an old callback
        // mutate B/C; it does not exercise the separate fallback identity guard.
        compose.onNodeWithTag("layout_custom_name").performTextReplacement("同一页面的下一次A")
        compose.onNodeWithTag("layout_custom_save").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("layout_custom_name").assert(hasText(""))
        compose.onNodeWithTag("layout_custom_name").performTextReplacement("草稿B")
        compose.runOnIdle { checkNotNull(oldCallback).invoke(null) }
        compose.onNodeWithTag("layout_custom_name").assert(hasText("草稿B"))
        compose.onNodeWithContentDescription("编辑项目C").performScrollTo().performClick()
        compose.onNodeWithTag("layout_custom_name").performTextReplacement("编辑C的新意图")
        compose.runOnIdle { checkNotNull(oldCallback).invoke("旧A的错误") }
        compose.onNodeWithTag("layout_custom_name").assert(hasText("编辑C的新意图"))
        compose.onNodeWithText("旧A的错误").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, writes) }
    }

    @Test fun retainedDeletedTargetFailurePreservesEditIntentAfterRestoration() {
        val owner = CustomItemSaveCommand()
        val finish = CompletableDeferred<String?>()
        val restoration = StateRestorationTester(compose)
        var items by mutableStateOf(listOf(CustomRecordItem(7, "项目C", 0, 0)))
        var adds = 0
        var updates = 0
        restoration.setContent {
            val command by owner.state.collectAsState()
            LeziTheme {
                LayoutCustomManageDialog(
                    items = items, onDismiss = {},
                    onAdd = { _, _, _ -> adds++ },
                    onUpdate = { item, _ ->
                        retainedScope.launch {
                            owner.save(CustomItemSaveDraft(item.id, item.name, item.iconSlot)) {
                                updates++
                                finish.await()
                            }
                        }
                    },
                    onDelete = { _, _ -> },
                    saveCommand = command, onConsumeSaveResult = owner::consume,
                )
            }
        }
        compose.onNodeWithContentDescription("编辑项目C").performClick()
        compose.onNodeWithTag("layout_custom_name").performTextReplacement("尚未保存的C")
        compose.onNodeWithTag("layout_custom_save").performScrollTo().performClick()
        compose.runOnIdle { items = emptyList() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("layout_custom_name").assert(hasText("尚未保存的C")).assertIsNotEnabled()
        compose.runOnIdle { finish.complete("自定义项目已删除，请重新打开") }
        compose.waitForIdle()
        compose.onNodeWithText("自定义项目已删除，请重新打开").assertExists()
        compose.onNodeWithTag("layout_custom_name").assert(hasText("尚未保存的C"))
        compose.onNodeWithText("保存改名").assertExists()
        compose.onNodeWithTag("layout_custom_save").performScrollTo().performClick()
        compose.onNodeWithText("项目已不存在，请保留草稿并重新选择").assertExists()
        compose.onNodeWithTag("layout_custom_name").assert(hasText("尚未保存的C"))
        compose.runOnIdle {
            assertEquals(1, updates)
            assertEquals(0, adds)
        }
    }

    @Test fun deletedEditTargetKeepsItsDraftAndDoesNotBecomeAnAdd() {
        var items by mutableStateOf(listOf(CustomRecordItem(7, "散步", 0, 0)))
        var adds = 0
        compose.setContent {
            LeziTheme {
                LayoutCustomManageDialog(items, {},
                    onAdd = { _, _, _ -> adds++ }, onUpdate = { _, _ -> }, onDelete = { _, _ -> })
            }
        }
        compose.onNodeWithContentDescription("编辑散步").performClick()
        compose.onNodeWithTag("layout_custom_name").performTextReplacement("未保存的新名称")
        compose.runOnIdle { items = emptyList() }
        compose.onNodeWithText("保存改名").assertExists()
        compose.onNodeWithTag("layout_custom_save").performScrollTo().performClick()
        compose.onNodeWithText("未保存的新名称").assertExists()
        compose.onNodeWithText("项目已不存在，请保留草稿并重新选择").assertExists()
        compose.runOnIdle { assertEquals(0, adds) }
    }

    @Test fun pendingSaveLocksTheDraftAndCannotBeSubmittedAgain() {
        var complete: ((String?) -> Unit)? = null
        compose.setContent {
            LeziTheme {
                LayoutCustomManageDialog(emptyList(), {},
                    onAdd = { _, _, done -> complete = done }, onUpdate = { _, _ -> }, onDelete = { _, _ -> })
            }
        }
        compose.onNodeWithTag("layout_custom_name").performTextReplacement("散步")
        compose.onNodeWithTag("layout_custom_save").performScrollTo().performClick()
        compose.onNodeWithTag("layout_custom_save").assertIsNotEnabled()
        compose.onNodeWithTag("layout_custom_name").assertIsNotEnabled()
        compose.runOnIdle { complete!!.invoke("保存失败") }
        compose.onNodeWithTag("layout_custom_name").performTextReplacement("散步草稿仍可修改")
    }
}
