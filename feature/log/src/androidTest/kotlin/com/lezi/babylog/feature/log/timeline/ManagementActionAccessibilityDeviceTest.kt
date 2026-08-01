package com.lezi.babylog.feature.log.timeline
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.RecordRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

@RunWith(AndroidJUnit4::class)
class ManagementActionAccessibilityDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun touchAndAuthorizedSpokenActionsShareTheManagedRow() {
        val invoked = mutableListOf<String>()
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                RecordRow(
                    time = "08:30",
                    title = "喂奶",
                    summary = "120 ml",
                    relative = "刚刚",
                    onClick = { invoked += "touch" },
                    modifier = Modifier
                        .testTag("managed_record")
                        .managementActions(
                            rowManagementCustomActions(
                                targetLabel = "喂奶记录",
                                canEdit = true,
                                canDelete = true,
                                onEdit = { invoked += "edit" },
                                onDelete = { invoked += "delete" },
                            ),
                        ),
                )
            }
        }

        composeRule.onNodeWithTag("managed_record").performClick()
        val actions = actionsOf("managed_record")
        assertEquals(listOf("编辑喂奶记录", "删除喂奶记录"), actions.map { it.label })
        composeRule.runOnIdle { actions.forEach { assertTrue(it.action()) } }
        assertEquals(listOf("touch", "edit", "delete"), invoked)
    }

    @Test
    fun unauthorizedActionsAreAbsentFromTheFocusableRow() {
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                RecordRow(
                    time = "08:30",
                    title = "喂奶",
                    summary = "120 ml",
                    relative = "刚刚",
                    onClick = {},
                    modifier = Modifier
                        .testTag("read_only_record")
                        .managementActions(
                            rowManagementCustomActions(
                                targetLabel = "喂奶记录",
                                canEdit = false,
                                canDelete = false,
                                onEdit = {},
                                onDelete = {},
                            ),
                        ),
                )
            }
        }

        assertTrue(actionsOf("read_only_record").isEmpty())
    }

    @Test
    fun busyAndFailureArePoliteAndBusySkipIsNotInvokableTwice() {
        val request = ManagementActionRequest(ManagementActionKind.SkipPlan, 7L)
        var state by mutableStateOf<ManagementActionState>(ManagementActionState.Idle)
        var invocations = 0
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                Column {
                    RecordRow(
                        time = "09:00",
                        title = "用药",
                        summary = "待执行",
                        relative = "30 分钟后",
                        onClick = {},
                        modifier = Modifier
                            .testTag("managed_plan")
                            .managementActions(
                                rowManagementCustomActions(
                                    targetLabel = "用药护理计划",
                                    canEdit = true,
                                    canDelete = true,
                                    canSkip = true,
                                    skipEnabled = state !is ManagementActionState.Running,
                                    onEdit = {},
                                    onDelete = {},
                                    onSkip = {
                                        val started = beginManagementAction(state, request)
                                        state = started.state
                                        if (started.accepted) invocations += 1
                                        started.accepted
                                    },
                                ),
                            ),
                    )
                    managementActionFeedback(state, request)?.let { message ->
                        ManagementActionFeedback(
                            message = message,
                            isError = state is ManagementActionState.Failed,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("management_feedback"),
                        )
                    }
                }
            }
        }

        val skip = actionsOf("managed_plan").single { it.label.startsWith("跳过") }
        composeRule.runOnIdle { assertTrue(skip.action()) }
        assertEquals(1, invocations)
        assertFalse(actionsOf("managed_plan").any { it.label.startsWith("跳过") })
        val feedback = composeRule.onNodeWithTag("management_feedback")
        feedback.assertTextEquals("正在跳过护理计划")
        assertEquals(
            LiveRegionMode.Polite,
            feedback.fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
        )

        composeRule.runOnIdle {
            state = finishManagementAction(
                state,
                request,
                Result.failure(Exception("网络不可用，请重试")),
            ).state
        }
        feedback.assertTextEquals("网络不可用，请重试")
        assertTrue(actionsOf("managed_plan").any { it.label.startsWith("跳过") })

        composeRule.runOnIdle { state = ManagementActionState.Idle }
        feedback.assertDoesNotExist()
    }

    private fun actionsOf(tag: String): List<CustomAccessibilityAction> {
        val config = composeRule.onNodeWithTag(tag).fetchSemanticsNode().config
        return if (config.contains(SemanticsActions.CustomActions)) {
            config[SemanticsActions.CustomActions]
        } else {
            emptyList()
        }
    }
}
