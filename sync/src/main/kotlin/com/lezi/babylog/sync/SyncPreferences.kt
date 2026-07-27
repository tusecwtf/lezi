package com.lezi.babylog.sync

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
    val familyToken: String = "",
    val deviceId: String = "",
    val role: FamilyRole = FamilyRole.None,
    val pullCursor: Long = 0,
    val pullGeneration: String = "",
    val lastSuccessAt: Long? = null,
    val serverHost: String = "",
    val serverPort: Int = DEFAULT_SERVER_PORT,
    val allowedSsids: List<String> = emptyList(),
    val serverScheme: String = DEFAULT_SERVER_SCHEME,
    /**
     * Shared family name cached from create/join/rename responses.
     * Null when the current family has no configured shared name.
     * Cold start relies on this local cache (no GET family-name path in this ticket).
     */
    val familyName: String? = null,
    /**
     * Server-minted immutable membership identity for this device's family session.
     * Empty only while not joined; current create/join responses require this field.
     */
    val membershipId: String = "",
    val pendingCreatorAcknowledgements: Set<CreatorAcknowledgementRef> = emptySet(),
) {
    val baseUrl: String
        get() = homeLanConfig.baseUrl

    val isJoined: Boolean
        get() = baseUrl.isNotBlank() && familyId.isNotBlank() && familyToken.isNotBlank()

    val homeLanConfig: HomeLanServerConfig
        get() = HomeLanServerConfig(
            host = serverHost,
            port = serverPort,
            allowedSsids = allowedSsids,
            scheme = serverScheme,
        ).withNormalized()

    fun isCreatorAcknowledgementPending(entityType: String, clientUuid: String): Boolean =
        CreatorAcknowledgementRef(entityType.trim(), clientUuid.trim()) in
            pendingCreatorAcknowledgements
}

interface SyncPreferences {
    val session: Flow<SyncSession>
    suspend fun saveServer(baseUrl: String)
    suspend fun saveHomeLanConfig(config: HomeLanServerConfig, clearSessionIfServerChanged: Boolean = true)
    suspend fun saveSession(session: SyncSession)
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
    suspend fun clearCreateRequestId()
    /** Wipes the host, port, SSID allowlist, and family session. */
    suspend fun clearAllLocalSyncConfig()
    /** Completes a same-version credential clear interrupted between durability domains. */
    suspend fun recoverPendingCredentialClear() {}
}

