package com.lezi.babylog.feature.family.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.domain.BabyMergePreview
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.feature.family.FamilyIdentityUi
import com.lezi.babylog.feature.family.baby.BabyAvatarFileStore
import com.lezi.babylog.feature.family.components.canEditFamilyAvatar
import com.lezi.babylog.feature.family.components.displayFamilyName
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.AppUpdateUiOutcome
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.SyncPort
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Account overview call-flow host: 账户概览 read model, one-line 同步状态 inputs,
 * optional self-hosted app-update entry, and thin baby-profile/avatar adapters.
 *
 * Does not own members/device roster commands or family-wizard lifecycle.
 * Technical endpoint credentials stay off this product surface (wizard owns seed).
 */
data class AccountOverviewUi(
    val identity: FamilyIdentityUi = FamilyIdentityUi(),
    val status: SyncStatus = SyncStatus.Disabled,
    val hasLocalBaby: Boolean = false,
    val current: Baby? = null,
    val babies: List<Baby> = emptyList(),
    /** Local pre-join profiles kept only as merge sources while this device is a member. */
    val localOrphanBabies: List<Baby> = emptyList(),
    val lastSuccessAt: Long? = null,
    val pendingMemberLogin: PendingMemberLogin? = null,
    /**
     * Optional self-hosted app update from handshake/sync discovery.
     * Null when none, not joined, up-to-date, or dismissed for this process session.
     */
    val optionalAppUpdate: AppUpdateMetadata? = null,
) {
    val displayName: String get() = identity.displayName
    val enabled: Boolean get() = identity.enabled
    val familyId: String get() = identity.familyId
    val membershipId: String get() = identity.membershipId
    val role get() = identity.role
    val familyName: String? get() = identity.familyName

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
    private val appUpdate = AppUpdateOutcomeMachine(sync)

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
            identity = FamilyIdentityUi(
                displayName = identity.displayName,
                enabled = session.isJoined,
                familyId = session.familyId.ifBlank { identity.familyId.toString() },
                membershipId = session.membershipId,
                role = session.role,
                familyName = session.familyName,
            ),
            status = st,
            hasLocalBaby = hasBaby,
            current = current,
            babies = babies,
            localOrphanBabies = localOrphans,
            lastSuccessAt = session.lastSuccessAt,
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

    val appUpdateOutcome: StateFlow<AppUpdateUiOutcome?> = appUpdate.outcome
    val checkingAppUpdate: StateFlow<Boolean> = appUpdate.checking
    val installingAppUpdate: StateFlow<Boolean> = appUpdate.installing

    /** Open the same optional confirm flow as the settings about path. */
    fun openOptionalAppUpdate(metadata: AppUpdateMetadata) = appUpdate.openOptional(metadata)

    /** Banner "稍后" or dialog dismiss: suppress this versionCode for the process session. */
    fun dismissOptionalAppUpdate(versionCode: Int) = appUpdate.dismissOptional(versionCode)

    fun dismissAppUpdateOutcome() = appUpdate.dismissOutcome()

    /**
     * Manual / PackageUnknown-retry check — same busy + [SyncPort.checkAppUpdate] path as
     * Settings so secondary force dialogs share one contract (root overlay stays authoritative).
     */
    fun checkAppUpdate() {
        viewModelScope.launch { appUpdate.check() }
    }

    /**
     * Download → sha256 → staged archive identity (packageName/versionCode/signing)
     * → PackageInstaller (same path as settings; see [SyncPort.installAvailableAppUpdate]).
     */
    fun installOptionalUpdate(metadata: AppUpdateMetadata) {
        viewModelScope.launch { appUpdate.install(metadata) }
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
                val errorMessage = saveBabyProfileWithAvatar(
                    careLog = careLog,
                    avatarFileStore = avatarFileStore,
                    existing = existing,
                    nickname = nickname,
                    sex = sex,
                    birthdayEpochDay = birthdayEpochDay,
                    birthWeightGrams = birthWeightGrams,
                    avatarJpeg = avatarJpeg,
                    removeAvatar = removeAvatar,
                    mayEditAvatar = canEditFamilyAvatar(ui.value.role),
                )
                currentCoroutineContext().ensureActive()
                onDone(errorMessage)
            }
        }
    }

    fun deleteBaby(babyId: Long, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val avatarPath = ui.value.babies.firstOrNull { it.id == babyId }?.avatarPath
            currentCoroutineContext().ensureActive()
            val ok = deleteBabyProfileWithAvatar(
                careLog = careLog,
                avatarFileStore = avatarFileStore,
                babyId = babyId,
                avatarPath = avatarPath,
            )
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
