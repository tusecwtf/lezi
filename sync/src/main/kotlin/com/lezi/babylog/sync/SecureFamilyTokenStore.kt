package com.lezi.babylog.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * At-rest store for the long-lived family Bearer token.
 *
 * Production uses Keystore-backed [EncryptedSharedPreferences]; JVM tests inject
 * [InMemorySecureFamilyTokenStore].
 */
interface SecureFamilyTokenStore {
    fun getToken(): String
    fun setToken(token: String)
    fun clearToken()
}

/** Process-local token store for JVM unit tests. */
class InMemorySecureFamilyTokenStore : SecureFamilyTokenStore {
    private val token = AtomicReference("")

    override fun getToken(): String = token.get()

    override fun setToken(token: String) {
        this.token.set(token)
    }

    override fun clearToken() {
        token.set("")
    }
}

@Singleton
class EncryptedSecureFamilyTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) : SecureFamilyTokenStore {
    private val prefs: SharedPreferences = createPrefs(context)

    override fun getToken(): String = prefs.getString(KEY_FAMILY_TOKEN, "").orEmpty()

    override fun setToken(token: String) {
        if (token.isBlank()) {
            clearToken()
            return
        }
        check(prefs.edit().putString(KEY_FAMILY_TOKEN, token).commit()) {
            "Unable to persist encrypted family token"
        }
    }

    override fun clearToken() {
        check(prefs.edit().remove(KEY_FAMILY_TOKEN).commit()) {
            "Unable to clear encrypted family token"
        }
    }

    private companion object {
        const val PREFS_NAME = "lezi_secure_family"
        const val KEY_FAMILY_TOKEN = "family_token"

        fun createPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
