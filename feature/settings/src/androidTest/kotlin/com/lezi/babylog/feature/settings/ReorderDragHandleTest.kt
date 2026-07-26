package com.lezi.babylog.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.SettingsLocal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReorderDragHandleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longPressDragReportsThePhysicalPointerDistance() {
        var reportedDistance: Float? = null
        compose.setContent {
            MaterialTheme {
                ReorderDragHandle(
                    label = "测试项目",
                    canMove = true,
                    onDragFinished = { reportedDistance = it },
                    modifier = Modifier.testTag("reorder-handle"),
                )
            }
        }

        compose.onNodeWithTag("reorder-handle").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, 180f))
            up()
        }

        compose.runOnIdle {
            assertThat(reportedDistance).isNotNull()
            assertThat(reportedDistance!!).isWithin(1f).of(180f)
        }
    }

    @Test
    fun quickSlotDragUsesRenderedTargetCentersInsteadOfAFixedRowHeight() {
        var reordered: List<String>? = null
        compose.setContent {
            MaterialTheme {
                QuickRecordSlotsSettingsDialog(
                    settings = SettingsLocal(),
                    customItems = emptyList(),
                    onDismiss = {},
                    onSlotsChanged = { reordered = it },
                )
            }
        }
        compose.waitForIdle()
        val first = compose.onNodeWithTag("quick-slot-reorder-0")
        val third = compose.onNodeWithTag("quick-slot-reorder-2")
        val distance = third.fetchSemanticsNode().boundsInRoot.center.y -
            first.fetchSemanticsNode().boundsInRoot.center.y

        first.performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, distance + 1f))
            up()
        }

        compose.runOnIdle {
            assertThat(reordered).containsExactly(
                "sleep",
                "nursing",
                "pee",
                "formula",
            ).inOrder()
        }
    }
}
