package com.lezi.babylog

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lezi.babylog.core.common.DefaultLocalDataGate
import com.lezi.babylog.core.common.LocalDataUpgradeBlockReason
import com.lezi.babylog.core.common.LocalDataUpgradeState
import com.lezi.babylog.sync.session.InMemorySecureRefreshTokenStore
import com.lezi.babylog.sync.session.SecureRefreshTokenStore
import dagger.Lazy
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-Android regression for the device-transfer credential failure: the
 * ciphertext prefs file was copied to the new phone, but the Keystore master
 * key that wrapped its keyset was not. The gate must block with
 * [LocalDataUpgradeBlockReason.CredentialsUnavailable] and preserve every byte.
 *
 * Fixtures never touch the production `lezi_secure_family` prefs or the
 * production master key alias; each example owns a UUID-isolated root,
 * prefs file and Keystore aliases.
 */
@RunWith(AndroidJUnit4::class)
class LocalDataCredentialRecoveryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureRoots = mutableListOf<File>()
    private val fixturePrefsNames = mutableListOf<String>()
    private val fixtureKeystoreAliases = mutableListOf<String>()
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        fixturePrefsNames.forEach(context::deleteSharedPreferences)
        fixtureKeystoreAliases.forEach(::deleteKeystoreAlias)
        fixtureRoots.forEach(File::deleteRecursively)
    }

    @Test
    fun realMasterKeyMismatchBlocksAsCredentialsUnavailableAndPreservesEveryFile() = runBlocking {
        val fixture = buildCurrentContractFixture()
        val credentials = WrongMasterKeyCredentialStore(
            context = context,
            prefsName = fixture.prefsName,
            wrongAlias = fixture.wrongAlias,
        )
        val gate = gateFor(fixture, credentials)

        val before = fixture.snapshot()
        assertWithMessage(gate.diagnosticReport()).that(gate.ensureReady()).isFalse()
        val blocked = gate.state.value as LocalDataUpgradeState.Blocked
        assertThat(blocked.reason).isEqualTo(LocalDataUpgradeBlockReason.CredentialsUnavailable)
        assertThat(blocked.detail).isEqualTo("家庭登录凭证无法读取")

        // The block came from a real keyset decryption failure, not a stub.
        assertThat(credentials.verificationCount).isAtLeast(1)
        val failure = requireNotNull(credentials.verificationFailure)
        val chain = generateSequence<Throwable>(failure) { it.cause }.toList()
        assertWithMessage(chain.joinToString("\n") { it.javaClass.name + ": " + it.message })
            .that(chain.any { it is AEADBadTagException })
            .isTrue()

        // Retrying re-runs the real verification and stays blocked.
        assertThat(gate.ensureReady()).isFalse()
        assertThat((gate.state.value as LocalDataUpgradeState.Blocked).reason)
            .isEqualTo(LocalDataUpgradeBlockReason.CredentialsUnavailable)
        assertThat(credentials.verificationCount).isAtLeast(2)

        // Nothing was deleted, re-keyed or rewritten: fixture bytes are intact.
        assertThat(fixture.snapshot()).isEqualTo(before)
    }

    @Test
    fun missingSecurePreferencesFileStaysReadyWithoutTouchingCredentials() = runBlocking {
        val fixture = buildCurrentContractFixture(securePreferencesPresent = false)
        val credentials = AccessCountingCredentialStore()
        val gate = gateFor(fixture, credentials)

        assertWithMessage(gate.diagnosticReport()).that(gate.ensureReady()).isTrue()
        assertThat(gate.state.value).isEqualTo(LocalDataUpgradeState.Ready(6))
        // No secure prefs file: the credential store is never even constructed
        // or consulted, so a fresh install never fabricates credentials.
        assertThat(credentials.verificationCount).isEqualTo(0)
        assertThat(fixture.snapshot()).isNotEmpty()
    }

    @Test
    fun credentialCancellationIsNotTranslatedIntoCredentialsUnavailable() = runBlocking {
        val fixture = buildCurrentContractFixture(realEncryption = false)
        val credentials = object : SecureRefreshTokenStore by InMemorySecureRefreshTokenStore() {
            override fun verifyReadable() {
                throw CancellationException("fixture credential read cancelled")
            }
        }
        val gate = gateFor(fixture, credentials)

        try {
            gate.ensureReady()
            throw AssertionError("cancellation must propagate, not convert into a block")
        } catch (expected: CancellationException) {
            // Propagates unchanged — the gate never publishes a fake recovery page.
        }
        assertThat(gate.state.value).isNotInstanceOf(LocalDataUpgradeState.Blocked::class.java)
    }

    private fun gateFor(
        fixture: CurrentContractFixture,
        credentials: SecureRefreshTokenStore,
    ): DefaultLocalDataGate = DefaultLocalDataGate(
        currentContractVersion = 6,
        minimumMigratableContractVersion = 1,
        steps = emptySet(),
        environment = AndroidLocalDataUpgradeEnvironment(
            context = context,
            settings = Lazy { fixture.settings },
            credentials = Lazy { credentials },
            storage = fixture.storage,
        ),
    )

    private class CurrentContractFixture(
        val root: File,
        val prefsName: String,
        val wrongAlias: String,
        val settings: DataStore<Preferences>,
        val storage: AndroidLocalDataStoragePaths,
        private val securePreferencesFile: File?,
    ) {
        /** Content digest snapshot of every fixture artifact taken after flush. */
        fun snapshot(): Map<String, String> = buildMap {
            root.walkTopDown().filter(File::isFile).forEach { put(it.path, it.sha256Hex()) }
            securePreferencesFile?.takeIf(File::isFile)?.let { put(it.path, it.sha256Hex()) }
        }

        private fun File.sha256Hex(): String =
            MessageDigest.getInstance("SHA-256").digest(readBytes())
                .joinToString("") { "%02x".format(it) }
    }

    /**
     * Builds a current-contract (6 / Room schema 29) fixture whose credential
     * prefs were encrypted under [aliasA]; the returned `wrongAlias` resolves
     * to a different real master key, reproducing the device-transfer state.
     */
    private fun buildCurrentContractFixture(
        securePreferencesPresent: Boolean = true,
        realEncryption: Boolean = securePreferencesPresent,
    ): CurrentContractFixture {
        val uuid = UUID.randomUUID().toString()
        val root = File(context.noBackupFilesDir, "local-data-credential-recovery-$uuid")
            .also { it.mkdirs() }
        fixtureRoots += root

        val prefsName = "lezi_credential_fixture_$uuid".also(fixturePrefsNames::add)
        val aliasA = "lezi_fixture_key_a_$uuid".replace("-", "_").also(fixtureKeystoreAliases::add)
        val wrongAlias = "lezi_fixture_key_b_$uuid".replace("-", "_").also(fixtureKeystoreAliases::add)

        val securePreferencesFile: File?
        if (securePreferencesPresent) {
            if (realEncryption) {
                val originalMasterKey = MasterKey.Builder(context, aliasA)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val encrypted = EncryptedSharedPreferences.create(
                    context,
                    prefsName,
                    originalMasterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                check(encrypted.edit().putString("refresh_token", "fixture-token").commit()) {
                    "fixture refresh_token commit failed"
                }
            } else {
                // Only the file's presence matters to the gate seam; the
                // injected store decides the outcome without touching crypto.
                File(context.applicationInfo.dataDir, "shared_prefs/$prefsName.xml")
                    .apply { parentFile?.mkdirs() }
                    .writeText("opaque-credential-fixture")
            }
            securePreferencesFile =
                File(context.applicationInfo.dataDir, "shared_prefs/$prefsName.xml")
        } else {
            securePreferencesFile = null
        }

        // Current-contract SQLite: schema 29 with one sentinel row.
        val database = File(root, "databases/lezi.db")
        database.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(database, null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE sentinel (value TEXT NOT NULL)")
            sqlite.execSQL("INSERT INTO sentinel(value) VALUES ('fixture-kept')")
            sqlite.version = 29
        }

        // Settings DataStore with one synthetic preference, flushed before snapshot.
        val settingsFile = File(root, "datastore/lezi_settings.preferences_pb")
        settingsFile.parentFile?.mkdirs()
        val settings = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { settingsFile },
        )
        runBlocking {
            settings.edit { it[booleanPreferencesKey("fixture_ready")] = true }
        }

        // Synthetic media bytes.
        val recordMedia = File(root, "record-media").apply { mkdirs() }
        File(recordMedia, "fixture.jpg").writeBytes(byteArrayOf(9, 8, 7, 6))

        // Current contract marker.
        val upgradeRoot = File(root, "upgrade").apply { mkdirs() }
        val marker = Properties().apply { setProperty("contract_version", "6") }
        File(upgradeRoot, "contract.properties").outputStream().use { marker.store(it, null) }

        val storage = AndroidLocalDataStoragePaths(
            dataRoot = root,
            database = database,
            upgradeRoot = upgradeRoot,
            settingsDataStore = settingsFile,
            widgetPreferences = File(root, "shared_prefs/widget.xml"),
            widgetPreferencesName = "lezi_fixture_widget_$uuid",
            securePreferences = securePreferencesFile
                ?: File(root, "shared_prefs/absent_$uuid.xml"),
            recordMedia = recordMedia,
            babyAvatars = File(root, "baby_avatars").apply { mkdirs() },
        )
        return CurrentContractFixture(
            root = root,
            prefsName = prefsName,
            wrongAlias = wrongAlias,
            settings = settings,
            storage = storage,
            securePreferencesFile = securePreferencesFile,
        )
    }

    /** Reopens the fixture prefs under a different real master key. */
    private class WrongMasterKeyCredentialStore(
        private val context: Context,
        private val prefsName: String,
        private val wrongAlias: String,
    ) : SecureRefreshTokenStore by InMemorySecureRefreshTokenStore() {
        var verificationCount = 0
            private set
        var verificationFailure: Throwable? = null
            private set

        override fun verifyReadable() {
            verificationCount += 1
            try {
                val wrongMasterKey = MasterKey.Builder(context, wrongAlias)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    prefsName,
                    wrongMasterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                ).all
            } catch (failure: Throwable) {
                verificationFailure = failure
                throw failure
            }
        }
    }

    private class AccessCountingCredentialStore : SecureRefreshTokenStore by
    InMemorySecureRefreshTokenStore() {
        var verificationCount = 0
            private set

        override fun verifyReadable() {
            verificationCount += 1
        }
    }

    private fun deleteKeystoreAlias(alias: String) {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }
}
