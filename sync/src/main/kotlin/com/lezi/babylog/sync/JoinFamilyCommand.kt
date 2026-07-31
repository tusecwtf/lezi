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

/**
 * Where home-LAN values on the join draft came from (wizard session).
 * Copy and routing use this enum; do not infer “from invite” from host alone.
 */
enum class JoinNetworkProvenance {
    None,
    NoviceHint,
    PrefsSaved,
    ScannedFull,
    UserEdited,
}

/**
 * Result of safe invite field / scan ingestion. [error] is product copy for the invite field.
 * Never throws from [JoinFamilyDraft.applyInvitationInput].
 */
data class InvitationInputResult(
    val draft: JoinFamilyDraft,
    val error: String? = null,
    val decodedHost: Boolean = false,
    val fromFullPayload: Boolean = false,
)

/** Shared mutable-UI value object for onboarding and the family account flow. */
data class JoinFamilyDraft(
    val invitation: String = "",
    val host: String = "",
    val portText: String = DEFAULT_SERVER_PORT.toString(),
    val scheme: String = DEFAULT_SERVER_SCHEME,
) {
    /** Join readiness is an explicit endpoint; trust is established by the setup probe. */
    fun hasJoinNetwork(): Boolean = host.isNotBlank()

    /**
     * Decode invite and merge network fields. Stores **short code** only.
     * Throws on invalid payload (callers that must not crash use [applyInvitationInput]).
     */
    fun prefillInvitation(raw: String): JoinFamilyDraft {
        val invitation = raw.trim()
        if (invitation.isEmpty()) return this
        val decoded = InvitePayloadCodec.decode(invitation)
        val invitedConfig = decoded.homeLanConfig.withNormalized()
        return copy(
            invitation = decoded.code,
            host = invitedConfig.host.takeIf(String::isNotBlank) ?: host,
            portText = if (invitedConfig.host.isNotBlank()) invitedConfig.port.toString() else portText,
            scheme = if (invitedConfig.host.isNotBlank()) invitedConfig.scheme else scheme,
        )
    }

    /**
     * Safe invite field / scan ingestion. Never throws.
     * - blank: unchanged
     * - successful JSON or plain code: short code + optional network merge
     * - broken `{...`: keep draft; surface error; never store partial JSON as invitation
     * - intermediate plain typing: store trimmed raw for later validation
     */
    fun applyInvitationInput(raw: String): InvitationInputResult {
        val value = raw.trim()
        if (value.isEmpty()) return InvitationInputResult(this)

        if (value.startsWith("{")) {
            return runCatching {
                val decoded = InvitePayloadCodec.decode(value)
                val next = prefillInvitation(value)
                InvitationInputResult(
                    draft = next,
                    decodedHost = decoded.host.isNotBlank() || decoded.baseUrl.isNotBlank(),
                    fromFullPayload = true,
                )
            }.getOrElse {
                InvitationInputResult(
                    draft = this,
                    error = it.message?.takeIf(String::isNotBlank)
                        ?: "邀请内容无效，请重新扫码或输入邀请码",
                )
            }
        }

        return runCatching {
            val code = InvitePayloadCodec.decode(value).code
            InvitationInputResult(
                draft = copy(invitation = code),
                decodedHost = false,
                fromFullPayload = false,
            )
        }.getOrElse {
            InvitationInputResult(copy(invitation = value), error = null)
        }
    }

    /**
     * Merge saved prefs network into this draft without wiping [invitation].
     * Used when prefs change mid-wizard while the user still holds an invite code.
     */
    fun mergeFromSaved(config: HomeLanServerConfig): JoinFamilyDraft {
        val normalized = config.withNormalized()
        return copy(
            host = normalized.host,
            portText = normalized.port.toString(),
            scheme = normalized.scheme,
            // invitation preserved
        )
    }

    /**
     * Build a join command. [displayName] is product-required (same rules as
     * createFamily / updateMyDisplayName); blank / 「我（本机）」 fail.
     * A trusted endpoint is required so account wizard and onboarding share one path.
     */
    fun toCommand(displayName: String): JoinFamilyCommand {
        require(invitation.trim().isNotEmpty()) { "请填写邀请码" }
        val config = HomeLanServerConfig.fromUserInput(
            rawHostOrUrl = host,
            explicitPort = portText.toIntOrNull(),
            fallbackScheme = scheme,
        )
        require(config.isServerConfigured) { "请填写服务器主机" }
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
            )
        }
    }
}

