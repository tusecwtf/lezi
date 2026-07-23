package com.lezi.babylog.feature.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PrivateWidgetCopyTest {
    @Test
    fun defaultCopy_onlyInvitesUserToOpenTheApp() {
        val copy = privateWidgetCopy()
        val rendered = "${copy.title} ${copy.message}"

        assertEquals("乐记", copy.title)
        assertEquals("为保护隐私，请打开应用查看记录", copy.message)
        listOf("宝宝", "奶", "睡", "尿", "便", "最近").forEach { sensitiveLabel ->
            assertFalse(rendered.contains(sensitiveLabel))
        }
        assertFalse(rendered.any(Char::isDigit))
    }
}
