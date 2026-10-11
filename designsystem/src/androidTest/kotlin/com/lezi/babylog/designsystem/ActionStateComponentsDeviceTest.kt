package com.lezi.babylog.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionStateComponentsDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun enabledActionsHaveButtonRolesAndKeepTheirClicks() {
        var saves = 0
        var cancels = 0
        compose.setContent {
            Column {
                listOf("warm", "journal").forEach { style ->
                    LeziTheme(visualStyle = style) {
                        LeziPrimaryButton(
                            "保存",
                            onClick = { saves += 1 },
                            modifier = Modifier.testTag("${style}_primary"),
                        )
                        LeziSecondaryButton(
                            "取消",
                            onClick = { cancels += 1 },
                            modifier = Modifier.testTag("${style}_secondary"),
                        )
                    }
                }
            }
        }

        listOf("warm", "journal").forEach { style ->
            listOf("primary", "secondary").forEach { action ->
                compose.onNodeWithTag("${style}_$action")
                    .assert(buttonRole)
                    .assertIsEnabled()
                    .performClick()
            }
        }
        compose.runOnIdle {
            assertEquals(2, saves)
            assertEquals(2, cancels)
        }
    }

    @Test
    fun busyActionsBlockRepeatedTapsAndBecomeActionableAfterCompletion() {
        val busy = mutableStateOf(true)
        var saves = 0
        var retries = 0
        compose.setContent {
            LeziTheme {
                Column {
                    LeziPrimaryButton(
                        "保存",
                        onClick = { saves += 1 },
                        busy = busy.value,
                        modifier = Modifier.testTag(PRIMARY),
                    )
                    LeziSecondaryButton(
                        "重试",
                        onClick = { retries += 1 },
                        busy = busy.value,
                        modifier = Modifier.testTag(SECONDARY),
                    )
                }
            }
        }

        listOf(PRIMARY, SECONDARY).forEach { tag ->
            compose.onNodeWithTag(tag).assert(buttonRole).assertIsNotEnabled()
                .performTouchInput {
                    click()
                    click()
                }
        }
        compose.runOnIdle {
            assertEquals(0, saves)
            assertEquals(0, retries)
            busy.value = false
        }
        listOf(PRIMARY, SECONDARY).forEach { tag ->
            compose.onNodeWithTag(tag).assertIsEnabled().performClick()
        }
        compose.runOnIdle {
            assertEquals(1, saves)
            assertEquals(1, retries)
        }
    }

    @Test
    fun trulyDisabledActionsKeepTheirRolesAndIgnoreTaps() {
        var clicks = 0
        compose.setContent {
            LeziTheme {
                Column {
                    LeziPrimaryButton(
                        "保存",
                        onClick = { clicks += 1 },
                        enabled = false,
                        modifier = Modifier.testTag(PRIMARY),
                    )
                    LeziSecondaryButton(
                        "取消",
                        onClick = { clicks += 1 },
                        enabled = false,
                        modifier = Modifier.testTag(SECONDARY),
                    )
                }
            }
        }

        listOf(PRIMARY, SECONDARY).forEach { tag ->
            compose.onNodeWithTag(tag).assert(buttonRole).assertIsNotEnabled()
                .performTouchInput { click() }
        }
        compose.runOnIdle { assertEquals(0, clicks) }
    }

    @Test
    fun explainedDisabledRemainsActionableAndScopesFeedbackToItsConcreteReason() {
        val reason = mutableStateOf<String?>(null)
        var explanations = 0
        compose.setContent {
            LeziTheme {
                Column {
                    LeziPrimaryButton(
                        "保存",
                        onClick = {
                            explanations += 1
                            reason.value = "请先填写记录时间"
                        },
                        enabled = false,
                        mode = LeziPrimaryButtonMode.ExplainedDisabled,
                        modifier = Modifier.testTag(PRIMARY),
                    )
                    reason.value?.let {
                        LeziConfirmReasonCard(it, modifier = Modifier.testTag(REASON))
                    }
                }
            }
        }

        compose.onAllNodes(liveRegion, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag(PRIMARY).assert(buttonRole).assertIsEnabled().performClick()
        compose.onNodeWithTag(REASON)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("请先填写记录时间")))
        compose.onAllNodes(liveRegion, useUnmergedTree = true).assertCountEquals(1)
        compose.runOnIdle { assertEquals(1, explanations) }
    }

    @Test
    fun busyExplainedDisabledDoesNotOpenAReason() {
        var explanations = 0
        compose.setContent {
            LeziTheme {
                LeziPrimaryButton(
                    "保存中",
                    onClick = { explanations += 1 },
                    busy = true,
                    mode = LeziPrimaryButtonMode.ExplainedDisabled,
                    modifier = Modifier.testTag(PRIMARY),
                )
            }
        }

        compose.onNodeWithTag(PRIMARY).assert(buttonRole).assertIsNotEnabled()
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, explanations) }
    }

    @Test
    fun genericListStateUpdatesDoNotBecomeLiveAnnouncements() {
        val kind = mutableStateOf(StateKind.Loading)
        compose.setContent {
            LeziTheme {
                StateContainer(
                    kind = kind.value,
                    title = if (kind.value == StateKind.Loading) "加载中" else "暂时无法读取",
                    message = if (kind.value == StateKind.Loading) "正在读取记录" else "请稍后重试",
                )
            }
        }

        compose.onAllNodes(liveRegion, useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { kind.value = StateKind.Error }
        compose.onNodeWithText("暂时无法读取").assertExists()
        compose.onAllNodes(liveRegion, useUnmergedTree = true).assertCountEquals(0)
    }

    private companion object {
        const val PRIMARY = "primary_action"
        const val SECONDARY = "secondary_action"
        const val REASON = "action_reason"
        val buttonRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        val liveRegion = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
    }
}
