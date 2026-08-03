package com.lezi.babylog.feature.family.members

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.feature.family.components.canRemoveFamilyMember
import com.lezi.babylog.feature.family.components.familyMemberDisplayName
import com.lezi.babylog.feature.family.components.familyMemberSummary
import com.lezi.babylog.feature.family.components.familyMemberTitle
import com.lezi.babylog.feature.family.components.familyMembersForDisplay
import com.lezi.babylog.feature.family.components.familyRoleLabel
import com.lezi.babylog.feature.family.components.normalizedFamilyDisplayNameKey
import com.lezi.babylog.sync.FamilyDevice
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.backend.MemberLoginStatus
import com.lezi.babylog.sync.backend.PendingMemberLoginRequest
import com.lezi.babylog.sync.backend.PendingMemberRenameRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Secondary member roster opened from the family-card count entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FamilyMembersListSheet(
    ui: MembersDevicesUi,
    onRefreshMembers: () -> Unit,
    onEditMyDisplayName: () -> Unit,
    onRenameFamily: (() -> Unit)? = null,
    onRemoveMember: ((membershipId: String, displayName: String) -> Unit)? = null,
    onReviewPending: ((PendingMemberLoginRequest) -> Unit)? = null,
    onCreateMemberLoginQr: ((membershipId: String) -> Unit)? = null,
    onAddMember: (() -> Unit)? = null,
    onRenameMember: ((membershipId: String, displayName: String) -> Unit)? = null,
    onRenameDevice: ((deviceId: String, deviceName: String) -> Unit)? = null,
    onRevokeDevice: ((deviceId: String, deviceName: String, isCurrent: Boolean) -> Unit)? = null,
    onReviewRename: ((PendingMemberRenameRequest, approve: Boolean) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val visibleMembers = familyMembersForDisplay(
        ui.members,
        ui.displayName,
        ui.role,
        ui.membershipId,
        ui.membersLoaded,
    )
    val viewerIsOwner = ui.role == FamilyRole.Owner
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LeziSpacing.Page)
                .padding(bottom = LeziSpacing.Xxl),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("家庭成员与设备", style = LeziTypography.TitleSm)
                    Text(
                        familyMemberSummary(visibleMembers.size, ui.role, ui.membersLoaded),
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (viewerIsOwner && onAddMember != null) {
                        TextButton(onClick = onAddMember) { Text("添加成员") }
                    }
                    if (viewerIsOwner && onRenameFamily != null) {
                        TextButton(onClick = onRenameFamily) { Text("修改家庭名") }
                    }
                    TextButton(onClick = onRefreshMembers, enabled = !ui.membersLoading) {
                        Text(if (ui.membersLoading) "刷新中…" else "刷新")
                    }
                }
            }
            if (viewerIsOwner && ui.pendingMemberRequests.isNotEmpty()) {
                Text(
                    "设备登录申请（${ui.pendingMemberRequests.size}）",
                    style = LeziTypography.BodyStrong,
                    modifier = Modifier.padding(top = LeziSpacing.Sm),
                )
                ui.pendingMemberRequests.forEach { request ->
                    PendingMemberLoginRow(
                        request = request,
                        onReview = onReviewPending?.let { review ->
                            { review(request) }
                        },
                    )
                }
            }
            if (viewerIsOwner && ui.pendingMemberRenameRequests.isNotEmpty()) {
                Text(
                    "待处理改名（${ui.pendingMemberRenameRequests.size}）",
                    style = LeziTypography.BodyStrong,
                    modifier = Modifier.padding(top = LeziSpacing.Sm),
                )
                ui.pendingMemberRenameRequests.forEach { request ->
                    PendingMemberRenameRow(
                        request = request,
                        onApprove = onReviewRename?.let { review ->
                            { review(request, true) }
                        },
                        onReject = onReviewRename?.let { review ->
                            { review(request, false) }
                        },
                    )
                }
            }
            if (visibleMembers.isEmpty() && ui.membersLoaded && ui.membersError == null) {
                Text(
                    "暂时没有可显示的家庭成员",
                    style = LeziTypography.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = LeziSpacing.Sm),
                )
            }
            visibleMembers.forEach { member ->
                val privacyProjectedMember = if (viewerIsOwner || member.isSelf) {
                    member
                } else {
                    member.copy(devices = null)
                }
                FamilyMemberRow(
                    member = privacyProjectedMember,
                    onEditSelf = onEditMyDisplayName.takeIf { member.isSelf },
                    onRemove = onRemoveMember
                        ?.takeIf { canRemoveFamilyMember(viewerIsOwner, member) }
                        ?.let { remove ->
                            {
                                remove(
                                    member.membershipId,
                                    familyMemberDisplayName(member),
                                )
                            }
                        },
                    onCreateLoginQr = onCreateMemberLoginQr
                        ?.takeIf { viewerIsOwner && member.role == FamilyRole.Member }
                        ?.let { create -> { create(member.membershipId) } },
                    onRenameMember = onRenameMember
                        ?.takeIf { viewerIsOwner && !member.isSelf }
                        ?.let { rename ->
                            { rename(member.membershipId, familyMemberDisplayName(member)) }
                        },
                    onRenameDevice = onRenameDevice.takeIf { viewerIsOwner || member.isSelf },
                    onRevokeDevice = onRevokeDevice.takeIf { viewerIsOwner },
                )
            }
            ui.membersError?.let { error ->
                Text(
                    error,
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = LeziSpacing.Xs),
                )
            } ?: if (!ui.membersLoaded && !ui.membersLoading) {
                Text(
                    "暂时无法读取成员与设备，请重试",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = LeziSpacing.Xs),
                )
            } else Unit
        }
    }
}

