package com.lezi.babylog.sync.backend
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.lezi.babylog.sync.appupdate.sha256Hex
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.MemorySyncPreferences

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RefreshingSyncBackendTest {
    @Test
    fun lateRefreshCannotOverwriteAnExternalSessionWriterInProductionPreferences() = runTest {
        val file = java.io.File.createTempFile("lezi-auth-cas-", ".preferences_pb").also { it.delete() }
        val storeScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler) + kotlinx.coroutines.SupervisorJob(),
        )
        try {
            val preferences = com.lezi.babylog.sync.session.DataStoreSyncPreferences(
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = storeScope) { file },
                com.lezi.babylog.sync.session.InMemorySecureRefreshTokenStore(),
            )
            val original = joinedSession(accessToken = "", expiresAt = 0)
            preferences.saveSession(original)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val recording = RefreshRecordingBackend()
            val delegate = object : SyncBackend by recording {
                override suspend fun refresh(baseUrl: String, refreshToken: String, requestId: String): SessionRefreshResult {
                    entered.complete(Unit)
                    release.await()
                    return recording.refresh(baseUrl, refreshToken, requestId)
                }
            }
            val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))
            val pull = async { backend.pull(original, testPullPage()) }
            entered.await()
            preferences.saveSession(original.copy(
                accessToken = "external-access", refreshToken = "external-refresh",
                accessExpiresAtEpochSeconds = 2_000_900,
            ))
            release.complete(Unit)
            assertThat(pull.await().cursor).isEqualTo(7)
            assertThat(preferences.session.first().refreshToken).isEqualTo("external-refresh")
            assertThat(recording.pullTokens).containsExactly("external-access")
        } finally {
            storeScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            file.delete()
        }
    }

    @Test
    fun appUpdateDownloadDoesNotBlockOtherAuthenticatedRequests() = runTest {
        val preferences = MemorySyncPreferences(joinedSession())
        val delegate = RefreshRecordingBackend().apply {
            downloadStarted = CompletableDeferred()
            releaseDownload = CompletableDeferred()
        }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        val download = async {
            val sink = ByteArrayOutputStream()
            val receipt = backend.downloadAppUpdateApk(preferences.current(), sink)
            receipt to sink
        }
        delegate.downloadStarted?.await()
        val pull = async { backend.pull(preferences.current(), testPullPage()) }
        runCurrent()

        assertThat(pull.isCompleted).isTrue()
        assertThat(pull.await().cursor).isEqualTo(7)
        delegate.releaseDownload?.complete(Unit)
        val (receipt, sink) = download.await()
        assertThat(sink.toByteArray()).isEqualTo(RELEASE_APK_BYTES)
        assertThat(receipt.byteCount).isEqualTo(RELEASE_APK_BYTES.size.toLong())
        assertThat(receipt.sha256).isEqualTo(sha256Hex(RELEASE_APK_BYTES))
    }

    @Test
    fun slowAuthenticatedPullDoesNotBlockOtherAuthenticatedRequests() = runTest {
        val preferences = MemorySyncPreferences(joinedSession())
        val delegate = RefreshRecordingBackend().apply {
            pullStarted = CompletableDeferred()
            releasePull = CompletableDeferred()
        }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        val pull = async { backend.pull(preferences.current(), testPullPage()) }
        delegate.pullStarted?.await()
        val directory = async { backend.memberDirectory(preferences.current()) }
        runCurrent()

        assertThat(directory.isCompleted).isTrue()
        assertThat(directory.await().generation).isEqualTo("fake-directory-v1")
        delegate.releasePull?.complete(Unit)
        assertThat(pull.await().cursor).isEqualTo(7)
    }

    @Test
    fun expiredOrProcessRecreatedAccessRefreshesBeforeOneOriginalRequest() = runTest {
        val preferences = MemorySyncPreferences(joinedSession(accessToken = "", expiresAt = 0))
        val delegate = RefreshRecordingBackend()
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        val result = backend.pull(preferences.current(), testPullPage())

        assertThat(result.cursor).isEqualTo(7)
        assertThat(delegate.refreshTokens).containsExactly("refresh-old")
        assertThat(delegate.pullTokens).containsExactly("access-new")
        assertThat(preferences.current().accessToken).isEqualTo("access-new")
        assertThat(preferences.current().refreshToken).isEqualTo("refresh-new")
        assertThat(preferences.current().accessExpiresAtEpochSeconds).isEqualTo(2_000_900)
    }

    @Test
    fun refreshResponseLostBeforeDurableSaveReplaysWithTheSameRotationRequestId() = runTest {
        val preferences = MemorySyncPreferences(joinedSession(accessToken = "", expiresAt = 0))
        val delegate = RefreshRecordingBackend()
        preferences.failSaveSessionAttempts = 1

        assertThat(
            runCatching {
                RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))
                    .pull(preferences.current(), testPullPage())
            }.isFailure,
        ).isTrue()

        RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))
            .pull(preferences.current(), testPullPage())

        assertThat(delegate.refreshTokens).containsExactly("refresh-old", "refresh-old").inOrder()
        assertThat(delegate.refreshRequestIds).hasSize(2)
        assertThat(delegate.refreshRequestIds.distinct()).hasSize(1)
        assertThat(preferences.ensureRefreshRequestId())
            .isNotEqualTo(delegate.refreshRequestIds.first())
    }

    @Test
    fun unexpectedAccess401RefreshesAndRetriesTheOriginalRequestExactlyOnce() = runTest {
        val preferences = MemorySyncPreferences(joinedSession())
        val delegate = RefreshRecordingBackend().apply { failFirstPullWith401 = true }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        backend.pull(preferences.current(), testPullPage())

        assertThat(delegate.refreshTokens).containsExactly("refresh-old")
        assertThat(delegate.pullTokens).containsExactly("access-old", "access-new").inOrder()
    }

    @Test
    fun concurrentLateUnauthorizedResponseReusesTheAlreadyRefreshedCredentials() = runTest {
        val preferences = MemorySyncPreferences(joinedSession())
        val bothOldRequestsStarted = CompletableDeferred<Unit>()
        val refreshedRequestStarted = CompletableDeferred<Unit>()
        var oldRequests = 0
        var rotations = 0
        val delegate = object : SyncBackend by RefreshRecordingBackend() {
            override suspend fun refresh(baseUrl: String, token: String, requestId: String): SessionRefreshResult {
                rotations += 1
                return RefreshRecordingBackend().refresh(baseUrl, token).copy(
                    accessToken = "access-$rotations",
                    refreshToken = "refresh-$rotations",
                )
            }

            override suspend fun pull(session: SyncSession, page: PullPageRequest): PullResult {
                if (session.accessToken == "access-old") {
                    oldRequests += 1
                    if (oldRequests == 1) {
                        bothOldRequestsStarted.await()
                    } else {
                        bothOldRequestsStarted.complete(Unit)
                        refreshedRequestStarted.await()
                    }
                    throw SyncHttpException(401)
                }
                refreshedRequestStarted.complete(Unit)
                return PullResult(emptyList(), 7, "generation", false, familyName = "乐乐一家")
            }
        }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))
        val first = async { backend.pull(preferences.current(), testPullPage()) }
        val second = async { backend.pull(preferences.current(), testPullPage()) }

        assertThat(first.await().cursor).isEqualTo(7)
        assertThat(second.await().cursor).isEqualTo(7)
        assertThat(rotations).isEqualTo(1)
        assertThat(preferences.current().accessToken).isEqualTo("access-1")
        assertThat(preferences.current().refreshToken).isEqualTo("refresh-1")
        assertThat(preferences.current().reauthRequired).isFalse()
    }

    @Test
    fun aLateRetryFailureCannotClearNewerDeviceCredentials() = runTest {
        val preferences = MemorySyncPreferences(joinedSession())
        val retryStarted = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        val delegate = object : SyncBackend by RefreshRecordingBackend() {
            override suspend fun pull(session: SyncSession, page: PullPageRequest): PullResult {
                if (session.accessToken != "access-old") {
                    retryStarted.complete(Unit)
                    releaseRetry.await()
                }
                throw SyncHttpException(401)
            }
        }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))
        val request = async { runCatching { backend.pull(preferences.current(), testPullPage()) } }
        retryStarted.await()
        preferences.saveRefreshedSession(preferences.current().copy(
            accessToken = "newer-access", refreshToken = "newer-refresh",
        ))
        releaseRetry.complete(Unit)
        assertThat(request.await().isFailure).isTrue()
        assertThat(preferences.current().accessToken).isEqualTo("newer-access")
        assertThat(preferences.current().refreshToken).isEqualTo("newer-refresh")
        assertThat(preferences.current().reauthRequired).isFalse()
    }

    @Test
    fun threeLate401RequestsReuseOneRotationInEveryArrivalOrder() = runTest {
        // Request identity is independent of launch order; enumerate all six 401 orders.
        listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
            listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0)).forEach { order ->
            val original = joinedSession()
            val preferences = MemorySyncPreferences(original)
            val entered = List(3) { CompletableDeferred<Unit>() }
            val release = List(3) { CompletableDeferred<Unit>() }
            val attempts = List(3) { mutableListOf<String>() }
            val recording = RefreshRecordingBackend()
            val delegate = object : SyncBackend by recording {
                override suspend fun pull(session: SyncSession, page: PullPageRequest): PullResult {
                    val id = page.pageIndex
                    attempts[id] += session.accessToken
                    if (session.accessToken == original.accessToken) {
                        entered[id].complete(Unit)
                        release[id].await()
                        throw SyncHttpException(401)
                    }
                    return PullResult(emptyList(), 7, "generation", false)
                }
            }
            val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))
            val requests = (0..2).map { id ->
                async { backend.pull(original, testPullPage().copy(pageIndex = id)) }
            }
            entered.forEach { it.await() }
            order.forEach { id ->
                release[id].complete(Unit)
                assertThat(requests[id].await().cursor).isEqualTo(7)
                assertThat(preferences.current().accessToken).isEqualTo("access-new")
                assertThat(preferences.current().refreshToken).isEqualTo("refresh-new")
                assertThat(preferences.current().reauthRequired).isFalse()
            }
            assertThat(recording.refreshTokens).containsExactly("refresh-old")
            attempts.forEach { assertThat(it).containsExactly("access-old", "access-new").inOrder() }
            backend.pull(original, testPullPage().copy(pageIndex = 0))
            assertThat(recording.refreshTokens).containsExactly("refresh-old")
        }
    }

    @Test
    fun threeRetryFailuresCannotClearAnExternalCredentialGeneration() = runTest {
        val original = joinedSession()
        val preferences = MemorySyncPreferences(original)
        val entered = List(3) { CompletableDeferred<Unit>() }
        val release = List(3) { CompletableDeferred<Unit>() }
        val allOldEntered = List(3) { CompletableDeferred<Unit>() }
        val releaseOld = CompletableDeferred<Unit>()
        val recording = RefreshRecordingBackend()
        val delegate = object : SyncBackend by recording {
            override suspend fun pull(session: SyncSession, page: PullPageRequest): PullResult {
                val id = page.pageIndex
                if (session.accessToken == "access-old") {
                    allOldEntered[id].complete(Unit)
                    releaseOld.await()
                    throw SyncHttpException(401)
                }
                entered[id].complete(Unit)
                release[id].await()
                throw SyncHttpException(401)
            }
        }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))
        val requests = (0..2).map { id -> async {
            runCatching { backend.pull(original, testPullPage().copy(pageIndex = id)) }
        } }
        allOldEntered.forEach { it.await() }
        releaseOld.complete(Unit)
        entered.forEach { it.await() }
        val winner = preferences.current().copy(accessToken = "winner-access", refreshToken = "winner-refresh")
        preferences.saveRefreshedSession(winner)
        listOf(2, 0, 1).forEach { id ->
            release[id].complete(Unit)
            assertThat(requests[id].await().exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
            assertThat(preferences.current()).isEqualTo(winner)
        }
        assertThat(recording.refreshTokens).containsExactly("refresh-old")
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

        val failure = runCatching { backend.pull(original, testPullPage()) }.exceptionOrNull()

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

        val failure = runCatching { backend.pull(original, testPullPage()) }.exceptionOrNull()

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

        val failure = runCatching { backend.pull(original, testPullPage()) }.exceptionOrNull()

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

        val failure = runCatching { backend.pull(original, testPullPage()) }.exceptionOrNull()

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

        val failure = runCatching { backend.pull(original, testPullPage()) }.exceptionOrNull()

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

        val failure = runCatching { backend.pull(original, testPullPage()) }.exceptionOrNull()

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

        val failure = runCatching { backend.pull(original, testPullPage()) }.exceptionOrNull()

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

        val failure = runCatching { backend.pull(original, testPullPage()) }.exceptionOrNull()

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

        val failure = runCatching { backend.pull(original, testPullPage()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(RemoteFamilyDeletedException::class.java)
        assertThat(delegate.refreshTokens).isEmpty()
        assertThat(preferences.current()).isEqualTo(original)
    }

    @Test
    fun a401AfterSuccessfulRefreshIsNotRetriedAgainAndRequiresReauth() = runTest {
        val preferences = MemorySyncPreferences(joinedSession())
        val delegate = RefreshRecordingBackend().apply { alwaysFailPullWith401 = true }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        val failure = runCatching { backend.pull(preferences.current(), testPullPage()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ReauthRequiredException::class.java)
        assertThat(delegate.refreshTokens).containsExactly("refresh-old")
        assertThat(delegate.pullTokens).containsExactly("access-old", "access-new").inOrder()
        assertThat(preferences.current().reauthRequired).isTrue()
    }

    @Test
    fun appUpdateDownload401RefreshesAndRetriesDownloadExactlyOnce() = runTest {
        val preferences = MemorySyncPreferences(joinedSession())
        val delegate = RefreshRecordingBackend().apply { failFirstDownloadWith401 = true }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        val sink = ByteArrayOutputStream()
        val receipt = backend.downloadAppUpdateApk(preferences.current(), sink)

        assertThat(sink.toByteArray()).isEqualTo(RELEASE_APK_BYTES)
        assertThat(receipt.sha256).isEqualTo(sha256Hex(RELEASE_APK_BYTES))
        assertThat(delegate.refreshTokens).containsExactly("refresh-old")
        assertThat(delegate.downloadTokens)
            .containsExactly("access-old", "access-new")
            .inOrder()
    }

    @Test
    fun appUpdateDownload401AfterRefreshRequiresReauthWithoutThirdAttempt() = runTest {
        val preferences = MemorySyncPreferences(joinedSession())
        val delegate = RefreshRecordingBackend().apply { alwaysFailDownloadWith401 = true }
        val backend = RefreshingSyncBackend(delegate, preferences, FixedAuthClock(2_000_000))

        val failure = runCatching {
            backend.downloadAppUpdateApk(preferences.current(), ByteArrayOutputStream())
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ReauthRequiredException::class.java)
        assertThat(delegate.refreshTokens).containsExactly("refresh-old")
        assertThat(delegate.downloadTokens)
            .containsExactly("access-old", "access-new")
            .inOrder()
        assertThat(preferences.current().reauthRequired).isTrue()
    }
}

private val RELEASE_APK_BYTES = "release-apk".toByteArray()

private class RefreshRecordingBackend : SyncBackend by FakeSyncBackend() {
    val refreshTokens = mutableListOf<String>()
    val refreshRequestIds = mutableListOf<String>()
    val refreshEndpoints = mutableListOf<TrustedEndpointProfile>()
    val pullTokens = mutableListOf<String>()
    val bindCalls = mutableListOf<Triple<String, String, String>>()
    val grantCreateCalls = mutableListOf<Pair<String, String>>()
    val grantClaimCalls = mutableListOf<Triple<TrustedEndpointProfile, String, String>>()
    var failFirstPullWith401 = false
    var alwaysFailPullWith401 = false
    var failFirstDownloadWith401 = false
    var alwaysFailDownloadWith401 = false
    var refreshFailure: Throwable? = null
    var pullFailure: Throwable? = null
    var downloadStarted: CompletableDeferred<Unit>? = null
    var releaseDownload: CompletableDeferred<Unit>? = null
    var pullStarted: CompletableDeferred<Unit>? = null
    var releasePull: CompletableDeferred<Unit>? = null
    val downloadTokens = mutableListOf<String>()

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
        baseUrl: String,
        refreshToken: String,
        refreshRequestId: String,
    ): SessionRefreshResult {
        refreshRequestIds += refreshRequestId
        return refresh(baseUrl, refreshToken)
    }

    override suspend fun refresh(
        endpoint: TrustedEndpointProfile,
        refreshToken: String,
    ): SessionRefreshResult {
        refreshEndpoints += endpoint
        return refresh(endpoint.origin, refreshToken)
    }


    override suspend fun refresh(
        endpoint: TrustedEndpointProfile,
        refreshToken: String,
        refreshRequestId: String,
    ): SessionRefreshResult {
        refreshEndpoints += endpoint
        refreshRequestIds += refreshRequestId
        return refresh(endpoint.origin, refreshToken)
    }

    override suspend fun pull(session: SyncSession, page: PullPageRequest): PullResult {
        pullTokens += session.accessToken
        pullFailure?.let { throw it }
        if (alwaysFailPullWith401 || (failFirstPullWith401 && pullTokens.size == 1)) {
            throw SyncHttpException(401)
        }
        pullStarted?.complete(Unit)
        releasePull?.await()
        return PullResult(
            entities = emptyList(),
            cursor = 7,
            generation = "generation",
            hasMore = false,
            familyName = "乐乐一家",
        )
    }

    override suspend fun downloadAppUpdateApk(
        session: SyncSession,
        target: OutputStream,
    ): AppUpdateApkDownload {
        downloadTokens += session.accessToken
        if (alwaysFailDownloadWith401 ||
            (failFirstDownloadWith401 && downloadTokens.size == 1)
        ) {
            throw SyncHttpException(401)
        }
        downloadStarted?.complete(Unit)
        releaseDownload?.await()
        val bytes = RELEASE_APK_BYTES
        target.write(bytes)
        return AppUpdateApkDownload(
            sha256 = sha256Hex(bytes),
            byteCount = bytes.size.toLong(),
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
