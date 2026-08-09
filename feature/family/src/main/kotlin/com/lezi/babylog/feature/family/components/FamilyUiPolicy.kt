package com.lezi.babylog.feature.family.components

import com.lezi.babylog.core.common.SingleFlightAction
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.domain.BabyMergePreview
import com.lezi.babylog.domain.family.FamilyWizardEntry
import com.lezi.babylog.domain.family.FamilyWizardOutcome
import com.lezi.babylog.domain.family.FamilyWizardSnapshot
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.session.LOCAL_DEVICE_DISPLAY_NAME
import com.lezi.babylog.sync.backend.PendingMemberLoginRequest
import com.lezi.babylog.sync.qr.MemberLoginQrCode as SyncMemberLoginQrCode
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

/**
 * Local-only display placeholder. Same literal as [LOCAL_DEVICE_DISPLAY_NAME];
 * must never be treated as a caregiver name.
 */
internal const val LOCAL_FAMILY_DISPLAY_NAME = LOCAL_DEVICE_DISPLAY_NAME

/** UI aliases; the authoritative wizard enums live in domain and are shared with onboarding. */
internal typealias FamilyWizardMode = com.lezi.babylog.domain.family.FamilyWizardMode
internal typealias FamilyWizardStep = com.lezi.babylog.domain.family.FamilyWizardStep
internal typealias FamilyWizardJoinRole = com.lezi.babylog.domain.family.FamilyWizardJoinRole

internal fun accountFamilyActions(): List<String> = listOf(FamilyPrimaryCta.CONNECT)

internal fun accountFamilyWizardSnapshot(
    mode: FamilyWizardMode,
    step: FamilyWizardStep,
    draft: com.lezi.babylog.sync.session.FamilyEndpointDraft,
    displayName: String,
    familyName: String = "",
    deviceName: String = "",
    joinRole: FamilyWizardJoinRole? = null,
): FamilyWizardSnapshot = FamilyWizardSnapshot.fromDraft(
    entry = FamilyWizardEntry.Account,
    mode = mode,
    step = step,
    draft = draft,
    displayName = displayName,
    familyName = familyName,
    deviceName = deviceName,
).copy(joinRole = joinRole)

/**
 * Whether the account family card should show the optional self-hosted update banner.
 * Pure policy for unit tests: joined + non-null metadata only.
 */
internal fun shouldShowOptionalAppUpdateBanner(
    isJoined: Boolean,
    optionalAppUpdate: com.lezi.babylog.sync.AppUpdateMetadata?,
): Boolean = isJoined && optionalAppUpdate != null

internal fun familyWizardOutcomeCopy(outcome: FamilyWizardOutcome): String = when (outcome) {
    is FamilyWizardOutcome.Created -> when (outcome.dataRecovery) {
        InitialFamilyDataRecovery.Complete -> "家庭已创建"
        InitialFamilyDataRecovery.RetryRequired -> "家庭已创建；首次同步失败，可稍后重试"
        InitialFamilyDataRecovery.NotRequired -> "家庭已创建，正在首次同步"
    }
    is FamilyWizardOutcome.Reclaimed -> when (outcome.dataRecovery) {
        InitialFamilyDataRecovery.Complete -> "已接回家庭，数据恢复完成"
        InitialFamilyDataRecovery.RetryRequired ->
            "已接回家庭，但数据同步失败，请在记录、汇总或成长页下拉重试"
        InitialFamilyDataRecovery.NotRequired -> "已接回家庭，正在同步数据"
    }
    is FamilyWizardOutcome.OwnerLoggedIn -> when (outcome.dataRecovery) {
        InitialFamilyDataRecovery.Complete -> "管理员设备已登录"
        InitialFamilyDataRecovery.RetryRequired -> "管理员设备已登录；首次同步失败，可稍后重试"
        InitialFamilyDataRecovery.NotRequired -> "管理员设备已登录，正在首次同步"
    }
    is FamilyWizardOutcome.MemberApproved -> when (outcome.dataRecovery) {
        InitialFamilyDataRecovery.Complete -> "管理员已确认，家庭数据同步完成"
        InitialFamilyDataRecovery.RetryRequired -> "管理员已确认；首次同步失败，可稍后重试"
        InitialFamilyDataRecovery.NotRequired -> "管理员已确认，正在首次同步"
    }
    is FamilyWizardOutcome.MemberLoginQrClaimed -> when (outcome.dataRecovery) {
        InitialFamilyDataRecovery.Complete -> "已在这台设备登录家庭"
        InitialFamilyDataRecovery.RetryRequired -> "已登录；首次同步失败，请重试"
        InitialFamilyDataRecovery.NotRequired -> "已在这台设备登录家庭，正在首次同步"
    }
}

