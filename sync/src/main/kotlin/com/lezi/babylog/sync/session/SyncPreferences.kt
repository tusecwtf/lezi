package com.lezi.babylog.sync.session
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.backend.MemberLoginReceipt
import com.lezi.babylog.sync.backend.normalizeFamilyNameForWire

enum class FamilyRole {
    Owner,
    Member,
    None,
}

/**
 * Exact local entity whose server-owned creator stamp has not been observed yet.
 *
 * This is provenance only: it never contains or implies a membership id. The
 * reference survives process death so a failed acknowledgement pull can retry
 * without guessing creator ownership.
 */
data class CreatorAcknowledgementRef(
    val entityType: String,
    val clientUuid: String,
)

data class SyncSession(
    val familyId: String = "",
    /** Short-lived access token. Never persisted; blank after process reconstruction. */
    val accessToken: String = "",
    /** Rotating refresh token projected from Keystore-backed storage. */
    val refreshToken: String = "",
    val accessExpiresAtEpochSeconds: Long = 0,
    /** Credentials are gone, but family identity and local replica are intentionally retained. */
    val reauthRequired: Boolean = false,
    val deviceId: String = "",
    val role: FamilyRole = FamilyRole.None,
    val pullCursor: Long = 0,
    val pullGeneration: String = "",
    val lastSuccessAt: Long? = null,
    val serverHost: String = "",
    val serverPort: Int = DEFAULT_SERVER_PORT,
    val serverScheme: String = DEFAULT_SERVER_SCHEME,
    /**
     * Shared family name cached from create/login/claim/rename responses.
     * Null when the current family has no configured shared name.
     * Cold start relies on this local cache (no GET family-name path in this ticket).
     */
    val familyName: String? = null,
    /**
     * Server-minted immutable membership identity for this device's family session.
     * Empty only while not authenticated; current session bootstrap responses require this field.
     */
    val membershipId: String = "",
    val pendingCreatorAcknowledgements: Set<CreatorAcknowledgementRef> = emptySet(),
) {
    val baseUrl: String
        get() = endpointConfig.baseUrl

    val isJoined: Boolean
        get() = !reauthRequired && baseUrl.isNotBlank() && familyId.isNotBlank() &&
            (accessToken.isNotBlank() || refreshToken.isNotBlank())

    val endpointConfig: FamilyEndpointConfig
        get() = FamilyEndpointConfig(
            host = serverHost,
            port = serverPort,
            scheme = serverScheme,
        ).withNormalized()

    fun isCreatorAcknowledgementPending(entityType: String, clientUuid: String): Boolean =
        CreatorAcknowledgementRef(entityType.trim(), clientUuid.trim()) in
            pendingCreatorAcknowledgements

    override fun toString(): String =
        "SyncSession(familyId=$familyId, deviceId=$deviceId, role=$role, " +
            "pullCursor=$pullCursor, reauthRequired=$reauthRequired, credentials=<redacted>)"
}

