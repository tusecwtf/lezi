package com.lezi.babylog.feature.family

import com.lezi.babylog.feature.family.members.deviceRemovedReceiptCopy
import com.lezi.babylog.feature.family.members.logoutDeviceConfirmPresentation
import com.lezi.babylog.feature.family.members.logoutPendingDisclosure
import com.lezi.babylog.sync.DeviceRemovedCleanupReceipt
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S3 (0.5.4) destructive-path copy: quantified pending disclosure before a
 * device logout, a confirm-not-block confirm label, and the one-time
 * device-removal cleanup receipt. Tier B pure functions, JVM-locked.
 */
class LogoutDestructivePathCopyTest {
    private val originalTimeZone = TimeZone.getDefault()

    @Before
    fun pinUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun pendingDisclosureNamesTheCountAndPermanentLoss() {
        assertNull(logoutPendingDisclosure(0))
        assertNull(logoutPendingDisclosure(-3))
        assertEquals(
            "还有 3 条未同步，退出后将永久丢弃",
            logoutPendingDisclosure(3),
        )
        assertEquals(
            "还有 34 条未同步，退出后将永久丢弃",
            logoutPendingDisclosure(34),
        )
    }

    @Test
    fun confirmStaysAvailableAndReadsStillLogoutOnlyWithDisclosure() {
        // Unknown count: plain destructive confirm, never blocked.
        val unknown = logoutDeviceConfirmPresentation(busy = false, pendingPublishCount = null)
        assertEquals("退出这台设备", unknown.label)
        assertTrue(unknown.enabled)
        assertTrue(unknown.dismissible)

        // Visible pending count: renamed to confirm-not-block, still enabled
        // even while the family server is unreachable.
        val pending = logoutDeviceConfirmPresentation(busy = false, pendingPublishCount = 34)
        assertEquals("仍然退出", pending.label)
        assertTrue(pending.enabled)
        assertTrue(pending.dismissible)

        // Zero pending behaves like unknown: no needless scare copy.
        val zero = logoutDeviceConfirmPresentation(busy = false, pendingPublishCount = 0)
        assertEquals("退出这台设备", zero.label)
        assertTrue(zero.enabled)

        // Busy keeps the existing busy presentation for either label state.
        val busy = logoutDeviceConfirmPresentation(busy = true, pendingPublishCount = 34)
        assertEquals("退出中…", busy.label)
        assertFalse(busy.enabled)
        assertFalse(busy.dismissible)
    }

    @Test
    fun removalReceiptCopyNamesRemovalTimeAndClearedLoss() {
        // 2026-09-13 04:30 UTC, pinned via TimeZone in this test class.
        val receipt = DeviceRemovedCleanupReceipt(
            removedAtEpochMillis = 1_789_273_800_000L,
            clearedPendingCount = 12,
        )
        assertEquals(
            "该设备于 2026-09-13 04:30 被家庭管理员移除，已清理 12 条未同步内容。",
            deviceRemovedReceiptCopy(receipt),
        )

        // Zero loss stays honest instead of being hidden.
        val zeroLoss = receipt.copy(clearedPendingCount = 0)
        assertTrue(deviceRemovedReceiptCopy(zeroLoss).endsWith("已清理 0 条未同步内容。"))
    }
}
