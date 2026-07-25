package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HomeNetworkPolicyTest {
    @Test
    fun emptyAddressAndNonWifiNeverProbeServer() = runTest {
        val probe = RecordingHealthProbe(result = true)
        val policy = HomeNetworkPolicy(
            networkState = FakeNetworkState(isWifi = false),
            healthProbe = probe,
            clock = FakePolicyClock(),
        )

        assertThat(policy.evaluate(baseUrl = "", isForeground = true))
            .isEqualTo(HomeNetworkDecision.MissingServer)
        assertThat(policy.evaluate(baseUrl = "http://nas:8765", isForeground = true))
            .isEqualTo(HomeNetworkDecision.NotOnWifi)
        assertThat(probe.calls).isEmpty()
    }

    @Test
    fun failedHealthProbeBacksOffBeforeTryingAgain() = runTest {
        val clock = FakePolicyClock(now = 1_000)
        val probe = RecordingHealthProbe(result = false)
        val policy = HomeNetworkPolicy(
            networkState = FakeNetworkState(isWifi = true),
            healthProbe = probe,
            clock = clock,
        )

        assertThat(policy.evaluate("http://nas:8765", isForeground = true))
            .isEqualTo(HomeNetworkDecision.ServerUnavailable)
        assertThat(policy.evaluate("http://nas:8765", isForeground = true))
            .isEqualTo(HomeNetworkDecision.BackingOff)
        assertThat(probe.calls).containsExactly("http://nas:8765")

        clock.now += 30_000
        probe.result = true
        assertThat(policy.evaluate("http://nas:8765", isForeground = true))
            .isEqualTo(HomeNetworkDecision.Allowed)
    }

    @Test
    fun backgroundAutomaticTriggerIsBlocked() = runTest {
        val probe = RecordingHealthProbe(result = true)
        val policy = HomeNetworkPolicy(
            networkState = FakeNetworkState(isWifi = true),
            healthProbe = probe,
            clock = FakePolicyClock(),
        )

        assertThat(policy.evaluate("http://nas:8765", isForeground = false))
            .isEqualTo(HomeNetworkDecision.Background)
        assertThat(probe.calls).isEmpty()
    }

    @Test
    fun changingServerDoesNotReusePreviousServersBackoff() = runTest {
        val probe = RecordingHealthProbe(result = false)
        val policy = HomeNetworkPolicy(
            networkState = FakeNetworkState(isWifi = true),
            healthProbe = probe,
            clock = FakePolicyClock(now = 1_000),
        )

        assertThat(policy.evaluate("http://old-nas:8765", isForeground = true))
            .isEqualTo(HomeNetworkDecision.ServerUnavailable)
        probe.result = true

        assertThat(policy.evaluate("http://new-nas:8765", isForeground = true))
            .isEqualTo(HomeNetworkDecision.Allowed)
        assertThat(probe.calls).containsExactly(
            "http://old-nas:8765",
            "http://new-nas:8765",
        ).inOrder()
    }
}

private class FakeNetworkState(private val isWifi: Boolean) : NetworkState {
    override fun isWifiConnected(): Boolean = isWifi
}

private class RecordingHealthProbe(var result: Boolean) : HealthProbe {
    val calls = mutableListOf<String>()
    override suspend fun isHealthy(baseUrl: String): Boolean {
        calls += baseUrl
        return result
    }
}

private class FakePolicyClock(var now: Long = 0) : PolicyClock {
    override fun nowMillis(): Long = now
}
