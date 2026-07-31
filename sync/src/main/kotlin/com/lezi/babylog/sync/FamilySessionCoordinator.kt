package com.lezi.babylog.sync

import com.lezi.babylog.core.database.OutboxDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface FamilySessionCommand {
    data class SaveServer(val baseUrl: String) : FamilySessionCommand
    data class SaveEndpointConfig(val config: FamilyEndpointConfig) : FamilySessionCommand
    data class CreateFamily(
        val displayName: String,
        val deviceName: String = "Android 设备",
        val bootstrapSecret: String,
        val familyName: String?,
    ) : FamilySessionCommand
    data class OwnerLogin(
        val deviceName: String,
        val rootPassword: String,
        val takeover: Boolean,
    ) : FamilySessionCommand
    data class RequestMemberLogin(
        val displayName: String,
        val deviceName: String,
    ) : FamilySessionCommand
    data object CheckMemberLogin : FamilySessionCommand
    data object CancelMemberLogin : FamilySessionCommand
    data object ListPendingMemberLogins : FamilySessionCommand
    data class ApproveNewMemberLogin(val requestId: String) : FamilySessionCommand
    data class BindExistingMemberLogin(
        val requestId: String,
        val membershipId: String,
    ) : FamilySessionCommand
    data class RejectMemberLogin(val requestId: String) : FamilySessionCommand
    data class CreateMemberLoginGrant(val membershipId: String) : FamilySessionCommand
    data class ClaimMemberLoginGrant(
        val payload: MemberLoginQrPayload,
        val deviceName: String,
    ) : FamilySessionCommand
    data object ListMembers : FamilySessionCommand
    data object ListPendingMemberRenames : FamilySessionCommand
    data class ApproveMemberRename(val requestId: String) : FamilySessionCommand
    data class RejectMemberRename(val requestId: String) : FamilySessionCommand
    data object CancelMyMemberRename : FamilySessionCommand
    data class AddFamilyMember(val displayName: String) : FamilySessionCommand
    data class RenameFamilyMember(
        val membershipId: String,
        val displayName: String,
    ) : FamilySessionCommand
    data class RenameFamilyDevice(
        val deviceId: String,
        val deviceName: String,
    ) : FamilySessionCommand
    data class RevokeFamilyDevice(val deviceId: String) : FamilySessionCommand
    data object LogoutCurrentDevice : FamilySessionCommand
    data class RenameFamily(val familyName: String?) : FamilySessionCommand
    data class UpdateMyDisplayName(val displayName: String) : FamilySessionCommand
    data object Leave : FamilySessionCommand
    /** Owner removes another active membership (not self). */
    data class RemoveMember(val membershipId: String) : FamilySessionCommand
    data class DeleteFamily(
        val familyName: String,
        val rootPassword: String,
    ) : FamilySessionCommand {
        override fun toString(): String =
            "DeleteFamily(familyName=$familyName, rootPassword=<redacted>)"
    }
}

internal sealed interface FamilySessionOutcome {
    data object Completed : FamilySessionOutcome
    data class Joined(
        val session: SyncSession,
        /** True when create reclaimed an existing owner membership (not first create). */
        val reclaimed: Boolean = false,
        val dataRecovery: InitialFamilyDataRecovery = InitialFamilyDataRecovery.NotRequired,
    ) : FamilySessionOutcome
    data class MemberLoginGrantCreated(val grant: MemberLoginGrant) : FamilySessionOutcome
    data class MembersListed(val members: List<FamilyMember>) : FamilySessionOutcome
    data class PendingMemberRenamesListed(
        val requests: List<PendingMemberRenameRequest>,
    ) : FamilySessionOutcome
    data class DisplayNameUpdateCompleted(
        val result: DisplayNameUpdateResult,
    ) : FamilySessionOutcome
    data class FamilyMemberAdded(val member: FamilyMember) : FamilySessionOutcome
    data class MemberLoginRequested(val request: PendingMemberLogin) : FamilySessionOutcome
    data class MemberLoginChecked(val result: MemberLoginCheckResult) : FamilySessionOutcome
    data class PendingMemberLoginsListed(
        val requests: List<PendingMemberLoginRequest>,
    ) : FamilySessionOutcome
}

