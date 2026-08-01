package com.lezi.babylog.sync.engine
import javax.inject.Inject
import javax.inject.Singleton
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.session.matchesOrigin

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

private fun ForegroundSyncDecision.userMessage(): String = when (this) {
    ForegroundSyncDecision.MissingEndpoint -> "请先连接可信家庭服务器"
    ForegroundSyncDecision.UntrustedEndpoint -> "服务器地址已变化，请重新确认并登录"
    ForegroundSyncDecision.Background -> "家庭同步仅在前台运行"
    ForegroundSyncDecision.Allowed -> ""
}

/** Raised when [ForegroundSyncGate] rejects a user-facing sync attempt. */
internal class ForegroundSyncBlockedException(
    val decision: ForegroundSyncDecision,
) : IllegalStateException(decision.userMessage())
