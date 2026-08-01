package com.lezi.babylog.sync

import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privacy-preserving family member projection from the home server.
 *
 * [membershipId] is the server-minted immutable membership identity (UUID) and
 * the only Record-author link. Device details are present only when authorized:
 * Owner for every member, ordinary Member for self only.
 */
data class FamilyDevice(
    /** Opaque server action key; never rendered as account copy. */
    val deviceId: String,
    val deviceName: String,
    val lastUsedAtEpochSeconds: Long,
    val isCurrent: Boolean,
) {
    init {
        require(deviceId.isNotBlank()) { "家庭设备 ID 不能为空" }
        require(deviceName.isNotBlank()) { "家庭设备称呼不能为空" }
        require(lastUsedAtEpochSeconds >= 0) { "家庭设备最近使用时间无效" }
    }
}

data class FamilyMember(
    val displayName: String,
    val role: FamilyRole,
    val isSelf: Boolean,
    val membershipId: String,
    /** Null means this viewer is not authorized to receive this member's device details. */
    val devices: List<FamilyDevice>? = null,
) {
    init {
        require(displayName.isNotBlank()) { "家庭成员称呼不能为空" }
        require(membershipId.isNotBlank()) { "家庭成员 membership_id 不能为空" }
    }
}

enum class SyncTrigger { Foreground, PullToRefresh, LocalWrite }

data class SyncPlan(val push: Boolean, val pull: Boolean) {
    companion object {
        fun forTrigger(trigger: SyncTrigger): SyncPlan = when (trigger) {
            SyncTrigger.Foreground, SyncTrigger.PullToRefresh -> SyncPlan(push = true, pull = true)
            SyncTrigger.LocalWrite -> SyncPlan(push = true, pull = false)
        }
    }
}

/**
 * Domain-owned two-phase local clear run under the sync barrier.
 *
 * [withLocalExclusion] supplies the shared calendar/reminder guard. The sync
 * implementation keeps that exclusion around its Room marker transaction,
 * replica finalization, and [finishCommitted], enforcing syncMutex → local
 * guard ordering for clear, pull projections, and crash recovery.
 */
interface LocalClearWorkflow {
    suspend fun <T> withLocalExclusion(block: suspend () -> T): T
    suspend fun clearRoom()
    suspend fun finishCommitted()
}

fun interface LocalClearRecoveryGate {
    /** @return the widest previously committed clear resumed to completion. */
    suspend fun recoverPendingLocalClear(): LocalDataClearScope?
}

class NoOpLocalClearRecoveryGate : LocalClearRecoveryGate {
    override suspend fun recoverPendingLocalClear(): LocalDataClearScope? = null
}

fun interface RemovedDeviceLocalClearGate {
    suspend fun clearAllLocalFamilyData()
}

class NoOpRemovedDeviceLocalClearGate : RemovedDeviceLocalClearGate {
    override suspend fun clearAllLocalFamilyData() = Unit
}

class SyncNotEnabledException : Exception("请先配置家庭服务器并加入家庭")
class BootstrapSecretRejectedException : Exception("初始化口令不正确，请核对 NAS 配置")
class OwnerRootPasswordRejectedException : Exception("管理员根密码不正确，请重试")
class MemberLoginQrUnavailableException : Exception("这个二维码已失效，请让管理员重新生成")
class MemberLoginQrTrustChangedException : Exception("家庭服务器安全信息不一致，登录已停止")

/** Outcome of [SyncPort.createFamily]: owner session plus whether the NAS reclaimed. */
enum class InitialFamilyDataRecovery {
    NotRequired,
    Complete,
    RetryRequired,
}

data class CreateFamilyResult(
    val session: SyncSession,
    val reclaimed: Boolean,
    val dataRecovery: InitialFamilyDataRecovery = InitialFamilyDataRecovery.Complete,
)

data class OwnerLoginResult(
    val session: SyncSession,
    val dataRecovery: InitialFamilyDataRecovery,
)

data class MemberLoginQrResult(
    val session: SyncSession,
    val dataRecovery: InitialFamilyDataRecovery,
)

data class PendingMemberLogin(
    val requestId: String,
    val displayName: String,
    val deviceName: String,
    val expiresAtEpochSeconds: Long,
)

