package com.lezi.babylog

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.designsystem.LeziHaptics
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bottom long-press baby-cycle outcome haptic (0.5.4 ticket 15, spec §M3): the long-press
 * switch emits exactly one confirm haptic and only when a cycle is possible; the short-press
 * navigation path never buzzes. Gesture-split precedent: BottomNavShortPressDeviceTest.
 *
 * Not run on this machine (no device attached); compiled against the androidTest source set.
 */
@RunWith(AndroidJUnit4::class)
class BottomNavBabyCycleHapticsDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longPressWhenCyclingIsPossibleEmitsOneConfirmAndCycles() {
        val haptics = RecordingLeziHaptics()
        var cycled = 0
        compose.setContent {
            LeziTheme {
                NavigationBar {
                    NavigationBarItem(
                        selected = false,
                        onClick = { },
                        modifier = Modifier
                            .testTag("primary_nav_item")
                            .bottomNavLongPressOnly(
                                bottomNavBabyCycleClick(
                                    canCycle = true,
                                    haptics = haptics,
                                    cycleBaby = { cycled += 1 },
                                ),
                            ),
                        icon = {
                            Icon(Icons.Filled.GridView, contentDescription = null)
                        },
                        label = { Text("记录") },
                    )
                }
            }
        }

        compose.onNodeWithTag("primary_nav_item").performTouchInput { longClick() }
        compose.runOnIdle {
            assertThat(haptics.confirms).isEqualTo(1)
            assertThat(haptics.rejects).isEqualTo(0)
            assertThat(cycled).isEqualTo(1)
        }
    }

    @Test
    fun longPressWithSingleBabyStaysSilentAndDoesNotCycle() {
        val haptics = RecordingLeziHaptics()
        var cycled = 0
        compose.setContent {
            LeziTheme {
                NavigationBar {
                    NavigationBarItem(
                        selected = false,
                        onClick = { },
                        modifier = Modifier
                            .testTag("primary_nav_item")
                            .bottomNavLongPressOnly(
                                bottomNavBabyCycleClick(
                                    canCycle = false,
                                    haptics = haptics,
                                    cycleBaby = { cycled += 1 },
                                ),
                            ),
                        icon = {
                            Icon(Icons.Filled.GridView, contentDescription = null)
                        },
                        label = { Text("记录") },
                    )
                }
            }
        }

        compose.onNodeWithTag("primary_nav_item").performTouchInput { longClick() }
        compose.runOnIdle {
            assertThat(haptics.confirms).isEqualTo(0)
            assertThat(cycled).isEqualTo(0)
        }
    }

    @Test
    fun shortPressNavigationNeverEmitsAConfirmHaptic() {
        val haptics = RecordingLeziHaptics()
        var navigated = 0
        var cycled = 0
        compose.setContent {
            LeziTheme {
                NavigationBar {
                    NavigationBarItem(
                        selected = false,
                        onClick = { navigated += 1 },
                        modifier = Modifier
                            .testTag("primary_nav_item")
                            .bottomNavLongPressOnly(
                                bottomNavBabyCycleClick(
                                    canCycle = true,
                                    haptics = haptics,
                                    cycleBaby = { cycled += 1 },
                                ),
                            ),
                        icon = {
                            Icon(Icons.Filled.GridView, contentDescription = null)
                        },
                        label = { Text("记录") },
                    )
                }
            }
        }

        compose.onNodeWithText("记录").performClick()
        compose.runOnIdle {
            assertThat(navigated).isEqualTo(1)
            assertThat(haptics.confirms).isEqualTo(0)
            assertThat(cycled).isEqualTo(0)
        }
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
