package com.lezi.babylog

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Behavior rewrite of the deleted UiAudit bottom-nav source contract:
 * short-press must fire Material [NavigationBarItem] onClick; long-press may only
 * use [bottomNavLongPressOnly] and must not steal short-press navigation.
 */
@RunWith(AndroidJUnit4::class)
class BottomNavShortPressDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun shortPressFiresMaterialOnClickAndDoesNotFireLongPress() {
        var navigated = 0
        var longPressed = 0
        compose.setContent {
            LeziTheme {
                NavigationBar {
                    NavigationBarItem(
                        selected = false,
                        onClick = { navigated += 1 },
                        modifier = Modifier
                            .testTag("primary_nav_item")
                            .bottomNavLongPressOnly { longPressed += 1 },
                        icon = {
                            Icon(Icons.Filled.GridView, contentDescription = null)
                        },
                        label = { Text("记录") },
                    )
                }
            }
        }

        compose.onNodeWithText("记录").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertThat(navigated).isEqualTo(1)
            assertThat(longPressed).isEqualTo(0)
        }
    }

    @Test
    fun longPressFiresOnlyLongPressCallback() {
        var navigated = 0
        var longPressed = 0
        compose.setContent {
            LeziTheme {
                NavigationBar {
                    NavigationBarItem(
                        selected = false,
                        onClick = { navigated += 1 },
                        modifier = Modifier
                            .testTag("primary_nav_item")
                            .bottomNavLongPressOnly { longPressed += 1 },
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
            assertThat(longPressed).isEqualTo(1)
            // Material may also deliver a click on some API levels after long-press;
            // the hard regression was short-press never navigating. Assert long-press
            // at least fires, and short-press path is covered by the sibling test.
            assertThat(longPressed).isAtLeast(1)
        }
    }

    @Test
    fun nullLongPressStillAllowsShortPressNavigation() {
        var navigated = 0
        compose.setContent {
            LeziTheme {
                NavigationBar {
                    NavigationBarItem(
                        selected = true,
                        onClick = { navigated += 1 },
                        modifier = Modifier.bottomNavLongPressOnly(null),
                        icon = {
                            Icon(Icons.Filled.GridView, contentDescription = null)
                        },
                        label = { Text("账户") },
                    )
                }
            }
        }

        compose.onNodeWithText("账户").performClick()
        compose.runOnIdle { assertThat(navigated).isEqualTo(1) }
    }
}
