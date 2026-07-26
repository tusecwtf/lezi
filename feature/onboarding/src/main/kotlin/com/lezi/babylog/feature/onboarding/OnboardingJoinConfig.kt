package com.lezi.babylog.feature.onboarding

import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.InvitePayloadCodec

internal data class OnboardingInvitePrefill(
    val host: String,
    val portText: String,
    val scheme: String,
    val ssids: List<String>,
)

internal fun decodeOnboardingInvitePrefill(rawPayload: String): OnboardingInvitePrefill {
    val decoded = InvitePayloadCodec.decode(rawPayload)
    val config = decoded.homeLanConfig
    return OnboardingInvitePrefill(
        host = config.host,
        portText = config.port.toString(),
        scheme = config.scheme,
        ssids = decoded.ssids,
    )
}

internal fun buildOnboardingHomeLanConfig(
    host: String,
    portText: String,
    scheme: String,
    ssids: List<String>,
): HomeLanServerConfig = HomeLanServerConfig.fromUserInput(
    rawHostOrUrl = host,
    explicitPort = portText.toIntOrNull(),
    allowedSsids = ssids,
    fallbackScheme = scheme,
)