/** Pure: after invite applied, land on Identity only when an endpoint is ready. */
fun joinDraftReadyForIdentity(draft: JoinFamilyDraft): Boolean = draft.hasJoinNetwork()

/** Pure: Network-step neutral hint after partial prefill; null = no info line. */
fun joinNetworkPartialPrefillHint(draft: JoinFamilyDraft): String? = when {
    draft.invitation.isNotBlank() && !draft.hasJoinNetwork() && draft.host.isBlank() ->
        "邀请码已填入，请填写家庭服务器地址"
    else -> null
}

/**
 * Durable Identity endpoint summary.
 */
fun identityNetworkSummary(
    draft: JoinFamilyDraft,
    provenance: JoinNetworkProvenance,
): String? {
    if (!draft.hasJoinNetwork()) return null
    val endpoint = "${draft.host.trim()}:${draft.portText.trim()}"
    return when (provenance) {
        JoinNetworkProvenance.ScannedFull ->
            "服务器已从邀请带入 · $endpoint"
        JoinNetworkProvenance.PrefsSaved,
        JoinNetworkProvenance.UserEdited,
        ->
            "家庭服务器已就绪 · $endpoint"
        JoinNetworkProvenance.NoviceHint ->
            "已预填家庭服务器地址（可改） · $endpoint"
        JoinNetworkProvenance.None -> null
    }
}

/** Hint when Identity is open without a ready network. */
fun identityNetworkMissingHint(draft: JoinFamilyDraft): String? =
    if (draft.hasJoinNetwork()) {
        null
    } else {
        "尚未配置家庭服务器：请扫码带入，或点「上一步」填写"
    }

/**
 * Provenance after wizard finally dismisses (not Message/Guide mid-stack).
 * Prefers PrefsSaved when prefs already hold a configured endpoint.
 */
fun initialProvenanceAfterDismiss(
    prefsConfigured: Boolean,
    draft: JoinFamilyDraft,
): JoinNetworkProvenance = when {
    prefsConfigured -> JoinNetworkProvenance.PrefsSaved
    !draft.hasJoinNetwork() -> JoinNetworkProvenance.None
    !prefsConfigured &&
        draft.host.trim() == DEFAULT_SERVER_HOST &&
        draft.portText.toIntOrNull() == DEFAULT_SERVER_PORT ->
        JoinNetworkProvenance.NoviceHint
    else -> JoinNetworkProvenance.UserEdited
}

/**
 * Update provenance after a successful [InvitationInputResult] with no error.
 * Plain code does not set Scanned*.
 */
fun provenanceAfterInviteInput(
    previous: JoinNetworkProvenance,
    result: InvitationInputResult,
): JoinNetworkProvenance {
    if (result.error != null) return previous
    return when {
        result.fromFullPayload && result.decodedHost ->
            JoinNetworkProvenance.ScannedFull
        else -> previous
    }
}

/**
 * After the user edits endpoint fields, demote scanned provenance so
 * summary no longer claims values still come from the invite.
 */
fun provenanceAfterManualNetworkEdit(previous: JoinNetworkProvenance): JoinNetworkProvenance =
    when (previous) {
        JoinNetworkProvenance.ScannedFull,
        JoinNetworkProvenance.NoviceHint,
        JoinNetworkProvenance.None,
        -> JoinNetworkProvenance.UserEdited
        else -> previous
    }

/**
 * Join confirm enablement (K11): network ready, invite non-blank, 称呼 non-empty.
 * Does not run full display-name validation (that still happens at submit).
 */
fun joinConfirmEnabled(
    draft: JoinFamilyDraft,
    displayName: String,
    joining: Boolean = false,
): Boolean =
    !joining &&
        draft.hasJoinNetwork() &&
        draft.invitation.isNotBlank() &&
        displayName.trim().isNotEmpty()
