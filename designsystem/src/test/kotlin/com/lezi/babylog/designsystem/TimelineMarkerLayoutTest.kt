package com.lezi.babylog.designsystem

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
        val milk = eventSegment(
            minute = 700,
            category = "MILK",
            colorRole = LeziRecordColorRole.Milk,
        )
        val peeRed = eventSegment(
            minute = 705,
            category = "PEE",
            title = "same",
            detail = "same",
            colorRole = LeziRecordColorRole.Nursing,
        )
        val peeBlue = eventSegment(
            minute = 705,
            category = "PEE",
            title = "same",
            detail = "same",
            colorRole = LeziRecordColorRole.Pee,
        )
        val poop = eventSegment(
            minute = 711,
            category = "POOP",
            colorRole = LeziRecordColorRole.Poop,
        )

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
            first.targets.associate { it.segment.colorRole to (it.centerPx to it.zOrder) },
            reorderedAndRecopied.targets.associate {
                it.segment.colorRole to (it.centerPx to it.zOrder)
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
            colorRole = LeziRecordColorRole.Sleep,
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

    @Test
    fun `nextCategorySelection toggles and switches shared day-chart keys`() {
        val pee = eventSegment(minute = 400, category = "PEE")
        val milk = eventSegment(minute = 500, category = "MILK")
        val nonselectable = eventSegment(minute = 600, category = null)

        assertEquals("PEE", nextCategorySelection(null, pee))
        assertNull(nextCategorySelection("PEE", pee))
        assertEquals("MILK", nextCategorySelection("PEE", milk))
        assertNull(nextCategorySelection("PEE", null))
        assertNull(nextCategorySelection("PEE", nonselectable))
        assertNull(nextCategorySelection(null, nonselectable))
    }

    @Test
    fun `nextCategorySelection on neighbor-day mark still emits category key for A2 gate`() {
        // Hit testing is content-axis agnostic: a D−1 mark yields the same key as D.
        // Feature-layer A2 decides whether to commit (must be present on day D).
        val d0 = TimelineAxis.PRIMARY_DAY_START_MINUTES
        val neighborPee = eventSegment(minute = d0 - 6 * 60, category = "PEE")
        val primaryPee = eventSegment(minute = d0 + 12 * 60, category = "PEE")
        val neighborPlusMilk = eventSegment(
            minute = d0 + TimelineAxis.MINUTES_PER_DAY + 3 * 60,
            category = "MILK",
        )

        assertEquals("PEE", nextCategorySelection(null, neighborPee))
        assertEquals("PEE", nextCategorySelection(null, primaryPee))
        assertEquals("MILK", nextCategorySelection("PEE", neighborPlusMilk))
        // Re-tap same type from a neighbor mark still clears at designsystem layer.
        assertNull(nextCategorySelection("PEE", neighborPee))
    }

    @Test
    fun `viewport mapping places primary-day markers with neighbor peeks`() {
        val d0 = TimelineAxis.PRIMARY_DAY_START_MINUTES
        val viewportStart = TimelineAxis.defaultViewportStartMinutes()
        val viewportDuration = TimelineAxis.defaultViewportDurationMinutes()
        val noon = eventSegment(minute = d0 + 12 * 60, category = "MILK")
        val axisPx = viewportDuration.toFloat()

        val layout = layoutTimelineEventMarkers(
            segments = listOf(noon),
            axisLengthPx = axisPx,
            clusterWindowMinutes = 12,
            slotSpacingPx = 10f,
            edgeInsetPx = 20f,
            hitRadiusPx = 8f,
            viewportStartMinutes = viewportStart,
            viewportDurationMinutes = viewportDuration,
        )

        // Noon of D is 12h after primary start → offset from viewport left =
        // peek + 12h minutes, 1px per content minute.
        val expected =
            (TimelineAxis.NEIGHBOR_PEEK_MINUTES + 12 * 60).toFloat()
        assertEquals(expected, layout.targets.single().centerPx, 0.001f)
        assertEquals("MILK", layout.hitTest(expected)?.dayChartCategoryKey)
    }

    @Test
    fun `dst viewport draws and hits a marker at the same dynamic coordinate`() {
        // New York spring-forward window: primary D ends at minute 2820, not 2880.
        val viewportStart = 1_350
        val viewportDuration = 1_560
        val markerMinute = 2_820
        val layout = layoutTimelineEventMarkers(
            segments = listOf(eventSegment(minute = markerMinute, category = "PEE")),
            axisLengthPx = viewportDuration.toFloat(),
            clusterWindowMinutes = 12,
            slotSpacingPx = 10f,
            edgeInsetPx = 20f,
            hitRadiusPx = 8f,
            viewportStartMinutes = viewportStart,
            viewportDurationMinutes = viewportDuration,
        )

        val expectedPx = (markerMinute - viewportStart).toFloat()
        assertEquals(expectedPx, layout.targets.single().centerPx, 0.001f)
        assertEquals("PEE", layout.hitTest(expectedPx)?.dayChartCategoryKey)
    }

    @Test
    fun `events far outside the viewport are not laid out`() {
        val viewportStart = TimelineAxis.defaultViewportStartMinutes()
        val viewportDuration = TimelineAxis.defaultViewportDurationMinutes()
        val far = eventSegment(minute = 30, category = "PEE") // deep in D−1

        val layout = layoutTimelineEventMarkers(
            segments = listOf(far),
            axisLengthPx = 1000f,
            clusterWindowMinutes = 12,
            slotSpacingPx = 10f,
            edgeInsetPx = 20f,
            hitRadiusPx = 8f,
            viewportStartMinutes = viewportStart,
            viewportDurationMinutes = viewportDuration,
        )

        assertTrue(layout.targets.isEmpty())
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
        colorRole: LeziRecordColorRole = LeziRecordColorRole.Care,
    ) = TimelineLaneSegment(
        startMinOfDay = minute,
        endMinOfDay = minute,
        colorRole = colorRole,
        title = title,
        detail = detail,
        isEvent = true,
        dayChartCategoryKey = category,
    )
}
