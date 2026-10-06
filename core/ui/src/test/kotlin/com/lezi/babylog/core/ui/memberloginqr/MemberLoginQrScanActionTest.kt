package com.lezi.babylog.core.ui.memberloginqr

import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.qr.MemberLoginQrRejection
import com.lezi.babylog.sync.qr.MemberLoginQrScanOutcome
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberLoginQrScanActionTest {
    @Test
    fun applyReadyExpiredCancelAndFailedNoCameraPresets() {
        val ready = mutableListOf<MemberLoginQrPayload>()
        val messages = mutableListOf<String>()
        val payload = MemberLoginQrPayload(
            endpoint = TrustedEndpointProfile.systemPki("https://nas.home"),
            grant = GRANT,
            familyName = "乐乐一家",
            memberDisplayName = "妈妈",
            expiresAtEpochSeconds = 1_753_419_000L,
        )

        applyMemberLoginQrScannerOutcome(
            outcome = MemberLoginQrScannerOutcome.Scanned(
                MemberLoginQrScanOutcome.Ready(payload),
            ),
            onReady = ready::add,
            onMessage = messages::add,
            copy = MemberLoginQrScanCopy.Family,
        )
        assertEquals(listOf(payload), ready)
        assertTrue(messages.isEmpty())

        applyMemberLoginQrScannerOutcome(
            outcome = MemberLoginQrScannerOutcome.Scanned(
                MemberLoginQrScanOutcome.Rejected(MemberLoginQrRejection.Expired),
            ),
            onReady = ready::add,
            onMessage = messages::add,
            copy = MemberLoginQrScanCopy.Family,
        )
        applyMemberLoginQrScannerOutcome(
            outcome = MemberLoginQrScannerOutcome.Cancelled,
            onReady = ready::add,
            onMessage = messages::add,
            copy = MemberLoginQrScanCopy.Onboarding,
        )
        assertEquals(listOf(payload), ready)
        assertEquals(listOf("这个二维码已失效，请让管理员重新生成"), messages)

        applyMemberLoginQrScannerOutcome(
            outcome = MemberLoginQrScannerOutcome.Failed(MemberLoginQrScannerFailure.NoCamera),
            onReady = ready::add,
            onMessage = messages::add,
            copy = MemberLoginQrScanCopy.Family,
        )
        applyMemberLoginQrScannerOutcome(
            outcome = MemberLoginQrScannerOutcome.Failed(MemberLoginQrScannerFailure.NoCamera),
            onReady = ready::add,
            onMessage = messages::add,
            copy = MemberLoginQrScanCopy.Onboarding,
        )
        assertEquals(listOf(payload), ready)
        assertEquals(
            listOf(
                "这个二维码已失效，请让管理员重新生成",
                "此设备没有可用相机，请使用家庭服务器地址手动申请加入",
                "这台设备没有可用相机，也可以输入家庭服务器地址继续",
            ),
            messages,
        )
    }

    private companion object {
        const val GRANT = "grant-0000000000000000000000000000000000000"
    }
}
