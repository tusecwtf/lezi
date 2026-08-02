package com.lezi.babylog.sync.availability

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FamilyServerAvailabilityPolicyTest {
    @Test
    fun healthyLeaseLastsThirtySecondsAndLocalWritesDoNotProbeInsideIt() {
        val available = FamilyServerAvailability.Available(
            endpointOrigin = "https://nas:8765",
            serverVersion = "0.3.3",
            lastHealthyAtMillis = 1_000,
            leaseUntilMillis = 31_000,
        )

        assertThat(
            FamilyServerAvailabilityPolicy.shouldProbe(
                state = available,
                reason = AvailabilityProbeReason.LocalChanges,
                nowMillis = 30_999,
            ),
        ).isFalse()
        assertThat(
            FamilyServerAvailabilityPolicy.shouldProbe(
                state = available,
                reason = AvailabilityProbeReason.LocalChanges,
                nowMillis = 31_000,
            ),
        ).isTrue()
    }

    @Test
    fun failuresBackOffThirtySecondsTwoMinutesThenTenMinutes() {
        assertThat(FamilyServerAvailabilityPolicy.retryDelayMillis(1)).isEqualTo(30_000)
        assertThat(FamilyServerAvailabilityPolicy.retryDelayMillis(2)).isEqualTo(120_000)
        assertThat(FamilyServerAvailabilityPolicy.retryDelayMillis(3)).isEqualTo(600_000)
        assertThat(FamilyServerAvailabilityPolicy.retryDelayMillis(30)).isEqualTo(600_000)
    }

    @Test
    fun repeatedLocalChangesRespectFailureDeadlineButUserEventsProbeImmediately() {
        val unavailable = FamilyServerAvailability.Unavailable(
            reason = FamilyServerUnavailableReason.Unreachable,
            lastHealthyAtMillis = 500,
            nextProbeAtMillis = 31_000,
            consecutiveFailures = 1,
        )

        assertThat(
            FamilyServerAvailabilityPolicy.shouldProbe(
                unavailable,
                AvailabilityProbeReason.LocalChanges,
                10_000,
            ),
        ).isFalse()
        assertThat(
            FamilyServerAvailabilityPolicy.shouldProbe(
                unavailable,
                AvailabilityProbeReason.Foreground,
                10_000,
            ),
        ).isTrue()
        assertThat(
            FamilyServerAvailabilityPolicy.shouldProbe(
                unavailable,
                AvailabilityProbeReason.PullToRefresh,
                10_000,
            ),
        ).isTrue()
        assertThat(
            FamilyServerAvailabilityPolicy.shouldProbe(
                unavailable,
                AvailabilityProbeReason.NetworkRecovered,
                10_000,
            ),
        ).isTrue()
    }
}
