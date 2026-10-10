package com.lezi.babylog.feature.summary

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.sync.session.ShallowSyncLine
import com.lezi.babylog.sync.session.ShallowSyncState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SummaryRangeTransitionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun outgoingRangeKeepsItsOwnTotalsDuringFade() {
        var ui by mutableStateOf(SummaryUi(
            calculating = false,
            totals = SummaryTotals(chartWindows = ChartWindowTotals(dayFeedCount = 111)),
        ))
        compose.setContent {
            LeziTheme {
                SummaryContent(ui, ui.range, ShallowSyncLine(ShallowSyncState.Synced, "已同步"), false, {}, {})
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            ui = ui.copy(range = SummaryRange.Week,
                totals = SummaryTotals(chartWindows = ChartWindowTotals(dayFeedCount = 222)))
        }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("111", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("222", useUnmergedTree = true).assertExists()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("111", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("222", useUnmergedTree = true).assertExists()
    }
}
