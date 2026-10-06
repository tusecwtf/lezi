package com.lezi.babylog.feature.timer

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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeNursingCompletionPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderNursingCompletionFits720pTo4k() {
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        val draft = NursingCompletionDraft(
            leftMinutes = "1",
            rightMinutes = "0",
            order = "L",
            startedAt = 1_700_000_000_000L,
            endedAt = 1_700_000_060_000L,
            capturedAt = 1_700_000_060_000L,
        )
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_nursing_completion_page"),
                    ) {
                        NursingCompletionSheet(
                            draft = draft,
                            saving = false,
                            saveError = null,
                            timeStepMin = 1,
                            onDraftChange = {},
                            onDismiss = {},
                            onConfirm = {},
                        )
                    }
                }
            }
        }

        LeziDeviceViewports.styled().forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val page = compose.onNodeWithTag("elder_nursing_completion_page").getUnclippedBoundsInRoot()
            assertTrue("${next.name} completion collapsed: $page", page.height >= 48.dp)
            compose.onNodeWithText("确认母乳记录").assertExists()
            compose.onNodeWithText("确认记录").assertExists()
        }
    }
}
