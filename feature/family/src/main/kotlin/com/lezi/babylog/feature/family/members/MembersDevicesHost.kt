package com.lezi.babylog.feature.family.members

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.LocalFamilyIdentity
import com.lezi.babylog.feature.family.FamilyIdentityUi
import com.lezi.babylog.domain.family.LocalFamilyIdentityInvalidations
import com.lezi.babylog.feature.family.localFamilyIdentityReloadKey
import com.lezi.babylog.feature.family.components.familyFailureKind
import com.lezi.babylog.feature.family.components.FamilyDestructiveActionGate
import com.lezi.babylog.feature.family.components.validateFamilyDisplayNameInput
import com.lezi.babylog.feature.family.components.validateFamilyNameInput
import com.lezi.babylog.sync.backend.DisplayNameUpdateResult
import com.lezi.babylog.sync.DeviceRemovedCleanupReceipt
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.SourceCommandLogoutConsent
import com.lezi.babylog.sync.SourceCommandLogoutConsentChangedException
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.qr.MemberLoginQrCode
import com.lezi.babylog.sync.backend.PendingMemberLoginRequest
import com.lezi.babylog.sync.backend.PendingMemberRenameRequest
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Members/devices call-flow host: roster refresh, approval, rename, revoke, remove,
 * invite-family QR create, and leave/logout/delete family commands.
 *
 * Does not own family-wizard lifecycle or app-update entry.
 * Identity is projected once via [FamilyIdentityUi]; roster fields stay roster-only.
 */
data class MembersDevicesUi(
    val identity: FamilyIdentityUi = FamilyIdentityUi(),
    val members: List<FamilyMember> = emptyList(),
    val membersLoaded: Boolean = false,
    val membersLoading: Boolean = false,
    val membersError: String? = null,
    val membersFailureKind: FailureKind? = null,
    val pendingMemberRequests: List<PendingMemberLoginRequest> = emptyList(),
    val pendingMemberRenameRequests: List<PendingMemberRenameRequest> = emptyList(),
) {
    val displayName: String get() = identity.displayName
    val enabled: Boolean get() = identity.enabled
    val familyId: String get() = identity.familyId
    val membershipId: String get() = identity.membershipId
    val role: FamilyRole get() = identity.role
    val familyName: String? get() = identity.familyName
}

/** Retained command receipt. UI recreation observes the same target and terminal result. */
data class FamilyMemberCommand(
    val id: Long,
    val target: String,
    val pending: Boolean = true,
    val success: Boolean = false,
    val message: String = "",
    val failureKind: FailureKind? = null,
    /** Process-memory only; never persist the login grant in saved instance state. */
    val qrCode: MemberLoginQrCode? = null,
)

/** Process-local confirmation state; a successful null preview proves no pending source work. */
sealed interface SourceCommandLogoutPreview {
    data object Checking : SourceCommandLogoutPreview
    data class Ready(val consent: SourceCommandLogoutConsent?) : SourceCommandLogoutPreview
    data class Failed(
        val message: String = "无法核对来源操作，暂时不能退出。请取消后重新打开重试。",
    ) : SourceCommandLogoutPreview
}

internal data class FamilyMembersState(
    val familyId: String = "",
    val members: List<FamilyMember> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val failureKind: FailureKind? = null,
    val pendingRequests: List<PendingMemberLoginRequest> = emptyList(),
    val pendingRenameRequests: List<PendingMemberRenameRequest> = emptyList(),
)

/**
 * Sync-only roster load/approval helpers (testable with [SyncPort] fakes).
 * Host owns StateFlow projection; this keeps CareLog out of command-path tests.
 */
