package com.lezi.babylog.sync

import com.lezi.babylog.core.database.OutboxDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface FamilySessionCommand {
    data class SaveServer(val baseUrl: String) : FamilySessionCommand
    data class SaveHomeLanConfig(val config: HomeLanServerConfig) : FamilySessionCommand
    data class CreateFamily(
        val displayName: String?,
        val bootstrapSecret: String,
        val familyName: String?,
    ) : FamilySessionCommand
    data class JoinFamily(val command: JoinFamilyCommand) : FamilySessionCommand
    data object CreateInvite : FamilySessionCommand
    data object ListMembers : FamilySessionCommand
    data class RenameFamily(val familyName: String?) : FamilySessionCommand
    data class UpdateMyDisplayName(val displayName: String) : FamilySessionCommand
    data object Leave : FamilySessionCommand
    data object DeleteFamily : FamilySessionCommand
}

internal sealed interface FamilySessionOutcome {
    data object Completed : FamilySessionOutcome
    data class Joined(val session: SyncSession) : FamilySessionOutcome
    data class InviteCreated(val invite: Invite) : FamilySessionOutcome
    data class MembersListed(val members: List<FamilyMember>) : FamilySessionOutcome
}

/**
 * The local-replica operations whose durable ordering is part of a family
 * session transition.
 */
internal interface FamilySessionReplica {
    suspend fun resetLocalSyncReceipts(
        previous: SyncSession,
        invalidateCurrentReceipts: Boolean = false,
    )

    suspend fun applyInitialEntities(
        session: SyncSession,
        entities: List<SyncEntity>,
    )

    suspend fun persistAuthenticatedSelfMembershipIfMissing(
        session: SyncSession,
        members: List<FamilyMember>,
    ): SyncSession
}

/**
 * Owns endpoint and family-session lifecycle commands behind one interface.
 *
 * Every command that can race with replica synchronization uses [barrier], the
 * same mutex held by the replica engine and local-clear coordinator. Remote
 * access remains guarded by the caller-provided Home-LAN policy.
 */
