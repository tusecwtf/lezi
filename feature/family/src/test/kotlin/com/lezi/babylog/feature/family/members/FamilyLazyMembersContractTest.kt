package com.lezi.babylog.feature.family.members

import com.lezi.babylog.sync.FamilyDevice
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.session.FamilyRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 09 (ui-drawing-polish): family members/devices lazy roster flatten.
 *
 * Lazy composition (LazyColumn / keys / animateItem) is implementation on the
 * members sheet — not gated by product-less source-layout StructureTests
 * (AGENTS.md / tech.md §2.1). Overflow/semantics stay on
 * [com.lezi.babylog.feature.family.FamilyMembersDevicesPageDeviceTest].
 * Domain revoke/remove stays on host/policy suites.
 */
class FamilyLazyMembersContractTest {

    @Test
    fun `owner roster flattens members then devices with stable keys`() {
        val now = 1_700_000_000L
        val owner = FamilyMember(
            displayName = "管理员",
            role = FamilyRole.Owner,
            isSelf = true,
            membershipId = "m-owner",
            devices = listOf(
                FamilyDevice("dev-owner-phone", "我的手机", now, isCurrent = true),
                FamilyDevice("dev-owner-pad", "家庭平板", now - 90, isCurrent = false),
            ),
        )
        val mom = FamilyMember(
            displayName = "妈妈",
            role = FamilyRole.Member,
            isSelf = false,
            membershipId = "m-mom",
            devices = listOf(
                FamilyDevice("dev-mom-phone", "妈妈手机", now - 86_400, isCurrent = false),
            ),
        )
        val grandma = FamilyMember(
            displayName = "奶奶",
            role = FamilyRole.Member,
            isSelf = false,
            membershipId = "m-grandma",
            devices = emptyList(),
        )

        val items = familyRosterLazyItems(
            members = listOf(owner, mom, grandma),
            viewerIsOwner = true,
        )

        assertEquals(
            listOf(
                "member:m-owner",
                "device:dev-owner-phone",
                "device:dev-owner-pad",
                "member:m-mom",
                "device:dev-mom-phone",
                "member:m-grandma",
                "empty_devices:m-grandma",
            ),
            items.map(FamilyRosterLazyItem::key),
        )
        assertEquals(
            listOf(
                "member_row",
                "device_row",
                "device_row",
                "member_row",
                "device_row",
                "member_row",
                "empty_devices",
            ),
            items.map(FamilyRosterLazyItem::contentType),
        )

        val ownerPhone = items.filterIsInstance<FamilyRosterLazyItem.Device>()
            .single { it.device.deviceId == "dev-owner-phone" }
        assertTrue(ownerPhone.memberIsSelf)
        val momPhone = items.filterIsInstance<FamilyRosterLazyItem.Device>()
            .single { it.device.deviceId == "dev-mom-phone" }
        assertFalse(momPhone.memberIsSelf)

        // Header projection strips devices so equality ignores device-list churn.
        items.filterIsInstance<FamilyRosterLazyItem.Member>().forEach { header ->
            assertNull(header.member.devices)
        }
    }

    @Test
    fun `owner authorized null devices coerce to empty_devices placeholder`() {
        val items = familyRosterLazyItems(
            members = listOf(
                FamilyMember(
                    displayName = "管理员",
                    role = FamilyRole.Owner,
                    isSelf = true,
                    membershipId = "m-owner",
                    devices = null,
                ),
            ),
            viewerIsOwner = true,
        )
        assertEquals(
            listOf("member:m-owner", "empty_devices:m-owner"),
            items.map(FamilyRosterLazyItem::key),
        )
    }

