package com.lezi.babylog.feature.onboarding.qr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScanner
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScannerOutcome
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScannerFailure
import com.lezi.babylog.core.ui.memberloginqr.rememberMemberLoginQrScanner
import com.lezi.babylog.designsystem.MemberLoginQrConfirmSurface
import com.lezi.babylog.domain.family.MemberLoginQrDialogModel
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.qr.MemberLoginQrRejection
import com.lezi.babylog.sync.qr.MemberLoginQrScanOutcome

/** Production-used scanner binding; tests may substitute only the hardware Adapter. */
@Composable
internal fun rememberOnboardingMemberLoginQrScanAction(
    onReady: (MemberLoginQrPayload) -> Unit,
    onMessage: (String) -> Unit,
    scannerFactory: @Composable (
        (MemberLoginQrScannerOutcome) -> Unit,
    ) -> MemberLoginQrScanner = { onOutcome ->
        rememberMemberLoginQrScanner(onOutcome = onOutcome)
    },
): () -> Unit {
    val scanner = scannerFactory { outcome ->
        applyOnboardingMemberLoginQrScannerOutcome(outcome, onReady, onMessage)
    }
    return remember(scanner) { scanner::launch }
}

/** Onboarding shell policy: typed scanner decisions become local navigation or form copy. */
internal fun applyOnboardingMemberLoginQrScannerOutcome(
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
                    "这台设备没有可用相机，也可以输入家庭服务器地址继续"
                MemberLoginQrScannerFailure.PermissionDenied ->
                    "未获得相机权限，也可以输入家庭服务器地址继续"
                MemberLoginQrScannerFailure.PermissionPermanentlyDenied ->
                    "相机权限已关闭，请在系统设置中开启，或输入地址继续"
                MemberLoginQrScannerFailure.LaunchFailed ->
                    "无法打开扫码，请稍后重试或输入家庭服务器地址继续"
            },
        )
    }
}

@Composable
internal fun OnboardingMemberLoginQrConfirm(
    model: MemberLoginQrDialogModel,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onManualJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (model.payload == null) return
    MemberLoginQrConfirmSurface(
        familyName = model.display.familyName,
        memberDisplayName = model.display.memberDisplayName,
        deviceName = deviceName,
        onDeviceNameChange = {
            if (model.deviceNameEditable) onDeviceNameChange(it)
        },
        feedback = model.feedback,
        submitting = model.submitting,
        verificationInProgress = model.verificationInProgress,
        verificationRetryRequired = model.verificationRetryRequired,
        recoveryRetryRequired = model.recoveryRetryRequired,
        deviceNameEditable = model.deviceNameEditable,
        showConfirm = model.showConfirm,
        confirmLabel = model.confirmLabel,
        title = model.title,
        onConfirm = onConfirm,
        onManualJoin = onManualJoin,
        onDismiss = onDismiss,
        showManualJoin = !model.submitting,
    )
}
