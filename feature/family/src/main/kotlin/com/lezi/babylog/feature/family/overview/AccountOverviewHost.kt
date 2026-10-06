package com.lezi.babylog.feature.family.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.SingleFlightAction
import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.domain.BabyMergePreview
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CreateBabyInput
import com.lezi.babylog.domain.family.BabyLocalLayoutCommands
import com.lezi.babylog.feature.family.FamilyIdentityUi
import com.lezi.babylog.domain.family.LocalFamilyIdentityInvalidations
import com.lezi.babylog.feature.family.localFamilyIdentityReloadKey
import com.lezi.babylog.feature.family.baby.BabyAvatarFileStore
import com.lezi.babylog.feature.family.components.canEditFamilyAvatar
import com.lezi.babylog.feature.family.components.displayFamilyName
import com.lezi.babylog.feature.family.components.FamilyDestructiveActionGate
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.appupdate.AppUpdateUiOutcome
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.session.ShallowSyncLine
import com.lezi.babylog.sync.session.ShallowSyncState
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.session.SyncSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    val hydrated: Boolean = false,
    val identity: FamilyIdentityUi = FamilyIdentityUi(),
    val status: SyncStatus = SyncStatus.Disabled,
    val hasLocalBaby: Boolean = false,
    val current: Baby? = null,
    val babies: List<Baby> = emptyList(),
    /** Local pre-join profiles kept only as merge sources while this device is a member. */
    val localOrphanBabies: List<Baby> = emptyList(),
    val lastSuccessAt: Long? = null,
    val pendingMemberLogin: PendingMemberLogin? = null,
    val shallowSyncLine: ShallowSyncLine = ShallowSyncLine(
        state = ShallowSyncState.Unjoined,
        text = "尚未加入家庭 · 数据仅保存在本机",
    ),
    /** Family identity is retained while credentials require recovery. */
    val retainedFamilyIdentity: Boolean = false,
    /**
     * Optional self-hosted app update from handshake/sync discovery.
     * Null when none, not joined, up-to-date, or dismissed for this process session.
     */
    val optionalAppUpdate: AppUpdateMetadata? = null,
    /** One item per open causal root conflict; branch count never inflates this badge. */
    val openConflictCount: Int = 0,
    /** Classified last sync/session failure; tap the one-line status to explain. */
    val lastFailureKind: FailureKind? = null,
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

private data class AccountSyncProjection(
    val session: SyncSession,
    val pendingMemberLogin: PendingMemberLogin?,
    val shallowSyncLine: ShallowSyncLine,
)

private data class OverviewInputs(
    val status: SyncStatus,
    val hasBaby: Boolean,
    val current: Baby?,
    val babyLists: Pair<List<Baby>, List<Baby>>,
    val syncState: AccountSyncProjection,
)


