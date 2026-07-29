package com.lezi.babylog.feature.log

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.lezi.babylog.core.ui.encodeItemOrder
import com.lezi.babylog.core.ui.knownCatalogKeys
import com.lezi.babylog.core.ui.mergeItemOrder
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class LayoutEditDropMatrixDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val known = knownCatalogKeys(emptyList())
    private val basePrefs = DeviceLayoutPrefs(
        quickRecordSlots = listOf("pee", "sleep", "nursing", "formula"),
        hiddenItems = emptySet(),
        itemOrderJson = encodeItemOrder(mergeItemOrder("[]", known)),
        categoryOrderJson = "[]",
    )

    @Test
    fun boundSlotToLockedMoreAndDockGapAreNoOps() {
        val emitted = showEditor(basePrefs)

        drag("layout_edit_slot_0", centerOf("layout_edit_more_locked"))
        assertTrue(emitted.isEmpty())

        val slot2 = boundsOf("layout_edit_slot_2")
        val slot3 = boundsOf("layout_edit_slot_3")
        val gap = Offset(
            x = (slot2.right + slot3.left) / 2f,
            y = slot2.center.y,
        )
        drag("layout_edit_slot_0", gap)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun boundSlotToTrueDockOutsideClearsOnlyItsSource() {
        val emitted = showEditor(basePrefs)

        drag("layout_edit_slot_1", centerOf("layout_edit_title"))

        assertEquals(listOf(LayoutEditIntent.ClearSlot(1)), emitted.toList())
    }

    @Test
    fun deletedItemOverSlotRestoresAndNeverAssigns() {
        val emitted = showEditor(
            basePrefs.copy(
                quickRecordSlots = listOf("pee", "", "nursing", "formula"),
                hiddenItems = setOf("sleep"),
            ),
        )

        drag("layout_edit_deleted_sleep", centerOf("layout_edit_slot_1"))

        assertEquals(
            listOf(LayoutEditIntent.RestoreFromLocalDeleted("sleep")),
            emitted.toList(),
        )
    }

    @Test
    fun catalogItemDraggedAfterScrollUsesItsCurrentBounds() {
        val emitted = showEditor(basePrefs)
        composeRule.onNodeWithTag("layout_edit_item_bath")
            .performScrollTo()
            .assertIsDisplayed()

        drag("layout_edit_item_bath", centerOf("layout_edit_slot_0"))

        assertEquals(
            listOf(LayoutEditIntent.AssignToSlot(0, "bath")),
            emitted.toList(),
        )
    }

    private fun showEditor(prefs: DeviceLayoutPrefs): CopyOnWriteArrayList<LayoutEditIntent> {
        val emitted = CopyOnWriteArrayList<LayoutEditIntent>()
        composeRule.setContent {
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
        composeRule.waitForIdle()
        return emitted
    }

    private fun boundsOf(tag: String) =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun centerOf(tag: String): Offset = boundsOf(tag).center

    private fun drag(sourceTag: String, targetInRoot: Offset) {
        val source = composeRule.onNodeWithTag(sourceTag).assertIsDisplayed()
        val sourceBounds = source.fetchSemanticsNode().boundsInRoot
        val targetInSource = targetInRoot - sourceBounds.topLeft
        source.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveTo(targetInSource)
            advanceEventTime(100L)
            up()
        }
        composeRule.waitForIdle()
    }
}
