package com.lezi.babylog.sync.session
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.FamilyDevice
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.backend.MemberLoginReceipt

@OptIn(ExperimentalCoroutinesApi::class)
class SyncPreferencesReplayPendingMemberTest {
    @Test
    fun qrOwnershipCreatesNoTemporaryTrustAndLateCleanupCannotRetireAnotherClaim() = runTest {
        val file = File.createTempFile("lezi-qr-owner-", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val prefs = preferences(PreferenceDataStoreFactory.create(scope = scope) { file })
        val endpoint = TrustedEndpointProfile.systemPki("https://family.example.com")
        try {
            val owner = prefs.memberReconnectOwner()
            assertThat(prefs.beginMemberQrClaim("qr-a", owner)).isTrue()
            assertThat(prefs.verifiedEndpoint.first()).isNull()
            assertThat(prefs.beginMemberQrClaim("qr-b", owner)).isTrue()
            prefs.endMemberQrClaim("qr-a")
            val joined = SyncSession(familyId = "family", membershipId = "member", deviceId = "device", role = FamilyRole.Member,
                serverHost = "family.example.com", serverPort = 443, refreshToken = "session-secret")
            assertThat(prefs.activateMemberQrClaimIfCurrent("qr-a", owner, joined, endpoint, null)).isFalse()
            assertThat(prefs.activateMemberQrClaimIfCurrent("qr-b", owner, joined, endpoint, null)).isTrue()
            prefs.endMemberQrClaim("qr-a")
            assertThat(prefs.verifiedEndpoint.first()).isEqualTo(endpoint)
            assertThat(prefs.session.first().deviceId).isEqualTo("device")
        } finally { scope.cancel(); file.delete() }
    }

    @Test
    fun explicitForgetInvalidatesQrEvenWhenNoTrustHadBeenPersisted() = runTest {
        val file = File.createTempFile("lezi-qr-forget-", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val prefs = preferences(PreferenceDataStoreFactory.create(scope = scope) { file })
        try {
            val owner = prefs.memberReconnectOwner()
            prefs.beginMemberQrClaim("qr-a", owner)
            prefs.forgetEndpoint()
            val endpoint = TrustedEndpointProfile.systemPki("https://family.example.com")
            assertThat(prefs.activateMemberQrClaimIfCurrent("qr-a", owner,
                SyncSession(familyId = "family", deviceId = "device", role = FamilyRole.Member), endpoint, null)).isFalse()
            assertThat(prefs.verifiedEndpoint.first()).isNull()
        } finally { scope.cancel(); file.delete() }
    }

    @Test
    fun readyEndpointAndCertificateEditsCannotImplicitlyAbandonAnUnknownWrite() = runTest {
        val file = File.createTempFile("lezi-member-trust-fence-", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val prefs = preferences(PreferenceDataStoreFactory.create(scope = scope) { file })
        val original = TrustedEndpointProfile.systemPki("https://family.example.com")
        val other = TrustedEndpointProfile.systemPki("https://other.example.com")
        val changedCertificate = TrustedEndpointProfile.tofuSpki(original.origin,
            Base64.getEncoder().encodeToString(ByteArray(32) { 1 }))
        val attempt = PendingMemberLogin("", "妈妈", "Phone", 0, "operation-a", original.origin, true)
        try {
            prefs.rememberEndpoint(original)
            prefs.saveMemberLoginAttempt(attempt)
            prefs.rememberEndpoint(original)
            for (candidate in listOf(other, changedCertificate)) {
                assertThat(runCatching { prefs.rememberEndpoint(candidate) }.isFailure).isTrue()
                assertThat(prefs.memberLoginAttempt()).isEqualTo(attempt)
                assertThat(prefs.verifiedEndpoint.first()).isEqualTo(original)
            }
            // Automatic trust invalidation is not the user's explicit abandon command.
            prefs.forgetEndpoint(retainMemberAttempts = true)
            assertThat(prefs.memberLoginAttempt()).isEqualTo(attempt)
            assertThat(runCatching { prefs.rememberEndpoint(other) }.isFailure).isTrue()
            prefs.forgetEndpoint()
            prefs.rememberEndpoint(other)
            assertThat(prefs.memberLoginAttempt()).isNull()
            assertThat(prefs.verifiedEndpoint.first()).isEqualTo(other)
        } finally { scope.cancel(); file.delete() }
    }

    @Test
    fun capturedUnknownOperationCanAbandonItsReceiptWhileTheSecureHandoffIsBlocked() = runTest {
        val file = File.createTempFile("lezi-member-lineage-", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val base = InMemorySecureRefreshTokenStore()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val tokens = object : SecureRefreshTokenStore by base {
            override fun setPendingMemberSecret(secret: String) {
                entered.complete(Unit)
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                base.setPendingMemberSecret(secret)
            }
        }
        val prefs = preferences(PreferenceDataStoreFactory.create(scope = scope) { file }, tokens)
        val attempt = PendingMemberLogin("", "妈妈", "Phone", 0, "operation-a", "https://family.example.com", true)
        prefs.saveMemberLoginAttempt(attempt)
        try {
            val handoff = async { prefs.savePendingMemberLogin(MemberLoginReceipt("server-r", "secret-r", 20), "妈妈", "Phone", attempt.operationId) }
            entered.await()
            val captured = requireNotNull(prefs.pendingMemberLogin.first())
            val abandon = async { prefs.clearPendingMemberLogin(expectedOperationId = captured.operationId) }
            runCurrent()
            release.countDown()
            handoff.await()
            abandon.await()
            assertThat(prefs.pendingMemberLogin.first()).isNull()
            assertThat(prefs.pendingMemberSecret()).isEmpty()
        } finally { release.countDown(); scope.cancel(); file.delete() }
    }

    @Test
    fun knownReceiptRetainsItsLocalOperationIdentityAfterRestart() = runTest {
        val file = File.createTempFile("lezi-member-lineage-restart-", ".preferences_pb").also { it.delete() }
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val tokens = InMemorySecureRefreshTokenStore()
        val first = preferences(PreferenceDataStoreFactory.create(scope = firstScope) { file }, tokens)
        val attempt = PendingMemberLogin("", "妈妈", "Phone", 0, "operation-a", "https://family.example.com", true)
        first.saveMemberLoginAttempt(attempt)
        first.savePendingMemberLogin(MemberLoginReceipt("server-r", "secret-r", 20), "妈妈", "Phone", attempt.operationId)
        firstScope.cancel()
        advanceUntilIdle()
        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val restored = preferences(PreferenceDataStoreFactory.create(scope = secondScope) { file }, tokens)
        try {
            val known = requireNotNull(restored.pendingMemberLogin.first())
            assertThat(known.operationId).isEqualTo(attempt.operationId)
            assertThat(known.requestId).isEqualTo("server-r")
            assertThat(known.remoteOutcomeUnknown).isFalse()
            restored.clearPendingMemberLogin(expectedOperationId = attempt.operationId)
            assertThat(restored.pendingMemberLogin.first()).isNull()
        } finally { secondScope.cancel(); file.delete() }
    }

    @Test
    fun oldNormalClaimCannotReadOrActivateAfterItsPendingSlotWasReplaced() = runTest {
        val file = File.createTempFile("lezi-normal-claim-cas-", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val prefs = preferences(PreferenceDataStoreFactory.create(scope = scope) { file })
        try {
            prefs.saveEndpointConfig(FamilyEndpointConfig(host = "family.example.com"))
            prefs.savePendingMemberLogin(MemberLoginReceipt("old-request", "old-secret", 10), "爸爸", "Phone")
            val owner = prefs.memberReconnectOwner()
            prefs.clearPendingMemberLogin(expectedOperationId = "old-request")
            prefs.savePendingMemberLogin(MemberLoginReceipt("new-request", "new-secret", 20), "妈妈", "Tablet")
            assertThat(prefs.pendingMemberSecretIfCurrent("old-request", owner)).isNull()
            assertThat(prefs.activatePendingMemberIfCurrent("old-request", owner,
                SyncSession(familyId = "family", deviceId = "old-device", membershipId = "old-member",
                    role = FamilyRole.Member, serverHost = "family.example.com", refreshToken = "old-claimed-secret"),
                prefs.session.first())).isFalse()
            prefs.clearPendingMemberLogin(expectedOperationId = "old-request")
            assertThat(prefs.pendingMemberLogin.first()?.requestId).isEqualTo("new-request")
            assertThat(prefs.pendingMemberSecret()).isEqualTo("new-secret")
            assertThat(prefs.session.first().isJoined).isFalse()
        } finally { scope.cancel(); file.delete() }
    }

    @Test
    fun candidateActivationRequiresItsOriginalDurableOperationAndIdentity() = runTest {
        for (transition in listOf("forget", "replace-device", "owner-reconnect")) {
            val file = File.createTempFile("lezi-candidate-cas-", ".preferences_pb").also { it.delete() }
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
            val prefs = preferences(PreferenceDataStoreFactory.create(scope = scope) { file })
            try {
                val old = SyncSession(familyId = "family", membershipId = "member", deviceId = "device",
                    role = FamilyRole.Member, serverHost = "old.example.com", refreshToken = "old-secret")
                prefs.saveSession(old)
                prefs.rememberEndpoint(TrustedEndpointProfile.systemPki(old.baseUrl))
                val owner = prefs.memberReconnectOwner()
                val target = TrustedEndpointProfile.systemPki("https://candidate.example.com")
                val attempt = PendingMemberLogin("", "妈妈", "Phone", 0, "operation", target.origin, true)
                assertThat(prefs.beginReconnectMemberAttempt(attempt, owner)).isTrue()
                when (transition) {
                    "forget" -> prefs.forgetEndpoint()
                    "replace-device" -> prefs.saveSession(old.copy(deviceId = "new-device", membershipId = "new-member"))
                    else -> prefs.saveReconnectedSession(old.copy(role = FamilyRole.Owner), TrustedEndpointProfile.systemPki("https://owner.example.com"))
                }
                val retained = prefs.session.first()
                assertThat(prefs.isReconnectMemberAttemptCurrent(attempt.operationId, owner)).isFalse()
                assertThat(prefs.activateReconnectMemberIfCurrent(attempt.operationId, owner,
                    old.copy(serverHost = "candidate.example.com"), target)).isFalse()
                assertThat(prefs.session.first()).isEqualTo(retained)
                assertThat(prefs.verifiedEndpoint.first()?.origin).isNotEqualTo(target.origin)
            } finally { scope.cancel(); file.delete() }
        }
    }

    @Test
    fun delayedSecretCleanupCannotEraseANewerReceipt() = runTest {
        val file = File.createTempFile("lezi-member-secret-race-", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val base = InMemorySecureRefreshTokenStore()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val tokens = object : SecureRefreshTokenStore by base {
            override fun clearPendingMemberSecret() {
                entered.complete(Unit)
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                base.clearPendingMemberSecret()
            }
        }
        val prefs = preferences(PreferenceDataStoreFactory.create(scope = scope) { file }, tokens)
        prefs.savePendingMemberLogin(MemberLoginReceipt("request-a", "secret-a", 10), "爸爸", "Phone")
        try {
            val clear = async { prefs.clearPendingMemberLogin() }
            entered.await()
            val newer = async {
                prefs.savePendingMemberLogin(MemberLoginReceipt("request-b", "secret-b", 20), "妈妈", "Tablet")
            }
            runCurrent()
            assertThat(newer.isCompleted).isFalse()
            release.countDown()
            clear.await()
            newer.await()
            assertThat(prefs.pendingMemberLogin.first()?.requestId).isEqualTo("request-b")
            assertThat(prefs.pendingMemberSecret()).isEqualTo("secret-b")
        } finally {
            release.countDown()
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun oldAttemptFailureCleanupCannotRetireANewerOperation() = runTest {
        val file = File.createTempFile("lezi-member-attempt-cas-", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val prefs = preferences(PreferenceDataStoreFactory.create(scope = scope) { file })
        val newer = PendingMemberLogin("", "妈妈", "Phone", 0, "new-operation", "https://family.example.com", true)
        try {
            prefs.saveMemberLoginAttempt(newer)
            prefs.clearMemberLoginAttempt(expectedOperationId = "old-operation")
            assertThat(prefs.memberLoginAttempt()).isEqualTo(newer)
        } finally { scope.cancel(); file.delete() }
    }

    @Test
    fun rejectedLateReceiptDoesNotOverwriteTheCurrentSecureSlot() = runTest {
        val file = File.createTempFile("lezi-member-late-secret-", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val prefs = preferences(PreferenceDataStoreFactory.create(scope = scope) { file })
        try {
            prefs.savePendingMemberLogin(MemberLoginReceipt("current", "current-secret", 20), "妈妈", "Tablet")
            val late = runCatching {
                prefs.savePendingMemberLogin(MemberLoginReceipt("old", "old-secret", 10), "爸爸", "Phone", "retired-operation")
            }
            assertThat(late.isFailure).isTrue()
            assertThat(prefs.pendingMemberLogin.first()?.requestId).isEqualTo("current")
            assertThat(prefs.pendingMemberSecret()).isEqualTo("current-secret")
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun unknownMemberAttemptSurvivesRestartWithoutASecretAndIsRetiredByEndpointForget() = runTest {
        val file = File.createTempFile("lezi-member-unknown-", ".preferences_pb").also { it.delete() }
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val first = preferences(PreferenceDataStoreFactory.create(scope = firstScope) { file })
        val attempt = PendingMemberLogin(
            requestId = "", displayName = "爸爸", deviceName = "Phone", expiresAtEpochSeconds = 0,
            operationId = "local-operation-1", endpointOrigin = "https://family.example.com",
            remoteOutcomeUnknown = true,
        )
        first.saveMemberLoginAttempt(attempt)
        first.saveMemberLoginAttempt(attempt, reconnect = true)
        firstScope.cancel()
        advanceUntilIdle()
        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val restored = preferences(PreferenceDataStoreFactory.create(scope = secondScope) { file })
        try {
            assertThat(restored.pendingMemberLogin.first()).isEqualTo(attempt)
            assertThat(restored.memberLoginAttempt(reconnect = true)).isEqualTo(attempt)
            assertThat(restored.pendingMemberSecret()).isEmpty()
            assertThat(runCatching {
                restored.saveEndpointConfig(FamilyEndpointConfig(host = "different.example.com"))
            }.isFailure).isTrue()
            assertThat(restored.memberLoginAttempt()).isEqualTo(attempt)
            restored.forgetEndpoint()
            assertThat(restored.pendingMemberLogin.first()).isNull()
            assertThat(restored.memberLoginAttempt(reconnect = true)).isNull()
        } finally {
            secondScope.cancel()
            file.delete()
        }
    }

    @Test
    fun createRequestIdSurvivesRetryUntilOwnerSessionIsPersisted() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val first = preferences(firstStore)
        val requestId = first.ensureCreateRequestId()
        assertThat(first.ensureCreateRequestId()).isEqualTo(requestId)
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val secondStore = PreferenceDataStoreFactory.create(scope = secondScope) { file }
        val restored = preferences(secondStore)
        assertThat(restored.ensureCreateRequestId()).isEqualTo(requestId)

        restored.saveSession(
            SyncSession(
                serverHost = "nas",
                familyId = "family",
                accessToken = "token",
                deviceId = "device",
                role = FamilyRole.Owner,
            ),
        )
        assertThat(restored.ensureCreateRequestId()).isNotEqualTo(requestId)
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun ownerLoginRequestIdSurvivesRetryUntilOwnerSessionIsPersisted() = runTest {
        val file = File.createTempFile("lezi-owner-login-", ".preferences_pb").also { it.delete() }
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val first = preferences(firstStore)
        val requestId = first.ensureOwnerLoginRequestId()
        assertThat(first.ensureOwnerLoginRequestId()).isEqualTo(requestId)
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val secondStore = PreferenceDataStoreFactory.create(scope = secondScope) { file }
        val restored = preferences(secondStore)
        assertThat(restored.ensureOwnerLoginRequestId()).isEqualTo(requestId)

        restored.saveSession(
            SyncSession(
                serverHost = "nas",
                familyId = "family",
                accessToken = "token",
                deviceId = "device",
                role = FamilyRole.Owner,
            ),
        )
        assertThat(restored.ensureOwnerLoginRequestId()).isNotEqualTo(requestId)
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun sessionCredentialWriteFailureKeepsClaimAndOwnerReplayCapabilities() = runTest {
        val file = File.createTempFile("lezi-session-replay-", ".preferences_pb")
            .also { it.delete() }
        val tokens = FailOnceSetTokenStore()
        val preferences = preferences(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { file },
            tokens,
        )
        val ownerRequestId = preferences.ensureOwnerLoginRequestId()
        val receipt = MemberLoginReceipt(
            requestId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            pendingSecret = "pending-secret-000000000000000000000001",
            expiresAtEpochSeconds = 1_753_504_800,
        )
        preferences.savePendingMemberLogin(receipt, "爸爸", "Pixel 9")
        tokens.failNextSet = true
        val joined = SyncSession(
            serverHost = "family.home",
            serverScheme = "https",
            familyId = "family",
            accessToken = "member-access",
            refreshToken = "member-refresh",
            deviceId = "member-device",
            role = FamilyRole.Member,
            membershipId = "member-membership",
        )

        assertThat(runCatching { preferences.saveSession(joined) }.isFailure).isTrue()

        assertThat(preferences.ensureOwnerLoginRequestId()).isEqualTo(ownerRequestId)
        assertThat(preferences.pendingMemberLogin.first()?.requestId).isEqualTo(receipt.requestId)
        assertThat(preferences.pendingMemberSecret()).isEqualTo(receipt.pendingSecret)

        preferences.saveSession(joined)
        assertThat(preferences.ensureOwnerLoginRequestId()).isNotEqualTo(ownerRequestId)
        assertThat(preferences.pendingMemberLogin.first()).isNull()
        assertThat(preferences.pendingMemberSecret()).isEmpty()
        file.delete()
    }

    @Test
    fun refreshRequestIdSurvivesRestartAndRetiresOnlyWithDurableRotatedSession() = runTest {
        val file = File.createTempFile("lezi-refresh-rotation-", ".preferences_pb")
            .also { it.delete() }
        val tokens = InMemorySecureRefreshTokenStore()
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val first = preferences(
            PreferenceDataStoreFactory.create(scope = firstScope) { file },
            tokens,
        )
        val requestId = first.ensureRefreshRequestId()
        assertThat(requestId.length).isAtLeast(32)
        assertThat(requestId.matches(Regex("[A-Za-z0-9_-]{32,128}"))).isTrue()
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val restored = preferences(
            PreferenceDataStoreFactory.create(scope = secondScope) { file },
            tokens,
        )
        assertThat(restored.ensureRefreshRequestId()).isEqualTo(requestId)
        restored.saveSession(
            SyncSession(
                serverHost = "family.home",
                serverScheme = "https",
                familyId = "family",
                accessToken = "rotated-access",
                refreshToken = "rotated-refresh",
                deviceId = "device",
                role = FamilyRole.Owner,
                membershipId = "owner",
            ),
        )
        assertThat(restored.ensureRefreshRequestId()).isNotEqualTo(requestId)
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun failedRotatedTokenWriteLeavesOldJoinedSessionAndReplayNonceUsable() = runTest {
        val file = File.createTempFile("lezi-refresh-write-", ".preferences_pb")
            .also { it.delete() }
        val tokens = FailOnceSetTokenStore()
        val preferences = preferences(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { file },
            tokens,
        )
        val original = SyncSession(
            serverHost = "family.home",
            serverScheme = "https",
            familyId = "family",
            accessToken = "old-access",
            refreshToken = "old-refresh",
            accessExpiresAtEpochSeconds = 100,
            deviceId = "device",
            role = FamilyRole.Owner,
            membershipId = "owner",
        )
        preferences.saveSession(original)
        val requestId = preferences.ensureRefreshRequestId()
        tokens.failNextSet = true
        val rotated = original.copy(
            accessToken = "new-access",
            refreshToken = "new-refresh",
            accessExpiresAtEpochSeconds = 200,
        )

        assertThat(runCatching { preferences.saveRefreshedSession(rotated) }.isFailure).isTrue()

        assertThat(preferences.session.first()).isEqualTo(original)
        assertThat(preferences.session.first().isJoined).isTrue()
        assertThat(preferences.ensureRefreshRequestId()).isEqualTo(requestId)

        preferences.saveRefreshedSession(rotated)
        assertThat(preferences.session.first()).isEqualTo(rotated)
        assertThat(preferences.ensureRefreshRequestId()).isNotEqualTo(requestId)
        file.delete()
    }

    @Test
    fun pendingMemberCapabilitySurvivesProcessButIsEncryptedAndRetiredWithSession() = runTest {
        val file = File.createTempFile("lezi-member-pending-", ".preferences_pb")
            .also { it.delete() }
        val tokens = InMemorySecureRefreshTokenStore()
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val first = preferences(firstStore, tokens)
        val receipt = MemberLoginReceipt(
            requestId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            pendingSecret = "pending-secret-000000000000000000000001",
            expiresAtEpochSeconds = 1_753_504_800,
        )

        first.savePendingMemberLogin(receipt, "爸爸", "Pixel 9")
        assertThat(first.pendingMemberLogin.first()).isEqualTo(
            PendingMemberLogin(
                requestId = receipt.requestId,
                displayName = "爸爸",
                deviceName = "Pixel 9",
                expiresAtEpochSeconds = receipt.expiresAtEpochSeconds,
            ),
        )
        assertThat(first.pendingMemberSecret()).isEqualTo(receipt.pendingSecret)
        assertThat(file.readBytes().toString(Charsets.ISO_8859_1))
            .doesNotContain(receipt.pendingSecret)
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val restored = preferences(
            PreferenceDataStoreFactory.create(scope = secondScope) { file },
            tokens,
        )
        assertThat(restored.pendingMemberLogin.first()?.requestId).isEqualTo(receipt.requestId)
        assertThat(restored.pendingMemberSecret()).isEqualTo(receipt.pendingSecret)

        restored.saveSession(
            SyncSession(
                serverHost = "family.home",
                serverScheme = "https",
                familyId = "family",
                accessToken = "member-access",
                refreshToken = "member-refresh",
                deviceId = "member-device",
                role = FamilyRole.Member,
                membershipId = "member-membership",
            ),
        )
        assertThat(restored.pendingMemberLogin.first()).isNull()
        assertThat(tokens.getPendingMemberSecret()).isEmpty()
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun pendingMemberSecretIoNeverBlocksTheCallingUiDispatcher() = runBlocking {
        val file = File.createTempFile("lezi-member-secret-thread-", ".preferences_pb")
            .also { it.delete() }
        val uiDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "account-ui-test")
        }.asCoroutineDispatcher()
        val dataDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "data-store-test")
        }.asCoroutineDispatcher()
        val dataScope = CoroutineScope(dataDispatcher + SupervisorJob())
        val secureStore = ThreadRecordingPendingSecretStore()
        val preferences = preferences(
            PreferenceDataStoreFactory.create(scope = dataScope) { file },
            secureStore,
        )
        val receipt = MemberLoginReceipt(
            requestId = "cccccccc-cccc-cccc-cccc-cccccccccccc",
            pendingSecret = "pending-secret-000000000000000000000003",
            expiresAtEpochSeconds = 1_753_504_800,
        )

        try {
            withContext(uiDispatcher) {
                preferences.savePendingMemberLogin(receipt, "奶奶", "Pixel 10")
                assertThat(preferences.pendingMemberSecret()).isEqualTo(receipt.pendingSecret)
                preferences.clearPendingMemberLogin()
            }

            assertThat(secureStore.setThread.get()).doesNotContain("account-ui-test")
            assertThat(secureStore.getThread.get()).doesNotContain("account-ui-test")
            assertThat(secureStore.clearThread.get()).doesNotContain("account-ui-test")
        } finally {
            dataScope.cancel()
            uiDispatcher.close()
            dataDispatcher.close()
            file.delete()
        }
    }

    @Test
    fun pendingMemberLocalAbandonmentSurvivesSecureResidueCleanupFailure() = runTest {
        val file = File.createTempFile("lezi-member-abandon-", ".preferences_pb")
            .also { it.delete() }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val secureStore = FailOnceClearPendingMemberSecretStore()
        val preferences = preferences(
            PreferenceDataStoreFactory.create(scope = scope) { file },
            secureStore,
        )
        val first = MemberLoginReceipt(
            requestId = "dddddddd-dddd-dddd-dddd-dddddddddddd",
            pendingSecret = "pending-secret-000000000000000000000004",
            expiresAtEpochSeconds = 1_753_504_800,
        )
        val second = MemberLoginReceipt(
            requestId = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
            pendingSecret = "pending-secret-000000000000000000000005",
            expiresAtEpochSeconds = 1_753_508_400,
        )

        try {
            preferences.savePendingMemberLogin(first, "奶奶", "Pixel 10")
            secureStore.failNextPendingClear = true

            preferences.clearPendingMemberLogin()

            assertThat(preferences.pendingMemberLogin.first()).isNull()
            assertThat(secureStore.getPendingMemberSecret()).isEqualTo(first.pendingSecret)

            preferences.savePendingMemberLogin(second, "奶奶", "Pixel 10 Pro")
            assertThat(preferences.pendingMemberLogin.first()?.requestId).isEqualTo(second.requestId)
            assertThat(preferences.pendingMemberSecret()).isEqualTo(second.pendingSecret)
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun disasterRestoreCheckpointSurvivesRestartWithoutWritingCredentialToDataStore() = runTest {
        val file = File.createTempFile("lezi-restore-checkpoint-", ".preferences_pb")
            .also { it.delete() }
        val tokens = InMemorySecureRefreshTokenStore()
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val first = preferences(
            PreferenceDataStoreFactory.create(scope = firstScope) { file },
            tokens,
        )
        val endpoint = TrustedEndpointProfile.systemPki("https://family.home:8765")
        val checkpoint = DisasterRestoreCheckpoint(
            batchId = "batch-a",
            endpoint = endpoint,
            familyId = "family-a",
            startRequestId = "start-request-00000000000000000001",
            manifestRequestId = "manifest-request-0000000000000001",
            commitRequestId = "commit-request-000000000000000001",
            expiresAtEpochSeconds = 1_753_504_800,
            status = "manifest_received",
            entityVersions = listOf(
                DisasterRestoreEntityVersion("record", "record-a", 42, true),
            ),
        )
        val recoveryToken = "restore-token-000000000000000000000001"

        first.saveDisasterRestoreCheckpoint(checkpoint, recoveryToken)
        assertThat(first.disasterRestoreToken()).isEqualTo(recoveryToken)
        assertThat(file.readBytes().toString(Charsets.ISO_8859_1))
            .doesNotContain(recoveryToken)
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val restored = preferences(
            PreferenceDataStoreFactory.create(scope = secondScope) { file },
            tokens,
        )
        assertThat(restored.disasterRestoreCheckpoint.first()).isEqualTo(checkpoint)
        assertThat(restored.disasterRestoreToken()).isEqualTo(recoveryToken)

        restored.clearDisasterRestoreCheckpoint()
        assertThat(restored.disasterRestoreCheckpoint.first()).isNull()
        assertThat(restored.disasterRestoreToken()).isEmpty()
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun nonAuthoritativeConfigRewriteKeepsPendingMemberCapability() = runTest {
        val file = File.createTempFile("lezi-member-config-", ".preferences_pb")
            .also { it.delete() }
        val tokens = InMemorySecureRefreshTokenStore()
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store, tokens)
        val receipt = MemberLoginReceipt(
            requestId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            pendingSecret = "pending-secret-000000000000000000000002",
            expiresAtEpochSeconds = 1_753_504_800,
        )
        preferences.saveEndpointConfig(FamilyEndpointConfig(host = "old.home"))
        preferences.savePendingMemberLogin(receipt, "奶奶", "Pixel 10")

        preferences.saveEndpointConfig(
            FamilyEndpointConfig(host = "new.home"),
            clearSessionIfServerChanged = false,
        )

        assertThat(preferences.pendingMemberLogin.first()?.requestId).isEqualTo(receipt.requestId)
        assertThat(preferences.pendingMemberSecret()).isEqualTo(receipt.pendingSecret)
        file.delete()
    }
}
