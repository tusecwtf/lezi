package com.lezi.babylog.feature.log.layout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.lezi.babylog.designsystem.LeziTheme
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

class LayoutDragGuidanceDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun automaticHintIsInlineClosableRevisitableAndRetainedAcrossRecreation() {
        val store = openSession(completed = false)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val session by store.state.collectAsState()
            LeziTheme(visualStyle = "warm") {
                session?.let { current ->
                    LayoutEditCanvas(
                        prefs = current.prefs,
                        customItems = emptyList(),
                        onIntent = {},
                        onDone = {},
                        onOpenCustomManage = {},
                        dragGuidance = current.dragGuidance,
                        onDragGuidanceHelp = {
                            store.reduceDragGuidance(LayoutDragGuidanceEvent.HelpRequested)
                        },
                        onDragGuidanceClose = {
                            store.reduceDragGuidance(LayoutDragGuidanceEvent.CloseRequested)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        compose.onNodeWithTag("layout_edit_guidance_text")
            .assertTextEquals("长按卡片拖到常用槽；拖出槽位可清空。")
        compose.onNodeWithTag("layout_edit_guidance_help").assertIsDisplayed()
        compose.onNodeWithTag("layout_edit_done").assertIsDisplayed()
        compose.onNodeWithTag("layout_edit_slot_0").assertIsDisplayed()
        val hintBounds = compose.onNodeWithTag("layout_edit_guidance")
            .fetchSemanticsNode().boundsInRoot
        val dockBounds = compose.onNodeWithTag("layout_edit_slot_0")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(hintBounds.bottom <= dockBounds.top)

        compose.onNodeWithTag("layout_edit_guidance_close").performClick()
        compose.onNodeWithTag("layout_edit_guidance").assertDoesNotExist()
        compose.onNodeWithTag("layout_edit_guidance_help").performClick()
        compose.onNodeWithTag("layout_edit_guidance").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("layout_edit_guidance").assertIsDisplayed()
        assertEquals(
            LayoutDragGuidanceVisibility.Manual,
            store.current?.dragGuidance?.visibility,
        )
    }

    @Test
    fun completedEntryStartsHiddenButPermanentHelpReopensTheSameHint() {
        val store = openSession(completed = true)
        compose.setContent {
            val session by store.state.collectAsState()
            LeziTheme(darkTheme = true, visualStyle = "journal") {
                session?.let { current ->
                    LayoutEditCanvas(
                        prefs = current.prefs,
                        customItems = emptyList(),
                        onIntent = {},
                        onDone = {},
                        onOpenCustomManage = {},
                        dragGuidance = current.dragGuidance,
                        onDragGuidanceHelp = {
                            store.reduceDragGuidance(LayoutDragGuidanceEvent.HelpRequested)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        compose.onNodeWithTag("layout_edit_guidance").assertDoesNotExist()
        compose.onNodeWithTag("layout_edit_guidance_help").performClick()
        compose.onNodeWithTag("layout_edit_guidance_text")
            .assertTextEquals("长按卡片拖到常用槽；拖出槽位可清空。")
        assertTrue(store.current?.dragGuidance?.completed == true)
    }

    @Test
    fun touchDragUsesItsDedicatedCompletionSeam() {
        val generic = CopyOnWriteArrayList<LayoutEditIntent>()
        val touch = CopyOnWriteArrayList<LayoutEditIntent>()
        compose.setContent {
            LeziTheme(visualStyle = "warm") {
                LayoutEditCanvas(
                    prefs = prefs(),
                    customItems = emptyList(),
                    onIntent = generic::add,
                    onTouchDragIntent = touch::add,
                    onDone = {},
                    onOpenCustomManage = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithTag("layout_edit_item_bath")
            .performScrollTo()
            .assertIsDisplayed()
        val sourceBounds = compose.onNodeWithTag("layout_edit_item_bath")
            .fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithTag("layout_edit_slot_0")
            .fetchSemanticsNode().boundsInRoot.center

        compose.onRoot().performTouchInput {
            down(sourceBounds.center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveTo(Offset(target.x, target.y))
            advanceEventTime(100L)
            up()
        }
        compose.waitForIdle()

        assertTrue(generic.isEmpty())
        assertEquals(
            listOf(LayoutEditIntent.AssignToSlot(slotIndex = 0, catalogKey = "bath")),
            touch.toList(),
        )
    }

    private fun openSession(completed: Boolean): LayoutEditSessionStore =
        LayoutEditSessionStore().also { store ->
            store.open(
                context = LayoutEditSessionContext(
                    babyId = 42L,
                    day = LocalDate.of(2026, 7, 30),
                ),
                prefs = prefs(),
                guidanceCompleted = completed,
            )
        }

    private fun prefs(): DeviceLayoutPrefs = DeviceLayoutPrefs(
        quickRecordSlots = listOf("pee", "sleep", "nursing", "formula"),
        hiddenItems = emptySet(),
        itemOrderJson = "[]",
        categoryOrderJson = "[]",
    )
}
