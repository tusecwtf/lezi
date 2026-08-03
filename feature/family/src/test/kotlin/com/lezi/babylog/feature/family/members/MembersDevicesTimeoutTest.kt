package com.lezi.babylog.feature.family.members

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MembersDevicesTimeoutTest {
    @Test
    fun hungRosterRefreshKeepsPriorMembersAndEndsWithRetryableTimeout() = runTest {
        val priorMember = FamilyMember(
            displayName = "妈妈",
            role = FamilyRole.Owner,
            isSelf = true,
            membershipId = "owner-membership",
        )
        val sync = object : SyncPort by NoOpSyncPort() {
            override fun session(): Flow<SyncSession> = flowOf(
                SyncSession(
                    familyId = "family-a",
                    accessToken = "access",
                    role = FamilyRole.Owner,
                    serverHost = "nas.home",
                    membershipId = "owner-membership",
                ),
            )

            override suspend fun listFamilyMembers(): Result<List<FamilyMember>> {
                awaitCancellation()
            }
        }
        val previous = FamilyMembersState(
            familyId = "family-a",
            members = listOf(priorMember),
            loaded = true,
        )

        val result = MembersDevicesActions(
            sync = sync,
            timeoutMillis = 1_000L,
        ).refreshMembersNow(previous, showErrors = true)

        assertThat(result.loading).isFalse()
        assertThat(result.loaded).isTrue()
        assertThat(result.members).containsExactly(priorMember)
        assertThat(result.error).isEqualTo("读取成员与设备超时，请检查家庭网络后重试")
    }
}
