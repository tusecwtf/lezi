package com.lezi.babylog.feature.log.layout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.encodeItemOrder
import com.lezi.babylog.core.ui.knownCatalogKeys
import com.lezi.babylog.core.ui.mergeItemOrder
import com.lezi.babylog.designsystem.LeziTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

@OptIn(ExperimentalTestApi::class)
class LayoutAccessibilityDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val known = knownCatalogKeys(emptyList())
    private val basePrefs = DeviceLayoutPrefs(
        quickRecordSlots = listOf("pee", "sleep", "nursing", ""),
        hiddenItems = emptySet(),
        itemOrderJson = encodeItemOrder(mergeItemOrder("[]", known)),
        categoryOrderJson = "[]",
    )

    @Test
    fun dailyEmptyIsTruthfulAndOnlyAlternativeActivationOpensExistingEditor() {
        val emptyShortPresses = AtomicInteger(0)
        val editEntries = AtomicInteger(0)
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                OneHandQuickDock(
                    storedSlots = listOf("", "", "", ""),
                    hiddenTypeKeys = emptySet(),
                    customItems = emptyList(),
                    sleepRunning = false,
                    onBound = {},
                    onEmpty = { emptyShortPresses.incrementAndGet() },
                    onMore = {},
                    onLongPress = { editEntries.incrementAndGet() },
                )
            }
        }

        val empty = composeRule.onNodeWithTag("one_hand_action_empty_0")
        empty.assertContentDescriptionEquals(
            "空槽，短按无操作；可使用编辑常用布局操作",
        )
        val emptyConfig = empty.fetchSemanticsNode().config
        assertFalse(emptyConfig.contains(SemanticsActions.OnClick))
        val customActions: List<CustomAccessibilityAction>? =
            if (emptyConfig.contains(SemanticsActions.CustomActions)) {
                emptyConfig[SemanticsActions.CustomActions]
            } else {
                null
            }
        val editAction = customActions?.singleOrNull { it.label == "编辑常用布局" }
        assertNotNull(editAction)

        empty.performTouchInput { click() }
        composeRule.waitForIdle()
        assertEquals(1, emptyShortPresses.get())
        assertEquals(0, editEntries.get())

        assertTrue(checkNotNull(editAction).action())
        composeRule.waitForIdle()
        assertEquals(1, editEntries.get())

        empty.performSemanticsAction(SemanticsActions.RequestFocus)
        empty.performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()
        assertEquals(2, editEntries.get())
    }

    @Test
    fun dailyMoreHasOnlyItsRealOpenActionAndNoLayoutLongClick() {
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                OneHandQuickDock(
                    storedSlots = listOf("", "", "", ""),
                    hiddenTypeKeys = emptySet(),
                    customItems = emptyList(),
                    sleepRunning = false,
                    onBound = {},
                    onEmpty = {},
                    onMore = {},
                    onLongPress = {},
                )
            }
        }

        val config = composeRule.onNodeWithTag("one_hand_action_more").fetchSemanticsNode().config
        assertTrue(config.contains(SemanticsActions.OnClick))
        assertFalse(config.contains(SemanticsActions.OnLongClick))
    }

    @Test
    fun catalogItemCustomActionsAndCtrlChordEmitTheSameExistingIntentSeam() {
        val emitted = showEditor(basePrefs)
        val bath = composeRule.onNodeWithTag("layout_edit_item_bath").performScrollTo()

        invokeAction(bath.fetchSemanticsNode().config, "设为常用槽1")
        invokeAction(bath.fetchSemanticsNode().config, LayoutEditPresentation.hideAction)
        bath.performSemanticsAction(SemanticsActions.RequestFocus)
        bath.performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.One)
            keyUp(Key.CtrlLeft)
        }
        bath.performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.DirectionRight)
            keyUp(Key.CtrlLeft)
        }

        assertEquals(
            listOf(
                LayoutEditIntent.AssignToSlot(slotIndex = 0, catalogKey = "bath"),
                LayoutEditIntent.MoveToLocalDeleted("bath"),
                LayoutEditIntent.AssignToSlot(slotIndex = 0, catalogKey = "bath"),
                LayoutEditIntent.MoveItemInSection("bath", delta = 1),
            ),
            emitted.toList(),
        )
    }

    @Test
    fun slotActionsPreserveEmptyTruthAndLockedMoreHasNoOperation() {
        val emitted = showEditor(basePrefs)
        val slot = composeRule.onNodeWithTag("layout_edit_slot_0")

        invokeAction(slot.fetchSemanticsNode().config, "向右移动")
        invokeAction(slot.fetchSemanticsNode().config, LayoutEditPresentation.hideSlotAction)
        slot.performSemanticsAction(SemanticsActions.RequestFocus)
        slot.performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.DirectionRight)
            keyUp(Key.CtrlLeft)
        }
        slot.performKeyInput { pressKey(Key.Delete) }

        assertEquals(
            listOf(
                LayoutEditIntent.SwapSlots(fromIndex = 0, toIndex = 1),
                LayoutEditIntent.MoveToLocalDeleted("pee"),
                LayoutEditIntent.SwapSlots(fromIndex = 0, toIndex = 1),
                LayoutEditIntent.ClearSlot(slotIndex = 0),
            ),
            emitted.toList(),
        )

        composeRule.onNodeWithTag("layout_edit_slot_3")
            .assertContentDescriptionEquals(
                "常用槽4，空，可从记录项目的操作中指派",
            )
        assertTrue(actionsOf("layout_edit_slot_3").isEmpty())

        val more = composeRule.onNodeWithTag("layout_edit_more_locked")
        more.assertContentDescriptionEquals("更多，固定在末位，编辑布局时已锁定")
        val moreConfig = more.fetchSemanticsNode().config
        assertTrue(moreConfig.contains(SemanticsProperties.StateDescription))
        assertTrue(actionsOf("layout_edit_more_locked").isEmpty())
        assertFalse(moreConfig.contains(SemanticsActions.RequestFocus))
    }

    @Test
    fun deletedRestoreAndCategoryReorderReuseExistingIntents() {
        val prefs = basePrefs.copy(
            quickRecordSlots = listOf("pee", "sleep", "nursing", ""),
            hiddenItems = setOf("bath"),
        )
        val emitted = showEditor(prefs)
        val deleted = composeRule.onNodeWithTag("layout_edit_deleted_bath").performScrollTo()
        invokeAction(deleted.fetchSemanticsNode().config, "恢复到日常末尾")
        deleted.performSemanticsAction(SemanticsActions.RequestFocus)
        deleted.performKeyInput { pressKey(Key.Enter) }

        val category = composeRule.onNodeWithTag("layout_edit_category_routine")
            .performScrollTo()
        invokeAction(category.fetchSemanticsNode().config, "分类前移")
        category.performSemanticsAction(SemanticsActions.RequestFocus)
        category.performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.DirectionDown)
            keyUp(Key.CtrlLeft)
        }

        assertEquals(
            listOf(
                LayoutEditIntent.RestoreFromLocalDeleted("bath"),
                LayoutEditIntent.RestoreFromLocalDeleted("bath"),
                LayoutEditIntent.MoveCategory(RecordSection.Routine, delta = -1),
                LayoutEditIntent.MoveCategory(RecordSection.Routine, delta = 1),
            ),
            emitted.toList(),
        )
    }

    @Test
    fun tabAndDpadMoveFocusWithoutEmittingLayoutMutation() {
        val emitted = showEditor(basePrefs)
        val done = composeRule.onNodeWithTag("layout_edit_done")
        done.performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()

        done.performKeyInput { pressKey(Key.Tab) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("layout_edit_category_feeding").assertIsFocused()
        composeRule.onNodeWithTag("layout_edit_category_feeding")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("layout_edit_item_nursing").assertIsFocused()
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun saveFeedbackIsPoliteAndOnlyDescribesTheCurrentSnapshot() {
        val older = basePrefs.copy(
            quickRecordSlots = listOf("sleep", "pee", "nursing", ""),
        )
        var state by mutableStateOf<DeviceLayoutWriteState>(
            DeviceLayoutWriteState.Saving(basePrefs.toSnapshot()),
        )
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                LayoutEditCanvas(
                    prefs = basePrefs,
                    customItems = emptyList(),
                    onIntent = {},
                    onDone = {},
                    onOpenCustomManage = {},
                    writeState = state,
                    hasSubmittedIntent = true,
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                )
            }
        }

        val feedback = composeRule.onNodeWithTag("layout_edit_save_feedback")
        feedback.assertTextEquals("正在保存布局")
        assertEquals(
            LiveRegionMode.Polite,
            feedback.fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
        )

        composeRule.runOnIdle { state = DeviceLayoutWriteState.Saved(older.toSnapshot()) }
        feedback.assertDoesNotExist()

        composeRule.runOnIdle {
            state = DeviceLayoutWriteState.Failed(
                sequence = 7L,
                snapshot = basePrefs.toSnapshot(),
                cause = IllegalStateException("disk full"),
            )
        }
        feedback.assertTextEquals("布局没有保存成功，下次进入会用默认布局，可重试")

        composeRule.runOnIdle {
            state = DeviceLayoutWriteState.Saving(basePrefs.toSnapshot())
        }
        feedback.assertTextEquals("正在保存布局")
        composeRule.runOnIdle {
            state = DeviceLayoutWriteState.Saved(basePrefs.toSnapshot())
        }
        feedback.assertTextEquals("布局已保存")
    }

    @Test
    fun actionableNodesMeetTouchTargetAndDragAvatarNeverEntersAccessibilityFocus() {
        showEditor(basePrefs)

        composeRule.onNodeWithTag("layout_edit_done").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("layout_edit_category_feeding")
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("layout_edit_item_nursing")
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("layout_edit_slot_0").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("layout_edit_more_locked").assertHeightIsAtLeast(48.dp)

        val source = composeRule.onNodeWithTag("layout_edit_item_nursing")
        source.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveBy(Offset(1f, 1f))
        }
        composeRule.waitForIdle()

        val avatar = composeRule.onNodeWithTag("layout_edit_drag_avatar")
        assertTrue(
            avatar.fetchSemanticsNode().config.contains(SemanticsProperties.InvisibleToUser),
        )
        source.performTouchInput { up() }
        composeRule.waitForIdle()
        avatar.assertDoesNotExist()
    }

    private fun showEditor(prefs: DeviceLayoutPrefs): CopyOnWriteArrayList<LayoutEditIntent> {
        val emitted = CopyOnWriteArrayList<LayoutEditIntent>()
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                LayoutEditCanvas(
                    prefs = prefs,
                    customItems = emptyList(),
                    onIntent = { emitted += it },
                    onDone = {},
                    onOpenCustomManage = {},
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitForIdle()
        return emitted
    }

    private fun actionsOf(tag: String): List<CustomAccessibilityAction> {
        val config = composeRule.onNodeWithTag(tag).fetchSemanticsNode().config
        return if (config.contains(SemanticsActions.CustomActions)) {
            config[SemanticsActions.CustomActions]
        } else {
            emptyList()
        }
    }

    private fun invokeAction(
        config: androidx.compose.ui.semantics.SemanticsConfiguration,
        label: String,
    ) {
        val actions = if (config.contains(SemanticsActions.CustomActions)) {
            config[SemanticsActions.CustomActions]
        } else {
            emptyList()
        }
        val action = actions.singleOrNull { it.label == label }
        assertNotNull("Missing custom action: $label", action)
        assertTrue(checkNotNull(action).action())
        composeRule.waitForIdle()
    }
}
