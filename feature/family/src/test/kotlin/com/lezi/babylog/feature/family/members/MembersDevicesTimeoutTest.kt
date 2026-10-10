package com.lezi.babylog.feature.family.members

import kotlinx.coroutines.flow.map
import com.lezi.babylog.sync.session.toPresentation
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.failure.FailureCategory
import com.lezi.babylog.core.common.failure.failureExplanation
import com.lezi.babylog.feature.family.components.familyFailureKind
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.backend.deadline.FamilyHttpException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MembersDevicesTimeoutTest {
    @Test
    fun rosterTransportFailureKeepsPriorMembersAndUsesTheTransportKind() = runTest {
        val priorMember = FamilyMember(
            displayName = "妈妈",
            role = FamilyRole.Owner,
            isSelf = true,
            membershipId = "owner-membership",
        )
        val timedOut = FamilyHttpException(FamilyHttpFailureKind.AddressNotFound)
        val sync = object : SyncPort by NoOpSyncPort() {
            override fun sessionPresentation() = session().map { it.toPresentation() }
            override fun session(): Flow<SyncSession> = flowOf(
                SyncSession(
                    familyId = "family-a",
                    accessToken = "access",
                    role = FamilyRole.Owner,
                    serverHost = "nas.home",
                    membershipId = "owner-membership",
                ),
            )

            override suspend fun listFamilyMembers(): Result<List<FamilyMember>> =
                Result.failure(timedOut)
        }
        val previous = FamilyMembersState(
            familyId = "family-a",
            members = listOf(priorMember),
            loaded = true,
        )

        val result = MembersDevicesActions(sync).refreshMembersNow(previous, showErrors = true)

        assertThat(result.loading).isFalse()
        assertThat(result.loaded).isTrue()
        assertThat(result.members).containsExactly(priorMember)
        assertThat(result.error).isNull()
        assertThat(result.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.AddressNotFound)
        assertThat(familyFailureKind(timedOut))
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.AddressNotFound)
        assertThat(familyFailureKind(timedOut))
            .isNotEqualTo(com.lezi.babylog.core.common.failure.FailureKind.ResponseTimedOut)
    }

    @Test
    fun approveBusyFamilyMapsHouseholdSyncingNotGenericRetry() = runTest {
        val sync = object : SyncPort by NoOpSyncPort() {
            override suspend fun approveNewMemberLogin(requestId: String): Result<Unit> =
                Result.failure(FamilyHttpException(FamilyHttpFailureKind.HouseholdSyncing))
        }

        val result = MembersDevicesActions(sync)
            .approveNewMemberLogin("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

        assertThat(result.isFailure).isTrue()
        assertThat(familyFailureKind(result.exceptionOrNull()!!))
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.HouseholdSyncing)
        assertThat(
            failureExplanation(
                com.lezi.babylog.core.common.failure.FailureKind.HouseholdSyncing,
            ).title,
        ).isEqualTo("家里正在同步")
    }

    @Test
    fun approveRejectBindAndLeaveKeepTheTransportKind() = runTest {
        val timedOut = FamilyHttpException(FamilyHttpFailureKind.SendStalled)
        val sync = object : SyncPort by NoOpSyncPort() {
            override suspend fun approveNewMemberLogin(requestId: String): Result<Unit> =
                Result.failure(timedOut)

            override suspend fun rejectMemberLogin(requestId: String): Result<Unit> =
                Result.failure(timedOut)

            override suspend fun bindExistingMemberLogin(
                requestId: String,
                membershipId: String,
            ): Result<Unit> = Result.failure(timedOut)

            override suspend fun leave(): Result<Unit> = Result.failure(timedOut)

            override suspend fun createMemberLoginQrCode(
                membershipId: String,
            ): Result<com.lezi.babylog.sync.qr.MemberLoginQrCode> = Result.failure(timedOut)

            override suspend fun renameFamily(familyName: String?): Result<Unit> =
                Result.failure(timedOut)

            override suspend fun addFamilyMember(
                displayName: String,
            ): Result<com.lezi.babylog.sync.FamilyMember> = Result.failure(timedOut)

            override suspend fun deleteFamily(
                familyName: String,
                rootPassword: String,
            ): Result<Unit> = Result.failure(timedOut)
        }
        val actions = MembersDevicesActions(sync)

        val approve = actions.approveNewMemberLogin("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val reject = actions.runWithUiTimeout { sync.rejectMemberLogin("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa") }
        val bind = actions.runWithUiTimeout {
            sync.bindExistingMemberLogin(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "membership-1",
            )
        }
        val leave = actions.runWithUiTimeout { sync.leave() }
        val qr = actions.runWithUiTimeout { sync.createMemberLoginQrCode("membership-1") }
        val rename = actions.runWithUiTimeout { sync.renameFamily("乐乐一家") }
        val add = actions.runWithUiTimeout { sync.addFamilyMember("奶奶") }
        val delete = actions.runWithUiTimeout { sync.deleteFamily("乐乐一家", "root") }

        listOf(approve, reject, bind, leave, qr, rename, add, delete).forEach { result ->
            assertThat(result.isFailure).isTrue()
            assertThat((result.exceptionOrNull() as FamilyHttpException).kind)
                .isEqualTo(FamilyHttpFailureKind.SendStalled)
            val catalog = failureExplanation(familyFailureKind(result.exceptionOrNull()!!)!!)
            assertThat(catalog.category).isEqualTo(FailureCategory.Network)
            assertThat(catalog.dialogTitle).contains("网络问题")
        }
    }
}
