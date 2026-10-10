package com.lezi.babylog.feature.family.members

import com.lezi.babylog.sync.DeviceRemovedCleanupReceipt
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class MemberDateFormattingTest {
    private val instant = Instant.parse("2026-01-01T00:30:00Z")

    @Test
    fun lastUsedDateFollowsEachCallsZoneWithoutChangingRelativeCopy() {
        val then = instant.epochSecond
        val now = then + 8 * 86_400
        assertEquals("01-01", formatFamilyDeviceLastUsed(then, now, ZoneId.of("UTC"), Locale.US))
        assertEquals("12-31", formatFamilyDeviceLastUsed(then, now, ZoneId.of("America/Los_Angeles"), Locale.US))
        assertEquals("01-01", formatFamilyDeviceLastUsed(then, now, ZoneId.of("Asia/Shanghai"), Locale.CHINA))
        assertEquals("2 分钟前", formatFamilyDeviceLastUsed(then, then + 120, ZoneId.of("UTC"), Locale.US))
    }

    @Test
    fun removalReceiptFollowsEachCallsZoneAndKeepsExactLossCopy() {
        val receipt = DeviceRemovedCleanupReceipt(instant.toEpochMilli(), 12)
        assertEquals(
            "该设备于 2026-01-01 00:30 被家庭管理员移除，已清理 12 条未同步内容。",
            deviceRemovedReceiptCopy(receipt, ZoneId.of("UTC"), Locale.US),
        )
        assertEquals(
            "该设备于 2025-12-31 16:30 被家庭管理员移除，已清理 12 条未同步内容。",
            deviceRemovedReceiptCopy(receipt, ZoneId.of("America/Los_Angeles"), Locale.US),
        )
        assertEquals(
            "该设备于 2026-01-01 08:30 被家庭管理员移除，已清理 12 条未同步内容。",
            deviceRemovedReceiptCopy(receipt, ZoneId.of("Asia/Shanghai"), Locale.CHINA),
        )
    }
}
