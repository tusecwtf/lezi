package com.lezi.babylog.feature.onboarding

import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScannerOutcome
import com.lezi.babylog.feature.onboarding.qr.applyOnboardingMemberLoginQrScannerOutcome
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.qr.MemberLoginQrScanPolicy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingMemberLoginQrScanTest {
    @Test
    fun onboardingShellConsumesTheSharedRawAndClockOutcome() {
        val scan = MemberLoginQrScanPolicy(FIXED_CLOCK).evaluate(VALID_RAW)
        var ready: MemberLoginQrPayload? = null
        var message: String? = null

        applyOnboardingMemberLoginQrScannerOutcome(
            outcome = MemberLoginQrScannerOutcome.Scanned(scan),
            onReady = { ready = it },
            onMessage = { message = it },
        )

        assertEquals("妈妈", ready?.memberDisplayName)
        assertEquals(GRANT, ready?.grant)
        assertNull(message)
    }

    @Test
    fun onboardingSurfaceKeepsLocalCopyAndCancelIsSilent() {
        val messages = mutableListOf<String>()
        val ready = mutableListOf<MemberLoginQrPayload>()

        applyOnboardingMemberLoginQrScannerOutcome(
            outcome = MemberLoginQrScannerOutcome.Scanned(
                MemberLoginQrScanPolicy(FIXED_CLOCK).evaluate(EXPIRED_RAW),
            ),
            onReady = ready::add,
            onMessage = messages::add,
        )
        applyOnboardingMemberLoginQrScannerOutcome(
            outcome = MemberLoginQrScannerOutcome.Cancelled,
            onReady = ready::add,
            onMessage = messages::add,
        )

        assertEquals(emptyList<MemberLoginQrPayload>(), ready)
        assertEquals(listOf("这个二维码已失效，请让管理员重新生成"), messages)
    }

    private companion object {
        const val GRANT = "grant-0000000000000000000000000000000000000"
        val FIXED_CLOCK: Clock = Clock.fixed(
            Instant.ofEpochSecond(1_753_418_000L),
            ZoneOffset.UTC,
        )
        const val VALID_RAW =
            "{\"v\":1,\"type\":\"member_login\",\"endpoint\":\"https://nas.home\"," +
                "\"trust\":\"system_pki\",\"grant\":\"$GRANT\",\"family_name\":\"乐乐一家\"," +
                "\"member_display_name\":\"妈妈\",\"expires_at\":1753419000}"
        const val EXPIRED_RAW =
            "{\"v\":1,\"type\":\"member_login\",\"endpoint\":\"https://nas.home\"," +
                "\"trust\":\"system_pki\",\"grant\":\"$GRANT\",\"family_name\":\"乐乐一家\"," +
                "\"member_display_name\":\"妈妈\",\"expires_at\":1753418000}"
    }
}
