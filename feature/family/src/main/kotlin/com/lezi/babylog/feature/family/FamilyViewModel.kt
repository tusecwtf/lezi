package com.lezi.babylog.feature.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.domain.BabyMergePreview
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.DuplicateBabyNicknameException
import com.lezi.babylog.domain.FamilyWizardController
import com.lezi.babylog.domain.FamilyWizardEntry
import com.lezi.babylog.domain.FamilyWizardOutcome
import com.lezi.babylog.domain.FamilyWizardSnapshot
import com.lezi.babylog.domain.FamilyWizardState
import com.lezi.babylog.domain.SyncFamilyWizardGateway
import com.lezi.babylog.domain.UpdateBabyInput
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.AppUpdateUiOutcome
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.CertificateTrustCandidate
import com.lezi.babylog.sync.DisplayNameUpdateResult
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.MemberLoginQrPayload
import com.lezi.babylog.sync.MemberLoginQrTrustChangedException
import com.lezi.babylog.sync.MemberLoginQrUnavailableException
import com.lezi.babylog.sync.SetupProbeResult
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.PendingMemberLoginRequest
import com.lezi.babylog.sync.PendingMemberRenameRequest
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.appUpdateInstallUiOutcome
import com.lezi.babylog.sync.appUpdateUiOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class FamilyUi(
    val displayName: String = LOCAL_FAMILY_DISPLAY_NAME,
    val status: SyncStatus = SyncStatus.Disabled,
    val enabled: Boolean = false,
    val hasLocalBaby: Boolean = false,
    val familyId: String = "1",
    val membershipId: String = "",
    val current: Baby? = null,
    val babies: List<Baby> = emptyList(),
    /** Local pre-join profiles kept only as merge sources while this device is a member. */
    val localOrphanBabies: List<Baby> = emptyList(),
    val baseUrl: String = "",
    val serverHost: String = "",
    val serverPort: Int = com.lezi.babylog.sync.DEFAULT_SERVER_PORT,
    val serverScheme: String = com.lezi.babylog.sync.DEFAULT_SERVER_SCHEME,
    val role: FamilyRole = FamilyRole.None,
    val lastSuccessAt: Long? = null,
    /** Raw shared family name from session cache; null when empty/unknown. */
    val familyName: String? = null,
    val members: List<FamilyMember> = emptyList(),
    val membersLoaded: Boolean = false,
    val membersLoading: Boolean = false,
    val membersError: String? = null,
    val pendingMemberLogin: PendingMemberLogin? = null,
    val pendingMemberRequests: List<PendingMemberLoginRequest> = emptyList(),
    val pendingMemberRenameRequests: List<PendingMemberRenameRequest> = emptyList(),
    /**
     * Optional self-hosted app update from handshake/sync discovery.
     * Null when none, not joined, up-to-date, or dismissed for this process session.
     */
    val optionalAppUpdate: AppUpdateMetadata? = null,
) {
    /** Resolved label when the current optional family name is empty. */
    val familyNameLabel: String
        get() = displayFamilyName(familyName, current?.nickname)
}

private data class FamilyMembersState(
    val familyId: String = "",
    val members: List<FamilyMember> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val pendingRequests: List<PendingMemberLoginRequest> = emptyList(),
    val pendingRenameRequests: List<PendingMemberRenameRequest> = emptyList(),
)

