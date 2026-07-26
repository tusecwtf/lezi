package com.lezi.babylog.sync

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
 * the primary Record-author link. Soft-parsed as null for legacy NAS responses.
 *
 * [deviceId] is retained only to resolve legacy `created_by_device_id` rows.
 * Product UI must never display it or treat it as authority.
 */
data class FamilyMember(
    val displayName: String?,
    val role: FamilyRole,
    val isSelf: Boolean,
    val deviceId: String? = null,
    val membershipId: String? = null,
)

enum class SyncTrigger { Foreground, PullToRefresh, LocalWrite }

data class SyncPlan(val push: Boolean, val pull: Boolean) {
    companion object {
        fun forTrigger(trigger: SyncTrigger): SyncPlan = when (trigger) {
            SyncTrigger.Foreground, SyncTrigger.PullToRefresh -> SyncPlan(push = true, pull = true)
            SyncTrigger.LocalWrite -> SyncPlan(push = true, pull = false)
        }
    }
}

class SyncNotEnabledException : Exception("请先配置家庭服务器并加入家庭")
class BootstrapSecretRejectedException : Exception("初始化口令不正确，请核对 NAS 配置")

interface SyncPort {
    fun status(): Flow<SyncStatus>
    fun session(): Flow<SyncSession>
    fun isEnabled(): Boolean
    fun requestSync(trigger: SyncTrigger)
    suspend fun saveServer(baseUrl: String): Result<Unit>
    /** Persists the host, port, and up to two SSIDs; form defaults are not applied here. */
    suspend fun saveHomeLanConfig(config: HomeLanServerConfig): Result<Unit>
    /**
     * @param displayName 家庭称呼 (product-required)
     * @param familyName shared family name (optional; blank → server null + client fallback)
     */
    suspend fun createFamily(
        displayName: String? = null,
        bootstrapSecret: String,
        familyName: String? = null,
    ): Result<SyncSession>
    /** Owner-only rename of the shared family name; blank/null clears. */
    suspend fun renameFamily(familyName: String?): Result<Unit>
    suspend fun sync(trigger: SyncTrigger): Result<Unit>
    suspend fun pull(familyId: String): Result<Unit>
    suspend fun push(familyId: String): Result<Unit>
    suspend fun createInvite(familyId: String): Result<Invite>
    suspend fun joinFamily(command: JoinFamilyCommand): Result<SyncSession>
    suspend fun listFamilyMembers(): Result<List<FamilyMember>>
    /** Self-only rename of this device's membership 家庭称呼. */
    suspend fun updateMyDisplayName(displayName: String): Result<Unit>
    suspend fun leave(familyId: String): Result<Unit>
    suspend fun deleteFamily(): Result<Unit>
    /** [clearLocal] must call its marker immediately after the domain transaction commits. */
    suspend fun clearLocalRecords(
        clearLocal: suspend (onCommitted: () -> Unit) -> Unit,
    ): Result<Unit>
    /**
     * Full local replica wipe (records, media, outbox, files) under the same
     * sync barrier as pull/apply. Domain tables beyond records are cleared via
     * [clearLocal]. Unlike [clearLocalRecords], avatar media and all outbox
     * rows are removed so a subsequent join cannot push stale residue.
     */
    suspend fun clearAllLocalData(
        clearLocal: suspend (onCommitted: () -> Unit) -> Unit,
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
        displayName: String?,
        bootstrapSecret: String,
        familyName: String?,
    ) = Result.failure<SyncSession>(SyncNotEnabledException())
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
    override suspend fun deleteFamily() = Result.failure<Unit>(SyncNotEnabledException())
    override suspend fun clearLocalRecords(
        clearLocal: suspend (onCommitted: () -> Unit) -> Unit,
    ) = runCatching {
        var committed = false
        try {
            clearLocal { committed = true }
            check(committed) { "本机记录清除未确认领域事务已提交" }
        } catch (error: Throwable) {
            if (committed) {
                throw LocalClearCommittedException(familyServerRetained = false, cause = error)
            }
            throw error
        }
    }

    override suspend fun clearAllLocalData(
        clearLocal: suspend (onCommitted: () -> Unit) -> Unit,
    ) = runCatching {
        var committed = false
        try {
            clearLocal { committed = true }
            check(committed) { "本机数据清除未确认领域事务已提交" }
        } catch (error: Throwable) {
            if (committed) {
                throw LocalClearCommittedException(familyServerRetained = false, cause = error)
            }
            throw error
        }
    }
}
