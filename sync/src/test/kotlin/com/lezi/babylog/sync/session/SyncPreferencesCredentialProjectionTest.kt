package com.lezi.babylog.sync.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.lezi.babylog.sync.FamilyMember
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SyncPreferencesCredentialProjectionTest {
    @Test
    fun delayedStorageEmissionCannotPairOldIdentityWithNewCredentials() = runTest {
        val store = DelayedEmissionDataStore()
        val preferences = DataStoreSyncPreferences(store, InMemorySecureRefreshTokenStore())
        val old = SyncSession(
            familyId = "old-family", membershipId = "old-member", deviceId = "old-device",
            role = FamilyRole.Owner, serverHost = "old.example.test",
            accessToken = "old-access", refreshToken = "old-refresh",
        )
        preferences.saveSession(old)
        store.delayNextEmission = true
        val observation = async { preferences.session.first() }
        store.captured.await()
        val replacement = old.copy(
            familyId = "new-family", membershipId = "new-member", deviceId = "new-device",
            serverHost = "new.example.test", accessToken = "new-access", refreshToken = "new-refresh",
        )
        preferences.saveSession(replacement)
        store.release.complete(Unit)

        val observed = observation.await()
        assertThat(observed.familyId).isEqualTo("new-family")
        assertThat(observed.baseUrl).isEqualTo("https://new.example.test:8765")
        assertThat(observed.accessToken).isEqualTo("new-access")
        assertThat(observed.refreshToken).isEqualTo("new-refresh")
    }

    @Test
    fun delayedCheckpointEmissionCannotPublishPreviousIdentityReadiness() = runTest {
        val store = DelayedEmissionDataStore()
        val preferences = DataStoreSyncPreferences(store, InMemorySecureRefreshTokenStore())
        val old = SyncSession(
            familyId = "old-family", membershipId = "old-member", deviceId = "old-device",
            role = FamilyRole.Owner, serverHost = "old.example.test",
            accessToken = "old-access", refreshToken = "old-refresh",
        )
        preferences.saveSession(old)
        preferences.updateCursor(91, "old-generation")
        store.delayNextEmission = true
        val observation = async { preferences.session.map(SyncSession::toPresentation).first() }
        store.captured.await()
        val replacement = old.copy(
            familyId = "new-family", membershipId = "new-member", deviceId = "new-device",
            role = FamilyRole.Member, serverHost = "new.example.test", familyName = "new name",
            accessToken = "new-access", refreshToken = "new-refresh",
            pullCursor = 0, pullGeneration = "new-generation",
        )
        preferences.saveSession(replacement)
        // Session activation deliberately clears the reauth gate. Enter reauth through the
        // actual credential owner rather than trying to smuggle the flag through saveSession.
        assertThat(preferences.clearDeviceCredentialsForReauthIfCurrent(
            preferences.session.first(),
        )).isTrue()
        store.release.complete(Unit)

        val observed = observation.await()
        assertThat(observed).isEqualTo(preferences.session.first().toPresentation())
        assertThat(observed.familyId).isEqualTo("new-family")
        assertThat(observed.membershipId).isEqualTo("new-member")
        assertThat(observed.deviceId).isEqualTo("new-device")
        assertThat(observed.role).isEqualTo(FamilyRole.Member)
        assertThat(observed.baseUrl).isEqualTo("https://new.example.test:8765")
        assertThat(observed.familyName).isEqualTo("new name")
        assertThat(observed.isJoined).isFalse()
        assertThat(observed.reauthRequired).isTrue()
    }

    @Test
    fun delayedDirectoryFetchCannotCommitAfterFamilyAToBToA() = runTest {
        val preferences = DataStoreSyncPreferences(DelayedEmissionDataStore(), InMemorySecureRefreshTokenStore())
        val a = readSession("a")
        preferences.saveSession(a)
        val originalEpoch = preferences.familyReadSnapshot.first().identityEpoch!!
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldFetch = async {
            val epoch = preferences.familyReadSnapshot.first().identityEpoch!!
            started.complete(Unit)
            release.await()
            preferences.saveFamilyMemberDirectoryIfCurrent(epoch, "server-a", listOf(member("old-a")))
        }
        started.await()
        preferences.saveSession(readSession("b"))
        preferences.saveSession(a)
        val currentEpoch = preferences.familyReadSnapshot.first().identityEpoch!!
        assertThat(currentEpoch).isGreaterThan(originalEpoch)
        assertThat(preferences.saveFamilyMemberDirectoryIfCurrent(
            currentEpoch, "server-a", listOf(member("new-a")),
        )).isTrue()
        release.complete(Unit)
        assertThat(oldFetch.await()).isFalse()
        val snapshot = preferences.familyReadSnapshot.first()
        assertThat(snapshot.session.familyId).isEqualTo("a")
        assertThat(snapshot.members.map { it.displayName }).containsExactly("new-a")
    }

    @Test
    fun delayedDataStoreEmissionReadsIdentityAndDirectoryFromOneCurrentOwner() = runTest {
        val store = DelayedEmissionDataStore()
        val preferences = DataStoreSyncPreferences(store, InMemorySecureRefreshTokenStore())
        preferences.saveSession(readSession("a"))
        preferences.saveFamilyMemberDirectoryIfCurrent(
            preferences.familyReadSnapshot.first().identityEpoch!!, "server-a", listOf(member("A")),
        )
        store.delayNextEmission = true
        val observation = async { preferences.familyReadSnapshot.first() }
        store.captured.await()
        preferences.saveSession(readSession("b"))
        preferences.saveFamilyMemberDirectoryIfCurrent(
            preferences.familyReadSnapshot.first().identityEpoch!!, "server-b", listOf(member("B")),
        )
        store.release.complete(Unit)
        val snapshot = observation.await()
        assertThat(snapshot.session.familyId).isEqualTo("b")
        assertThat(snapshot.members.map { it.displayName }).containsExactly("B")
    }

    @Test
    fun credentialRefreshCheckpointAndRenameKeepDirectoryEpochButIdentityChangesRetireIt() = runTest {
        val preferences = DataStoreSyncPreferences(DelayedEmissionDataStore(), InMemorySecureRefreshTokenStore())
        preferences.saveSession(readSession("a"))
        val epoch = preferences.familyReadSnapshot.first().identityEpoch!!
        val session = preferences.session.first()
        assertThat(preferences.saveRefreshedSessionIfCurrent(
            session, session.copy(accessToken = "rotated-access", refreshToken = "rotated-refresh"),
        )).isTrue()
        preferences.updatePullCheckpoint(91, "server-a", "renamed")
        assertThat(preferences.updateFamilyName(preferences.session.first(), "renamed again")).isTrue()
        assertThat(preferences.familyReadSnapshot.first().identityEpoch).isEqualTo(epoch)
        assertThat(preferences.saveFamilyMemberDirectoryIfCurrent(epoch, "server-a", listOf(member("A")))).isTrue()
        assertThat(preferences.familyReadSnapshot.first().members).hasSize(1)
        // Same-family membership/device replacement still retires the previous directory owner.
        preferences.saveSession(preferences.session.first().copy(membershipId = "replacement", deviceId = "replacement-device"))
        val replacement = preferences.familyReadSnapshot.first()
        assertThat(replacement.identityEpoch).isGreaterThan(epoch)
        assertThat(replacement.members).isEmpty()
        assertThat(preferences.saveFamilyMemberDirectoryIfCurrent(epoch, "server-a", listOf(member("late")))).isFalse()
    }

    @Test
    fun existingUntaggedDirectoryIsWithheldUntilAProducerOwnedRefreshAndEpochSurvivesRestart() = runTest {
        val store = DelayedEmissionDataStore()
        val tokens = InMemorySecureRefreshTokenStore()
        val preferences = DataStoreSyncPreferences(store, tokens)
        preferences.saveSession(readSession("a"))
        preferences.saveFamilyMemberDirectory(listOf(member("legacy")))
        // Simulate an existing contract-7 preferences file written before the optional epoch keys.
        store.edit { it.remove(longPreferencesKey("sync_read_identity_epoch_v1")) }
        assertThat(preferences.familyReadSnapshot.first().identityEpoch).isEqualTo(0L)
        assertThat(preferences.familyMemberDirectory.first()).hasSize(1)
        assertThat(preferences.familyReadSnapshot.first().members).isEmpty()
        val epoch = preferences.familyReadSnapshot.first().identityEpoch!!
        assertThat(preferences.saveFamilyMemberDirectoryIfCurrent(epoch, "server-a", listOf(member("A")))).isTrue()
        val restored = DataStoreSyncPreferences(store, tokens).familyReadSnapshot.first()
        assertThat(restored.identityEpoch).isEqualTo(epoch)
        assertThat(restored.members.map { it.displayName }).containsExactly("A")
    }

    private fun readSession(family: String) = SyncSession(
        familyId = family, membershipId = "$family-member", deviceId = "$family-device",
        role = FamilyRole.Member, serverHost = "$family.example.test",
        accessToken = "$family-access", refreshToken = "$family-refresh",
    )

    private fun member(name: String) = FamilyMember(name, FamilyRole.Member, false, "author")

    /** Storage boundary emits a snapshot captured before a concurrent committed write. */
    private class DelayedEmissionDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        @Volatile private var value: Preferences = emptyPreferences()
        var delayNextEmission = false
        val captured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override val data: Flow<Preferences>
            get() = flow {
                val snapshot = value
                if (delayNextEmission) {
                    delayNextEmission = false
                    captured.complete(Unit)
                    release.await()
                }
                emit(snapshot)
            }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(value).also { value = it } }
    }
}
