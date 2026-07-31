package com.lezi.babylog.sync

import javax.inject.Inject
import javax.inject.Singleton

/** Foreground + trusted endpoint gate; transport and reachability are tested by real HTTPS I/O. */
enum class ForegroundSyncDecision {
    Allowed,
    MissingEndpoint,
    UntrustedEndpoint,
    Background,
}

@Singleton
class ForegroundSyncGate @Inject constructor() {
    fun evaluate(
        config: FamilyEndpointConfig,
        trustedEndpoint: TrustedEndpointProfile?,
        isForeground: Boolean,
    ): ForegroundSyncDecision {
        if (!config.withNormalized().isServerConfigured) {
            return ForegroundSyncDecision.MissingEndpoint
        }
        if (!isForeground) return ForegroundSyncDecision.Background
        if (trustedEndpoint == null || !trustedEndpoint.matchesOrigin(config.baseUrl)) {
            return ForegroundSyncDecision.UntrustedEndpoint
        }
        return ForegroundSyncDecision.Allowed
    }
}