/** Exactly one family overlay can be active at a time. */
internal sealed interface FamilyDialog {
    data object ConnectEndpoint : FamilyDialog
    data class Wizard(val mode: FamilyWizardMode, val step: FamilyWizardStep) : FamilyDialog
    data object OwnerTakeoverConfirm : FamilyDialog
    data object MembersList : FamilyDialog
    data class ReviewPendingMember(val request: PendingMemberLoginRequest) : FamilyDialog
    data class MemberLoginQrCode(val code: SyncMemberLoginQrCode) : FamilyDialog
    data object EditMyDisplayName : FamilyDialog
    data object AddFamilyMember : FamilyDialog
    data class RenameFamilyMember(
        val membershipId: String,
        val currentDisplayName: String,
    ) : FamilyDialog
    data class RenameFamilyDevice(
        val deviceId: String,
        val currentDeviceName: String,
    ) : FamilyDialog
    data class ConfirmDeviceRevoke(
        val deviceId: String,
        val deviceName: String,
        val isCurrent: Boolean,
    ) : FamilyDialog
    data object ConfirmDeviceLogout : FamilyDialog
    data object RenameFamily : FamilyDialog
    data object ConfirmLeave : FamilyDialog
    /** Owner confirms removing another member (not self). */
    data class ConfirmRemoveMember(
        val membershipId: String,
        val displayName: String,
    ) : FamilyDialog
    data class DeleteFamily(val stage: DeleteStage) : FamilyDialog
    data class Message(val copy: String, val resume: FamilyDialog? = null) : FamilyDialog
    data class DeleteBaby(val baby: Baby) : FamilyDialog
    data class MergeBaby(val source: Baby) : FamilyDialog
    data class MergePreview(val preview: BabyMergePreview) : FamilyDialog
    data class EditBaby(val baby: Baby) : FamilyDialog
    data object AddBaby : FamilyDialog

    enum class DeleteStage { Warning, Final }
}

internal enum class FamilyDestructiveAction {
    LeaveFamily,
    LogoutDevice,
    RevokeDevice,
    DeleteBaby,
    MergeBaby,
    DeleteFamily,
}

internal data class FamilyDestructiveConfirmPresentation(
    val label: String,
    val enabled: Boolean,
    val dismissible: Boolean,
)

internal fun familyDestructiveConfirmPresentation(
    action: FamilyDestructiveAction,
    busy: Boolean,
): FamilyDestructiveConfirmPresentation = FamilyDestructiveConfirmPresentation(
    label = if (busy) {
        when (action) {
            FamilyDestructiveAction.LeaveFamily,
            FamilyDestructiveAction.LogoutDevice,
            -> "退出中…"
            FamilyDestructiveAction.RevokeDevice -> "撤销中…"
            FamilyDestructiveAction.DeleteBaby -> "删除中…"
            FamilyDestructiveAction.MergeBaby -> "合并中…"
            FamilyDestructiveAction.DeleteFamily -> "正在删除…"
        }
    } else {
        when (action) {
            FamilyDestructiveAction.LeaveFamily -> "退出家庭"
            FamilyDestructiveAction.LogoutDevice -> "退出这台设备"
            FamilyDestructiveAction.RevokeDevice -> "确认撤销"
            FamilyDestructiveAction.DeleteBaby -> "删除"
            FamilyDestructiveAction.MergeBaby -> "确认合并"
            FamilyDestructiveAction.DeleteFamily -> "永久删除家庭"
        }
    },
    enabled = !busy,
    dismissible = !busy,
)

/** Feature-owned single-flight seam shared by destructive family hosts. */
internal class FamilyDestructiveActionGate {
    private val action = SingleFlightAction()

    val busy: StateFlow<Boolean> = action.busy

    suspend fun run(block: suspend () -> Unit): Boolean = action.run(block)
}

internal fun normalizedFamilyDisplayNameKey(raw: String): String {
    val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
    return buildString(normalized.length) {
        var pendingSpace = false
        normalized.forEach { character ->
            if (character.isWhitespace()) {
                pendingSpace = isNotEmpty()
            } else {
                if (pendingSpace) append(' ')
                append(character)
                pendingSpace = false
            }
        }
    }.lowercase(Locale.ROOT)
}

internal fun canConfirmFamilyDeletion(
    expectedFamilyName: String,
    enteredFamilyName: String,
    rootPassword: String,
): Boolean = expectedFamilyName.trim().isNotEmpty() &&
    enteredFamilyName.trim() == expectedFamilyName.trim() &&
    rootPassword.isNotBlank()

/**
 * Overview primary CTA copy (S1).
 * Scan is available inside the join wizard Network/Identity steps (Must);
 * overview secondary「扫码加入」is Should and not shipped here.
 */
