package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RefreshingSyncBackendTest {
    @Test
    fun expiredOrProcessRecreatedAccessRefreshesBeforeOneOriginalRequest() = runTest {
        val preferences = MemorySyncPreferences(joinedSession(accessToken = "", expiresAt = 0))
        val delegate = RefreshRecordingBackend()
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        val result = backend.pull(preferences.current())

        assertThat(result.cursor).isEqualTo(7)
        assertThat(delegate.refreshTokens).containsExactly("refresh-old")
        assertThat(delegate.pullTokens).containsExactly("access-new")
        assertThat(preferences.current().accessToken).isEqualTo("access-new")
        assertThat(preferences.current().refreshToken).isEqualTo("refresh-new")
        assertThat(preferences.current().accessExpiresAtEpochSeconds).isEqualTo(2_000_900)
    }

    @Test
    fun unexpectedAccess401RefreshesAndRetriesTheOriginalRequestExactlyOnce() = runTest {
        val preferences = MemorySyncPreferences(joinedSession())
        val delegate = RefreshRecordingBackend().apply { failFirstPullWith401 = true }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        backend.pull(preferences.current())

        assertThat(delegate.refreshTokens).containsExactly("refresh-old")
        assertThat(delegate.pullTokens).containsExactly("access-old", "access-new").inOrder()
    }

    @Test
    fun existingMemberBindingUsesTheAuthenticatedOwnerSessionAfterRefresh() = runTest {
        val preferences = MemorySyncPreferences(joinedSession(accessToken = "", expiresAt = 0))
        val delegate = RefreshRecordingBackend()
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        backend.bindExistingMemberLogin(
            preferences.current(),
            "request-000000000000000000000001",
            "membership-existing",
        )

        assertThat(delegate.refreshTokens).containsExactly("refresh-old")
        assertThat(delegate.bindCalls).containsExactly(
            Triple(
                "access-new",
                "request-000000000000000000000001",
                "membership-existing",
            ),
        )
    }

    @Test
    fun memberLoginGrantCreationUsesRefreshedOwnerButClaimNeedsNoExistingSession() = runTest {
        val preferences = MemorySyncPreferences(joinedSession(accessToken = "", expiresAt = 0))
        val delegate = RefreshRecordingBackend()
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))
        val endpoint = TrustedEndpointProfile.systemPki("https://family.example.com")

        backend.createMemberLoginGrant(preferences.current(), endpoint, "membership-member")
        backend.claimMemberLoginGrant(
            endpoint,
            "grant-0000000000000000000000000000000000000",
            "Pixel Tablet",
        )

        assertThat(delegate.refreshTokens).containsExactly("refresh-old")
        assertThat(delegate.refreshEndpoints).containsExactly(endpoint)
        assertThat(delegate.grantCreateCalls)
            .containsExactly("access-new" to "membership-member")
        assertThat(delegate.grantClaimCalls).containsExactly(
            Triple(endpoint, "grant-0000000000000000000000000000000000000", "Pixel Tablet"),
        )
    }

    @Test
    fun invalidRefreshClearsOnlyDeviceCredentialsAndEntersReauthRequired() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://family.example.com")
        val original = joinedSession(accessToken = "", expiresAt = 0)
        val preferences = MemorySyncPreferences(original).apply { rememberEndpoint(endpoint) }
        val delegate = RefreshRecordingBackend().apply {
            refreshFailure = SyncHttpException(401, "{\"code\":\"invalid_refresh\"}")
        }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        val failure = runCatching { backend.pull(original) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ReauthRequiredException::class.java)
        val retained = preferences.current()
        assertThat(retained.reauthRequired).isTrue()
        assertThat(retained.isJoined).isFalse()
        assertThat(retained.accessToken).isEmpty()
        assertThat(retained.refreshToken).isEmpty()
        assertThat(retained.familyId).isEqualTo(original.familyId)
        assertThat(retained.membershipId).isEqualTo(original.membershipId)
        assertThat(retained.deviceId).isEqualTo(original.deviceId)
        assertThat(retained.pullCursor).isEqualTo(original.pullCursor)
        assertThat(retained.baseUrl).isEqualTo(original.baseUrl)
        assertThat(preferences.verifiedEndpoint.first()).isEqualTo(endpoint)
        assertThat(failure?.message).doesNotContain("refresh-old")
        assertThat(failure?.message).doesNotContain("Authorization")
        assertThat(failure?.message).doesNotContain("access-old")
    }

    @Test
    fun missingRefreshClearsOnlyDeviceCredentialsAndEntersReauthRequired() = runTest {
        val original = joinedSession(accessToken = "", expiresAt = 0).copy(refreshToken = "")
        val preferences = MemorySyncPreferences(original)
        val delegate = RefreshRecordingBackend()
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        val failure = runCatching { backend.pull(original) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ReauthRequiredException::class.java)
        assertThat(delegate.refreshTokens).isEmpty()
        assertThat(preferences.current().reauthRequired).isTrue()
        assertThat(preferences.current().familyId).isEqualTo(original.familyId)
        assertThat(preferences.current().pullCursor).isEqualTo(original.pullCursor)
    }

    @Test
    fun transientRefreshFailurePreservesCredentialsAndDoesNotEnterReauth() = runTest {
        val original = joinedSession(accessToken = "", expiresAt = 0)
        val preferences = MemorySyncPreferences(original)
        val delegate = RefreshRecordingBackend().apply {
            refreshFailure = SyncHttpException(503, "{\"detail\":\"maintenance\"}")
        }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        val failure = runCatching { backend.pull(original) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SyncHttpException::class.java)
        assertThat(preferences.current().reauthRequired).isFalse()
        assertThat(preferences.current().refreshToken).isEqualTo("refresh-old")
        assertThat(preferences.current().familyId).isEqualTo(original.familyId)
    }

    @Test
    fun explicitDeviceRemovedOnRefreshIsTerminalAndDoesNotBecomeOrdinaryReauth() = runTest {
        val original = joinedSession(accessToken = "", expiresAt = 0)
        val preferences = MemorySyncPreferences(original)
        val delegate = RefreshRecordingBackend().apply {
            refreshFailure = SyncHttpException(
                401,
                """{"code":"device_removed","detail":"removed"}""",
            )
        }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        val failure = runCatching { backend.pull(original) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(RemoteDeviceRemovedException::class.java)
        assertThat(preferences.current()).isEqualTo(original)
        assertThat(preferences.current().reauthRequired).isFalse()
    }

    @Test
    fun explicitDeviceRemovedOnAccessDoesNotAttemptRefreshOrClearAsGeneric401() = runTest {
        val original = joinedSession()
        val preferences = MemorySyncPreferences(original)
        val delegate = RefreshRecordingBackend().apply {
            pullFailure = SyncHttpException(
                401,
                """{"code":"device_removed","detail":"removed"}""",
            )
        }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        val failure = runCatching { backend.pull(original) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(RemoteDeviceRemovedException::class.java)
        assertThat(delegate.refreshTokens).isEmpty()
        assertThat(preferences.current()).isEqualTo(original)
    }

    @Test
    fun explicitMembershipDeletedOnRefreshIsTerminalAndPreservesStateForRecoverableClear() = runTest {
        val original = joinedSession(accessToken = "", expiresAt = 0)
        val preferences = MemorySyncPreferences(original)
        val delegate = RefreshRecordingBackend().apply {
            refreshFailure = SyncHttpException(
                401,
                """{"code":"membership_deleted","detail":"deleted"}""",
            )
        }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        val failure = runCatching { backend.pull(original) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(RemoteMembershipDeletedException::class.java)
        assertThat(preferences.current()).isEqualTo(original)
        assertThat(preferences.current().reauthRequired).isFalse()
    }

    @Test
    fun explicitMembershipDeletedOnAccessDoesNotAttemptRefreshOrBecomeGenericReauth() = runTest {
        val original = joinedSession()
        val preferences = MemorySyncPreferences(original)
        val delegate = RefreshRecordingBackend().apply {
            pullFailure = SyncHttpException(
                401,
                """{"code":"membership_deleted","detail":"deleted"}""",
            )
        }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        val failure = runCatching { backend.pull(original) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(RemoteMembershipDeletedException::class.java)
        assertThat(delegate.refreshTokens).isEmpty()
        assertThat(preferences.current()).isEqualTo(original)
    }

    @Test
    fun explicitFamilyDeletedOnRefreshIsTerminalAndPreservesStateForRecoverableClear() = runTest {
        val original = joinedSession(accessToken = "", expiresAt = 0)
        val preferences = MemorySyncPreferences(original)
        val delegate = RefreshRecordingBackend().apply {
            refreshFailure = SyncHttpException(
                401,
                """{"code":"family_deleted","detail":"deleted"}""",
            )
        }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        val failure = runCatching { backend.pull(original) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(RemoteFamilyDeletedException::class.java)
        assertThat(preferences.current()).isEqualTo(original)
        assertThat(preferences.current().reauthRequired).isFalse()
    }

    @Test
    fun explicitFamilyDeletedOnAccessDoesNotAttemptRefreshOrBecomeGenericReauth() = runTest {
        val original = joinedSession()
        val preferences = MemorySyncPreferences(original)
        val delegate = RefreshRecordingBackend().apply {
            pullFailure = SyncHttpException(
                401,
                """{"code":"family_deleted","detail":"deleted"}""",
            )
        }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        val failure = runCatching { backend.pull(original) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(RemoteFamilyDeletedException::class.java)
        assertThat(delegate.refreshTokens).isEmpty()
        assertThat(preferences.current()).isEqualTo(original)
    }

    @Test
    fun a401AfterSuccessfulRefreshIsNotRetriedAgainAndRequiresReauth() = runTest {
        val preferences = MemorySyncPreferences(joinedSession())
        val delegate = RefreshRecordingBackend().apply { alwaysFailPullWith401 = true }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        val failure = runCatching { backend.pull(preferences.current()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ReauthRequiredException::class.java)
        assertThat(delegate.refreshTokens).containsExactly("refresh-old")
        assertThat(delegate.pullTokens).containsExactly("access-old", "access-new").inOrder()
        assertThat(preferences.current().reauthRequired).isTrue()
    }
}

private class RefreshRecordingBackend : SyncBackend by FakeSyncBackend() {
    val refreshTokens = mutableListOf<String>()
    val refreshEndpoints = mutableListOf<TrustedEndpointProfile>()
    val pullTokens = mutableListOf<String>()
    val bindCalls = mutableListOf<Triple<String, String, String>>()
    val grantCreateCalls = mutableListOf<Pair<String, String>>()
    val grantClaimCalls = mutableListOf<Triple<TrustedEndpointProfile, String, String>>()
    var failFirstPullWith401 = false
    var alwaysFailPullWith401 = false
    var refreshFailure: Throwable? = null
    var pullFailure: Throwable? = null

    override suspend fun refresh(baseUrl: String, refreshToken: String): SessionRefreshResult {
        refreshTokens += refreshToken
        refreshFailure?.let { throw it }
        return SessionRefreshResult(
            familyId = "family",
            membershipId = "membership",
            deviceId = "device",
            role = FamilyRole.Owner,
            accessToken = "access-new",
            refreshToken = "refresh-new",
            accessExpiresAtEpochSeconds = 2_000_900,
            generation = "generation",
            familyName = "乐乐一家",
        )
    }

    override suspend fun refresh(
        endpoint: TrustedEndpointProfile,
        refreshToken: String,
    ): SessionRefreshResult {
        refreshEndpoints += endpoint
        return refresh(endpoint.origin, refreshToken)
    }

    override suspend fun pull(session: SyncSession): PullResult {
        pullTokens += session.accessToken
        pullFailure?.let { throw it }
        if (alwaysFailPullWith401 || (failFirstPullWith401 && pullTokens.size == 1)) {
            throw SyncHttpException(401)
        }
        return PullResult(
            entities = emptyList(),
            cursor = 7,
            generation = "generation",
            hasMore = false,
            familyName = "乐乐一家",
        )
    }

    override suspend fun bindExistingMemberLogin(
        session: SyncSession,
        requestId: String,
        membershipId: String,
    ) {
        bindCalls += Triple(session.accessToken, requestId, membershipId)
    }

    override suspend fun createMemberLoginGrant(
        session: SyncSession,
        endpoint: TrustedEndpointProfile,
        membershipId: String,
    ): MemberLoginGrant {
        grantCreateCalls += session.accessToken to membershipId
        return MemberLoginGrant(
            grant = "grant-0000000000000000000000000000000000000",
            familyName = "乐乐一家",
            memberDisplayName = "妈妈",
            expiresAtEpochSeconds = 2_000_600,
        )
    }

    override suspend fun claimMemberLoginGrant(
        endpoint: TrustedEndpointProfile,
        grant: String,
        deviceName: String,
    ): SessionBootstrapResult {
        grantClaimCalls += Triple(endpoint, grant, deviceName)
        return SessionBootstrapResult(
            familyId = "family",
            accessToken = "member-access",
            refreshToken = "member-refresh",
            accessExpiresAtEpochSeconds = 2_000_900,
            deviceId = "new-device",
            role = FamilyRole.Member,
            generation = "generation",
            familyName = "乐乐一家",
            membershipId = "membership-member",
        )
    }
}

private class FixedAuthClock(private val nowMillis: Long) : PolicyClock {
    override fun nowMillis(): Long = nowMillis
}

private fun joinedSession(
    accessToken: String = "access-old",
    expiresAt: Long = 3_000_000,
): SyncSession = SyncSession(
    familyId = "family",
    accessToken = accessToken,
    refreshToken = "refresh-old",
    accessExpiresAtEpochSeconds = expiresAt,
    deviceId = "device",
    role = FamilyRole.Owner,
    pullCursor = 4,
    pullGeneration = "generation",
    serverHost = "family.example.com",
    serverPort = 443,
    serverScheme = "https",
    familyName = "乐乐一家",
    membershipId = "membership",
)
