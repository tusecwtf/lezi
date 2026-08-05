package com.lezi.gf.care

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.kernel.GfError
import com.lezi.gf.kernel.GfResult
import org.junit.Test

/** G7 domain ACL: member cannot edit/delete others; owner can; deleted author → 家人. */
class RecordAclTest {
    @Test
    fun memberForbiddenOnOthersRecord() {
        val care = CareService(selfMembershipId = { "mem-b" }, isOwner = { false })
        care.store().putRecord(
            CareRecord(
                clientUuid = "r1",
                babyClientUuid = "b",
                typeKey = "pee",
                timestampMs = 1,
                createdByMembershipId = "mem-a",
            ),
        )
        val del = care.deleteRecord("r1", true)
        assertThat(del).isInstanceOf(GfResult.Err::class.java)
        assertThat((del as GfResult.Err).error).isInstanceOf(GfError.Forbidden::class.java)
        val edit = care.editRecord("r1", note = "x")
        assertThat(edit).isInstanceOf(GfResult.Err::class.java)
    }

    @Test
    fun ownerCanManage() {
        val care = CareService(selfMembershipId = { "owner" }, isOwner = { true })
        care.store().putRecord(
            CareRecord(
                clientUuid = "r1",
                babyClientUuid = "b",
                typeKey = "pee",
                timestampMs = 1,
                createdByMembershipId = "mem-a",
            ),
        )
        assertThat(care.deleteRecord("r1", true)).isInstanceOf(GfResult.Ok::class.java)
    }

    @Test
    fun authorLabelUses家人WhenMembershipMissing() {
        val care = CareService()
        val r = CareRecord(
            clientUuid = "r",
            babyClientUuid = "b",
            typeKey = "pee",
            timestampMs = 1,
            createdByMembershipId = "gone",
        )
        assertThat(care.authorDisplayName(r, emptyMap(), "self")).isEqualTo("家人")
        assertThat(care.authorDisplayName(r, mapOf("gone" to "妈妈"), "self")).isEqualTo("妈妈")
        assertThat(care.authorDisplayName(r.copy(createdByMembershipId = "self"), emptyMap(), "self")).isNull()
    }

    @Test
    fun nonAdoptedExcludedFromTimelineAndOwnerCanConvert() {
        val care = CareService(isOwner = { true })
        care.keepNonAdopted(
            CareRecord(
                clientUuid = "na",
                babyClientUuid = "b",
                typeKey = "nursing",
                timestampMs = 1,
                isNonAdoptedFulfill = true,
            ),
        )
        assertThat(care.normalTimelineExcludesNonAdopted()).isTrue()
        val converted = care.convertNonAdoptedToIndependent("na")
        assertThat(converted).isInstanceOf(GfResult.Ok::class.java)
        assertThat((converted as GfResult.Ok).value.isNonAdoptedFulfill).isFalse()
    }
}
