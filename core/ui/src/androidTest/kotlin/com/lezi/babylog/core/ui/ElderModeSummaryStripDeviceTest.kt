package com.lezi.babylog.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ElderModeSummaryStripDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderSummaryStripCellsStaySeparatedAcrossConfigs() {
        var config by mutableStateOf(StripConfigs.first())
        compose.setContent {
            val current = config
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(Modifier.requiredWidth(current.width)) {
                        RecordSummaryStrip(
                            values = listOf(
                                RecordSummaryValue(RecordType.FORMULA, "180ml", "奶量"),
                                RecordSummaryValue(RecordType.SLEEP, "12h20m", "睡眠"),
                                RecordSummaryValue(RecordType.PEE, "3次", "尿尿"),
                                RecordSummaryValue(RecordType.POOP, "2次", "便便"),
                            ),
                            onSelect = {},
                        )
                    }
                }
            }
        }

        StripConfigs.forEach { next ->
            compose.runOnIdle { config = next }
            compose.waitForIdle()
            val cells = listOf("奶量", "睡眠", "尿尿", "便便").map { label ->
                compose.onNodeWithContentDescription(label, substring = true).getBoundsInRoot()
            }
            cells.forEachIndexed { i, left ->
                cells.drop(i + 1).forEach { right ->
                    val overlap =
                        left.left < right.right &&
                            left.right > right.left &&
                            left.top < right.bottom &&
                            left.bottom > right.top
                    assertTrue("${next.name} strip cells overlap: $left vs $right", !overlap)
                }
            }
            if (next.physicalWidthPx <= 1080) {
                listOf("180ml", "12h20m").forEach { text ->
                    val node = compose.onNodeWithContentDescription(text, substring = true)
                    val clipped = node.getBoundsInRoot()
                    val unclipped = node.getUnclippedBoundsInRoot()
                    assertEquals(
                        "${next.name} $text width",
                        unclipped.width.value,
                        clipped.width.value,
                        0.5f,
                    )
                }
            }
        }
    }

    private companion object {
        val StripConfigs = LeziDeviceViewports.styledWidths()
    }
}
