package com.lezi.babylog.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Ignore

/**
 * Host-side fixture writer for emu UI acceptance (joined + network configured).
 * Not part of CI product gates — enable when seeding device prefs.
 */
class WriteJoinedPrefsFixtureTest {
    @Ignore("Manual fixture seed for emu UI evidence only")
    @Test
    fun writeJoinedOwnerFixtureToScratch() = runTest {
        val out = File("/tmp/grok-goal-bdf73c4e3748/implementer/lezi_settings.preferences_pb")
        if (out.exists()) out.delete()
        val tokens = InMemorySecureFamilyTokenStore()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { out }
        val prefs = DataStoreSyncPreferences(store, tokens)
        prefs.saveSession(
            SyncSession(
                baseUrl = "http://192.168.50.4:8765",
                familyId = "fixture-family-ui",
                familyToken = "fixture-token-for-ui-layout-only",
                deviceId = "fixture-device",
                role = FamilyRole.Owner,
                pullCursor = 0,
                pullGeneration = "",
                lastSuccessAt = System.currentTimeMillis(),
                serverHost = "192.168.50.4",
                serverPort = 8765,
                allowedSsids = listOf("AndroidWifi"),
            ),
        )
        // Leave plaintext token so device can migrate into EncryptedSharedPreferences on first launch.
        store.edit {
            it[stringPreferencesKey("sync_family_token")] = "fixture-token-for-ui-layout-only"
        }
        scope.cancel()
        advanceUntilIdle()
        check(out.exists() && out.length() > 0) { "fixture not written" }
        println("WROTE_FIXTURE ${out.absolutePath} bytes=${out.length()}")
    }
}
