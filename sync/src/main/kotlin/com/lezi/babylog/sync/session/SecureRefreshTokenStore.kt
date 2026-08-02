package com.lezi.babylog.sync.session
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * At-rest store for the rotating device-session refresh token.
 *
 * Production uses Keystore-backed [EncryptedSharedPreferences]; JVM tests inject
 * [InMemorySecureRefreshTokenStore].
 */
interface SecureRefreshTokenStore {
    fun getToken(): String
    fun setToken(token: String)
    fun clearToken()
    fun getPendingMemberSecret(): String = ""
    fun setPendingMemberSecret(secret: String) = Unit
    fun clearPendingMemberSecret() = Unit
    fun getDisasterRestoreToken(): String = ""
    fun setDisasterRestoreToken(token: String) = Unit
    fun clearDisasterRestoreToken() = Unit
    fun verifyReadable() = Unit
}

/** Process-local token store for JVM unit tests. */
class InMemorySecureRefreshTokenStore : SecureRefreshTokenStore {
    private val token = AtomicReference("")
    private val pendingMemberSecret = AtomicReference("")
    private val disasterRestoreToken = AtomicReference("")

    override fun getToken(): String = token.get()

    override fun setToken(token: String) {
        this.token.set(token)
    }

    override fun clearToken() {
        token.set("")
    }

    override fun getPendingMemberSecret(): String = pendingMemberSecret.get()

    override fun setPendingMemberSecret(secret: String) {
        pendingMemberSecret.set(secret)
    }

    override fun clearPendingMemberSecret() {
        pendingMemberSecret.set("")
    }

    override fun getDisasterRestoreToken(): String = disasterRestoreToken.get()

    override fun setDisasterRestoreToken(token: String) {
        disasterRestoreToken.set(token)
    }

    override fun clearDisasterRestoreToken() {
        disasterRestoreToken.set("")
    }
}

@Singleton
class EncryptedSecureRefreshTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) : SecureRefreshTokenStore {
    private val applicationContext = context.applicationContext
    private val prefs: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createPrefs(applicationContext)
    }

    override fun getToken(): String = prefs.getString(KEY_REFRESH_TOKEN, "").orEmpty()

    override fun setToken(token: String) {
        if (token.isBlank()) {
            clearToken()
            return
        }
        check(prefs.edit().putString(KEY_REFRESH_TOKEN, token).commit()) {
            "Unable to persist encrypted refresh token"
        }
    }

    override fun clearToken() {
        check(prefs.edit().remove(KEY_REFRESH_TOKEN).commit()) {
            "Unable to clear encrypted refresh token"
        }
    }

    override fun getPendingMemberSecret(): String =
        prefs.getString(KEY_PENDING_MEMBER_SECRET, "").orEmpty()

    override fun setPendingMemberSecret(secret: String) {
        if (secret.isBlank()) {
            clearPendingMemberSecret()
            return
        }
        check(prefs.edit().putString(KEY_PENDING_MEMBER_SECRET, secret).commit()) {
            "Unable to persist encrypted pending member secret"
        }
    }

    override fun clearPendingMemberSecret() {
        check(prefs.edit().remove(KEY_PENDING_MEMBER_SECRET).commit()) {
            "Unable to clear encrypted pending member secret"
        }
    }

    override fun getDisasterRestoreToken(): String =
        prefs.getString(KEY_DISASTER_RESTORE_TOKEN, "").orEmpty()

    override fun setDisasterRestoreToken(token: String) {
        if (token.isBlank()) {
            clearDisasterRestoreToken()
            return
        }
        check(prefs.edit().putString(KEY_DISASTER_RESTORE_TOKEN, token).commit()) {
            "Unable to persist encrypted disaster restore token"
        }
    }

    override fun clearDisasterRestoreToken() {
        check(prefs.edit().remove(KEY_DISASTER_RESTORE_TOKEN).commit()) {
            "Unable to clear encrypted disaster restore token"
        }
    }

    override fun verifyReadable() {
        prefs.all
    }

    private companion object {
        const val PREFS_NAME = "lezi_secure_family"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_PENDING_MEMBER_SECRET = "pending_member_secret"
        const val KEY_DISASTER_RESTORE_TOKEN = "disaster_restore_token"

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
