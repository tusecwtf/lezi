package com.lezi.babylog.sync.session
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.FamilyDevice
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.backend.MemberLoginReceipt

internal fun preferences(
    store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    tokens: SecureRefreshTokenStore = InMemorySecureRefreshTokenStore(),
) = DataStoreSyncPreferences(store, tokens)

internal class FailOnceClearTokenStore : SecureRefreshTokenStore {
    internal val delegate = InMemorySecureRefreshTokenStore()
    var failNextClear = false

    override fun getToken(): String = delegate.getToken()

    override fun setToken(token: String) = delegate.setToken(token)

    override fun clearToken() {
        if (failNextClear) {
            failNextClear = false
            throw IllegalStateException("secure clear interrupted")
        }
        delegate.clearToken()
    }
}

internal class FailOnceSetTokenStore : SecureRefreshTokenStore {
    internal val delegate = InMemorySecureRefreshTokenStore()
    var failNextSet = false

    override fun getToken(): String = delegate.getToken()

    override fun setToken(token: String) {
        if (failNextSet) {
            failNextSet = false
            throw IllegalStateException("secure set interrupted")
        }
        delegate.setToken(token)
    }

    override fun clearToken() = delegate.clearToken()

    override fun getPendingMemberSecret(): String = delegate.getPendingMemberSecret()

    override fun setPendingMemberSecret(secret: String) = delegate.setPendingMemberSecret(secret)

    override fun clearPendingMemberSecret() = delegate.clearPendingMemberSecret()
}

internal class ThreadRecordingPendingSecretStore : SecureRefreshTokenStore {
    internal val delegate = InMemorySecureRefreshTokenStore()
    val setThread = AtomicReference("")
    val getThread = AtomicReference("")
    val clearThread = AtomicReference("")

    override fun getToken(): String = delegate.getToken()

    override fun setToken(token: String) = delegate.setToken(token)

    override fun clearToken() = delegate.clearToken()

    override fun getPendingMemberSecret(): String {
        getThread.set(Thread.currentThread().name)
        return delegate.getPendingMemberSecret()
    }

    override fun setPendingMemberSecret(secret: String) {
        setThread.set(Thread.currentThread().name)
        delegate.setPendingMemberSecret(secret)
    }

    override fun clearPendingMemberSecret() {
        clearThread.set(Thread.currentThread().name)
        delegate.clearPendingMemberSecret()
    }
}

internal class FailOnceClearPendingMemberSecretStore : SecureRefreshTokenStore {
    internal val delegate = InMemorySecureRefreshTokenStore()
    var failNextPendingClear = false

    override fun getToken(): String = delegate.getToken()

    override fun setToken(token: String) = delegate.setToken(token)

    override fun clearToken() = delegate.clearToken()

    override fun getPendingMemberSecret(): String = delegate.getPendingMemberSecret()

    override fun setPendingMemberSecret(secret: String) = delegate.setPendingMemberSecret(secret)

    override fun clearPendingMemberSecret() {
        if (failNextPendingClear) {
            failNextPendingClear = false
            throw IllegalStateException("pending secret cleanup interrupted")
        }
        delegate.clearPendingMemberSecret()
    }
}
