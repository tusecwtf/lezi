package com.lezi.babylog.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductUiErrorTest {
    @Test
    fun hidesNetworkHostsAndPaths() {
        assertEquals(
            "操作失败",
            productUiError(IllegalStateException("Failed to connect to /10.0.2.2:8765"), "操作失败"),
        )
        assertEquals(
            "操作失败",
            productUiError(IllegalStateException("连接失败：nas.local:8765"), "操作失败"),
        )
        assertEquals(
            "操作失败",
            productUiError(IllegalStateException("同步错误 /data/user/0/com.lezi/files"), "操作失败"),
        )
        assertEquals(
            "操作失败",
            productUiError(IllegalStateException("java.lang.IllegalStateException: boom"), "操作失败"),
        )
    }

    @Test
    fun keepsProductFacingChinese() {
        assertEquals(
            "邀请码已失效",
            productUiError(IllegalArgumentException("邀请码已失效"), "加入失败"),
        )
        assertEquals(
            "宝宝昵称「豆豆」已存在",
            productUiError(IllegalArgumentException("宝宝昵称「豆豆」已存在"), "添加失败"),
        )
    }

    @Test
    fun technicalDetectorsCatchCommonLeakPatterns() {
        assertTrue(looksTechnicalDetail("连接失败：example.com:8765"))
        assertTrue(looksTechnicalDetail("path /data/user/0/db"))
        assertFalse(looksTechnicalDetail("邀请码已失效"))
        assertTrue(isProductFacingChinese("睡眠状态已变化，请重新打开睡眠菜单"))
        assertFalse(isProductFacingChinese("Something went wrong"))
    }
}