internal object FamilyPrimaryCta {
    const val CONNECT = "连接家庭服务器"
    const val CREATE = "新建家庭"
    const val JOIN = "加入家庭"
}

/** Where the family wizard should open given current endpoint readiness. */
internal fun familyWizardInitialStep(endpointConfigured: Boolean): FamilyWizardStep =
    if (endpointConfigured) FamilyWizardStep.Identity else FamilyWizardStep.Endpoint

internal fun familyWizardTitle(mode: FamilyWizardMode, step: FamilyWizardStep): String = when (step) {
    FamilyWizardStep.Endpoint -> "配置家庭服务器"
    FamilyWizardStep.Role -> "你要如何加入？"
    FamilyWizardStep.Identity -> when (mode) {
        FamilyWizardMode.Create -> FamilyPrimaryCta.CREATE
        FamilyWizardMode.Join -> FamilyPrimaryCta.JOIN
    }
}

/**
 * Step chip labels for the wizard chrome (1/2 endpoint → identity).
 * [endpointReady] must reflect a real HTTPS endpoint — never true merely
 * because the wizard has moved past the endpoint step.
 */
internal fun familyWizardProgress(
    mode: FamilyWizardMode,
    endpointReady: Boolean,
): Pair<String, String> {
    val step1 = if (endpointReady) "✓ 家庭服务器" else "1 家庭服务器"
    val step2 = when (mode) {
        FamilyWizardMode.Create -> "2 新建家庭"
        FamilyWizardMode.Join -> "2 加入家庭"
    }
    return step1 to step2
}

/**
 * Wizard session remains active while on Wizard or a stack layer that resumes
 * back to Wizard through a message.
 */
internal fun isWizardSessionDialog(dialog: FamilyDialog?): Boolean = when (dialog) {
    FamilyDialog.ConnectEndpoint -> true
    is FamilyDialog.Wizard -> true
    FamilyDialog.OwnerTakeoverConfirm -> true
    is FamilyDialog.Message -> dialog.resume is FamilyDialog.Wizard
    else -> false
}

internal fun familyDialogAfterDismiss(dialog: FamilyDialog): FamilyDialog? = when (dialog) {
    is FamilyDialog.Message -> dialog.resume
    else -> null
}

internal fun familySyncError(error: Throwable, fallback: String): String =
    com.lezi.babylog.sync.session.familySyncError(error, fallback)

/**
 * Result-oriented sync phrase for the account **overview** family card (S1).
 * Never exposes protocol state, endpoint, token, certificate, or device id.
 */
internal fun overviewSyncStatusLabel(
    status: SyncStatus,
    isJoined: Boolean,
    lastSuccessAt: Long? = null,
    waitingForApproval: Boolean = false,
): String = when {
    waitingForApproval -> "等待管理员确认"
    status == SyncStatus.ReauthRequired -> "登录已失效，请重新登录或申请"
    // Offline-mode and other unjoined states: local Room is usable; family path is on account.
    !isJoined || status == SyncStatus.Disabled -> "本机可先记；连接家庭服务器后同步给家人"
    status == SyncStatus.Syncing -> "正在同步…"
    status == SyncStatus.Idle -> formatLastSuccessAt(lastSuccessAt)
    status == SyncStatus.Error -> "同步遇到问题"
    else -> "同步遇到问题"
}

/** Unjoined family-card subtitle (overview only; not network ops). */
internal fun unjoinedFamilyCardSubtitle(): String =
    "本机可先记；新建或加入家庭后同步给家人"

internal fun canEditFamilyAvatar(role: FamilyRole): Boolean = role != FamilyRole.Member

internal fun canManageFamilyBabies(role: FamilyRole): Boolean = role != FamilyRole.Member


/** Controls for family account operations. */
internal data class FamilyControlVisibility(
    val showJoin: Boolean,
    val showCreateFamily: Boolean,
    val showJoinedActions: Boolean,
    /** Owner may remove non-self members from the roster. */
    val showRemoveMember: Boolean,
)

internal fun familyControlVisibility(
    isJoined: Boolean,
    role: FamilyRole,
): FamilyControlVisibility = FamilyControlVisibility(
    showJoin = !isJoined,
    showCreateFamily = !isJoined && role == FamilyRole.None,
    showJoinedActions = isJoined,
    showRemoveMember = isJoined && role == FamilyRole.Owner,
)

/** Whether this roster row can show owner「移除」. Never for self or owner role. */
internal fun canRemoveFamilyMember(
    viewerIsOwner: Boolean,
    member: FamilyMember,
): Boolean =
    viewerIsOwner &&
        !member.isSelf &&
        member.role == FamilyRole.Member &&
        member.membershipId.trim().isNotEmpty()

