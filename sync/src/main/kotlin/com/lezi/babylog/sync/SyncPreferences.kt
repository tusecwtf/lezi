package com.lezi.babylog.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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
    val baseUrl: String = "",
    val familyId: String = "",
    val familyToken: String = "",
    val deviceId: String = "",
    val role: FamilyRole = FamilyRole.None,
    val pullCursor: Long = 0,
    val pullGeneration: String = "",
    val lastSuccessAt: Long? = null,
) {
    val isJoined: Boolean
        get() = baseUrl.isNotBlank() && familyId.isNotBlank() && familyToken.isNotBlank()
}

interface SyncPreferences {
    val session: Flow<SyncSession>
    suspend fun saveServer(baseUrl: String)
    suspend fun saveSession(session: SyncSession)
    suspend fun updateCursor(cursor: Long, generation: String = "")
    suspend fun markSuccess(atMillis: Long)
    suspend fun ensureDeviceId(): String
    suspend fun ensureCreateRequestId(): String
    suspend fun clearCreateRequestId()
    suspend fun clearFamilySession()
    /** Move legacy plaintext secrets into the secure store when present. */
    suspend fun migrateSecretsIfNeeded() {}
}

@Singleton
class DataStoreSyncPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val secureTokenStore: SecureFamilyTokenStore,
) : SyncPreferences {
    override val session: Flow<SyncSession> = dataStore.data.map { prefs ->
        SyncSession(
            baseUrl = prefs[Keys.BASE_URL].orEmpty(),
            familyId = prefs[Keys.FAMILY_ID].orEmpty(),
            familyToken = resolveFamilyToken(prefs),
            deviceId = prefs[Keys.DEVICE_ID].orEmpty(),
            role = prefs[Keys.ROLE]?.let { runCatching { FamilyRole.valueOf(it) }.getOrNull() }
                ?: FamilyRole.None,
            pullCursor = prefs[Keys.PULL_CURSOR] ?: 0,
            pullGeneration = prefs[Keys.PULL_GENERATION].orEmpty(),
            lastSuccessAt = prefs[Keys.LAST_SUCCESS_AT],
        )
    }

    override suspend fun saveServer(baseUrl: String) {
        val normalized = normalizeBaseUrl(baseUrl)
        dataStore.edit { prefs ->
            if (prefs[Keys.BASE_URL].orEmpty() != normalized) {
                clearFamilyValues(prefs)
            }
            prefs[Keys.BASE_URL] = normalized
        }
    }

    override suspend fun saveSession(session: SyncSession) {
        secureTokenStore.setToken(session.familyToken)
        dataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = normalizeBaseUrl(session.baseUrl)
            prefs[Keys.FAMILY_ID] = session.familyId
            // Token lives only in the Keystore-wrapped store after this write.
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

    override suspend fun migrateSecretsIfNeeded() = migratePlaintextTokenIfPresent()

    /**
     * One-shot migration for upgrades that still hold `sync_family_token` in
     * plaintext DataStore preferences.
     */
    suspend fun migratePlaintextTokenIfPresent() {
        dataStore.edit { prefs ->
            val legacy = prefs[Keys.FAMILY_TOKEN]
            if (!legacy.isNullOrBlank()) {
                if (secureTokenStore.getToken().isBlank()) {
                    secureTokenStore.setToken(legacy)
                }
                prefs.remove(Keys.FAMILY_TOKEN)
            }
        }
    }

    private fun resolveFamilyToken(prefs: Preferences): String {
        val secure = secureTokenStore.getToken()
        if (secure.isNotBlank()) return secure
        return prefs[Keys.FAMILY_TOKEN].orEmpty()
    }

    private fun normalizeBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        if (normalized.isEmpty()) return ""
        InvitePayloadCodec.encode(InvitePayload(normalized, "ABC12345"))
        return normalized
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

    private object Keys {
        val BASE_URL = stringPreferencesKey("sync_base_url")
        val FAMILY_ID = stringPreferencesKey("sync_family_id")
        /** Legacy plaintext key — migrated out on first secure write/read path. */
        val FAMILY_TOKEN = stringPreferencesKey("sync_family_token")
        val DEVICE_ID = stringPreferencesKey("sync_device_id")
        val ROLE = stringPreferencesKey("sync_family_role")
        val PULL_CURSOR = longPreferencesKey("sync_pull_cursor")
        val PULL_GENERATION = stringPreferencesKey("sync_pull_generation")
        val LAST_SUCCESS_AT = longPreferencesKey("sync_last_success_at")
        val CREATE_REQUEST_ID = stringPreferencesKey("sync_create_request_id")
    }
}
