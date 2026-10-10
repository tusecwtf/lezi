package com.lezi.babylog.sync.session

import java.util.Collections

/**
 * Credential-free snapshot for feature, domain, and app state. It is a read model, never an
 * authentication capability or an input to session writes. Identity, readiness, profile, and
 * provenance are projected together from one owner snapshot; no independently collected flows
 * can pair an old identity with a new session's readiness.
 *
 * [isJoined] is evaluated by [SyncSession] before its credentials are discarded. In particular,
 * a reconstructed refresh-only session is still joined, while a retained reauth identity is not.
 * Credential presence, expiry, pull checkpoints, and writable owner state are intentionally absent.
 */
@ConsistentCopyVisibility
data class SyncSessionPresentation private constructor(
    val familyId: String,
    val deviceId: String,
    val role: FamilyRole,
    val membershipId: String,
    val familyName: String?,
    val isJoined: Boolean,
    val reauthRequired: Boolean,
    val serverHost: String,
    val serverPort: Int,
    val serverScheme: String,
    val baseUrl: String,
    val lastSuccessAt: Long?,
    val pendingCreatorAcknowledgements: Set<CreatorAcknowledgementRef>,
) {
    fun isCreatorAcknowledgementPending(entityType: String, clientUuid: String): Boolean =
        CreatorAcknowledgementRef(entityType.trim(), clientUuid.trim()) in
            pendingCreatorAcknowledgements

    companion object {
        internal fun from(session: SyncSession): SyncSessionPresentation = with(session) {
            SyncSessionPresentation(
                familyId = familyId,
                deviceId = deviceId,
                role = role,
                membershipId = membershipId,
                familyName = familyName,
                isJoined = isJoined,
                reauthRequired = reauthRequired,
                serverHost = serverHost,
                serverPort = serverPort,
                serverScheme = serverScheme,
                baseUrl = baseUrl,
                lastSuccessAt = lastSuccessAt,
                pendingCreatorAcknowledgements = Collections.unmodifiableSet(
                    LinkedHashSet(pendingCreatorAcknowledgements),
                ),
            )
        }
    }
}

/** One projection shared by the production owner, legacy result adapters, and test fakes. */
fun SyncSession.toPresentation(): SyncSessionPresentation = SyncSessionPresentation.from(this)
