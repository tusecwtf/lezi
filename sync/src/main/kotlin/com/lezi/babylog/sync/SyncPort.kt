package com.lezi.babylog.sync

import com.lezi.babylog.core.model.Family
import com.lezi.babylog.core.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class Invite(val code: String, val expiresAt: Long)

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

interface SyncPort {
    fun status(): Flow<SyncStatus>
    fun session(): Flow<SyncSession>
    fun isEnabled(): Boolean
    fun requestSync(trigger: SyncTrigger)
    suspend fun saveServer(baseUrl: String): Result<Unit>
    suspend fun createFamily(displayName: String? = null): Result<SyncSession>
    suspend fun sync(trigger: SyncTrigger): Result<Unit>
    suspend fun pull(familyId: String): Result<Unit>
    suspend fun push(familyId: String): Result<Unit>
    suspend fun createInvite(familyId: String): Result<Invite>
    suspend fun joinWithCode(code: String): Result<Family>
    suspend fun joinWithPayload(payload: String): Result<SyncSession>
    suspend fun leave(familyId: String): Result<Unit>
    suspend fun deleteFamily(): Result<Unit>
    suspend fun clearLocalRecords(clearLocal: suspend () -> Unit): Result<Unit>
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
    override suspend fun createFamily(displayName: String?) = Result.failure<SyncSession>(SyncNotEnabledException())
    override suspend fun sync(trigger: SyncTrigger) = Result.success(Unit)
    override suspend fun pull(familyId: String) = Result.success(Unit)
    override suspend fun push(familyId: String) = Result.success(Unit)
    override suspend fun createInvite(familyId: String) = Result.failure<Invite>(SyncNotEnabledException())
    override suspend fun joinWithCode(code: String) = Result.failure<Family>(SyncNotEnabledException())
    override suspend fun joinWithPayload(payload: String) = Result.failure<SyncSession>(SyncNotEnabledException())
    override suspend fun leave(familyId: String) = Result.failure<Unit>(SyncNotEnabledException())
    override suspend fun deleteFamily() = Result.failure<Unit>(SyncNotEnabledException())
    override suspend fun clearLocalRecords(clearLocal: suspend () -> Unit) = runCatching {
        clearLocal()
    }
}
