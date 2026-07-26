package com.lezi.babylog.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineMarkerLayoutTest {
    @Test
    fun `identical timestamp markers have distinct hittable centers`() {
        val pee = eventSegment(minute = 720, category = "PEE")
        val poop = eventSegment(minute = 720, category = "POOP")

        val layout = layoutTimelineEventMarkers(
            segments = listOf(poop, pee),
            axisLengthPx = 1440f,
            clusterWindowMinutes = 12,
            slotSpacingPx = 10f,
            edgeInsetPx = 20f,
            hitRadiusPx = 8f,
        )

        val targetsByCategory = layout.targets.associateBy {
            it.segment.dayChartCategoryKey
        }
        assertEquals(715f, targetsByCategory.getValue("PEE").centerPx, 0.001f)
        assertEquals(725f, targetsByCategory.getValue("POOP").centerPx, 0.001f)
        assertEquals("PEE", layout.hitTest(715f)?.dayChartCategoryKey)
        assertEquals("POOP", layout.hitTest(725f)?.dayChartCategoryKey)
        assertEquals("POOP", layout.hitTest(720f)?.dayChartCategoryKey)
    }

    @Test
    fun `edge clusters clamp as a group without collapsing hit centers`() {
        val startLayout = layoutTimelineEventMarkers(
            segments = listOf(
                eventSegment(minute = 0, category = "PEE"),
                eventSegment(minute = 0, category = "POOP"),
            ),
            axisLengthPx = 100f,
            clusterWindowMinutes = 12,
            slotSpacingPx = 8f,
            edgeInsetPx = 10f,
            hitRadiusPx = 6f,
        )
        val endLayout = layoutTimelineEventMarkers(
            segments = listOf(
                eventSegment(minute = 1440, category = "PEE"),
                eventSegment(minute = 1440, category = "POOP"),
            ),
            axisLengthPx = 100f,
            clusterWindowMinutes = 12,
            slotSpacingPx = 8f,
            edgeInsetPx = 10f,
            hitRadiusPx = 6f,
        )

        assertEquals(listOf(10f, 18f), startLayout.targets.map { it.centerPx })
        assertEquals(listOf(82f, 90f), endLayout.targets.map { it.centerPx })
        assertEquals("PEE", startLayout.hitTest(10f)?.dayChartCategoryKey)
        assertEquals("POOP", startLayout.hitTest(18f)?.dayChartCategoryKey)
        assertEquals("PEE", endLayout.hitTest(82f)?.dayChartCategoryKey)
        assertEquals("POOP", endLayout.hitTest(90f)?.dayChartCategoryKey)
    }

    @Test
    fun `near cluster order spacing and tie break ignore traversal and copy`() {
        val milk = eventSegment(minute = 700, category = "MILK", color = Color.Green)
        val peeRed = eventSegment(
            minute = 705,
            category = "PEE",
            title = "same",
            detail = "same",
            color = Color.Red,
        )
        val peeBlue = eventSegment(
            minute = 705,
            category = "PEE",
            title = "same",
            detail = "same",
            color = Color.Blue,
        )
        val poop = eventSegment(minute = 711, category = "POOP", color = Color.Yellow)

        val first = layout(
            listOf(peeRed, poop, milk, peeBlue),
            slotSpacingPx = 6f,
        )
        val reorderedAndRecopied = layout(
            listOf(
                peeBlue.copy(title = "changed", detail = "changed"),
                milk,
                poop,
                peeRed.copy(title = "also changed", detail = "also changed"),
            ),
            slotSpacingPx = 6f,
        )

        assertEquals(
            first.targets.associate { it.segment.color.value to (it.centerPx to it.zOrder) },
            reorderedAndRecopied.targets.associate {
                it.segment.color.value to (it.centerPx to it.zOrder)
            },
        )
        assertEquals(listOf(700, 705, 705, 711), first.targets.map { it.segment.startMinOfDay })
        assertTrue(
            first.targets.zipWithNext().all { (left, right) ->
                right.centerPx - left.centerPx == 6f
            },
        )
    }

    @Test
    fun `dense edge cluster compresses safely inside a short axis`() {
        val layout = layoutTimelineEventMarkers(
            segments = listOf("A", "B", "C", "D").map { category ->
                eventSegment(minute = 0, category = category)
            },
            axisLengthPx = 30f,
            clusterWindowMinutes = 12,
            slotSpacingPx = 8f,
            edgeInsetPx = 10f,
            hitRadiusPx = 4f,
        )

        assertEquals(10f, layout.targets.first().centerPx, 0.001f)
        assertEquals(20f, layout.targets.last().centerPx, 0.001f)
        assertTrue(
            layout.targets.zipWithNext().all { (left, right) ->
                right.centerPx > left.centerPx
            },
        )
    }

    @Test
    fun `blank and nonselectable halo clear while intervals stay outside marker layout`() {
        val selectable = eventSegment(minute = 100, category = "PEE")
        val nonselectable = eventSegment(minute = 130, category = null)
        val interval = TimelineLaneSegment(
            startMinOfDay = 150,
            endMinOfDay = 200,
            color = Color.Magenta,
            isEvent = false,
            dayChartCategoryKey = "SLEEP",
        )
        val layout = layoutTimelineEventMarkers(
            segments = listOf(interval, nonselectable, selectable),
            axisLengthPx = 1440f,
            clusterWindowMinutes = 12,
            slotSpacingPx = 10f,
            edgeInsetPx = 20f,
            hitRadiusPx = 8f,
        )

        assertEquals(2, layout.targets.size)
        assertNull(layout.hitTest(115f))
        assertNull(nextCategorySelection("PEE", layout.hitTest(115f)))
        assertNull(nextCategorySelection("PEE", layout.hitTest(130f)))
    }

    private fun layout(
        segments: List<TimelineLaneSegment>,
        slotSpacingPx: Float = 10f,
    ) = layoutTimelineEventMarkers(
        segments = segments,
        axisLengthPx = 1440f,
        clusterWindowMinutes = 12,
        slotSpacingPx = slotSpacingPx,
        edgeInsetPx = 20f,
        hitRadiusPx = 8f,
    )

    private fun eventSegment(
        minute: Int,
        category: String?,
        title: String = "",
        detail: String = "",
        color: Color = Color.Unspecified,
    ) = TimelineLaneSegment(
        startMinOfDay = minute,
        endMinOfDay = minute,
        color = color,
        title = title,
        detail = detail,
        isEvent = true,
        dayChartCategoryKey = category,
    )
}
