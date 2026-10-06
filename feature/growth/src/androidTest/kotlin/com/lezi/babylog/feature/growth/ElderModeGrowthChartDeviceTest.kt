package com.lezi.babylog.feature.growth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.core.model.GrowthReferenceBand
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeGrowthChartDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun l3CanvasIsTallerAndLegendStaysVisible() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LeziTheme(elderMode = "l3") {
                    Box(Modifier.width(390.dp)) {
                        // The chart draws with an internal 12dp top inset inside its
                        // 280dp height box, so measure the box via the wrapper.
                        Box(Modifier.testTag("growth_chart_box")) {
                            GrowthChart(
                                points = listOf(
                                    MeasurePoint(
                                        monthAge = 1f,
                                        value = 5.2f,
                                        recordId = 1L,
                                        measuredAt = 0L,
                                        note = null,
                                        referenceWarning = null,
                                    ),
                                    MeasurePoint(
                                        monthAge = 6f,
                                        value = 7.8f,
                                        recordId = 2L,
                                        measuredAt = 0L,
                                        note = null,
                                        referenceWarning = null,
                                    ),
                                ),
                                bands = listOf(
                                    GrowthReferenceBand(month = 0f, p3 = 2.5f, p50 = 3.3f, p97 = 4.3f),
                                    GrowthReferenceBand(month = 6f, p3 = 6.9f, p50 = 8.0f, p97 = 9.2f),
                                    GrowthReferenceBand(month = 12f, p3 = 8.5f, p50 = 9.6f, p97 = 11.9f),
                                ),
                                metric = GrowthMetric.WEIGHT,
                            )
                        }
                        GrowthPercentileLegend()
                    }
                }
            }
        }

        val chartBox = compose.onNodeWithTag("growth_chart_box")
        chartBox.assertIsDisplayed()
        val bounds = chartBox.getUnclippedBoundsInRoot()
        assertTrue("chart box height ${bounds.height}", bounds.height.value >= 280f)
        compose.onNodeWithTag("growth_chart").assertIsDisplayed()
        listOf("— P3", "— P50", "— P97").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }
}
