package com.lezi.babylog.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adapter/settings copy contract for graded disclosure (no Compose runtime).
 */
class SystemCalendarDisclosureUiTest {
    @Test
    fun disclosureLabelsMatchProductGrades() {
        assertEquals("仅乐记事件", systemCalendarDisclosureLabel(1))
        assertEquals("宝宝昵称 · 记录类型", systemCalendarDisclosureLabel(2))
        assertEquals("含备注与照片数量", systemCalendarDisclosureLabel(3))
        // Out-of-range coerces via domain fromStored → L1 for 0, L3 for high.
        assertEquals("仅乐记事件", systemCalendarDisclosureLabel(0))
        assertEquals("含备注与照片数量", systemCalendarDisclosureLabel(9))
    }

    @Test
    fun disclosureDetailsStayConciseAndPreservePrivacy() {
        assertEquals(
            "标题显示「宝宝昵称 · 记录类型」。",
            systemCalendarDisclosureDetail(2),
        )
        assertTrue(systemCalendarDisclosureDetail(3).contains("照片本身不会写入日历"))
        assertTrue(systemCalendarDisclosureDetail(3).contains("返回乐记"))
        assertTrue(systemCalendarDisclosureDetail(1).contains("乐记 · 护理计划"))
    }
}
