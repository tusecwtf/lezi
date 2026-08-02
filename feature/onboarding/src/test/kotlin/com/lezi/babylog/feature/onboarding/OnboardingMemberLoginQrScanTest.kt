package com.lezi.babylog.feature.onboarding

import com.lezi.babylog.feature.onboarding.qr.OnboardingMemberLoginScanOutcome
import com.lezi.babylog.feature.onboarding.qr.parseOnboardingMemberLoginQrScan
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.qr.MemberLoginQrPayloadCodec
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Public seam: onboarding QR scan maps raw camera text to Ticket-23 error copy
 * without host-local verify Job logic. Empty input is a no-op (no error banner).
 */
class OnboardingMemberLoginQrScanTest {
    @Test
    fun blankScanIsNoOpWithoutErrorCopy() {
        assertEquals(
            OnboardingMemberLoginScanOutcome.Empty,
            parseOnboardingMemberLoginQrScan("  ", nowEpochSeconds = 1_753_418_000),
        )
    }

    @Test
    fun invalidPayloadUsesUnifiedUnavailableCopy() {
        val outcome = parseOnboardingMemberLoginQrScan(
            "not-a-member-login-qr",
            nowEpochSeconds = 1_753_418_000,
        )
        assertEquals(
            OnboardingMemberLoginScanOutcome.Rejected("这不是可用的成员登录二维码"),
            outcome,
        )
    }

    @Test
    fun expiredPayloadUsesUnifiedExpiredCopy() {
        val encoded = MemberLoginQrPayloadCodec.encode(
            MemberLoginQrPayload(
                endpoint = TrustedEndpointProfile.systemPki("https://nas.home"),
                grant = "grant-0000000000000000000000000000000000000",
                familyName = "乐乐一家",
                memberDisplayName = "妈妈",
                expiresAtEpochSeconds = 1_753_419_000,
            ),
        )
        val outcome = parseOnboardingMemberLoginQrScan(
            encoded,
            nowEpochSeconds = 1_753_419_000,
        )
        assertEquals(
            OnboardingMemberLoginScanOutcome.Rejected("这个二维码已失效，请让管理员重新生成"),
            outcome,
        )
    }

    @Test
    fun validPayloadIsReadyForControllerVerify() {
        val encoded = MemberLoginQrPayloadCodec.encode(
            MemberLoginQrPayload(
                endpoint = TrustedEndpointProfile.systemPki("https://nas.home"),
                grant = "grant-0000000000000000000000000000000000000",
                familyName = "乐乐一家",
                memberDisplayName = "妈妈",
                expiresAtEpochSeconds = 1_753_419_000,
            ),
        )
        val outcome = parseOnboardingMemberLoginQrScan(
            encoded,
            nowEpochSeconds = 1_753_418_999,
        )
        assertTrue(outcome is OnboardingMemberLoginScanOutcome.Ready)
        val ready = outcome as OnboardingMemberLoginScanOutcome.Ready
        assertEquals("妈妈", ready.payload.memberDisplayName)
        assertEquals("https://nas.home", ready.payload.endpoint.origin)
    }

    @Test
    fun landingPageQrIsReadyForTheSameControllerVerifyFlow() {
        val outcome = parseOnboardingMemberLoginQrScan(
            "http://nas.home:8767/join#v1." +
                "eyJ2IjoxLCJ0eXBlIjoibWVtYmVyX2xvZ2luIiwiZW5kcG9pbnQiOiJodHRwczovL25hcy5ob21lIiwidHJ1c3QiOiJzeXN0ZW1fcGtpIiwiZ3JhbnQiOiJncmFudC0wMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwIiwiZmFtaWx5X25hbWUiOiLkuZDkuZDkuIDlrrYiLCJtZW1iZXJfZGlzcGxheV9uYW1lIjoi5aaI5aaIIiwiZXhwaXJlc19hdCI6MTc1MzQxOTAwMH0",
            nowEpochSeconds = 1_753_418_999,
        )

        assertTrue(outcome is OnboardingMemberLoginScanOutcome.Ready)
        assertEquals(
            "妈妈",
            (outcome as OnboardingMemberLoginScanOutcome.Ready).payload.memberDisplayName,
        )
    }
}
