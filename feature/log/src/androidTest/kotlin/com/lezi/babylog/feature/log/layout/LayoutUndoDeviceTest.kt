package com.lezi.babylog.feature.log.layout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import com.lezi.babylog.designsystem.LeziTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

@OptIn(ExperimentalTestApi::class)
class LayoutUndoDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun durableClearOfferUsesCtrlZWithItsExactToken() {
        val before = snapshot("pee")
        val offer = LayoutUndoCandidate(
            token = 17L,
            kind = LayoutUndoKind.ClearSlot,
            before = before,
            after = snapshot(""),
        )
        var candidate: LayoutUndoCandidate? by mutableStateOf(null)
        val undoTokens = CopyOnWriteArrayList<Long>()

        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                LayoutEditCanvas(
                    prefs = offer.after.toLayoutPrefs(),
                    customItems = emptyList(),
                    onIntent = {},
                    onDone = {},
                    onOpenCustomManage = {},
                    undoCandidate = candidate,
                    onUndo = { undoTokens += it },
                    onUndoExpired = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val done = composeRule.onNodeWithTag("layout_edit_done")
        done.performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()

        composeRule.runOnIdle { candidate = offer }
        composeRule.onNodeWithText("已清空常用槽").fetchSemanticsNode()
        done.performKeyInput { pressKey(Key.Tab) }
        val category = composeRule.onNodeWithTag("layout_edit_category_feeding")
        category.assertIsFocused()
        category.performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.Z)
            keyUp(Key.CtrlLeft)
        }
        composeRule.waitForIdle()

        assertEquals(listOf(17L), undoTokens.toList())
    }

    @Test
    fun newerOfferReplacesTheOldSnackbarAndOnlyItsTokenCanUndo() {
        val first = LayoutUndoCandidate(
            token = 21L,
            kind = LayoutUndoKind.ClearSlot,
            before = snapshot("pee"),
            after = snapshot(""),
        )
        val second = LayoutUndoCandidate(
            token = 22L,
            kind = LayoutUndoKind.MoveToLocalDeleted,
            before = snapshot("bath"),
            after = snapshot("").copy(hiddenItems = setOf("bath")),
        )
        var candidate by mutableStateOf(first)
        val undoTokens = CopyOnWriteArrayList<Long>()

        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                LayoutEditCanvas(
                    prefs = candidate.after.toLayoutPrefs(),
                    customItems = emptyList(),
                    onIntent = {},
                    onDone = {},
                    onOpenCustomManage = {},
                    undoCandidate = candidate,
                    onUndo = { undoTokens += it },
                    onUndoExpired = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.onNodeWithText("已清空常用槽").fetchSemanticsNode()

        composeRule.runOnIdle { candidate = second }

        composeRule.onNodeWithText("已清空常用槽").assertDoesNotExist()
        composeRule.onNodeWithText("已移入本机已删除").fetchSemanticsNode()
        val done = composeRule.onNodeWithTag("layout_edit_done")
        done.performSemanticsAction(SemanticsActions.RequestFocus)
        done.performKeyInput { pressKey(Key.Tab) }
        val category = composeRule.onNodeWithTag("layout_edit_category_feeding")
        category.assertIsFocused()
        category.performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.Z)
            keyUp(Key.CtrlLeft)
        }
        composeRule.waitForIdle()
        assertEquals(listOf(22L), undoTokens.toList())
    }

    @Test
    fun timeoutDismissesTheOfferAndReturnsItsToken() {
        var candidate: LayoutUndoCandidate? by mutableStateOf(
            LayoutUndoCandidate(
                token = 31L,
                kind = LayoutUndoKind.ClearSlot,
                before = snapshot("pee"),
                after = snapshot(""),
            ),
        )
        val expiredTokens = CopyOnWriteArrayList<Long>()
        val undoTokens = CopyOnWriteArrayList<Long>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                LayoutEditCanvas(
                    prefs = snapshot("").toLayoutPrefs(),
                    customItems = emptyList(),
                    onIntent = {},
                    onDone = {},
                    onOpenCustomManage = {},
                    undoCandidate = candidate,
                    onUndo = { undoTokens += it },
                    onUndoExpired = {
                        expiredTokens += it
                        candidate = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已清空常用槽").fetchSemanticsNode()

        composeRule.mainClock.advanceTimeBy(30_000L)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        composeRule.onNodeWithText("已清空常用槽").assertDoesNotExist()
        assertEquals(listOf(31L), expiredTokens.toList())
        val done = composeRule.onNodeWithTag("layout_edit_done")
        done.performSemanticsAction(SemanticsActions.RequestFocus)
        done.performKeyInput { pressKey(Key.Tab) }
        val category = composeRule.onNodeWithTag("layout_edit_category_feeding")
        category.assertIsFocused()
        category.performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.Z)
            keyUp(Key.CtrlLeft)
        }
        composeRule.waitForIdle()
        assertEquals(emptyList<Long>(), undoTokens.toList())
    }

    @Test
    fun stableDeadlineExpiresWithRemainingWindowNotFullRecount() {
        val offer = LayoutUndoCandidate(
            token = 51L,
            kind = LayoutUndoKind.ClearSlot,
            before = snapshot("pee"),
            after = snapshot(""),
        )
        // Already 3s into a 4s offer: session remaining is 1s, not a full Short window.
        var candidate: LayoutUndoCandidate? by mutableStateOf(offer)
        val expiredTokens = CopyOnWriteArrayList<Long>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                LayoutEditCanvas(
                    prefs = snapshot("").toLayoutPrefs(),
                    customItems = emptyList(),
                    onIntent = {},
                    onDone = {},
                    onOpenCustomManage = {},
                    undoCandidate = candidate,
                    undoRemainingOfferMs = 1_000L,
                    onUndo = {},
                    onUndoExpired = {
                        expiredTokens += it
                        candidate = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已清空常用槽").fetchSemanticsNode()

        // Remaining ~1s; advancing a full Material Short (4s+) without stable
        // deadline would still show, but remaining-only must already expire.
        composeRule.mainClock.advanceTimeBy(1_500L)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        composeRule.onNodeWithText("已清空常用槽").assertDoesNotExist()
        assertEquals(listOf(51L), expiredTokens.toList())
    }

    @Test
    fun removingOfferOnExitImmediatelyRemovesItsAction() {
        val prefs = snapshot("").toLayoutPrefs()
        var candidate: LayoutUndoCandidate? by mutableStateOf(
            LayoutUndoCandidate(
                token = 41L,
                kind = LayoutUndoKind.ClearSlot,
                before = snapshot("pee"),
                after = snapshot(""),
            ),
        )

        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                LayoutEditCanvas(
                    prefs = prefs,
                    customItems = emptyList(),
                    onIntent = {},
                    onDone = {},
                    onOpenCustomManage = {},
                    undoCandidate = candidate,
                    onUndo = {},
                    onUndoExpired = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.onNodeWithText("撤销").fetchSemanticsNode()

        composeRule.runOnIdle { candidate = null }

        composeRule.onNodeWithText("撤销").assertDoesNotExist()
        composeRule.onNodeWithText("已清空常用槽").assertDoesNotExist()
    }

    private fun snapshot(firstSlot: String): DeviceLayoutSnapshot =
        DeviceLayoutSnapshot(quickRecordSlots = listOf(firstSlot, "", "", ""))
}
