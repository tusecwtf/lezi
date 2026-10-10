package com.lezi.babylog.sync.appupdate

// 192.168.77.10 is a synthetic RFC1918 LAN test endpoint, never a deployment default.

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure force-shell recovery helpers used by the root force overlay.
 * Covers reauth × install gate and LAN invite-install URL construction (ticket 02).
 */
class ForceShellSessionRecoveryTest {
    @Test
    fun needsSessionRecoveryWhenReauthRequiredAndNotJoined() {
        assertThat(
            forceShellNeedsSessionRecovery(
                isJoined = false,
                reauthRequired = true,
                retainsFamilyIdentity = true,
            ),
        ).isTrue()
    }

    @Test
    fun noSessionRecoveryWhenFullyJoined() {
        assertThat(
            forceShellNeedsSessionRecovery(
                isJoined = true,
                reauthRequired = false,
                retainsFamilyIdentity = false,
            ),
        ).isFalse()
    }

    @Test
    fun lanInviteApkUrlUsesPort8767ForKnownHost() {
        assertThat(lanInviteApkDownloadUrl("192.168.77.4"))
            .isEqualTo("http://192.168.77.4:8767/download/lezi.apk")
        assertThat(lanInviteApkDownloadUrl("  nas.home  "))
            .isEqualTo("http://nas.home:8767/download/lezi.apk")
        assertThat(lanInviteApkDownloadUrl("")).isNull()
        assertThat(lanInviteApkDownloadUrl("   ")).isNull()
    }

    @Test
    fun lanInviteGuidanceNamesPortAndNoFamilyApi() {
        val url = requireNotNull(lanInviteApkDownloadUrl("192.168.77.4"))
        val copy = forcedUpdateLanInviteGuidance(url)
        assertThat(copy).contains("8767")
        assertThat(copy).contains("原地升级会保留本机数据与家庭配置")
        assertThat(copy).contains(url)
        assertThat(copy).contains("无家庭 API")
        assertThat(forcedUpdateSessionRecoveryLabel()).isEqualTo("重新登录家庭")
        assertThat(forcedUpdateLanInviteOpenLabel()).contains("局域网")
    }
}
