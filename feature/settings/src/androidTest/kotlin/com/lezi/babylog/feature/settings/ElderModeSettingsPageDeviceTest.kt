package com.lezi.babylog.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Upload
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
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.PageHero
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.designsystem.SectionHeading
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeSettingsPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderSettingsMenuFits720pTo4k() {
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_settings_page"),
                    ) {
                        PageScaffoldBackground {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(LeziSpacing.Page)
                                    .testTag("elder_settings_content"),
                                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                            ) {
                                PageHero(eyebrow = "", title = "菜单")
                                SectionHeading(title = "查找与管理")
                                SettingsMenuRow(
                                    "搜索全部记录",
                                    "按类型、详情或备注查找",
                                    icon = Icons.Outlined.Search,
                                    actionLabel = "搜索全部记录",
                                    onClick = {},
                                )
                                SettingsMenuRow(
                                    "导出数据",
                                    "PDF / TXT 导出与分享",
                                    icon = Icons.Outlined.Upload,
                                    actionLabel = "打开数据导出",
                                    onClick = {},
                                )
                                SettingsMenuRow(
                                    "日程",
                                    "本机提醒与日程列表",
                                    icon = Icons.Outlined.CalendarMonth,
                                    actionLabel = "打开日程",
                                    onClick = {},
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
            val page = compose.onNodeWithTag("elder_settings_page").getUnclippedBoundsInRoot()
            val content = compose.onNodeWithTag("elder_settings_content").getUnclippedBoundsInRoot()
            assertTrue(
                "${next.name} settings overflow: $content vs $page",
                content.left.value >= page.left.value - 0.5f &&
                    content.top.value >= page.top.value - 0.5f &&
                    content.right.value <= page.right.value + 0.5f,
            )
            assertTrue("${next.name} settings collapsed: $content", content.height >= 48.dp)
            compose.onNodeWithText("菜单").assertExists()
            compose.onNodeWithText("搜索全部记录").performScrollTo().assertExists()
            compose.onNodeWithText("日程").performScrollTo().assertExists()
        }
    }
}