@HiltViewModel
class AccountOverviewHost @Inject constructor(
    private val sync: SyncPort,
    private val careLog: CareLog,
    private val babyLocalLayout: BabyLocalLayoutCommands,
    private val avatarFileStore: BabyAvatarFileStore,
) : ViewModel() {
    private val profileSaveMutex = Mutex()
    private val destructiveAction = FamilyDestructiveActionGate()
    private val addBabyAction = SingleFlightAction()
    private val reconcileAction = SingleFlightAction()
    private val appUpdate = AppUpdateOutcomeMachine(sync)

    val addingBaby: StateFlow<Boolean> = addBabyAction.busy

    private val babySurfaces = combine(
        careLog.observeBabies(),
        careLog.observeMemberLocalBabyOrphans(),
    ) { babies, orphans -> babies to orphans }

    private val syncIdentity = combine(
        sync.session(),
        sync.pendingMemberLogin(),
        sync.shallowStatus(),
    ) { session, pending, shallowSyncLine ->
        AccountSyncProjection(session, pending, shallowSyncLine)
    }

    private val localIdentity = combine(
        sync.session().map(::localFamilyIdentityReloadKey).distinctUntilChanged(),
        LocalFamilyIdentityInvalidations.epoch,
    ) { _, _ ->
        careLog.localFamilyIdentity()
    }

    private val overviewInputs = combine(
        sync.status(),
        careLog.observeHasBaby(),
        careLog.observeCurrentBaby(),
        babySurfaces,
        syncIdentity,
    ) { st, hasBaby, current, babyLists, syncState ->
        OverviewInputs(st, hasBaby, current, babyLists, syncState)
    }

    private val baseUi = combine(overviewInputs, localIdentity) { inputs, identity ->
        val st = inputs.status
        val hasBaby = inputs.hasBaby
        val current = inputs.current
        val (babies, localOrphans) = inputs.babyLists
        val syncState = inputs.syncState
        val session = syncState.session
        val pendingMemberLogin = syncState.pendingMemberLogin
        AccountOverviewUi(
            hydrated = true,
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
            shallowSyncLine = syncState.shallowSyncLine,
            retainedFamilyIdentity = session.familyId.isNotBlank(),
        )
    }

    val ui: StateFlow<AccountOverviewUi> = combine(
        baseUi,
        sync.availableOptionalAppUpdate(),
        careLog.observeOpenConflictInbox(),
        sync.lastFailureKind(),
    ) { family, optionalUpdate, inbox, lastFailureKind ->
        // Only show the banner when the account is joined; never for offline/unjoined.
        family.copy(
            optionalAppUpdate = optionalUpdate.takeIf { family.enabled },
            openConflictCount = inbox.count.takeIf { family.enabled } ?: 0,
            lastFailureKind = lastFailureKind,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountOverviewUi())

    val appUpdateOutcome: StateFlow<AppUpdateUiOutcome?> = appUpdate.outcome
    val checkingAppUpdate: StateFlow<Boolean> = appUpdate.checking
    val installingAppUpdate: StateFlow<Boolean> = appUpdate.installing
    val destructiveBusy: StateFlow<Boolean> = destructiveAction.busy

    /** Open the same optional confirm flow as the settings about path. */
    fun openOptionalAppUpdate(metadata: AppUpdateMetadata) = appUpdate.openOptional(metadata)

    /** Banner "稍后" or dialog dismiss: suppress this versionCode for the process session. */
    fun dismissOptionalAppUpdate(versionCode: Int) = appUpdate.dismissOptional(versionCode)

    fun dismissAppUpdateOutcome() = appUpdate.dismissOutcome()

    /**
     * Same PullToRefresh cycle as 记录 / 汇总 / 成长 pull. Account has no
     * pull gesture; Error tap and explanation retry buttons call this.
     */
    fun retryReconcile() {
        viewModelScope.launch {
            reconcileAction.run {
                runCatching { sync.syncWhenAvailable(SyncTrigger.PullToRefresh) }
                .onFailure { error -> if (error is CancellationException) throw error }
            }
        }
    }

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
        viewModelScope.launch {
            // A clear racing the switch (epoch exceptions) or a member without
            // permission keeps the current baby instead of crashing.
            runCatching { careLog.setCurrentBaby(id) }
                .onFailure { error -> if (error is CancellationException) throw error }
        }
    }

    fun setBabyLocalTheme(id: Long, argb: Int, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            onDone(babyLocalLayout.setTheme(id, argb))
        }
    }

    fun moveBabyLocal(id: Long, delta: Int, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            onDone(babyLocalLayout.move(id, delta))
        }
    }

    fun addBaby(
        nickname: String,
        sex: String?,
        birthdayEpochDay: Long,
        birthWeightGrams: Int?,
        themeColorArgb: Int,
        avatarJpeg: ByteArray?,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            var failure: String? = null
            val accepted = addBabyAction.run {
                failure = try {
                    profileSaveMutex.withLock {
                        val id = careLog.addBaby(
                            CreateBabyInput(
                                nickname = nickname,
                                sex = sex,
                                birthdayEpochDay = birthdayEpochDay,
                                birthWeightGrams = birthWeightGrams,
                                themeColorArgb = themeColorArgb,
                            ),
                        )
                        if (avatarJpeg != null) {
                            val created = careLog.listBabies().firstOrNull { it.id == id }
                            if (created != null) {
                                val avatarError = saveBabyProfileWithAvatar(
                                    careLog = careLog,
                                    avatarFileStore = avatarFileStore,
                                    existing = created,
                                    nickname = nickname,
                                    sex = sex,
                                    birthdayEpochDay = birthdayEpochDay,
                                    birthWeightGrams = birthWeightGrams,
                                    avatarJpeg = avatarJpeg,
                                    removeAvatar = false,
                                    mayEditAvatar = true,
                                )
                                if (avatarError != null) return@withLock avatarError
                            }
                        }
                        null
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    productUiError(error, "添加失败")
                }
            }
            if (accepted) onDone(failure)
        }
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

    fun deleteBaby(babyId: Long, onDone: (success: Boolean, message: String) -> Unit) {
        viewModelScope.launch {
            var outcome: Pair<Boolean, String>? = null
            val accepted = destructiveAction.run {
                outcome = try {
                    val deleted = deleteBabyProfileWithAvatar(
                        careLog = careLog,
                        babyId = babyId,
                    )
                    currentCoroutineContext().ensureActive()
                    deleted to if (deleted) "已删除宝宝档案" else "至少保留一位宝宝档案"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    false to productUiError(error, "删除宝宝失败，请稍后重试")
                }
            }
            if (accepted) outcome?.let { (success, message) -> onDone(success, message) }
        }
    }

    fun previewMerge(
        sourceBabyId: Long,
        targetBabyId: Long,
        onDone: (BabyMergePreview?) -> Unit,
    ) {
        viewModelScope.launch {
            // Failure surfaces as the existing empty-preview state (the merge
            // dialog simply shows nothing to confirm) instead of crashing.
            onDone(
                runCatching { careLog.previewBabyMerge(sourceBabyId, targetBabyId) }
                .onFailure { error -> if (error is CancellationException) throw error }.getOrNull(),
            )
        }
    }

    fun merge(preview: BabyMergePreview, onDone: (success: Boolean, message: String) -> Unit) {
        viewModelScope.launch {
            var outcome: Pair<Boolean, String>? = null
            val accepted = destructiveAction.run {
                outcome = try {
                    val merged = careLog.mergeBabyProfiles(
                        sourceBabyId = preview.sourceBabyId,
                        targetBabyId = preview.targetBabyId,
                    )
                    merged to if (merged) {
                        "宝宝档案已合并"
                    } else {
                        "档案状态已变化，请重新预览"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    false to productUiError(error, "合并没有完成，两个宝宝的记录都还在、没有丢失，可重试")
                }
            }
            if (accepted) outcome?.let { (success, message) -> onDone(success, message) }
        }
    }
}