internal class MembersDevicesActions(
    private val sync: SyncPort,
    private val onLoading: (FamilyMembersState) -> Unit = {},
) {

    private val memberRefreshMutex = Mutex()

    suspend fun refreshMembersNow(
        previous: FamilyMembersState,
        showErrors: Boolean,
    ): FamilyMembersState = memberRefreshMutex.withLock {
        val session = sync.sessionPresentation().first()
        if (!session.isJoined) {
            return@withLock FamilyMembersState()
        }
        val prior = previous.takeIf { it.familyId == session.familyId }
        if (!showErrors && prior != null &&
            (prior.loading || prior.loaded || prior.error != null)
        ) {
            return@withLock previous
        }
        val loading = FamilyMembersState(
            familyId = session.familyId,
            members = prior?.members.orEmpty(),
            loaded = prior?.loaded ?: false,
            loading = true,
            pendingRequests = prior?.pendingRequests.orEmpty(),
            pendingRenameRequests = prior?.pendingRenameRequests.orEmpty(),
        )
        onLoading(loading)
        try {
            val result = sync.listFamilyMembers()
            val pendingResult = if (session.role == FamilyRole.Owner) {
                sync.listPendingMemberLogins()
            } else {
                Result.success(emptyList())
            }
            val pendingRenameResult = if (session.role == FamilyRole.Owner) {
                sync.listPendingMemberRenameRequests()
            } else {
                Result.success(emptyList())
            }
            if (sync.sessionPresentation().first().familyId != session.familyId) return@withLock loading
            if (result.isSuccess && pendingResult.isSuccess && pendingRenameResult.isSuccess) {
                FamilyMembersState(
                    familyId = session.familyId,
                    members = result.getOrThrow(),
                    loaded = true,
                    pendingRequests = pendingResult.getOrThrow(),
                    pendingRenameRequests = pendingRenameResult.getOrThrow(),
                )
            } else {
                val error = result.exceptionOrNull()
                    ?: pendingResult.exceptionOrNull()
                    ?: pendingRenameResult.exceptionOrNull()
                    ?: Exception()
                FamilyMembersState(
                    familyId = session.familyId,
                    members = prior?.members.orEmpty(),
                    loaded = prior?.loaded ?: false,
                    pendingRequests = prior?.pendingRequests.orEmpty(),
                    pendingRenameRequests = prior?.pendingRenameRequests.orEmpty(),
                    error = null,
                    failureKind = if (showErrors) familyFailureKind(error) else null,
                )
            }
        } catch (cancelled: CancellationException) {
            onLoading(
                prior?.copy(loading = false)
                    ?: FamilyMembersState(familyId = session.familyId),
            )
            throw cancelled
        }
    }

    suspend fun approveNewMemberLogin(requestId: String): Result<Unit> =
        sync.approveNewMemberLogin(requestId)

    suspend fun <T> runWithUiTimeout(block: suspend () -> Result<T>): Result<T> = block()
}

