package com.lezi.babylog.feature.family.networksettings

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.availability.FamilyServerAvailability
import com.lezi.babylog.sync.availability.FamilyServerUnavailableReason
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FamilyNetworkSettingsActionsTest {
    @Test
    fun candidateProbeReturnsReadyWithoutRememberingOrReplacingTheActiveEndpoint() = runTest {
        val old = TrustedEndpointProfile.systemPki("https://old.example.test")
        val candidate = TrustedEndpointProfile.systemPki("https://new.example.test")
        var remembered: TrustedEndpointProfile? = old
        val sync = object : SyncPort by NoOpSyncPort() {
            override suspend fun probeReconnectEndpoint(endpointDraft: String) =
                SetupProbeResult.Ready(candidate, SetupFamilyState.Configured)

            override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile): Result<Unit> {
                remembered = endpoint
                return Result.success(Unit)
            }
        }

        val result = FamilyNetworkSettingsActions(sync).probeCandidate(candidate.origin)

        assertThat(result).isEqualTo(
            FamilyNetworkCandidate.Ready(candidate, SetupFamilyState.Configured),
        )
        assertThat(remembered).isEqualTo(old)
    }

    @Test
    fun availabilityCopyIsResultOrientedAndKeepsLastHealthyTime() {
        val unavailable = FamilyServerAvailability.Unavailable(
            reason = FamilyServerUnavailableReason.Unreachable,
            lastHealthyAtMillis = 1_234L,
            nextProbeAtMillis = 31_234L,
            consecutiveFailures = 1,
        )

        val copy = networkAvailabilityCopy(unavailable)

        assertThat(copy.status).isEqualTo("当前不可连接，本机护理可继续使用")
        assertThat(copy.lastHealthyAtMillis).isEqualTo(1_234L)
    }

    @Test
    fun trustChangeCopyGuidesCertificateCheckAndReauthentication() {
        val unavailable = FamilyServerAvailability.Unavailable(
            reason = FamilyServerUnavailableReason.TrustChanged,
            lastHealthyAtMillis = 1_234L,
            nextProbeAtMillis = 31_234L,
            consecutiveFailures = 1,
        )

        val copy = networkAvailabilityCopy(unavailable)

        assertThat(copy.status).isEqualTo("服务器安全信息已变化，请核对证书并重新登录")
        assertThat(copy.lastHealthyAtMillis).isEqualTo(1_234L)
        assertThat(copy.trustRecoveryRequired).isTrue()
    }
}
