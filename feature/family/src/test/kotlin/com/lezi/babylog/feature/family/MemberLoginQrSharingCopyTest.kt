package com.lezi.babylog.feature.family

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.feature.family.members.memberLoginQrSharingCopy
import com.lezi.babylog.sync.qr.MemberLoginQrCode
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import org.junit.Test

class MemberLoginQrSharingCopyTest {
    @Test
    fun systemCameraDownloadCopyAppearsOnlyWhenTheQrHasALandingPage() {
        val payload = MemberLoginQrPayload(
            endpoint = TrustedEndpointProfile.systemPki("https://nas.home"),
            grant = "grant-0000000000000000000000000000000000000",
            familyName = "乐乐一家",
            memberDisplayName = "妈妈",
            expiresAtEpochSeconds = 1_753_419_000,
        )

        val legacy = memberLoginQrSharingCopy(MemberLoginQrCode(payload, landingUrl = null))
        val downloadable = memberLoginQrSharingCopy(
            MemberLoginQrCode(payload, landingUrl = "http://nas.home:8767/join"),
        )

        assertThat(legacy.description)
            .isEqualTo("成员登录二维码，已授权妈妈在十分钟内登录一台新设备")
        assertThat(legacy.instructions).doesNotContain("系统相机")
        assertThat(downloadable.description).contains("未安装乐记可用系统相机下载")
        assertThat(downloadable.instructions).contains("安装后请用乐记重新扫描")
    }
}
