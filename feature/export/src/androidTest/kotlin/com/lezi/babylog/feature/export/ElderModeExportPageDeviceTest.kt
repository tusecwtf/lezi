package com.lezi.babylog.feature.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziDetailTopBar
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.LeziTypography
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeExportPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderExportPageFits720pTo4k() {
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_export_page"),
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            LeziDetailTopBar(
                                title = "导出记录",
                                onBack = {},
                                applyStatusBarsPadding = false,
                                modifier = Modifier.testTag("elder_export_bar"),
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(LeziSpacing.Page)
                                    .testTag("elder_export_content"),
                                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                            ) {
                                LeziSurfacePanel(Modifier.fillMaxWidth(), bottomBand = true) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
                                    ) {
                                        Text("导出范围", style = LeziTypography.TitleSm)
                                        Text("2026年9月1日 — 2026年9月30日", style = LeziTypography.BodyStrong)
                                        LeziSecondaryButton(
                                            label = "选择开始日期",
                                            onClick = {},
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        LeziSecondaryButton(
                                            label = "选择结束日期",
                                            onClick = {},
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                                LeziSecondaryButton(
                                    label = "导出 TXT 并分享",
                                    onClick = {},
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                LeziPrimaryButton(
                                    label = "导出 PDF 并分享",
                                    onClick = {},
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }

        LeziDeviceViewports.styled().forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val page = compose.onNodeWithTag("elder_export_page").getUnclippedBoundsInRoot()
            val bar = compose.onNodeWithTag("elder_export_bar").getUnclippedBoundsInRoot()
            val content = compose.onNodeWithTag("elder_export_content").getUnclippedBoundsInRoot()
            assertTrue(
                "${next.name} export bar overflow: $bar vs $page",
                bar.right.value <= page.right.value + 0.5f,
            )
            assertTrue("${next.name} export content collapsed: $content", content.height >= 48.dp)
            compose.onNodeWithText("导出记录").assertExists()
            compose.onNodeWithText("导出 PDF 并分享").performScrollTo().assertExists()
        }
    }
}
