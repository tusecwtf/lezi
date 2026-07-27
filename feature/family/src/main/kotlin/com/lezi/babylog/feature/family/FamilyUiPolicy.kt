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

/** Create vs join path inside the multi-step family wizard (ticket 04). */
internal enum class FamilyWizardMode { Create, Join }

/** Wizard steps: network only when needed, then identity (create/join). */
internal enum class FamilyWizardStep { Network, Identity }

/** Exactly one family overlay can be active at a time. */
internal sealed interface FamilyDialog {
    data class Wizard(val mode: FamilyWizardMode, val step: FamilyWizardStep) : FamilyDialog
    data object NetworkSettings : FamilyDialog
    data object MembersList : FamilyDialog
    data object EditMyDisplayName : FamilyDialog
    data object RenameFamily : FamilyDialog
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

/** Overview primary CTA copy (S1). Scan lives inside the join wizard, not as a split CTA. */
internal object FamilyPrimaryCta {
    const val CREATE = "新建家庭"
    const val JOIN = "加入家庭"
    const val INVITE = "邀请家人"
}

/** Where the family wizard should open given current home-LAN readiness. */
internal fun familyWizardInitialStep(networkConfigured: Boolean): FamilyWizardStep =
    if (networkConfigured) FamilyWizardStep.Identity else FamilyWizardStep.Network

internal fun familyWizardTitle(mode: FamilyWizardMode, step: FamilyWizardStep): String = when (step) {
    FamilyWizardStep.Network -> "配置家庭网络"
    FamilyWizardStep.Identity -> when (mode) {
        FamilyWizardMode.Create -> FamilyPrimaryCta.CREATE
        FamilyWizardMode.Join -> FamilyPrimaryCta.JOIN
    }
}

/** Step chip labels for the wizard chrome (1/2 network → identity). */
internal fun familyWizardProgress(
    mode: FamilyWizardMode,
    step: FamilyWizardStep,
    networkConfigured: Boolean,
): Pair<String, String> {
    val networkDone = networkConfigured || step == FamilyWizardStep.Identity
    val step1 = if (networkDone) "✓ 家庭网络" else "1 家庭网络"
    val step2 = when {
        step == FamilyWizardStep.Identity -> when (mode) {
            FamilyWizardMode.Create -> "2 新建家庭"
            FamilyWizardMode.Join -> "2 加入家庭"
        }
        else -> when (mode) {
            FamilyWizardMode.Create -> "2 新建家庭"
            FamilyWizardMode.Join -> "2 加入家庭"
        }
    }
    return step1 to step2
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

/**
 * Result-oriented sync phrase for the account **overview** family card (S1).
 * Never exposes Idle / BlockedOfflineHome / SSID / host / device id.
 */
internal fun overviewSyncStatusLabel(
    status: SyncStatus,
    isJoined: Boolean,
): String = when {
    !isJoined || status == SyncStatus.Disabled -> "还没和家人一起记"
    status == SyncStatus.Syncing -> "正在同步…"
    status == SyncStatus.BlockedOfflineHome -> "连上家里 Wi‑Fi 后才能同步"
    status == SyncStatus.Idle -> "家人记录已对齐"
    status == SyncStatus.Error -> "同步遇到问题"
    else -> "同步遇到问题"
}

/**
 * Detailed labels for the **network settings** sheet only (ops / troubleshooting).
 * Overview must use [overviewSyncStatusLabel] instead.
 */
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

internal fun familyStorageCopy(enabled: Boolean): String =
    if (enabled) "本机 + 家庭服务器" else "仅本机"

/** Controls for network settings ops (invite stays overview-primary; leave/delete live here). */
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

/**
 * Overview primary surface: unjoined create/join wizard CTAs and owner「邀请家人」.
 * Sync / leave / delete never appear here — they live in network settings.
 * Scan is not a separate overview CTA; it lives inside the join wizard.
 */
internal data class FamilyPrimarySurface(
    val compactJoined: Boolean,
    val showCreateJoin: Boolean,
    val showInvite: Boolean,
    val createLabel: String = FamilyPrimaryCta.CREATE,
    val joinLabel: String = FamilyPrimaryCta.JOIN,
    val inviteLabel: String = FamilyPrimaryCta.INVITE,
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
        createLabel = FamilyPrimaryCta.CREATE,
        joinLabel = FamilyPrimaryCta.JOIN,
        inviteLabel = FamilyPrimaryCta.INVITE,
    )
}

/** Pure overview card projection for S1 unit tests (no Compose). */
internal data class FamilyOverviewCard(
    val familyNameLabel: String,
    val memberCountLabel: String,
    val selfTitle: String,
    val syncStatusLabel: String,
    val showCreateJoin: Boolean,
    val showInvite: Boolean,
    val showMembersEntry: Boolean,
    val showRenameFamily: Boolean,
)

internal fun buildFamilyOverviewCard(
    isJoined: Boolean,
    role: FamilyRole,
    networkConfigured: Boolean,
    familyName: String?,
    babyNickname: String?,
    localDisplayName: String,
    memberCount: Int,
    membersLoaded: Boolean,
    status: SyncStatus,
): FamilyOverviewCard {
    val primary = familyPrimarySurface(isJoined, role, networkConfigured)
    return FamilyOverviewCard(
        familyNameLabel = if (isJoined) {
            displayFamilyName(familyName, babyNickname)
        } else {
            "家庭"
        },
        memberCountLabel = if (isJoined) {
            familyMemberCountLabel(memberCount, membersLoaded)
        } else {
            "尚未加入"
        },
        selfTitle = if (isJoined) {
            overviewSelfTitle(localDisplayName, role)
        } else {
            ""
        },
        syncStatusLabel = overviewSyncStatusLabel(status, isJoined),
        showCreateJoin = primary.showCreateJoin,
        showInvite = primary.showInvite,
        showMembersEntry = isJoined,
        showRenameFamily = isJoined && role == FamilyRole.Owner,
    )
}

/** True when [text] looks like overview-forbidden network/tech detail. */
internal fun overviewCopyLooksTechnical(text: String): Boolean {
    if (text.isBlank()) return false
    val lower = text.lowercase()
    if (looksTechnicalDetail(text)) return true
    if (Regex("""\b(idle|blockedofflinehome|syncing|disabled)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    ) {
        return true
    }
    if (text.contains("空闲") || text.contains("SSID") || text.contains("ssid")) return true
    if (text.contains("设备 ID") || text.contains("设备ID")) return true
    // host:port or raw IPv4-ish fragments that must not land on overview phrases
    if (Regex("""\d{1,3}(\.\d{1,3}){3}""").containsMatchIn(text)) return true
    if (lower.contains("http://") || lower.contains("https://")) return true
    return false
}

internal fun familyRoleLabel(role: FamilyRole): String = when (role) {
    FamilyRole.Owner -> "管理员"
    FamilyRole.Member -> "成员"
    FamilyRole.None -> "未加入"
}

internal fun familyMemberDisplayName(member: FamilyMember): String =
    member.displayName.trim()
        .takeIf { member.isSelf || it != LOCAL_FAMILY_DISPLAY_NAME }
        ?: if (member.role == FamilyRole.Owner) "家庭管理员" else "家庭成员"

/** Owner ★ marker for list / overview lines (never applied to non-owners). */
internal fun familyMemberTitle(member: FamilyMember): String {
    val name = familyMemberDisplayName(member)
    return if (member.role == FamilyRole.Owner) "$name ★" else name
}

/** Overview self line: local cache name + admin ★. */
internal fun overviewSelfTitle(displayName: String, role: FamilyRole): String {
    val name = displayName.trim().ifBlank { LOCAL_FAMILY_DISPLAY_NAME }
    val safe = if (name == LOCAL_FAMILY_DISPLAY_NAME && role == FamilyRole.Owner) {
        "家庭管理员"
    } else if (name == LOCAL_FAMILY_DISPLAY_NAME) {
        "家庭成员"
    } else {
        name
    }
    return if (role == FamilyRole.Owner) "$safe ★" else safe
}

/**
 * Client-side gate for create/join/self-rename free-text 称呼.
 * Delegates to sync [com.lezi.babylog.sync.memberDisplayNameValidationError] so account
 * wizard and onboarding share one rule set (no third form stack).
 */
internal fun validateFamilyDisplayNameInput(raw: String): String? =
    com.lezi.babylog.sync.memberDisplayNameValidationError(raw)

/**
 * Shared family name for display. Empty →「我的家庭」or「{宝宝昵称}的家庭」.
 */
internal fun displayFamilyName(
    familyName: String?,
    babyNickname: String? = null,
): String {
    val shared = familyName?.trim().orEmpty()
    if (shared.isNotEmpty()) return shared
    val baby = babyNickname?.trim().orEmpty()
    return if (baby.isNotEmpty()) "${baby}的家庭" else "我的家庭"
}

/** Optional family name on create/rename; blank is allowed (server stores null). */
internal fun validateFamilyNameInput(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.any { it.isISOControl() }) return "家庭名不能包含控制字符"
    if (trimmed.codePointCount(0, trimmed.length) > 64) return "家庭名最多 64 个字符"
    return null
}

/** Member-count entry label on the family card (e.g.「3 位家人」). */
internal fun familyMemberCountLabel(visibleCount: Int, loaded: Boolean): String = if (loaded) {
    "$visibleCount 位家人"
} else {
    "查看家人"
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
    localMembershipId: String,
    membersLoaded: Boolean,
): List<FamilyMember> {
    val bounded = members.take(50)
    if (bounded.any(FamilyMember::isSelf)) return bounded
    if (membersLoaded) return bounded
    val membershipId = localMembershipId.trim()
    if (membershipId.isEmpty()) return bounded
    return listOf(
        FamilyMember(
            displayName = localDisplayName.ifBlank { LOCAL_FAMILY_DISPLAY_NAME },
            role = localRole.takeUnless { it == FamilyRole.None } ?: FamilyRole.Member,
            isSelf = true,
            membershipId = membershipId,
        ),
    ) + bounded
}

/** Network-sheet compact status (not for overview). */
internal fun compactSyncStatusLabel(status: SyncStatus, isJoined: Boolean): String = when {
    !isJoined -> "等待完成网络配置"
    status == SyncStatus.BlockedOfflineHome -> "等待家庭 Wi-Fi"
    status == SyncStatus.Idle -> "已就绪"
    status == SyncStatus.Syncing -> "同步中"
    status == SyncStatus.Error -> "需要重试"
    else -> "等待同步"
}

internal fun formatLastSuccessAt(lastSuccessAt: Long?): String =
    lastSuccessAt?.let {
        "上次成功 · ${java.text.DateFormat.getDateTimeInstance().format(it)}"
    } ?: "尚无成功同步"
