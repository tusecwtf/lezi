package com.lezi.babylog.feature.family.wizard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM chrome contract for owner takeover (replaces device dialog click suite).
 * Dangerous-copy wording must stay explicit; busy/idle confirm labels must differ.
 */
class OwnerTakeoverChromeTest {
    @Test
    fun titleAndBodyWarnThatOldOwnerDevicesLeave() {
        assertEquals("接管管理员身份？", OwnerTakeoverChrome.TITLE)
        assertTrue(OwnerTakeoverChrome.BODY.contains("旧管理员设备都会退出家庭"))
        assertTrue(OwnerTakeoverChrome.BODY.contains("普通成员不会退出"))
        assertTrue(OwnerTakeoverChrome.BODY.contains("旧设备已丢失"))
    }

    @Test
    fun confirmLabelReflectsBusyStateAndCancelStaysStable() {
        assertEquals("确认接管", OwnerTakeoverChrome.confirmLabel(submitting = false))
        assertEquals("正在接管…", OwnerTakeoverChrome.confirmLabel(submitting = true))
        assertEquals("取消", OwnerTakeoverChrome.CANCEL)
    }
}
