package com.lezi.babylog.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeRecordRowDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun l3KeepsOverlappingSleepSummaryUnclipped() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides LeziDeviceViewports.Fhd1080) {
                LeziTheme(elderMode = "l3") {
                    Box(Modifier.requiredWidth(360.dp)) {
                        RecordRow(
                            time = "15:41",
                            title = "睡眠",
                            summary = "进行中 · 妈妈 · 重叠待确认",
                            relative = "21 天前",
                            onClick = {},
                        )
                    }
                }
            }
        }

        listOf("重叠待确认", "15:41", "睡眠").forEach { label ->
            val node = compose.onNodeWithText(label, substring = true)
            node.assertIsDisplayed()
            val clipped = node.getBoundsInRoot()
            val unclipped = node.getUnclippedBoundsInRoot()
            assertEquals(label, unclipped.width.value, clipped.width.value, 0.5f)
            assertEquals(label, unclipped.height.value, clipped.height.value, 0.5f)
        }
    }

    @Test
    fun elderRecordRowKeepsRequiredCopyAcrossConfigs() {
        var config by mutableStateOf(RowConfigs.first())
        compose.setContent {
            val current = config
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(Modifier.requiredWidth(current.width)) {
                        RecordRow(
                            time = "15:41",
                            title = "睡眠",
                            summary = "进行中 · 妈妈 · 重叠待确认",
                            relative = "21 天前",
                            onClick = {},
                        )
                    }
                }
            }
        }

        RowConfigs.forEach { next ->
            compose.runOnIdle { config = next }
            compose.waitForIdle()
            listOf("重叠待确认", "15:41", "睡眠").forEach { text ->
                val node = compose.onNodeWithText(text, substring = true)
                node.assertIsDisplayed()
                if (next.width >= 360.dp) {
                    val clipped = node.getBoundsInRoot()
                    val unclipped = node.getUnclippedBoundsInRoot()
                    assertEquals(
                        "${next.name} $text width",
                        unclipped.width.value,
                        clipped.width.value,
                        0.5f,
                    )
                    assertEquals(
                        "${next.name} $text height",
                        unclipped.height.value,
                        clipped.height.value,
                        0.5f,
                    )
                }
            }
        }
    }

    private companion object {
        val RowConfigs = LeziDeviceViewports.styledWidths()
    }
}
