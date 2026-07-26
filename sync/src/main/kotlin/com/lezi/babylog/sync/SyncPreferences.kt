package com.lezi.babylog.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
}

interface SyncPreferences {
    val session: Flow<SyncSession>
    suspend fun saveServer(baseUrl: String)
    suspend fun saveHomeLanConfig(config: HomeLanServerConfig, clearSessionIfServerChanged: Boolean = true)
    suspend fun saveSession(session: SyncSession)
    suspend fun updateCursor(cursor: Long, generation: String = "")
    suspend fun markSuccess(atMillis: Long)
    suspend fun ensureDeviceId(): String
    suspend fun ensureCreateRequestId(): String
    suspend fun clearCreateRequestId()
    /** Clears family session only (legacy); prefer [clearAllLocalSyncConfig] for leave/delete. */
    suspend fun clearFamilySession()
    /** Wipes the host, port, SSID allowlist, and family session. */
    suspend fun clearAllLocalSyncConfig()
    /** Move legacy plaintext secrets into the secure store when present. */
    suspend fun migrateSecretsIfNeeded() {}
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
        val legacyUrl = prefs[Keys.BASE_URL].orEmpty()
        val parsedLegacyUrl = runCatching { HomeLanServerConfig.fromBaseUrl(legacyUrl) }
            .getOrNull()
        val rawScheme = prefs[Keys.SERVER_SCHEME].orEmpty()
            .ifBlank { parsedLegacyUrl?.scheme.orEmpty() }
        val schemeIsValid = rawScheme.lowercase() == "http" || rawScheme.lowercase() == "https"
        val host = if (schemeIsValid) {
            prefs[Keys.SERVER_HOST].orEmpty().ifBlank { parsedLegacyUrl?.host.orEmpty() }
        } else {
            ""
        }
        val port = prefs[Keys.SERVER_PORT]
            ?: parsedLegacyUrl?.port?.takeIf { legacyUrl.isNotBlank() }
            ?: DEFAULT_SERVER_PORT
        val scheme = if (schemeIsValid) rawScheme.lowercase() else DEFAULT_SERVER_SCHEME
        val ssids = decodeSsids(prefs[Keys.ALLOWED_SSIDS])
        return SyncSession(
            familyId = prefs[Keys.FAMILY_ID].orEmpty(),
            familyToken = resolveFamilyToken(prefs),
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
        dataStore.edit { prefs ->
            val serverChanged = previous.baseUrl.isNotBlank() &&
                previous.baseUrl != newBase &&
                newBase.isNotBlank()
            if (clearSessionIfServerChanged && serverChanged && !previous.isJoined) {
                clearFamilyValues(prefs)
            }
            if (normalized.host.isBlank()) {
                prefs.remove(Keys.SERVER_HOST)
                prefs.remove(Keys.BASE_URL)
                prefs.remove(Keys.SERVER_SCHEME)
            } else {
                prefs[Keys.SERVER_HOST] = normalized.host
                prefs[Keys.SERVER_PORT] = normalized.port
                prefs[Keys.SERVER_SCHEME] = normalized.scheme
                prefs.remove(Keys.BASE_URL)
            }
            val ssidEncoded = encodeSsids(normalized.allowedSsids)
            if (ssidEncoded.isBlank()) {
                prefs.remove(Keys.ALLOWED_SSIDS)
            } else {
                prefs[Keys.ALLOWED_SSIDS] = ssidEncoded
            }
        }
        // A joined server change keeps the token but forces a full resync.
        if (previous.isJoined && previous.baseUrl != newBase && newBase.isNotBlank()) {
            dataStore.edit {
                it[Keys.PULL_CURSOR] = 0
                it.remove(Keys.PULL_GENERATION)
            }
        }
    }

    override suspend fun saveSession(session: SyncSession) {
        secureTokenStore.setToken(session.familyToken)
        val config = session.homeLanConfig.withNormalized()
        dataStore.edit { prefs ->
            if (config.host.isNotBlank()) {
                prefs[Keys.SERVER_HOST] = config.host
                prefs[Keys.SERVER_PORT] = config.port
                prefs[Keys.SERVER_SCHEME] = config.scheme
                prefs.remove(Keys.BASE_URL)
            } else {
                prefs.remove(Keys.BASE_URL)
            }
            val ssidEncoded = encodeSsids(config.allowedSsids.ifEmpty { session.allowedSsids })
            if (ssidEncoded.isBlank()) prefs.remove(Keys.ALLOWED_SSIDS)
            else prefs[Keys.ALLOWED_SSIDS] = ssidEncoded
            prefs[Keys.FAMILY_ID] = session.familyId
            prefs.remove(Keys.FAMILY_TOKEN)
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
        }
    }

    override suspend fun updateCursor(cursor: Long, generation: String) {
        migratePlaintextTokenIfPresent()
        dataStore.edit {
            it[Keys.PULL_CURSOR] = cursor.coerceAtLeast(0)
            if (generation.isBlank()) {
                it.remove(Keys.PULL_GENERATION)
            } else {
                it[Keys.PULL_GENERATION] = generation
            }
        }
    }

