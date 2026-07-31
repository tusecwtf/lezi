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

/** Local installed app identity used for app-update comparisons (versionCode only). */
data class ClientAppVersion(
    val versionCode: Int,
    val versionName: String,
    val packageName: String = "com.lezi.babylog",
) {
    init {
        require(versionCode > 0) { "versionCode must be positive" }
        require(versionName.isNotBlank()) { "versionName must not be blank" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
    }

    companion object {
        /** Matches current release identity from docs/prd/tech.md / app build.gradle.kts. */
        val FALLBACK = ClientAppVersion(versionCode = 6, versionName = "0.3.0")
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
 * Forced-upgrade UX is ticket 03; this wave only surfaces optional updates when
 * the server package is newer than the local [ClientAppVersion.versionCode].
 */
sealed interface AppUpdateCheckResult {
    /** Device has no usable family session; no anonymous update request is made. */
    data object NotJoined : AppUpdateCheckResult
    /** Local versionCode is at least the server package versionCode. */
    data object UpToDate : AppUpdateCheckResult
    /** Server advertises a newer package; user may download and install. */
    data class OptionalUpdate(val metadata: AppUpdateMetadata) : AppUpdateCheckResult
}

/**
 * Outcome of [SyncPort.installAvailableAppUpdate] after download + sha256 verify.
 * Does not report final PackageInstaller success (system UI is async).
 */
sealed interface AppUpdateInstallResult {
    /** PackageInstaller session committed; system may show confirm UI. */
    data object SessionStarted : AppUpdateInstallResult
    /** App lacks permission to request package installs; open unknown-sources settings. */
    data object RequiresInstallPermission : AppUpdateInstallResult
}

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
    ): Result<SyncSession> = Result.failure(SyncNotEnabledException())
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
     */
    suspend fun checkAppUpdate(): Result<AppUpdateCheckResult> =
        Result.success(AppUpdateCheckResult.NotJoined)

    /**
     * Downloads the release APK for [metadata] from the trusted family server,
     * verifies sha256, then starts a [android.content.pm.PackageInstaller] session.
     *
     * Staging is limited to app-private cache and is always cleaned up after the
     * attempt (success path after session commit, failure/cancel paths too).
     * Not joined or foreground/trust gate failures return [Result.failure].
     */
    suspend fun installAvailableAppUpdate(
        metadata: AppUpdateMetadata,
    ): Result<AppUpdateInstallResult> =
        Result.failure(SyncNotEnabledException())

    /** Best-effort delete of private app-update staging APKs. */
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

    override suspend fun installAvailableAppUpdate(
        metadata: AppUpdateMetadata,
    ): Result<AppUpdateInstallResult> = Result.failure(SyncNotEnabledException())

    override suspend fun cleanupAppUpdateStaging(): Result<Unit> = Result.success(Unit)
}
