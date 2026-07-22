package com.lezi.babylog.sync

import com.lezi.babylog.core.model.Family
import com.lezi.babylog.core.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class Invite(val code: String, val expiresAt: Long)

class SyncNotEnabledException : Exception("同步将在后续版本提供")

interface SyncPort {
    fun status(): Flow<SyncStatus>
    fun isEnabled(): Boolean
    suspend fun pull(familyId: String): Result<Unit>
    suspend fun push(familyId: String): Result<Unit>
    suspend fun createInvite(familyId: String): Result<Invite>
    suspend fun joinWithCode(code: String): Result<Family>
    suspend fun leave(familyId: String): Result<Unit>
}

@Singleton
class NoOpSyncPort @Inject constructor() : SyncPort {
    private val _status = MutableStateFlow(SyncStatus.Disabled)
    override fun status(): Flow<SyncStatus> = _status.asStateFlow()
    override fun isEnabled(): Boolean = false
    override suspend fun pull(familyId: String): Result<Unit> = Result.success(Unit)
    override suspend fun push(familyId: String): Result<Unit> = Result.success(Unit)
    override suspend fun createInvite(familyId: String): Result<Invite> =
        Result.failure(SyncNotEnabledException())
    override suspend fun joinWithCode(code: String): Result<Family> =
        Result.failure(SyncNotEnabledException())
    override suspend fun leave(familyId: String): Result<Unit> =
        Result.failure(SyncNotEnabledException())
}
