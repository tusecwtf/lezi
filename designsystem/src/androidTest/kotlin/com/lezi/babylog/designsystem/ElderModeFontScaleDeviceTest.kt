package com.lezi.babylog.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ElderModeFontScaleDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun offKeepsSystemDensityAndFontScale() {
        var observedDensity = 0f
        var observedScale = 0f
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2.5f, 1.3f)) {
                LeziTheme(elderMode = "off") {
                    observedDensity = LocalDensity.current.density
                    observedScale = LocalDensity.current.fontScale
                }
            }
        }
        compose.runOnIdle {
            assertEquals(2.5f, observedDensity)
            assertEquals(1.3f, observedScale)
        }
    }

    @Test
    fun tiersReplaceTheInjectedSystemFontScale() {
        val observed = mutableMapOf<String, Float>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LeziTheme(elderMode = "l1") {
                    observed["l1"] = LocalDensity.current.fontScale
                }
                LeziTheme(elderMode = "l2") {
                    observed["l2"] = LocalDensity.current.fontScale
                }
                LeziTheme(elderMode = "l3") {
                    observed["l3"] = LocalDensity.current.fontScale
                }
            }
        }
        compose.runOnIdle {
            assertEquals(1.5f, observed.getValue("l1"))
            assertEquals(1.8f, observed.getValue("l2"))
            assertEquals(2.1f, observed.getValue("l3"))
        }
    }

    @Test
    fun l2RendersTitleTwentyAtThirtySixDpWhenSystemScaleIsOne() {
        var titleDp = 0f
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                LeziTheme(elderMode = "l2") {
                    titleDp = with(LocalDensity.current) { 20.sp.toDp() }.value
                }
            }
        }
        compose.runOnIdle {
            assertEquals(36f, titleDp)
        }
    }
}