@Composable
private fun PendingMemberRenameRow(
    request: PendingMemberRenameRequest,
    onApprove: (() -> Unit)?,
    onReject: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LeziSpacing.Xs),
        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
    ) {
        Text(
            "${request.currentDisplayName} → ${request.requestedDisplayName}",
            style = LeziTypography.BodyStrong,
        )
        Text(
            formatPendingRequestTime(request.createdAtEpochSeconds),
            style = LeziTypography.Meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs)) {
            TextButton(onClick = { onApprove?.invoke() }, enabled = onApprove != null) {
                Text("确认改名")
            }
            TextButton(onClick = { onReject?.invoke() }, enabled = onReject != null) {
                Text("拒绝")
            }
        }
    }
}

@Composable
private fun PendingMemberLoginRow(
    request: PendingMemberLoginRequest,
    onReview: (() -> Unit)?,
) {
    val awaitingClaim = request.status == MemberLoginStatus.Approved
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LeziSpacing.Xs),
        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
    ) {
        Text(request.displayName, style = LeziTypography.BodyStrong)
        Text(
            "${request.deviceName} · ${formatPendingRequestTime(request.createdAtEpochSeconds)}",
            style = LeziTypography.Meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (awaitingClaim) {
            Text(
                "已批准，等待对方领取",
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        TextButton(onClick = { onReview?.invoke() }, enabled = onReview != null) {
            Text(if (awaitingClaim) "撤销批准" else "处理申请")
        }
    }
}

@Composable
internal fun PendingMemberDecisionDialog(
    request: PendingMemberLoginRequest,
    members: List<FamilyMember>,
    busy: Boolean,
    onBindExisting: (membershipId: String) -> Unit,
    onApproveNew: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
) {
    val awaitingClaim = request.status == MemberLoginStatus.Approved
    val existingMembers = members.filter {
        it.role == FamilyRole.Member && it.membershipId.isNotBlank()
    }
    val requestedKey = normalizedFamilyDisplayNameKey(request.displayName)
    val conflictsWithExisting = existingMembers.any {
        normalizedFamilyDisplayNameKey(it.displayName) == requestedKey
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (awaitingClaim) "等待设备领取" else "确认这台设备") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
            ) {
                Text("对方声明称呼：${request.displayName}")
                Text("设备：${request.deviceName}")
                if (awaitingClaim) {
                    Text(
                        "对方领取前将保留家庭称呼；如果这台设备无法领取，可撤销批准并释放称呼。",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (existingMembers.isEmpty()) {
                    Text(
                        "当前没有可绑定的普通成员",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    existingMembers.forEach { member ->
                        TextButton(
                            onClick = { onBindExisting(member.membershipId) },
                            enabled = !busy,
                        ) {
                            Text("绑定到现有「${member.displayName}」")
                        }
                    }
                }
                if (awaitingClaim) {
                    TextButton(onClick = onReject, enabled = !busy) {
                        Text("撤销批准")
                    }
                } else {
                    TextButton(onClick = onApproveNew, enabled = !busy && !conflictsWithExisting) {
                        Text("用此称呼添加新成员")
                    }
                    if (conflictsWithExisting) {
                        Text(
                            "该家庭称呼已存在，请绑定到现有成员",
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = onReject, enabled = !busy) {
                        Text("拒绝")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(if (awaitingClaim) "关闭" else "取消")
            }
        },
    )
}

internal fun formatPendingRequestTime(epochSeconds: Long): String =
    formatFamilyDeviceLastUsed(epochSeconds)

@Composable
internal fun FamilyMemberRow(
    member: FamilyMember,
    onEditSelf: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onCreateLoginQr: (() -> Unit)? = null,
    onRenameMember: (() -> Unit)? = null,
    onRenameDevice: ((deviceId: String, deviceName: String) -> Unit)? = null,
    onRevokeDevice: ((deviceId: String, deviceName: String, isCurrent: Boolean) -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(vertical = LeziSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (member.isSelf) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (member.isSelf) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            member.isSelf -> "我"
                            member.role == FamilyRole.Owner -> "管"
                            else -> "员"
                        },
                        style = LeziTypography.Label,
                    )
                }
            }
            Spacer(Modifier.size(LeziSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    familyMemberTitle(member),
                    style = LeziTypography.BodyStrong,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    familyRoleLabel(member.role),
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (member.isSelf && onEditSelf != null) {
                    androidx.compose.material3.TextButton(onClick = onEditSelf) {
                        Text(if (member.role == FamilyRole.Owner) "改称呼" else "申请改称呼")
                    }
                }
                if (!member.isSelf && onRenameMember != null) {
                    androidx.compose.material3.TextButton(onClick = onRenameMember) {
                        Text("改称呼")
                    }
                }
                if (onCreateLoginQr != null) {
                    androidx.compose.material3.TextButton(onClick = onCreateLoginQr) {
                        Text("为这个成员生成登录二维码")
                    }
                }
                if (!member.isSelf && onRemove != null) {
                    androidx.compose.material3.TextButton(onClick = onRemove) {
                        Text(
                            "移除",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        member.devices?.let { devices ->
            if (devices.isEmpty()) {
                Text(
                    "暂无设备",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp, bottom = LeziSpacing.Xs),
                )
            } else {
                devices.forEach { device ->
                    FamilyDeviceRow(
                        device = device,
                        onRename = onRenameDevice?.let { rename ->
                            { rename(device.deviceId, device.deviceName) }
                        },
                        onRevoke = onRevokeDevice?.let { revoke ->
                            { revoke(device.deviceId, device.deviceName, device.isCurrent) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FamilyDeviceRow(
    device: FamilyDevice,
    onRename: (() -> Unit)?,
    onRevoke: (() -> Unit)?,
) {
    val activity = formatFamilyDeviceLastUsed(device.lastUsedAtEpochSeconds)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 56.dp, top = LeziSpacing.Xs, bottom = LeziSpacing.Xs)
            .semantics {
                contentDescription = buildString {
                    append(device.deviceName)
                    if (device.isCurrent) append("，这台设备")
                    append("，")
                    append(activity)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                device.deviceName,
                style = LeziTypography.Body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (device.isCurrent) {
                Text(
                    "这台设备",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                activity,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onRename != null) {
                TextButton(onClick = onRename) { Text("改设备称呼") }
            }
            if (onRevoke != null) {
                TextButton(onClick = onRevoke) {
                    Text("撤销设备", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

internal fun formatFamilyDeviceLastUsed(
    epochSeconds: Long,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
): String {
    val then = epochSeconds.coerceAtLeast(0)
    val delta = (nowEpochSeconds - then).coerceAtLeast(0)
    return when {
        delta < 60 -> "刚刚"
        delta < 3_600 -> "${delta / 60} 分钟前"
        delta < 86_400 -> "${delta / 3_600} 小时前"
        delta < 172_800 -> "昨天"
        delta < 7 * 86_400 -> "${delta / 86_400} 天前"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(then * 1_000L))
    }
}

@Composable
internal fun RemoveMemberConfirmDialog(
    displayName: String,
    removing: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val label = displayName.trim().ifBlank { "家人" }
    AlertDialog(
        onDismissRequest = { if (!removing) onDismiss() },
        title = { Text("删除成员？") },
        text = {
            Text(
                "确认彻底删除「$label」的成员身份和全部设备？对方下次连接会清除本地家庭数据；已同步的家庭事实继续保留，作者显示为“家人”。此操作无法撤销。",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !removing) {
                Text(
                    if (removing) "删除中…" else "删除成员",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !removing) { Text("取消") }
        },
    )
}

