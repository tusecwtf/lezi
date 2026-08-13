package com.lezi.babylog.feature.family.wizard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScanner
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScannerOutcome
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScannerFailure
import com.lezi.babylog.core.ui.memberloginqr.rememberMemberLoginQrScanner
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.qr.MemberLoginQrRejection
import com.lezi.babylog.sync.qr.MemberLoginQrScanOutcome

/** Production-used scanner binding; tests may substitute only the hardware Adapter. */
@Composable
internal fun rememberFamilyMemberLoginQrScanAction(
    onReady: (MemberLoginQrPayload) -> Unit,
    onMessage: (String) -> Unit,
    scannerFactory: @Composable (
        (MemberLoginQrScannerOutcome) -> Unit,
    ) -> MemberLoginQrScanner = { onOutcome ->
        rememberMemberLoginQrScanner(onOutcome = onOutcome)
    },
): () -> Unit {
    val scanner = scannerFactory { outcome ->
        applyFamilyMemberLoginQrScannerOutcome(outcome, onReady, onMessage)
    }
    return remember(scanner) { scanner::launch }
}

/** Family shell policy: typed scanner decisions become local navigation or product copy. */
internal fun applyFamilyMemberLoginQrScannerOutcome(
    outcome: MemberLoginQrScannerOutcome,
    onReady: (MemberLoginQrPayload) -> Unit,
    onMessage: (String) -> Unit,
) {
    when (outcome) {
        is MemberLoginQrScannerOutcome.Scanned -> when (val scan = outcome.outcome) {
            MemberLoginQrScanOutcome.Empty -> Unit
            is MemberLoginQrScanOutcome.Ready -> onReady(scan.payload)
            is MemberLoginQrScanOutcome.Rejected -> onMessage(
                when (scan.reason) {
                    MemberLoginQrRejection.Unavailable ->
                        "这不是可用的成员登录二维码"
                    MemberLoginQrRejection.Expired ->
                        "这个二维码已失效，请让管理员重新生成"
                },
            )
        }
        MemberLoginQrScannerOutcome.Cancelled -> Unit
        is MemberLoginQrScannerOutcome.Failed -> onMessage(
            when (outcome.reason) {
                MemberLoginQrScannerFailure.NoCamera ->
                    "此设备没有可用相机，请使用家庭服务器地址手动申请加入"
                MemberLoginQrScannerFailure.PermissionDenied ->
                    "未获得相机权限，可继续使用家庭服务器地址手动申请加入"
                MemberLoginQrScannerFailure.PermissionPermanentlyDenied ->
                    "相机权限已关闭，请在系统设置中开启，或手动申请加入"
                MemberLoginQrScannerFailure.LaunchFailed ->
                    "无法打开扫码，请稍后重试或手动申请加入"
            },
        )
    }
}
