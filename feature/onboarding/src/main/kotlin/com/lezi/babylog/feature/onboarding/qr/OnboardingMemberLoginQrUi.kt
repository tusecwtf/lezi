package com.lezi.babylog.feature.onboarding.qr

import androidx.compose.runtime.Composable
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScanCopy
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScanner
import com.lezi.babylog.core.ui.memberloginqr.MemberLoginQrScannerOutcome
import com.lezi.babylog.core.ui.memberloginqr.rememberMemberLoginQrScanAction
import com.lezi.babylog.core.ui.memberloginqr.rememberMemberLoginQrScanner
import com.lezi.babylog.designsystem.MemberLoginQrConfirmSurface
import com.lezi.babylog.domain.family.MemberLoginQrDialogModel
import com.lezi.babylog.sync.qr.MemberLoginQrPayload

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
): () -> Unit = rememberMemberLoginQrScanAction(
    onReady = onReady,
    onMessage = onMessage,
    copy = MemberLoginQrScanCopy.Onboarding,
    scannerFactory = scannerFactory,
)


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
