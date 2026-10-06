package com.lezi.babylog.core.ui.memberloginqr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.qr.MemberLoginQrRejection
import com.lezi.babylog.sync.qr.MemberLoginQrScanOutcome

/** Product copy for rejected scans and scanner failures. */
data class MemberLoginQrScanCopy(
    val unavailable: String,
    val expired: String,
    val noCamera: String,
    val permissionDenied: String,
    val permissionPermanentlyDenied: String,
    val launchFailed: String,
) {
    companion object {
        val Family = MemberLoginQrScanCopy(
            unavailable = REJECTED_UNAVAILABLE,
            expired = REJECTED_EXPIRED,
            noCamera = "此设备没有可用相机，请使用家庭服务器地址手动申请加入",
            permissionDenied = "未获得相机权限，可继续使用家庭服务器地址手动申请加入",
            permissionPermanentlyDenied = "相机权限已关闭，请在系统设置中开启，或手动申请加入",
            launchFailed = "无法打开扫码，请稍后重试或手动申请加入",
        )
        val Onboarding = MemberLoginQrScanCopy(
            unavailable = REJECTED_UNAVAILABLE,
            expired = REJECTED_EXPIRED,
            noCamera = "这台设备没有可用相机，也可以输入家庭服务器地址继续",
            permissionDenied = "未获得相机权限，也可以输入家庭服务器地址继续",
            permissionPermanentlyDenied = "相机权限已关闭，请在系统设置中开启，或输入地址继续",
            launchFailed = "无法打开扫码，请稍后重试或输入家庭服务器地址继续",
        )
    }
}

fun applyMemberLoginQrScannerOutcome(
    outcome: MemberLoginQrScannerOutcome,
    onReady: (MemberLoginQrPayload) -> Unit,
    onMessage: (String) -> Unit,
    copy: MemberLoginQrScanCopy,
) {
    when (outcome) {
        is MemberLoginQrScannerOutcome.Scanned -> when (val scan = outcome.outcome) {
            MemberLoginQrScanOutcome.Empty -> Unit
            is MemberLoginQrScanOutcome.Ready -> onReady(scan.payload)
            is MemberLoginQrScanOutcome.Rejected -> onMessage(
                when (scan.reason) {
                    MemberLoginQrRejection.Unavailable -> copy.unavailable
                    MemberLoginQrRejection.Expired -> copy.expired
                },
            )
        }
        MemberLoginQrScannerOutcome.Cancelled -> Unit
        is MemberLoginQrScannerOutcome.Failed -> onMessage(
            when (outcome.reason) {
                MemberLoginQrScannerFailure.NoCamera -> copy.noCamera
                MemberLoginQrScannerFailure.PermissionDenied -> copy.permissionDenied
                MemberLoginQrScannerFailure.PermissionPermanentlyDenied ->
                    copy.permissionPermanentlyDenied
                MemberLoginQrScannerFailure.LaunchFailed -> copy.launchFailed
            },
        )
    }
}

@Composable
fun rememberMemberLoginQrScanAction(
    onReady: (MemberLoginQrPayload) -> Unit,
    onMessage: (String) -> Unit,
    copy: MemberLoginQrScanCopy,
    scannerFactory: @Composable (
        (MemberLoginQrScannerOutcome) -> Unit,
    ) -> MemberLoginQrScanner = { onOutcome ->
        rememberMemberLoginQrScanner(onOutcome = onOutcome)
    },
): () -> Unit {
    val scanner = scannerFactory { outcome ->
        applyMemberLoginQrScannerOutcome(outcome, onReady, onMessage, copy)
    }
    val scannerState = rememberUpdatedState(scanner)
    return remember { { scannerState.value.launch() } }
}

private const val REJECTED_UNAVAILABLE = "这不是可用的成员登录二维码"
private const val REJECTED_EXPIRED = "这个二维码已失效，请让管理员重新生成"
