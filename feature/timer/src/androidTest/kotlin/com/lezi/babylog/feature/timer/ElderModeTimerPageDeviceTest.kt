package com.lezi.babylog.feature.timer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeTimerPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderTimerPageFits720pTo4k() {
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        compose.setContent {
            val current = viewport
            val fontScale = when (current.elder) {
                "l1" -> 1.5f
                "l2" -> 1.8f
                else -> 2.1f
            }
            val mode = timerViewportMode(current.height.value.toInt(), fontScale)
            val timerScroll = rememberScrollState()
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_timer_page"),
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            LeziDetailTopBar(
                                title = "喂奶计时",
                                onBack = {},
                                applyStatusBarsPadding = false,
                                modifier = Modifier.testTag("elder_timer_bar"),
                            )
                            TimerSessionPane(
                                state = TimerState(),
                                leftMs = 0,
                                rightMs = 0,
                                viewportMode = mode,
                                actionsEnabled = true,
                                onToggleLeft = {},
                                onToggleRight = {},
                                onComplete = {},
                                onDiscard = {},
                                onRetryService = {},
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .then(
                                        if (mode == TimerViewportMode.Scrollable) {
                                            Modifier.verticalScroll(timerScroll)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .testTag("elder_timer_content"),
                            )
                        }
                    }
                }
            }
        }

        LeziDeviceViewports.styled().forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val page = compose.onNodeWithTag("elder_timer_page").getUnclippedBoundsInRoot()
            val bar = compose.onNodeWithTag("elder_timer_bar").getUnclippedBoundsInRoot()
            val content = compose.onNodeWithTag("elder_timer_content").getUnclippedBoundsInRoot()
            assertTrue(
                "${next.name} timer bar overflow: $bar vs $page",
                bar.right.value <= page.right.value + 0.5f &&
                    bar.bottom.value <= page.bottom.value + 0.5f,
            )
            assertTrue("${next.name} timer content collapsed: $content", content.height >= 48.dp)
            compose.onNodeWithText("喂奶计时").assertExists()
            compose.onNodeWithText("完成并记录").assertExists()
            compose.onNodeWithText("左").assertExists()
            compose.onNodeWithText("右").assertExists()
        }
    }
}
