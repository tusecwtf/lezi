package com.lezi.babylog.feature.settings.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.lezi.babylog.designsystem.LeziDetailTopBar
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.PageScaffoldBackground
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeCalendarPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderCalendarPageFits720pTo4k() {
        val today = LocalDate.of(2026, 9, 11)
        val monthState = CalendarMonthState.initial(today, today)
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_calendar_page"),
                    ) {
                        PageScaffoldBackground {
                            Column(Modifier.fillMaxSize()) {
                                LeziDetailTopBar(
                                    title = "乐记日历",
                                    onBack = {},
                                    applyStatusBarsPadding = false,
                                    modifier = Modifier.testTag("elder_calendar_bar"),
                                )
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(LeziSpacing.Page)
                                        .testTag(CalendarUiTags.SelectedDayItems),
                                    verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                                ) {
                                    CalendarMonthPicker(
                                        state = monthState,
                                        itemCountsByDate = emptyMap(),
                                        onPreviousMonth = {},
                                        onNextMonth = {},
                                        onShowMonth = {},
                                        onSelectDate = {},
                                    )
                                    LeziPrimaryButton(
                                        "＋ 安排护理",
                                        onClick = {},
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag(CalendarUiTags.ScheduleCare),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        LeziDeviceViewports.styled().forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val page = compose.onNodeWithTag("elder_calendar_page").getUnclippedBoundsInRoot()
            val bar = compose.onNodeWithTag("elder_calendar_bar").getUnclippedBoundsInRoot()
            val grid = compose.onNodeWithTag(CalendarUiTags.MonthGrid).getUnclippedBoundsInRoot()
            assertTrue(
                "${next.name} calendar bar overflow: $bar vs $page",
                bar.right.value <= page.right.value + 0.5f,
            )
            assertTrue("${next.name} calendar grid collapsed: $grid", grid.height >= 48.dp)
            compose.onNodeWithText("乐记日历").assertExists()
            compose.onNodeWithTag(CalendarUiTags.ScheduleCare).assertExists()
        }
    }
}
