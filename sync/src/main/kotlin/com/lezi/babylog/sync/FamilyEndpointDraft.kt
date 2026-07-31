package com.lezi.babylog.sync

/**
 * Shared non-sensitive endpoint draft for onboarding and account connection.
 * Authentication material belongs to the role-specific step, never this value.
 */
data class FamilyEndpointDraft(
    val host: String = "",
    val portText: String = DEFAULT_SERVER_PORT.toString(),
    val scheme: String = DEFAULT_SERVER_SCHEME,
) {
    fun hasEndpoint(): Boolean = host.isNotBlank()

    fun toConfig(): FamilyEndpointConfig {
        val config = FamilyEndpointConfig.fromUserInput(
            rawHostOrUrl = host,
            explicitPort = portText.toIntOrNull(),
            fallbackScheme = scheme,
        )
        require(config.isServerConfigured) { "请填写服务器主机" }
        return config
    }

    fun mergeFromSaved(config: FamilyEndpointConfig): FamilyEndpointDraft {
        val normalized = config.withNormalized()
        return copy(
            host = normalized.host,
            portText = normalized.port.toString(),
            scheme = normalized.scheme,
        )
    }

    companion object {
        fun fromConfig(config: FamilyEndpointConfig): FamilyEndpointDraft {
            val normalized = config.withNormalized()
            return FamilyEndpointDraft(
                host = normalized.host,
                portText = normalized.port.toString(),
                scheme = normalized.scheme,
            )
        }
    }
}

fun memberDisplayNameValidationError(displayName: String?): String? =
    runCatching {
        requireMemberDisplayName(displayName)
        null
    }.exceptionOrNull()?.message
