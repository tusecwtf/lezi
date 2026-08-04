package com.lezi.babylog.feature.family.networksettings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.lezi.babylog.designsystem.LeziAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.lezi.babylog.designsystem.LeziDetailTopBar
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.sync.session.CertificateTrustCandidate
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.defaultAndroidDeviceName
import java.text.DateFormat

internal fun disasterRecoveryStatusCopy(status: String?): String = when (status) {
    "summary_ready" -> "恢复摘要已就绪"
    "started" -> "恢复批次已建立，准备上传本机数据"
    "manifest_received" -> "正在校验已上传的家庭数据"
    "ready_to_commit" ->
        "数据和照片已校验完成，提交前对其他设备不可见。请再次输入根密码。"
    "committed" -> "家庭恢复已提交"
    "cancelled" -> "恢复批次已取消"
    "expired" -> "恢复批次已过期，请重新开始"
    else -> "恢复状态暂时无法确认，请重试查询"
}

@Composable
fun FamilyNetworkSettingsScreen(
    ui: FamilyNetworkSettingsUi,
    onBack: () -> Unit,
    onEndpointDraftChange: (String) -> Unit,
    onProbeCandidate: () -> Unit,
    onTrustCandidate: (CertificateTrustCandidate) -> Unit,
    onRefreshAvailability: () -> Unit,
    onReconnectOwner: (deviceName: String, rootPassword: String) -> Unit,
    onRequestReconnectMember: (displayName: String, deviceName: String) -> Unit,
    onCheckReconnectMember: () -> Unit,
    onCancelReconnectMember: () -> Unit,
    onPrepareDisasterRecovery: () -> Unit,
    onStartDisasterRecovery: (
        ownerDisplayName: String,
        deviceName: String,
        rootPassword: String,
    ) -> Unit,
    onCommitDisasterRecovery: (rootPassword: String) -> Unit,
    onCancelDisasterRecovery: () -> Unit,
) {
    val context = LocalContext.current
    var deviceName by remember { mutableStateOf(defaultAndroidDeviceName(context)) }
    var rootPassword by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var ownerDisplayName by remember { mutableStateOf("") }
    var recoveryRootPassword by remember { mutableStateOf("") }
    var finalRecoveryConfirmation by remember { mutableStateOf(false) }
    var certificateConfirmation by remember {
        mutableStateOf<CertificateTrustCandidate?>(null)
    }

    PageScaffoldBackground {
        Column(Modifier.fillMaxSize()) {
            LeziDetailTopBar(title = "家庭网络设置", onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(LeziSpacing.Page),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                SectionHeading(title = "当前连接")
                LeziSurfacePanel(modifier = Modifier.fillMaxWidth()) {
                    Text("当前 HTTPS 地址", style = LeziTypography.Meta)
                    Text(
                        ui.currentEndpoint.ifBlank { "尚未配置" },
                        style = LeziTypography.BodyStrong,
                    )
                    Spacer(Modifier.height(LeziSpacing.Xs))
                    Text(ui.availabilityStatus, style = LeziTypography.Body)
                    Text(
                        ui.lastHealthyAtMillis?.let {
                            "最近健康 · ${DateFormat.getDateTimeInstance().format(it)}"
                        } ?: "尚无健康记录",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LeziSecondaryButton(
                    "重新检测连接",
                    onClick = onRefreshAvailability,
                    enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (ui.trustRecoveryRequired) {
                    LeziPrimaryButton(
                        "核对证书并重新登录",
                        onClick = onProbeCandidate,
                        enabled = !ui.busy && ui.pendingMember == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SectionHeading(title = "更换地址")
                Text(
                    "候选地址通过安全检查和重新登录前，当前地址、会话与本机数据不会改变。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = ui.endpointDraft,
                    onValueChange = onEndpointDraftChange,
                    label = { Text("候选 HTTPS 地址") },
                    placeholder = { Text("https://nas.home:8765") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    enabled = !ui.busy && ui.pendingMember == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                LeziPrimaryButton(
                    "检查候选地址",
                    onClick = onProbeCandidate,
                    enabled = !ui.busy && ui.pendingMember == null,
                    modifier = Modifier.fillMaxWidth(),
                )

                AnimatedVisibility(
                    visible = ui.candidate is FamilyNetworkCandidate.CertificateApproval ||
                        ui.candidate is FamilyNetworkCandidate.Ready,
                ) {
                    when (val candidate = ui.candidate) {
                        is FamilyNetworkCandidate.CertificateApproval -> {
                            LeziSurfacePanel(modifier = Modifier.fillMaxWidth()) {
                                Text("服务器证书需要确认", style = LeziTypography.BodyStrong)
                                Text(
                                    "旧指纹：${ui.currentFingerprint ?: "系统证书验证"}",
                                    style = LeziTypography.Meta,
                                )
                                Text(
                                    "新指纹：${candidate.candidate.fingerprint}",
                                    style = LeziTypography.Meta,
                                )
                            }
                            LeziSecondaryButton(
                                "核对并接受新证书",
                                onClick = { certificateConfirmation = candidate.candidate },
                                enabled = !ui.busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        is FamilyNetworkCandidate.Ready -> CandidateLoginControls(
                            ui = ui,
                            familyState = candidate.familyState,
                            displayName = displayName,
                            onDisplayNameChange = { displayName = it },
                            deviceName = deviceName,
                            onDeviceNameChange = { deviceName = it },
                            rootPassword = rootPassword,
                            onRootPasswordChange = { rootPassword = it },
                            onReconnectOwner = {
                                onReconnectOwner(deviceName, rootPassword)
                                rootPassword = ""
                            },
                            onRequestReconnectMember = {
                                onRequestReconnectMember(displayName, deviceName)
                            },
                            onCheckReconnectMember = onCheckReconnectMember,
                            onCancelReconnectMember = onCancelReconnectMember,
                            onPrepareDisasterRecovery = onPrepareDisasterRecovery,
                        )
                        is FamilyNetworkCandidate.Failed,
                        null,
                        -> Unit
                    }
                }

                if (ui.recoveryStatus != null) {
                    DisasterRecoveryControls(
                        ui = ui,
                        ownerDisplayName = ownerDisplayName,
                        onOwnerDisplayNameChange = { ownerDisplayName = it },
                        deviceName = deviceName,
                        onDeviceNameChange = { deviceName = it },
                        rootPassword = recoveryRootPassword,
                        onRootPasswordChange = { recoveryRootPassword = it },
                        onStart = {
                            onStartDisasterRecovery(
                                ownerDisplayName,
                                deviceName,
                                recoveryRootPassword,
                            )
                            recoveryRootPassword = ""
                        },
                        onCommit = { finalRecoveryConfirmation = true },
                        onCancel = onCancelDisasterRecovery,
                    )
                }

                ui.feedback?.let {
                    Text(
                        it,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(LeziSpacing.Xxl))
            }
        }
    }

    certificateConfirmation?.let { certificate ->
        LeziAlertDialog(
            onDismissRequest = { certificateConfirmation = null },
            title = { Text("确认更换服务器证书？") },
            text = {
                // 指纹已在上方「服务器证书需要确认」面板展示，弹窗只做确认。
                Text(
                    "地址或证书变化后必须重新登录或审批。确认只信任候选证书，不会发送当前家庭凭据。",
                )
            },
            confirmButton = {
                LeziTextButton(label = "确认接受", onClick = {
                        certificateConfirmation = null
                        onTrustCandidate(certificate)
                    })
            },
            dismissButton = {
                LeziTextButton(label = "取消", onClick = { certificateConfirmation = null })
            },
        )
    }
    if (finalRecoveryConfirmation) {
        LeziAlertDialog(
            onDismissRequest = { finalRecoveryConfirmation = false },
            title = { Text("最终确认恢复家庭？") },
            text = {
                Text(
                    "提交后，空服务器会一次激活完整家庭数据；当前设备将切换到新地址并成为新管理员。此操作不合并两个已有家庭。",
                )
            },
            confirmButton = {
                LeziTextButton(label = "最终确认恢复", onClick = {
                        finalRecoveryConfirmation = false
                        onCommitDisasterRecovery(recoveryRootPassword)
                        recoveryRootPassword = ""
                    })
            },
            dismissButton = {
                LeziTextButton(label = "返回核对", onClick = { finalRecoveryConfirmation = false })
            },
        )
    }
}

@Composable
private fun CandidateLoginControls(
    ui: FamilyNetworkSettingsUi,
    familyState: SetupFamilyState,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    rootPassword: String,
    onRootPasswordChange: (String) -> Unit,
    onReconnectOwner: () -> Unit,
    onRequestReconnectMember: () -> Unit,
    onCheckReconnectMember: () -> Unit,
    onCancelReconnectMember: () -> Unit,
    onPrepareDisasterRecovery: () -> Unit,
) {
    LeziSurfacePanel(modifier = Modifier.fillMaxWidth()) {
        Text("候选服务器检查通过", style = LeziTypography.BodyStrong)
        Text(
            if (familyState == SetupFamilyState.Empty) "服务器尚未配置家庭" else "服务器已有家庭配置",
            style = LeziTypography.Meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (familyState == SetupFamilyState.Empty) {
        if (ui.role == FamilyRole.Owner) {
            Text(
                "只有仍保留旧管理员身份的本机完整副本可以恢复这个空服务器。",
                style = LeziTypography.Meta,
            )
            LeziPrimaryButton(
                "从本机恢复家庭",
                onClick = onPrepareDisasterRecovery,
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text("普通成员不能恢复空服务器，请联系家庭管理员。", style = LeziTypography.Meta)
        }
        return
    }

    OutlinedTextField(
        value = deviceName,
        onValueChange = onDeviceNameChange,
        label = { Text("这台设备的称呼") },
        singleLine = true,
        enabled = !ui.busy && ui.pendingMember == null,
        modifier = Modifier.fillMaxWidth(),
    )
    if (ui.role == FamilyRole.Owner) {
        RecoveryRootPasswordField(rootPassword, onRootPasswordChange, ui.busy)
        LeziPrimaryButton(
            "重新登录并更新地址",
            onClick = onReconnectOwner,
            enabled = !ui.busy && rootPassword.isNotBlank() && deviceName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        if (ui.pendingMember == null) {
            OutlinedTextField(
                value = displayName,
                onValueChange = onDisplayNameChange,
                label = { Text("家庭称呼") },
                singleLine = true,
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            LeziPrimaryButton(
                "提交新地址加入申请",
                onClick = onRequestReconnectMember,
                enabled = !ui.busy && displayName.isNotBlank() && deviceName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text("等待候选服务器的家庭管理员确认", style = LeziTypography.Meta)
            LeziPrimaryButton(
                "检查确认结果",
                onClick = onCheckReconnectMember,
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            LeziSecondaryButton(
                "取消这条申请",
                onClick = onCancelReconnectMember,
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DisasterRecoveryControls(
    ui: FamilyNetworkSettingsUi,
    ownerDisplayName: String,
    onOwnerDisplayNameChange: (String) -> Unit,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    rootPassword: String,
    onRootPasswordChange: (String) -> Unit,
    onStart: () -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    SectionHeading(title = "本机恢复摘要")
    LeziSurfacePanel(modifier = Modifier.fillMaxWidth()) {
        val summary = ui.recoverySummary
        if (summary == null) {
            Text("已找到服务器暂存批次", style = LeziTypography.BodyStrong)
        } else {
            Text("将恢复 ${summary.totalEntities} 项家庭数据", style = LeziTypography.BodyStrong)
            Text(
                "宝宝 ${summary.babies} · 记录 ${summary.records} · 计划 ${summary.carePlans} · " +
                    "履行关系 ${summary.fulfillmentRelations}",
                style = LeziTypography.Meta,
            )
            Text(
                "自定义项目 ${summary.customItems} · 照片 ${summary.photos} · " +
                    "${formatRecoveryBytes(summary.mediaBytes)}",
                style = LeziTypography.Meta,
            )
        }
        Text(
            "不恢复旧设备、加入申请、设置、墓碑、游标或凭据；全部历史作者归新管理员。",
            style = LeziTypography.Meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (ui.recoveryStatus == "summary_ready") {
        OutlinedTextField(
            value = ownerDisplayName,
            onValueChange = onOwnerDisplayNameChange,
            label = { Text("新管理员家庭称呼") },
            singleLine = true,
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = deviceName,
            onValueChange = onDeviceNameChange,
            label = { Text("这台设备的称呼") },
            singleLine = true,
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        RecoveryRootPasswordField(rootPassword, onRootPasswordChange, ui.busy)
        LeziPrimaryButton(
            "确认摘要并开始上传",
            onClick = onStart,
            enabled = !ui.busy && ownerDisplayName.isNotBlank() &&
                deviceName.isNotBlank() && rootPassword.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Text(
            disasterRecoveryStatusCopy(ui.recoveryStatus),
            style = LeziTypography.Meta,
        )
        RecoveryRootPasswordField(rootPassword, onRootPasswordChange, ui.busy)
        LeziPrimaryButton(
            "最终检查并恢复家庭",
            onClick = onCommit,
            enabled = !ui.busy && ui.recoveryStatus in setOf("ready_to_commit", "committed") &&
                rootPassword.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (canCancelDisasterRecovery(ui)) {
        LeziSecondaryButton(
            if (ui.cancellingRecovery) "正在取消…" else "取消恢复批次",
            onClick = onCancel,
            enabled = !ui.cancellingRecovery,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RecoveryRootPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("新服务器管理员根密码") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatRecoveryBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}
