package com.lezi.babylog.sync.availability

import com.lezi.babylog.sync.session.SetupFamilyState

sealed interface FamilyServerAvailability {
    data object Disabled : FamilyServerAvailability

    data class Checking(
        val lastHealthyAtMillis: Long?,
    ) : FamilyServerAvailability

    data class Available(
        val endpointOrigin: String,
        val serverVersion: String,
        val lastHealthyAtMillis: Long,
        val leaseUntilMillis: Long,
        val familyState: SetupFamilyState? = null,
    ) : FamilyServerAvailability

    data class Unavailable(
        val reason: FamilyServerUnavailableReason,
        val lastHealthyAtMillis: Long?,
        val nextProbeAtMillis: Long,
        val consecutiveFailures: Int,
    ) : FamilyServerAvailability
}

enum class FamilyServerUnavailableReason {
    Unreachable,
    Maintenance,
    Incompatible,
    NotLezi,
    TrustChanged,
}

enum class AvailabilityProbeReason {
    Foreground,
    NetworkRecovered,
    PullToRefresh,
    LocalChanges,
    RetryDeadline,
}

object FamilyServerAvailabilityPolicy {
    const val HEALTHY_LEASE_MILLIS = 30_000L

    fun retryDelayMillis(consecutiveFailures: Int): Long = when {
        consecutiveFailures <= 1 -> 30_000L
        consecutiveFailures == 2 -> 120_000L
        else -> 600_000L
    }

    fun shouldProbe(
        state: FamilyServerAvailability,
        reason: AvailabilityProbeReason,
        nowMillis: Long,
    ): Boolean {
        if (
            reason == AvailabilityProbeReason.Foreground ||
            reason == AvailabilityProbeReason.NetworkRecovered ||
            reason == AvailabilityProbeReason.PullToRefresh
        ) {
            return true
        }
        return when (state) {
            FamilyServerAvailability.Disabled -> true
            is FamilyServerAvailability.Checking -> false
            is FamilyServerAvailability.Available -> nowMillis >= state.leaseUntilMillis
            is FamilyServerAvailability.Unavailable -> nowMillis >= state.nextProbeAtMillis
        }
    }
}
