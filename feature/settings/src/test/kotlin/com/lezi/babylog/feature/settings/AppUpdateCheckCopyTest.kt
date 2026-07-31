package com.lezi.babylog.feature.settings

import com.lezi.babylog.sync.AppUpdateCheckResult
import com.lezi.babylog.sync.AppUpdateMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckCopyTest {
    @Test
    fun localVersionLabelUsesProductPrefix() {
        assertEquals("版本 0.3.0", localAppVersionLabel("0.3.0"))
        assertEquals("版本 未知", localAppVersionLabel("  "))
    }

    @Test
    fun notJoinedUsesHonestConnectFamilyCopy() {
        val outcome = appUpdateUiOutcome(
            Result.success(AppUpdateCheckResult.NotJoined),
            failureCopy = { "网络错误" },
        )

        assertEquals(
            AppUpdateUiOutcome.Message(
                title = "检查更新",
                body = "请先连接家庭服务器后再检查更新",
            ),
            outcome,
        )
    }

    @Test
    fun upToDateUsesClearLatestCopy() {
        val outcome = appUpdateUiOutcome(
            Result.success(AppUpdateCheckResult.UpToDate),
            failureCopy = { "网络错误" },
        )

        assertEquals(
            AppUpdateUiOutcome.Message(
                title = "检查更新",
                body = "当前已是最新版本",
            ),
            outcome,
        )
    }

    @Test
    fun optionalUpdateSurfacesMetadataForConfirmation() {
        val metadata = AppUpdateMetadata(
            packageName = "com.lezi.babylog",
            versionCode = 7,
            versionName = "0.3.1",
            minSupportedVersionCode = 6,
            sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            releaseNotes = "修复同步",
        )

        val outcome = appUpdateUiOutcome(
            Result.success(AppUpdateCheckResult.OptionalUpdate(metadata)),
            failureCopy = { "网络错误" },
        )

        assertEquals(AppUpdateUiOutcome.OptionalUpdate(metadata), outcome)
        assertEquals(
            "发现新版本 0.3.1\n修复同步",
            optionalUpdateDialogBody(metadata),
        )
    }

    @Test
    fun failureUsesInjectedProductCopy() {
        val outcome = appUpdateUiOutcome(
            Result.failure(IllegalStateException("boom")),
            failureCopy = { "暂时无法连接家庭服务器" },
        )

        assertTrue(outcome is AppUpdateUiOutcome.Message)
        assertEquals(
            "暂时无法连接家庭服务器",
            (outcome as AppUpdateUiOutcome.Message).body,
        )
    }
}
