package com.lezi.babylog.sync

import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class Invite(val code: String, val expiresAt: Long)

/**
 * Privacy-preserving family member projection from the home server.
 *
 * [membershipId] is the server-minted immutable membership identity (UUID) and
 * the only Record-author link. Transport device ids are deliberately absent.
 */
data class FamilyMember(
    val displayName: String,
    val role: FamilyRole,
    val isSelf: Boolean,
    val membershipId: String,
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

class SyncNotEnabledException : Exception("请先配置家庭服务器并加入家庭")
class BootstrapSecretRejectedException : Exception("初始化口令不正确，请核对 NAS 配置")

/** Outcome of [SyncPort.createFamily]: owner session plus whether the NAS reclaimed. */
enum class InitialFamilyDataRecovery {
    NotRequired,
    Complete,
    RetryRequired,
}

data class CreateFamilyResult(
    val session: SyncSession,
    val reclaimed: Boolean,
    val dataRecovery: InitialFamilyDataRecovery = InitialFamilyDataRecovery.NotRequired,
)

interface SyncPort {
    fun status(): Flow<SyncStatus>
    fun session(): Flow<SyncSession>
    fun isEnabled(): Boolean
    fun requestSync(trigger: SyncTrigger)
    suspend fun saveServer(baseUrl: String): Result<Unit>
    /** Persists the host, port, and up to two SSIDs; form defaults are not applied here. */
    suspend fun saveHomeLanConfig(config: HomeLanServerConfig): Result<Unit>
    /**
     * @param displayName 家庭称呼 (product-required; blank rejected at the session seam)
     * @param familyName shared family name (optional; blank → server null + client fallback)
     */
    suspend fun createFamily(
        displayName: String,
        bootstrapSecret: String,
        familyName: String? = null,
    ): Result<CreateFamilyResult>
    /** Owner-only rename of the shared family name; blank/null clears. */
    suspend fun renameFamily(familyName: String?): Result<Unit>
    suspend fun sync(trigger: SyncTrigger): Result<Unit>
    suspend fun pull(familyId: String): Result<Unit>
    suspend fun push(familyId: String): Result<Unit>
    suspend fun createInvite(familyId: String): Result<Invite>
    /** Persists the joined session only; the shared domain join use case owns the immediate sync request. */
    suspend fun joinFamily(command: JoinFamilyCommand): Result<SyncSession>
    suspend fun listFamilyMembers(): Result<List<FamilyMember>>
    /** Self-only rename of this device's membership 家庭称呼. */
    suspend fun updateMyDisplayName(displayName: String): Result<Unit>
    suspend fun leave(familyId: String): Result<Unit>
    /** Owner removes another active member by server membership id. */
    suspend fun removeMember(membershipId: String): Result<Unit>
    suspend fun deleteFamily(): Result<Unit>
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
}

@Singleton
class NoOpSyncPort @Inject constructor() : SyncPort {
    private val status = MutableStateFlow(SyncStatus.Disabled)
    private val session = MutableStateFlow(SyncSession())
    override fun status(): Flow<SyncStatus> = status
    override fun session(): Flow<SyncSession> = session
    override fun isEnabled() = false
    override fun requestSync(trigger: SyncTrigger) = Unit
    override suspend fun saveServer(baseUrl: String) = Result.success(Unit)
    override suspend fun saveHomeLanConfig(config: HomeLanServerConfig) = Result.success(Unit)
    override suspend fun createFamily(
        displayName: String,
        bootstrapSecret: String,
        familyName: String?,
    ) = Result.failure<CreateFamilyResult>(SyncNotEnabledException())
    override suspend fun renameFamily(familyName: String?) =
        Result.failure<Unit>(SyncNotEnabledException())
    override suspend fun sync(trigger: SyncTrigger) = Result.success(Unit)
    override suspend fun pull(familyId: String) = Result.success(Unit)
    override suspend fun push(familyId: String) = Result.success(Unit)
    override suspend fun createInvite(familyId: String) = Result.failure<Invite>(SyncNotEnabledException())
    override suspend fun joinFamily(command: JoinFamilyCommand) =
        Result.failure<SyncSession>(SyncNotEnabledException())
    override suspend fun listFamilyMembers() =
        Result.failure<List<FamilyMember>>(SyncNotEnabledException())
    override suspend fun updateMyDisplayName(displayName: String) =
        Result.failure<Unit>(SyncNotEnabledException())
    override suspend fun leave(familyId: String) = Result.failure<Unit>(SyncNotEnabledException())
    override suspend fun removeMember(membershipId: String) =
        Result.failure<Unit>(SyncNotEnabledException())
    override suspend fun deleteFamily() = Result.failure<Unit>(SyncNotEnabledException())
    override suspend fun clearLocalData(
        scope: LocalDataClearScope,
        workflow: LocalClearWorkflow,
    ) = runCatching {
        workflow.withLocalExclusion {
            workflow.clearRoom()
            workflow.finishCommitted()
        }
    }
}
