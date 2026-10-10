package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/** Uses the actual DI providers and domain clear workflow, never a counting clear gate. */
class CareLogRealServerSeamTerminalClearTest {
    @Test
    fun logoutCompletesProductionClearAndLeavesTheSyncBarrierUsable() = runBlocking {
        CareLogRealServerSeamFixture.open().use { fixture ->
            val owner = fixture.owner
            owner.careLog.createBaby(CreateBabyInput(nickname = "Local baby", birthdayEpochDay = 20_000L))
            withTimeout(5_000) { owner.port.logoutCurrentDevice().getOrThrow() }
            assertThat(owner.currentSession().isJoined).isFalse()
            // A fresh operation must be able to acquire the same barrier after clearing.
            withTimeout(5_000) {
                val endpoint = requireNotNull(fixture.member.preferences.currentEndpoint())
                owner.port.rememberEndpoint(endpoint).getOrThrow()
                owner.port.saveEndpointConfig(
                    com.lezi.babylog.sync.session.FamilyEndpointConfig.fromBaseUrl(endpoint.origin),
                ).getOrThrow()
                owner.port.ownerLogin("Rejoined owner", fixture.server.bootstrapSecret, takeover = false).getOrThrow()
            }
            assertThat(owner.currentSession().isJoined).isTrue()
        }
    }

    @Test
    fun leavingFamilyCompletesProductionClear() = runBlocking {
        CareLogRealServerSeamFixture.open().use { fixture ->
            withTimeout(5_000) { fixture.member.port.leave().getOrThrow() }
            assertThat(fixture.member.currentSession().isJoined).isFalse()
        }
    }

    @Test
    fun selfRevocationCompletesProductionClear() = runBlocking {
        CareLogRealServerSeamFixture.open().use { fixture ->
            withTimeout(5_000) {
                fixture.owner.port.revokeFamilyDevice(fixture.owner.currentSession().deviceId).getOrThrow()
            }
            assertThat(fixture.owner.currentSession().isJoined).isFalse()
        }
    }

    @Test
    fun deletingFamilyCompletesProductionClear() = runBlocking {
        CareLogRealServerSeamFixture.open().use { fixture ->
            withTimeout(5_000) {
                fixture.owner.port.deleteFamily(
                    requireNotNull(fixture.owner.currentSession().familyName), fixture.server.bootstrapSecret,
                ).getOrThrow()
            }
            assertThat(fixture.owner.currentSession().isJoined).isFalse()
        }
    }
}
