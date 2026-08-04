package com.lezi.babylog.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shared Account/Onboarding member-login QR confirm chrome.
 * Hosts own actions; this surface only renders the common enablement and labels so
 * verify-retry / claim / recovery cannot diverge between entries.
 */
@Composable
fun MemberLoginQrConfirmSurface(
    familyName: String?,
    memberDisplayName: String,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    feedback: String?,
    submitting: Boolean,
    verificationInProgress: Boolean = false,
    verificationRetryRequired: Boolean = false,
    recoveryRetryRequired: Boolean = false,
    deviceNameEditable: Boolean = true,
    showConfirm: Boolean = true,
    confirmLabel: String = "在这台设备登录",
    title: String = "登录家庭",
    onConfirm: () -> Unit,
    onManualJoin: () -> Unit,
    onDismiss: () -> Unit,
    showManualJoin: Boolean = true,
) {
    LeziAlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                familyName?.let { Text(it, style = LeziTypography.TitleSm) }
                Text("已由家庭管理员授权：$memberDisplayName", style = LeziTypography.Body)
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = onDeviceNameChange,
                    enabled = deviceNameEditable && !submitting && !verificationInProgress,
                    label = { Text("这台设备的名称 *") },
                    singleLine = true,
                    isError = feedback != null && !verificationInProgress,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("将信任管理员提供的家庭服务器配置。", style = LeziTypography.Body)
                feedback?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (showManualJoin) {
                    TextButton(onClick = onManualJoin, enabled = !submitting) {
                        Text("改用加入家庭")
                    }
                }
            }
        },
        confirmButton = {
            if (showConfirm && !verificationInProgress) {
                TextButton(
                    onClick = onConfirm,
                    enabled = !submitting,
                ) {
                    Text(
                        when {
                            submitting -> "同步中…"
                            verificationRetryRequired -> "重新确认"
                            recoveryRetryRequired -> "重试首次同步"
                            else -> confirmLabel
                        },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
        },
    )
}
