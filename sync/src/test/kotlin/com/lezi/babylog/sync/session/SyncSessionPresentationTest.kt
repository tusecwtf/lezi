package com.lezi.babylog.sync.session

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.CreateFamilyResult
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.MemberLoginCheckResult
import com.lezi.babylog.sync.MemberLoginQrResult
import com.lezi.babylog.sync.OwnerLoginResult
import com.lezi.babylog.sync.SyncPort
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncSessionPresentationTest {
    @Test
    fun readinessAlwaysMatchesOwnerIncludingRefreshOnlyAndRetainedReauth() {
        val joined = joinedSession()
        val cases = listOf(
            joined,
            joined.copy(accessToken = "", accessExpiresAtEpochSeconds = 0),
            joined.copy(refreshToken = ""),
            joined.copy(accessToken = "", refreshToken = ""),
            joined.copy(reauthRequired = true),
            joined.copy(accessToken = "", refreshToken = "", reauthRequired = true),
            joined.copy(serverHost = ""),
            joined.copy(familyId = ""),
            SyncSession(),
        )
        for (owner in cases) {
            val presentation = owner.toPresentation()
            assertThat(presentation.isJoined).isEqualTo(owner.isJoined)
            assertThat(presentation.reauthRequired).isEqualTo(owner.reauthRequired)
            assertThat(presentation.familyId).isEqualTo(owner.familyId)
            assertThat(presentation.membershipId).isEqualTo(owner.membershipId)
            assertThat(presentation.deviceId).isEqualTo(owner.deviceId)
            assertThat(presentation.role).isEqualTo(owner.role)
            assertThat(presentation.baseUrl).isEqualTo(owner.baseUrl)
        }
        assertThat(cases[1].toPresentation().isJoined).isTrue()
        assertThat(cases[4].toPresentation().isJoined).isFalse()
    }

    @Test
    fun credentialsAndCheckpointChurnDoNotChangePresentationButVisibleFactsDo() {
        val joined = joinedSession()
        val original = joined.toPresentation()
        assertThat(
            joined.copy(
                accessToken = "rotated-access", refreshToken = "rotated-refresh",
                accessExpiresAtEpochSeconds = 9_000, pullCursor = 500,
                pullGeneration = "next-checkpoint",
            ).toPresentation(),
        ).isEqualTo(original)
        assertThat(joined.copy(familyName = "renamed").toPresentation()).isNotEqualTo(original)
        assertThat(joined.copy(lastSuccessAt = 100).toPresentation()).isNotEqualTo(original)
        assertThat(joined.copy(membershipId = "replacement").toPresentation()).isNotEqualTo(original)
    }

    @Test
    fun repeatedCredentialAndCheckpointEmissionsStayVisiblyEquivalentWithExplicitCopyCost() = runTest {
        val provenance = CountingAcknowledgements(
            (0 until 64).map { CreatorAcknowledgementRef("record", "record-$it") }.toSet(),
        )
        val owner = joinedSession().copy(pendingCreatorAcknowledgements = provenance)
        val emissions = 1_000
        val presentations = flow {
            repeat(emissions) { index ->
                emit(owner.copy(
                    accessToken = "rotated-$index", refreshToken = "refresh-$index",
                    pullCursor = index.toLong(), pullGeneration = "generation-$index",
                ))
            }
        }.map(SyncSession::toPresentation).distinctUntilChanged().toList()

        assertThat(presentations).hasSize(1)
        assertThat(presentations.single().isJoined).isTrue()
        assertThat(presentations.single().pendingCreatorAcknowledgements).hasSize(64)
        // Distinct suppresses downstream UI emissions, not the owner-snapshot copy preceding it.
        assertThat(provenance.iterations).isEqualTo(emissions)
    }

    @Test
    fun provenanceSnapshotIsDetachedAndCannotMutateOwner() {
        val ref = CreatorAcknowledgementRef("record", "record-a")
        val mutableOwnerRefs = linkedSetOf(ref)
        val owner = joinedSession().copy(pendingCreatorAcknowledgements = mutableOwnerRefs)
        val presentation = owner.toPresentation()
        mutableOwnerRefs.clear()

        assertThat(presentation.pendingCreatorAcknowledgements).containsExactly(ref)
        assertThat(presentation.isCreatorAcknowledgementPending(" record ", " record-a ")).isTrue()
        assertThrows(UnsupportedOperationException::class.java) {
            (presentation.pendingCreatorAcknowledgements as MutableSet<CreatorAcknowledgementRef>).clear()
        }
        assertThat(owner.pendingCreatorAcknowledgements).isEmpty()
    }

    @Test
    fun resultAdaptersUseSameTokenFreeSnapshotAndKeepLegacySourceAvailable() {
        val owner = joinedSession()
        val expected = owner.toPresentation()
        val created = CreateFamilyResult(owner, reclaimed = false)
        val login = OwnerLoginResult(owner, InitialFamilyDataRecovery.Complete)
        val qr = MemberLoginQrResult(owner, InitialFamilyDataRecovery.Complete)
        val joined = MemberLoginCheckResult.Joined(owner, InitialFamilyDataRecovery.Complete)

        assertThat(created.sessionPresentation).isEqualTo(expected)
        assertThat(login.sessionPresentation).isEqualTo(expected)
        assertThat(qr.sessionPresentation).isEqualTo(expected)
        assertThat(joined.sessionPresentation).isEqualTo(expected)
        assertThat(created.session).isSameInstanceAs(owner)
    }

    @Test
    fun publicPresentationApiHasOnlyReadonlyNonSecretFields() {
        val type = SyncSessionPresentation::class.java
        val fields = type.declaredFields.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        assertThat(fields.map { it.name }).containsExactly(
            "familyId", "deviceId", "role", "membershipId", "familyName", "isJoined",
            "reauthRequired", "serverHost", "serverPort", "serverScheme", "baseUrl",
            "lastSuccessAt", "pendingCreatorAcknowledgements",
        )
        assertThat(fields.all { Modifier.isFinal(it.modifiers) }).isTrue()
        assertThat(type.methods.none { it.name.startsWith("set") }).isTrue()
        assertThat(type.methods.none { it.returnType == SyncSession::class.java }).isTrue()
        assertThat(type.methods.none {
            it.name.contains("token", ignoreCase = true) ||
                it.name.contains("credential", ignoreCase = true)
        }).isTrue()
        val portResult = SyncPort::class.java.getMethod("sessionPresentation")
            .genericReturnType as ParameterizedType
        assertThat(portResult.actualTypeArguments.single()).isEqualTo(type)
        val text = joinedSession().toPresentation().toString()
        assertThat(text).doesNotContain("secret-access")
        assertThat(text).doesNotContain("secret-refresh")
    }

    private class CountingAcknowledgements(
        private val values: Set<CreatorAcknowledgementRef>,
    ) : AbstractSet<CreatorAcknowledgementRef>() {
        var iterations = 0
            private set
        override val size: Int get() = values.size
        override fun iterator(): Iterator<CreatorAcknowledgementRef> {
            iterations++
            return values.iterator()
        }
    }

    private fun joinedSession() = SyncSession(
        familyId = "family-a", membershipId = "member-a", deviceId = "device-a",
        role = FamilyRole.Owner, familyName = "family name", serverHost = "nas.example.test",
        accessToken = "secret-access", refreshToken = "secret-refresh",
        accessExpiresAtEpochSeconds = 100, pullCursor = 5, pullGeneration = "generation-a",
    )
}