sealed interface MemberLoginCheckResult {
    data class Waiting(val request: PendingMemberLogin) : MemberLoginCheckResult
    data class Terminal(val status: MemberLoginStatus) : MemberLoginCheckResult
    data class Joined(
        val session: SyncSession,
        val dataRecovery: InitialFamilyDataRecovery,
    ) : MemberLoginCheckResult
}

/**
 * Local installed app identity used for app-update gates.
 *
 * - [packageName]: process applicationId; must match server metadata and staged APK
 *   before an update is offered or installed.
 * - [versionCode]: dual-tier force/optional comparison and staged-APK version gate.
 * - [versionName]: display only.
 */
data class ClientAppVersion(
    val versionCode: Int,
    val versionName: String,
    val packageName: String = "com.lezi.babylog",
    val localDataContractVersion: Int = 1,
) {
    init {
        require(versionCode > 0) { "versionCode must be positive" }
        require(versionName.isNotBlank()) { "versionName must not be blank" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(localDataContractVersion > 0) {
            "localDataContractVersion must be positive"
        }
    }

    companion object {
        /** Matches current release identity from docs/prd/tech.md / app build.gradle.kts. */
        val FALLBACK = ClientAppVersion(versionCode = 8, versionName = "0.3.1")
    }
}

/** Server-published self-hosted app-update metadata (wire snake_case). */
data class AppUpdateMetadata(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val minSupportedVersionCode: Int,
    val sha256: String,
    val releaseNotes: String? = null,
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(versionCode > 0) { "versionCode must be positive" }
        require(versionName.isNotBlank()) { "versionName must not be blank" }
        require(minSupportedVersionCode >= 0) { "minSupportedVersionCode must be non-negative" }
        require(sha256.matches(Regex("^[0-9a-f]{64}$"))) {
            "sha256 must be 64 lowercase hex characters"
        }
    }
}

/**
 * High-level outcome of [SyncPort.checkAppUpdate].
 *
 * Dual tier on integer versionCode only:
 * - local &lt; minSupported → [ForcedUpdate]
 * - minSupported ≤ local &lt; latest → [OptionalUpdate]
 * - local ≥ latest → [UpToDate]
 */
sealed interface AppUpdateCheckResult {
    /** Device has no usable family session; no anonymous update request is made. */
    data object NotJoined : AppUpdateCheckResult
    /** Local versionCode is at least the server package versionCode. */
    data object UpToDate : AppUpdateCheckResult
    /** Server advertises a newer package; user may download and install later. */
    data class OptionalUpdate(val metadata: AppUpdateMetadata) : AppUpdateCheckResult
    /**
     * Local versionCode is below [AppUpdateMetadata.minSupportedVersionCode].
     * Must surface non-dismissible force UI; optional "稍后" is not allowed.
     */
    data class ForcedUpdate(val metadata: AppUpdateMetadata) : AppUpdateCheckResult
}

/**
 * Non-dismissible force-update surface published by [SyncPort.availableForcedAppUpdate].
 * Null on the flow means the client is not under a force gate.
 *
 * After a server `client_update_required` rejection, the client must still expose a
 * non-silent shell even when package metadata cannot be loaded yet ([PackageUnknown]),
 * so the UI is never "SyncStatus.Idle + no force layer + no error".
 */
sealed interface ForcedAppUpdateState {
    /** Metadata available; user can download/install the package. */
    data class WithPackage(val metadata: AppUpdateMetadata) : ForcedAppUpdateState

    /**
     * Server rejected authoritative sync as requiring a client update, but update
     * package metadata is not available yet. UI shows a force shell with retry check.
     */
    data object PackageUnknown : ForcedAppUpdateState
}

/**
 * Outcome of [SyncPort.installAvailableAppUpdate] after download, sha256, and
 * staged-APK identity (packageName / versionCode / signing cert) checks.
 * Does not report final PackageInstaller success (system UI is async).
 */
sealed interface AppUpdateInstallResult {
    /** PackageInstaller session committed; system may show confirm UI. */
    data object SessionStarted : AppUpdateInstallResult
    /** App lacks permission to request package installs; open unknown-sources settings. */
    data object RequiresInstallPermission : AppUpdateInstallResult
}

/** Product copy when a second install is rejected while another pipeline owns staging. */
const val APP_UPDATE_INSTALL_IN_PROGRESS_MESSAGE = "更新正在进行中，请稍候"

