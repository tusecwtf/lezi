package com.lezi.babylog.feature.log

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.LocalHapticFeedback
import com.lezi.babylog.core.ui.encodeItemOrder
import com.lezi.babylog.core.ui.knownCatalogKeys
import com.lezi.babylog.core.ui.mergeItemOrder
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class LayoutMotionHapticsDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun idleLayoutNodesStayStationaryAsTheClockAdvances() {
        composeRule.mainClock.autoAdvance = false
        showEditor()
        composeRule.mainClock.advanceTimeByFrame()
        val catalogBefore = boundsOf("layout_edit_item_pee")
        val dockBefore = boundsOf("layout_edit_slot_0")

        composeRule.mainClock.advanceTimeBy(1_000L)

        assertEquals(catalogBefore, boundsOf("layout_edit_item_pee"))
        assertEquals(dockBefore, boundsOf("layout_edit_slot_0"))
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun longPressNewTargetAndAcceptedDropEachEmitOneRateLimitedHaptic() {
        val haptics = RecordingHapticFeedback()
        val emitted = showEditor(haptics)
        val source = boundsOf("layout_edit_item_pee").center
        val target = boundsOf("layout_edit_slot_1").center

        composeRule.onRoot().performTouchInput {
            down(source)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveTo(target)
            advanceEventTime(30L)
            moveTo(target + Offset(1f, 0f))
            advanceEventTime(30L)
            moveTo(target)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(
            listOf(
                HapticFeedbackType.LongPress,
                HapticFeedbackType.TextHandleMove,
                HapticFeedbackType.LongPress,
            ),
            haptics.events.toList(),
        )
        assertEquals(
            listOf(LayoutEditIntent.AssignToSlot(slotIndex = 1, catalogKey = "pee")),
            emitted.toList(),
        )
    }

    @Test
    fun shortPressAndNoOpDropNeverEmitSuccessHaptics() {
        val haptics = RecordingHapticFeedback()
        showEditor(haptics)
        val item = boundsOf("layout_edit_item_pee").center
        composeRule.onRoot().performTouchInput {
            down(item)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis / 2L)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(emptyList<HapticFeedbackType>(), haptics.events.toList())

        val slot = boundsOf("layout_edit_slot_0").center
        composeRule.onRoot().performTouchInput {
            down(slot)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveTo(slot + Offset(1f, 0f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals(listOf(HapticFeedbackType.LongPress), haptics.events.toList())
    }

    private fun showEditor(
        hapticFeedback: HapticFeedback? = null,
    ): CopyOnWriteArrayList<LayoutEditIntent> {
        val known = knownCatalogKeys(emptyList())
        val prefs = DeviceLayoutPrefs(
            quickRecordSlots = listOf("pee", "sleep", "nursing", "formula"),
            hiddenItems = emptySet(),
            itemOrderJson = encodeItemOrder(mergeItemOrder("[]", known)),
            categoryOrderJson = "[]",
        )
        val emitted = CopyOnWriteArrayList<LayoutEditIntent>()
        composeRule.setContent {
            val content = @androidx.compose.runtime.Composable {
                LeziTheme(visualStyle = "warm") {
                    LayoutEditCanvas(
                        prefs = prefs,
                        customItems = emptyList(),
                        onIntent = emitted::add,
                        onDone = {},
                        onOpenCustomManage = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (hapticFeedback == null) {
                content()
            } else {
                CompositionLocalProvider(LocalHapticFeedback provides hapticFeedback) {
                    content()
                }
            }
        }
        composeRule.waitForIdle()
        return emitted
    }

    private fun boundsOf(tag: String) =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private class RecordingHapticFeedback : HapticFeedback {
        val events = CopyOnWriteArrayList<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            events += hapticFeedbackType
        }
    }
}
