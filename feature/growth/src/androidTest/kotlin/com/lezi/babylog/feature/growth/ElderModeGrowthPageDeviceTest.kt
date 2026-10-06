package com.lezi.babylog.feature.growth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.lezi.babylog.core.model.GrowthReferenceBand
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.PageHero
import com.lezi.babylog.designsystem.PageScaffoldBackground
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeGrowthPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderGrowthPageFits720pTo4k() {
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_growth_page"),
                    ) {
                        PageScaffoldBackground {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(LeziSpacing.Page)
                                    .testTag("elder_growth_content"),
                                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                            ) {
                                PageHero(eyebrow = "", title = "成长")
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
            }
        }

        LeziDeviceViewports.styled().forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val page = compose.onNodeWithTag("elder_growth_page").getUnclippedBoundsInRoot()
            val content = compose.onNodeWithTag("elder_growth_content").getUnclippedBoundsInRoot()
            assertTrue(
                "${next.name} growth overflow: $content vs $page",
                content.left.value >= page.left.value - 0.5f &&
                    content.top.value >= page.top.value - 0.5f &&
                    content.right.value <= page.right.value + 0.5f,
            )
            assertTrue("${next.name} growth collapsed: $content", content.height >= 48.dp)
            compose.onNodeWithText("成长").assertExists()
            compose.onNodeWithTag("growth_chart").assertExists()
            compose.onNodeWithText("— P50").assertExists()
        }
    }
}
