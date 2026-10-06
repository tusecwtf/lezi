package com.lezi.babylog.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeziDeviceViewportsTest {
    @Test
    fun physicalWidthsCover720pTo4kAndNeverDropTo300p() {
        val widths = LeziDeviceViewports.All.map { it.physicalWidthPx }
        assertTrue("min physical width ${widths.minOrNull()} < 720", widths.min() >= 720)
        assertTrue("max physical width ${widths.maxOrNull()} < 2160", widths.max() >= 2160)
        LeziDeviceViewports.Widths.forEach { width ->
            assertTrue(
                "${width.name} physical width ${width.physicalWidthPx} < 720",
                width.physicalWidthPx >= 720,
            )
        }
        val heights = LeziDeviceViewports.All.map { it.physicalHeightPx }
        assertTrue("min physical height ${heights.minOrNull()} < 720", heights.min() >= 720)
    }

    @Test
    fun eachPortraitClassKeepsExtremeAspectsAtRealPixels() {
        assertPixels("720p 1.4:1", 720, 1008)
        assertPixels("720p 16:9", 720, 1280)
        assertPixels("720p 23:9", 720, 1840)
        assertPixels("1080p 1.4:1", 1080, 1512)
        assertPixels("1080p 20:9", 1080, 2400)
        assertPixels("1080p 23:9", 1080, 2760)
        assertPixels("1440p 1.4:1", 1440, 2016)
        assertPixels("1440p 20:9", 1440, 3200)
        assertPixels("1440p 23:9", 1440, 3680)
        assertPixels("4K 1.4:1", 2160, 3024)
        assertPixels("4K 20:9", 2160, 4800)
        assertPixels("4K 23:9", 2160, 5520)
        assertTrue(
            "portrait lock dropped a landscape viewport",
            LeziDeviceViewports.All.none { it.physicalWidthPx > it.physicalHeightPx },
        )
    }

    private fun assertPixels(name: String, widthPx: Int, heightPx: Int) {
        val viewport = LeziDeviceViewports.All.single { it.name == name }
        assertEquals("$name width", widthPx, viewport.physicalWidthPx)
        assertEquals("$name height", heightPx, viewport.physicalHeightPx)
    }
}
