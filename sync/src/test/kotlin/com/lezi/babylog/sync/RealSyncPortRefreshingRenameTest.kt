package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.backend.RefreshingSyncBackend
import com.lezi.babylog.sync.backend.SessionRefreshResult
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.testPullPage
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SyncSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Production facade + refresh owner, with deterministic transport (not a Rust/Room proof). */
class RealSyncPortRefreshingRenameTest {
    @Test
    fun expiredAccessAndInFlight401RenameKeepRotatedCredentialsForTheNextRequest() = runTest {
        for (expired in listOf(true, false)) {
            val original = joinedSession("family-a").copy(
                role = FamilyRole.Owner, accessToken = "old-access", refreshToken = "old-refresh",
                accessExpiresAtEpochSeconds = if (expired) 0 else Long.MAX_VALUE,
            )
            val preferences = MemorySyncPreferences(original)
            val requests = mutableListOf<String>()
            val refreshes = mutableListOf<String>()
            val delegate = object : SyncBackend by RecordingSyncBackend() {
                override suspend fun refresh(baseUrl: String, refreshToken: String, refreshRequestId: String): SessionRefreshResult {
                    refreshes += refreshToken
                    return refreshed(original)
                }
                override suspend fun renameFamily(session: SyncSession, familyName: String?) {
                    requests += session.accessToken
                    if (session.accessToken == "old-access") throw SyncHttpException(401)
                }
            }
            val backend = RefreshingSyncBackend(delegate, preferences, object : PolicyClock {
                override fun nowMillis() = 1_000L
            })
            val rig = SyncRig(original, syncBackend = backend, syncPreferences = preferences)
            rig.awaitInitialReplicaBarrier()
            assertThat(rig.port.renameFamily("Renamed synthetic family").isSuccess).isTrue()
            assertThat(preferences.current().familyName).isEqualTo("Renamed synthetic family")
            assertThat(preferences.current().accessToken).isEqualTo("new-access")
            assertThat(preferences.current().refreshToken).isEqualTo("new-refresh")
            assertThat(requests).containsExactlyElementsIn(
                if (expired) listOf("new-access") else listOf("old-access", "new-access"),
            ).inOrder()
            backend.pull(preferences.current(), testPullPage())
            assertThat(refreshes).containsExactly("old-refresh")
        }
    }

    @Test
    fun concurrentRefreshDuringRenameKeepsItsCredentialsAndRejectsOldIdentityResponse() = runTest {
        for (newIdentity in listOf(false, true)) {
            val original = joinedSession("family-a").copy(
                role = FamilyRole.Owner, accessToken = "old-access", refreshToken = "old-refresh",
                accessExpiresAtEpochSeconds = Long.MAX_VALUE,
            )
            val preferences = MemorySyncPreferences(original)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delegate = object : SyncBackend by RecordingSyncBackend() {
                override suspend fun renameFamily(session: SyncSession, familyName: String?) {
                    entered.complete(Unit)
                    release.await()
                }
                override suspend fun refresh(baseUrl: String, refreshToken: String, refreshRequestId: String) = refreshed(original)
            }
            val backend = RefreshingSyncBackend(delegate, preferences, object : PolicyClock {
                override fun nowMillis() = 1_000L
            })
            val rig = SyncRig(original, syncBackend = backend, syncPreferences = preferences)
            rig.awaitInitialReplicaBarrier()
            val rename = async { rig.port.renameFamily("Renamed synthetic family") }
            entered.await()
            if (newIdentity) {
                preferences.saveSession(original.copy(familyId = "family-b", deviceId = "device-b", familyName = "New login"))
            } else {
                preferences.saveRefreshedSession(original.copy(accessExpiresAtEpochSeconds = 0))
                backend.pull(preferences.current(), testPullPage())
            }
            release.complete(Unit)
            val result = rename.await()
            if (newIdentity) {
                assertThat(result.isFailure).isTrue()
                assertThat(preferences.current().familyName).isEqualTo("New login")
                assertThat(preferences.current().familyId).isEqualTo("family-b")
            } else {
                assertThat(result.isSuccess).isTrue()
                assertThat(preferences.current().familyName).isEqualTo("Renamed synthetic family")
                assertThat(preferences.current().accessToken).isEqualTo("new-access")
                assertThat(preferences.current().refreshToken).isEqualTo("new-refresh")
            }
        }
    }

    private fun refreshed(original: SyncSession) = SessionRefreshResult(
        familyId = original.familyId, membershipId = original.membershipId,
        deviceId = original.deviceId, role = original.role,
        accessToken = "new-access", refreshToken = "new-refresh",
        accessExpiresAtEpochSeconds = Long.MAX_VALUE,
        generation = original.pullGeneration, familyName = original.familyName,
    )
}