interface SyncPreferences {
    val session: Flow<SyncSession>
    val verifiedEndpoint: Flow<TrustedEndpointProfile?>
    val pendingMemberLogin: Flow<PendingMemberLogin?>
        get() = kotlinx.coroutines.flow.flowOf(null)
    suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile)
    suspend fun forgetEndpoint()
    suspend fun saveEndpointConfig(config: FamilyEndpointConfig, clearSessionIfServerChanged: Boolean = true)
    suspend fun saveSession(session: SyncSession)
    /** Durably stores a claimed session but keeps it non-pushable until old receipts reset. */
    suspend fun saveSessionPendingReplicaReset(session: SyncSession, previous: SyncSession) {
        saveSession(session)
    }
    suspend fun pendingReplicaResetPrevious(): SyncSession? = null
    suspend fun completePendingReplicaReset() {}
    suspend fun updateCursor(cursor: Long, generation: String = "")
    suspend fun updatePullCheckpoint(
        cursor: Long,
        generation: String,
        familyName: String?,
    )
    suspend fun updateCreatorAcknowledgements(
        add: Set<CreatorAcknowledgementRef> = emptySet(),
        remove: Set<CreatorAcknowledgementRef> = emptySet(),
    )
    suspend fun markSuccess(atMillis: Long)
    suspend fun ensureDeviceId(): String
    suspend fun ensureCreateRequestId(): String
    /** Durable across retries; retired atomically with a committed session. */
    suspend fun ensureOwnerLoginRequestId(): String
    suspend fun savePendingMemberLogin(
        receipt: MemberLoginReceipt,
        displayName: String,
        deviceName: String,
    ): Unit = throw UnsupportedOperationException("Pending member login is not implemented")
    suspend fun pendingMemberSecret(): String = ""
    suspend fun clearPendingMemberLogin() = Unit
    suspend fun clearCreateRequestId()
    /** Wipes the endpoint, trust profile, credentials, and family session. */
    suspend fun clearAllLocalSyncConfig()
    /** Clears this Device's credentials while retaining endpoint, identity and local family data. */
    suspend fun clearDeviceCredentialsForReauth()
    /** Completes a same-version credential clear interrupted between durability domains. */
    suspend fun recoverPendingCredentialClear() {}
    /** Durable hand-off after the server confirms this Device is terminally removed. */
    suspend fun markPendingDeviceRemovalClear() {}
    suspend fun hasPendingDeviceRemovalClear(): Boolean = false
    suspend fun clearPendingDeviceRemovalClear() {}
    /** Durable hand-off after the server confirms this membership was hard-deleted. */
    suspend fun markPendingMembershipDeletionClear() {}
    suspend fun hasPendingMembershipDeletionClear(): Boolean = false
    suspend fun clearPendingMembershipDeletionClear() {}
    /** Durable hand-off after the server confirms the entire family was deleted. */
    suspend fun markPendingFamilyDeletionClear() {}
    suspend fun hasPendingFamilyDeletionClear(): Boolean = false
    suspend fun clearPendingFamilyDeletionClear() {}
}

