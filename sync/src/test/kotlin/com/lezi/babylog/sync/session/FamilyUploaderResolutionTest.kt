package com.lezi.babylog.sync.session
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import com.lezi.babylog.sync.FamilyMember

class FamilyUploaderResolutionTest {
    private val members = listOf(
        UploaderMemberRef(
            displayName = "妈妈",
            role = FamilyRole.Owner,
            isSelf = true,
            membershipId = "membership-a",
        ),
        UploaderMemberRef(
            displayName = "爸爸",
            role = FamilyRole.Member,
            isSelf = false,
            membershipId = "membership-b",
        ),
    )

    @Test
    fun membershipAuthorIdentifiesSelfAndPeer() {
        assertThat(
            resolveRecordUploaderLabel(
                isFamilyJoined = true,
                members = members,
                createdByMembershipId = "membership-a",
                selfMembershipId = "membership-a",
            ),
        ).isNull()
        assertThat(
            resolveRecordUploaderLabel(
                isFamilyJoined = true,
                members = members,
                createdByMembershipId = "membership-b",
                selfMembershipId = "membership-a",
            ),
        ).isEqualTo("爸爸")
    }

    @Test
    fun hidesUploaderWhenNotJoined() {
        assertThat(
            resolveRecordUploaderLabel(
                isFamilyJoined = false,
                members = members,
                createdByMembershipId = "membership-b",
            ),
        ).isNull()
    }

    @Test
    fun unresolvedOrMissingMembershipFallsBackWithoutLeakingIdentifiers() {
        assertThat(
            resolveRecordUploaderLabel(
                isFamilyJoined = true,
                members = members,
                createdByMembershipId = "unknown-membership",
            ),
        ).isEqualTo("家人")
        assertThat(
            resolveRecordUploaderLabel(
                isFamilyJoined = true,
                members = members,
                createdByMembershipId = null,
            ),
        ).isEqualTo("家人")
    }

    @Test
    fun familyMemberMapsToUploaderRefByMembershipOnly() {
        val ref = FamilyMember(
            "妈妈",
            FamilyRole.Owner,
            isSelf = true,
            membershipId = "m1",
        ).toUploaderRef()

        assertThat(ref).isEqualTo(
            UploaderMemberRef(
                displayName = "妈妈",
                role = FamilyRole.Owner,
                isSelf = true,
                membershipId = "m1",
            ),
        )
        assertThat(runCatching {
            FamilyMember("爸爸", FamilyRole.Member, isSelf = false, membershipId = "")
        }.isFailure).isTrue()
    }
}
