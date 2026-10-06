package com.lezi.babylog.sync.session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import com.lezi.babylog.sync.BootstrapSecretRejectedException
import com.lezi.babylog.sync.DifferentFamilyServerException
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.MemberLoginCheckResult
import com.lezi.babylog.sync.OwnerRootPasswordRejectedException
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.SyncNotEnabledException
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.DisplayNameUpdateResult
import com.lezi.babylog.sync.backend.MemberLoginGrant
import com.lezi.babylog.sync.backend.MemberLoginStatus
import com.lezi.babylog.sync.backend.PendingMemberLoginRequest
import com.lezi.babylog.sync.backend.PendingMemberRenameRequest
import com.lezi.babylog.sync.backend.SessionBootstrapResult
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind

import com.lezi.babylog.sync.qr.MemberLoginQrPayload

/** Short wait before a session command fails instead of queueing behind a full reconcile. */
internal const val SESSION_BARRIER_WAIT_MILLIS = 2_000L

internal sealed interface FamilySessionCommand {
    data class SaveEndpointConfig(val config: FamilyEndpointConfig) : FamilySessionCommand
    data class CreateFamily(
        val displayName: String,
        val deviceName: String = "Android 设备",
        val bootstrapSecret: String,
        val familyName: String?,
    ) : FamilySessionCommand {
        override fun toString(): String =
            "CreateFamily(displayName=$displayName, deviceName=$deviceName, " +
                "familyName=$familyName, bootstrapSecret=<redacted>)"
    }
    data class OwnerLogin(
        val deviceName: String,
        val rootPassword: String,
        val takeover: Boolean,
        /** When set, login targets this origin without first replacing the retained session. */
        val candidateBaseUrl: String? = null,
    ) : FamilySessionCommand {
        override fun toString(): String =
            "OwnerLogin(deviceName=$deviceName, takeover=$takeover, rootPassword=<redacted>)"
    }
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
    data class MembersListed(
        val generation: String,
        val members: List<FamilyMember>,
    ) : FamilySessionOutcome
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
    data class RecoveryTarget(
        val baseUrl: String,
        val familyId: String? = null,
        val membershipId: String? = null,
        val deviceId: String? = null,
    )

    data class ResetRoot(
        val entityType: String,
        val clientUuid: String,
        val contentEpoch: Long,
        val wasPending: Boolean,
    )

    data class ResetReceipt(
        val previousFamilyId: String,
        val previousMembershipId: String,
        val previousDeviceId: String,
        val crossingFamilyBoundary: Boolean,
        val recoveryTarget: RecoveryTarget?,
        val roots: List<ResetRoot>,
    )

    /** [crossingFamilyBoundary] invalidates server-owned evidence from the previous family. */
    suspend fun resetLocalSyncReceipts(
        previous: SyncSession,
        invalidateCurrentReceipts: Boolean = false,
        crossingFamilyBoundary: Boolean = false,
        recoveryTarget: RecoveryTarget? = null,
    ): ResetReceipt

    suspend fun applyInitialEntities(
        session: SyncSession,
        entities: List<SyncEntity>,
        resetReceipt: ResetReceipt? = null,
    )

    suspend fun completeLocalSyncReset(receipt: ResetReceipt)

    suspend fun convergeAuthenticatedSelfMembership(
        session: SyncSession,
        members: List<FamilyMember>,
    ): SyncSession
}

/**
 * Owns endpoint and family-session lifecycle commands behind one interface.
 *
 * Every command that can race with replica synchronization uses [barrier], the
 * same mutex held by the replica engine and local-clear coordinator. Session
 * commands wait at most [SESSION_BARRIER_WAIT_MILLIS] then fail as
 * [FamilyHttpFailureKind.HouseholdSyncing]. Local member-login abandon does
 * not take the barrier. Credential persist and replica reset still run under
 * the held lock; HTTP is bounded by the family session budget. Remote access
 * remains guarded by the caller-provided Home-LAN policy.
 */
