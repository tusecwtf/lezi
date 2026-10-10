package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.backend.MemberLoginReceipt
import com.lezi.babylog.sync.session.SyncSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The isolated-server fixture must honor the same scoped persistence contract as production. */
class InMemorySyncPreferencesContractTest {
    private fun attempt(id: String) = PendingMemberLogin(
        requestId = "", displayName = "照护者", deviceName = "test-device",
        expiresAtEpochSeconds = 0, operationId = id,
        endpointOrigin = "https://example.test:8765", remoteOutcomeUnknown = true,
    )

    @Test
    fun staleReceiptAndScopedCleanupCannotReplaceOrEraseNewerAttempt() = runTest {
        val preferences = InMemorySyncPreferences(SyncSession())
        preferences.saveMemberLoginAttempt(attempt("new"))
        val receipt = MemberLoginReceipt("request", "synthetic-secret", 1234)
        assertThat(runCatching {
            preferences.savePendingMemberLogin(receipt, "照护者", "test-device", "old")
        }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(preferences.memberLoginAttempt()?.operationId).isEqualTo("new")
        assertThat(preferences.pendingMemberSecret()).isEmpty()
        preferences.clearPendingMemberLogin("old")
        assertThat(preferences.memberLoginAttempt()?.operationId).isEqualTo("new")
        preferences.savePendingMemberLogin(receipt, "照护者", "test-device", "new")
        val pending = preferences.pendingMemberLogin.first()!!
        assertThat(pending.operationId).isEqualTo("new")
        assertThat(pending.endpointOrigin).isEqualTo("https://example.test:8765")
        preferences.clearPendingMemberLogin("old")
        assertThat(preferences.pendingMemberLogin.first()).isEqualTo(pending)
        preferences.clearPendingMemberLogin("new")
        assertThat(preferences.pendingMemberLogin.first()).isNull()
        assertThat(preferences.pendingMemberSecret()).isEmpty()
    }

    @Test
    fun forgettingEndpointHonorsExplicitAttemptRetention() = runTest {
        val preferences = InMemorySyncPreferences(SyncSession())
        preferences.saveMemberLoginAttempt(attempt("join"))
        preferences.saveMemberLoginAttempt(attempt("reconnect"), reconnect = true)
        preferences.forgetEndpoint(retainMemberAttempts = true)
        assertThat(preferences.memberLoginAttempt()?.operationId).isEqualTo("join")
        assertThat(preferences.memberLoginAttempt(reconnect = true)?.operationId).isEqualTo("reconnect")
        preferences.forgetEndpoint(retainMemberAttempts = false)
        assertThat(preferences.memberLoginAttempt()).isNull()
        assertThat(preferences.memberLoginAttempt(reconnect = true)).isNull()
    }

    @Test
    fun identityReplacementFencesLatePendingActivationAndSecretRead() = runTest {
        val preferences = InMemorySyncPreferences(SyncSession(deviceId = "original"))
        val owner = preferences.memberReconnectOwner()
        preferences.saveMemberLoginAttempt(attempt("join"))
        preferences.savePendingMemberLogin(
            MemberLoginReceipt("request", "synthetic-secret", 1234), "照护者", "test-device", "join",
        )
        assertThat(preferences.isPendingMemberLoginCurrent("request", owner)).isTrue()
        preferences.saveSession(SyncSession(deviceId = "replacement"))
        assertThat(preferences.pendingMemberSecretIfCurrent("request", owner)).isNull()
        assertThat(preferences.activatePendingMemberIfCurrent(
            "request", owner, SyncSession(deviceId = "late"), pendingReplicaResetPrevious = null,
        )).isFalse()
        assertThat(preferences.current().deviceId).isEqualTo("replacement")
    }
}
