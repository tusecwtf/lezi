package com.lezi.babylog.feature.family.members

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.lezi.babylog.designsystem.LeziAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.feature.family.components.FamilyDialog
import com.lezi.babylog.feature.family.components.FamilyDestructiveAction
import com.lezi.babylog.feature.family.components.SecureWindowWhileVisible
import com.lezi.babylog.feature.family.components.canConfirmFamilyDeletion
import com.lezi.babylog.feature.family.components.familyDestructiveConfirmPresentation
import com.lezi.babylog.sync.qr.MemberLoginQrCode
import com.lezi.babylog.sync.qr.MemberLoginQrContentCodec
@Composable
internal fun RenameFamilyDialog(
    familyName: String,
    onFamilyNameChange: (String) -> Unit,
    feedback: String?,
    saving: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    LeziAlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("修改家庭名") },
        text = {
            Column(
                modifier = Modifier.dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                OutlinedTextField(
                    value = familyName,
                    onValueChange = onFamilyNameChange,
                    label = { Text("共享家庭名") },
                    placeholder = { Text("家庭名（必填）") },
                    supportingText = {
                        Text(feedback ?: "仅管理员可改；全员设备看到同一个名字")
                    },
                    isError = feedback != null,
                    enabled = !saving,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !saving) {
                Text(if (saving) "保存中…" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        },
    )
}


@Composable
internal fun EditMyDisplayNameDialog(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    feedback: String?,
    saving: Boolean,
    title: String = "修改我的家庭称呼",
    fieldLabel: String = "我是宝宝的？",
    supportingCopy: String = "只改自己的称呼，不能改其他家人",
    confirmCopy: String = "保存",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    LeziAlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text(fieldLabel) },
                    placeholder = { Text("如：妈妈、干妈、月嫂小王") },
                    supportingText = {
                        Text(feedback ?: supportingCopy)
                    },
                    isError = feedback != null,
                    enabled = !saving,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !saving) {
                Text(if (saving) "保存中…" else confirmCopy)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        },
    )
}


internal data class MemberLoginQrSharingCopy(
    val description: String,
    val instructions: String,
)

internal fun memberLoginQrSharingCopy(code: MemberLoginQrCode): MemberLoginQrSharingCopy =
    if (code.landingUrl == null) {
        MemberLoginQrSharingCopy(
            description =
                "成员登录二维码，已授权${code.payload.memberDisplayName}在十分钟内登录一台新设备",
            instructions =
                "十分钟内有效，只可成功登录一次。二维码包含家庭服务器信任配置，请仅当面分享。",
        )
    } else {
        MemberLoginQrSharingCopy(
            description =
                "成员登录二维码，已授权${code.payload.memberDisplayName}在十分钟内登录；未安装乐记可用系统相机下载",
            instructions =
                "十分钟内有效，只可成功登录一次。未安装乐记时，可用系统相机扫描并下载；安装后请用乐记重新扫描此二维码。超时请让管理员重新生成。二维码包含家庭服务器信任配置，请仅当面分享。",
        )
    }

@Composable
internal fun MemberLoginQrCodeDialog(
    code: MemberLoginQrCode,
    onDismiss: () -> Unit,
) {
    SecureWindowWhileVisible()
    val copy = memberLoginQrSharingCopy(code)
    val qrBitmap = remember(code) {
        BarcodeEncoder().encodeBitmap(
            MemberLoginQrContentCodec.encode(code),
            BarcodeFormat.QR_CODE,
            640,
            640,
        ).asImageBitmap()
    }
    LeziAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("成员登录二维码") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                Image(
                    bitmap = qrBitmap,
                    contentDescription = copy.description,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 240.dp),
                )
                Text("已授权：${code.payload.memberDisplayName}", style = LeziTypography.BodyStrong)
                Text(
                    copy.instructions,
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}


