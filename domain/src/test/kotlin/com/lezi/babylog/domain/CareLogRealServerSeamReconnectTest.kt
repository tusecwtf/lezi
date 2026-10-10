package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class CareLogRealServerSeamReconnectTest {
    @Test
    fun ordinaryReconnectPreservesAnotherAdministratorsLiveSession() = runBlocking {
        CareLogRealServerSeamFixture.open().use { fixture ->
            val other = fixture.joinExtraOwner("other-admin")
            val before = other.currentSession()
            withTimeout(10_000) {
                fixture.owner.port.reconnectOwner(
                    endpoint = requireNotNull(fixture.owner.preferences.currentEndpoint()),
                    deviceName = "Reconnected owner",
                    rootPassword = fixture.server.bootstrapSecret,
                ).getOrThrow()
                other.port.listFamilyMembers().getOrThrow()
            }
            assertThat(other.currentSession().deviceId).isEqualTo(before.deviceId)
            assertThat(other.currentSession().isJoined).isTrue()
        }
    }
}
