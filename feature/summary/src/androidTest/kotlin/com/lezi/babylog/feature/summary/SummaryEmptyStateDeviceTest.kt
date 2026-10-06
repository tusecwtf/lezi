package com.lezi.babylog.feature.summary

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.sync.session.ShallowSyncLine
import com.lezi.babylog.sync.session.ShallowSyncState
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 票 09（T2）：空周期/空范围的对外可见状态——图表区渲染空态卡、图表面板整组
 * 消失、KPI 区维持现状；非空范围不出现空态卡。
 */
@RunWith(AndroidJUnit4::class)
class SummaryEmptyStateDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(empty: Boolean, totals: SummaryTotals = SummaryTotals()) {
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                SummaryContent(
                    ui = SummaryUi(
                        calculating = false,
                        range = SummaryRange.Week,
                        anchorDate = LocalDate.of(2026, 9, 11),
                        rangeStartDate = LocalDate.of(2026, 9, 5),
                        totals = totals,
                        empty = empty,
                        babyName = "乐乐",
                    ),
                    selectedRange = SummaryRange.Week,
                    shallowSyncStatus = ShallowSyncLine(
                        state = ShallowSyncState.Synced,
                        text = "已同步",
                    ),
                    isRefreshing = false,
                    onRefresh = {},
                    onSelectRange = {},
                )
            }
        }
    }

    @Test
    fun emptyWeekShowsEmptyStateCardInsteadOfChartPanels() {
        setContent(empty = true)

        composeRule.onNodeWithTag("summary_empty_period").assertIsDisplayed()
        composeRule.onNodeWithText("这个范围还没有记录").assertIsDisplayed()
        composeRule.onNodeWithText("去底栏「记录」页记一条", substring = true).assertIsDisplayed()
        listOf("喂养", "睡眠", "尿布").forEach { metric ->
            composeRule.onNodeWithTag("summary_chart_$metric").assertDoesNotExist()
        }
    }

    @Test
    fun emptyWeekKeepsTheKpiStripAsIs() {
        setContent(empty = true)

        // KPI 区维持现状：喂养/睡眠/尿布三卡仍按 0 展示，空态卡只替换图表区。
        composeRule.onNodeWithText("喂养").assertIsDisplayed()
        composeRule.onNodeWithText("睡眠").assertIsDisplayed()
        composeRule.onNodeWithText("尿布").assertIsDisplayed()
    }

    @Test
    fun nonEmptyWeekNeverShowsTheEmptyStateCard() {
        setContent(
            empty = false,
            totals = SummaryTotals(
                feedCount = 5,
                feedMl = 900,
                nursingMin = 30,
                sleepMin = 420,
                pee = 4,
                poop = 2,
                dayValuesFeed = listOf(120f, 0f, 260f, 0f, 180f, 240f, 100f),
                dayValuesSleep = listOf(60f, 0f, 90f, 0f, 80f, 60f, 70f),
                dayValuesPee = listOf(1f, 0f, 1f, 0f, 1f, 0f, 1f),
                dayValuesPoop = listOf(0f, 1f, 0f, 0f, 0f, 1f, 0f),
                dayValuesDiaper = listOf(1f, 1f, 1f, 0f, 1f, 1f, 1f),
            ),
        )

        composeRule.onNodeWithTag("summary_empty_period").assertDoesNotExist()
        composeRule.onNodeWithTag("summary_chart_喂养").assertIsDisplayed()
    }
}
