package com.lezi.babylog.feature.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.domain.BabyMergePreview
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.DuplicateBabyNicknameException
import com.lezi.babylog.domain.JoinFamilyRequest
import com.lezi.babylog.domain.JoinFamilyResult
import com.lezi.babylog.domain.JoinFamilyUseCase
import com.lezi.babylog.domain.UpdateBabyInput
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.InvitePayload
import com.lezi.babylog.sync.InvitePayloadCodec
import com.lezi.babylog.sync.JoinFamilyDraft
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    val allowedSsids: List<String> = emptyList(),
    val role: FamilyRole = FamilyRole.None,
    val lastSuccessAt: Long? = null,
    /** Raw shared family name from session cache; null when empty/unknown. */
    val familyName: String? = null,
    val members: List<FamilyMember> = emptyList(),
    val membersLoaded: Boolean = false,
    val membersLoading: Boolean = false,
    val membersError: String? = null,
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
)

data class FamilyInviteView(
    val code: String,
    val payload: String,
    val expiresAt: Long,
)

@HiltViewModel
class FamilyViewModel @Inject constructor(
    private val sync: SyncPort,
    private val careLog: CareLog,
    private val avatarFileStore: BabyAvatarFileStore,
    private val networkState: com.lezi.babylog.sync.NetworkState,
    private val joinFamily: JoinFamilyUseCase,
) : ViewModel() {
    private val profileSaveMutex = Mutex()
    private val memberRefreshMutex = Mutex()
    private val familyMembers = MutableStateFlow(FamilyMembersState())

    fun currentWifiSsid(): String? = networkState.currentWifiSsid()

    private val babySurfaces = combine(
        careLog.observeBabies(),
        careLog.observeMemberLocalBabyOrphans(),
    ) { babies, orphans -> babies to orphans }

    private val baseUi = combine(
        sync.status(),
        careLog.observeHasBaby(),
        careLog.observeCurrentBaby(),
        babySurfaces,
        sync.session(),
    ) { st, hasBaby, current, babyLists, session ->
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
            allowedSsids = session.allowedSsids,
            role = session.role,
            lastSuccessAt = session.lastSuccessAt,
            familyName = session.familyName,
        )
    }

    val ui = combine(baseUi, familyMembers) { family, memberState ->
        if (family.enabled && memberState.familyId == family.familyId) {
            family.copy(
                members = memberState.members,
                membersLoaded = memberState.loaded,
                membersLoading = memberState.loading,
                membersError = memberState.error,
            )
        } else {
            family
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FamilyUi())

    fun refreshMembers(showErrors: Boolean = true) {
        viewModelScope.launch { refreshMembersNow(showErrors) }
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
        )
        val result = sync.listFamilyMembers()
        if (sync.session().first().familyId != session.familyId) return@withLock
        familyMembers.value = result.fold(
            onSuccess = { members ->
                FamilyMembersState(
                    familyId = session.familyId,
                    members = members,
                    loaded = true,
                )
            },
            onFailure = { error ->
                FamilyMembersState(
                    familyId = session.familyId,
                    members = previous?.members.orEmpty(),
                    loaded = previous?.loaded ?: false,
                    error = if (showErrors) {
                        familySyncError(error, "暂时无法读取成员，请连接家庭 Wi‑Fi 后重试")
                    } else {
                        null
                    },
                )
            },
        )
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

    fun createInvite(onResult: (Result<FamilyInviteView>) -> Unit) {
        viewModelScope.launch {
            val familyId = ui.value.familyId
            val result = sync.createInvite(familyId)
            onResult(
                result
                    .map {
                        val session = ui.value
                        val host = session.serverHost.ifBlank {
                            HomeLanServerConfig.fromBaseUrl(session.baseUrl).host
                        }
                        val port = session.serverPort.takeIf { p -> p in 1..65535 }
                            ?: HomeLanServerConfig.fromBaseUrl(session.baseUrl).port
                        val base = session.baseUrl.ifBlank {
                            HomeLanServerConfig(
                                host = host,
                                port = port,
                                scheme = session.serverScheme,
                            ).baseUrl
                        }
                        FamilyInviteView(
                            code = it.code,
                            payload = InvitePayloadCodec.encode(
                                InvitePayload(
                                    baseUrl = base,
                                    code = it.code,
                                    host = host,
                                    port = port,
                                    ssids = session.allowedSsids,
                                ),
                            ),
                            expiresAt = it.expiresAt,
                        )
                    }
                    .recoverCatching {
                        throw IllegalStateException(
                            familySyncError(it, fallback = "生成共享码失败，请稍后重试"),
                        )
                    },
            )
        }
    }

    fun join(
        draft: JoinFamilyDraft,
        displayName: String,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            when (
                val result = joinFamily.execute(
                    JoinFamilyRequest(draft = draft, displayName = displayName),
                )
            ) {
                is JoinFamilyResult.Joined -> {
                    onDone(true, "已加入家庭")
                    refreshMembersNow(showErrors = true)
                }
                is JoinFamilyResult.Failed -> onDone(false, result.message)
            }
        }
    }

    fun leave(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val id = ui.value.familyId
            val result = sync.leave(id)
            onMessage(
                result.fold(
                    onSuccess = { "已离开家庭" },
                    onFailure = { familySyncError(it, fallback = "离开家庭失败，请稍后重试") },
                ),
            )
            if (result.isSuccess) familyMembers.value = FamilyMembersState()
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
                onDone(true, "已将「$label」移出家庭")
            } else {
                onDone(
                    false,
                    familySyncError(
                        result.exceptionOrNull() ?: Exception(),
                        fallback = "移除家人失败，请稍后重试",
                    ),
                )
            }
        }
    }

    fun pullNow(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val id = ui.value.familyId
            val r = sync.sync(SyncTrigger.PullToRefresh)
            onMessage(
                r.fold(
                    onSuccess = { "已同步" },
                    onFailure = { familySyncError(it, fallback = "同步失败，请稍后重试") },
                ),
            )
            if (r.isSuccess) refreshMembersNow(showErrors = true)
        }
    }

    internal fun saveHomeLanConfig(
        host: String,
        portText: String,
        ssid1: String,
        ssid2: String,
        fallbackScheme: String,
        onResult: (NetworkSaveResult) -> Unit,
    ) {
        viewModelScope.launch {
            val config = runCatching {
                com.lezi.babylog.sync.HomeLanServerConfig.fromUserInput(
                    rawHostOrUrl = host,
                    explicitPort = portText.toIntOrNull(),
                    allowedSsids = listOf(ssid1, ssid2),
                    fallbackScheme = fallbackScheme,
                )
            }.getOrElse {
                onResult(NetworkSaveResult.Failed(it.message ?: "服务器地址无效"))
                return@launch
            }
            onResult(
                sync.saveHomeLanConfig(config).fold(
                    onSuccess = { NetworkSaveResult.Saved("家庭网络与服务器已保存") },
                    onFailure = {
                        NetworkSaveResult.Failed(familySyncError(it, "保存失败"))
                    },
                ),
            )
        }
    }

    fun createFamily(
        displayName: String,
        bootstrapSecret: String,
        familyName: String = "",
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            validateFamilyDisplayNameInput(displayName)?.let {
                onDone(false, it)
                return@launch
            }
            validateFamilyNameInput(familyName)?.let {
                onDone(false, it)
                return@launch
            }
            val result = sync.createFamily(
                displayName = displayName.trim(),
                bootstrapSecret = bootstrapSecret,
                familyName = familyName.trim().ifEmpty { null },
            )
            if (result.isSuccess) {
                careLog.updateLocalDisplayName(displayName.trim())
            }
            onDone(
                result.isSuccess,
                result.fold(
                    ::createFamilyResultCopy,
                    { familySyncError(it, "创建家庭失败") },
                ),
            )
            if (result.isSuccess) refreshMembersNow(showErrors = true)
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
            val result = sync.renameFamily(familyName.trim().ifEmpty { null })
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
            if (result.isSuccess) {
                careLog.updateLocalDisplayName(displayName.trim())
                refreshMembersNow(showErrors = true)
            }
            onDone(
                result.isSuccess,
                result.fold(
                    onSuccess = { "家庭称呼已更新" },
                    onFailure = { familySyncError(it, "更新称呼失败") },
                ),
            )
        }
    }

    fun deleteFamily(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val result = sync.deleteFamily()
            onMessage(result.fold(
                { "家庭数据已删除" },
                { familySyncError(it, "删除家庭失败") },
            ))
            if (result.isSuccess) familyMembers.value = FamilyMembersState()
        }
    }
}