/** Dialog title for [AppUpdateInstallInProgressException] (busy, not hard failure). */
const val APP_UPDATE_INSTALL_IN_PROGRESS_TITLE = "更新进行中"

/**
 * Concurrent [SyncPort.installAvailableAppUpdate] while another pipeline owns the
 * private staging path. Surfaces as busy, not a corrupted package failure.
 */
class AppUpdateInstallInProgressException :
    IllegalStateException(APP_UPDATE_INSTALL_IN_PROGRESS_MESSAGE)

interface SyncPort {
    fun status(): Flow<SyncStatus>
    fun session(): Flow<SyncSession>
    fun verifiedEndpoint(): Flow<TrustedEndpointProfile?> = kotlinx.coroutines.flow.flowOf(null)
    fun pendingMemberLogin(): Flow<PendingMemberLogin?> = kotlinx.coroutines.flow.flowOf(null)
    /** Exact foreground/manual member-login checks observed by an open approval UI. */
    fun memberLoginChecks(): Flow<MemberLoginCheckResult> = kotlinx.coroutines.flow.emptyFlow()
    fun isEnabled(): Boolean
    fun requestSync(trigger: SyncTrigger)
    suspend fun probeEndpoint(endpointDraft: String): SetupProbeResult =
        SetupProbeResult.Failed.Unreachable
    /** Verifies a QR-provided endpoint and pin without persisting its grant or trust decision. */
    suspend fun verifyEndpoint(endpoint: TrustedEndpointProfile): SetupProbeResult =
        SetupProbeResult.Failed.Unreachable
    suspend fun trustCertificate(candidate: CertificateTrustCandidate): SetupProbeResult =
        SetupProbeResult.Failed.Unreachable
    suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile): Result<Unit> =
        Result.failure(SyncNotEnabledException())
    suspend fun forgetEndpoint(): Result<Unit> = Result.success(Unit)
    /** Reclaims exact committed media tombstones; logical mutation success is independent. */
    suspend fun cleanupTombstonedMedia(clientUuids: Set<String>): Result<Unit>
    suspend fun saveServer(baseUrl: String): Result<Unit>
    /** Persists an endpoint origin; trust is established separately by setup probe. */
    suspend fun saveEndpointConfig(config: FamilyEndpointConfig): Result<Unit>
    /**
     * @param displayName 家庭称呼 (product-required; blank rejected at the session seam)
     * @param familyName shared family name (optional; blank → server null + client fallback)
     */
    suspend fun createFamily(
        displayName: String,
        deviceName: String = "Android 设备",
        bootstrapSecret: String,
        familyName: String? = null,
    ): Result<CreateFamilyResult>
    suspend fun ownerLogin(
        deviceName: String,
        rootPassword: String,
        takeover: Boolean = false,
    ): Result<OwnerLoginResult> = Result.failure(SyncNotEnabledException())
    suspend fun requestMemberLogin(
        displayName: String,
        deviceName: String,
    ): Result<PendingMemberLogin> = Result.failure(SyncNotEnabledException())
    suspend fun checkMemberLogin(): Result<MemberLoginCheckResult> =
        Result.failure(SyncNotEnabledException())
    suspend fun cancelMemberLogin(): Result<Unit> = Result.failure(SyncNotEnabledException())
    suspend fun listPendingMemberLogins(): Result<List<PendingMemberLoginRequest>> =
        Result.failure(SyncNotEnabledException())
    suspend fun approveNewMemberLogin(requestId: String): Result<Unit> =
        Result.failure(SyncNotEnabledException())
    suspend fun bindExistingMemberLogin(
        requestId: String,
        membershipId: String,
    ): Result<Unit> = Result.failure(SyncNotEnabledException())
    suspend fun rejectMemberLogin(requestId: String): Result<Unit> =
        Result.failure(SyncNotEnabledException())
    suspend fun createMemberLoginQrPayload(membershipId: String): Result<MemberLoginQrPayload> =
        Result.failure(SyncNotEnabledException())
    suspend fun claimMemberLoginQr(
        payload: MemberLoginQrPayload,
        deviceName: String,
    ): Result<MemberLoginQrResult> = Result.failure(SyncNotEnabledException())
    /** Owner-only rename of the shared family name; current wire requires non-empty. */
    suspend fun renameFamily(familyName: String?): Result<Unit>
    suspend fun sync(trigger: SyncTrigger): Result<Unit>
    suspend fun pull(familyId: String): Result<Unit>
    suspend fun push(familyId: String): Result<Unit>
    suspend fun listFamilyMembers(): Result<List<FamilyMember>>
    /** Owner updates immediately; Member receives a pending approval request. */
    suspend fun updateMyDisplayName(displayName: String): Result<DisplayNameUpdateResult>
    suspend fun listPendingMemberRenameRequests(): Result<List<PendingMemberRenameRequest>> =
        Result.failure(SyncNotEnabledException())
    suspend fun approveMemberRename(requestId: String): Result<Unit> =
        Result.failure(SyncNotEnabledException())
    suspend fun rejectMemberRename(requestId: String): Result<Unit> =
        Result.failure(SyncNotEnabledException())
    suspend fun cancelMyMemberRename(): Result<Unit> = Result.failure(SyncNotEnabledException())
    suspend fun addFamilyMember(displayName: String): Result<FamilyMember> =
        Result.failure(SyncNotEnabledException())
    suspend fun renameFamilyMember(
        membershipId: String,
        displayName: String,
    ): Result<Unit> = Result.failure(SyncNotEnabledException())
    suspend fun renameFamilyDevice(deviceId: String, deviceName: String): Result<Unit> =
        Result.failure(SyncNotEnabledException())
    suspend fun revokeFamilyDevice(deviceId: String): Result<Unit> =
        Result.failure(SyncNotEnabledException())
    suspend fun logoutCurrentDevice(): Result<Unit> = Result.failure(SyncNotEnabledException())
    suspend fun leave(familyId: String): Result<Unit>
    /** Owner removes another active member by server membership id. */
    suspend fun removeMember(membershipId: String): Result<Unit>
    suspend fun deleteFamily(familyName: String, rootPassword: String): Result<Unit>
    /** [workflow] joins domain Room work and committed cleanup to the replica barrier. */
    /**
     * Clears the selected local domain and replica state under one sync barrier.
     * [LocalDataClearScope.AllLocalData] also removes avatar media and all
     * outbox rows so a subsequent join cannot push stale residue.
     */
    suspend fun clearLocalData(
        scope: LocalDataClearScope,
        workflow: LocalClearWorkflow,
    ): Result<Unit>

    /**
     * Checks whether the trusted family server advertises a newer release APK.
     *
     * Not joined → [AppUpdateCheckResult.NotJoined] without network I/O.
     * Joined → authenticated metadata fetch; compares integer versionCode only.
     * Failures do not change [status] (update checks must not look like sync errors).
     */
    suspend fun checkAppUpdate(): Result<AppUpdateCheckResult> =
        Result.success(AppUpdateCheckResult.NotJoined)

    /**
     * Optional update discovered by foreground handshake/sync (or published after a
     * successful manual check). Null when none, not joined, up-to-date, forced, or the
     * user dismissed that versionCode for this process session ("稍后").
     */
    fun availableOptionalAppUpdate(): Flow<AppUpdateMetadata?> =
        kotlinx.coroutines.flow.flowOf(null)

    /**
     * Forced update when local versionCode is below minSupported (from check, handshake,
     * or a server `client_update_required` rejection). Null when not forced.
     *
     * [ForcedAppUpdateState.WithPackage] when installable metadata is known;
     * [ForcedAppUpdateState.PackageUnknown] when the server already gated sync but
     * metadata could not be loaded — still a non-silent force shell with retry.
     * UI must not offer "稍后" for either state.
     */
    fun availableForcedAppUpdate(): Flow<ForcedAppUpdateState?> =
        kotlinx.coroutines.flow.flowOf(null)

    /**
     * Process-session "稍后": hide the optional banner for [versionCode] until the
     * process dies. Does not block a later explicit [checkAppUpdate] dialog path.
     * Does not apply to forced updates.
     */
    fun dismissOptionalAppUpdate(versionCode: Int) = Unit

    /**
     * Downloads the release APK for [metadata] from the trusted family server and
     * installs only after a full fail-closed pipeline:
     * download → sha256 → staged archive identity (packageName / versionCode /
     * signing cert vs local + metadata) → [android.content.pm.PackageInstaller] commit.
     *
     * Download, digest, identity read, staging write, and session commit run on a
     * background dispatcher (not the main thread). At most one install pipeline may
     * own the private staging path at a time; a concurrent call fails with a
     * product-facing "进行中" error instead of racing half-written APKs.
     *
     * Identity or digest failure never commits PackageInstaller for a foreign or
     * mismatched package. Staging is limited to app-private cache and is always
     * cleaned up after the attempt (success path after session commit, failure/cancel
     * paths too). Not joined or foreground/trust gate failures return [Result.failure].
     */
    suspend fun installAvailableAppUpdate(
        metadata: AppUpdateMetadata,
    ): Result<AppUpdateInstallResult> =
        Result.failure(SyncNotEnabledException())

    /**
     * Best-effort delete of private app-update staging APKs.
     * No-op while [installAvailableAppUpdate] holds the staging path.
     */
    suspend fun cleanupAppUpdateStaging(): Result<Unit> = Result.success(Unit)
}

