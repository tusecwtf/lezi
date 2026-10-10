package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.ShallowSyncLine
import com.lezi.babylog.sync.session.ShallowSyncState
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.SyncSessionPresentation
import com.lezi.babylog.sync.session.toPresentation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SyncPortShallowPresentationTest {
    @Test
    fun defaultShallowStatusUsesPresentationWithoutReadingLegacyCredentials() = runTest {
        val owner = SyncSession(
            familyId = "family-a", membershipId = "member-a", deviceId = "device-a",
            role = FamilyRole.Member, serverHost = "nas.example.test",
            accessToken = "access", refreshToken = "refresh",
        )
        val cases = listOf(
            owner to ShallowSyncState.WaitingForFirstSync,
            owner.copy(accessToken = "") to ShallowSyncState.WaitingForFirstSync,
            owner.copy(refreshToken = "") to ShallowSyncState.WaitingForFirstSync,
            owner.copy(accessToken = "", refreshToken = "") to ShallowSyncState.Unjoined,
            owner.copy(reauthRequired = true) to ShallowSyncState.ReauthRequired,
            owner.copy(accessToken = "", refreshToken = "", reauthRequired = true) to
                ShallowSyncState.ReauthRequired,
            owner.copy(serverHost = "") to ShallowSyncState.Unjoined,
            owner.copy(familyId = "") to ShallowSyncState.Unjoined,
            SyncSession() to ShallowSyncState.Unjoined,
        )

        for ((session, expected) in cases) {
            assertThat(presentationOnlyPort(session).shallowStatus().first().state)
                .isEqualTo(expected)
        }
    }

    @Test
    fun defaultShallowStatusKeepsPendingCountFromTheSamePresentationPath() = runTest {
        val owner = SyncSession(
            familyId = "family-a", membershipId = "member-a", deviceId = "device-a",
            role = FamilyRole.Owner, serverHost = "nas.example.test",
            refreshToken = "refresh", lastSuccessAt = 1_000L,
        )

        val line = presentationOnlyPort(owner, pendingCount = 4).shallowStatus().first()

        assertThat(line).isEqualTo(
            ShallowSyncLine(
                state = ShallowSyncState.Pending,
                text = "已保存在本机 · 待同步 4 项",
                pendingCount = 4,
            ),
        )
    }

    private fun presentationOnlyPort(owner: SyncSession, pendingCount: Int = 0): SyncPort =
        object : SyncPort by NoOpSyncPort() {
            override fun status(): Flow<SyncStatus> = flowOf(SyncStatus.Idle)
            override fun sessionPresentation(): Flow<SyncSessionPresentation> =
                flowOf(owner.toPresentation())
            @Deprecated("Use sessionPresentation() outside sync internals")
            override fun session(): Flow<SyncSession> =
                error("Product status must not read credential-bearing session()")
            override fun pendingPublishCount(): Flow<Int> = flowOf(pendingCount)
            override fun shallowStatus(): Flow<ShallowSyncLine> = super<SyncPort>.shallowStatus()
        }
}
