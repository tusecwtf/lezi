package com.lezi.babylog.designsystem

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ElderModeDensityTest {
    @Test
    fun offReusesTheParentDensityInstance() {
        val parent = Density(2.5f, 1.3f)
        assertSame(parent, stackedElderDensity(parent, "off"))
        assertSame(parent, stackedElderDensity(parent, "huge"))
    }

    @Test
    fun l2ConvertsTitle20ToThirtySixDpAtSystemScaleOne() {
        val density = stackedElderDensity(Density(1f, 1f), "l2")
        assertEquals(1.8f, density.fontScale)
        assertEquals(36.dp, with(density) { 20.sp.toDp() })
    }

    @Test
    fun elderTiersIgnoreParentFontScale() {
        val density = stackedElderDensity(Density(1f, 1.3f), "l2")
        assertEquals(1.8f, density.fontScale, 0.0001f)
        assertEquals(36.dp, with(density) { 20.sp.toDp() })
    }
}
