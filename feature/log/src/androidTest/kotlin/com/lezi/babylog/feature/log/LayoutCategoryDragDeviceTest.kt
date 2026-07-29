package com.lezi.babylog.feature.log

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.encodeItemOrder
import com.lezi.babylog.core.ui.knownCatalogKeys
import com.lezi.babylog.core.ui.mergeItemOrder
import com.lezi.babylog.designsystem.LeziTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LayoutCategoryDragDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val known = knownCatalogKeys(emptyList())
    private fun prefsWithVisible(vararg keys: String) = DeviceLayoutPrefs(
        quickRecordSlots = listOf("pee", "sleep", "nursing", "formula"),
        hiddenItems = known.toSet() - keys.toSet(),
        itemOrderJson = encodeItemOrder(mergeItemOrder("[]", known)),
        categoryOrderJson =
            """["feeding","excretion","routine","health","growth","custom"]""",
    )

    @Test
    fun categoryHeadingCanBeDraggedToFirst() {
        val emitted = showEditor(prefsWithVisible("nursing"), visualStyle = "journal")

        drag("layout_edit_category_custom", centerOf("layout_edit_category_feeding"))

        assertEquals(
            listOf(LayoutEditIntent.MoveCategoryToIndex(RecordSection.Custom, 0)),
            emitted.toList(),
        )
    }

    @Test
    fun categoryHeadingCanBeDraggedToMiddle() {
        val emitted = showEditor(prefsWithVisible("sleep"))

        composeRule.onNodeWithTag("layout_edit_category_routine").assertIsDisplayed()
        drag("layout_edit_category_custom", centerOf("layout_edit_category_routine"))

        assertEquals(
            listOf(LayoutEditIntent.MoveCategoryToIndex(RecordSection.Custom, 2)),
            emitted.toList(),
        )
    }

    @Test
    fun categoryHeadingCanBeDraggedToLast() {
        val emitted = showEditor(prefsWithVisible("nursing"))

        composeRule.onNodeWithTag("layout_edit_category_custom").assertIsDisplayed()
        drag("layout_edit_category_feeding", centerOf("layout_edit_category_custom"))

        assertEquals(
            listOf(LayoutEditIntent.MoveCategoryToIndex(RecordSection.Feeding, 5)),
            emitted.toList(),
        )
    }

    @Test
    fun categoryHeadingShowsTheSharedDragAvatarWhileHeldOverATarget() {
        val emitted = showEditor(prefsWithVisible("nursing"))
        val source = composeRule.onNodeWithTag("layout_edit_category_custom")
            .assertIsDisplayed()
        val sourceBounds = source.fetchSemanticsNode().boundsInRoot
        val target = centerOf("layout_edit_category_feeding") - sourceBounds.topLeft

        source.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveTo(target)
            advanceEventTime(100L)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("layout_edit_drag_avatar").assertIsDisplayed()
        source.performTouchInput { up() }
        composeRule.waitForIdle()
        assertEquals(
            listOf(LayoutEditIntent.MoveCategoryToIndex(RecordSection.Custom, 0)),
            emitted.toList(),
        )
    }

    private fun showEditor(
        initial: DeviceLayoutPrefs,
        visualStyle: String = "warm",
    ): CopyOnWriteArrayList<LayoutEditIntent> {
        val emitted = CopyOnWriteArrayList<LayoutEditIntent>()
        var prefs by mutableStateOf(initial)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                LeziTheme(visualStyle = visualStyle) {
                    LayoutEditCanvas(
                        prefs = prefs,
                        customItems = emptyList(),
                        onIntent = { intent ->
                            emitted += intent
                            prefs = reduceLayoutEdit(prefs, intent, known)
                        },
                        onDone = {},
                        onOpenCustomManage = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        return emitted
    }

    private fun centerOf(tag: String): Offset =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center

    private fun drag(sourceTag: String, targetInRoot: Offset) {
        val source = composeRule.onNodeWithTag(sourceTag).assertIsDisplayed()
        val sourceBounds = source.fetchSemanticsNode().boundsInRoot
        source.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveTo(targetInRoot - sourceBounds.topLeft)
            advanceEventTime(100L)
            up()
        }
        composeRule.waitForIdle()
    }
}
