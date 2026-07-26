package com.lezi.babylog.sync

/** Complete, caller-owned input for one join attempt. */
data class JoinFamilyCommand(
    val invitation: String,
    val homeLanConfig: HomeLanServerConfig,
    val displayName: String,
)

/**
 * Soft UI validation for family 称呼 (create/join/self-rename).
 * Shared by account wizard and onboarding so both reject the same inputs.
 * Returns null when valid.
 */
fun memberDisplayNameValidationError(displayName: String?): String? =
    runCatching {
        requireMemberDisplayName(displayName)
        null
    }.exceptionOrNull()?.message

/** Shared mutable-UI value object for onboarding and the family account flow. */
data class JoinFamilyDraft(
    val invitation: String = "",
    val host: String = "",
    val portText: String = DEFAULT_SERVER_PORT.toString(),
    val scheme: String = DEFAULT_SERVER_SCHEME,
    val ssid1: String = "",
    val ssid2: String = "",
) {
    val ssids: List<String>
        get() = HomeLanServerConfig.normalizeSsids(listOf(ssid1, ssid2))

    fun prefillInvitation(raw: String): JoinFamilyDraft {
        val invitation = raw.trim()
        if (invitation.isEmpty()) return this
        val decoded = InvitePayloadCodec.decode(invitation)
        val invitedConfig = decoded.homeLanConfig.withNormalized()
        return copy(
            invitation = invitation,
            host = invitedConfig.host.takeIf(String::isNotBlank) ?: host,
            portText = if (invitedConfig.host.isNotBlank()) invitedConfig.port.toString() else portText,
            scheme = if (invitedConfig.host.isNotBlank()) invitedConfig.scheme else scheme,
            ssid1 = decoded.ssids.getOrNull(0) ?: ssid1,
            ssid2 = decoded.ssids.getOrNull(1) ?: ssid2,
        )
    }

    /**
     * Build a join command. [displayName] is product-required (same rules as
     * createFamily / updateMyDisplayName); blank / 「我（本机）」 fail.
     * Network host + ≥1 SSID are always required so account wizard and onboarding share one path.
     */
    fun toCommand(displayName: String): JoinFamilyCommand {
        require(invitation.trim().isNotEmpty()) { "请填写邀请码" }
        val config = HomeLanServerConfig.fromUserInput(
            rawHostOrUrl = host,
            explicitPort = portText.toIntOrNull(),
            allowedSsids = ssids,
            fallbackScheme = scheme,
        )
        require(config.isServerConfigured) { "请填写服务器主机" }
        require(config.allowedSsids.isNotEmpty()) { "请至少填写一个家庭 Wi‑Fi 名称" }
        val normalizedName = requireMemberDisplayName(displayName)
        return JoinFamilyCommand(
            invitation = invitation.trim(),
            homeLanConfig = config,
            displayName = normalizedName,
        )
    }

    companion object {
        fun fromConfig(config: HomeLanServerConfig, invitation: String = ""): JoinFamilyDraft {
            val normalized = config.withNormalized()
            return JoinFamilyDraft(
                invitation = invitation,
                host = normalized.host,
                portText = normalized.port.toString(),
                scheme = normalized.scheme,
                ssid1 = normalized.allowedSsids.getOrNull(0).orEmpty(),
                ssid2 = normalized.allowedSsids.getOrNull(1).orEmpty(),
            )
        }
    }
}
