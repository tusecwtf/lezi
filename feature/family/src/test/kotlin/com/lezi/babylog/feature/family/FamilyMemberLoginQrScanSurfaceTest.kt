package com.lezi.babylog.feature.family

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScannerOutcome
import com.lezi.babylog.feature.family.wizard.applyFamilyMemberLoginQrScannerOutcome
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.qr.MemberLoginQrScanPolicy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Test

class FamilyMemberLoginQrScanSurfaceTest {
    @Test
    fun familyShellConsumesTheSharedRawAndClockOutcome() {
        val scan = MemberLoginQrScanPolicy(FIXED_CLOCK).evaluate(VALID_RAW)
        var ready: MemberLoginQrPayload? = null
        var message: String? = null

        applyFamilyMemberLoginQrScannerOutcome(
            outcome = MemberLoginQrScannerOutcome.Scanned(scan),
            onReady = { ready = it },
            onMessage = { message = it },
        )

        assertThat(ready?.memberDisplayName).isEqualTo("妈妈")
        assertThat(ready?.grant).isEqualTo(GRANT)
        assertThat(message).isNull()
    }

    @Test
    fun familySurfaceKeepsLocalCopyAndCancelIsSilent() {
        val messages = mutableListOf<String>()
        val ready = mutableListOf<MemberLoginQrPayload>()

        applyFamilyMemberLoginQrScannerOutcome(
            outcome = MemberLoginQrScannerOutcome.Scanned(
                MemberLoginQrScanPolicy(FIXED_CLOCK).evaluate(EXPIRED_RAW),
            ),
            onReady = ready::add,
            onMessage = messages::add,
        )
        applyFamilyMemberLoginQrScannerOutcome(
            outcome = MemberLoginQrScannerOutcome.Cancelled,
            onReady = ready::add,
            onMessage = messages::add,
        )

        assertThat(ready).isEmpty()
        assertThat(messages).containsExactly("这个二维码已失效，请让管理员重新生成")
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
