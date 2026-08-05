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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.backend.MemberLoginReceipt

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

data class DisasterRestoreCheckpoint(
    val batchId: String,
    val endpoint: TrustedEndpointProfile,
    val familyId: String,
    val startRequestId: String,
    val manifestRequestId: String,
    val commitRequestId: String,
    val expiresAtEpochSeconds: Long,
    val status: String,
    val entityVersions: List<DisasterRestoreEntityVersion>,
)

data class DisasterRestoreEntityVersion(
    val type: String,
    val clientUuid: String,
    val updatedAt: Long,
    val restored: Boolean,
)

data class DisasterRestoreRequestIds(
    val start: String,
    val manifest: String,
    val commit: String,
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
    val familyMemberDirectory: Flow<List<FamilyMember>>
        get() = kotlinx.coroutines.flow.flowOf(emptyList())
    val lastServerHealthyAt: Flow<Long?>
        get() = kotlinx.coroutines.flow.flowOf(null)
    val pendingMemberLogin: Flow<PendingMemberLogin?>
        get() = kotlinx.coroutines.flow.flowOf(null)
    val disasterRestoreCheckpoint: Flow<DisasterRestoreCheckpoint?>
        get() = kotlinx.coroutines.flow.flowOf(null)
    suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile)
    suspend fun forgetEndpoint()
    suspend fun saveFamilyMemberDirectory(members: List<FamilyMember>) = Unit
    suspend fun clearFamilyMemberDirectory() = Unit
    suspend fun saveLastServerHealthyAt(atMillis: Long) = Unit
    suspend fun saveEndpointConfig(config: FamilyEndpointConfig, clearSessionIfServerChanged: Boolean = true)
    suspend fun saveSession(session: SyncSession)
    /**
     * Commits rotated credentials for the same Device identity without entering the
     * cross-identity reauth gate. The durable refresh request id is retired last.
     */
    suspend fun saveRefreshedSession(session: SyncSession) {
        saveSession(session)
    }
    /**
     * Candidate reconnect commit: endpoint trust, session identity, and retirement of the old
     * member directory share one durable commit.
     */
    suspend fun saveReconnectedSession(
        session: SyncSession,
        endpoint: TrustedEndpointProfile,
    )
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
    /** Retires a server-conflicted login mode/name key so the next explicit retry can proceed. */
    suspend fun clearOwnerLoginRequestId()
    /** Durable refresh-rotation nonce; retired only after rotated credentials are committed. */
    suspend fun ensureRefreshRequestId(): String
    suspend fun savePendingMemberLogin(
        receipt: MemberLoginReceipt,
        displayName: String,
        deviceName: String,
    ): Unit = throw UnsupportedOperationException("Pending member login is not implemented")
    suspend fun pendingMemberSecret(): String = ""
    suspend fun clearPendingMemberLogin() = Unit
    suspend fun saveDisasterRestoreCheckpoint(
        checkpoint: DisasterRestoreCheckpoint,
        recoveryToken: String,
    ) = Unit
    suspend fun ensureDisasterRestoreRequestIds(): DisasterRestoreRequestIds =
        DisasterRestoreRequestIds(
            start = UUID.randomUUID().toString(),
            manifest = UUID.randomUUID().toString(),
            commit = UUID.randomUUID().toString(),
        )
    suspend fun disasterRestoreToken(): String = ""
    suspend fun clearDisasterRestoreCheckpoint() = Unit
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
    override val familyMemberDirectory: Flow<List<FamilyMember>> = dataStore.data.map { prefs ->
        decodeFamilyMemberDirectory(prefs[Keys.FAMILY_MEMBER_DIRECTORY])
    }
    override val lastServerHealthyAt: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_SERVER_HEALTHY_AT]
    }
    override val pendingMemberLogin: Flow<PendingMemberLogin?> = dataStore.data.map { prefs ->
        val requestId = prefs[Keys.PENDING_MEMBER_REQUEST_ID].orEmpty()
        if (requestId.isBlank() || secureStoreIo {
                getPendingMemberSecret()
            }.isBlank()
        ) {
            return@map null
        }
        PendingMemberLogin(
            requestId = requestId,
            displayName = prefs[Keys.PENDING_MEMBER_DISPLAY_NAME].orEmpty(),
            deviceName = prefs[Keys.PENDING_MEMBER_DEVICE_NAME].orEmpty(),
            expiresAtEpochSeconds = prefs[Keys.PENDING_MEMBER_EXPIRES_AT] ?: 0L,
        )
    }
    override val disasterRestoreCheckpoint: Flow<DisasterRestoreCheckpoint?> =
        dataStore.data.map(::mapDisasterRestoreCheckpoint)

    override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile) {
        var clearCredentials = false
        dataStore.edit { prefs ->
            val previousOrigin = prefs[Keys.VERIFIED_ENDPOINT_ORIGIN].orEmpty()
            val previousMode = prefs[Keys.VERIFIED_ENDPOINT_TRUST_MODE]
            val previousPin = prefs[Keys.VERIFIED_ENDPOINT_SPKI_SHA256]
            val trustUnchanged = previousOrigin == endpoint.origin &&
                previousMode == endpoint.trustMode.name &&
                previousPin == endpoint.spkiSha256
            clearCredentials = previousOrigin.isNotBlank() &&
                !trustUnchanged &&
                storedFamilySessionMatchesOrigin(prefs, previousOrigin)
            if (clearCredentials) markCredentialsTerminal(prefs)
            prefs[Keys.VERIFIED_ENDPOINT_ORIGIN] = endpoint.origin
            prefs[Keys.VERIFIED_ENDPOINT_TRUST_MODE] = endpoint.trustMode.name
            val pin = endpoint.spkiSha256
            if (pin == null) {
                prefs.remove(Keys.VERIFIED_ENDPOINT_SPKI_SHA256)
            } else {
                prefs[Keys.VERIFIED_ENDPOINT_SPKI_SHA256] = pin
            }
        }
        if (clearCredentials) finishPendingFamilyCredentialClear()
    }

    override suspend fun forgetEndpoint() {
        var clearCredentials = false
        dataStore.edit { prefs ->
            val trustedOrigin = prefs[Keys.VERIFIED_ENDPOINT_ORIGIN].orEmpty()
            clearCredentials = trustedOrigin.isNotBlank() &&
                storedFamilySessionMatchesOrigin(prefs, trustedOrigin)
            if (clearCredentials) markCredentialsTerminal(prefs)
            prefs.remove(Keys.VERIFIED_ENDPOINT_ORIGIN)
            prefs.remove(Keys.VERIFIED_ENDPOINT_TRUST_MODE)
            prefs.remove(Keys.VERIFIED_ENDPOINT_SPKI_SHA256)
        }
        if (clearCredentials) finishPendingFamilyCredentialClear()
    }

    override suspend fun saveFamilyMemberDirectory(members: List<FamilyMember>) {
        val encoded = encodeFamilyMemberDirectory(members)
        dataStore.edit { prefs ->
            if (encoded == "[]") {
                prefs.remove(Keys.FAMILY_MEMBER_DIRECTORY)
            } else {
                prefs[Keys.FAMILY_MEMBER_DIRECTORY] = encoded
            }
        }
    }

    override suspend fun clearFamilyMemberDirectory() {
        dataStore.edit { it.remove(Keys.FAMILY_MEMBER_DIRECTORY) }
    }

    override suspend fun saveLastServerHealthyAt(atMillis: Long) {
        require(atMillis >= 0) { "server healthy time must be non-negative" }
        dataStore.edit { it[Keys.LAST_SERVER_HEALTHY_AT] = atMillis }
    }

    private suspend fun mapSession(prefs: Preferences): SyncSession {
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
                secureStoreIo { getToken() }
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
        if (shouldClearSession) {
            secureStoreIo {
                clearPendingMemberSecret()
                clearDisasterRestoreToken()
            }
        }
        finishPendingFamilyCredentialClear()
    }

    override suspend fun saveSession(session: SyncSession) {
        persistSession(session, pendingReplicaResetPrevious = null, reconnectedEndpoint = null)
    }

    override suspend fun saveRefreshedSession(session: SyncSession) {
        val current = this.session.first()
        require(
            current.familyId == session.familyId &&
                current.membershipId == session.membershipId &&
                current.deviceId == session.deviceId &&
                current.role == session.role &&
                current.baseUrl == session.baseUrl &&
                current.familyId.isNotBlank() &&
                session.refreshToken.isNotBlank(),
        ) { "刷新凭据与当前家庭身份不一致" }

        // This is a same-identity rotation, not an account switch. Write the
        // Keystore token first: a process death leaves either the old token or
        // the new token, while the durable request id remains available to replay
        // the one deterministic server rotation. Retire that id only afterward.
        secureStoreIo { setToken(session.refreshToken) }
        processAccessToken.set(session.accessToken)
        processAccessExpiry.set(session.accessExpiresAtEpochSeconds)
        dataStore.edit { prefs ->
            check(
                prefs[Keys.FAMILY_ID].orEmpty() == session.familyId &&
                    prefs[Keys.MEMBERSHIP_ID].orEmpty() == session.membershipId &&
                    prefs[Keys.DEVICE_ID].orEmpty() == session.deviceId &&
                    prefs[Keys.ROLE].orEmpty() == session.role.name,
            ) { "刷新期间家庭身份已变化" }
            val sharedName = session.familyName?.trim()?.takeIf(String::isNotEmpty)
            if (sharedName == null) {
                prefs.remove(Keys.FAMILY_NAME)
            } else {
                prefs[Keys.FAMILY_NAME] = sharedName
            }
            prefs.remove(Keys.REFRESH_REQUEST_ID)
        }
    }

    override suspend fun saveReconnectedSession(
        session: SyncSession,
        endpoint: TrustedEndpointProfile,
    ) {
        persistSession(
            session,
            pendingReplicaResetPrevious = null,
            reconnectedEndpoint = endpoint,
        )
    }

    override suspend fun saveSessionPendingReplicaReset(
        session: SyncSession,
        previous: SyncSession,
    ) {
        persistSession(
            session,
            pendingReplicaResetPrevious = previous,
            reconnectedEndpoint = null,
        )
    }

    private suspend fun persistSession(
        session: SyncSession,
        pendingReplicaResetPrevious: SyncSession?,
        reconnectedEndpoint: TrustedEndpointProfile?,
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
            reconnectedEndpoint?.let { endpoint ->
                prefs[Keys.VERIFIED_ENDPOINT_ORIGIN] = endpoint.origin
                prefs[Keys.VERIFIED_ENDPOINT_TRUST_MODE] = endpoint.trustMode.name
                endpoint.spkiSha256?.let { pin ->
                    prefs[Keys.VERIFIED_ENDPOINT_SPKI_SHA256] = pin
                } ?: prefs.remove(Keys.VERIFIED_ENDPOINT_SPKI_SHA256)
                // Membership ids belong to the server identity, even when the restored
                // family_id is intentionally preserved. Retire the old display projection in
                // the same DataStore commit as endpoint/session activation.
                prefs.remove(Keys.FAMILY_MEMBER_DIRECTORY)
            }
            prefs[Keys.FAMILY_ID] = session.familyId
            if (previousFamilyId != session.familyId) {
                prefs.remove(Keys.PENDING_CREATOR_ACKNOWLEDGEMENTS)
                prefs.remove(Keys.FAMILY_MEMBER_DIRECTORY)
            }
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
        secureStoreIo { setToken(session.refreshToken) }
        processAccessToken.set(session.accessToken)
        processAccessExpiry.set(session.accessExpiresAtEpochSeconds)
        dataStore.edit { prefs ->
            // Claim/login/refresh replay capabilities retire in the same durable
            // activation commit that removes the reauth gate. If the separate
            // secure-token write fails, the original server operation can still
            // be replayed after process death without minting another identity.
            prefs.remove(Keys.CREATE_REQUEST_ID)
            prefs.remove(Keys.OWNER_LOGIN_REQUEST_ID)
            prefs.remove(Keys.REFRESH_REQUEST_ID)
            clearPendingMemberValues(prefs)
            prefs.remove(Keys.PENDING_FAMILY_CREDENTIAL_CLEAR)
            prefs.remove(Keys.REAUTH_REQUIRED)
        }
        secureStoreIo { clearPendingMemberSecret() }
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

    override suspend fun clearOwnerLoginRequestId() {
        dataStore.edit { it.remove(Keys.OWNER_LOGIN_REQUEST_ID) }
    }

    override suspend fun ensureRefreshRequestId(): String {
        dataStore.data.first()[Keys.REFRESH_REQUEST_ID]
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        val generated = UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            if (prefs[Keys.REFRESH_REQUEST_ID].isNullOrBlank()) {
                prefs[Keys.REFRESH_REQUEST_ID] = generated
            }
        }
        return requireNotNull(dataStore.data.first()[Keys.REFRESH_REQUEST_ID])
    }

    override suspend fun savePendingMemberLogin(
        receipt: MemberLoginReceipt,
        displayName: String,
        deviceName: String,
    ) {
        require(receipt.requestId.isNotBlank()) { "pending request id is required" }
        require(receipt.pendingSecret.isNotBlank()) { "pending member secret is required" }
        secureStoreIo { setPendingMemberSecret(receipt.pendingSecret) }
        dataStore.edit { prefs ->
            prefs[Keys.PENDING_MEMBER_REQUEST_ID] = receipt.requestId
            prefs[Keys.PENDING_MEMBER_DISPLAY_NAME] = displayName
            prefs[Keys.PENDING_MEMBER_DEVICE_NAME] = deviceName
            prefs[Keys.PENDING_MEMBER_EXPIRES_AT] = receipt.expiresAtEpochSeconds
        }
    }

    override suspend fun pendingMemberSecret(): String = secureStoreIo {
        getPendingMemberSecret()
    }

    override suspend fun clearPendingMemberLogin() {
        dataStore.edit(::clearPendingMemberValues)
        try {
            secureStoreIo { clearPendingMemberSecret() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // DataStore metadata is the durable pending-slot authority. Once it is gone, stale
            // encrypted residue is unreachable and the next request safely overwrites it.
        }
    }

    override suspend fun saveDisasterRestoreCheckpoint(
        checkpoint: DisasterRestoreCheckpoint,
        recoveryToken: String,
    ) {
        require(recoveryToken.isNotBlank()) { "disaster restore token is required" }
        secureStoreIo { setDisasterRestoreToken(recoveryToken) }
        dataStore.edit { prefs ->
            prefs[Keys.RESTORE_BATCH_ID] = checkpoint.batchId
            prefs[Keys.RESTORE_ENDPOINT_ORIGIN] = checkpoint.endpoint.origin
            prefs[Keys.RESTORE_ENDPOINT_TRUST_MODE] = checkpoint.endpoint.trustMode.name
            checkpoint.endpoint.spkiSha256?.let { pin ->
                prefs[Keys.RESTORE_ENDPOINT_SPKI_SHA256] = pin
            } ?: prefs.remove(Keys.RESTORE_ENDPOINT_SPKI_SHA256)
            prefs[Keys.RESTORE_FAMILY_ID] = checkpoint.familyId
            prefs[Keys.RESTORE_START_REQUEST_ID] = checkpoint.startRequestId
            prefs[Keys.RESTORE_MANIFEST_REQUEST_ID] = checkpoint.manifestRequestId
            prefs[Keys.RESTORE_COMMIT_REQUEST_ID] = checkpoint.commitRequestId
            prefs[Keys.RESTORE_EXPIRES_AT] = checkpoint.expiresAtEpochSeconds
            prefs[Keys.RESTORE_STATUS] = checkpoint.status
            prefs[Keys.RESTORE_ENTITY_VERSIONS] = encodeDisasterRestoreEntityVersions(
                checkpoint.entityVersions,
            )
        }
    }

    override suspend fun ensureDisasterRestoreRequestIds(): DisasterRestoreRequestIds {
        val generated = DisasterRestoreRequestIds(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
        )
        dataStore.edit { prefs ->
            if (prefs[Keys.RESTORE_START_REQUEST_ID].isNullOrBlank()) {
                prefs[Keys.RESTORE_START_REQUEST_ID] = generated.start
            }
            if (prefs[Keys.RESTORE_MANIFEST_REQUEST_ID].isNullOrBlank()) {
                prefs[Keys.RESTORE_MANIFEST_REQUEST_ID] = generated.manifest
            }
            if (prefs[Keys.RESTORE_COMMIT_REQUEST_ID].isNullOrBlank()) {
                prefs[Keys.RESTORE_COMMIT_REQUEST_ID] = generated.commit
            }
        }
        val prefs = dataStore.data.first()
        return DisasterRestoreRequestIds(
            start = requireNotNull(prefs[Keys.RESTORE_START_REQUEST_ID]),
            manifest = requireNotNull(prefs[Keys.RESTORE_MANIFEST_REQUEST_ID]),
            commit = requireNotNull(prefs[Keys.RESTORE_COMMIT_REQUEST_ID]),
        )
    }

    override suspend fun disasterRestoreToken(): String =
        secureStoreIo { getDisasterRestoreToken() }

    override suspend fun clearDisasterRestoreCheckpoint() {
        dataStore.edit(::clearDisasterRestoreValues)
        secureStoreIo { clearDisasterRestoreToken() }
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
            prefs.remove(Keys.LAST_SERVER_HEALTHY_AT)
        }
        finishPendingFamilyCredentialClear()
        secureStoreIo { clearPendingMemberSecret() }
        clearDisasterRestoreCheckpoint()
    }

    override suspend fun clearDeviceCredentialsForReauth() {
        dataStore.edit { prefs ->
            markCredentialsTerminal(prefs)
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
        secureStoreIo { clearToken() }
        processAccessToken.set("")
        processAccessExpiry.set(0)
        dataStore.edit { prefs ->
            prefs.remove(Keys.PENDING_FAMILY_CREDENTIAL_CLEAR)
        }
    }

    /**
     * Android Keystore and EncryptedSharedPreferences expose synchronous APIs. Keep their lazy
     * initialization and commit/get work off UI callers while DataStore remains the durable
     * ordering authority around each separate credential domain.
     */
    private suspend fun <T> secureStoreIo(block: SecureRefreshTokenStore.() -> T): T =
        withContext(Dispatchers.IO) { secureTokenStore.block() }

    private fun markCredentialsTerminal(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
    ) {
        // Commit the terminal marker and credential gate together before domain
        // clearing starts. A racing sync therefore observes an unjoined reauth
        // projection and cannot publish with credentials from the retired identity.
        prefs[Keys.REAUTH_REQUIRED] = true
        prefs[Keys.PENDING_FAMILY_CREDENTIAL_CLEAR] = true
        prefs.remove(Keys.REFRESH_REQUEST_ID)
    }

    private fun storedFamilySessionMatchesOrigin(
        prefs: Preferences,
        origin: String,
    ): Boolean {
        if (prefs[Keys.FAMILY_ID].isNullOrBlank()) return false
        val scheme = prefs[Keys.SERVER_SCHEME].orEmpty().lowercase()
        val host = prefs[Keys.SERVER_HOST].orEmpty()
        if (scheme != "https" || host.isBlank()) return false
        val config = FamilyEndpointConfig(
            host = host,
            port = prefs[Keys.SERVER_PORT] ?: DEFAULT_SERVER_PORT,
            scheme = scheme,
        ).withNormalized()
        return config.baseUrl == origin
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
        prefs.remove(Keys.REFRESH_REQUEST_ID)
        clearPendingMemberValues(prefs)
        prefs.remove(Keys.FAMILY_NAME)
        prefs.remove(Keys.MEMBERSHIP_ID)
        prefs.remove(Keys.PENDING_CREATOR_ACKNOWLEDGEMENTS)
        prefs.remove(Keys.FAMILY_MEMBER_DIRECTORY)
        prefs.remove(Keys.REAUTH_REQUIRED)
        clearPendingReplicaReset(prefs)
        clearDisasterRestoreValues(prefs)
    }

    private fun mapDisasterRestoreCheckpoint(prefs: Preferences): DisasterRestoreCheckpoint? {
        val batchId = prefs[Keys.RESTORE_BATCH_ID].orEmpty()
        val origin = prefs[Keys.RESTORE_ENDPOINT_ORIGIN].orEmpty()
        if (batchId.isBlank() || origin.isBlank()) return null
        val endpoint = runCatching {
            when (prefs[Keys.RESTORE_ENDPOINT_TRUST_MODE]) {
                EndpointTrustMode.SystemPki.name -> TrustedEndpointProfile.systemPki(origin)
                EndpointTrustMode.TofuSpki.name -> TrustedEndpointProfile.tofuSpki(
                    origin,
                    prefs[Keys.RESTORE_ENDPOINT_SPKI_SHA256].orEmpty(),
                )
                else -> return null
            }
        }.getOrNull() ?: return null
        return DisasterRestoreCheckpoint(
            batchId = batchId,
            endpoint = endpoint,
            familyId = prefs[Keys.RESTORE_FAMILY_ID].orEmpty(),
            startRequestId = prefs[Keys.RESTORE_START_REQUEST_ID].orEmpty(),
            manifestRequestId = prefs[Keys.RESTORE_MANIFEST_REQUEST_ID].orEmpty(),
            commitRequestId = prefs[Keys.RESTORE_COMMIT_REQUEST_ID].orEmpty(),
            expiresAtEpochSeconds = prefs[Keys.RESTORE_EXPIRES_AT] ?: 0L,
            status = prefs[Keys.RESTORE_STATUS].orEmpty(),
            entityVersions = decodeDisasterRestoreEntityVersions(
                prefs[Keys.RESTORE_ENTITY_VERSIONS],
            ),
        )
    }

    private fun encodeDisasterRestoreEntityVersions(
        versions: List<DisasterRestoreEntityVersion>,
    ): String = buildJsonArray {
        versions.forEach { version ->
            add(buildJsonObject {
                put("type", version.type)
                put("client_uuid", version.clientUuid)
                put("updated_at", version.updatedAt)
                put("restored", version.restored)
            })
        }
    }.toString()

    private fun decodeDisasterRestoreEntityVersions(
        raw: String?,
    ): List<DisasterRestoreEntityVersion> = runCatching {
        Json.parseToJsonElement(raw.orEmpty()).jsonArray.map { element ->
            val value = element.jsonObject
            DisasterRestoreEntityVersion(
                type = value.getValue("type").jsonPrimitive.content,
                clientUuid = value.getValue("client_uuid").jsonPrimitive.content,
                updatedAt = value.getValue("updated_at").jsonPrimitive.long,
                restored = value.getValue("restored").jsonPrimitive.boolean,
            )
        }
    }.getOrDefault(emptyList())

    private fun clearDisasterRestoreValues(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
    ) {
        prefs.remove(Keys.RESTORE_BATCH_ID)
        prefs.remove(Keys.RESTORE_ENDPOINT_ORIGIN)
        prefs.remove(Keys.RESTORE_ENDPOINT_TRUST_MODE)
        prefs.remove(Keys.RESTORE_ENDPOINT_SPKI_SHA256)
        prefs.remove(Keys.RESTORE_FAMILY_ID)
        prefs.remove(Keys.RESTORE_START_REQUEST_ID)
        prefs.remove(Keys.RESTORE_MANIFEST_REQUEST_ID)
        prefs.remove(Keys.RESTORE_COMMIT_REQUEST_ID)
        prefs.remove(Keys.RESTORE_EXPIRES_AT)
        prefs.remove(Keys.RESTORE_STATUS)
        prefs.remove(Keys.RESTORE_ENTITY_VERSIONS)
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

    private fun encodeFamilyMemberDirectory(members: List<FamilyMember>): String =
        buildJsonArray {
            members.asSequence()
                .mapNotNull(::normalizeDirectoryMember)
                .distinctBy(FamilyMember::membershipId)
                .forEach { member ->
                    add(
                        buildJsonObject {
                            put("membership_id", member.membershipId)
                            put("display_name", member.displayName)
                            put("role", member.role.name)
                            put("is_self", member.isSelf)
                            member.lastSyncAtEpochSeconds?.let { put("last_sync_at", it) }
                        },
                    )
                }
        }.toString()

    private fun decodeFamilyMemberDirectory(raw: String?): List<FamilyMember> = runCatching {
        Json.parseToJsonElement(raw.orEmpty().ifBlank { "[]" }).jsonArray.mapNotNull { element ->
            val value = element.jsonObject
            val membershipId = value["membership_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val displayName = value["display_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val role = value["role"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { FamilyRole.valueOf(it) }.getOrNull() }
                ?: return@mapNotNull null
            val isSelf = value["is_self"]?.jsonPrimitive?.booleanOrNull
                ?: return@mapNotNull null
            val lastSyncAt = value["last_sync_at"]?.jsonPrimitive?.longOrNull
            normalizeDirectoryMember(
                FamilyMember(
                    displayName = displayName,
                    role = role,
                    isSelf = isSelf,
                    membershipId = membershipId,
                    devices = null,
                    lastSyncAtEpochSeconds = lastSyncAt,
                ),
            )
        }.distinctBy(FamilyMember::membershipId)
    }.getOrDefault(emptyList())

    private fun normalizeDirectoryMember(member: FamilyMember): FamilyMember? {
        val membershipId = member.membershipId.trim()
        val displayName = member.displayName.trim()
        if (membershipId.isEmpty() || displayName.isEmpty() || member.role == FamilyRole.None) return null
        return FamilyMember(
            displayName = displayName,
            role = member.role,
            isSelf = member.isSelf,
            membershipId = membershipId,
            devices = null,
            lastSyncAtEpochSeconds = member.lastSyncAtEpochSeconds,
        )
    }

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
        val LAST_SERVER_HEALTHY_AT = longPreferencesKey("sync_last_server_healthy_at")
        val CREATE_REQUEST_ID = stringPreferencesKey("sync_create_request_id")
        val OWNER_LOGIN_REQUEST_ID = stringPreferencesKey("sync_owner_login_request_id")
        val REFRESH_REQUEST_ID = stringPreferencesKey("sync_refresh_request_id")
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
        val FAMILY_MEMBER_DIRECTORY = stringPreferencesKey("sync_family_member_directory_v1")
        val RESTORE_BATCH_ID = stringPreferencesKey("sync_restore_batch_id")
        val RESTORE_ENDPOINT_ORIGIN = stringPreferencesKey("sync_restore_endpoint_origin")
        val RESTORE_ENDPOINT_TRUST_MODE = stringPreferencesKey("sync_restore_endpoint_trust_mode")
        val RESTORE_ENDPOINT_SPKI_SHA256 = stringPreferencesKey("sync_restore_endpoint_spki_sha256")
        val RESTORE_FAMILY_ID = stringPreferencesKey("sync_restore_family_id")
        val RESTORE_START_REQUEST_ID = stringPreferencesKey("sync_restore_start_request_id")
        val RESTORE_MANIFEST_REQUEST_ID = stringPreferencesKey("sync_restore_manifest_request_id")
        val RESTORE_COMMIT_REQUEST_ID = stringPreferencesKey("sync_restore_commit_request_id")
        val RESTORE_EXPIRES_AT = longPreferencesKey("sync_restore_expires_at")
        val RESTORE_STATUS = stringPreferencesKey("sync_restore_status")
        val RESTORE_ENTITY_VERSIONS = stringPreferencesKey("sync_restore_entity_versions_v1")
    }

    private companion object {
        const val CREATOR_ACK_ENTRY_SEPARATOR = '\u001e'
        const val CREATOR_ACK_FIELD_SEPARATOR = '\u001f'
        val CREATOR_ACK_ENTITY_TYPES = setOf("care_plan", "custom_item")
    }
}