internal fun isEndpointConfigured(
    serverHost: String,
    baseUrl: String,
): Boolean = serverHost.isNotBlank() || baseUrl.isNotBlank()

/**
 * Overview primary surface: unauthenticated create/login wizard CTAs.
 * Leave and delete remain deliberate account actions; sync lives on data pages.
 * Scan is not a separate overview CTA; it lives inside the join wizard.
 */
internal data class FamilyPrimarySurface(
    val compactJoined: Boolean,
    val showCreateJoin: Boolean,
    val createLabel: String = FamilyPrimaryCta.CREATE,
    val joinLabel: String = FamilyPrimaryCta.JOIN,
)

internal fun familyPrimarySurface(
    isJoined: Boolean,
    role: FamilyRole,
    endpointConfigured: Boolean,
): FamilyPrimarySurface {
    val controls = familyControlVisibility(isJoined, role)
    return FamilyPrimarySurface(
        compactJoined = isJoined && endpointConfigured,
        showCreateJoin = controls.showJoin || controls.showCreateFamily,
        createLabel = FamilyPrimaryCta.CREATE,
        joinLabel = FamilyPrimaryCta.JOIN,
    )
}

/** Pure overview card projection for S1 unit tests (no Compose). */
internal data class FamilyOverviewCard(
    val familyNameLabel: String,
    val memberCountLabel: String,
    val selfTitle: String,
    val syncStatusLabel: String,
    val showCreateJoin: Boolean,
    val showMembersEntry: Boolean,
    val showRenameFamily: Boolean,
)

internal data class FamilyRosterEntryPresentation(
    val label: String,
    val isError: Boolean,
)

internal fun familyRosterEntryPresentation(
    visibleCount: Int,
    loaded: Boolean,
    loading: Boolean,
    error: String?,
): FamilyRosterEntryPresentation = when {
    loading -> FamilyRosterEntryPresentation("正在读取家人…", isError = false)
    error != null -> FamilyRosterEntryPresentation("读取失败，点此重试", isError = true)
    loaded -> FamilyRosterEntryPresentation("$visibleCount 位家人", isError = false)
    else -> FamilyRosterEntryPresentation("正在读取家人…", isError = false)
}

/** Path A: family-name mutation is only offered from the members page. */
internal fun familyNameSupportingCopy(role: FamilyRole): String = "共享家庭名"

internal fun buildFamilyOverviewCard(
    isJoined: Boolean,
    role: FamilyRole,
    endpointConfigured: Boolean,
    familyName: String?,
    babyNickname: String?,
    localDisplayName: String,
    memberCount: Int,
    membersLoaded: Boolean,
    membersLoading: Boolean = false,
    membersError: String? = null,
    status: SyncStatus,
    lastSuccessAt: Long? = null,
    waitingForApproval: Boolean = false,
): FamilyOverviewCard {
    val primary = familyPrimarySurface(isJoined, role, endpointConfigured)
    return FamilyOverviewCard(
        familyNameLabel = if (isJoined) {
            displayFamilyName(familyName, babyNickname)
        } else {
            "家庭"
        },
        memberCountLabel = if (isJoined) {
            familyRosterEntryPresentation(
                visibleCount = memberCount,
                loaded = membersLoaded,
                loading = membersLoading,
                error = membersError,
            ).label
        } else {
            "尚未加入"
        },
        selfTitle = if (isJoined) {
            overviewSelfTitle(localDisplayName, role)
        } else {
            ""
        },
        syncStatusLabel = overviewSyncStatusLabel(
            status = status,
            isJoined = isJoined,
            lastSuccessAt = lastSuccessAt,
            waitingForApproval = waitingForApproval,
        ),
        showCreateJoin = primary.showCreateJoin,
        showMembersEntry = isJoined,
        showRenameFamily = false,
    )
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
    val selfAware = if (member.isSelf) "$name（我）" else name
    return if (member.role == FamilyRole.Owner) "$selfAware ★" else selfAware
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
 * Client-side gate for create/member-request/self-rename free-text 称呼.
 * Delegates to sync [com.lezi.babylog.sync.session.memberDisplayNameValidationError] so account
 * wizard and onboarding share one rule set (no third form stack).
 */
internal fun validateFamilyDisplayNameInput(raw: String): String? =
    com.lezi.babylog.sync.session.memberDisplayNameValidationError(raw)

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

/** Current trusted create/rename requires a non-empty family name. */
internal fun validateFamilyNameInput(raw: String): String? {
    return com.lezi.babylog.domain.family.familyNameValidationError(raw)
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

internal fun formatLastSuccessAt(lastSuccessAt: Long?): String =
    lastSuccessAt?.let {
        "上次成功 · ${java.text.DateFormat.getDateTimeInstance().format(it)}"
    } ?: "尚无成功同步"
