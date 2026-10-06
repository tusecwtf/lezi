package com.lezi.babylog.designsystem

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Device matrix for layout contracts.
 *
 * Sizes are logical dp; [density] maps them to **physical pixels** so 360.dp is
 * 720 / 1080 / 1440 px wide — never a 360px (~300p) canvas. Extreme aspects
 * (1.4:1, 23:9) stay in the set at those real pixel classes.
 */
data class LeziDeviceViewport(
    val name: String,
    val width: Dp,
    val height: Dp,
    val density: Density,
) {
    val physicalWidthPx: Int get() = (width.value * density.density).toInt()
    val physicalHeightPx: Int get() = (height.value * density.density).toInt()
}

data class LeziStyledDeviceViewport(
    val name: String,
    val style: String,
    val elder: String,
    val width: Dp,
    val height: Dp,
    val density: Density,
) {
    val physicalWidthPx: Int get() = (width.value * density.density).toInt()
    val physicalHeightPx: Int get() = (height.value * density.density).toInt()
}

data class LeziDeviceWidth(
    val name: String,
    val width: Dp,
    val density: Density,
) {
    val physicalWidthPx: Int get() = (width.value * density.density).toInt()
}

object LeziDeviceViewports {
    val Hd720 = Density(2f)
    val Fhd1080 = Density(3f)
    val Qhd1440 = Density(4f)
    val Uhd4k = Density(4f)

    val All = listOf(
        viewport("720p 1.4:1", 360.dp, 504.dp, Hd720),
        viewport("720p 16:9", 360.dp, 640.dp, Hd720),
        viewport("720p 23:9", 360.dp, 920.dp, Hd720),
        viewport("1080p 1.4:1", 360.dp, 504.dp, Fhd1080),
        viewport("1080p 20:9", 360.dp, 800.dp, Fhd1080),
        viewport("1080p 23:9", 360.dp, 920.dp, Fhd1080),
        viewport("1440p 1.4:1", 360.dp, 504.dp, Qhd1440),
        viewport("1440p 20:9", 360.dp, 800.dp, Qhd1440),
        viewport("1440p 23:9", 360.dp, 920.dp, Qhd1440),
        viewport("4K 1.4:1", 540.dp, 756.dp, Uhd4k),
        viewport("4K 20:9", 540.dp, 1200.dp, Uhd4k),
        viewport("4K 23:9", 540.dp, 1380.dp, Uhd4k),
    )

    val Widths = listOf(
        LeziDeviceWidth("720p", 360.dp, Hd720),
        LeziDeviceWidth("1080p", 360.dp, Fhd1080),
        LeziDeviceWidth("1440p", 360.dp, Qhd1440),
        LeziDeviceWidth("4K", 540.dp, Uhd4k),
    )

    fun styled(
        styles: List<String> = listOf("warm", "journal"),
        elders: List<String> = listOf("l1", "l2", "l3"),
        viewports: List<LeziDeviceViewport> = All,
    ): List<LeziStyledDeviceViewport> = styles.flatMap { style ->
        elders.flatMap { elder ->
            viewports.map { device ->
                LeziStyledDeviceViewport(
                    name = "$style $elder ${device.name}",
                    style = style,
                    elder = elder,
                    width = device.width,
                    height = device.height,
                    density = device.density,
                )
            }
        }
    }

    fun styledWidths(
        styles: List<String> = listOf("warm", "journal"),
        elders: List<String> = listOf("l1", "l2", "l3"),
    ): List<LeziStyledDeviceViewport> = styles.flatMap { style ->
        elders.flatMap { elder ->
            Widths.map { device ->
                LeziStyledDeviceViewport(
                    name = "$style $elder ${device.name}",
                    style = style,
                    elder = elder,
                    width = device.width,
                    height = 800.dp,
                    density = device.density,
                )
            }
        }
    }

    private fun viewport(
        name: String,
        width: Dp,
        height: Dp,
        density: Density,
    ) = LeziDeviceViewport(name, width, height, density)
}
