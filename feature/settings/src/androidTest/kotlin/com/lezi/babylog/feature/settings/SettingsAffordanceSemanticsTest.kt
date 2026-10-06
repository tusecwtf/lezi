package com.lezi.babylog.feature.settings

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAffordanceSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun informationalBabyRowHasNoClickRoleOrChevron() {
        compose.setContent {
            LeziTheme {
                SettingsMenuRow(
                    title = "宝宝档案",
                    subtitle = "宝宝档案由家庭管理员管理",
                    modifier = Modifier.testTag(READ_ONLY_BABY_ROW),
                )
            }
        }

        val row = compose.onNodeWithTag(READ_ONLY_BABY_ROW)
            .assertHasNoClickAction()
            .fetchSemanticsNode()
        assertThat(row.config.contains(SemanticsProperties.Role)).isFalse()
        compose.onAllNodesWithTag(SETTINGS_MENU_ROW_MORE_TAG, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun actionableRowHasOneWholeRowActionWithMatchingLabelAndResult() {
        var clickCount = 0
        compose.setContent {
            LeziTheme {
                SettingsMenuRow(
                    title = "年年",
                    subtitle = "打开年年的本机设置",
                    actionLabel = "打开年年的本机设置",
                    onClick = { clickCount += 1 },
                    modifier = Modifier.testTag(ACTIONABLE_BABY_ROW),
                )
            }
        }

        val row = compose.onNodeWithTag(ACTIONABLE_BABY_ROW)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        val semantics = row.fetchSemanticsNode().config
        assertThat(semantics[SemanticsProperties.Role]).isEqualTo(Role.Button)
        assertThat(semantics[SemanticsActions.OnClick].label)
            .isEqualTo("打开年年的本机设置")
        compose.onAllNodesWithTag(SETTINGS_MENU_ROW_MORE_TAG, useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
            useUnmergedTree = true,
        ).assertCountEquals(1)

        row.performClick()
        compose.runOnIdle { assertThat(clickCount).isEqualTo(1) }
    }

    private companion object {
        const val READ_ONLY_BABY_ROW = "settings_read_only_baby_row"
        const val ACTIONABLE_BABY_ROW = "settings_actionable_baby_row"
    }
}
