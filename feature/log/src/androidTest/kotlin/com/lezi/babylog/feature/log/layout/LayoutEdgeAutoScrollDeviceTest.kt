package com.lezi.babylog.feature.log.layout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.ui.encodeItemOrder
import com.lezi.babylog.core.ui.knownCatalogKeys
import com.lezi.babylog.core.ui.mergeItemOrder
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.domain.CustomRecordItem
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

class LayoutEdgeAutoScrollDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val customItems = (0 until 24).map { index ->
        CustomRecordItem(
            id = 1_000L + index,
            name = "自定义${index + 1}",
            iconSlot = index % 8,
            sortOrder = index,
            clientUuid = "00000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}",
        )
    }
    private val customKeys = customItems.map {
        RecordItemIdentity.custom(it.id, it.clientUuid).catalogKey
    }
    private val known = knownCatalogKeys(customItems.map { it.clientUuid })
    private val prefs = DeviceLayoutPrefs(
        quickRecordSlots = listOf("", "", "", ""),
        hiddenItems = known.toSet() - customKeys.toSet(),
        itemOrderJson = encodeItemOrder(mergeItemOrder("[]", known)),
        categoryOrderJson = "[]",
    )

    @Test
    fun catalogItemHeldAtBottomEdgeRevealsTailAndCancelStopsScrolling() {
        val emitted = showEditor()
        val sourceKey = customKeys.first()
        val targetKey = customKeys.last()
        val source = composeRule.onNodeWithTag("layout_edit_item_$sourceKey")
            .assertIsDisplayed()
        val target = composeRule.onNodeWithTag("layout_edit_item_$targetKey")
        assertFalse(target.isDisplayed())
        val root = composeRule.onRoot()

        val sourceBounds = source.fetchSemanticsNode().boundsInRoot
        val bottomEdge = composeRule.onNodeWithTag("layout_edit_local_deleted")
            .fetchSemanticsNode().boundsInRoot.top - 1f
        val edgeHoldPoint = Offset(
            x = sourceBounds.center.x,
            y = sourceBounds.bottom - 4f,
        )
        assertTrue(edgeHoldPoint.y <= bottomEdge)
        root.performTouchInput {
            down(edgeHoldPoint)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
        }

        val reachedTail = advanceUntilTargetCenterIsAbove(target, bottomEdge)
        assertTrue(
            "sourceBefore=$sourceBounds sourceAfter=${source.fetchSemanticsNode().boundsInRoot} " +
                "target=${target.fetchSemanticsNode().boundsInRoot} bottomEdge=$bottomEdge",
            reachedTail,
        )
        val stoppedTargetBounds = target.fetchSemanticsNode().boundsInRoot
        root.performTouchInput {
            cancel()
        }
        composeRule.mainClock.advanceTimeBy(500L)

        assertEquals(
            stoppedTargetBounds.center.y.toDouble(),
            target.fetchSemanticsNode().boundsInRoot.center.y.toDouble(),
            2.0,
        )
        assertTrue(emitted.isEmpty())
    }

    private fun showEditor(): CopyOnWriteArrayList<LayoutEditIntent> {
        val emitted = CopyOnWriteArrayList<LayoutEditIntent>()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                LeziTheme(visualStyle = "warm") {
                    Box(
                        Modifier
                            .size(width = 320.dp, height = 640.dp)
                            .clipToBounds(),
                    ) {
                        LayoutEditCanvas(
                            prefs = prefs,
                            customItems = customItems,
                            onIntent = emitted::add,
                            onDone = {},
                            onOpenCustomManage = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return emitted
    }

    private fun advanceUntilTargetCenterIsAbove(
        node: SemanticsNodeInteraction,
        maxCenterY: Float,
        timeoutMillis: Long = 5_000L,
    ): Boolean {
        var elapsedMillis = 0L
        fun targetIsReady(): Boolean = node.isDisplayed() &&
            node.fetchSemanticsNode().boundsInRoot.center.y < maxCenterY

        while (!targetIsReady() && elapsedMillis < timeoutMillis) {
            composeRule.mainClock.advanceTimeBy(50L)
            elapsedMillis += 50L
        }
        return targetIsReady()
    }
}