    @Test
    fun `ordinary member privacy omits other members device rows entirely`() {
        val now = 1_700_000_000L
        val owner = FamilyMember(
            displayName = "管理员",
            role = FamilyRole.Owner,
            isSelf = false,
            membershipId = "m-owner",
            devices = listOf(
                FamilyDevice("dev-owner-pad", "管理员平板", now, isCurrent = false),
            ),
        )
        val self = FamilyMember(
            displayName = "妈妈",
            role = FamilyRole.Member,
            isSelf = true,
            membershipId = "m-self",
            devices = listOf(
                FamilyDevice("dev-self-phone", "我的手机", now, isCurrent = true),
            ),
        )
        val other = FamilyMember(
            displayName = "奶奶",
            role = FamilyRole.Member,
            isSelf = false,
            membershipId = "m-other",
            devices = listOf(
                FamilyDevice("dev-other", "奶奶手机", now, isCurrent = false),
            ),
        )

        val items = familyRosterLazyItems(
            members = listOf(owner, self, other),
            viewerIsOwner = false,
        )

        assertEquals(
            listOf(
                "member:m-owner",
                "member:m-self",
                "device:dev-self-phone",
                "member:m-other",
            ),
            items.map(FamilyRosterLazyItem::key),
        )
        // Privacy: no empty-devices placeholder for unauthorized members.
        assertTrue(items.none { it is FamilyRosterLazyItem.EmptyDevices })
        assertTrue(items.none { it is FamilyRosterLazyItem.Device && it.device.deviceId == "dev-owner-pad" })
        assertTrue(items.none { it is FamilyRosterLazyItem.Device && it.device.deviceId == "dev-other" })

        val selfDevice = items.filterIsInstance<FamilyRosterLazyItem.Device>().single()
        assertTrue(selfDevice.memberIsSelf)
    }

    @Test
    fun `ordinary member self empty devices emit empty_devices placeholder`() {
        val items = familyRosterLazyItems(
            members = listOf(
                FamilyMember(
                    displayName = "管理员",
                    role = FamilyRole.Owner,
                    isSelf = false,
                    membershipId = "m-owner",
                    devices = listOf(
                        FamilyDevice("dev-owner", "管理员设备", 1_700_000_000L, isCurrent = false),
                    ),
                ),
                FamilyMember(
                    displayName = "妈妈",
                    role = FamilyRole.Member,
                    isSelf = true,
                    membershipId = "m-self",
                    devices = emptyList(),
                ),
            ),
            viewerIsOwner = false,
        )
        assertEquals(
            listOf(
                "member:m-owner",
                "member:m-self",
                "empty_devices:m-self",
            ),
            items.map(FamilyRosterLazyItem::key),
        )
    }

    @Test
    fun `empty member list yields empty roster items`() {
        assertEquals(
            emptyList<FamilyRosterLazyItem>(),
            familyRosterLazyItems(members = emptyList(), viewerIsOwner = true),
        )
    }

    @Test
    fun `action policy helpers match owner and self gates`() {
        val selfMember = FamilyMember("妈妈", FamilyRole.Member, isSelf = true, membershipId = "m-self")
        val otherMember = FamilyMember("爸爸", FamilyRole.Member, isSelf = false, membershipId = "m-other")
        val ownerMember = FamilyMember("管理员", FamilyRole.Owner, isSelf = false, membershipId = "m-owner")

        assertTrue(canCreateMemberLoginQr(viewerIsOwner = true, member = otherMember))
        assertFalse(canCreateMemberLoginQr(viewerIsOwner = true, member = ownerMember))
        assertFalse(canCreateMemberLoginQr(viewerIsOwner = false, member = otherMember))

        assertTrue(canRenameFamilyMemberRow(viewerIsOwner = true, member = otherMember))
        assertFalse(canRenameFamilyMemberRow(viewerIsOwner = true, member = selfMember))
        assertFalse(canRenameFamilyMemberRow(viewerIsOwner = false, member = otherMember))

        assertTrue(canRenameFamilyDeviceRow(viewerIsOwner = true, memberIsSelf = false))
        assertTrue(canRenameFamilyDeviceRow(viewerIsOwner = false, memberIsSelf = true))
        assertFalse(canRenameFamilyDeviceRow(viewerIsOwner = false, memberIsSelf = false))

        assertTrue(canRevokeFamilyDeviceRow(viewerIsOwner = true))
        assertFalse(canRevokeFamilyDeviceRow(viewerIsOwner = false))
    }
}