    override suspend fun markSuccess(atMillis: Long) {
        migratePlaintextTokenIfPresent()
        dataStore.edit { it[Keys.LAST_SUCCESS_AT] = atMillis }
    }

    override suspend fun ensureDeviceId(): String {
        migratePlaintextTokenIfPresent()
        session.first().deviceId.takeIf { it.isNotBlank() }?.let { return it }
        val generated = UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            if (prefs[Keys.DEVICE_ID].isNullOrBlank()) prefs[Keys.DEVICE_ID] = generated
        }
        return session.first().deviceId
    }

    override suspend fun ensureCreateRequestId(): String {
        migratePlaintextTokenIfPresent()
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

    override suspend fun clearFamilySession() {
        dataStore.edit(::clearFamilyValues)
    }

    override suspend fun clearAllLocalSyncConfig() {
        dataStore.edit { prefs ->
            clearFamilyValues(prefs)
            prefs.remove(Keys.BASE_URL)
            prefs.remove(Keys.SERVER_HOST)
            prefs.remove(Keys.SERVER_PORT)
            prefs.remove(Keys.SERVER_SCHEME)
            prefs.remove(Keys.ALLOWED_SSIDS)
        }
    }

    override suspend fun migrateSecretsIfNeeded() = migratePlaintextTokenIfPresent()

    suspend fun migratePlaintextTokenIfPresent() {
        dataStore.edit { prefs ->
            val legacy = prefs[Keys.FAMILY_TOKEN]
            if (!legacy.isNullOrBlank()) {
                if (secureTokenStore.getToken().isBlank()) {
                    secureTokenStore.setToken(legacy)
                }
                prefs.remove(Keys.FAMILY_TOKEN)
            }
            // Populate any missing structured endpoint fields from the legacy
            // URL, then retire the legacy key once a usable host exists.
            val base = prefs[Keys.BASE_URL].orEmpty()
            if (base.isNotBlank()) {
                val parsed = runCatching { HomeLanServerConfig.fromBaseUrl(base) }.getOrNull()
                if (parsed != null && parsed.host.isNotBlank()) {
                    if (prefs[Keys.SERVER_HOST].isNullOrBlank()) {
                        prefs[Keys.SERVER_HOST] = parsed.host
                    }
                    if (prefs[Keys.SERVER_PORT] == null) {
                        prefs[Keys.SERVER_PORT] = parsed.port
                    }
                    if (prefs[Keys.SERVER_SCHEME].isNullOrBlank()) {
                        prefs[Keys.SERVER_SCHEME] = parsed.scheme
                    }
                }
                if (!prefs[Keys.SERVER_HOST].isNullOrBlank()) {
                    prefs.remove(Keys.BASE_URL)
                }
            }
        }
    }

    private fun resolveFamilyToken(prefs: Preferences): String {
        val secure = secureTokenStore.getToken()
        if (secure.isNotBlank()) return secure
        return prefs[Keys.FAMILY_TOKEN].orEmpty()
    }

    private fun clearFamilyValues(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        prefs.remove(Keys.FAMILY_ID)
        prefs.remove(Keys.FAMILY_TOKEN)
        prefs.remove(Keys.ROLE)
        prefs.remove(Keys.PULL_CURSOR)
        prefs.remove(Keys.PULL_GENERATION)
        prefs.remove(Keys.LAST_SUCCESS_AT)
        prefs.remove(Keys.CREATE_REQUEST_ID)
        secureTokenStore.clearToken()
    }

    private fun encodeSsids(ssids: List<String>): String =
        HomeLanServerConfig.normalizeSsids(ssids).joinToString("\u001e")

    private fun decodeSsids(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return HomeLanServerConfig.normalizeSsids(raw.split('\u001e', '\n', ','))
    }

    private object Keys {
        val BASE_URL = stringPreferencesKey("sync_base_url")
        val SERVER_HOST = stringPreferencesKey("sync_server_host")
        val SERVER_PORT = intPreferencesKey("sync_server_port")
        val SERVER_SCHEME = stringPreferencesKey("sync_server_scheme")
        val ALLOWED_SSIDS = stringPreferencesKey("sync_allowed_ssids")
        val FAMILY_ID = stringPreferencesKey("sync_family_id")
        val FAMILY_TOKEN = stringPreferencesKey("sync_family_token")
        val DEVICE_ID = stringPreferencesKey("sync_device_id")
        val ROLE = stringPreferencesKey("sync_family_role")
        val PULL_CURSOR = longPreferencesKey("sync_pull_cursor")
        val PULL_GENERATION = stringPreferencesKey("sync_pull_generation")
        val LAST_SUCCESS_AT = longPreferencesKey("sync_last_success_at")
        val CREATE_REQUEST_ID = stringPreferencesKey("sync_create_request_id")
    }
}
