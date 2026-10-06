package com.lezi.babylog.feature.log.dock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ElderModeDockStructureDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun l3PlusMaxFontScaleDockSlotsAreAtLeastEightyFourAndUnclipped() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides LeziDeviceViewports.Fhd1080) {
                LeziTheme(visualStyle = "warm", elderMode = "l3") {
                    OneHandQuickDock(
                        storedSlots = listOf("pee", "sleep", "nursing", "formula"),
                        hiddenTypeKeys = emptySet(),
                        customItems = emptyList(),
                        sleepRunning = false,
                        onBound = {},
                        onEmpty = {},
                        onMore = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("one_hand_quick_dock_fixed")
            .assertHeightIsAtLeast(84.dp)
        val node = compose.onNodeWithTag("one_hand_quick_dock_fixed")
        val clipped = node.getBoundsInRoot()
        val unclipped = node.getUnclippedBoundsInRoot()
        assertEquals(unclipped.width.value, clipped.width.value, 0.5f)
        assertEquals(unclipped.height.value, clipped.height.value, 0.5f)

        compose.onNodeWithTag("one_hand_action_more")
            .assertHeightIsAtLeast(84.dp)
    }

    @Test
    fun elderDockCellsStaySeparatedAcrossConfigs() {
        var config by mutableStateOf(DockConfigs.first())
        compose.setContent {
            val current = config
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(Modifier.requiredWidth(current.width)) {
                        OneHandQuickDock(
                            storedSlots = listOf("pee", "sleep", "nursing", "formula"),
                            hiddenTypeKeys = emptySet(),
                            customItems = emptyList(),
                            sleepRunning = false,
                            onBound = {},
                            onEmpty = {},
                            onMore = {},
                        )
                    }
                }
            }
        }

        DockConfigs.forEach { next ->
            compose.runOnIdle { config = next }
            compose.waitForIdle()
            val cells = DockCellTags.map { tag ->
                compose.onNodeWithTag(tag).getBoundsInRoot()
            }
            cells.zipWithNext { left, right ->
                assertMinGap("${next.name} dock cells", left, right)
            }
            compose.onNodeWithTag("one_hand_quick_dock_fixed")
                .assertHeightIsAtLeast(84.dp)
        }
    }

    private fun assertMinGap(label: String, left: DpRect, right: DpRect, minGap: Dp = 8.dp) {
        val gap = right.left - left.right
        assertTrue(
            "$label: expected ≥$minGap, was $gap (left=$left right=$right)",
            gap.value + 0.5f >= minGap.value,
        )
    }

    private companion object {
        val DockCellTags = listOf(
            "one_hand_action_pee",
            "one_hand_action_sleep",
            "one_hand_action_nursing",
            "one_hand_action_formula",
            "one_hand_action_more",
        )
        val DockConfigs = LeziDeviceViewports.styledWidths()
    }
}