@HiltViewModel
class MembersDevicesHost private constructor(
    private val sync: SyncPort,
    private val localIdentityProvider: suspend () -> LocalFamilyIdentity,
    private val updateLocalDisplayName: suspend (String) -> Unit,
) : ViewModel() {
    @Inject
    constructor(sync: SyncPort, careLog: CareLog) : this(
        sync = sync,
        localIdentityProvider = careLog::localFamilyIdentity,
        updateLocalDisplayName = { name ->
            careLog.updateLocalDisplayName(name)
        },
    )

    internal constructor(sync: SyncPort, localIdentity: LocalFamilyIdentity) : this(
        sync = sync,
        localIdentityProvider = { localIdentity },
        updateLocalDisplayName = {},
    )

    private var nextCommandId = 0L
    private val mutableCommand = MutableStateFlow<FamilyMemberCommand?>(null)
    val command: StateFlow<FamilyMemberCommand?> = mutableCommand

    fun consumeCommand(id: Long) {
        if (mutableCommand.value?.let { it.id == id && !it.pending } == true) {
            mutableCommand.value = null
        }
    }

    private fun launchCommand(
        target: String,
        callback: (Boolean, String, FailureKind?) -> Unit,
        action: suspend ((Boolean, String, FailureKind?) -> Unit) -> Unit,
    ) {
        // Claim synchronously, before launch: duplicate taps and recreated callers share this gate.
        if (mutableCommand.value != null) return
        val receipt = FamilyMemberCommand(++nextCommandId, target)
        mutableCommand.value = receipt
        viewModelScope.launch {
            try {
                action { success, message, kind ->
                    mutableCommand.value = (mutableCommand.value?.takeIf { it.id == receipt.id } ?: receipt).copy(
                        pending = false, success = success, message = message, failureKind = kind,
                    )
                    callback(success, message, kind)
                }
            } catch (cancelled: CancellationException) {
                mutableCommand.value = null
                throw cancelled
            } catch (error: Throwable) {
                val kind = familyFailureKind(error)
                mutableCommand.value = receipt.copy(pending = false, failureKind = kind)
                callback(false, "", kind)
            }
        }
    }

    private val familyMembers = MutableStateFlow(FamilyMembersState())
    private val destructiveAction = FamilyDestructiveActionGate()
    private val actions = MembersDevicesActions(sync) { loading ->
        familyMembers.value = loading
    }

    private val localIdentity = combine(
        sync.sessionPresentation().map(::localFamilyIdentityReloadKey).distinctUntilChanged(),
        LocalFamilyIdentityInvalidations.epoch,
    ) { _, _ ->
        localIdentityProvider()
    }

    val ui: StateFlow<MembersDevicesUi> = combine(
        sync.sessionPresentation(),
        familyMembers,
        localIdentity,
    ) { session, memberState, identity ->
        val familyId = session.familyId.ifBlank { identity.familyId.toString() }
        val base = MembersDevicesUi(
            identity = FamilyIdentityUi(
                displayName = identity.displayName,
                enabled = session.isJoined,
                familyId = familyId,
                membershipId = session.membershipId,
                role = session.role,
                familyName = session.familyName,
            ),
        )
        if (session.isJoined && memberState.familyId == familyId) {
            base.copy(
                members = memberState.members,
                membersLoaded = memberState.loaded,
                membersLoading = memberState.loading,
                membersError = memberState.error,
                membersFailureKind = memberState.failureKind,
                pendingMemberRequests = memberState.pendingRequests,
                pendingMemberRenameRequests = memberState.pendingRenameRequests,
            )
        } else {
            base
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MembersDevicesUi())

    /**
     * Dirty Room entity count backing the logout disclosure. Null until the
     * first Room emission so the dialog never shows a fabricated count.
     */
    val pendingPublishCount: StateFlow<Int?> = sync.pendingPublishCount()
        .map<Int, Int?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** One pending sync-first round for the logout dialog; stays inside the dialog. */
    private val logoutSyncCheckingInternal = MutableStateFlow(false)
    val logoutSyncChecking: StateFlow<Boolean> = logoutSyncCheckingInternal

    /** Inline failure copy after a sync-first round; never blocks 仍然退出. */
    val logoutSyncFeedback = MutableStateFlow<String?>(null)

    private val mutableLogoutSourcePreview =
        MutableStateFlow<SourceCommandLogoutPreview>(SourceCommandLogoutPreview.Checking)
    val logoutSourcePreview: StateFlow<SourceCommandLogoutPreview> = mutableLogoutSourcePreview
    private var logoutPreviewGeneration = 0L
    private var logoutPreviewJob: Job? = null

    /** Load once per visible dialog. Never refresh this snapshot behind its final click. */
    fun openLogoutConfirmation() {
        val generation = ++logoutPreviewGeneration
        logoutPreviewJob?.cancel()
        mutableLogoutSourcePreview.value = SourceCommandLogoutPreview.Checking
        logoutSyncFeedback.value = null
        logoutPreviewJob = viewModelScope.launch {
            val preview = try {
                sync.prepareSourceCommandLogout().fold(
                    onSuccess = { SourceCommandLogoutPreview.Ready(it) },
                    onFailure = { SourceCommandLogoutPreview.Failed() },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SourceCommandLogoutPreview.Failed()
            }
            if (logoutPreviewGeneration == generation) {
                mutableLogoutSourcePreview.value = preview
            }
        }
    }

    /** Closing/recreating the dialog retires its ephemeral consent, including late loads. */
    fun closeLogoutConfirmation() {
        ++logoutPreviewGeneration
        logoutPreviewJob?.cancel()
        logoutPreviewJob = null
        mutableLogoutSourcePreview.value = SourceCommandLogoutPreview.Checking
    }

    /**
     * Fires one Foreground sync round so the logout dialog can refresh the
     * pending count in place. Best-effort: failures surface as inline feedback
     * and the confirm action stays available (confirm, not block).
     */
    fun syncBeforeLogoutCheck() {
        if (logoutSyncCheckingInternal.value) return
        viewModelScope.launch {
            logoutSyncCheckingInternal.value = true
            logoutSyncFeedback.value = null
            try {
                val result = sync.syncWhenAvailable(SyncTrigger.Foreground)
                if (result.isFailure) {
                    logoutSyncFeedback.value = LOGOUT_SYNC_FIRST_FAILED
                }
            } finally {
                logoutSyncCheckingInternal.value = false
            }
        }
    }

    /** Durable one-time receipt of the remote-removal cleanup, or null. */
    val sourceCommandClearNotice = sync.sourceCommandClearNotice()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun acknowledgeSourceCommandClearNotice(notice: com.lezi.babylog.sync.sourcerelation.SourceCommandClearNotice) {
        viewModelScope.launch { sync.acknowledgeSourceCommandClearNotice(notice) }
    }

    val deviceRemovedReceipt: StateFlow<DeviceRemovedCleanupReceipt?> =
        sync.deviceRemovedReceipt()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Retires the receipt after its one-time presentation. */
    fun consumeDeviceRemovedReceipt() {
        viewModelScope.launch { sync.consumeDeviceRemovedReceipt() }
    }

    val destructiveBusy: StateFlow<Boolean> = destructiveAction.busy

    fun refreshMembers(showErrors: Boolean = true) {
        viewModelScope.launch {
            familyMembers.value = actions.refreshMembersNow(familyMembers.value, showErrors)
        }
    }

    fun refreshFamilyForDeletion() {
        viewModelScope.launch {
            sync.syncWhenAvailable(SyncTrigger.PullToRefresh)
            familyMembers.value = actions.refreshMembersNow(familyMembers.value, showErrors = true)
        }
    }

    private fun refreshMembersAfterCommit() {
        viewModelScope.launch {
            try {
                familyMembers.value = actions.refreshMembersNow(familyMembers.value, showErrors = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The command already committed. Explicit roster refresh remains available.
            }
        }
    }

    fun approveNewMemberLogin(requestId: String, onDone: (FailureKind?) -> Unit = {}) {
        launchCommand("review_member:$requestId", { _, _, kind -> onDone(kind) }) { report ->
            val result = actions.approveNewMemberLogin(requestId)
            if (result.isSuccess) {
                refreshMembersAfterCommit()
            }
            report(result.isSuccess, "成员申请已处理", result.exceptionOrNull()?.let(::familyFailureKind))
        }
    }

    fun bindExistingMemberLogin(
        requestId: String,
        membershipId: String,
        onDone: (FailureKind?) -> Unit = {},
    ) {
        launchCommand("review_member:$requestId", { _, _, kind -> onDone(kind) }) { report ->
            val result = actions.runWithUiTimeout { sync.bindExistingMemberLogin(requestId, membershipId) }
            if (result.isSuccess) {
                refreshMembersAfterCommit()
            }
            report(result.isSuccess, "成员申请已处理", result.exceptionOrNull()?.let(::familyFailureKind))
        }
    }

    fun rejectMemberLogin(
        request: PendingMemberLoginRequest,
        onDone: (FailureKind?) -> Unit = {},
    ) {
        launchCommand("review_member:${request.requestId}", { _, _, kind -> onDone(kind) }) { report ->
            val result = actions.runWithUiTimeout { sync.rejectMemberLogin(request.requestId) }
            if (result.isSuccess) {
                refreshMembersAfterCommit()
            }
            report(result.isSuccess, "成员申请已处理", result.exceptionOrNull()?.let(::familyFailureKind))
        }
    }

    fun createMemberLoginQr(
        membershipId: String,
        onResult: (Result<MemberLoginQrCode>) -> Unit = {},
    ) {
        launchCommand("members_list", { _, _, _ -> }) { report ->
            val result = actions.runWithUiTimeout { sync.createMemberLoginQrCode(membershipId) }
            mutableCommand.value = mutableCommand.value?.copy(qrCode = result.getOrNull())
            report(result.isSuccess, if (result.isSuccess) "" else "二维码生成失败，请重试",
                result.exceptionOrNull()?.let(::familyFailureKind))
            onResult(result)
        }
    }

    fun leave(onDone: (success: Boolean, message: String, kind: FailureKind?) -> Unit = { _, _, _ -> }) {
        launchCommand("confirm_leave", onDone) { report ->
            var outcome: Triple<Boolean, String, FailureKind?>? = null
            val accepted = destructiveAction.run {
                val result = actions.runWithUiTimeout { sync.leave() }
                outcome = Triple(
                    result.isSuccess,
                    result.fold(
                        onSuccess = { "已退出家庭" },
                        onFailure = { "" },
                    ),
                    result.exceptionOrNull()?.let(::familyFailureKind),
                )
                if (result.isSuccess) familyMembers.value = FamilyMembersState()
            }
            if (accepted) outcome?.let { (success, message, kind) -> report(success, message, kind) }
        }
    }

    fun logoutCurrentDevice(
        preview: SourceCommandLogoutPreview,
        onDone: (success: Boolean, message: String, kind: FailureKind?) -> Unit = { _, _, _ -> },
    ) {
        // The click must refer to the exact preview rendered by this dialog.
        if (preview !is SourceCommandLogoutPreview.Ready ||
            mutableLogoutSourcePreview.value !== preview
        ) return
        launchCommand("confirm_device_logout", onDone) { report ->
            var outcome: Triple<Boolean, String, FailureKind?>? = null
            val accepted = destructiveAction.run {
                val result = actions.runWithUiTimeout {
                    preview.consent?.let { sync.logoutCurrentDevice(it) }
                        ?: sync.logoutCurrentDevice()
                }
                if (result.isSuccess) {
                    familyMembers.value = FamilyMembersState()
                    closeLogoutConfirmation()
                } else if (result.exceptionOrNull() is SourceCommandLogoutConsentChangedException) {
                    mutableLogoutSourcePreview.value = SourceCommandLogoutPreview.Failed(
                        "来源操作已变化，请取消后重新打开退出确认。",
                    )
                }
                outcome = Triple(
                    result.isSuccess,
                    result.fold(
                        onSuccess = { "这台设备已退出家庭" },
                        onFailure = {
                            if (it is SourceCommandLogoutConsentChangedException) {
                                "来源操作已变化，请重新核对退出确认。"
                            } else {
                                ""
                            }
                        },
                    ),
                    result.exceptionOrNull()
                        ?.takeUnless { it is SourceCommandLogoutConsentChangedException }
                        ?.let(::familyFailureKind),
                )
            }
            if (accepted) outcome?.let { (success, message, kind) -> report(success, message, kind) }
        }
    }

    fun revokeFamilyDevice(
        deviceId: String,
        deviceName: String,
        isCurrent: Boolean,
        onDone: (success: Boolean, message: String, kind: FailureKind?) -> Unit = { _, _, _ -> },
    ) {
        launchCommand("revoke_device:$deviceId", onDone) { report ->
            var outcome: Triple<Boolean, String, FailureKind?>? = null
            val accepted = destructiveAction.run {
                val result = actions.runWithUiTimeout { sync.revokeFamilyDevice(deviceId) }
                if (result.isSuccess) {
                    if (isCurrent) {
                        familyMembers.value = FamilyMembersState()
                    } else {
                        refreshMembersAfterCommit()
                    }
                }
                val label = deviceName.trim().ifBlank { "这台设备" }
                outcome = Triple(
                    result.isSuccess,
                    result.fold(
                        onSuccess = {
                            if (isCurrent) "这台设备已退出家庭" else "已撤销「$label」"
                        },
                        onFailure = { "" },
                    ),
                    result.exceptionOrNull()?.let(::familyFailureKind),
                )
            }
            if (accepted) outcome?.let { (success, message, kind) -> report(success, message, kind) }
        }
    }

    /**
     * Owner removes another active member. On success, refreshes the roster.
     * Does not clear this device's session.
     */
    fun removeMember(
        membershipId: String,
        displayName: String,
        onDone: (success: Boolean, message: String, kind: FailureKind?) -> Unit = { _, _, _ -> },
    ) {
        launchCommand("remove_member:$membershipId", onDone) { report ->
            var outcome: Triple<Boolean, String, FailureKind?>? = null
            val accepted = destructiveAction.run {
                val result = actions.runWithUiTimeout { sync.removeMember(membershipId) }
                if (result.isSuccess) {
                    refreshMembersAfterCommit()
                    val label = displayName.trim().ifBlank { "家人" }
                    outcome = Triple(true, "已删除成员「$label」", null)
                } else {
                    val error = result.exceptionOrNull() ?: Exception()
                    outcome = Triple(
                        false,
                        "",
                        familyFailureKind(error),
                    )
                }
            }
            if (accepted) outcome?.let { (success, message, kind) -> report(success, message, kind) }
        }
    }

    fun renameFamily(
        familyName: String,
        onDone: (success: Boolean, message: String, kind: FailureKind?) -> Unit = { _, _, _ -> },
    ) {
        launchCommand("rename_family", onDone) { report ->
            validateFamilyNameInput(familyName)?.let {
                report(false, it, FailureKind.InvalidInput)
                return@launchCommand
            }
            val result = actions.runWithUiTimeout { sync.renameFamily(familyName.trim()) }
            report(
                result.isSuccess,
                result.fold(
                    onSuccess = { "家庭名已更新" },
                    onFailure = { "" },
                ),
                result.exceptionOrNull()?.let(::familyFailureKind),
            )
        }
    }

    fun updateMyDisplayName(
        displayName: String,
        onDone: (success: Boolean, message: String, kind: FailureKind?) -> Unit = { _, _, _ -> },
    ) {
        launchCommand("edit_my_display_name", onDone) { report ->
            validateFamilyDisplayNameInput(displayName)?.let {
                report(false, it, FailureKind.InvalidInput)
                return@launchCommand
            }
            val result = actions.runWithUiTimeout { sync.updateMyDisplayName(displayName.trim()) }
            val outcome = result.getOrNull()
            if (outcome is DisplayNameUpdateResult.Updated) {
                viewModelScope.launch {
                    runCatching { updateLocalDisplayName(outcome.displayName) }
                        .onFailure { if (it is CancellationException) throw it }
                }
            }
            if (outcome != null) {
                refreshMembersAfterCommit()
            }
            report(
                result.isSuccess,
                result.fold(
                    onSuccess = {
                        when (it) {
                            is DisplayNameUpdateResult.Updated -> "家庭称呼已更新"
                            is DisplayNameUpdateResult.Pending ->
                                "改名申请已提交，确认前仍显示「${it.request.currentDisplayName}」"
                        }
                    },
                    onFailure = { "" },
                ),
                result.exceptionOrNull()?.let(::familyFailureKind),
            )
        }
    }

    fun addFamilyMember(
        displayName: String,
        onDone: (success: Boolean, message: String, kind: FailureKind?) -> Unit = { _, _, _ -> },
    ) {
        launchCommand("add_family_member", onDone) { report ->
            validateFamilyDisplayNameInput(displayName)?.let {
                report(false, it, FailureKind.InvalidInput)
                return@launchCommand
            }
            val result = actions.runWithUiTimeout { sync.addFamilyMember(displayName.trim()) }
            if (result.isSuccess) {
                refreshMembersAfterCommit()
            }
            report(
                result.isSuccess,
                result.fold(
                    onSuccess = { "已添加「${it.displayName}」，可继续生成登录二维码" },
                    onFailure = { "" },
                ),
                result.exceptionOrNull()?.let(::familyFailureKind),
            )
        }
    }

    fun renameFamilyMember(
        membershipId: String,
        displayName: String,
        onDone: (success: Boolean, message: String, kind: FailureKind?) -> Unit = { _, _, _ -> },
    ) {
        launchCommand("rename_member:$membershipId", onDone) { report ->
            validateFamilyDisplayNameInput(displayName)?.let {
                report(false, it, FailureKind.InvalidInput)
                return@launchCommand
            }
            val result = actions.runWithUiTimeout { sync.renameFamilyMember(membershipId, displayName.trim()) }
            if (result.isSuccess) {
                refreshMembersAfterCommit()
            }
            report(
                result.isSuccess,
                result.fold(
                    onSuccess = { "成员称呼已更新" },
                    onFailure = { "" },
                ),
                result.exceptionOrNull()?.let(::familyFailureKind),
            )
        }
    }

    fun renameFamilyDevice(
        deviceId: String,
        deviceName: String,
        onDone: (success: Boolean, message: String, kind: FailureKind?) -> Unit = { _, _, _ -> },
    ) {
        launchCommand("rename_device:$deviceId", onDone) { report ->
            val normalized = deviceName.trim()
            if (normalized.isEmpty()) {
                report(false, "请填写设备称呼", FailureKind.InvalidInput)
                return@launchCommand
            }
            val result = actions.runWithUiTimeout { sync.renameFamilyDevice(deviceId, normalized) }
            if (result.isSuccess) {
                refreshMembersAfterCommit()
            }
            report(
                result.isSuccess,
                result.fold(
                    onSuccess = { "设备称呼已更新" },
                    onFailure = { "" },
                ),
                result.exceptionOrNull()?.let(::familyFailureKind),
            )
        }
    }

    fun decideMemberRename(
        request: PendingMemberRenameRequest,
        approve: Boolean,
        onDone: (success: Boolean, message: String, kind: FailureKind?) -> Unit = { _, _, _ -> },
    ) {
        launchCommand("members_list", onDone) { report ->
            val result = actions.runWithUiTimeout {
                if (approve) {
                    sync.approveMemberRename(request.requestId)
                } else {
                    sync.rejectMemberRename(request.requestId)
                }
            }
            if (result.isSuccess) {
                refreshMembersAfterCommit()
            }
            report(
                result.isSuccess,
                result.fold(
                    onSuccess = { if (approve) "改名申请已确认" else "改名申请已拒绝" },
                    onFailure = { "" },
                ),
                result.exceptionOrNull()?.let(::familyFailureKind),
            )
        }
    }

    fun cancelMyMemberRename(onDone: (success: Boolean, message: String, kind: FailureKind?) -> Unit = { _, _, _ -> }) {
        launchCommand("members_list", onDone) { report ->
            val result = actions.runWithUiTimeout { sync.cancelMyMemberRename() }
            if (result.isSuccess) {
                refreshMembersAfterCommit()
            }
            report(
                result.isSuccess,
                result.fold(
                    onSuccess = { "改名申请已撤回" },
                    onFailure = { "" },
                ),
                result.exceptionOrNull()?.let(::familyFailureKind),
            )
        }
    }

    fun deleteFamily(
        familyName: String,
        rootPassword: String,
        onDone: (success: Boolean, message: String, kind: FailureKind?) -> Unit = { _, _, _ -> },
    ) {
        launchCommand("delete_family", onDone) { report ->
            var outcome: Triple<Boolean, String, FailureKind?>? = null
            val accepted = destructiveAction.run {
                val result = actions.runWithUiTimeout { sync.deleteFamily(familyName, rootPassword) }
                outcome = Triple(
                    result.isSuccess,
                    result.fold(
                        { "家庭数据已永久删除，本机数据已清除" },
                        { "" },
                    ),
                    result.exceptionOrNull()?.let(::familyFailureKind),
                )
                if (result.isSuccess) familyMembers.value = FamilyMembersState()
            }
            if (accepted) outcome?.let { (success, message, kind) -> report(success, message, kind) }
        }
    }
}
