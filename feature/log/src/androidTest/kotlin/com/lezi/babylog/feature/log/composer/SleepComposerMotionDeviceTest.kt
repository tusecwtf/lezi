package com.lezi.babylog.feature.log.composer

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziMotion
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Leaf behavior proof; the existing LeziMotionScaleDeviceTest covers the system-scale reader. */
@RunWith(AndroidJUnit4::class)
class SleepComposerMotionDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun disabledSleepAndWakeMarksStayStaticInBothTemplates() {
        var action by mutableStateOf(SleepDraftAction.SleepDown)
        var template by mutableStateOf("warm")
        var scale by mutableStateOf(0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LeziTheme(visualStyle = template) {
                Box(
                    Modifier.size(112.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .testTag(MARK_FRAME),
                    contentAlignment = Alignment.Center,
                ) {
                    SleepActionAnimation(
                        action = action,
                        animationDescription = description(action),
                        motionMillis = LeziMotion.nonEssentialMillis(LeziMotion.Fast, scale),
                    )
                }
            }
        }

        for (style in listOf("warm", "journal")) {
            for (variant in SleepDraftAction.entries) {
                for (disabledScale in listOf(0f, -1f)) {
                    compose.runOnIdle {
                        template = style
                        action = variant
                        scale = disabledScale
                    }
                    compose.mainClock.advanceTimeByFrame()
                    compose.onNodeWithContentDescription(description(variant)).assertIsDisplayed()
                    val before = snapshot()
                    compose.mainClock.advanceTimeBy(1_800L)
                    assertTrue("$style / $variant / $disabledScale moved", before.sameAs(snapshot()))
                }
            }
        }
    }

    @Test
    fun disablingAndRestoringMotionSettlesAndRestartsOnlyTheVisibleMark() {
        var action by mutableStateOf(SleepDraftAction.SleepDown)
        var scale by mutableStateOf(0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LeziTheme {
                Box(
                    Modifier.size(112.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .testTag(MARK_FRAME),
                    contentAlignment = Alignment.Center,
                ) {
                    SleepActionAnimation(
                        action = action,
                        animationDescription = description(action),
                        motionMillis = LeziMotion.nonEssentialMillis(LeziMotion.Fast, scale),
                    )
                }
            }
        }

        for (variant in SleepDraftAction.entries) {
            compose.runOnIdle {
                action = variant
                scale = 0f
            }
            compose.mainClock.advanceTimeByFrame()
            val neutral = snapshot()
            for (enabledScale in listOf(0.5f, 1f)) {
                compose.runOnIdle { scale = enabledScale }
                compose.mainClock.advanceTimeByFrame()
                val animatedBefore = snapshot()
                compose.mainClock.advanceTimeBy(500L)
                assertFalse("$variant / $enabledScale did not animate", animatedBefore.sameAs(snapshot()))

                compose.runOnIdle { scale = 0f }
                compose.mainClock.advanceTimeByFrame()
                assertTrue("$variant did not return to neutral", neutral.sameAs(snapshot()))
                compose.mainClock.advanceTimeBy(1_800L)
                assertTrue("$variant kept animating after disable", neutral.sameAs(snapshot()))
                compose.onNodeWithContentDescription(description(variant)).assertIsDisplayed()
            }
        }
    }

    @Test
    fun changingActionWhileDisablingMotionKeepsConfirmationFocusAndClick() {
        var action by mutableStateOf(SleepDraftAction.SleepDown)
        var motionMillis by mutableStateOf(LeziMotion.Fast)
        var confirmations = 0
        val confirmFocus = FocusRequester()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LeziTheme {
                Column {
                    SleepActionAnimation(
                        action = action,
                        animationDescription = description(action),
                        motionMillis = motionMillis,
                    )
                    Button(
                        onClick = { confirmations++ },
                        modifier = Modifier.focusRequester(confirmFocus).testTag(CONFIRM),
                    ) {
                        Text("确认")
                    }
                }
            }
        }
        compose.runOnIdle { confirmFocus.requestFocus() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(CONFIRM).assertIsFocused()

        for (variant in listOf(SleepDraftAction.WakeUp, SleepDraftAction.Manual)) {
            val previousDescription = description(action)
            compose.runOnIdle {
                action = variant
                motionMillis = 0
            }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithContentDescription(previousDescription).assertDoesNotExist()
            compose.onNodeWithContentDescription(description(variant)).assertIsDisplayed()
            compose.onNodeWithTag(CONFIRM).assertIsFocused().performClick()
        }
        compose.runOnIdle { assertEquals(2, confirmations) }
    }

    private fun snapshot(): Bitmap = compose.onNodeWithTag(MARK_FRAME).captureToImage().asAndroidBitmap()

    private fun description(action: SleepDraftAction): String = when (action) {
        SleepDraftAction.SleepDown -> "睡下"
        SleepDraftAction.WakeUp -> "醒来"
        SleepDraftAction.Manual -> "睡眠记录"
    }

    private companion object {
        const val MARK_FRAME = "sleep_motion_frame"
        const val CONFIRM = "sleep_motion_confirm"
    }
}