@Composable
internal fun LeaveFamilyDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
) {
    val presentation = familyDestructiveConfirmPresentation(
        FamilyDestructiveAction.LeaveFamily,
        busy,
    )
    LeziAlertDialog(
        onDismissRequest = { if (presentation.dismissible) onDismiss() },
        title = { Text("退出家庭？") },
        text = {
            Text(
                "服务器确认后，你的家庭成员身份、全部设备和登录信息会被彻底删除，本机家庭记录、待同步内容、照片和服务器信任也会清除。已同步的家庭事实继续保留，作者显示为“家人”。此操作无法撤销。",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = presentation.enabled) {
                Text(presentation.label, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = presentation.dismissible) { Text("取消") }
        },
    )
}


@Composable
internal fun LogoutCurrentDeviceDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
) {
    val presentation = familyDestructiveConfirmPresentation(
        FamilyDestructiveAction.LogoutDevice,
        busy,
    )
    LeziAlertDialog(
        onDismissRequest = { if (presentation.dismissible) onDismiss() },
        title = { Text("退出这台设备？") },
        text = {
            Text(
                "服务器确认退出后，本机会清除家庭记录、待同步内容、照片、服务器信任和登录信息。家庭成员身份及其他设备不受影响。",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = presentation.enabled) {
                Text(presentation.label, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = presentation.dismissible) { Text("取消") }
        },
    )
}


@Composable
internal fun RevokeFamilyDeviceDialog(
    deviceName: String,
    isCurrent: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
) {
    val presentation = familyDestructiveConfirmPresentation(
        FamilyDestructiveAction.RevokeDevice,
        busy,
    )
    LeziAlertDialog(
        onDismissRequest = { if (presentation.dismissible) onDismiss() },
        title = { Text(if (isCurrent) "撤销这台设备？" else "撤销「$deviceName」？") },
        text = {
            Text(
                if (isCurrent) {
                    "服务器确认后，本机会立即进入可恢复清理；该成员身份及其他设备不受影响。"
                } else {
                    "这只会撤销该设备的凭证。设备离线时不会即时收到通知；下次连接家庭服务器后才会清除其本地家庭数据。"
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = presentation.enabled) {
                Text(presentation.label, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = presentation.dismissible) { Text("取消") }
        },
    )
}


@Composable
internal fun DeleteFamilyDialog(
    stage: FamilyDialog.DeleteStage,
    expectedFamilyName: String,
    familyNameInput: String,
    onFamilyNameInputChange: (String) -> Unit,
    rootPassword: String,
    onRootPasswordChange: (String) -> Unit,
    errorMessage: String?,
    deleting: Boolean,
    onContinue: () -> Unit,
    onConfirm: () -> Unit,
    onRefreshFamilyInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val final = stage == FamilyDialog.DeleteStage.Final
    LeziAlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(if (final) "删除整个家庭" else "删除家庭服务器上的全部数据？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                Text(
                    if (final) {
                        "这会永久删除服务器上的全部家庭记录、计划、照片、成员和设备，且无法恢复。其他设备下次连接时也会退出。"
                    } else {
                        "这会影响全部家庭成员，并删除服务器上的全部家庭数据。服务器确认前不会清除本机。"
                    },
                )
                if (final && expectedFamilyName.isBlank()) {
                    Text(
                        "家庭名尚未同步，暂时不能安全确认删除。请返回账户页刷新家庭信息后重试。",
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (final) {
                    Text(
                        "请输入完整家庭名「$expectedFamilyName」以确认删除。",
                    )
                    OutlinedTextField(
                        enabled = !deleting,
                        value = familyNameInput,
                        onValueChange = onFamilyNameInputChange,
                        label = { Text("输入家庭名确认") },
                        singleLine = true,
                        isError = familyNameInput.isNotEmpty() &&
                            familyNameInput.trim() != expectedFamilyName.trim(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        enabled = !deleting,
                        value = rootPassword,
                        onValueChange = onRootPasswordChange,
                        label = { Text("管理员根密码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    errorMessage?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = LeziTypography.Meta,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Assertive
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = when {
                    final && expectedFamilyName.isBlank() -> onRefreshFamilyInfo
                    final -> onConfirm
                    else -> onContinue
                },
                enabled = !deleting && (expectedFamilyName.isBlank() ||
                    !final || canConfirmFamilyDeletion(
                        expectedFamilyName,
                        familyNameInput,
                        rootPassword,
                    )
                ),
            ) {
                Text(
                    if (final) {
                        when {
                            expectedFamilyName.isBlank() -> "返回并刷新"
                            deleting -> "正在删除…"
                            else -> "永久删除家庭"
                        }
                    } else {
                        "继续"
                    },
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) { Text("取消") }
        },
    )
}
