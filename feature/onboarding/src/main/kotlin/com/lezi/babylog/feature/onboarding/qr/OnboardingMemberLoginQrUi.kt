package com.lezi.babylog.feature.onboarding.qr

import androidx.compose.runtime.Composable
import com.lezi.babylog.designsystem.MemberLoginQrConfirmSurface
import com.lezi.babylog.domain.family.MemberLoginQrDialogModel
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.qr.MemberLoginQrPayloadCodec

/** Outcome of mapping camera text before launching controller verify. */
sealed class OnboardingMemberLoginScanOutcome {
    data object Empty : OnboardingMemberLoginScanOutcome()
    data class Ready(val payload: MemberLoginQrPayload) : OnboardingMemberLoginScanOutcome()
    data class Rejected(val message: String) : OnboardingMemberLoginScanOutcome()
}

/**
 * Ticket-23 unified onboarding scan mapping: blank is a no-op; invalid/expired use
 * fixed product copy; valid payloads are handed to FamilyWizardController verify.
 */
internal fun parseOnboardingMemberLoginQrScan(
    raw: String,
    nowEpochSeconds: Long,
): OnboardingMemberLoginScanOutcome {
    val payload = raw.trim()
    if (payload.isEmpty()) return OnboardingMemberLoginScanOutcome.Empty
    val memberLogin = runCatching { MemberLoginQrPayloadCodec.decode(payload) }.getOrNull()
        ?: return OnboardingMemberLoginScanOutcome.Rejected("这不是可用的成员登录二维码")
    if (nowEpochSeconds >= memberLogin.expiresAtEpochSeconds) {
        return OnboardingMemberLoginScanOutcome.Rejected("这个二维码已失效，请让管理员重新生成")
    }
    return OnboardingMemberLoginScanOutcome.Ready(memberLogin)
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