@Singleton
class NoOpSyncPort @Inject constructor() : SyncPort {
    private val status = MutableStateFlow(SyncStatus.Disabled)
    private val session = MutableStateFlow(SyncSession())
    override fun status(): Flow<SyncStatus> = status
    override fun session(): Flow<SyncSession> = session
    override fun pendingMemberLogin(): Flow<PendingMemberLogin?> = kotlinx.coroutines.flow.flowOf(null)
    override fun isEnabled() = false
    override fun requestSync(trigger: SyncTrigger) = Unit
    override suspend fun cleanupTombstonedMedia(clientUuids: Set<String>) = Result.success(Unit)
    override suspend fun saveServer(baseUrl: String) = Result.success(Unit)
    override suspend fun saveEndpointConfig(config: FamilyEndpointConfig) = Result.success(Unit)
    override suspend fun createFamily(
        displayName: String,
        deviceName: String,
        bootstrapSecret: String,
        familyName: String?,
    ) = Result.failure<CreateFamilyResult>(SyncNotEnabledException())
    override suspend fun ownerLogin(
        deviceName: String,
        rootPassword: String,
        takeover: Boolean,
    ) = Result.failure<OwnerLoginResult>(SyncNotEnabledException())
    override suspend fun renameFamily(familyName: String?) =
        Result.failure<Unit>(SyncNotEnabledException())
    override suspend fun sync(trigger: SyncTrigger) = Result.success(Unit)
    override suspend fun pull(familyId: String) = Result.success(Unit)
    override suspend fun push(familyId: String) = Result.success(Unit)
    override suspend fun listFamilyMembers() =
        Result.failure<List<FamilyMember>>(SyncNotEnabledException())
    override suspend fun updateMyDisplayName(displayName: String) =
        Result.failure<DisplayNameUpdateResult>(SyncNotEnabledException())
    override suspend fun leave(familyId: String) = Result.failure<Unit>(SyncNotEnabledException())
    override suspend fun revokeFamilyDevice(deviceId: String) =
        Result.failure<Unit>(SyncNotEnabledException())
    override suspend fun logoutCurrentDevice() = Result.failure<Unit>(SyncNotEnabledException())
    override suspend fun removeMember(membershipId: String) =
        Result.failure<Unit>(SyncNotEnabledException())
    override suspend fun deleteFamily(familyName: String, rootPassword: String) =
        Result.failure<Unit>(SyncNotEnabledException())
    override suspend fun clearLocalData(
        scope: LocalDataClearScope,
        workflow: LocalClearWorkflow,
    ) = runCatching {
        workflow.withLocalExclusion {
            workflow.clearRoom()
            workflow.finishCommitted()
        }
    }

    override suspend fun checkAppUpdate(): Result<AppUpdateCheckResult> =
        Result.success(AppUpdateCheckResult.NotJoined)

    override fun availableOptionalAppUpdate(): Flow<AppUpdateMetadata?> =
        kotlinx.coroutines.flow.flowOf(null)

    override fun availableForcedAppUpdate(): Flow<ForcedAppUpdateState?> =
        kotlinx.coroutines.flow.flowOf(null)

    override fun dismissOptionalAppUpdate(versionCode: Int) = Unit

    override suspend fun installAvailableAppUpdate(
        metadata: AppUpdateMetadata,
    ): Result<AppUpdateInstallResult> = Result.failure(SyncNotEnabledException())

    override suspend fun cleanupAppUpdateStaging(): Result<Unit> = Result.success(Unit)
}
