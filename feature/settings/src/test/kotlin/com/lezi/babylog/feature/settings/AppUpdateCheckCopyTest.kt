package com.lezi.babylog.feature.settings

import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.sync.APP_UPDATE_INSTALL_IN_PROGRESS_MESSAGE
import com.lezi.babylog.sync.APP_UPDATE_INSTALL_IN_PROGRESS_TITLE
import com.lezi.babylog.sync.AppUpdateCheckResult
import com.lezi.babylog.sync.AppUpdateInstallInProgressException
import com.lezi.babylog.sync.AppUpdateInstallResult
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.AppUpdateUiOutcome
import com.lezi.babylog.sync.ClientUpdateRequiredException
import com.lezi.babylog.sync.appUpdateInstallUiOutcome
import com.lezi.babylog.sync.appUpdateUiOutcome
import com.lezi.babylog.sync.familySyncError
import com.lezi.babylog.sync.forcedUpdateDialogBody
import com.lezi.babylog.sync.forcedUpdatePackageUnknownBody
import com.lezi.babylog.sync.forcedUpdateRetryCheckLabel
import com.lezi.babylog.sync.forcedUpdateTitle
import com.lezi.babylog.sync.localAppVersionLabel
import com.lezi.babylog.sync.optionalUpdateDialogBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun forcedUpdateSurfacesNonDismissibleCopy() {
        val metadata = AppUpdateMetadata(
            packageName = "com.lezi.babylog",
            versionCode = 9,
            versionName = "0.4.0",
            minSupportedVersionCode = 8,
            sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            releaseNotes = "破坏性同步合同",
        )

        val outcome = appUpdateUiOutcome(
            Result.success(AppUpdateCheckResult.ForcedUpdate(metadata)),
            failureCopy = { "网络错误" },
        )

        assertEquals(AppUpdateUiOutcome.ForcedUpdate(metadata), outcome)
        assertEquals("必须更新乐记", forcedUpdateTitle())
        assertEquals(
            "当前版本过旧，须升级到 0.4.0 后才能继续同步家庭数据。\n破坏性同步合同",
            forcedUpdateDialogBody(metadata),
        )
    }

    @Test
    fun clientUpdateRequiredFailureMapsToForcePackageUnknownNotNetworkFailure() {
        val outcome = appUpdateUiOutcome(
            Result.failure(ClientUpdateRequiredException()),
            failureCopy = { "网络错误" },
        )

        assertEquals(AppUpdateUiOutcome.ForcedUpdatePackageUnknown, outcome)
    }

    @Test
    fun forcedPackageUnknownShellCopyIsNotGenericSyncFailure() {
        val body = forcedUpdatePackageUnknownBody()
        assertTrue(body.contains("须更新乐记"))
        assertTrue(body.contains("家庭服务器"))
        assertTrue(body.contains("重试检查更新"))
        assertFalse(body.contains("网络错误"))
        assertFalse(body.contains("暂时无法连接"))
        assertEquals("重试检查更新", forcedUpdateRetryCheckLabel())
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

    @Test
    fun installSessionStartedMapsToSystemConfirmCopy() {
        val outcome = appUpdateInstallUiOutcome(
            Result.success(AppUpdateInstallResult.SessionStarted),
            failureCopy = { "unused" },
        )

        assertTrue(outcome is AppUpdateUiOutcome.Message)
        assertEquals("正在安装", (outcome as AppUpdateUiOutcome.Message).title)
    }

    @Test
    fun installPermissionRequiredMapsToNeedsInstallPermission() {
        val outcome = appUpdateInstallUiOutcome(
            Result.success(AppUpdateInstallResult.RequiresInstallPermission),
            failureCopy = { "unused" },
        )

        assertEquals(AppUpdateUiOutcome.NeedsInstallPermission, outcome)
    }

    @Test
    fun installFailureUsesInjectedProductCopy() {
        val outcome = appUpdateInstallUiOutcome(
            Result.failure(IllegalStateException("校验失败")),
            failureCopy = { error -> error.message ?: "failed" },
        )

        assertEquals(
            AppUpdateUiOutcome.Message(title = "更新失败", body = "校验失败"),
            outcome,
        )
    }

    @Test
    fun installBusyMapsToInProgressCopyNotHardFailureTitle() {
        val outcome = appUpdateInstallUiOutcome(
            Result.failure(AppUpdateInstallInProgressException()),
            failureCopy = { "should-not-use-fallback" },
        )

        assertEquals(
            AppUpdateUiOutcome.Message(
                title = APP_UPDATE_INSTALL_IN_PROGRESS_TITLE,
                body = APP_UPDATE_INSTALL_IN_PROGRESS_MESSAGE,
            ),
            outcome,
        )
    }

    @Test
    fun installFailureTechnicalDetailUsesUpdateFallbackNotFamilySyncCopy() {
        val technical = IllegalStateException("Failed to connect to /10.0.2.2:8765")
        // Family/Settings install paths inject productUiError, not familySyncError.
        val outcome = appUpdateInstallUiOutcome(
            Result.failure(technical),
            failureCopy = { error -> productUiError(error, "下载或安装失败，请稍后重试") },
        )

        assertEquals(
            AppUpdateUiOutcome.Message(
                title = "更新失败",
                body = "下载或安装失败，请稍后重试",
            ),
            outcome,
        )
        // Contrasting family-session mapper still says NAS/sync is down — not for updates.
        assertEquals(
            "家庭同步服务暂未连接，请稍后重试",
            familySyncError(technical, "下载或安装失败，请稍后重试"),
        )
        assertFalse((outcome as AppUpdateUiOutcome.Message).body.contains("家庭同步"))
    }
}