@Singleton
class DataStoreSyncPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val secureTokenStore: SecureFamilyTokenStore,
) : SyncPreferences {
    override val session: Flow<SyncSession> = dataStore.data.map { prefs ->
        mapSession(prefs)
    }

    private fun mapSession(prefs: Preferences): SyncSession {
        val rawScheme = prefs[Keys.SERVER_SCHEME].orEmpty()
        val schemeIsValid = rawScheme.lowercase() == "http" || rawScheme.lowercase() == "https"
        val host = if (schemeIsValid) {
            prefs[Keys.SERVER_HOST].orEmpty()
        } else {
            ""
        }
        val port = prefs[Keys.SERVER_PORT]
            ?: DEFAULT_SERVER_PORT
        val scheme = if (schemeIsValid) rawScheme.lowercase() else DEFAULT_SERVER_SCHEME
        val ssids = decodeSsids(prefs[Keys.ALLOWED_SSIDS])
        val credentialClearPending = prefs[Keys.PENDING_FAMILY_CREDENTIAL_CLEAR] == true
        return SyncSession(
            familyId = prefs[Keys.FAMILY_ID].orEmpty(),
            familyToken = if (credentialClearPending) "" else secureTokenStore.getToken(),
            deviceId = prefs[Keys.DEVICE_ID].orEmpty(),
            role = prefs[Keys.ROLE]?.let { runCatching { FamilyRole.valueOf(it) }.getOrNull() }
                ?: FamilyRole.None,
            pullCursor = prefs[Keys.PULL_CURSOR] ?: 0,
            pullGeneration = prefs[Keys.PULL_GENERATION].orEmpty(),
            lastSuccessAt = prefs[Keys.LAST_SUCCESS_AT],
            serverHost = host,
            serverPort = port,
            allowedSsids = ssids,
            serverScheme = scheme,
            familyName = prefs[Keys.FAMILY_NAME]?.trim()?.takeIf { it.isNotEmpty() },
            membershipId = prefs[Keys.MEMBERSHIP_ID].orEmpty(),
            pendingCreatorAcknowledgements =
                decodeCreatorAcknowledgements(prefs[Keys.PENDING_CREATOR_ACKNOWLEDGEMENTS]),
        )
    }

    override suspend fun saveServer(baseUrl: String) {
        val config = HomeLanServerConfig.fromBaseUrl(baseUrl).withNormalized()
        val previous = session.first()
        saveHomeLanConfig(
            config.copy(allowedSsids = previous.allowedSsids),
            clearSessionIfServerChanged = true,
        )
    }

    override suspend fun saveHomeLanConfig(
        config: HomeLanServerConfig,
        clearSessionIfServerChanged: Boolean,
    ) {
        val normalized = config.withNormalized()
        val previous = session.first()
        val newBase = normalized.baseUrl
        val serverChanged = previous.baseUrl.isNotBlank() &&
            previous.baseUrl != newBase &&
            newBase.isNotBlank()
        dataStore.edit { prefs ->
            if (clearSessionIfServerChanged && serverChanged) {
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
            val ssidEncoded = encodeSsids(normalized.allowedSsids)
            if (ssidEncoded.isBlank()) {
                prefs.remove(Keys.ALLOWED_SSIDS)
            } else {
                prefs[Keys.ALLOWED_SSIDS] = ssidEncoded
            }
        }
        finishPendingFamilyCredentialClear()
    }

    override suspend fun saveSession(session: SyncSession) {
        secureTokenStore.setToken(session.familyToken)
        val config = session.homeLanConfig.withNormalized()
        dataStore.edit { prefs ->
            val previousFamilyId = prefs[Keys.FAMILY_ID].orEmpty()
            if (config.host.isNotBlank()) {
                prefs[Keys.SERVER_HOST] = config.host
                prefs[Keys.SERVER_PORT] = config.port
                prefs[Keys.SERVER_SCHEME] = config.scheme
            }
            val ssidEncoded = encodeSsids(config.allowedSsids.ifEmpty { session.allowedSsids })
            if (ssidEncoded.isBlank()) prefs.remove(Keys.ALLOWED_SSIDS)
            else prefs[Keys.ALLOWED_SSIDS] = ssidEncoded
            prefs[Keys.FAMILY_ID] = session.familyId
            if (previousFamilyId != session.familyId) {
                prefs.remove(Keys.PENDING_CREATOR_ACKNOWLEDGEMENTS)
            }
            // The owner session and retirement of its idempotency key are one
            // durable commit. A separate post-commit edit can fail after the UI
            // already owns a valid joined session.
            prefs.remove(Keys.CREATE_REQUEST_ID)
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
        }
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
            prefs.remove(Keys.ALLOWED_SSIDS)
        }
        finishPendingFamilyCredentialClear()
    }

    override suspend fun recoverPendingCredentialClear() {
        finishPendingFamilyCredentialClear()
    }

    private suspend fun finishPendingFamilyCredentialClear() {
        if (dataStore.data.first()[Keys.PENDING_FAMILY_CREDENTIAL_CLEAR] != true) return
        // EncryptedSharedPreferences is a separate durability domain. Keep the
        // DataStore marker (and suppress token projection) until its synchronous
        // clear succeeds, so process death can only expose the terminal unjoined
        // state and a later foreground operation can finish idempotently.
        secureTokenStore.clearToken()
        dataStore.edit { prefs ->
            prefs.remove(Keys.PENDING_FAMILY_CREDENTIAL_CLEAR)
        }
    }

    private fun clearFamilyValues(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        prefs.remove(Keys.FAMILY_ID)
        prefs.remove(Keys.ROLE)
        prefs.remove(Keys.PULL_CURSOR)
        prefs.remove(Keys.PULL_GENERATION)
        prefs.remove(Keys.LAST_SUCCESS_AT)
        prefs.remove(Keys.CREATE_REQUEST_ID)
        prefs.remove(Keys.FAMILY_NAME)
        prefs.remove(Keys.MEMBERSHIP_ID)
        prefs.remove(Keys.PENDING_CREATOR_ACKNOWLEDGEMENTS)
    }

    private fun encodeSsids(ssids: List<String>): String =
        HomeLanServerConfig.normalizeSsids(ssids).joinToString("\u001e")

    private fun decodeSsids(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return HomeLanServerConfig.normalizeSsids(raw.split('\u001e', '\n', ','))
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
        val ALLOWED_SSIDS = stringPreferencesKey("sync_allowed_ssids")
        val FAMILY_ID = stringPreferencesKey("sync_family_id")
        val DEVICE_ID = stringPreferencesKey("sync_device_id")
        val ROLE = stringPreferencesKey("sync_family_role")
        val PULL_CURSOR = longPreferencesKey("sync_pull_cursor")
        val PULL_GENERATION = stringPreferencesKey("sync_pull_generation")
        val LAST_SUCCESS_AT = longPreferencesKey("sync_last_success_at")
        val CREATE_REQUEST_ID = stringPreferencesKey("sync_create_request_id")
        val FAMILY_NAME = stringPreferencesKey("sync_family_name")
        val MEMBERSHIP_ID = stringPreferencesKey("sync_membership_id")
        val PENDING_CREATOR_ACKNOWLEDGEMENTS =
            stringPreferencesKey("sync_pending_creator_acknowledgements")
        val PENDING_FAMILY_CREDENTIAL_CLEAR =
            booleanPreferencesKey("sync_pending_family_credential_clear")
    }

    private companion object {
        const val CREATOR_ACK_ENTRY_SEPARATOR = '\u001e'
        const val CREATOR_ACK_FIELD_SEPARATOR = '\u001f'
        val CREATOR_ACK_ENTITY_TYPES = setOf("care_plan", "custom_item")
    }
}
