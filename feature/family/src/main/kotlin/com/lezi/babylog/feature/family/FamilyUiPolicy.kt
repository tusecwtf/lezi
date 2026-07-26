package com.lezi.babylog.feature.family

import com.lezi.babylog.core.common.looksTechnicalDetail
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.domain.BabyMergePreview
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.SyncNotEnabledException

/**
 * Local-only display placeholder. Keep this literal aligned with the server-side
 * member projection placeholder; it must never be treated as a caregiver name.
 */
internal const val LOCAL_FAMILY_DISPLAY_NAME = "我（本机）"

internal sealed interface NetworkSaveResult {
    val message: String

    data class Saved(override val message: String) : NetworkSaveResult
    data class Failed(override val message: String) : NetworkSaveResult
}

internal inline fun deliverNetworkSaveResult(
    result: NetworkSaveResult,
    onMessage: (String) -> Unit,
    onSaved: () -> Unit = {},
) {
    onMessage(result.message)
    if (result is NetworkSaveResult.Saved) onSaved()
}

/** Exactly one family overlay can be active at a time. */
internal sealed interface FamilyDialog {
    data object Join : FamilyDialog
    data object NetworkSettings : FamilyDialog
    data object CreateFamily : FamilyDialog
    data class Invite(val invite: FamilyInviteView) : FamilyDialog
    data object ConfirmLeave : FamilyDialog
    data class DeleteFamily(val stage: DeleteStage) : FamilyDialog
    data class HomeWifiAccessGuide(val resume: FamilyDialog? = null) : FamilyDialog
    data class Message(val copy: String, val resume: FamilyDialog? = null) : FamilyDialog
    data class DeleteBaby(val baby: Baby) : FamilyDialog
    data class MergeBaby(val source: Baby) : FamilyDialog
    data class MergePreview(val preview: BabyMergePreview) : FamilyDialog
    data class EditBaby(val baby: Baby) : FamilyDialog

    enum class DeleteStage { Warning, Final }
}

internal fun familyDialogAfterDismiss(dialog: FamilyDialog): FamilyDialog? = when (dialog) {
    is FamilyDialog.Message -> dialog.resume
    is FamilyDialog.HomeWifiAccessGuide -> dialog.resume
    else -> null
}

internal fun familySyncError(error: Throwable, fallback: String): String {
    if (error is SyncNotEnabledException) {
        return "请先填写家庭服务器地址并绑定 Wi‑Fi 名称后加入家庭"
    }
    val message = error.message.orEmpty()
    if (looksTechnicalDetail(message)) {
        return "家庭同步服务暂未连接，请稍后重试"
    }
    return productUiError(error, fallback)
}

internal fun syncStatusLabel(
    status: SyncStatus,
    hasServer: Boolean = false,
    hasSsid: Boolean = false,
    isJoined: Boolean = false,
): String = when (status) {
    SyncStatus.Disabled -> when {
        isJoined -> "未启用"
        !hasServer && !hasSsid -> "未加入家庭（请先保存服务器与 Wi‑Fi 名称）"
        !hasServer -> "未加入家庭（请先保存服务器地址）"
        !hasSsid -> "未加入家庭（请先保存 Wi‑Fi 名称）"
        else -> "网络已配置 · 尚未加入家庭"
    }
    SyncStatus.BlockedOfflineHome -> "等待家庭 Wi‑Fi 或服务器可达"
    SyncStatus.Idle -> "空闲"
    SyncStatus.Syncing -> "同步中"
    SyncStatus.Error -> "同步错误"
}

internal fun canEditFamilyAvatar(role: FamilyRole): Boolean = role != FamilyRole.Member

internal fun familyDeviceId(syncDeviceId: String, localDeviceId: String): String =
    syncDeviceId.ifBlank { localDeviceId }

/** Result-oriented sync line for the account overview card (not network jargon). */
internal fun overviewSyncStatusCopy(
    status: SyncStatus,
    isJoined: Boolean,
): Pair<String, String> = when {
    !isJoined -> "还没和家人一起记" to "新建或加入家庭后即可一起记录"
    status == SyncStatus.Syncing -> "正在同步…" to "家人之间的记录正在对齐"
    status == SyncStatus.BlockedOfflineHome -> "连上家里 Wi‑Fi 后才能同步" to "出门在外时记录会先留在本机"
    status == SyncStatus.Error -> "同步遇到问题" to "可在网络设置中查看并重试"
    status == SyncStatus.Idle -> "家人记录已对齐" to "打开应用或下拉即可更新"
    else -> "等待同步" to "可在网络设置中查看详情"
}

/**
 * Family scope line: babies in this household + member identity for this device.
 * Avoids device IDs and storage-mode jargon on the beginner surface.
 */
