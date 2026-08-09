package com.lezi.babylog.feature.summary

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SummaryDuplicateBoundsDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unresolvedDuplicateNoticeRendersHonestBoundsSemantics() {
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                SummaryDuplicateBoundsNotice(visible = true)
            }
        }

        val notice = composeRule.onNodeWithTag("summary_duplicate_bounds")
            .assertExists()
            .assertTextContains("疑似重复尚未确认")
            .assertTextContains("下界–上界")
        assertThat(notice.captureToImage().width).isGreaterThan(0)
    }

    @Test
    fun resolvedSummaryDoesNotRenderUncertaintyNotice() {
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                SummaryDuplicateBoundsNotice(visible = false)
            }
        }

        composeRule.onNodeWithTag("summary_duplicate_bounds").assertDoesNotExist()
    }
}
