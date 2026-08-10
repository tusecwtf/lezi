package com.lezi.babylog.sync.backend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.media.SyncMediaUploadSource
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SyncPreferences
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.session.matchesOrigin

class ReauthRequiredException : IllegalStateException("登录已失效，请重新登录或申请")
class RemoteDeviceRemovedException : IllegalStateException("这台设备已被移出家庭")
class RemoteMembershipDeletedException : IllegalStateException("你的家庭成员身份已被删除")
class RemoteFamilyDeletedException : IllegalStateException("这个家庭已被删除")

/**
 * Server rejected authoritative sync because the client is below minSupported
 * (`code=client_update_required`). Must surface force-update UI, not a vague network error.
 */
class ClientUpdateRequiredException :
    IllegalStateException("需要更新乐记后才能继续同步家庭数据")

/**
 * One authentication seam for every protected server call.
 *
 * It serializes refresh rotation, keeps access credentials process-local via
 * [SyncPreferences], and retries an access-401 exactly once after refresh.
 */
internal class RefreshingSyncBackend(
    private val delegate: SyncBackend,
    private val preferences: SyncPreferences,
    private val clock: PolicyClock,
) : SyncBackend by delegate {
    private val sessionMutex = Mutex()

    override suspend fun pull(session: SyncSession): PullResult =
        authenticated(session, delegate::pull)

    override suspend fun reconcile(
        session: SyncSession,
        units: List<ReconcileUnitDraft>,
    ): ReconcileResult = authenticated(session) { delegate.reconcile(it, units) }

    override suspend fun members(session: SyncSession): List<FamilyMember> =
        authenticated(session, delegate::members)

    override suspend fun pendingMemberLogins(
        session: SyncSession,
    ): List<PendingMemberLoginRequest> = authenticated(session, delegate::pendingMemberLogins)

    override suspend fun approveNewMemberLogin(session: SyncSession, requestId: String) =
        authenticated(session) { delegate.approveNewMemberLogin(it, requestId) }

    override suspend fun bindExistingMemberLogin(
        session: SyncSession,
        requestId: String,
        membershipId: String,
    ) = authenticated(session) {
        delegate.bindExistingMemberLogin(it, requestId, membershipId)
    }

    override suspend fun rejectMemberLogin(session: SyncSession, requestId: String) =
        authenticated(session) { delegate.rejectMemberLogin(it, requestId) }

    override suspend fun createMemberLoginGrant(
        session: SyncSession,
        endpoint: TrustedEndpointProfile,
        membershipId: String,
    ): MemberLoginGrant = authenticated(session, endpoint) {
        delegate.createMemberLoginGrant(it, endpoint, membershipId)
    }

    override suspend fun claimMemberLoginGrant(
        endpoint: TrustedEndpointProfile,
        grant: String,
        deviceName: String,
    ): SessionBootstrapResult = delegate.claimMemberLoginGrant(endpoint, grant, deviceName)

    override suspend fun updateMyDisplayName(session: SyncSession, displayName: String) =
        authenticated(session) { delegate.updateMyDisplayName(it, displayName) }

    override suspend fun pendingMemberRenameRequests(session: SyncSession) =
        authenticated(session) { delegate.pendingMemberRenameRequests(it) }

    override suspend fun approveMemberRename(session: SyncSession, requestId: String) =
        authenticated(session) { delegate.approveMemberRename(it, requestId) }

    override suspend fun rejectMemberRename(session: SyncSession, requestId: String) =
        authenticated(session) { delegate.rejectMemberRename(it, requestId) }

    override suspend fun cancelMyMemberRename(session: SyncSession) =
        authenticated(session) { delegate.cancelMyMemberRename(it) }

    override suspend fun addFamilyMember(session: SyncSession, displayName: String) =
        authenticated(session) { delegate.addFamilyMember(it, displayName) }

    override suspend fun renameFamilyMember(
        session: SyncSession,
        membershipId: String,
        displayName: String,
    ) = authenticated(session) { delegate.renameFamilyMember(it, membershipId, displayName) }

    override suspend fun renameFamilyDevice(
        session: SyncSession,
        deviceId: String,
        deviceName: String,
    ) = authenticated(session) { delegate.renameFamilyDevice(it, deviceId, deviceName) }

    override suspend fun revokeFamilyDevice(session: SyncSession, deviceId: String) =
        authenticated(session) { delegate.revokeFamilyDevice(it, deviceId) }

    override suspend fun logoutCurrentDevice(session: SyncSession) =
        authenticated(session, delegate::logoutCurrentDevice)

    override suspend fun renameFamily(session: SyncSession, familyName: String?) =
        authenticated(session) { delegate.renameFamily(it, familyName) }

    override suspend fun leave(session: SyncSession) =
        authenticated(session, delegate::leave)

    override suspend fun removeMember(session: SyncSession, membershipId: String) =
        authenticated(session) { delegate.removeMember(it, membershipId) }

    override suspend fun deleteFamily(
        session: SyncSession,
        familyName: String,
        rootPassword: String,
    ) = authenticated(session) { delegate.deleteFamily(it, familyName, rootPassword) }

    override suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray =
        authenticated(session) { delegate.getMedia(it, clientUuid) }

    override suspend fun getAppUpdateMetadata(session: SyncSession): AppUpdateMetadata =
        authenticated(session, delegate::getAppUpdateMetadata)

    override suspend fun downloadAppUpdateApk(session: SyncSession): ByteArray =
        authenticatedOnceOutsideMutex(session, delegate::downloadAppUpdateApk)

    override suspend fun stageBundle(
        session: SyncSession,
        draft: AtomicBundleDraft,
    ): BundleStageStatus = authenticated(session) { delegate.stageBundle(it, draft) }

    override suspend fun putBundleMedia(
        session: SyncSession,
        bundleId: String,
        clientUuid: String,
        source: SyncMediaUploadSource,
    ): BundleStageStatus = authenticated(session) {
        delegate.putBundleMedia(it, bundleId, clientUuid, source)
    }

    override suspend fun commitBundle(
        session: SyncSession,
        bundleId: String,
    ): BundleCommitResult = authenticated(session) { delegate.commitBundle(it, bundleId) }

    override fun supportsCausalWire(): Boolean = delegate.supportsCausalWire()

    override suspend fun causalReconcile(
        session: SyncSession,
        units: List<com.lezi.babylog.sync.backend.CausalMutationUnit>,
    ) = authenticated(session) { delegate.causalReconcile(it, units) }

    override suspend fun causalCommit(
        session: SyncSession,
        units: List<com.lezi.babylog.sync.backend.CausalMutationUnit>,
    ) = authenticated(session) { delegate.causalCommit(it, units) }

    override suspend fun putCausalMediaPreimage(
        session: SyncSession,
        mediaUuid: String,
        source: com.lezi.babylog.sync.media.SyncMediaUploadSource,
        sha256: String,
    ) = authenticated(session) {
        delegate.putCausalMediaPreimage(it, mediaUuid, source, sha256)
    }

    override suspend fun fetchConflictSnapshotPage(
        session: SyncSession,
        conflictId: String,
        request: com.lezi.babylog.sync.conflict.ConflictSnapshotPageRequest,
    ) = authenticated(session) { delegate.fetchConflictSnapshotPage(it, conflictId, request) }

    override suspend fun resolveConflict(
        session: SyncSession,
        conflictId: String,
        request: ConflictResolveRequest,
    ) = authenticated(session) { delegate.resolveConflict(it, conflictId, request) }

    override suspend fun declareSourceRelation(
        session: SyncSession,
        request: SourceRelationDeclareRequest,
    ) = authenticated(session) { delegate.declareSourceRelation(it, request) }

    override suspend fun resolveSourceRelationGroup(
        session: SyncSession,
        request: SourceRelationResolveGroupRequest,
    ) = authenticated(session) { delegate.resolveSourceRelationGroup(it, request) }

    private suspend fun <T> authenticated(
        requested: SyncSession,
        operation: suspend (SyncSession) -> T,
    ): T = authenticated(requested, null, operation)

    private suspend fun <T> authenticated(
        requested: SyncSession,
        refreshEndpoint: TrustedEndpointProfile?,
        operation: suspend (SyncSession) -> T,
    ): T = sessionMutex.withLock {
        var current = currentCredentialsFor(requested)
        if (current.reauthRequired) throw ReauthRequiredException()
        var refreshed = false
        if (!current.hasUsableAccess()) {
            current = refreshOrRequireReauth(current, refreshEndpoint)
            refreshed = true
        }
        try {
            operation(current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SyncHttpException) {
            failure.remoteTerminalRemovalOrNull()?.let { throw it }
            failure.clientUpdateRequiredOrNull()?.let { throw it }
            if (failure.statusCode != 401) throw failure
            if (refreshed) {
                requireReauth()
            }
            current = refreshOrRequireReauth(current, refreshEndpoint)
            try {
                operation(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (retryFailure: SyncHttpException) {
                retryFailure.remoteTerminalRemovalOrNull()?.let { throw it }
                retryFailure.clientUpdateRequiredOrNull()?.let { throw it }
                if (retryFailure.statusCode == 401) requireReauth()
                throw retryFailure
            }
        }
    }

    /**
     * Resolves one usable credential snapshot under the refresh-rotation mutex, then releases it
     * before the potentially long-running operation (APK download). On an unexpected access 401,
     * refresh once under the mutex and retry the download **exactly once** so a near-expiry token
     * does not force a full reauth under the force-update shell. A second 401 after that refresh
     * still maps to reauth (same as [authenticated]).
     */
    private suspend fun <T> authenticatedOnceOutsideMutex(
        requested: SyncSession,
        operation: suspend (SyncSession) -> T,
    ): T {
        var current = sessionMutex.withLock {
            val stored = currentCredentialsFor(requested)
            if (stored.reauthRequired) throw ReauthRequiredException()
            if (stored.hasUsableAccess()) stored else refreshOrRequireReauth(stored)
        }
        return try {
            operation(current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SyncHttpException) {
            failure.remoteTerminalRemovalOrNull()?.let { throw it }
            failure.clientUpdateRequiredOrNull()?.let { throw it }
            if (failure.statusCode != 401) throw failure
            // One refresh + one download retry; mutex is not held across the large transfer.
            current = sessionMutex.withLock {
                refreshOrRequireReauth(currentCredentialsFor(requested))
            }
            try {
                operation(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (retryFailure: SyncHttpException) {
                retryFailure.remoteTerminalRemovalOrNull()?.let { throw it }
                retryFailure.clientUpdateRequiredOrNull()?.let { throw it }
                if (retryFailure.statusCode == 401) requireReauth()
                throw retryFailure
            }
        }
    }

    private suspend fun currentCredentialsFor(requested: SyncSession): SyncSession {
        val stored = preferences.session.first()
        return if (
            stored.familyId == requested.familyId &&
            stored.deviceId == requested.deviceId &&
            stored.membershipId == requested.membershipId
        ) {
            stored
        } else {
            requested
        }
    }

    private fun SyncSession.hasUsableAccess(): Boolean =
        accessToken.isNotBlank() && accessExpiresAtEpochSeconds > clock.nowMillis() / 1_000L

    private suspend fun refreshOrRequireReauth(
        session: SyncSession,
        endpoint: TrustedEndpointProfile? = null,
    ): SyncSession {
        if (session.refreshToken.isBlank()) requireReauth()
        // Persist before the server rotates. If the process dies after the server
        // commit but before saveSession, the same old token and nonce replay the
        // one accepted rotation instead of looking like token theft.
        val refreshRequestId = preferences.ensureRefreshRequestId()
        val refreshed = try {
            if (endpoint == null) {
                delegate.refresh(session.baseUrl, session.refreshToken, refreshRequestId)
            } else {
                require(endpoint.matchesOrigin(session.baseUrl)) {
                    "可信服务器与当前家庭会话不一致"
                }
                delegate.refresh(endpoint, session.refreshToken, refreshRequestId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SyncHttpException) {
            failure.remoteTerminalRemovalOrNull()?.let { throw it }
            if (failure.statusCode == 401) requireReauth()
            throw failure
        }
        if (
            refreshed.familyId != session.familyId ||
            refreshed.membershipId != session.membershipId ||
            refreshed.deviceId != session.deviceId ||
            refreshed.role != session.role ||
            refreshed.accessToken.isBlank() ||
            refreshed.refreshToken.isBlank() ||
            refreshed.accessExpiresAtEpochSeconds <= clock.nowMillis() / 1_000L
        ) {
            requireReauth()
        }
        return session.copy(
            accessToken = refreshed.accessToken,
            refreshToken = refreshed.refreshToken,
            accessExpiresAtEpochSeconds = refreshed.accessExpiresAtEpochSeconds,
            reauthRequired = false,
            familyName = refreshed.familyName,
        ).also { preferences.saveRefreshedSession(it) }
    }

    private suspend fun requireReauth(): Nothing {
        preferences.clearDeviceCredentialsForReauth()
        throw ReauthRequiredException()
    }
}

private fun SyncHttpException.remoteTerminalRemovalOrNull(): IllegalStateException? {
    if (statusCode != 401) return null
    return when (syncHttpCodeOrNull(responseBody)) {
        "device_removed" -> RemoteDeviceRemovedException()
        "membership_deleted" -> RemoteMembershipDeletedException()
        "family_deleted" -> RemoteFamilyDeletedException()
        else -> null
    }
}

internal fun SyncHttpException.clientUpdateRequiredOrNull(): ClientUpdateRequiredException? {
    if (syncHttpCodeOrNull(responseBody) != "client_update_required") return null
    return ClientUpdateRequiredException()
}
