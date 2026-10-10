package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.SyncSessionPresentation
import com.lezi.babylog.sync.session.toPresentation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealSyncPortSessionPresentationTest {
    @Test
    fun productionPortKeepsJoinedReauthRenameCheckpointAndIdentityTransitionsCoherent() = runTest {
        val initial = joinedSession("family-a").copy(refreshToken = "refresh-a")
        val rig = SyncRig(initial)
        val observations = mutableListOf<SyncSessionPresentation>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.port.sessionPresentation().collect(observations::add)
        }
        assertThat(observations).containsExactly(initial.toPresentation())

        // Cursor and credential generations stay at the owner; neither creates UI churn.
        rig.preferences.updateCursor(50, "checkpoint-a")
        val beforeRefresh = rig.preferences.current()
        assertThat(rig.preferences.saveRefreshedSessionIfCurrent(
            beforeRefresh,
            beforeRefresh.copy(accessToken = "access-b", refreshToken = "refresh-b"),
        )).isTrue()
        runCurrent()
        assertThat(observations).containsExactly(initial.toPresentation())

        assertThat(rig.preferences.updateFamilyName(rig.preferences.current(), "renamed")).isTrue()
        runCurrent()
        assertThat(observations.last().familyName).isEqualTo("renamed")
        assertThat(observations.last().isJoined).isTrue()
        assertThat(observations.last()).isEqualTo(rig.preferences.current().toPresentation())

        assertThat(rig.preferences.clearDeviceCredentialsForReauthIfCurrent(
            rig.preferences.current(),
        )).isTrue()
        runCurrent()
        assertThat(observations.last().isJoined).isFalse()
        assertThat(observations.last().reauthRequired).isTrue()
        assertThat(observations.last().familyId).isEqualTo("family-a")

        // One replacement snapshot changes all identity fields and readiness together.
        val replacement = initial.copy(
            familyId = "family-b", membershipId = "member-b", deviceId = "device-b",
            role = FamilyRole.Member, familyName = "family b", serverHost = "other.example.test",
            accessToken = "", refreshToken = "refresh-only-b", pullCursor = 0,
            pullGeneration = "checkpoint-b",
        )
        rig.preferences.saveSession(replacement)
        runCurrent()
        assertThat(observations.last()).isEqualTo(replacement.toPresentation())
        assertThat(observations.last().isJoined).isTrue()
        rig.preferences.updateCursor(75, "checkpoint-b")
        runCurrent()
        assertThat(observations.last()).isEqualTo(replacement.toPresentation())

        rig.preferences.saveSession(SyncSession(deviceId = "local-device"))
        runCurrent()
        assertThat(observations.last().familyId).isEmpty()
        assertThat(observations.last().isJoined).isFalse()
        assertThat(observations.last().reauthRequired).isFalse()
        assertThat(observations).hasSize(5)
    }

    @Test
    fun disabledPortUsesSameProjectionWithoutInventingAJoinedIdentity() = runTest {
        val port = NoOpSyncPort()
        assertThat(port.sessionPresentation().first()).isEqualTo(port.session().first().toPresentation())
        assertThat(port.sessionPresentation().first().isJoined).isFalse()
    }
}