/**
 * The local-replica operations whose durable ordering is part of a family
 * session transition.
 */
internal interface FamilySessionReplica {
    /** [crossingFamilyBoundary] invalidates server-owned evidence from the previous family. */
    suspend fun resetLocalSyncReceipts(
        previous: SyncSession,
        invalidateCurrentReceipts: Boolean = false,
        crossingFamilyBoundary: Boolean = false,
    )

    suspend fun applyInitialEntities(
        session: SyncSession,
        entities: List<SyncEntity>,
    )

    suspend fun convergeAuthenticatedSelfMembership(
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
    private val requireRemoteAllowed: suspend (FamilyEndpointConfig) -> Unit,
    private val onSessionChanged: (SyncSession) -> Unit,
    private val onSessionObserved: (SyncSession) -> Unit,
    private val requestSync: (SyncTrigger) -> Unit,
    private val recoverReclaimedSession: suspend (SyncSession) -> InitialFamilyDataRecovery,
    private val beforeOperation: suspend () -> Unit = {},
) {
    suspend fun execute(command: FamilySessionCommand): Result<FamilySessionOutcome> =
        resultOf {
            when (command) {
                is FamilySessionCommand.SaveServer -> saveServer(command.baseUrl)
                is FamilySessionCommand.SaveEndpointConfig -> saveEndpointConfig(command.config)
                is FamilySessionCommand.CreateFamily -> createFamily(command)
                is FamilySessionCommand.OwnerLogin -> ownerLogin(command)
                is FamilySessionCommand.RequestMemberLogin -> requestMemberLogin(command)
                FamilySessionCommand.CheckMemberLogin -> checkMemberLogin()
                FamilySessionCommand.CancelMemberLogin -> cancelMemberLogin()
                FamilySessionCommand.ListPendingMemberLogins -> listPendingMemberLogins()
                is FamilySessionCommand.ApproveNewMemberLogin ->
                    approveNewMemberLogin(command.requestId)
                is FamilySessionCommand.BindExistingMemberLogin ->
                    bindExistingMemberLogin(command.requestId, command.membershipId)
                is FamilySessionCommand.RejectMemberLogin -> rejectMemberLogin(command.requestId)
                is FamilySessionCommand.CreateMemberLoginGrant ->
                    createMemberLoginGrant(command.membershipId)
                is FamilySessionCommand.ClaimMemberLoginGrant ->
                    claimMemberLoginGrant(command.payload, command.deviceName)
                FamilySessionCommand.ListMembers -> listMembers()
                FamilySessionCommand.ListPendingMemberRenames -> listPendingMemberRenames()
                is FamilySessionCommand.ApproveMemberRename ->
                    approveMemberRename(command.requestId)
                is FamilySessionCommand.RejectMemberRename ->
                    rejectMemberRename(command.requestId)
                FamilySessionCommand.CancelMyMemberRename -> cancelMyMemberRename()
                is FamilySessionCommand.AddFamilyMember -> addFamilyMember(command.displayName)
                is FamilySessionCommand.RenameFamilyMember ->
                    renameFamilyMember(command.membershipId, command.displayName)
                is FamilySessionCommand.RenameFamilyDevice ->
                    renameFamilyDevice(command.deviceId, command.deviceName)
                is FamilySessionCommand.RevokeFamilyDevice ->
                    revokeFamilyDevice(command.deviceId)
                FamilySessionCommand.LogoutCurrentDevice -> logoutCurrentDevice()
                is FamilySessionCommand.RenameFamily -> renameFamily(command.familyName)
                is FamilySessionCommand.UpdateMyDisplayName ->
                    updateMyDisplayName(command.displayName)
                FamilySessionCommand.Leave -> leave()
                is FamilySessionCommand.RemoveMember -> removeMember(command.membershipId)
                is FamilySessionCommand.DeleteFamily -> deleteFamily(command)
            }
        }

    private suspend fun saveServer(baseUrl: String): FamilySessionOutcome =
        withBarrier {
            val previous = preferences.session.first()
            val parsed = FamilyEndpointConfig.fromBaseUrl(baseUrl).withNormalized()
            require(parsed.isServerConfigured) { "请先填写家庭服务器地址" }
            if (previous.baseUrl.isNotBlank() && previous.baseUrl != parsed.baseUrl) {
                replica.resetLocalSyncReceipts(
                    previous,
                    crossingFamilyBoundary = true,
                )
            }
            preferences.saveEndpointConfig(
                parsed,
                clearSessionIfServerChanged = true,
            )
            onSessionChanged(preferences.session.first())
            FamilySessionOutcome.Completed
        }

    private suspend fun saveEndpointConfig(
        config: FamilyEndpointConfig,
    ): FamilySessionOutcome = withBarrier {
        val previous = preferences.session.first()
        val normalized = config.withNormalized()
        require(normalized.isServerConfigured) { "请先填写家庭服务器地址" }
        if (previous.baseUrl.isNotBlank() && previous.baseUrl != normalized.baseUrl) {
            replica.resetLocalSyncReceipts(
                previous,
                crossingFamilyBoundary = true,
            )
        }
        preferences.saveEndpointConfig(
            normalized,
            clearSessionIfServerChanged = true,
        )
        onSessionChanged(preferences.session.first())
        FamilySessionOutcome.Completed
    }

    private suspend fun createFamily(
        command: FamilySessionCommand.CreateFamily,
    ): FamilySessionOutcome {
        return withBarrier {
            val current = preferences.session.first()
            require(!current.isJoined) {
                "请先退出当前家庭，再创建新的家庭"
            }
            requireRemoteAllowed(current.endpointConfig)
            val createRequestId = preferences.ensureCreateRequestId()
            require(command.bootstrapSecret.isNotBlank()) { "请填写管理员根密码" }
            val joined = try {
                backend.create(
                    baseUrl = current.endpointConfig.baseUrl,
                    deviceId = requireDeviceName(command.deviceName),
                    displayName = requireMemberDisplayName(command.displayName),
                    createRequestId = createRequestId,
                    bootstrapSecret = command.bootstrapSecret,
                    familyName = requireNotNull(normalizeFamilyNameForWire(command.familyName)) {
                        "请填写家庭名"
                    },
                )
            } catch (error: SyncHttpException) {
                if (error.statusCode == 401 || error.statusCode == 403) {
                    throw BootstrapSecretRejectedException()
                }
                throw error
            }
            val session = persistJoin(
                baseUrl = current.endpointConfig.baseUrl,
                deviceId = joined.deviceId,
                joined = joined.copy(cursor = 0L),
            )
            // The device session is durable before this independent cursor-zero pull.
            // Failure leaves the session intact so foreground retry never reruns create.
            val dataRecovery = recoverReclaimedSession(session)
            FamilySessionOutcome.Joined(
                session = session,
                reclaimed = joined.reclaimed,
                dataRecovery = dataRecovery,
            )
        }
    }

    private suspend fun ownerLogin(
        command: FamilySessionCommand.OwnerLogin,
    ): FamilySessionOutcome = withBarrier {
        val current = preferences.session.first()
        require(!current.isJoined) {
            "请先退出当前家庭，再登录管理员设备"
        }
        requireRemoteAllowed(current.endpointConfig)
        require(command.rootPassword.isNotBlank()) { "请填写管理员根密码" }
        val joined = try {
            backend.ownerLogin(
                baseUrl = current.endpointConfig.baseUrl,
                deviceName = requireDeviceName(command.deviceName),
                loginRequestId = preferences.ensureOwnerLoginRequestId(),
                rootPassword = command.rootPassword,
                takeover = command.takeover,
            )
        } catch (error: SyncHttpException) {
            if (error.statusCode == 401 || error.statusCode == 403) {
                throw OwnerRootPasswordRejectedException()
            }
            throw error
        }
        require(joined.role == FamilyRole.Owner) { "管理员登录响应角色无效" }
        val session = persistJoin(
            baseUrl = current.endpointConfig.baseUrl,
            deviceId = joined.deviceId,
            joined = joined.copy(cursor = 0L),
        )
        FamilySessionOutcome.Joined(
            session = session,
            dataRecovery = recoverReclaimedSession(session),
        )
    }

    private suspend fun requestMemberLogin(
        command: FamilySessionCommand.RequestMemberLogin,
    ): FamilySessionOutcome = withBarrier {
        val current = preferences.session.first()
        require(!current.isJoined) { "请先退出当前家庭，再提交加入申请" }
        require(preferences.pendingMemberLogin.first() == null) {
            "已有一条等待管理员确认的申请"
        }
        requireRemoteAllowed(current.endpointConfig)
        val displayName = requireMemberDisplayName(command.displayName)
        val deviceName = requireDeviceName(command.deviceName)
        val receipt = backend.requestMemberLogin(
            current.endpointConfig.baseUrl,
            displayName,
            deviceName,
        )
        preferences.savePendingMemberLogin(receipt, displayName, deviceName)
        FamilySessionOutcome.MemberLoginRequested(
            requireNotNull(preferences.pendingMemberLogin.first()) {
                "加入申请未能保存，请重试"
            },
        )
    }

    private suspend fun checkMemberLogin(): FamilySessionOutcome = withBarrier {
        val current = preferences.session.first()
        require(!current.isJoined) { "这台设备已经加入家庭" }
        val pending = requireNotNull(preferences.pendingMemberLogin.first()) {
            "没有等待管理员确认的申请"
        }
        requireRemoteAllowed(current.endpointConfig)
        val secret = preferences.pendingMemberSecret()
        require(secret.isNotBlank()) { "等待确认凭据已丢失，请重新申请" }
        when (val status = backend.memberLoginStatus(current.endpointConfig.baseUrl, secret)) {
            MemberLoginStatus.Pending -> FamilySessionOutcome.MemberLoginChecked(
                MemberLoginCheckResult.Waiting(pending),
            )
            MemberLoginStatus.Approved -> {
                val joined = backend.claimMemberLogin(current.endpointConfig.baseUrl, secret)
                require(joined.role == FamilyRole.Member) { "成员登录响应角色无效" }
                // A claim is single-use: make the session durable before any replica work.
                val session = persistClaimedMemberSession(current, joined)
                val dataRecovery = try {
                    replica.resetLocalSyncReceipts(
                        current,
                        crossingFamilyBoundary = true,
                    )
                    recoverReclaimedSession(session)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    InitialFamilyDataRecovery.RetryRequired
                }
                FamilySessionOutcome.MemberLoginChecked(
                    MemberLoginCheckResult.Joined(session, dataRecovery),
                )
            }
            MemberLoginStatus.Rejected,
            MemberLoginStatus.Cancelled,
            MemberLoginStatus.Expired,
            MemberLoginStatus.Claimed,
            -> {
                preferences.clearPendingMemberLogin()
                FamilySessionOutcome.MemberLoginChecked(MemberLoginCheckResult.Terminal(status))
            }
        }
    }

    private suspend fun cancelMemberLogin(): FamilySessionOutcome = withBarrier {
        val current = preferences.session.first()
        require(!current.isJoined) { "这台设备已经加入家庭" }
        requireNotNull(preferences.pendingMemberLogin.first()) {
            "没有等待管理员确认的申请"
        }
        requireRemoteAllowed(current.endpointConfig)
        val secret = preferences.pendingMemberSecret()
        require(secret.isNotBlank()) { "等待确认凭据已丢失，请重新申请" }
        backend.cancelMemberLogin(current.endpointConfig.baseUrl, secret)
        preferences.clearPendingMemberLogin()
        FamilySessionOutcome.Completed
    }

    private suspend fun listPendingMemberLogins(): FamilySessionOutcome =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Owner) { "仅家庭管理员可查看待确认设备" }
            FamilySessionOutcome.PendingMemberLoginsListed(backend.pendingMemberLogins(session))
        }

    private suspend fun approveNewMemberLogin(requestId: String): FamilySessionOutcome =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Owner) { "仅家庭管理员可批准加入申请" }
            backend.approveNewMemberLogin(session, requirePendingRequestId(requestId))
            FamilySessionOutcome.Completed
        }

    private suspend fun bindExistingMemberLogin(
        requestId: String,
        membershipId: String,
    ): FamilySessionOutcome = withAllowedSession { session ->
        require(session.role == FamilyRole.Owner) { "仅家庭管理员可绑定加入申请" }
        backend.bindExistingMemberLogin(
            session,
            requirePendingRequestId(requestId),
            requireTargetMembershipId(membershipId),
        )
        FamilySessionOutcome.Completed
    }

    private suspend fun rejectMemberLogin(requestId: String): FamilySessionOutcome =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Owner) { "仅家庭管理员可拒绝加入申请" }
            backend.rejectMemberLogin(session, requirePendingRequestId(requestId))
            FamilySessionOutcome.Completed
        }

    private suspend fun createMemberLoginGrant(
        membershipId: String,
    ): FamilySessionOutcome = withAllowedSession { session ->
        require(session.role == FamilyRole.Owner) {
            "仅家庭管理员可生成成员登录二维码"
        }
        val endpoint = requireNotNull(preferences.verifiedEndpoint.first()) {
            "当前家庭服务器尚未建立可信 HTTPS 配置"
        }
        require(endpoint.matchesOrigin(session.baseUrl)) {
            "当前家庭会话与可信服务器地址不一致"
        }
        FamilySessionOutcome.MemberLoginGrantCreated(
            backend.createMemberLoginGrant(
                session,
                endpoint,
                requireTargetMembershipId(membershipId),
            ),
        )
    }

    private suspend fun claimMemberLoginGrant(
        payload: MemberLoginQrPayload,
        deviceName: String,
    ): FamilySessionOutcome = withBarrier {
        require(!preferences.session.first().isJoined) {
            "请先退出当前家庭，再登录成员设备"
        }
        require(preferences.verifiedEndpoint.first() == payload.endpoint) {
            "请先确认并保存二维码中的家庭服务器信任信息"
        }
        val joined = backend.claimMemberLoginGrant(
            endpoint = payload.endpoint,
            grant = payload.grant,
            deviceName = requireDeviceName(deviceName),
        )
        require(joined.role == FamilyRole.Member) { "成员登录响应角色无效" }
        // The grant is single-use: publish the new session before fallible replica recovery.
        val previous = preferences.session.first()
        val session = persistClaimedMemberSession(
            previous = previous,
            joined = joined.copy(cursor = 0L),
            baseUrl = payload.endpoint.origin,
        )
        val dataRecovery = try {
            replica.resetLocalSyncReceipts(
                previous,
                crossingFamilyBoundary = true,
            )
            recoverReclaimedSession(session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            InitialFamilyDataRecovery.RetryRequired
        }
        FamilySessionOutcome.Joined(
            session = session,
            dataRecovery = dataRecovery,
        )
    }

    private suspend fun listMembers(): FamilySessionOutcome =
        withAllowedSession { session ->
            val members = backend.members(session)
            val refreshed = replica.convergeAuthenticatedSelfMembership(session, members)
            onSessionObserved(refreshed)
            FamilySessionOutcome.MembersListed(members)
        }

    private suspend fun listPendingMemberRenames(): FamilySessionOutcome =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Owner) {
                "仅家庭管理员可查看改名申请"
            }
            FamilySessionOutcome.PendingMemberRenamesListed(
                backend.pendingMemberRenameRequests(session),
            )
        }

    private suspend fun approveMemberRename(requestId: String): FamilySessionOutcome =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Owner) {
                "仅家庭管理员可确认改名申请"
            }
            backend.approveMemberRename(session, requirePendingRequestId(requestId))
            FamilySessionOutcome.Completed
        }

    private suspend fun rejectMemberRename(requestId: String): FamilySessionOutcome =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Owner) {
                "仅家庭管理员可拒绝改名申请"
            }
            backend.rejectMemberRename(session, requirePendingRequestId(requestId))
            FamilySessionOutcome.Completed
        }

    private suspend fun cancelMyMemberRename(): FamilySessionOutcome =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Member) {
                "管理员称呼修改无需等待确认"
            }
            backend.cancelMyMemberRename(session)
            FamilySessionOutcome.Completed
        }

    private suspend fun addFamilyMember(displayName: String): FamilySessionOutcome =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Owner) {
                "仅家庭管理员可添加成员"
            }
            FamilySessionOutcome.FamilyMemberAdded(
                backend.addFamilyMember(session, requireMemberDisplayName(displayName)),
            )
        }

    private suspend fun renameFamilyMember(
        membershipId: String,
        displayName: String,
    ): FamilySessionOutcome = withAllowedSession { session ->
        require(session.role == FamilyRole.Owner) {
            "仅家庭管理员可直接修改成员称呼"
        }
        backend.renameFamilyMember(
            session,
            requireTargetMembershipId(membershipId),
            requireMemberDisplayName(displayName),
        )
        FamilySessionOutcome.Completed
    }

    private suspend fun renameFamilyDevice(
        deviceId: String,
        deviceName: String,
    ): FamilySessionOutcome = withAllowedSession { session ->
        backend.renameFamilyDevice(
            session,
            requireDeviceActionId(deviceId),
            requireDeviceName(deviceName),
        )
        FamilySessionOutcome.Completed
    }

    private suspend fun revokeFamilyDevice(deviceId: String): FamilySessionOutcome =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Owner) {
                "仅家庭管理员可撤销家庭设备"
            }
            backend.revokeFamilyDevice(session, requireDeviceActionId(deviceId))
            FamilySessionOutcome.Completed
        }

    private suspend fun logoutCurrentDevice(): FamilySessionOutcome =
        withAllowedSession { session ->
            backend.logoutCurrentDevice(session)
            FamilySessionOutcome.Completed
        }

    private suspend fun renameFamily(familyName: String?): FamilySessionOutcome =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Owner) {
                "仅家庭管理员可修改家庭名"
            }
            val normalized = requireNotNull(normalizeFamilyNameForWire(familyName)) {
                "家庭名不能为空"
            }
            backend.renameFamily(session, normalized)
            val updated = session.copy(familyName = normalized)
            preferences.saveSession(updated)
            onSessionObserved(updated)
            FamilySessionOutcome.Completed
        }

    private suspend fun updateMyDisplayName(
        displayName: String,
    ): FamilySessionOutcome = withAllowedSession { session ->
        FamilySessionOutcome.DisplayNameUpdateCompleted(
            backend.updateMyDisplayName(
                session,
                requireMemberDisplayName(displayName),
            ),
        )
    }

    private suspend fun leave(): FamilySessionOutcome =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Member) {
                "家庭管理员请使用“删除家庭数据”完成退出"
            }
            backend.leave(session)
            FamilySessionOutcome.Completed
        }

    private suspend fun removeMember(membershipId: String): FamilySessionOutcome =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Owner) {
                "仅家庭管理员可移除家人"
            }
            val target = membershipId.trim()
            require(target.isNotEmpty()) { "请选择要移除的家人" }
            require(target != session.membershipId.trim()) {
                "不能移除自己；管理员请使用“删除家庭数据”"
            }
            backend.removeMember(session, target)
            FamilySessionOutcome.Completed
        }

    private suspend fun deleteFamily(command: FamilySessionCommand.DeleteFamily): FamilySessionOutcome =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Owner) {
                "仅家庭管理员可删除家庭"
            }
            val expectedFamilyName = requireNotNull(
                normalizeFamilyNameForWire(session.familyName),
            ) {
                "当前家庭名不可用，请先设置家庭名"
            }
            val confirmedFamilyName = requireNotNull(
                normalizeFamilyNameForWire(command.familyName),
            ) {
                "请输入家庭名"
            }
            require(confirmedFamilyName == expectedFamilyName) {
                "输入的家庭名与当前家庭不一致"
            }
            require(command.rootPassword.isNotBlank()) { "请输入管理员根密码" }
            backend.deleteFamily(session, confirmedFamilyName, command.rootPassword)
            FamilySessionOutcome.Completed
        }

    private suspend fun persistJoin(
        baseUrl: String,
        deviceId: String,
        joined: SessionBootstrapResult,
        joinedConfig: FamilyEndpointConfig? = null,
    ): SyncSession {
        val previous = preferences.session.first()
        val parsed = FamilyEndpointConfig.fromBaseUrl(baseUrl).withNormalized()
        val config = joinedConfig?.withNormalized() ?: parsed
        val session = SyncSession(
            familyId = joined.familyId,
            accessToken = joined.accessToken,
            refreshToken = joined.refreshToken,
            accessExpiresAtEpochSeconds = joined.accessExpiresAtEpochSeconds,
            deviceId = joined.deviceId.ifBlank { deviceId },
            role = joined.role,
            pullCursor = joined.cursor,
            pullGeneration = joined.generation,
            serverHost = config.host.ifBlank { previous.serverHost },
            serverPort = if (config.host.isNotBlank()) config.port else previous.serverPort,
            serverScheme = if (config.host.isNotBlank()) config.scheme else previous.serverScheme,
            familyName = joined.familyName?.trim()?.takeIf { it.isNotEmpty() },
            membershipId = joined.membershipId.trim(),
        )
        replica.resetLocalSyncReceipts(
            previous,
            crossingFamilyBoundary = true,
        )
        if (joined.entities.isNotEmpty()) {
            replica.applyInitialEntities(session, joined.entities)
        }
        preferences.saveSession(session)
        onSessionChanged(session)
        return session
    }

    private suspend fun persistClaimedMemberSession(
        previous: SyncSession,
        joined: SessionBootstrapResult,
        baseUrl: String = previous.endpointConfig.baseUrl,
    ): SyncSession {
        val parsed = FamilyEndpointConfig.fromBaseUrl(baseUrl).withNormalized()
        val session = SyncSession(
            familyId = joined.familyId,
            accessToken = joined.accessToken,
            refreshToken = joined.refreshToken,
            accessExpiresAtEpochSeconds = joined.accessExpiresAtEpochSeconds,
            deviceId = joined.deviceId,
            role = joined.role,
            pullCursor = 0L,
            pullGeneration = joined.generation,
            serverHost = parsed.host,
            serverPort = parsed.port,
            serverScheme = parsed.scheme,
            familyName = joined.familyName?.trim()?.takeIf(String::isNotEmpty),
            membershipId = joined.membershipId.trim(),
        )
        preferences.saveSession(session)
        onSessionChanged(session)
        return session
    }

    private suspend fun <T> withAllowedSession(
        block: suspend (SyncSession) -> T,
    ): T = withBarrier {
        val session = preferences.session.first()
        onSessionObserved(session)
        if (!session.isJoined) throw SyncNotEnabledException()
        requireRemoteAllowed(session.endpointConfig)
        block(session)
    }

    private suspend fun <T> withBarrier(block: suspend () -> T): T =
        barrier.withLock {
            beforeOperation()
            block()
        }
}

private fun requirePendingRequestId(requestId: String): String = requestId.trim().also {
    require(it.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "待确认申请 ID 无效" }
}

private fun requireTargetMembershipId(membershipId: String): String = membershipId.trim().also {
    require(it.isNotEmpty() && it.length <= 128 && it.none(Char::isWhitespace)) {
        "目标家庭成员 ID 无效"
    }
}

private fun requireDeviceActionId(deviceId: String): String = deviceId.trim().also {
    require(it.isNotEmpty()) { "请选择家庭设备" }
}

private suspend fun <T> resultOf(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
