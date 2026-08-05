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

@OptIn(ExperimentalCoroutinesApi::class)
class SyncPreferencesClearMarkersTest {
    @Test
    fun pendingDeviceRemovalClearMarkerSurvivesRestartUntilExplicitCompletion() = runTest {
        val file = File.createTempFile("lezi-device-removal-", ".preferences_pb")
            .also { it.delete() }
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val first = preferences(PreferenceDataStoreFactory.create(scope = firstScope) { file })

        first.markPendingDeviceRemovalClear()
        assertThat(first.hasPendingDeviceRemovalClear()).isTrue()
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val restored = preferences(PreferenceDataStoreFactory.create(scope = secondScope) { file })
        assertThat(restored.hasPendingDeviceRemovalClear()).isTrue()

        restored.clearPendingDeviceRemovalClear()
        assertThat(restored.hasPendingDeviceRemovalClear()).isFalse()
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun terminalClearMarkerMakesCredentialsUnusableBeforeDomainClearStarts() = runTest {
        val file = File.createTempFile("lezi-terminal-gate-", ".preferences_pb")
            .also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store)
        preferences.saveSession(
            SyncSession(
                serverHost = "family.home",
                familyId = "family-id",
                accessToken = "access-token",
                refreshToken = "refresh-token",
                deviceId = "device-id",
                membershipId = "membership-id",
                role = FamilyRole.Member,
            ),
        )

        preferences.markPendingDeviceRemovalClear()

        val gated = preferences.session.first()
        assertThat(gated.familyId).isEqualTo("family-id")
        assertThat(gated.accessToken).isEmpty()
        assertThat(gated.refreshToken).isEmpty()
        assertThat(gated.reauthRequired).isTrue()
        assertThat(gated.isJoined).isFalse()
        file.delete()
    }

    @Test
    fun pendingMembershipDeletionClearMarkerSurvivesRestartUntilExplicitCompletion() = runTest {
        val file = File.createTempFile("lezi-membership-deletion-", ".preferences_pb")
            .also { it.delete() }
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val first = preferences(PreferenceDataStoreFactory.create(scope = firstScope) { file })

        first.markPendingMembershipDeletionClear()
        assertThat(first.hasPendingMembershipDeletionClear()).isTrue()
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val restored = preferences(PreferenceDataStoreFactory.create(scope = secondScope) { file })
        assertThat(restored.hasPendingMembershipDeletionClear()).isTrue()

        restored.clearPendingMembershipDeletionClear()
        assertThat(restored.hasPendingMembershipDeletionClear()).isFalse()
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun pendingFamilyDeletionClearMarkerSurvivesRestartUntilExplicitCompletion() = runTest {
        val file = File.createTempFile("lezi-family-deletion-", ".preferences_pb")
            .also { it.delete() }
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val first = preferences(PreferenceDataStoreFactory.create(scope = firstScope) { file })

        first.markPendingFamilyDeletionClear()
        assertThat(first.hasPendingFamilyDeletionClear()).isTrue()
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val restored = preferences(PreferenceDataStoreFactory.create(scope = secondScope) { file })
        assertThat(restored.hasPendingFamilyDeletionClear()).isTrue()

        restored.clearPendingFamilyDeletionClear()
        assertThat(restored.hasPendingFamilyDeletionClear()).isFalse()
        secondScope.cancel()
        file.delete()
    }
}
