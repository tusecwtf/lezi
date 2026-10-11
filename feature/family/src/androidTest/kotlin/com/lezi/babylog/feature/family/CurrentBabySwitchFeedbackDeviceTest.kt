package com.lezi.babylog.feature.family

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.feature.family.overview.AccountOverviewUi
import com.lezi.babylog.feature.family.overview.FamilyOverview
import org.junit.Rule
import org.junit.Test

class CurrentBabySwitchFeedbackDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aFailedSwitchExplainsTheRetainedSelectionAndAllowsTheUserToTryAgain() {
        val first = baby(1, "年年")
        val second = baby(2, "岁岁")
        val feedback = "切换宝宝没有完成，当前宝宝仍是「年年」。请稍后重试"
        val ui = mutableStateOf(AccountOverviewUi(current = first, babies = listOf(first, second)))
        var requests = 0
        compose.setContent {
            LeziTheme {
                FamilyOverview(
                    ui = ui.value,
                    onAddBaby = {},
                    onSetCurrent = {
                        requests += 1
                        ui.value = ui.value.copy(babySwitchFeedback = feedback)
                    },
                    onEditBaby = {},
                    onMergeBaby = {},
                    onDeleteBaby = {},
                )
            }
        }

        compose.onNodeWithText("岁岁").performClick()
        compose.onNodeWithText(feedback).assertIsDisplayed().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
        compose.onNodeWithText("年年").assertIsSelected()
        compose.onNodeWithText("岁岁").assertIsNotSelected().performClick()
        compose.runOnIdle { assertThat(requests).isEqualTo(2) }
    }

    @Test
    fun aPendingSwitchKeepsTheOriginalSelectionAndBlocksRepeatedTaps() {
        val first = baby(1, "年年")
        val second = baby(2, "岁岁")
        compose.setContent {
            LeziTheme {
                FamilyOverview(
                    ui = AccountOverviewUi(
                        current = first,
                        babies = listOf(first, second),
                        switchingBaby = true,
                    ),
                    onAddBaby = {},
                    onSetCurrent = {},
                    onEditBaby = {},
                    onMergeBaby = {},
                    onDeleteBaby = {},
                )
            }
        }

        compose.onNodeWithText("正在切换宝宝…").assertIsDisplayed()
        compose.onNodeWithText("年年").assertIsSelected()
        compose.onNodeWithText("岁岁").assertIsNotSelected().assertIsNotEnabled()
    }

    private fun baby(id: Long, name: String) = Baby(
        id = id,
        familyId = 1,
        nickname = name,
        birthdayEpochDay = 20_000,
        themeColorArgb = 0xff6688aa.toInt(),
        clientUuid = "baby-$id",
        updatedAt = 1,
    )
}
