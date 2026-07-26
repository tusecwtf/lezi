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
 * [deviceId] is a client-only link key for mapping record `created_by_device_id`
 * to the current 家庭称呼. Product UI must never display [deviceId].
 */
data class FamilyMember(
    val displayName: String?,
    val role: FamilyRole,
    val isSelf: Boolean,
    val deviceId: String? = null,
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
    /**
     * Join via invite code or full invite payload.
     * Compatibility alias for tests and old callers. Product UI must build a
     * complete [JoinFamilyCommand] instead of relying on saved endpoint state.
     */
    @Deprecated("Use joinFamily with an explicit HomeLanServerConfig")
    suspend fun joinWithCode(code: String): Result<SyncSession>
    @Deprecated("Use joinFamily with an explicit HomeLanServerConfig")
    suspend fun joinWithPayload(
        payload: String,
        preferredConfig: HomeLanServerConfig? = null,
        displayName: String? = null,
    ): Result<SyncSession>
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
    @Deprecated("Use joinFamily with an explicit HomeLanServerConfig")
    override suspend fun joinWithCode(code: String) = Result.failure<SyncSession>(SyncNotEnabledException())
    @Deprecated("Use joinFamily with an explicit HomeLanServerConfig")
    override suspend fun joinWithPayload(
        payload: String,
        preferredConfig: HomeLanServerConfig?,
        displayName: String?,
    ) =
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
