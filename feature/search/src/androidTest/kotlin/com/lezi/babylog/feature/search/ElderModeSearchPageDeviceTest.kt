package com.lezi.babylog.feature.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziDetailTopBar
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTextField
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeSearchPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderSearchPromptFits720pTo4k() {
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_search_page"),
                    ) {
                        Scaffold(
                            topBar = {
                                LeziDetailTopBar(
                                    title = stringResource(R.string.search_title),
                                    onBack = {},
                                    applyStatusBarsPadding = false,
                                    modifier = Modifier.testTag("elder_search_bar"),
                                )
                            },
                        ) { padding ->
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .padding(padding)
                                    .padding(LeziSpacing.Page)
                                    .testTag("elder_search_content"),
                            ) {
                                LeziTextField(
                                    value = "",
                                    onValueChange = {},
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.search_field_label)) },
                                    placeholder = {
                                        Text(stringResource(R.string.search_field_placeholder))
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Search, contentDescription = null)
                                    },
                                )
                                StateContainer(
                                    kind = StateKind.Empty,
                                    title = stringResource(R.string.search_prompt_title),
                                    message = stringResource(R.string.search_prompt_message),
                                    modifier = Modifier.padding(top = LeziSpacing.Md),
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
            val page = compose.onNodeWithTag("elder_search_page").getUnclippedBoundsInRoot()
            val bar = compose.onNodeWithTag("elder_search_bar").getUnclippedBoundsInRoot()
            val content = compose.onNodeWithTag("elder_search_content").getUnclippedBoundsInRoot()
            assertTrue(
                "${next.name} search bar overflow: $bar vs $page",
                bar.right.value <= page.right.value + 0.5f,
            )
            assertTrue("${next.name} search content collapsed: $content", content.height >= 48.dp)
            compose.onNodeWithText("搜索").assertExists()
            compose.onNodeWithText("输入关键字").assertExists()
        }
    }
}
