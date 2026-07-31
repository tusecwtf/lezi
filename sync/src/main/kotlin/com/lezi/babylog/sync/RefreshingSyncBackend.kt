package com.lezi.babylog.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ReauthRequiredException : IllegalStateException("登录已失效，请重新登录或申请")
class RemoteDeviceRemovedException : IllegalStateException("这台设备已被移出家庭")
class RemoteMembershipDeletedException : IllegalStateException("你的家庭成员身份已被删除")
class RemoteFamilyDeletedException : IllegalStateException("这个家庭已被删除")

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
        val refreshed = try {
            if (endpoint == null) {
                delegate.refresh(session.baseUrl, session.refreshToken)
            } else {
                require(endpoint.matchesOrigin(session.baseUrl)) {
                    "可信服务器与当前家庭会话不一致"
                }
                delegate.refresh(endpoint, session.refreshToken)
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
        ).also { preferences.saveSession(it) }
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
