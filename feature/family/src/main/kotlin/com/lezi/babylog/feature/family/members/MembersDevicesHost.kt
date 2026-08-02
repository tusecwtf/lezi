package com.lezi.babylog.feature.family.members

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.LocalFamilyIdentity
import com.lezi.babylog.feature.family.FamilyIdentityUi
import com.lezi.babylog.feature.family.components.familySyncError
import com.lezi.babylog.feature.family.components.validateFamilyDisplayNameInput
import com.lezi.babylog.feature.family.components.validateFamilyNameInput
import com.lezi.babylog.sync.backend.DisplayNameUpdateResult
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.backend.PendingMemberLoginRequest
import com.lezi.babylog.sync.backend.PendingMemberRenameRequest
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

internal data class FamilyMembersState(
    val familyId: String = "",
    val members: List<FamilyMember> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
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
        val session = sync.session().first()
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
        if (sync.session().first().familyId != session.familyId) return@withLock loading
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
                error = if (showErrors) {
                    familySyncError(error, "暂时无法读取成员与设备，请稍后重试")
                } else {
                    null
                },
            )
        }
    }

    suspend fun approveNewMemberLogin(requestId: String): Result<Unit> =
        sync.approveNewMemberLogin(requestId)
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
        updateLocalDisplayName = careLog::updateLocalDisplayName,
    )

    internal constructor(sync: SyncPort, localIdentity: LocalFamilyIdentity) : this(
        sync = sync,
        localIdentityProvider = { localIdentity },
        updateLocalDisplayName = {},
    )

    private val familyMembers = MutableStateFlow(FamilyMembersState())
    private val actions = MembersDevicesActions(sync) { loading ->
        familyMembers.value = loading
    }

    val ui: StateFlow<MembersDevicesUi> = combine(
        sync.session(),
        familyMembers,
    ) { session, memberState ->
        val identity = localIdentityProvider()
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
                pendingMemberRequests = memberState.pendingRequests,
                pendingMemberRenameRequests = memberState.pendingRenameRequests,
            )
        } else {
            base
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MembersDevicesUi())

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

    fun approveNewMemberLogin(requestId: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = actions.approveNewMemberLogin(requestId)
            if (result.isSuccess) {
                familyMembers.value = actions.refreshMembersNow(familyMembers.value, showErrors = true)
            }
            onDone(result.exceptionOrNull()?.let { familySyncError(it, "批准失败，请稍后重试") })
        }
    }

    fun bindExistingMemberLogin(
        requestId: String,
        membershipId: String,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val result = sync.bindExistingMemberLogin(requestId, membershipId)
            if (result.isSuccess) {
                familyMembers.value = actions.refreshMembersNow(familyMembers.value, showErrors = true)
            }
            onDone(result.exceptionOrNull()?.let { familySyncError(it, "绑定失败，请稍后重试") })
        }
    }

    fun rejectMemberLogin(requestId: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = sync.rejectMemberLogin(requestId)
            if (result.isSuccess) {
                familyMembers.value = actions.refreshMembersNow(familyMembers.value, showErrors = true)
            }
            onDone(result.exceptionOrNull()?.let { familySyncError(it, "拒绝失败，请稍后重试") })
        }
    }

    fun createMemberLoginQr(
        membershipId: String,
        onResult: (Result<MemberLoginQrPayload>) -> Unit,
    ) {
        viewModelScope.launch {
            onResult(sync.createMemberLoginQrPayload(membershipId))
        }
    }

    fun leave(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val result = sync.leave()
            onMessage(
                result.fold(
                    onSuccess = { "已退出家庭" },
                    onFailure = {
                        familySyncError(
                            it,
                            fallback = "退出失败；本机数据未清除，请稍后重试",
                        )
                    },
                ),
            )
            if (result.isSuccess) familyMembers.value = FamilyMembersState()
        }
    }

    fun logoutCurrentDevice(onDone: (success: Boolean, message: String) -> Unit) {
        viewModelScope.launch {
            val result = sync.logoutCurrentDevice()
            if (result.isSuccess) familyMembers.value = FamilyMembersState()
            onDone(
                result.isSuccess,
                result.fold(
                    onSuccess = { "这台设备已退出家庭" },
                    onFailure = {
                        familySyncError(
                            it,
                            "退出失败；本机数据未清除，请稍后重试",
                        )
                    },
                ),
            )
        }
    }

    fun revokeFamilyDevice(
        deviceId: String,
        deviceName: String,
        isCurrent: Boolean,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            val result = sync.revokeFamilyDevice(deviceId)
            if (result.isSuccess) {
                if (isCurrent) {
                    familyMembers.value = FamilyMembersState()
                } else {
                    familyMembers.value =
                        actions.refreshMembersNow(familyMembers.value, showErrors = true)
                }
            }
            val label = deviceName.trim().ifBlank { "这台设备" }
            onDone(
                result.isSuccess,
                result.fold(
                    onSuccess = {
                        if (isCurrent) "这台设备已退出家庭" else "已撤销「$label」"
                    },
                    onFailure = { familySyncError(it, "撤销设备失败，请稍后重试") },
                ),
            )
        }
    }

    /**
     * Owner removes another active member. On success, refreshes the roster.
     * Does not clear this device's session.
     */
    fun removeMember(
        membershipId: String,
        displayName: String,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            val result = sync.removeMember(membershipId)
            if (result.isSuccess) {
                familyMembers.value =
                    actions.refreshMembersNow(familyMembers.value, showErrors = true)
                val label = displayName.trim().ifBlank { "家人" }
                onDone(true, "已删除成员「$label」")
            } else {
                onDone(
                    false,
                    familySyncError(
                        result.exceptionOrNull() ?: Exception(),
                        fallback = "删除成员失败，请稍后重试",
                    ),
                )
            }
        }
    }

    fun renameFamily(
        familyName: String,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            validateFamilyNameInput(familyName)?.let {
                onDone(false, it)
                return@launch
            }
            val result = sync.renameFamily(familyName.trim())
            onDone(
                result.isSuccess,
                result.fold(
                    onSuccess = { "家庭名已更新" },
                    onFailure = { familySyncError(it, "修改家庭名失败") },
                ),
            )
        }
    }

    fun updateMyDisplayName(
        displayName: String,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            validateFamilyDisplayNameInput(displayName)?.let {
                onDone(false, it)
                return@launch
            }
            val result = sync.updateMyDisplayName(displayName.trim())
            val outcome = result.getOrNull()
            if (outcome is DisplayNameUpdateResult.Updated) {
                updateLocalDisplayName(outcome.displayName)
            }
            if (outcome != null) {
                familyMembers.value =
                    actions.refreshMembersNow(familyMembers.value, showErrors = true)
            }
            onDone(
                result.isSuccess,
                result.fold(
                    onSuccess = {
                        when (it) {
                            is DisplayNameUpdateResult.Updated -> "家庭称呼已更新"
                            is DisplayNameUpdateResult.Pending ->
                                "改名申请已提交，确认前仍显示「${it.request.currentDisplayName}」"
                        }
                    },
                    onFailure = { familySyncError(it, "更新称呼失败") },
                ),
            )
        }
    }

    fun addFamilyMember(
        displayName: String,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            validateFamilyDisplayNameInput(displayName)?.let {
                onDone(false, it)
                return@launch
            }
            val result = sync.addFamilyMember(displayName.trim())
            if (result.isSuccess) {
                familyMembers.value =
                    actions.refreshMembersNow(familyMembers.value, showErrors = true)
            }
            onDone(
                result.isSuccess,
                result.fold(
                    onSuccess = { "已添加「${it.displayName}」，可继续生成登录二维码" },
                    onFailure = { familySyncError(it, "添加成员失败") },
                ),
            )
        }
    }

    fun renameFamilyMember(
        membershipId: String,
        displayName: String,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            validateFamilyDisplayNameInput(displayName)?.let {
                onDone(false, it)
                return@launch
            }
            val result = sync.renameFamilyMember(membershipId, displayName.trim())
            if (result.isSuccess) {
                familyMembers.value =
                    actions.refreshMembersNow(familyMembers.value, showErrors = true)
            }
            onDone(
                result.isSuccess,
                result.fold(
                    onSuccess = { "成员称呼已更新" },
                    onFailure = { familySyncError(it, "修改成员称呼失败") },
                ),
            )
        }
    }

    fun renameFamilyDevice(
        deviceId: String,
        deviceName: String,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            val normalized = deviceName.trim()
            if (normalized.isEmpty()) {
                onDone(false, "请填写设备称呼")
                return@launch
            }
            val result = sync.renameFamilyDevice(deviceId, normalized)
            if (result.isSuccess) {
                familyMembers.value =
                    actions.refreshMembersNow(familyMembers.value, showErrors = true)
            }
            onDone(
                result.isSuccess,
                result.fold(
                    onSuccess = { "设备称呼已更新" },
                    onFailure = { familySyncError(it, "修改设备称呼失败") },
                ),
            )
        }
    }

    fun decideMemberRename(
        request: PendingMemberRenameRequest,
        approve: Boolean,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            val result = if (approve) {
                sync.approveMemberRename(request.requestId)
            } else {
                sync.rejectMemberRename(request.requestId)
            }
            if (result.isSuccess) {
                familyMembers.value =
                    actions.refreshMembersNow(familyMembers.value, showErrors = true)
            }
            onDone(
                result.isSuccess,
                result.fold(
                    onSuccess = { if (approve) "改名申请已确认" else "改名申请已拒绝" },
                    onFailure = { familySyncError(it, "处理改名申请失败") },
                ),
            )
        }
    }

    fun cancelMyMemberRename(onDone: (success: Boolean, message: String) -> Unit) {
        viewModelScope.launch {
            val result = sync.cancelMyMemberRename()
            if (result.isSuccess) {
                familyMembers.value =
                    actions.refreshMembersNow(familyMembers.value, showErrors = true)
            }
            onDone(
                result.isSuccess,
                result.fold(
                    onSuccess = { "改名申请已撤回" },
                    onFailure = { familySyncError(it, "撤回改名申请失败") },
                ),
            )
        }
    }

    fun deleteFamily(
        familyName: String,
        rootPassword: String,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            val result = sync.deleteFamily(familyName, rootPassword)
            onDone(
                result.isSuccess,
                result.fold(
                    { "家庭数据已永久删除，本机数据已清除" },
                    { familySyncError(it, "删除家庭失败，本机数据未清除") },
                ),
            )
            if (result.isSuccess) familyMembers.value = FamilyMembersState()
        }
    }
}
