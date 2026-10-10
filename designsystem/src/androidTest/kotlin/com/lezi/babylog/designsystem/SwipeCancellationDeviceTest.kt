package com.lezi.babylog.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwipeCancellationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancelledDeleteSwipeDoesNotRequestDeletion() = swipe(right = true, cancelled = true)
    @Test fun cancelledEditSwipeDoesNotRequestEditing() = swipe(right = false, cancelled = true)
    @Test fun releasedDeleteSwipeRequestsDeletionOnce() = swipe(right = true, cancelled = false)
    @Test fun releasedEditSwipeRequestsEditingOnce() = swipe(right = false, cancelled = false)

    @Test fun permissionLossCancelsPendingGestureWithoutAnAction() {
        var enabled by mutableStateOf(true)
        var actions = 0
        compose.setContent {
            var open by remember { mutableStateOf(false) }
            LeziTheme {
                SwipeEditDeleteRow(open, { open = it }, { actions++ }, { actions++ },
                    modifier = Modifier.testTag("permission_row"),
                    editEnabled = enabled, deleteEnabled = enabled,
                ) { Box(Modifier.height(64.dp)) }
            }
        }
        compose.onNodeWithTag("permission_row").performTouchInput {
            down(Offset(width * .1f, centerY))
            moveTo(Offset(width * .9f, centerY))
        }
        compose.runOnIdle { enabled = false }
        compose.onNodeWithTag("permission_row").performTouchInput { up() }
        compose.runOnIdle { assertEquals(0, actions) }
    }

    private fun swipe(right: Boolean, cancelled: Boolean) {
        var edits = 0
        var deletes = 0
        var isOpen = false
        compose.setContent {
            var open by remember { mutableStateOf(false) }
            LeziTheme {
                SwipeEditDeleteRow(
                    open = open,
                    onOpenChange = { open = it; isOpen = it },
                    onEdit = { edits++ },
                    onDelete = { deletes++ },
                    modifier = Modifier.testTag("row"),
                ) { Box(Modifier.height(64.dp)) }
            }
        }
        compose.onNodeWithTag("row").performTouchInput {
            down(Offset(if (right) width * .1f else width * .9f, centerY))
            moveTo(Offset(if (right) width * .9f else width * .1f, centerY))
            if (cancelled) cancel() else up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(if (!cancelled && !right) 1 else 0, edits)
            assertEquals(if (!cancelled && right) 1 else 0, deletes)
            assertEquals(false, isOpen)
        }
    }
}
