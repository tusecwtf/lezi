package com.lezi.babylog.feature.summary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeSummaryChartsDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderDayHidesChartsAndWeekKeepsThem() {
        var range by mutableStateOf(SummaryRange.Day)
        val dates = List(7) { LocalDate.of(2026, 8, 17).plusDays(it.toLong()) }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LeziTheme(elderMode = "l3") {
                    Box(Modifier.width(390.dp)) {
                        SummaryChartSections(
                            range = range,
                            totals = SummaryTotals(
                                dayValuesSleep = listOf(120f, 90f, 100f, 110f, 130f, 80f, 95f),
                            ),
                            windows = ChartWindowTotals(daySleepMin = 725),
                            chartDates = dates,
                            food = null,
                            showAvgSleep = false,
                            chartCardPad = PaddingValues(16.dp),
                        )
                    }
                }
            }
        }

        // Day + elder: positive sleep data exists, so only the hide policy can
        // explain the missing panel.
        compose.onNodeWithTag("summary_chart_睡眠").assertDoesNotExist()
        compose.runOnIdle { range = SummaryRange.Week }
        compose.onNodeWithTag("summary_chart_睡眠").assertExists()
    }
}
