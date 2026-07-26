package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FamilyUploaderResolutionTest {
    private val members = listOf(
        UploaderMemberRef(
            "device-a",
            "妈妈",
            FamilyRole.Owner,
            isSelf = true,
            membershipId = "membership-a",
        ),
        UploaderMemberRef(
            "device-b",
            "爸爸",
            FamilyRole.Member,
            isSelf = false,
            membershipId = "membership-b",
        ),
        UploaderMemberRef("device-c", null, FamilyRole.Member, isSelf = false),
        UploaderMemberRef("device-d", "我（本机）", FamilyRole.Owner, isSelf = false),
    )

    @Test
    fun membershipAuthorWinsOverConflictingLegacyDeviceAndIdentifiesSelf() {
        assertThat(
            resolveRecordUploaderLabel(
                createdByDeviceId = "device-b",
                selfDeviceId = "device-a",
                isFamilyJoined = true,
                members = members,
                createdByMembershipId = "membership-a",
                selfMembershipId = "membership-a",
            ),
        ).isNull()
        assertThat(
            resolveRecordUploaderLabel(
                createdByDeviceId = "device-a",
                selfDeviceId = "device-a",
                isFamilyJoined = true,
                members = members,
                createdByMembershipId = "membership-b",
                selfMembershipId = "membership-a",
            ),
        ).isEqualTo("爸爸")
    }

    @Test
    fun hidesUploaderWhenNotJoinedOrSelf() {
        assertThat(
            resolveRecordUploaderLabel("device-b", "device-a", isFamilyJoined = false, members),
        ).isNull()
        assertThat(
            resolveRecordUploaderLabel("device-a", "device-a", isFamilyJoined = true, members),
        ).isNull()
        assertThat(
            resolveRecordUploaderLabel("device-b", "device-b", isFamilyJoined = true, members),
        ).isNull()
    }

    @Test
    fun resolvesCurrentMembershipNameForOthers() {
        assertThat(
            resolveRecordUploaderLabel("device-b", "device-a", isFamilyJoined = true, members),
        ).isEqualTo("爸爸")
    }

    @Test
    fun fallsBackWithoutLeakingDeviceIdsOrLocalPlaceholder() {
        assertThat(
            resolveRecordUploaderLabel("device-c", "device-a", isFamilyJoined = true, members),
        ).isEqualTo("家庭成员")
        assertThat(
            resolveRecordUploaderLabel("device-d", "device-a", isFamilyJoined = true, members),
        ).isEqualTo("家庭管理员")
        assertThat(
            resolveRecordUploaderLabel("unknown", "device-a", isFamilyJoined = true, members),
        ).isEqualTo("家人")
        assertThat(
            resolveRecordUploaderLabel(null, "device-a", isFamilyJoined = true, members),
        ).isEqualTo("家人")
        val label = resolveRecordUploaderLabel(
            "device-c",
            "device-a",
            isFamilyJoined = true,
            members,
        )
        assertThat(label).doesNotContain("device")
        assertThat(label).isNotEqualTo("我（本机）")
    }

    @Test
    fun familyMemberMapsToUploaderRefWhenDeviceIdPresent() {
        assertThat(
            FamilyMember(
                "妈妈",
                FamilyRole.Owner,
                isSelf = true,
                deviceId = "d1",
                membershipId = "m1",
            ).toUploaderRef(),
        ).isEqualTo(
            UploaderMemberRef(
                "d1",
                "妈妈",
                FamilyRole.Owner,
                isSelf = true,
                membershipId = "m1",
            ),
        )
        assertThat(
            FamilyMember(
                "爸爸",
                FamilyRole.Member,
                isSelf = false,
                deviceId = null,
                membershipId = "m2",
            ).toUploaderRef(),
        ).isEqualTo(
            UploaderMemberRef(
                deviceId = null,
                displayName = "爸爸",
                role = FamilyRole.Member,
                membershipId = "m2",
            ),
        )
    }
}
