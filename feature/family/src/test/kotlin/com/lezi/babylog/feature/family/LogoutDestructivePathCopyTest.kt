package com.lezi.babylog.feature.family

import com.lezi.babylog.feature.family.members.deviceRemovedReceiptCopy
import com.lezi.babylog.feature.family.members.logoutDeviceConfirmPresentation
import com.lezi.babylog.feature.family.members.logoutPendingDisclosure
import com.lezi.babylog.feature.family.members.logoutSourceCommandDisclosure
import com.lezi.babylog.feature.family.members.SourceCommandLogoutPreview
import com.lezi.babylog.sync.DeviceRemovedCleanupReceipt
import com.lezi.babylog.sync.SourceCommandLogoutConsent
import com.lezi.babylog.sync.SourceCommandLogoutState
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
        val unknown = logoutDeviceConfirmPresentation(false, null, SourceCommandLogoutPreview.Ready(null))
        assertEquals("退出这台设备", unknown.label)
        assertTrue(unknown.enabled)
        assertTrue(unknown.dismissible)

        // Visible pending count: renamed to confirm-not-block, still enabled
        // even while the family server is unreachable.
        val pending = logoutDeviceConfirmPresentation(false, 34, SourceCommandLogoutPreview.Ready(null))
        assertEquals("仍然退出", pending.label)
        assertTrue(pending.enabled)
        assertTrue(pending.dismissible)

        // Zero pending behaves like unknown: no needless scare copy.
        val zero = logoutDeviceConfirmPresentation(false, 0, SourceCommandLogoutPreview.Ready(null))
        assertEquals("退出这台设备", zero.label)
        assertTrue(zero.enabled)

        // Busy keeps the existing busy presentation for either label state.
        val busy = logoutDeviceConfirmPresentation(true, 34, SourceCommandLogoutPreview.Ready(null))
        assertEquals("退出中…", busy.label)
        assertFalse(busy.enabled)
        assertFalse(busy.dismissible)
    }

    @Test
    fun uncheckedOrFailedSourcePreviewDisablesFinalActionButAllowsCancel() {
        val checking = logoutDeviceConfirmPresentation(false, 34, SourceCommandLogoutPreview.Checking)
        assertFalse(checking.enabled)
        assertTrue(checking.dismissible)
        val failed = logoutDeviceConfirmPresentation(false, 34, SourceCommandLogoutPreview.Failed())
        assertFalse(failed.enabled)
        assertTrue(failed.dismissible)
    }

    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    @Test
    fun unknownSourceDisclosureKeepsHistoricalTargetUnknownAndNamesEveryRequest() {
        val consent = SourceCommandLogoutConsent(
            requestIds = listOf("request-a", "request-b"),
            serverOrigin = null,
            state = SourceCommandLogoutState.Unknown,
            exactEvidence = Any(),
        )

        val disclosure = logoutSourceCommandDisclosure(consent)
        val presentation = logoutDeviceConfirmPresentation(false, 34, SourceCommandLogoutPreview.Ready(consent))

        assertTrue(disclosure.contains("来源操作结果尚未确认"))
        assertTrue(disclosure.contains("服务器：未知的历史服务器"))
        assertTrue(disclosure.contains("请求（2）：\nrequest-a\nrequest-b"))
        assertTrue(disclosure.contains("本机核实；不会撤销服务器上可能已经生效的操作"))
        assertEquals("放弃核实并退出", presentation.label)
        assertTrue(presentation.enabled)
        assertTrue(presentation.dismissible)
    }

    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    @Test
    fun confirmedSourceDisclosureNamesItsOriginalServerAndRemainingRefresh() {
        val consent = SourceCommandLogoutConsent(
            requestIds = listOf("request-confirmed"),
            serverOrigin = "https://original-nas.example:8765",
            state = SourceCommandLogoutState.ConfirmedRefreshRequired,
            exactEvidence = Any(),
        )

        val disclosure = logoutSourceCommandDisclosure(consent)
        val presentation = logoutDeviceConfirmPresentation(false, 0, SourceCommandLogoutPreview.Ready(consent))

        assertTrue(disclosure.contains("来源操作已确认，仍需刷新本机"))
        assertTrue(disclosure.contains("服务器：https://original-nas.example:8765"))
        assertTrue(disclosure.contains("请求（1）：\nrequest-confirmed"))
        assertTrue(disclosure.contains("本机刷新；不会撤销服务器上已经生效的操作"))
        assertEquals("放弃刷新并退出", presentation.label)
        assertTrue(presentation.enabled)
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