internal class FamilySessionCoordinator(
    private val backend: SyncBackend,
    private val preferences: SyncPreferences,
    private val replica: FamilySessionReplica,
    private val barrier: Mutex,
    private val requireRemoteAllowed: suspend (FamilyEndpointConfig) -> Unit,
    private val onSessionChanged: (SyncSession) -> Unit,
    private val onSessionObserved: (SyncSession) -> Unit,
    private val requestSync: (SyncTrigger) -> Unit,
    private val beforeOperation: suspend () -> Unit = {},
    private val launchBestEffort: ((suspend () -> Unit) -> Unit) = {},
) {
    suspend fun execute(command: FamilySessionCommand): Result<FamilySessionOutcome> =
        resultOf {
            when (command) {
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

    private suspend fun saveEndpointConfig(
        config: FamilyEndpointConfig,
    ): FamilySessionOutcome = withBarrier {
        val previous = preferences.session.first()
        val normalized = config.withNormalized()
        require(normalized.isServerConfigured) { "请先填写家庭服务器地址" }
        if (previous.baseUrl.isNotBlank() && previous.baseUrl != normalized.baseUrl) {
            // A retained family id is a configured home. Changing origin before the
            // new server proves it is the same family would dirty Room and publish
            // the candidate. Reconnect/login must do that only after family id matches.
            if (previous.familyId.isNotBlank()) {
                throw DifferentFamilyServerException()
            }
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
            // Session establishment ends at the durable credential boundary. Initial family data
            // recovery belongs to the process sync loop and must never keep this wizard command
            // suspended on an offline or wedged NAS.
            val dataRecovery = scheduleInitialSync()
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
        val targetBaseUrl = command.candidateBaseUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: current.endpointConfig.baseUrl
        requireRemoteAllowed(
            FamilyEndpointConfig.fromBaseUrl(targetBaseUrl).withNormalized(),
        )
        require(command.rootPassword.isNotBlank()) { "请填写管理员根密码" }
        val joined = try {
            backend.ownerLogin(
                baseUrl = targetBaseUrl,
                deviceName = requireDeviceName(command.deviceName),
                loginRequestId = preferences.ensureOwnerLoginRequestId(),
                rootPassword = command.rootPassword,
                takeover = command.takeover,
            )
        } catch (error: SyncHttpException) {
            if (error.statusCode == 401 || error.statusCode == 403) {
                throw OwnerRootPasswordRejectedException()
            }
            if (error.statusCode == 409) {
                preferences.clearOwnerLoginRequestId()
                throw IllegalStateException("登录方式或设备称呼已变化，请重试", error)
            }
            throw error
        }
        require(joined.role == FamilyRole.Owner) { "管理员登录响应角色无效" }
        val session = persistJoin(
            baseUrl = targetBaseUrl,
            deviceId = joined.deviceId,
            joined = joined.copy(cursor = 0L),
        )
        val dataRecovery = scheduleInitialSync()
        FamilySessionOutcome.Joined(
            session = session,
            dataRecovery = dataRecovery,
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
            MemberLoginStatus.Approved,
            MemberLoginStatus.Claimed,
            -> {
                abandonedMemberLoginCheckOrNull()?.let { return@withBarrier it }
                val joined = try {
                    backend.claimMemberLogin(current.endpointConfig.baseUrl, secret)
                } catch (error: SyncHttpException) {
                    if (
                        status == MemberLoginStatus.Claimed &&
                        error.statusCode in setOf(404, 409, 410)
                    ) {
                        preferences.clearPendingMemberLogin()
                        return@withBarrier FamilySessionOutcome.MemberLoginChecked(
                            MemberLoginCheckResult.Terminal(status),
                        )
                    }
                    throw error
                }
                require(joined.role == FamilyRole.Member) { "成员登录响应角色无效" }
                abandonedMemberLoginCheckOrNull()?.let { return@withBarrier it }
                val session = claimedMemberSession(current, joined)
                val dataRecovery = try {
                    persistClaimedMemberSession(current, session)
                    // Initial pull belongs to the process sync loop. Account polling
                    // returns at the durable session boundary and cannot hang on a
                    // slow/offline NAS after the one-shot claim committed.
                    scheduleInitialSync()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    // A failed secure-token handoff keeps the pending claim replay
                    // capability. Do not report a joined session that never became
                    // durable. Receipt-reset/initial-pull failures happen after the
                    // replay slot retired and remain ordinary recovery work.
                    if (preferences.pendingMemberLogin.first() != null) throw error
                    InitialFamilyDataRecovery.RetryRequired(
                        causeKind = familyFailureKind(error),
                    )
                }
                FamilySessionOutcome.MemberLoginChecked(
                    MemberLoginCheckResult.Joined(session, dataRecovery),
                )
            }
            MemberLoginStatus.Rejected,
            MemberLoginStatus.Cancelled,
            MemberLoginStatus.Expired,
            -> {
                preferences.clearPendingMemberLogin()
                FamilySessionOutcome.MemberLoginChecked(MemberLoginCheckResult.Terminal(status))
            }
        }
    }

    private suspend fun cancelMemberLogin(): FamilySessionOutcome {
        val current = preferences.session.first()
        require(!current.isJoined) { "这台设备已经加入家庭" }
        // Local abandon must not wait for the replica barrier. A full reconcile or
        // in-flight check cannot swallow “在这台设备放弃等待”.
        if (preferences.pendingMemberLogin.first() == null) {
            return FamilySessionOutcome.Completed
        }
        val secret = try {
            preferences.pendingMemberSecret()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ""
        }

        // Cancelling is first and foremost a local abandonment command. A family NAS may be
        // offline, or the 24-hour request may already be gone; neither condition may leave this
        // Device permanently blocked by its one durable pending slot. The server-side request is
        // best-effort and harmless when orphaned: without the retired secret it cannot be claimed
        // and expires on the existing 0.3.3 contract.
        preferences.clearPendingMemberLogin()
        if (secret.isNotBlank()) {
            try {
                launchBestEffort {
                    try {
                        requireRemoteAllowed(current.endpointConfig)
                        backend.cancelMemberLogin(current.endpointConfig.baseUrl, secret)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // The local slot is retired; the 0.3.3 server request expires normally.
                    }
                }
            } catch (_: Throwable) {
                // Scheduling remote cleanup is best-effort; never resurrect the local slot.
            }
        }
        return FamilySessionOutcome.Completed
    }

    private suspend fun abandonedMemberLoginCheckOrNull(): FamilySessionOutcome? {
        if (preferences.pendingMemberLogin.first() != null) return null
        return FamilySessionOutcome.MemberLoginChecked(
            MemberLoginCheckResult.Terminal(MemberLoginStatus.Cancelled),
        )
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
        val previous = preferences.session.first()
        val session = claimedMemberSession(
            previous = previous,
            joined = joined.copy(cursor = 0L),
            baseUrl = payload.endpoint.origin,
        )
        persistClaimedMemberSession(previous, session)
        val dataRecovery = scheduleInitialSync()
        FamilySessionOutcome.Joined(
            session = session,
            dataRecovery = dataRecovery,
        )
    }

    private fun scheduleInitialSync(): InitialFamilyDataRecovery {
        // Scheduling is deliberately best-effort: the joined session is already durable and the
        // process-level foreground/background triggers remain available if this enqueue fails.
        return try {
            requestSync(SyncTrigger.Foreground)
            InitialFamilyDataRecovery.NotRequired
        } catch (error: Throwable) {
            InitialFamilyDataRecovery.RetryRequired(
                causeKind = familyFailureKind(error),
            )
        }
    }

    private suspend fun listMembers(): FamilySessionOutcome =
        withAllowedSession { session ->
            val directory = backend.memberDirectory(session)
            val refreshed = replica.convergeAuthenticatedSelfMembership(session, directory.members)
            onSessionObserved(refreshed)
            FamilySessionOutcome.MembersListed(directory.generation, directory.members)
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
        if (previous.familyId.isNotBlank() && previous.familyId != session.familyId) {
            revokeProbeSession(session)
            throw DifferentFamilyServerException()
        }
        val resetReceipt = if (!replicaIdentityUnchanged(previous, session)) {
            replica.resetLocalSyncReceipts(
                previous,
                crossingFamilyBoundary = previous.familyId != session.familyId,
                recoveryTarget = session.toRecoveryTarget(),
            )
        } else {
            null
        }
        if (joined.entities.isNotEmpty()) {
            replica.applyInitialEntities(session, joined.entities, resetReceipt)
        }
        preferences.saveSession(session)
        resetReceipt?.let { replica.completeLocalSyncReset(it) }
        onSessionChanged(session)
        return session
    }

    private fun claimedMemberSession(
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
        return session
    }

    /**
     * A single-use claim is durable before fallible reset work, but the saved
     * session remains non-pushable behind a durable replica-reset marker. Same
     * replica identity is the only path that can activate without a reset.
     */
    private suspend fun persistClaimedMemberSession(
        previous: SyncSession,
        session: SyncSession,
    ) {
        if (previous.familyId.isNotBlank() && previous.familyId != session.familyId) {
            revokeProbeSession(session)
            throw DifferentFamilyServerException()
        }
        if (replicaIdentityUnchanged(previous, session)) {
            preferences.saveSession(session)
            onSessionChanged(session)
            return
        }
        preferences.saveSessionPendingReplicaReset(session, previous)
        onSessionChanged(preferences.session.first())
        recoverPendingReplicaReset()
    }

    /** Caller owns [barrier]. Safe to call during process/start-of-operation recovery. */
    suspend fun recoverPendingReplicaReset() {
        val previous = preferences.pendingReplicaResetPrevious() ?: return
        val pending = preferences.session.first()
        replica.resetLocalSyncReceipts(
            previous,
            crossingFamilyBoundary = previous.familyId != pending.familyId,
            recoveryTarget = pending.toRecoveryTarget(),
        )
        preferences.completePendingReplicaReset()
        onSessionChanged(preferences.session.first())
    }

    /**
     * The login already created a device on the other server. Revoke that device
     * before surfacing the block. A failed revoke must not be reported as a clean
     * family mismatch: the other server may now have an extra owner device.
     */
    private suspend fun revokeProbeSession(probe: SyncSession) {
        try {
            backend.logoutCurrentDevice(probe)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw IllegalStateException(
                "对方服务器可能多了一台管理员设备，请在那台服务器上删除它",
                error,
            )
        }
    }

    private fun SyncSession.toRecoveryTarget() = FamilySessionReplica.RecoveryTarget(
        baseUrl = baseUrl,
        familyId = familyId,
        membershipId = membershipId,
        deviceId = deviceId,
    )

    private suspend fun <T> withAllowedSession(
        block: suspend (SyncSession) -> T,
    ): T = withBarrier {
        val session = preferences.session.first()
        onSessionObserved(session)
        if (!session.isJoined) throw SyncNotEnabledException()
        requireRemoteAllowed(session.endpointConfig)
        block(session)
    }

    private suspend fun <T> withBarrier(block: suspend () -> T): T {
        if (!tryAcquireBarrier()) {
            throw FamilyHttpException(FamilyHttpFailureKind.HouseholdSyncing)
        }
        try {
            beforeOperation()
            return block()
        } finally {
            barrier.unlock()
        }
    }

    /**
     * Acquire the replica barrier, or give up after [SESSION_BARRIER_WAIT_MILLIS].
     * If [Mutex.lock] wins in the same instant as the wait timeout, unlock so the
     * next approve / reconcile is not stuck in [FamilyHttpFailureKind.HouseholdSyncing].
     */
    private suspend fun tryAcquireBarrier(): Boolean {
        if (barrier.tryLock()) return true
        return coroutineScope {
            val acquired = CompletableDeferred<Boolean>()
            val locker = launch {
                try {
                    barrier.lock()
                    if (!acquired.complete(true)) {
                        barrier.unlock()
                    }
                } catch (cancelled: CancellationException) {
                    acquired.complete(false)
                    throw cancelled
                }
            }
            val got = withTimeoutOrNull(SESSION_BARRIER_WAIT_MILLIS) { acquired.await() } == true
            if (!got) {
                acquired.complete(false)
                locker.cancel()
                locker.join()
            }
            acquired.isCompleted && acquired.getCompleted()
        }
    }
}

private fun requirePendingRequestId(requestId: String): String = requestId.trim().also {
    require(it.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "待确认申请 ID 无效" }
}

private fun replicaIdentityUnchanged(previous: SyncSession, next: SyncSession): Boolean =
    previous.familyId.isNotBlank() &&
        previous.familyId == next.familyId &&
        previous.membershipId.isNotBlank() &&
        previous.membershipId == next.membershipId &&
        previous.role == next.role

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
