package com.lezi.babylog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeBottomNavDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderBottomNavItemsStaySeparatedAcrossConfigs() {
        var config by mutableStateOf(NavConfigs.first())
        compose.setContent {
            val current = config
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(Modifier.requiredWidth(current.width)) {
                        NavigationBar {
                            NavItems.forEach { (label, icon) ->
                                NavigationBarItem(
                                    selected = label == "记录",
                                    onClick = {},
                                    modifier = Modifier.testTag("nav_$label"),
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
            }
        }

        NavConfigs.forEach { next ->
            compose.runOnIdle { config = next }
            compose.waitForIdle()
            val bounds = NavItems.map { (label, _) ->
                compose.onNodeWithTag("nav_$label").getBoundsInRoot()
            }
            bounds.zipWithNext { left, right ->
                val gap = right.left - left.right
                assertTrue(
                    "${next.name} bottom nav overlap: $left vs $right gap=$gap",
                    gap.value + 0.5f >= 0f,
                )
            }
        }
    }

    private companion object {
        val NavItems = listOf(
            "记录" to Icons.Filled.GridView,
            "汇总" to Icons.Filled.BarChart,
            "成长" to Icons.AutoMirrored.Filled.ShowChart,
            "账户" to Icons.Filled.Person,
            "菜单" to Icons.Filled.MoreHoriz,
        )
        val NavConfigs = LeziDeviceViewports.styledWidths()
    }
}
