package com.lezi.babylog.feature.family.members

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
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
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTextButtonTone
import com.lezi.babylog.designsystem.LeziTextField
import com.lezi.babylog.feature.family.components.FamilyDialog
import com.lezi.babylog.feature.family.components.FamilyDestructiveAction
import com.lezi.babylog.feature.family.components.FamilyDestructiveConfirmPresentation
import com.lezi.babylog.feature.family.components.SecureWindowWhileVisible
import com.lezi.babylog.feature.family.components.canConfirmFamilyDeletion
import com.lezi.babylog.feature.family.components.familyDestructiveConfirmPresentation
import com.lezi.babylog.sync.DeviceRemovedCleanupReceipt
import com.lezi.babylog.sync.SourceCommandLogoutConsent
import com.lezi.babylog.sync.SourceCommandLogoutState
import com.lezi.babylog.sync.qr.MemberLoginQrCode
import com.lezi.babylog.sync.qr.MemberLoginQrContentCodec
import java.time.Instant
import java.util.Locale
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
                LeziTextField(
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
            LeziTextButton(label = if (saving) "保存中…" else "保存", onClick = onConfirm, enabled = !saving, tone = LeziTextButtonTone.Primary)
        },
        dismissButton = {
            LeziTextButton(label = "取消", onClick = onDismiss, enabled = !saving)
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
                LeziTextField(
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
            LeziTextButton(label = if (saving) "保存中…" else confirmCopy, onClick = onConfirm, enabled = !saving)
        },
        dismissButton = {
            LeziTextButton(label = "取消", onClick = onDismiss, enabled = !saving)
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
        confirmButton = { LeziTextButton(label = "完成", onClick = onDismiss) },
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
            LeziTextButton(label = presentation.label, onClick = onConfirm, enabled = presentation.enabled, tone = LeziTextButtonTone.Destructive)
        },
        dismissButton = {
            LeziTextButton(label = "取消", onClick = onDismiss, enabled = presentation.dismissible)
        },
    )
}


