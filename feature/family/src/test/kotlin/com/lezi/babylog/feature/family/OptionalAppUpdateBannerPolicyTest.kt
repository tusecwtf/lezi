package com.lezi.babylog.feature.family

import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.optionalAppUpdateBannerContentDescription
import com.lezi.babylog.sync.optionalAppUpdateBannerLabel
import com.lezi.babylog.sync.optionalUpdateDialogBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy + copy for handshake-discovered optional update banner on the account card.
 * Covers discover → banner / 稍后 visibility rules without Compose.
 */
class OptionalAppUpdateBannerPolicyTest {
    @Test
    fun bannerOnlyWhenJoinedAndMetadataPresent() {
        val metadata = sampleMetadata(versionCode = 7, versionName = "0.3.1")

        assertTrue(shouldShowOptionalAppUpdateBanner(isJoined = true, optionalAppUpdate = metadata))
        assertFalse(shouldShowOptionalAppUpdateBanner(isJoined = false, optionalAppUpdate = metadata))
        assertFalse(shouldShowOptionalAppUpdateBanner(isJoined = true, optionalAppUpdate = null))
        assertFalse(shouldShowOptionalAppUpdateBanner(isJoined = false, optionalAppUpdate = null))
    }

    @Test
    fun bannerCopyMatchesAccountLanguageAndDialogBody() {
        val metadata = sampleMetadata(
            versionCode = 8,
            versionName = "0.4.0",
            releaseNotes = "同步更稳",
        )

        assertEquals("有新版本 0.4.0 可更新", optionalAppUpdateBannerLabel(metadata.versionName))
        assertEquals(
            "可选更新：版本 0.4.0，点按查看",
            optionalAppUpdateBannerContentDescription(metadata.versionName),
        )
        assertEquals(
            "发现新版本 0.4.0\n同步更稳",
            optionalUpdateDialogBody(metadata),
        )
    }

    private fun sampleMetadata(
        versionCode: Int,
        versionName: String,
        releaseNotes: String? = null,
    ) = AppUpdateMetadata(
        packageName = "com.lezi.babylog",
        versionCode = versionCode,
        versionName = versionName,
        minSupportedVersionCode = 6,
        sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        releaseNotes = releaseNotes,
    )
}
