package com.lezi.babylog.feature.family.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.domain.BabyMergePreview
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.DuplicateBabyNicknameException
import com.lezi.babylog.domain.UpdateBabyInput
import com.lezi.babylog.feature.family.BabyAvatarFileStore
import com.lezi.babylog.feature.family.LOCAL_FAMILY_DISPLAY_NAME
import com.lezi.babylog.feature.family.canEditFamilyAvatar
import com.lezi.babylog.feature.family.displayFamilyName
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.AppUpdateUiOutcome
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.appUpdateInstallUiOutcome
import com.lezi.babylog.sync.appUpdateUiOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Account overview call-flow host: 账户概览 read model, one-line 同步状态 inputs,
 * optional self-hosted app-update entry, and thin baby-profile/avatar adapters.
 *
 * Does not own members/device roster commands or family-wizard lifecycle.
 */
data class AccountOverviewUi(
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
    val pendingMemberLogin: PendingMemberLogin? = null,
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

@HiltViewModel
class AccountOverviewHost @Inject constructor(
    private val sync: SyncPort,
    private val careLog: CareLog,
    private val avatarFileStore: BabyAvatarFileStore,
) : ViewModel() {
    private val profileSaveMutex = Mutex()

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
        AccountOverviewUi(
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

    val ui: StateFlow<AccountOverviewUi> = combine(
        baseUi,
        sync.availableOptionalAppUpdate(),
    ) { family, optionalUpdate ->
        // Only show the banner when the account is joined; never for offline/unjoined.
        family.copy(
            optionalAppUpdate = optionalUpdate.takeIf { family.enabled },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountOverviewUi())

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
}