@HiltViewModel
class FamilyViewModel @Inject constructor(
    private val sync: SyncPort,
    private val careLog: CareLog,
    private val avatarFileStore: BabyAvatarFileStore,
) : ViewModel() {
    private var memberLoginQrVerificationJob: Job? = null
    private val profileSaveMutex = Mutex()
    private val memberRefreshMutex = Mutex()
    private val familyMembers = MutableStateFlow(FamilyMembersState())
    private val familyWizard = FamilyWizardController(
        gateway = SyncFamilyWizardGateway(sync, careLog),
        initialSnapshot = FamilyWizardSnapshot.empty(FamilyWizardEntry.Account),
    )
    val familyWizardState = familyWizard.state
    val verifiedEndpoint = sync.verifiedEndpoint()

    init {
        viewModelScope.launch {
            sync.memberLoginChecks().collect(familyWizard::observeMemberLoginCheck)
        }
        viewModelScope.launch {
            combine(sync.pendingMemberLogin(), sync.session()) { pending, session ->
                pending to session
            }.collect { (pending, session) ->
                if (pending != null) {
                    familyWizard.restorePendingMemberApproval(
                        FamilyWizardSnapshot.empty(FamilyWizardEntry.Account).copy(
                            mode = com.lezi.babylog.domain.FamilyWizardMode.Join,
                            step = com.lezi.babylog.domain.FamilyWizardStep.Identity,
                            host = session.serverHost,
                            portText = session.serverPort.toString(),
                            scheme = session.serverScheme,
                            joinRole = com.lezi.babylog.domain.FamilyWizardJoinRole.Member,
                        ),
                        pending,
                    )
                }
            }
        }
    }

    private val babySurfaces = combine(
        careLog.observeBabies(),
        careLog.observeMemberLocalBabyOrphans(),
    ) { babies, orphans -> babies to orphans }

    private val syncIdentity = combine(
        sync.session(),
        sync.pendingMemberLogin(),
    ) { session, pending -> session to pending }

    private val baseUi = combine(
        sync.status(),
        careLog.observeHasBaby(),
        careLog.observeCurrentBaby(),
        babySurfaces,
        syncIdentity,
    ) { st, hasBaby, current, babyLists, syncState ->
        val (session, pendingMemberLogin) = syncState
        val (babies, localOrphans) = babyLists
        val identity = careLog.localFamilyIdentity()
        FamilyUi(
            displayName = identity.displayName,
            status = st,
            enabled = session.isJoined,
            hasLocalBaby = hasBaby,
            familyId = session.familyId.ifBlank { identity.familyId.toString() },
            membershipId = session.membershipId,
            current = current,
            babies = babies,
            localOrphanBabies = localOrphans,
            baseUrl = session.baseUrl,
            serverHost = session.serverHost,
            serverPort = session.serverPort,
            serverScheme = session.serverScheme,
            role = session.role,
            lastSuccessAt = session.lastSuccessAt,
            familyName = session.familyName,
            pendingMemberLogin = pendingMemberLogin,
        )
    }

    val ui = combine(
        baseUi,
        familyMembers,
        sync.availableOptionalAppUpdate(),
    ) { family, memberState, optionalUpdate ->
        val withMembers = if (family.enabled && memberState.familyId == family.familyId) {
            family.copy(
                members = memberState.members,
                membersLoaded = memberState.loaded,
                membersLoading = memberState.loading,
                membersError = memberState.error,
                pendingMemberRequests = memberState.pendingRequests,
                pendingMemberRenameRequests = memberState.pendingRenameRequests,
            )
        } else {
            family
        }
        // Only show the banner when the account is joined; never for offline/unjoined.
        withMembers.copy(
            optionalAppUpdate = optionalUpdate.takeIf { withMembers.enabled },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FamilyUi())

    private val _appUpdateOutcome = MutableStateFlow<AppUpdateUiOutcome?>(null)
    val appUpdateOutcome: StateFlow<AppUpdateUiOutcome?> = _appUpdateOutcome.asStateFlow()
    private val _checkingAppUpdate = MutableStateFlow(false)
    val checkingAppUpdate: StateFlow<Boolean> = _checkingAppUpdate.asStateFlow()
    private val _installingAppUpdate = MutableStateFlow(false)
    val installingAppUpdate: StateFlow<Boolean> = _installingAppUpdate.asStateFlow()

    /** Open the same optional confirm flow as the settings about path. */
    fun openOptionalAppUpdate(metadata: AppUpdateMetadata) {
        if (_installingAppUpdate.value) return
        _appUpdateOutcome.value = AppUpdateUiOutcome.OptionalUpdate(metadata)
    }

    /** Banner "稍后" or dialog dismiss: suppress this versionCode for the process session. */
    fun dismissOptionalAppUpdate(versionCode: Int) {
        sync.dismissOptionalAppUpdate(versionCode)
        val current = _appUpdateOutcome.value
        if (current is AppUpdateUiOutcome.OptionalUpdate &&
            current.metadata.versionCode == versionCode
        ) {
            _appUpdateOutcome.value = null
        }
    }

    fun dismissAppUpdateOutcome() {
        val current = _appUpdateOutcome.value
        // Forced updates cannot be dismissed ("稍后" is not allowed). Root force shell
        // remains authoritative; do not dismiss PackageUnknown either.
        if (current is AppUpdateUiOutcome.ForcedUpdate) return
        if (current is AppUpdateUiOutcome.ForcedUpdatePackageUnknown) return
        if (current is AppUpdateUiOutcome.OptionalUpdate) {
            sync.dismissOptionalAppUpdate(current.metadata.versionCode)
        }
        _appUpdateOutcome.value = null
    }

    /**
     * Manual / PackageUnknown-retry check — same busy + [SyncPort.checkAppUpdate] path as
     * Settings so secondary force dialogs share one contract (root overlay stays authoritative).
     */
    fun checkAppUpdate() {
        if (_checkingAppUpdate.value || _installingAppUpdate.value) return
        viewModelScope.launch {
            _checkingAppUpdate.value = true
            try {
                val result = sync.checkAppUpdate()
                // Pass live force shell so secondary dialog never claims Optional/UpToDate
                // while root ForcedAppUpdateState is retained (AUDIT-20260801-P1-01).
                val activeForce = sync.availableForcedAppUpdate().first()
                _appUpdateOutcome.value = appUpdateUiOutcome(
                    result = result,
                    failureCopy = { error ->
                        productUiError(error, "检查更新失败，请稍后重试")
                    },
                    activeForcedAppUpdate = activeForce,
                )
            } finally {
                _checkingAppUpdate.value = false
            }
        }
    }

    /**
     * Download → sha256 → staged archive identity (packageName/versionCode/signing)
     * → PackageInstaller (same path as settings; see [SyncPort.installAvailableAppUpdate]).
     */
    fun installOptionalUpdate(metadata: AppUpdateMetadata) {
        if (_installingAppUpdate.value) return
        viewModelScope.launch {
            _installingAppUpdate.value = true
            _appUpdateOutcome.value = AppUpdateUiOutcome.Message(
                title = "正在下载",
                body = "正在从家庭服务器下载更新包…",
            )
            try {
                val result = sync.installAvailableAppUpdate(metadata)
                // Update install failures must not use family-sync/NAS copy (productUiError only).
                _appUpdateOutcome.value = appUpdateInstallUiOutcome(result) { error ->
                    productUiError(error, "下载或安装失败，请稍后重试")
                }
            } finally {
                _installingAppUpdate.value = false
            }
        }
    }

    fun refreshMembers(showErrors: Boolean = true) {
        viewModelScope.launch { refreshMembersNow(showErrors) }
    }

    fun refreshFamilyForDeletion() {
        viewModelScope.launch {
            sync.sync(SyncTrigger.PullToRefresh)
            refreshMembersNow(showErrors = true)
        }
    }

    private suspend fun refreshMembersNow(showErrors: Boolean) = memberRefreshMutex.withLock {
        val session = sync.session().first()
        if (!session.isJoined) {
            familyMembers.value = FamilyMembersState()
            return@withLock
        }
        val previous = familyMembers.value.takeIf { it.familyId == session.familyId }
        if (!showErrors && previous != null &&
            (previous.loading || previous.loaded || previous.error != null)
        ) {
            return@withLock
        }
        familyMembers.value = FamilyMembersState(
            familyId = session.familyId,
            members = previous?.members.orEmpty(),
            loaded = previous?.loaded ?: false,
            loading = true,
            pendingRequests = previous?.pendingRequests.orEmpty(),
            pendingRenameRequests = previous?.pendingRenameRequests.orEmpty(),
        )
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
        if (sync.session().first().familyId != session.familyId) return@withLock
        familyMembers.value = if (
            result.isSuccess && pendingResult.isSuccess && pendingRenameResult.isSuccess
        ) {
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
                    members = previous?.members.orEmpty(),
                    loaded = previous?.loaded ?: false,
                    pendingRequests = previous?.pendingRequests.orEmpty(),
                    pendingRenameRequests = previous?.pendingRenameRequests.orEmpty(),
                    error = if (showErrors) {
                        familySyncError(error, "暂时无法读取成员与设备，请稍后重试")
                    } else {
                        null
                    },
                )
            }
    }

    fun setCurrent(id: Long) {
        viewModelScope.launch { careLog.setCurrentBaby(id) }
    }

    fun updateBaby(
        babyId: Long,
        nickname: String,
        sex: String?,
        birthdayEpochDay: Long,
        birthWeightGrams: Int?,
        avatarJpeg: ByteArray?,
        removeAvatar: Boolean,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            profileSaveMutex.withLock {
                val existing = careLog.listBabies().firstOrNull { it.id == babyId }
                if (existing == null) {
                    onDone("宝宝档案不存在")
                    return@withLock
                }
                var writtenAvatarPath: String? = null
                var profileCommitted = false
                val mayEditAvatar = canEditFamilyAvatar(ui.value.role)

                suspend fun rollbackWrittenAvatar() {
                    val path = writtenAvatarPath ?: return
                    withContext(NonCancellable) {
                        try {
                            avatarFileStore.delete(path)
                        } catch (_: Throwable) {
                            // Preserve the original save failure or cancellation.
                        }
                    }
                }

                val errorMessage = try {
                    val avatarPath = when {
                        mayEditAvatar && avatarJpeg != null -> {
                            avatarFileStore.write(existing.clientUuid, avatarJpeg).also {
                                writtenAvatarPath = it
                            }
                        }
                        mayEditAvatar && removeAvatar -> null
                        else -> existing.avatarPath
                    }
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        careLog.updateBabyProfile(
                            babyId,
                            UpdateBabyInput(
                                nickname = nickname,
                                sex = sex,
                                birthdayEpochDay = birthdayEpochDay,
                                birthWeightGrams = birthWeightGrams,
                                avatarPath = avatarPath,
                                themeColorArgb = existing.themeColorArgb,
                            ),
                        )
                        profileCommitted = true
                        if (avatarPath != existing.avatarPath) {
                            try {
                                avatarFileStore.delete(existing.avatarPath)
                            } catch (_: Throwable) {
                                // The new profile is durable; stale cleanup is best effort.
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    null
                } catch (cancelled: CancellationException) {
                    if (!profileCommitted) rollbackWrittenAvatar()
                    throw cancelled
                } catch (error: Throwable) {
                    if (!profileCommitted) rollbackWrittenAvatar()
                    if (error is DuplicateBabyNicknameException) {
                        error.message
                    } else {
                        "保存失败，请重试"
                    }
                }
                currentCoroutineContext().ensureActive()
                onDone(errorMessage)
            }
        }
    }

    fun deleteBaby(babyId: Long, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val avatarPath = ui.value.babies.firstOrNull { it.id == babyId }?.avatarPath
            currentCoroutineContext().ensureActive()
            val ok = withContext(NonCancellable) {
                careLog.deleteBaby(babyId).also { deleted ->
                    if (deleted) {
                        try {
                            avatarFileStore.delete(avatarPath)
                        } catch (_: Throwable) {
                            // The soft-deleted profile no longer references this local file.
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            onDone(if (ok) "已删除宝宝档案" else "至少保留一位宝宝档案")
        }
    }

    fun previewMerge(
        sourceBabyId: Long,
        targetBabyId: Long,
        onDone: (BabyMergePreview?) -> Unit,
    ) {
        viewModelScope.launch {
            onDone(careLog.previewBabyMerge(sourceBabyId, targetBabyId))
        }
    }

    fun merge(preview: BabyMergePreview, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val merged = careLog.mergeBabyProfiles(
                sourceBabyId = preview.sourceBabyId,
                targetBabyId = preview.targetBabyId,
            )
            onDone(if (merged) "宝宝档案已合并" else "档案状态已变化，请重新预览")
        }
    }

    fun submitFamilyWizard(
        snapshot: FamilyWizardSnapshot,
        bootstrapSecret: String = "",
        ownerTakeover: Boolean = false,
    ) {
        viewModelScope.launch {
            familyWizard.submit(snapshot, bootstrapSecret, ownerTakeover)
            if (familyWizard.state.value is FamilyWizardState.Completed) {
                refreshMembersNow(showErrors = true)
            }
        }
    }

    fun beginFamilyWizard(snapshot: FamilyWizardSnapshot) {
        familyWizard.begin(snapshot)
    }

    fun connectEndpoint(endpointDraft: String) {
        viewModelScope.launch {
            familyWizard.connectEndpoint(FamilyWizardEntry.Account, endpointDraft)
        }
    }

    fun trustCertificate(candidate: CertificateTrustCandidate) {
        viewModelScope.launch {
            familyWizard.trustCertificate(FamilyWizardEntry.Account, candidate)
        }
    }

    fun keepOffline() {
        familyWizard.keepOffline(FamilyWizardEntry.Account)
    }

    fun forgetEndpoint() {
        viewModelScope.launch { familyWizard.forgetEndpoint(FamilyWizardEntry.Account) }
    }

    fun consumeFamilyWizardCompletion(): FamilyWizardOutcome? =
        familyWizard.consumeCompletion()

    fun checkMemberApproval() {
        viewModelScope.launch { familyWizard.checkMemberApproval() }
    }

    fun cancelMemberApproval() {
        viewModelScope.launch { familyWizard.cancelMemberApproval() }
    }

    fun approveNewMemberLogin(requestId: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = sync.approveNewMemberLogin(requestId)
            if (result.isSuccess) refreshMembersNow(showErrors = true)
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
            if (result.isSuccess) refreshMembersNow(showErrors = true)
            onDone(result.exceptionOrNull()?.let { familySyncError(it, "绑定失败，请稍后重试") })
        }
    }

    fun rejectMemberLogin(requestId: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = sync.rejectMemberLogin(requestId)
            if (result.isSuccess) refreshMembersNow(showErrors = true)
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

    fun verifyMemberLoginQr(
        payload: MemberLoginQrPayload,
        onResult: (SetupProbeResult) -> Unit,
    ) {
        memberLoginQrVerificationJob?.cancel()
        memberLoginQrVerificationJob = viewModelScope.launch {
            onResult(sync.verifyEndpoint(payload.endpoint))
        }
    }

    fun cancelMemberLoginQrVerification() {
        memberLoginQrVerificationJob?.cancel()
        memberLoginQrVerificationJob = null
    }

    fun claimMemberLoginQr(
        payload: MemberLoginQrPayload,
        deviceName: String,
        onDone: (InitialFamilyDataRecovery?, String?) -> Unit,
    ) {
        viewModelScope.launch {
            val trusted = sync.rememberEndpoint(payload.endpoint)
            if (trusted.isFailure) {
                onDone(null, "无法保存家庭服务器信任信息，请重试")
                return@launch
            }
            val result = sync.claimMemberLoginQr(payload, deviceName)
            result.fold(
                onSuccess = { onDone(it.dataRecovery, null) },
                onFailure = { error ->
                    onDone(
                        null,
                        when (error) {
                            is MemberLoginQrUnavailableException -> error.message
                            is MemberLoginQrTrustChangedException -> error.message
                            else -> familySyncError(error, "登录失败，请稍后重试")
                        },
                    )
                },
            )
        }
    }

    fun retryMemberLoginQrRecovery(onDone: (String?) -> Unit) {
        viewModelScope.launch {
            onDone(
                sync.sync(SyncTrigger.PullToRefresh).exceptionOrNull()?.let {
                    familySyncError(it, "首次同步仍未完成，请稍后重试")
                },
            )
        }
    }

    fun leave(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val id = ui.value.familyId
            val result = sync.leave(id)
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
                    refreshMembersNow(showErrors = true)
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
                refreshMembersNow(showErrors = true)
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
                careLog.updateLocalDisplayName(outcome.displayName)
            }
            if (outcome != null) {
                refreshMembersNow(showErrors = true)
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
            if (result.isSuccess) refreshMembersNow(showErrors = true)
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
            if (result.isSuccess) refreshMembersNow(showErrors = true)
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
            if (result.isSuccess) refreshMembersNow(showErrors = true)
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
            if (result.isSuccess) refreshMembersNow(showErrors = true)
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
            if (result.isSuccess) refreshMembersNow(showErrors = true)
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
