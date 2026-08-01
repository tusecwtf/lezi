package com.lezi.babylog.feature.onboarding.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.domain.family.FamilyWizardState
import com.lezi.babylog.sync.PendingMemberLogin

@Composable
internal fun OnboardingJoinRoleDialog(
    familyWizardBusy: Boolean,
    onOwner: () -> Unit,
    onMember: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!familyWizardBusy) onDismiss() },
        title = { Text("你要如何加入？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                Text("已找到配置完成的家庭。请选择这台设备使用的身份。")
                Button(
                    onClick = onOwner,
                    enabled = !familyWizardBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("我是家庭管理员") }
                OutlinedButton(
                    onClick = onMember,
                    enabled = !familyWizardBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("我是家庭成员") }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun OnboardingOwnerLoginDialog(
    ownerDeviceName: String,
    onOwnerDeviceNameChange: (String) -> Unit,
    ownerRootPassword: String,
    onOwnerRootPasswordChange: (String) -> Unit,
    formError: String?,
    familyWizardBusy: Boolean,
    onLogin: () -> Unit,
    onTakeover: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理员登录") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .dismissKeyboardOnTap()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                Text("登录会新增一台管理员设备，已有管理员设备不会退出。")
                OutlinedTextField(
                    value = ownerDeviceName,
                    onValueChange = onOwnerDeviceNameChange,
                    label = { Text("设备称呼") },
                    enabled = !familyWizardBusy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ownerRootPassword,
                    onValueChange = onOwnerRootPasswordChange,
                    label = { Text("管理员根密码") },
                    supportingText = { Text("与 NAS 部署根密码一致；不会保存") },
                    enabled = !familyWizardBusy,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(
                    onClick = onTakeover,
                    enabled = !familyWizardBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("丢失设备并接管…") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onLogin,
                enabled = !familyWizardBusy,
            ) { Text(if (familyWizardBusy) "正在登录…" else "登录这台设备") }
        },
        dismissButton = {
            TextButton(
                onClick = onBack,
                enabled = !familyWizardBusy,
            ) { Text("上一步") }
        },
    )
}

@Composable
internal fun OnboardingOwnerTakeoverDialog(
    familyWizardBusy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("接管管理员身份？") },
        text = {
            Text("所有旧管理员设备都会退出家庭；普通成员不会退出。只有确定旧设备已丢失时才使用。")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !familyWizardBusy,
            ) { Text(if (familyWizardBusy) "正在接管…" else "确认接管") }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !familyWizardBusy,
            ) { Text("取消") }
        },
    )
}

@Composable
internal fun OnboardingMemberJoinDialog(
    joinDisplayName: String,
    onJoinDisplayNameChange: (String) -> Unit,
    memberDeviceName: String,
    onMemberDeviceNameChange: (String) -> Unit,
    formError: String?,
    familyWizardBusy: Boolean,
    onSubmit: () -> Unit,
    onKeepOffline: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!familyWizardBusy) onDismiss() },
        title = { Text("申请在这台设备登录") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .dismissKeyboardOnTap()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                OutlinedTextField(
                    value = joinDisplayName,
                    onValueChange = onJoinDisplayNameChange,
                    label = { Text("我的家庭称呼") },
                    placeholder = { Text("如：妈妈、干妈、月嫂小王") },
                    supportingText = { Text("家庭称呼，必填；家人用这个认出你") },
                    enabled = !familyWizardBusy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = memberDeviceName,
                    onValueChange = onMemberDeviceNameChange,
                    label = { Text("这台设备的名称") },
                    supportingText = { Text("默认取自 Android 设备名，可修改") },
                    enabled = !familyWizardBusy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "管理员会看到你的申请，并决定是否用这个称呼添加新成员。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                formError?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSubmit,
                enabled = !familyWizardBusy &&
                    joinDisplayName.isNotBlank() && memberDeviceName.isNotBlank(),
            ) { Text(if (familyWizardBusy) "正在发送…" else "发送确认请求") }
        },
        dismissButton = {
            TextButton(
                onClick = onKeepOffline,
                enabled = !familyWizardBusy,
            ) { Text("暂不连接，保持离线") }
        },
    )
}

@Composable
internal fun OnboardingMemberWaitingDialog(
    waitingRequest: PendingMemberLogin,
    familyWizardState: FamilyWizardState,
    familyWizardBusy: Boolean,
    onCancelRequest: () -> Unit,
    onKeepOffline: () -> Unit,
    onCheckResult: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!familyWizardBusy) onDismiss()
        },
        title = { Text("等待管理员确认") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                Text("已申请：${waitingRequest.displayName}")
                Text("设备：${waitingRequest.deviceName}")
                Text(
                    "申请将在 24 小时内失效。管理员下次前台打开 App 后可以批准或拒绝。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (familyWizardState as? FamilyWizardState.WaitingForMemberApproval)
                    ?.feedback
                    ?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(
                    onClick = onCancelRequest,
                    enabled = !familyWizardBusy,
                ) { Text("取消申请") }
                TextButton(
                    onClick = onKeepOffline,
                    enabled = !familyWizardBusy,
                ) { Text("暂不连接，保持离线") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCheckResult,
                enabled = !familyWizardBusy,
            ) { Text(if (familyWizardBusy) "正在检查…" else "检查结果") }
        },
    )
}