internal class FamilySessionCoordinator(
    private val backend: SyncBackend,
    private val preferences: SyncPreferences,
    private val outboxDao: OutboxDao,
    private val replica: FamilySessionReplica,
    private val barrier: Mutex,
    private val requireRemoteAllowed: suspend (HomeLanServerConfig) -> Unit,
    private val onSessionChanged: (SyncSession) -> Unit,
    private val onSessionObserved: (SyncSession) -> Unit,
    private val requestSync: (SyncTrigger) -> Unit,
) {
    suspend fun execute(command: FamilySessionCommand): Result<FamilySessionOutcome> =
        resultOf {
            when (command) {
                is FamilySessionCommand.SaveServer -> saveServer(command.baseUrl)
                is FamilySessionCommand.SaveHomeLanConfig -> saveHomeLanConfig(command.config)
                is FamilySessionCommand.CreateFamily -> createFamily(command)
                is FamilySessionCommand.JoinFamily -> joinFamily(command.command)
                FamilySessionCommand.CreateInvite -> createInvite()
                FamilySessionCommand.ListMembers -> listMembers()
                is FamilySessionCommand.RenameFamily -> renameFamily(command.familyName)
                is FamilySessionCommand.UpdateMyDisplayName ->
                    updateMyDisplayName(command.displayName)
                FamilySessionCommand.Leave -> leave()
                FamilySessionCommand.DeleteFamily -> deleteFamily()
            }
        }

    private suspend fun saveServer(baseUrl: String): FamilySessionOutcome =
        barrier.withLock {
            val previous = preferences.session.first()
            val parsed = HomeLanServerConfig.fromBaseUrl(baseUrl).withNormalized()
            require(parsed.isServerConfigured) { "请先填写家庭服务器地址" }
            val merged = parsed.copy(allowedSsids = previous.allowedSsids)
            if (previous.baseUrl.isNotBlank() && previous.baseUrl != merged.baseUrl) {
                replica.resetLocalSyncReceipts(previous)
            }
            preferences.saveHomeLanConfig(
                merged,
                clearSessionIfServerChanged = true,
            )
            onSessionChanged(preferences.session.first())
            FamilySessionOutcome.Completed
        }

    private suspend fun saveHomeLanConfig(
        config: HomeLanServerConfig,
    ): FamilySessionOutcome = barrier.withLock {
        val previous = preferences.session.first()
        val merged = config.withNormalized().let { normalized ->
            if (
                normalized.allowedSsids.isEmpty() &&
                previous.allowedSsids.isNotEmpty() &&
                normalized.host == previous.serverHost
            ) {
                normalized.copy(allowedSsids = previous.allowedSsids)
            } else {
                normalized
            }
        }
        require(merged.isServerConfigured) { "请先填写家庭服务器地址" }
        if (previous.baseUrl.isNotBlank() && previous.baseUrl != merged.baseUrl) {
            replica.resetLocalSyncReceipts(previous)
        }
        preferences.saveHomeLanConfig(
            merged,
            clearSessionIfServerChanged = true,
        )
        onSessionChanged(preferences.session.first())
        FamilySessionOutcome.Completed
    }

    private suspend fun createFamily(
        command: FamilySessionCommand.CreateFamily,
    ): FamilySessionOutcome {
        require(command.bootstrapSecret.isNotBlank()) {
            "请填写服务器初始化口令"
        }
        return barrier.withLock {
            val current = preferences.session.first()
            require(!current.isJoined) {
                "请先退出当前家庭，再创建新的家庭"
            }
            requireRemoteAllowed(current.homeLanConfig)
            val deviceId = preferences.ensureDeviceId()
            val createRequestId = preferences.ensureCreateRequestId()
            val joined = try {
                backend.create(
                    baseUrl = current.homeLanConfig.baseUrl,
                    deviceId = deviceId,
                    displayName = memberDisplayNameForWire(command.displayName),
                    createRequestId = createRequestId,
                    bootstrapSecret = command.bootstrapSecret,
                    familyName = normalizeFamilyNameForWire(command.familyName),
                )
            } catch (error: SyncHttpException) {
                if (error.statusCode == 401 || error.statusCode == 403) {
                    throw BootstrapSecretRejectedException()
                }
                throw error
            }
            val session = persistJoin(
                baseUrl = current.homeLanConfig.baseUrl,
                deviceId = deviceId,
                joined = joined,
            )
            // saveSession atomically retires the create request id. Scheduling is
            // post-commit notification: a closed signal must not turn a durable
            // owner session into a user-visible create failure.
            try {
                requestSync(SyncTrigger.LocalWrite)
            } catch (_: Exception) {
                // A later foreground transition retries from the durable outbox.
            }
            FamilySessionOutcome.Joined(session)
        }
    }

    private suspend fun joinFamily(
        command: JoinFamilyCommand,
    ): FamilySessionOutcome = barrier.withLock {
        require(!preferences.session.first().isJoined) {
            "请先退出当前家庭，再加入新的家庭"
        }
        val decoded = InvitePayloadCodec.decode(command.invitation.trim())
        val config = command.homeLanConfig.withNormalized()
        require(config.isServerConfigured) { "请先填写家庭服务器地址" }
        require(config.allowedSsids.isNotEmpty()) {
            "请至少填写一个家庭 Wi‑Fi 名称"
        }
        requireRemoteAllowed(config)
        val deviceId = preferences.ensureDeviceId()
        val joined = backend.join(
            baseUrl = config.baseUrl,
            code = decoded.code,
            deviceId = deviceId,
            displayName = memberDisplayNameForWire(command.displayName),
        )
        FamilySessionOutcome.Joined(
            persistJoin(
                baseUrl = config.baseUrl,
                deviceId = deviceId,
                joined = joined,
                joinedConfig = config,
            ),
        )
    }

    private suspend fun createInvite(): FamilySessionOutcome =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Owner) {
                "仅家庭管理员可生成邀请"
            }
            FamilySessionOutcome.InviteCreated(backend.invite(session))
        }

    private suspend fun listMembers(): FamilySessionOutcome =
        withAllowedSession { session ->
            val members = backend.members(session)
            val refreshed = replica.persistAuthenticatedSelfMembershipIfMissing(session, members)
            onSessionObserved(refreshed)
            FamilySessionOutcome.MembersListed(members)
        }

    private suspend fun renameFamily(familyName: String?): FamilySessionOutcome =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Owner) {
                "仅家庭管理员可修改家庭名"
            }
            val normalized = normalizeFamilyNameForWire(familyName)
            backend.renameFamily(session, normalized)
            val updated = session.copy(familyName = normalized)
            preferences.saveSession(updated)
            onSessionObserved(updated)
            FamilySessionOutcome.Completed
        }

    private suspend fun updateMyDisplayName(
        displayName: String,
    ): FamilySessionOutcome = withAllowedSession { session ->
        backend.updateMyDisplayName(
            session,
            memberDisplayNameForWire(displayName),
        )
        FamilySessionOutcome.Completed
    }

    private suspend fun leave(): FamilySessionOutcome =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Member) {
                "家庭管理员请使用“删除家庭数据”完成退出"
            }
            try {
                backend.leave(session)
            } catch (error: SyncHttpException) {
                if (!error.meansFamilySessionIsGone()) throw error
            }
            clearSession(session)
            FamilySessionOutcome.Completed
        }

    private suspend fun deleteFamily(): FamilySessionOutcome =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Owner) {
                "仅家庭管理员可删除家庭"
            }
            try {
                backend.deleteFamily(session)
            } catch (error: SyncHttpException) {
                if (!error.meansFamilySessionIsGone()) throw error
            }
            clearSession(session)
            FamilySessionOutcome.Completed
        }

    private suspend fun clearSession(session: SyncSession) {
        outboxDao.deleteFamily(session.familyId)
        replica.resetLocalSyncReceipts(session)
        preferences.clearAllLocalSyncConfig()
        onSessionChanged(preferences.session.first())
    }

    private suspend fun persistJoin(
        baseUrl: String,
        deviceId: String,
        joined: JoinResult,
        joinedConfig: HomeLanServerConfig? = null,
    ): SyncSession {
        val previous = preferences.session.first()
        val parsed = HomeLanServerConfig.fromBaseUrl(baseUrl).withNormalized()
        val config = joinedConfig?.withNormalized()
            ?: parsed.copy(allowedSsids = previous.allowedSsids)
        val session = SyncSession(
            familyId = joined.familyId,
            familyToken = joined.token,
            deviceId = deviceId,
            role = joined.role,
            pullCursor = joined.cursor,
            pullGeneration = joined.generation,
            serverHost = config.host.ifBlank { previous.serverHost },
            serverPort = if (config.host.isNotBlank()) config.port else previous.serverPort,
            allowedSsids = config.allowedSsids,
            serverScheme = if (config.host.isNotBlank()) config.scheme else previous.serverScheme,
            familyName = joined.familyName?.trim()?.takeIf { it.isNotEmpty() },
            membershipId = joined.membershipId?.trim().orEmpty(),
        )
        replica.resetLocalSyncReceipts(previous)
        if (joined.entities.isNotEmpty()) {
            replica.applyInitialEntities(session, joined.entities)
        }
        preferences.saveSession(session)
        onSessionChanged(session)
        return session
    }

    private suspend fun <T> withAllowedSession(
        block: suspend (SyncSession) -> T,
    ): T = barrier.withLock {
        val session = preferences.session.first()
        onSessionObserved(session)
        if (!session.isJoined) throw SyncNotEnabledException()
        requireRemoteAllowed(session.homeLanConfig)
        block(session)
    }
}

private fun SyncHttpException.meansFamilySessionIsGone(): Boolean =
    statusCode == 401

private suspend fun <T> resultOf(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
