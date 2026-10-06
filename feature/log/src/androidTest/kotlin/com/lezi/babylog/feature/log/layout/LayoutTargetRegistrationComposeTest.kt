package com.lezi.babylog.feature.log.layout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import com.lezi.babylog.core.ui.RecordSection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

class LayoutTargetRegistrationComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun nodeRemovalAndRecompositionMoveImmediatelyReplaceVisibleBounds() {
        val registry = LayoutVisibleTargetRegistry()
        val node = LayoutTargetNode.CatalogItem(
            catalogKey = "formula",
            section = RecordSection.Feeding,
            toIndex = 1,
        )
        val show = mutableStateOf(true)
        val xOffset = mutableStateOf(0.dp)

        composeRule.setContent {
            Box(Modifier.size(300.dp)) {
                if (show.value) {
                    Box(
                        Modifier
                            .offset(x = xOffset.value)
                            .size(40.dp)
                            .layoutTargetRegistration(
                                node = node,
                                registry = registry,
                                onRegistryChanged = {},
                            ),
                    )
                }
            }
        }

        lateinit var firstBounds: androidx.compose.ui.geometry.Rect
        composeRule.runOnIdle {
            firstBounds = registry.snapshot().catalogItemBounds.getValue("formula").bounds
            xOffset.value = 100.dp
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val movedBounds = registry.snapshot().catalogItemBounds.getValue("formula").bounds
            assertTrue(movedBounds.left > firstBounds.left)
            assertFalse(movedBounds.contains(firstBounds.center))
            show.value = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertFalse(registry.snapshot().catalogItemBounds.containsKey("formula"))
        }
    }
}