@Singleton
class DataStoreSyncPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val secureTokenStore: SecureRefreshTokenStore,
) : SyncPreferences {
    private val processAccessToken = java.util.concurrent.atomic.AtomicReference("")
    private val processAccessExpiry = java.util.concurrent.atomic.AtomicLong(0)
    override val session: Flow<SyncSession> = dataStore.data.map { prefs ->
        mapSession(prefs)
    }
    override val verifiedEndpoint: Flow<TrustedEndpointProfile?> = dataStore.data.map { prefs ->
        val origin = prefs[Keys.VERIFIED_ENDPOINT_ORIGIN].orEmpty()
        val trustMode = prefs[Keys.VERIFIED_ENDPOINT_TRUST_MODE]
        val pin = prefs[Keys.VERIFIED_ENDPOINT_SPKI_SHA256].orEmpty()
        if (origin.isBlank()) return@map null
        runCatching {
            when (trustMode) {
                EndpointTrustMode.SystemPki.name -> TrustedEndpointProfile.systemPki(origin)
                EndpointTrustMode.TofuSpki.name -> TrustedEndpointProfile.tofuSpki(origin, pin)
                else -> null
            }
        }.getOrNull()
    }
    override val pendingMemberLogin: Flow<PendingMemberLogin?> = dataStore.data.map { prefs ->
        val requestId = prefs[Keys.PENDING_MEMBER_REQUEST_ID].orEmpty()
        if (requestId.isBlank() || secureTokenStore.getPendingMemberSecret().isBlank()) {
            return@map null
        }
        PendingMemberLogin(
            requestId = requestId,
            displayName = prefs[Keys.PENDING_MEMBER_DISPLAY_NAME].orEmpty(),
            deviceName = prefs[Keys.PENDING_MEMBER_DEVICE_NAME].orEmpty(),
            expiresAtEpochSeconds = prefs[Keys.PENDING_MEMBER_EXPIRES_AT] ?: 0L,
        )
    }

    override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile) {
        dataStore.edit { prefs ->
            prefs[Keys.VERIFIED_ENDPOINT_ORIGIN] = endpoint.origin
            prefs[Keys.VERIFIED_ENDPOINT_TRUST_MODE] = endpoint.trustMode.name
            val pin = endpoint.spkiSha256
            if (pin == null) {
                prefs.remove(Keys.VERIFIED_ENDPOINT_SPKI_SHA256)
            } else {
                prefs[Keys.VERIFIED_ENDPOINT_SPKI_SHA256] = pin
            }
        }
    }

    override suspend fun forgetEndpoint() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.VERIFIED_ENDPOINT_ORIGIN)
            prefs.remove(Keys.VERIFIED_ENDPOINT_TRUST_MODE)
            prefs.remove(Keys.VERIFIED_ENDPOINT_SPKI_SHA256)
        }
    }

    private fun mapSession(prefs: Preferences): SyncSession {
        val rawScheme = prefs[Keys.SERVER_SCHEME].orEmpty()
        val schemeIsValid = rawScheme.lowercase() == "https"
        val host = if (schemeIsValid) {
            prefs[Keys.SERVER_HOST].orEmpty()
        } else {
            ""
        }
        val port = prefs[Keys.SERVER_PORT]
            ?: DEFAULT_SERVER_PORT
        val scheme = if (schemeIsValid) rawScheme.lowercase() else DEFAULT_SERVER_SCHEME
        val credentialClearPending = prefs[Keys.PENDING_FAMILY_CREDENTIAL_CLEAR] == true
        val replicaResetPending = prefs[Keys.PENDING_REPLICA_RESET] == true
        return SyncSession(
            familyId = prefs[Keys.FAMILY_ID].orEmpty(),
            accessToken = if (credentialClearPending || replicaResetPending) {
                ""
            } else {
                processAccessToken.get()
            },
            refreshToken = if (credentialClearPending || replicaResetPending) {
                ""
            } else {
                secureTokenStore.getToken()
            },
            accessExpiresAtEpochSeconds =
                if (credentialClearPending || replicaResetPending) 0 else processAccessExpiry.get(),
            reauthRequired = prefs[Keys.REAUTH_REQUIRED] == true || replicaResetPending,
            deviceId = prefs[Keys.DEVICE_ID].orEmpty(),
            role = prefs[Keys.ROLE]?.let { runCatching { FamilyRole.valueOf(it) }.getOrNull() }
                ?: FamilyRole.None,
            pullCursor = prefs[Keys.PULL_CURSOR] ?: 0,
            pullGeneration = prefs[Keys.PULL_GENERATION].orEmpty(),
            lastSuccessAt = prefs[Keys.LAST_SUCCESS_AT],
            serverHost = host,
            serverPort = port,
            serverScheme = scheme,
            familyName = prefs[Keys.FAMILY_NAME]?.trim()?.takeIf { it.isNotEmpty() },
            membershipId = prefs[Keys.MEMBERSHIP_ID].orEmpty(),
            pendingCreatorAcknowledgements =
                decodeCreatorAcknowledgements(prefs[Keys.PENDING_CREATOR_ACKNOWLEDGEMENTS]),
        )
    }

    override suspend fun saveEndpointConfig(
        config: FamilyEndpointConfig,
        clearSessionIfServerChanged: Boolean,
    ) {
        val normalized = config.withNormalized()
        val previous = session.first()
        val newBase = normalized.baseUrl
        val serverChanged = previous.baseUrl.isNotBlank() &&
            previous.baseUrl != newBase &&
            newBase.isNotBlank()
        val shouldClearSession = clearSessionIfServerChanged && serverChanged
        dataStore.edit { prefs ->
            if (shouldClearSession) {
                clearFamilyValues(prefs)
                prefs[Keys.PENDING_FAMILY_CREDENTIAL_CLEAR] = true
            }
            if (normalized.host.isBlank()) {
                prefs.remove(Keys.SERVER_HOST)
                prefs.remove(Keys.SERVER_SCHEME)
            } else {
                prefs[Keys.SERVER_HOST] = normalized.host
                prefs[Keys.SERVER_PORT] = normalized.port
                prefs[Keys.SERVER_SCHEME] = normalized.scheme
            }
        }
        if (shouldClearSession) secureTokenStore.clearPendingMemberSecret()
        finishPendingFamilyCredentialClear()
    }

    override suspend fun saveSession(session: SyncSession) {
        persistSession(session, pendingReplicaResetPrevious = null)
    }

    override suspend fun saveSessionPendingReplicaReset(
        session: SyncSession,
        previous: SyncSession,
    ) {
        persistSession(session, pendingReplicaResetPrevious = previous)
    }

    private suspend fun persistSession(
        session: SyncSession,
        pendingReplicaResetPrevious: SyncSession?,
    ) {
        val config = session.endpointConfig.withNormalized()
        dataStore.edit { prefs ->
            // DataStore identity changes before the separate Keystore write, but
            // this durable marker suppresses both old and new credentials until
            // every durability domain agrees. A crash therefore resumes reauth,
            // never a mixed-family pushable session.
            prefs[Keys.REAUTH_REQUIRED] = true
            prefs[Keys.PENDING_FAMILY_CREDENTIAL_CLEAR] = true
            val previousFamilyId = prefs[Keys.FAMILY_ID].orEmpty()
            if (config.host.isNotBlank()) {
                prefs[Keys.SERVER_HOST] = config.host
                prefs[Keys.SERVER_PORT] = config.port
                prefs[Keys.SERVER_SCHEME] = config.scheme
            }
            prefs[Keys.FAMILY_ID] = session.familyId
            if (previousFamilyId != session.familyId) {
                prefs.remove(Keys.PENDING_CREATOR_ACKNOWLEDGEMENTS)
            }
            // The owner session and retirement of its idempotency key are one
            // durable commit. A separate post-commit edit can fail after the UI
            // already owns a valid joined session.
            prefs.remove(Keys.CREATE_REQUEST_ID)
            prefs.remove(Keys.OWNER_LOGIN_REQUEST_ID)
            clearPendingMemberValues(prefs)
            prefs[Keys.DEVICE_ID] = session.deviceId
            prefs[Keys.ROLE] = session.role.name
            prefs[Keys.PULL_CURSOR] = session.pullCursor
            if (session.pullGeneration.isBlank()) {
                prefs.remove(Keys.PULL_GENERATION)
            } else {
                prefs[Keys.PULL_GENERATION] = session.pullGeneration
            }
            if (session.lastSuccessAt == null) {
                prefs.remove(Keys.LAST_SUCCESS_AT)
            } else {
                prefs[Keys.LAST_SUCCESS_AT] = session.lastSuccessAt
            }
            val sharedName = session.familyName?.trim()?.takeIf { it.isNotEmpty() }
            if (sharedName == null) {
                prefs.remove(Keys.FAMILY_NAME)
            } else {
                prefs[Keys.FAMILY_NAME] = sharedName
            }
            val membershipId = session.membershipId.trim()
            if (membershipId.isEmpty()) {
                prefs.remove(Keys.MEMBERSHIP_ID)
            } else {
                prefs[Keys.MEMBERSHIP_ID] = membershipId
            }
            if (pendingReplicaResetPrevious == null) {
                clearPendingReplicaReset(prefs)
            } else {
                writePendingReplicaReset(prefs, pendingReplicaResetPrevious)
            }
        }
        secureTokenStore.setToken(session.refreshToken)
        processAccessToken.set(session.accessToken)
        processAccessExpiry.set(session.accessExpiresAtEpochSeconds)
        dataStore.edit { prefs ->
            prefs.remove(Keys.PENDING_FAMILY_CREDENTIAL_CLEAR)
            prefs.remove(Keys.REAUTH_REQUIRED)
        }
        secureTokenStore.clearPendingMemberSecret()
    }

    override suspend fun pendingReplicaResetPrevious(): SyncSession? {
        val prefs = dataStore.data.first()
        if (prefs[Keys.PENDING_REPLICA_RESET] != true) return null
        return SyncSession(
            familyId = prefs[Keys.PENDING_REPLICA_FAMILY_ID].orEmpty(),
            deviceId = prefs[Keys.PENDING_REPLICA_DEVICE_ID].orEmpty(),
            role = prefs[Keys.PENDING_REPLICA_ROLE]
                ?.let { runCatching { FamilyRole.valueOf(it) }.getOrNull() }
                ?: FamilyRole.None,
            serverHost = prefs[Keys.PENDING_REPLICA_SERVER_HOST].orEmpty(),
            serverPort = prefs[Keys.PENDING_REPLICA_SERVER_PORT] ?: DEFAULT_SERVER_PORT,
            serverScheme = prefs[Keys.PENDING_REPLICA_SERVER_SCHEME].orEmpty(),
            membershipId = prefs[Keys.PENDING_REPLICA_MEMBERSHIP_ID].orEmpty(),
            reauthRequired = true,
        )
    }

    override suspend fun completePendingReplicaReset() {
        dataStore.edit(::clearPendingReplicaReset)
    }

    override suspend fun updateCursor(cursor: Long, generation: String) {
        dataStore.edit {
            it[Keys.PULL_CURSOR] = cursor.coerceAtLeast(0)
            if (generation.isBlank()) {
                it.remove(Keys.PULL_GENERATION)
            } else {
                it[Keys.PULL_GENERATION] = generation
            }
        }
    }

    override suspend fun updatePullCheckpoint(
        cursor: Long,
        generation: String,
        familyName: String?,
    ) {
        val normalizedFamilyName = normalizeFamilyNameForWire(familyName)
        dataStore.edit {
            it[Keys.PULL_CURSOR] = cursor.coerceAtLeast(0)
            if (generation.isBlank()) {
                it.remove(Keys.PULL_GENERATION)
            } else {
                it[Keys.PULL_GENERATION] = generation
            }
            if (normalizedFamilyName == null) {
                it.remove(Keys.FAMILY_NAME)
            } else {
                it[Keys.FAMILY_NAME] = normalizedFamilyName
            }
        }
    }

    override suspend fun updateCreatorAcknowledgements(
        add: Set<CreatorAcknowledgementRef>,
        remove: Set<CreatorAcknowledgementRef>,
    ) {
        val normalizedAdd = normalizeCreatorAcknowledgements(add)
        val normalizedRemove = normalizeCreatorAcknowledgements(remove)
        if (normalizedAdd.isEmpty() && normalizedRemove.isEmpty()) return
        dataStore.edit { prefs ->
            val next = (
                decodeCreatorAcknowledgements(prefs[Keys.PENDING_CREATOR_ACKNOWLEDGEMENTS]) +
                    normalizedAdd
                ) - normalizedRemove
            val encoded = encodeCreatorAcknowledgements(next)
            if (encoded.isEmpty()) {
                prefs.remove(Keys.PENDING_CREATOR_ACKNOWLEDGEMENTS)
            } else {
                prefs[Keys.PENDING_CREATOR_ACKNOWLEDGEMENTS] = encoded
            }
        }
    }

    override suspend fun markSuccess(atMillis: Long) {
        dataStore.edit { it[Keys.LAST_SUCCESS_AT] = atMillis }
    }

    override suspend fun ensureDeviceId(): String {
        session.first().deviceId.takeIf { it.isNotBlank() }?.let { return it }
        val generated = UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            if (prefs[Keys.DEVICE_ID].isNullOrBlank()) prefs[Keys.DEVICE_ID] = generated
        }
        return session.first().deviceId
    }

    override suspend fun ensureCreateRequestId(): String {
        dataStore.data.first()[Keys.CREATE_REQUEST_ID]
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        val generated = UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            if (prefs[Keys.CREATE_REQUEST_ID].isNullOrBlank()) {
                prefs[Keys.CREATE_REQUEST_ID] = generated
            }
        }
        return requireNotNull(dataStore.data.first()[Keys.CREATE_REQUEST_ID])
    }

    override suspend fun ensureOwnerLoginRequestId(): String {
        dataStore.data.first()[Keys.OWNER_LOGIN_REQUEST_ID]
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        val generated = UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            if (prefs[Keys.OWNER_LOGIN_REQUEST_ID].isNullOrBlank()) {
                prefs[Keys.OWNER_LOGIN_REQUEST_ID] = generated
            }
        }
        return requireNotNull(dataStore.data.first()[Keys.OWNER_LOGIN_REQUEST_ID])
    }

    override suspend fun savePendingMemberLogin(
        receipt: MemberLoginReceipt,
        displayName: String,
        deviceName: String,
    ) {
        require(receipt.requestId.isNotBlank()) { "pending request id is required" }
        require(receipt.pendingSecret.isNotBlank()) { "pending member secret is required" }
        secureTokenStore.setPendingMemberSecret(receipt.pendingSecret)
        dataStore.edit { prefs ->
            prefs[Keys.PENDING_MEMBER_REQUEST_ID] = receipt.requestId
            prefs[Keys.PENDING_MEMBER_DISPLAY_NAME] = displayName
            prefs[Keys.PENDING_MEMBER_DEVICE_NAME] = deviceName
            prefs[Keys.PENDING_MEMBER_EXPIRES_AT] = receipt.expiresAtEpochSeconds
        }
    }

    override suspend fun pendingMemberSecret(): String = secureTokenStore.getPendingMemberSecret()

    override suspend fun clearPendingMemberLogin() {
        dataStore.edit(::clearPendingMemberValues)
        secureTokenStore.clearPendingMemberSecret()
    }

    override suspend fun clearCreateRequestId() {
        dataStore.edit { it.remove(Keys.CREATE_REQUEST_ID) }
    }

    override suspend fun clearAllLocalSyncConfig() {
        dataStore.edit { prefs ->
            clearFamilyValues(prefs)
            prefs[Keys.PENDING_FAMILY_CREDENTIAL_CLEAR] = true
            prefs.remove(Keys.SERVER_HOST)
            prefs.remove(Keys.SERVER_PORT)
            prefs.remove(Keys.SERVER_SCHEME)
            prefs.remove(Keys.VERIFIED_ENDPOINT_ORIGIN)
            prefs.remove(Keys.VERIFIED_ENDPOINT_TRUST_MODE)
            prefs.remove(Keys.VERIFIED_ENDPOINT_SPKI_SHA256)
        }
        finishPendingFamilyCredentialClear()
        secureTokenStore.clearPendingMemberSecret()
    }

    override suspend fun clearDeviceCredentialsForReauth() {
        dataStore.edit { prefs ->
            prefs[Keys.REAUTH_REQUIRED] = true
            prefs[Keys.PENDING_FAMILY_CREDENTIAL_CLEAR] = true
        }
        finishPendingFamilyCredentialClear()
    }

    override suspend fun recoverPendingCredentialClear() {
        finishPendingFamilyCredentialClear()
    }

    override suspend fun markPendingDeviceRemovalClear() {
        dataStore.edit {
            it[Keys.PENDING_DEVICE_REMOVAL_CLEAR] = true
            markCredentialsTerminal(it)
        }
    }

    override suspend fun hasPendingDeviceRemovalClear(): Boolean =
        dataStore.data.first()[Keys.PENDING_DEVICE_REMOVAL_CLEAR] == true

    override suspend fun clearPendingDeviceRemovalClear() {
        dataStore.edit { it.remove(Keys.PENDING_DEVICE_REMOVAL_CLEAR) }
    }

    override suspend fun markPendingMembershipDeletionClear() {
        dataStore.edit {
            it[Keys.PENDING_MEMBERSHIP_DELETION_CLEAR] = true
            markCredentialsTerminal(it)
        }
    }

    override suspend fun hasPendingMembershipDeletionClear(): Boolean =
        dataStore.data.first()[Keys.PENDING_MEMBERSHIP_DELETION_CLEAR] == true

    override suspend fun clearPendingMembershipDeletionClear() {
        dataStore.edit { it.remove(Keys.PENDING_MEMBERSHIP_DELETION_CLEAR) }
    }

    override suspend fun markPendingFamilyDeletionClear() {
        dataStore.edit {
            it[Keys.PENDING_FAMILY_DELETION_CLEAR] = true
            markCredentialsTerminal(it)
        }
    }

    override suspend fun hasPendingFamilyDeletionClear(): Boolean =
        dataStore.data.first()[Keys.PENDING_FAMILY_DELETION_CLEAR] == true

    override suspend fun clearPendingFamilyDeletionClear() {
        dataStore.edit { it.remove(Keys.PENDING_FAMILY_DELETION_CLEAR) }
    }

    private suspend fun finishPendingFamilyCredentialClear() {
        if (dataStore.data.first()[Keys.PENDING_FAMILY_CREDENTIAL_CLEAR] != true) return
        // EncryptedSharedPreferences is a separate durability domain. Keep the
        // DataStore marker (and suppress token projection) until its synchronous
        // clear succeeds, so process death can only expose the terminal unjoined
        // state and a later foreground operation can finish idempotently.
        secureTokenStore.clearToken()
        processAccessToken.set("")
        processAccessExpiry.set(0)
        dataStore.edit { prefs ->
            prefs.remove(Keys.PENDING_FAMILY_CREDENTIAL_CLEAR)
        }
    }

    private fun markCredentialsTerminal(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
    ) {
        // Commit the terminal marker and credential gate together before domain
        // clearing starts. A racing sync therefore observes an unjoined reauth
        // projection and cannot publish with credentials from the retired identity.
        prefs[Keys.REAUTH_REQUIRED] = true
        prefs[Keys.PENDING_FAMILY_CREDENTIAL_CLEAR] = true
    }

    private fun writePendingReplicaReset(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        previous: SyncSession,
    ) {
        val config = previous.endpointConfig.withNormalized()
        prefs[Keys.PENDING_REPLICA_RESET] = true
        prefs[Keys.PENDING_REPLICA_FAMILY_ID] = previous.familyId
        prefs[Keys.PENDING_REPLICA_DEVICE_ID] = previous.deviceId
        prefs[Keys.PENDING_REPLICA_ROLE] = previous.role.name
        prefs[Keys.PENDING_REPLICA_SERVER_HOST] = config.host
        prefs[Keys.PENDING_REPLICA_SERVER_PORT] = config.port
        prefs[Keys.PENDING_REPLICA_SERVER_SCHEME] = config.scheme
        prefs[Keys.PENDING_REPLICA_MEMBERSHIP_ID] = previous.membershipId
    }

    private fun clearPendingReplicaReset(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
    ) {
        prefs.remove(Keys.PENDING_REPLICA_RESET)
        prefs.remove(Keys.PENDING_REPLICA_FAMILY_ID)
        prefs.remove(Keys.PENDING_REPLICA_DEVICE_ID)
        prefs.remove(Keys.PENDING_REPLICA_ROLE)
        prefs.remove(Keys.PENDING_REPLICA_SERVER_HOST)
        prefs.remove(Keys.PENDING_REPLICA_SERVER_PORT)
        prefs.remove(Keys.PENDING_REPLICA_SERVER_SCHEME)
        prefs.remove(Keys.PENDING_REPLICA_MEMBERSHIP_ID)
    }

    private fun clearFamilyValues(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        prefs.remove(Keys.FAMILY_ID)
        prefs.remove(Keys.ROLE)
        prefs.remove(Keys.PULL_CURSOR)
        prefs.remove(Keys.PULL_GENERATION)
        prefs.remove(Keys.LAST_SUCCESS_AT)
        prefs.remove(Keys.CREATE_REQUEST_ID)
        prefs.remove(Keys.OWNER_LOGIN_REQUEST_ID)
        clearPendingMemberValues(prefs)
        prefs.remove(Keys.FAMILY_NAME)
        prefs.remove(Keys.MEMBERSHIP_ID)
        prefs.remove(Keys.PENDING_CREATOR_ACKNOWLEDGEMENTS)
        prefs.remove(Keys.REAUTH_REQUIRED)
        clearPendingReplicaReset(prefs)
    }

    private fun clearPendingMemberValues(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
    ) {
        prefs.remove(Keys.PENDING_MEMBER_REQUEST_ID)
        prefs.remove(Keys.PENDING_MEMBER_DISPLAY_NAME)
        prefs.remove(Keys.PENDING_MEMBER_DEVICE_NAME)
        prefs.remove(Keys.PENDING_MEMBER_EXPIRES_AT)
    }

    private fun encodeCreatorAcknowledgements(
        refs: Set<CreatorAcknowledgementRef>,
    ): String = normalizeCreatorAcknowledgements(refs)
        .sortedWith(
            compareBy(
                CreatorAcknowledgementRef::entityType,
                CreatorAcknowledgementRef::clientUuid,
            ),
        )
        .joinToString(CREATOR_ACK_ENTRY_SEPARATOR.toString()) { ref ->
            "${ref.entityType}$CREATOR_ACK_FIELD_SEPARATOR${ref.clientUuid}"
        }

    private fun decodeCreatorAcknowledgements(raw: String?): Set<CreatorAcknowledgementRef> =
        raw.orEmpty()
            .split(CREATOR_ACK_ENTRY_SEPARATOR)
            .mapNotNull { encoded ->
                val parts = encoded.split(CREATOR_ACK_FIELD_SEPARATOR, limit = 2)
                if (parts.size != 2) return@mapNotNull null
                normalizeCreatorAcknowledgement(
                    CreatorAcknowledgementRef(parts[0], parts[1]),
                )
            }
            .toSet()

    private fun normalizeCreatorAcknowledgements(
        refs: Set<CreatorAcknowledgementRef>,
    ): Set<CreatorAcknowledgementRef> = refs.mapNotNull(::normalizeCreatorAcknowledgement).toSet()

    private fun normalizeCreatorAcknowledgement(
        ref: CreatorAcknowledgementRef,
    ): CreatorAcknowledgementRef? {
        val entityType = ref.entityType.trim()
        val clientUuid = ref.clientUuid.trim()
        if (entityType !in CREATOR_ACK_ENTITY_TYPES || clientUuid.isEmpty()) return null
        if (
            CREATOR_ACK_ENTRY_SEPARATOR in clientUuid ||
            CREATOR_ACK_FIELD_SEPARATOR in clientUuid
        ) {
            return null
        }
        return CreatorAcknowledgementRef(entityType, clientUuid)
    }

    private object Keys {
        val SERVER_HOST = stringPreferencesKey("sync_server_host")
        val SERVER_PORT = intPreferencesKey("sync_server_port")
        val SERVER_SCHEME = stringPreferencesKey("sync_server_scheme")
        val FAMILY_ID = stringPreferencesKey("sync_family_id")
        val DEVICE_ID = stringPreferencesKey("sync_device_id")
        val ROLE = stringPreferencesKey("sync_family_role")
        val PULL_CURSOR = longPreferencesKey("sync_pull_cursor")
        val PULL_GENERATION = stringPreferencesKey("sync_pull_generation")
        val LAST_SUCCESS_AT = longPreferencesKey("sync_last_success_at")
        val CREATE_REQUEST_ID = stringPreferencesKey("sync_create_request_id")
        val OWNER_LOGIN_REQUEST_ID = stringPreferencesKey("sync_owner_login_request_id")
        val PENDING_MEMBER_REQUEST_ID = stringPreferencesKey("sync_pending_member_request_id")
        val PENDING_MEMBER_DISPLAY_NAME = stringPreferencesKey("sync_pending_member_display_name")
        val PENDING_MEMBER_DEVICE_NAME = stringPreferencesKey("sync_pending_member_device_name")
        val PENDING_MEMBER_EXPIRES_AT = longPreferencesKey("sync_pending_member_expires_at")
        val FAMILY_NAME = stringPreferencesKey("sync_family_name")
        val MEMBERSHIP_ID = stringPreferencesKey("sync_membership_id")
        val PENDING_CREATOR_ACKNOWLEDGEMENTS =
            stringPreferencesKey("sync_pending_creator_acknowledgements")
        val PENDING_FAMILY_CREDENTIAL_CLEAR =
            booleanPreferencesKey("sync_pending_family_credential_clear")
        val PENDING_REPLICA_RESET = booleanPreferencesKey("sync_pending_replica_reset")
        val PENDING_REPLICA_FAMILY_ID = stringPreferencesKey("sync_pending_replica_family_id")
        val PENDING_REPLICA_DEVICE_ID = stringPreferencesKey("sync_pending_replica_device_id")
        val PENDING_REPLICA_ROLE = stringPreferencesKey("sync_pending_replica_role")
        val PENDING_REPLICA_SERVER_HOST =
            stringPreferencesKey("sync_pending_replica_server_host")
        val PENDING_REPLICA_SERVER_PORT = intPreferencesKey("sync_pending_replica_server_port")
        val PENDING_REPLICA_SERVER_SCHEME =
            stringPreferencesKey("sync_pending_replica_server_scheme")
        val PENDING_REPLICA_MEMBERSHIP_ID =
            stringPreferencesKey("sync_pending_replica_membership_id")
        val PENDING_DEVICE_REMOVAL_CLEAR =
            booleanPreferencesKey("sync_pending_device_removal_clear")
        val PENDING_MEMBERSHIP_DELETION_CLEAR =
            booleanPreferencesKey("sync_pending_membership_deletion_clear")
        val PENDING_FAMILY_DELETION_CLEAR =
            booleanPreferencesKey("sync_pending_family_deletion_clear")
        val REAUTH_REQUIRED = booleanPreferencesKey("sync_reauth_required")
        val VERIFIED_ENDPOINT_ORIGIN = stringPreferencesKey("sync_verified_endpoint_origin")
        val VERIFIED_ENDPOINT_TRUST_MODE =
            stringPreferencesKey("sync_verified_endpoint_trust_mode")
        val VERIFIED_ENDPOINT_SPKI_SHA256 =
            stringPreferencesKey("sync_verified_endpoint_spki_sha256")
    }

    private companion object {
        const val CREATOR_ACK_ENTRY_SEPARATOR = '\u001e'
        const val CREATOR_ACK_FIELD_SEPARATOR = '\u001f'
        val CREATOR_ACK_ENTITY_TYPES = setOf("care_plan", "custom_item")
    }
}