@Composable
internal fun LogoutCurrentDeviceDialog(
    sourcePreview: SourceCommandLogoutPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    pendingPublishCount: Int? = null,
    syncChecking: Boolean = false,
    syncFeedback: String? = null,
    onSyncFirst: () -> Unit = {},
) {
    val presentation = logoutDeviceConfirmPresentation(busy, pendingPublishCount, sourcePreview)
    val disclosure = pendingPublishCount?.let(::logoutPendingDisclosure)
    LeziAlertDialog(
        onDismissRequest = { if (presentation.dismissible) onDismiss() },
        title = { Text("退出这台设备？") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                Text(
                    "服务器确认退出后，本机会清除家庭记录、待同步内容、照片、服务器信任和登录信息。家庭成员身份及其他设备不受影响。",
                )
                if (disclosure != null) {
                    Text(
                        disclosure,
                        style = LeziTypography.BodyStrong,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
                val sourceDisclosure = when (sourcePreview) {
                    SourceCommandLogoutPreview.Checking -> "正在核对待确认的来源操作…"
                    is SourceCommandLogoutPreview.Failed -> sourcePreview.message
                    is SourceCommandLogoutPreview.Ready ->
                        sourcePreview.consent?.let(::logoutSourceCommandDisclosure)
                }
                if (sourceDisclosure != null) {
                    Text(
                        sourceDisclosure,
                        style = LeziTypography.BodyStrong,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (syncFeedback != null) {
                    Text(
                        syncFeedback,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
            }
        },
        confirmButton = {
            LeziTextButton(
                label = presentation.label,
                onClick = onConfirm,
                enabled = presentation.enabled,
                tone = LeziTextButtonTone.Destructive,
            )
        },
        dismissButton = {
            if (disclosure == null) {
                LeziTextButton(label = "取消", onClick = onDismiss, enabled = presentation.dismissible)
            } else {
                // Confirm-not-block: the sync-first chance sits beside 取消 and
                // 仍然退出 stays enabled even while the server is unreachable.
                Row(horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                    LeziTextButton(
                        label = "取消",
                        onClick = onDismiss,
                        enabled = presentation.dismissible,
                    )
                    LeziTextButton(
                        label = if (syncChecking) LOGOUT_SYNC_FIRST_BUSY_LABEL else LOGOUT_SYNC_FIRST_LABEL,
                        onClick = onSyncFirst,
                        enabled = presentation.dismissible && !syncChecking,
                    )
                }
            }
        },
    )
}

/** Quantified loss disclosure before a logout that will permanently drop dirty rows. */
internal fun logoutPendingDisclosure(count: Int): String? =
    if (count > 0) {
        "还有 $count 条未同步，退出后将永久丢弃"
    } else {
        null
    }

internal const val LOGOUT_SYNC_FIRST_LABEL = "先同步再检查"

internal const val LOGOUT_SYNC_FIRST_BUSY_LABEL = "正在同步…"

/** Honest inline copy when the sync-first round fails; never blocks 仍然退出. */
internal const val LOGOUT_SYNC_FIRST_FAILED =
    "同步未完成，家庭服务器可能不可连接；计数未变，仍然退出会丢弃这些内容"

/**
 * 退出这台设备 keeps its plain destructive label until the pending disclosure is
 * actually shown; with a visible count it reads 「仍然退出」 — confirm, not block.
 */
internal fun logoutDeviceConfirmPresentation(
    busy: Boolean,
    pendingPublishCount: Int?,
    sourcePreview: SourceCommandLogoutPreview,
): FamilyDestructiveConfirmPresentation {
    val base = familyDestructiveConfirmPresentation(
        FamilyDestructiveAction.LogoutDevice,
        busy,
    )
    if (busy) return base
    when (sourcePreview) {
        SourceCommandLogoutPreview.Checking -> return base.copy(label = "正在核对…", enabled = false)
        is SourceCommandLogoutPreview.Failed -> return base.copy(enabled = false)
        is SourceCommandLogoutPreview.Ready -> sourcePreview.consent?.let { consent ->
            return base.copy(
                label = when (consent.state) {
                    SourceCommandLogoutState.Unknown -> "放弃核实并退出"
                    SourceCommandLogoutState.ConfirmedRefreshRequired -> "放弃刷新并退出"
                },
            )
        }
    }
    val hasPendingDisclosure = pendingPublishCount != null && pendingPublishCount > 0
    return if (hasPendingDisclosure) {
        base.copy(label = "仍然退出")
    } else {
        base
    }
}

/** Names only the frozen source preview; logout never claims to undo its server effects. */
internal fun logoutSourceCommandDisclosure(consent: SourceCommandLogoutConsent): String {
    val state = when (consent.state) {
        SourceCommandLogoutState.Unknown -> "来源操作结果尚未确认"
        SourceCommandLogoutState.ConfirmedRefreshRequired -> "来源操作已确认，仍需刷新本机"
    }
    val requests = if (consent.requestIds.isEmpty()) {
        "请求：无法确认历史请求编号"
    } else {
        "请求（${consent.requestIds.size}）：\n${consent.requestIds.joinToString("\n")}"
    }
    val consequence = when (consent.state) {
        SourceCommandLogoutState.Unknown ->
            "退出会放弃上述来源操作的本机核实；不会撤销服务器上可能已经生效的操作。"
        SourceCommandLogoutState.ConfirmedRefreshRequired ->
            "退出会放弃上述来源操作的本机刷新；不会撤销服务器上已经生效的操作。"
    }
    return "$state\n服务器：${consent.serverOrigin ?: "未知的历史服务器"}\n$requests\n$consequence"
}

@Composable
internal fun DeviceRemovedReceiptDialog(
    receipt: DeviceRemovedCleanupReceipt,
    onConfirm: () -> Unit,
) {
    LeziAlertDialog(
        onDismissRequest = onConfirm,
        title = { Text("设备已被家庭管理员移除") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                Text(deviceRemovedReceiptCopy(receipt))
                Text(
                    "已同步的家庭事实仍保留在家庭里，作者显示为“家人”。可重新连接家庭服务器再次加入。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            LeziTextButton(
                label = "我知道了",
                onClick = onConfirm,
                tone = LeziTextButtonTone.Primary,
            )
        },
    )
}

private val REMOVED_RECEIPT_DATE_TIME =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
        .withZone(java.time.ZoneId.systemDefault())

/** One-time loss receipt: when this device was removed and what it cost locally. */
internal fun deviceRemovedReceiptCopy(receipt: DeviceRemovedCleanupReceipt): String {
    val removedAt = REMOVED_RECEIPT_DATE_TIME.format(
        Instant.ofEpochMilli(receipt.removedAtEpochMillis),
    )
    return "该设备于 $removedAt 被家庭管理员移除，已清理 ${receipt.clearedPendingCount} 条未同步内容。"
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
            LeziTextButton(label = presentation.label, onClick = onConfirm, enabled = presentation.enabled, tone = LeziTextButtonTone.Destructive)
        },
        dismissButton = {
            LeziTextButton(label = "取消", onClick = onDismiss, enabled = presentation.dismissible)
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
                    LeziTextField(
                        enabled = !deleting,
                        value = familyNameInput,
                        onValueChange = onFamilyNameInputChange,
                        label = { Text("输入家庭名确认") },
                        singleLine = true,
                        isError = familyNameInput.isNotEmpty() &&
                            familyNameInput.trim() != expectedFamilyName.trim(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LeziTextField(
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
            LeziTextButton(
                label = if (final) {
                    when {
                        expectedFamilyName.isBlank() -> "返回并刷新"
                        deleting -> "正在删除…"
                        else -> "永久删除家庭"
                    }
                } else {
                    "继续"
                },
                onClick = when {
                    final && expectedFamilyName.isBlank() -> onRefreshFamilyInfo
                    final -> onConfirm
                    else -> onContinue
                },
                enabled = !deleting && (
                    expectedFamilyName.isBlank() ||
                        !final ||
                        canConfirmFamilyDeletion(
                            expectedFamilyName,
                            familyNameInput,
                            rootPassword,
                        )
                    ),
                tone = LeziTextButtonTone.Destructive,
            )
        },
        dismissButton = {
            LeziTextButton(label = "取消", onClick = onDismiss, enabled = !deleting)
        },
    )
}


@Composable
internal fun SourceCommandClearNoticeDialog(
    notice: com.lezi.babylog.sync.sourcerelation.SourceCommandClearNotice,
    onConfirm: () -> Unit,
) {
    LeziAlertDialog(onDismissRequest = onConfirm,
        title = { Text("来源选择核对已结束") },
        text = { Text(sourceCommandClearNoticeCopy(notice)) },
        confirmButton = { com.lezi.babylog.designsystem.LeziTextButton(label = "我知道了", onClick = onConfirm) })
}

internal fun sourceCommandClearNoticeCopy(notice: com.lezi.babylog.sync.sourcerelation.SourceCommandClearNotice): String =
    buildString {
        if (notice.unknownCount > 0) append("有 ${notice.unknownCount} 项来源选择的服务器结果未确认。")
        if (notice.confirmedCount > 0) append("有 ${notice.confirmedCount} 项选择已被服务器确认，但未完成本机核对。")
        append("家庭访问结束后，本机核对状态已清除；服务器上的操作不会因此撤销。")
    }
