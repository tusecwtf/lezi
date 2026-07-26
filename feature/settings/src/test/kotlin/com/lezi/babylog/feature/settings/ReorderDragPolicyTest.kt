package com.lezi.babylog.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ReorderDragPolicyTest {
    @Test
    fun dragReleaseHitsTheNearestMeasuredTargetCenter() {
        val order = listOf("尿尿", "睡眠", "母乳", "配方奶")
        val centers = mapOf(
            "尿尿" to 40f,
            "睡眠" to 160f,
            "母乳" to 420f,
            "配方奶" to 620f,
        )
        assertEquals(
            0,
            dropTargetDelta(
                orderedKeys = order,
                sourceKey = "睡眠",
                dragDistancePx = 120f,
                targetCentersPx = centers,
            ),
        )
        assertEquals(
            1,
            dropTargetDelta(
                orderedKeys = order,
                sourceKey = "睡眠",
                dragDistancePx = 140f,
                targetCentersPx = centers,
            ),
        )
        assertEquals(
            2,
            dropTargetDelta(
                orderedKeys = order,
                sourceKey = "睡眠",
                dragDistancePx = 450f,
                targetCentersPx = centers,
            ),
        )
        assertEquals(
            -2,
            dropTargetDelta(order, "母乳", -400f, centers),
        )
    }

    @Test
    fun invalidOrUnmeasuredDragGeometryIsAStableNoOp() {
        val order = listOf("尿尿", "睡眠")
        val centers = mapOf("尿尿" to 40f, "睡眠" to 160f)
        assertEquals(0, dropTargetDelta(order, "尿尿", Float.NaN, centers, 48f))
        assertEquals(0, dropTargetDelta(order, "未知", 100f, centers, 48f))
        assertEquals(0, dropTargetDelta(order, "尿尿", 100f, emptyMap(), 48f))
    }

    @Test
    fun viewportEdgeDragMovesOnlyOneUnmeasuredAdjacentTarget() {
        val order = listOf("进食", "排泄", "睡眠", "健康")
        val onlyVisibleHeader = mapOf("排泄" to 200f)

        assertEquals(
            1,
            dropTargetDelta(order, "排泄", 80f, onlyVisibleHeader, 48f),
        )
        assertEquals(
            -1,
            dropTargetDelta(order, "排泄", -80f, onlyVisibleHeader, 48f),
        )
        assertEquals(
            0,
            dropTargetDelta(order, "排泄", 40f, onlyVisibleHeader, 48f),
        )
        assertEquals(
            0,
            dropTargetDelta(order, "健康", 80f, mapOf("健康" to 200f), 48f),
        )
    }

    @Test
    fun movingAQuickSlotShiftsEveryIntermediateSlot() {
        assertEquals(
            listOf("睡眠", "母乳", "配方奶", "尿尿"),
            moveItemBy(
                items = listOf("尿尿", "睡眠", "母乳", "配方奶"),
                fromIndex = 0,
                delta = 3,
            ),
        )
        assertEquals(
            listOf("配方奶", "尿尿", "睡眠", "母乳"),
            moveItemBy(
                items = listOf("尿尿", "睡眠", "母乳", "配方奶"),
                fromIndex = 3,
                delta = -3,
            ),
        )
    }

    @Test
    fun invalidListMovePreservesOrder() {
        val slots = listOf("尿尿", "睡眠", "母乳", "配方奶")
        assertEquals(slots, moveItemBy(slots, fromIndex = -1, delta = 1))
        assertEquals(slots, moveItemBy(slots, fromIndex = 2, delta = 0))
        assertEquals(slots, moveItemBy(slots, fromIndex = 4, delta = -1))
    }
}