internal fun overviewFamilyIdentityCopy(
    isJoined: Boolean,
    babyNicknames: List<String>,
    memberCount: Int,
    membersLoaded: Boolean,
    myDisplayName: String,
    role: FamilyRole,
): Pair<String, String> {
    val babiesTitle = when {
        babyNicknames.isEmpty() -> "暂无宝宝档案"
        babyNicknames.size == 1 -> babyNicknames.first()
        else -> babyNicknames.joinToString("、")
    }
    val selfLabel = myDisplayName.trim().ifBlank { LOCAL_FAMILY_DISPLAY_NAME }
    val rolePart = when (role) {
        FamilyRole.Owner -> "管理员 ★"
        FamilyRole.Member -> "成员"
        FamilyRole.None -> "未加入"
    }
    val identityDetail = if (!isJoined) {
        "仅本机 · 还没有家人一起记"
    } else {
        val people = if (membersLoaded) {
            "$memberCount 位家人"
        } else {
            "家人待刷新"
        }
        "$people · 我是$selfLabel（$rolePart）"
    }
    return babiesTitle to identityDetail
}

internal data class FamilyControlVisibility(
    val showJoin: Boolean,
    val showCreateFamily: Boolean,
    val showInvite: Boolean,
    val showJoinedActions: Boolean,
    val showLeave: Boolean,
)

internal fun familyControlVisibility(
    isJoined: Boolean,
    role: FamilyRole,
): FamilyControlVisibility = FamilyControlVisibility(
    showJoin = !isJoined,
    showCreateFamily = !isJoined && role == FamilyRole.None,
    showInvite = isJoined && role == FamilyRole.Owner,
    showJoinedActions = isJoined,
    showLeave = isJoined && role == FamilyRole.Member,
)

internal fun isHomeLanNetworkConfigured(
    serverHost: String,
    baseUrl: String,
    allowedSsids: List<String>,
): Boolean =
    (serverHost.isNotBlank() || baseUrl.isNotBlank()) && allowedSsids.isNotEmpty()

internal data class FamilyPrimarySurface(
    val compactJoined: Boolean,
    val showCreateJoin: Boolean,
    val showInvite: Boolean,
    val showJoinedActions: Boolean,
    val showLeave: Boolean,
)

internal fun familyPrimarySurface(
    isJoined: Boolean,
    role: FamilyRole,
    networkConfigured: Boolean,
): FamilyPrimarySurface {
    val controls = familyControlVisibility(isJoined, role)
    return FamilyPrimarySurface(
        compactJoined = isJoined && networkConfigured,
        showCreateJoin = controls.showJoin || controls.showCreateFamily,
        showInvite = controls.showInvite,
        showJoinedActions = controls.showJoinedActions,
        showLeave = controls.showLeave,
    )
}

internal fun familyRoleLabel(role: FamilyRole): String = when (role) {
    FamilyRole.Owner -> "管理员"
    FamilyRole.Member -> "成员"
    FamilyRole.None -> "未加入"
}

internal fun familyMemberDisplayName(member: FamilyMember): String =
    member.displayName
        ?.trim()
        ?.takeIf { it.isNotEmpty() && (member.isSelf || it != LOCAL_FAMILY_DISPLAY_NAME) }
        ?: when {
            member.isSelf -> LOCAL_FAMILY_DISPLAY_NAME
            member.role == FamilyRole.Owner -> "家庭管理员"
            else -> "家庭成员"
        }

internal fun familyMemberSummary(
    visibleCount: Int,
    role: FamilyRole,
    loaded: Boolean,
): String = if (loaded) {
    "$visibleCount 位 · ${familyRoleLabel(role)}"
} else {
    "待刷新 · ${familyRoleLabel(role)}"
}

internal fun familyMembersForDisplay(
    members: List<FamilyMember>,
    localDisplayName: String,
    localRole: FamilyRole,
    membersLoaded: Boolean,
): List<FamilyMember> {
    val bounded = members.take(50)
    if (bounded.any(FamilyMember::isSelf)) return bounded
    if (membersLoaded) return bounded
    return listOf(
        FamilyMember(
            displayName = localDisplayName.ifBlank { LOCAL_FAMILY_DISPLAY_NAME },
            role = localRole.takeUnless { it == FamilyRole.None } ?: FamilyRole.Member,
            isSelf = true,
        ),
    ) + bounded
}

internal fun compactSyncStatusLabel(status: SyncStatus, isJoined: Boolean): String = when {
    !isJoined -> "等待完成前两步"
    status == SyncStatus.BlockedOfflineHome -> "等待家庭 Wi-Fi"
    status == SyncStatus.Idle -> "已就绪"
    status == SyncStatus.Syncing -> "同步中"
    status == SyncStatus.Error -> "需要重试"
    else -> "等待同步"
}
