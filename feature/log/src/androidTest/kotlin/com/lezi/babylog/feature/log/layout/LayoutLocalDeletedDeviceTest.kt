package com.lezi.babylog.feature.log.layout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.ui.encodeItemOrder
import com.lezi.babylog.core.ui.knownCatalogKeys
import com.lezi.babylog.core.ui.mergeItemOrder
import com.lezi.babylog.designsystem.LeziTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

class LayoutLocalDeletedDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val known = knownCatalogKeys(emptyList())
    private val basePrefs = DeviceLayoutPrefs(
        quickRecordSlots = listOf("pee", "sleep", "nursing", "formula"),
        hiddenItems = emptySet(),
        itemOrderJson = encodeItemOrder(mergeItemOrder("[]", known)),
        categoryOrderJson = "[]",
    )

    @Test
    fun emptyPartitionExplainsLocalOnlyRecoveryMeaning() {
        showEditor(basePrefs)

        composeRule.onNodeWithText(LayoutEditPresentation.localDeletedHeading(0)).assertIsDisplayed()
        composeRule.onNodeWithText(LayoutEditPresentation.localDeletedEmpty).assertIsDisplayed()
        composeRule.onNodeWithTag("layout_edit_local_deleted")
            .assertContentDescriptionEquals(LayoutEditPresentation.localDeletedDescription(0))
    }

    @Test
    fun allHiddenItemsScrollInsideBoundAndDockRemainsVisibleAtSmallViewport() {
        val allHidden = basePrefs.copy(
            quickRecordSlots = listOf("", "", "", ""),
            hiddenItems = known.toSet(),
        )
        showEditor(
            prefs = allHidden,
            viewportWidth = 320.dp,
            viewportHeight = 480.dp,
        )

        composeRule.onNodeWithTag("layout_edit_dock").assertIsDisplayed()
        composeRule.onNodeWithTag("layout_edit_deleted_${known.last()}")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("layout_edit_dock").assertIsDisplayed()
    }

    @Test
    fun allHiddenItemsAndDockRemainReachableAtLargeFontScale() {
        val allHidden = basePrefs.copy(
            quickRecordSlots = listOf("", "", "", ""),
            hiddenItems = known.toSet(),
        )
        showEditor(
            prefs = allHidden,
            viewportWidth = 320.dp,
            viewportHeight = 480.dp,
            fontScale = 1.5f,
        )

        composeRule.onNodeWithText(LayoutEditPresentation.localDeletedHeading(known.size))
            .assertIsDisplayed()
        composeRule.onNodeWithText(LayoutEditPresentation.localDeletedHelper).assertIsDisplayed()
        composeRule.onNodeWithTag("layout_edit_deleted_${known.last()}")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("layout_edit_dock").assertIsDisplayed()
    }

    @Test
    fun heldDropUsesDangerEmphasisAndAnnouncesLocalHideOutcome() {
        val emitted = showEditor(basePrefs)
        val source = composeRule.onNodeWithTag("layout_edit_item_bath")
            .performScrollTo()
            .assertIsDisplayed()
        val sourceBounds = source.fetchSemanticsNode().boundsInRoot
        val target = centerOf("layout_edit_local_deleted") - sourceBounds.topLeft

        source.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveTo(target)
            advanceEventTime(100L)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(LayoutEditPresentation.localDeletedHoverHelper)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("layout_edit_drag_avatar").assertIsDisplayed()

        source.performTouchInput { up() }
        composeRule.waitForIdle()
        assertEquals(
            listOf(LayoutEditIntent.MoveToLocalDeleted("bath")),
            emitted.toList(),
        )
    }

    private fun showEditor(
        prefs: DeviceLayoutPrefs,
        viewportWidth: Dp = 360.dp,
        viewportHeight: Dp = 640.dp,
        fontScale: Float = 1f,
    ): CopyOnWriteArrayList<LayoutEditIntent> {
        val emitted = CopyOnWriteArrayList<LayoutEditIntent>()
        var currentPrefs by mutableStateOf(prefs)
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                val deviceDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(deviceDensity.density, fontScale),
                ) {
                    Box(
                        Modifier
                            .size(viewportWidth, viewportHeight)
                            .clipToBounds(),
                    ) {
                        LayoutEditCanvas(
                            prefs = currentPrefs,
                            customItems = emptyList(),
                            onIntent = { intent ->
                                emitted += intent
                                currentPrefs = reduceLayoutEdit(currentPrefs, intent, known)
                            },
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

    private fun centerOf(tag: String): Offset =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center
}
