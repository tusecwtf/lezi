package com.lezi.babylog.feature.log.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.core.model.DEFAULT_QUICK_RECORD_SLOTS
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeLayoutEditPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderLayoutEditorFits720pTo4k() {
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_layout_edit_page"),
                    ) {
                        LayoutEditCanvas(
                            prefs = DeviceLayoutPrefs(
                                quickRecordSlots = DEFAULT_QUICK_RECORD_SLOTS,
                                hiddenItems = emptySet(),
                                itemOrderJson = "[]",
                                categoryOrderJson = "[]",
                            ),
                            customItems = emptyList(),
                            onIntent = {},
                            onDone = {},
                            onOpenCustomManage = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        LeziDeviceViewports.styled().forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val page = compose.onNodeWithTag("elder_layout_edit_page").getUnclippedBoundsInRoot()
            assertTrue("${next.name} layout editor collapsed: $page", page.height >= 48.dp)
            compose.onNodeWithTag("layout_edit_done").assertExists()
            compose.onNodeWithTag("layout_edit_slot_0").assertExists()
        }
    }
}
