package com.lezi.babylog.feature.summary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.sync.session.ShallowSyncLine
import com.lezi.babylog.sync.session.ShallowSyncState
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeSummaryPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderSummaryPageFits720pTo4k() {
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_summary_page"),
                    ) {
                        SummaryContent(
                            ui = SummaryUi(
                                calculating = false,
                                range = SummaryRange.Day,
                                anchorDate = LocalDate.of(2026, 9, 11),
                                rangeStartDate = LocalDate.of(2026, 9, 11),
                                totals = SummaryTotals(
                                    feedCount = 6,
                                    sleepMin = 480,
                                    pee = 4,
                                    poop = 2,
                                    chartWindows = ChartWindowTotals(
                                        dayFeedCount = 6,
                                        daySleepMin = 480,
                                        dayPee = 4,
                                        dayPoop = 2,
                                    ),
                                ),
                                empty = false,
                                babyName = "乐乐",
                            ),
                            selectedRange = SummaryRange.Day,
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
        }

        LeziDeviceViewports.styled().forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val page = compose.onNodeWithTag("elder_summary_page").getUnclippedBoundsInRoot()
            assertTrue("${next.name} summary page collapsed: $page", page.height >= 48.dp)
            compose.onNodeWithText("汇总").assertExists()
            compose.onNodeWithText("日").assertExists()
        }
    }
}
