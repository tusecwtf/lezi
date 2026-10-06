package com.lezi.babylog.feature.log.layout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.designsystem.LeziMotion
import com.lezi.babylog.designsystem.LeziTheme
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hosts the same [LogHomeLayoutMode] AnimatedContent contract as [com.lezi.babylog.feature.log.LogRoute].
 * The compose clock stays at scale 1 with auto-advance off so the exit transition
 * actually plays — that is the window where reading outer-null prefs used to crash.
 */
@RunWith(AndroidJUnit4::class)
class LayoutEditExitReturnsHomeDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun doneAfterExitTransitionShowsLogHomeWithoutFinishingActivity() {
        exitFromLayoutEdit {
            composeRule.onNodeWithTag("layout_edit_done").performClick()
        }
    }

    @Test
    fun systemBackAfterExitTransitionShowsLogHomeWithoutFinishingActivity() {
        exitFromLayoutEdit {
            Espresso.pressBack()
        }
    }

    @Test
    fun prefsUpdateKeepsEditBranchWithoutSwitchingToHome() {
        var presentation by mutableStateOf<RetainedLayoutEditPresentation?>(
            samplePresentation(firstSlot = "pee"),
        )
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                LayoutEditExitHost(
                    presentation = presentation,
                    onPresentationChange = { presentation = it },
                )
            }
        }
        composeRule.onNodeWithTag("layout_edit_done").assertIsDisplayed()
        composeRule.onNodeWithText("pee").assertIsDisplayed()

        composeRule.runOnIdle {
            val current = requireNotNull(presentation)
            presentation = current.copy(
                session = current.session.copy(
                    prefs = current.session.prefs.copy(
                        quickRecordSlots = listOf("sleep", "", "", ""),
                    ),
                ),
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("layout_edit_done").assertIsDisplayed()
        composeRule.onNodeWithText("sleep").assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.LOG_HOME).assertDoesNotExist()
        assertFalse(composeRule.activity.isFinishing)
    }

    private fun exitFromLayoutEdit(trigger: () -> Unit) {
        composeRule.mainClock.autoAdvance = false
        var presentation by mutableStateOf<RetainedLayoutEditPresentation?>(
            samplePresentation(),
        )
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                LayoutEditExitHost(
                    presentation = presentation,
                    onPresentationChange = { presentation = it },
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("layout_edit_done").assertExists()

        trigger()

        // Outgoing edit content still recomposes here while outer presentation is null.
        // The host must keep drawing the retained slot; a revert to outer-null prefs
        // would drop this node or crash before the next assert.
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(LeziMotion.Emphasized.toLong() / 2)
        composeRule.onNodeWithText("pee").assertExists()
        composeRule.onNodeWithTag("layout_edit_done").assertExists()
        assertFalse(composeRule.activity.isFinishing)

        composeRule.mainClock.advanceTimeBy(LeziMotion.Emphasized.toLong())
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(UiTags.LOG_HOME).assertIsDisplayed()
        assertFalse(composeRule.activity.isFinishing)
        assertFalse(composeRule.activity.isDestroyed)
    }
}

@Composable
private fun LayoutEditExitHost(
    presentation: RetainedLayoutEditPresentation?,
    onPresentationChange: (RetainedLayoutEditPresentation?) -> Unit,
    transitionMillis: Int = LeziMotion.Emphasized,
) {
    LogHomeLayoutMode(
        layoutPresentation = presentation,
        transitionMillis = transitionMillis,
        onRequestExit = { onPresentationChange(null) },
        modifier = Modifier.fillMaxSize(),
    ) { presented ->
        if (presented != null) {
            val prefs = presented.session.prefs
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("layout_edit_done")
                    .clickable { onPresentationChange(null) },
            ) {
                Text(text = prefs.quickRecordSlots.first())
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(UiTags.LOG_HOME),
            ) {
                Text("日常记录")
            }
        }
    }
}

private fun samplePresentation(
    firstSlot: String = "pee",
): RetainedLayoutEditPresentation {
    val prefs = DeviceLayoutPrefs(
        quickRecordSlots = listOf(firstSlot, "", "", ""),
        hiddenItems = emptySet(),
        itemOrderJson = "[]",
        categoryOrderJson = "[]",
    )
    return RetainedLayoutEditPresentation(
        session = LayoutEditSession(
            context = LayoutEditSessionContext(
                babyId = 1L,
                day = LocalDate.of(2026, 9, 13),
            ),
            prefs = prefs,
            dragGuidance = initialLayoutDragGuidanceState(completed = true),
        ),
        writeState = DeviceLayoutWriteState.Saved(),
    )
}
