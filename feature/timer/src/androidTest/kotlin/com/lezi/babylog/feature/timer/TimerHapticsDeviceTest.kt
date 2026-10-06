package com.lezi.babylog.feature.timer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziHaptics
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.LocalLeziHaptics
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Timer outcome haptics (0.5.4 ticket 15, spec §M3): each committed start/pause flip of a
 * running flag emits exactly one confirm haptic (first observed state and steady ticks stay
 * silent), and the domain-committed completion emits one confirm with the saved snackbar.
 * The haptic rides the observable state transition — a click that does not commit never buzzes.
 *
 * Not run on this machine (no device attached); compiled against the androidTest source set.
 */
@RunWith(AndroidJUnit4::class)
class TimerHapticsDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun eachCommittedStartPauseFlipEmitsExactlyOneConfirmHaptic() {
        val haptics = RecordingLeziHaptics()
        var toggles = 0
        var state by mutableStateOf(TimerState())
        composeRule.setContent {
            CompositionLocalProvider(LocalLeziHaptics provides haptics) {
                LeziTheme {
                    TimerSessionPane(
                        state = state,
                        leftMs = 0L,
                        rightMs = 0L,
                        viewportMode = TimerViewportMode.Spacious,
                        actionsEnabled = true,
                        onToggleLeft = { toggles += 1 },
                        onToggleRight = { toggles += 1 },
                        onComplete = {},
                        onDiscard = {},
                        onRetryService = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // First observed state is silent.
        assertEquals(0, haptics.confirms)

        composeRule.runOnIdle { state = state.copy(leftRunning = true) }
        composeRule.waitForIdle()
        assertEquals(1, haptics.confirms)

        composeRule.runOnIdle { state = state.copy(leftRunning = false) }
        composeRule.waitForIdle()
        assertEquals(2, haptics.confirms)

        // Same-flag recomposition (accumulated time) must stay silent — never per-tick.
        composeRule.runOnIdle { state = state.copy(leftAccumMs = 5_000L) }
        composeRule.waitForIdle()
        assertEquals(2, haptics.confirms)
        assertEquals(0, haptics.rejects)
        assertEquals(0, toggles)
    }

    @Test
    fun sideButtonClickWithoutACommittedFlipStaysSilent() {
        val haptics = RecordingLeziHaptics()
        var toggles = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLeziHaptics provides haptics) {
                LeziTheme {
                    TimerSessionPane(
                        state = TimerState(),
                        leftMs = 0L,
                        rightMs = 0L,
                        viewportMode = TimerViewportMode.Spacious,
                        actionsEnabled = true,
                        onToggleLeft = { toggles += 1 },
                        onToggleRight = { toggles += 1 },
                        onComplete = {},
                        onDiscard = {},
                        onRetryService = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("左").performClick()
        composeRule.waitForIdle()

        // The test callback does not flip the running flags (a rejected toggle) — no buzz.
        assertEquals(1, toggles)
        assertEquals(0, haptics.confirms)
    }

    @Test
    fun completionSaveCommitEmitsOneConfirmHaptic() {
        val haptics = RecordingLeziHaptics()
        val snackbarHostState = SnackbarHostState()
        var clearPending by mutableStateOf(false)
        composeRule.setContent {
            CompositionLocalProvider(LocalLeziHaptics provides haptics) {
                LeziTheme {
                    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                        Box(Modifier.padding(padding))
                    }
                    TimerCompletionSavedFeedback(
                        timerClearPending = clearPending,
                        snackbarHostState = snackbarHostState,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, haptics.confirms)

        composeRule.runOnIdle { clearPending = true }
        composeRule.waitForIdle()

        assertEquals(1, haptics.confirms)
        assertEquals(0, haptics.rejects)
    }

    private class RecordingLeziHaptics : LeziHaptics {
        var confirms = 0
        var rejects = 0

        override fun confirm() {
            confirms += 1
        }

        override fun reject() {
            rejects += 1
        }
    }
}
