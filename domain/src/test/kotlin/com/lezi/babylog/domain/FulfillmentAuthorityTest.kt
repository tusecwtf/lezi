package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.FulfillmentAdoptionStatus
import com.lezi.babylog.core.model.FulfillmentAuthority
import com.lezi.babylog.core.model.FulfillmentCandidateEvidence
import org.junit.Test

class FulfillmentAuthorityTest {

    @Test
    fun adminOutranksEarlierMemberConfirm() {
        val memberEarly = evidence("cand-m", "rec-m", confirmedAt = 100, role = "member")
        val ownerLate = evidence("cand-o", "rec-o", confirmedAt = 999, role = "owner")
        val resolution = FulfillmentAuthority.resolve(listOf(memberEarly, ownerLate))!!
        assertThat(resolution.winnerClientUuid).isEqualTo("cand-o")
        assertThat(resolution.winnerRecordClientUuid).isEqualTo("rec-o")
        assertThat(resolution.adoptionByCandidateUuid["cand-o"])
            .isEqualTo(FulfillmentAdoptionStatus.ADOPTED)
        assertThat(resolution.adoptionByCandidateUuid["cand-m"])
            .isEqualTo(FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED)
    }

    @Test
    fun earlierConfirmedAtWinsAmongPeers() {
        val a = evidence("cand-a", "rec-a", confirmedAt = 200, role = "member")
        val b = evidence("cand-b", "rec-b", confirmedAt = 100, role = "member")
        val winner = FulfillmentAuthority.selectWinner(listOf(a, b))!!
        assertThat(winner.clientUuid).isEqualTo("cand-b")
    }

    @Test
    fun uuidBreaksConfirmedAtTie() {
        val a = evidence("uuid-zzz", "rec-z", confirmedAt = 50, role = "member")
        val b = evidence("uuid-aaa", "rec-a", confirmedAt = 50, role = "member")
        val winner = FulfillmentAuthority.selectWinner(listOf(a, b))!!
        assertThat(winner.clientUuid).isEqualTo("uuid-aaa")
    }

    @Test
    fun arrivalOrderAndActualTimeDoNotAffectKeys() {
        // Pure function only sees role / confirmedAt / clientUuid — call order
        // and any actualTimestamp field (absent from evidence) cannot change result.
        val first = evidence("cand-1", "rec-1", confirmedAt = 300, role = "member")
        val second = evidence("cand-2", "rec-2", confirmedAt = 100, role = "member")
        val third = evidence("cand-3", "rec-3", confirmedAt = 200, role = "owner")
        val orders = listOf(
            listOf(first, second, third),
            listOf(third, first, second),
            listOf(second, third, first),
        )
        val winners = orders.map { FulfillmentAuthority.selectWinner(it)!!.clientUuid }.toSet()
        assertThat(winners).containsExactly("cand-3")
    }

    @Test
    fun emptyRoleIsNotAdmin() {
        assertThat(FulfillmentAuthority.isAdminRole("")).isFalse()
        assertThat(FulfillmentAuthority.isAdminRole("member")).isFalse()
        assertThat(FulfillmentAuthority.isAdminRole("owner")).isTrue()
        assertThat(FulfillmentAuthority.isAdminRole("OWNER")).isTrue()
        val local = evidence("local", "rec-l", confirmedAt = 1, role = "")
        val owner = evidence("owner", "rec-o", confirmedAt = 9_999, role = "owner")
        assertThat(FulfillmentAuthority.selectWinner(listOf(local, owner))!!.clientUuid)
            .isEqualTo("owner")
    }

    @Test
    fun singleCandidateIsAdopted() {
        val only = evidence("only", "rec", confirmedAt = 1, role = "member")
        val resolution = FulfillmentAuthority.resolve(listOf(only))!!
        assertThat(resolution.adoptionByCandidateUuid).containsExactly(
            "only",
            FulfillmentAdoptionStatus.ADOPTED,
        )
    }

    @Test
    fun emptySetResolvesNull() {
        assertThat(FulfillmentAuthority.resolve(emptyList())).isNull()
        assertThat(FulfillmentAuthority.selectWinner(emptyList())).isNull()
    }

    @Test
    fun resolveIsIdempotentForSameEvidence() {
        val set = listOf(
            evidence("a", "ra", confirmedAt = 10, role = "member"),
            evidence("b", "rb", confirmedAt = 20, role = "member"),
        )
        val first = FulfillmentAuthority.resolve(set)!!
        val second = FulfillmentAuthority.resolve(set.shuffled())!!
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun notAdoptedReasonMatchesComparisonKeys() {
        val member = evidence("m", "rm", confirmedAt = 10, role = "member")
        val owner = evidence("o", "ro", confirmedAt = 999, role = "owner")
        assertThat(FulfillmentAuthority.notAdoptedReason(member, owner))
            .isEqualTo("未采纳：另一履行由管理员确认")
        val late = evidence("late", "rl", confirmedAt = 200, role = "member")
        val early = evidence("early", "re", confirmedAt = 100, role = "member")
        assertThat(FulfillmentAuthority.notAdoptedReason(late, early))
            .isEqualTo("未采纳：另一履行确认时间更早")
        val z = evidence("uuid-zzz", "rz", confirmedAt = 50, role = "member")
        val a = evidence("uuid-aaa", "ra", confirmedAt = 50, role = "member")
        assertThat(FulfillmentAuthority.notAdoptedReason(z, a))
            .isEqualTo("未采纳：候选身份排序后落选")
    }

    @Test
    fun needsPlanRelinkWhenWinnerDiffers() {
        val resolution = FulfillmentAuthority.resolve(
            listOf(
                evidence("a", "rec-a", confirmedAt = 10, role = "member"),
                evidence("b", "rec-b", confirmedAt = 5, role = "member"),
            ),
        )!!
        assertThat(
            FulfillmentAuthority.needsPlanRelink(
                currentStatusStorageKey = "pending",
                currentFulfilledRecordClientUuid = null,
                currentFulfilledAt = null,
                resolution = resolution,
            ),
        ).isTrue()
        assertThat(
            FulfillmentAuthority.needsPlanRelink(
                currentStatusStorageKey = "completed",
                currentFulfilledRecordClientUuid = "rec-b",
                currentFulfilledAt = 5,
                resolution = resolution,
            ),
        ).isFalse()
        assertThat(
            FulfillmentAuthority.needsPlanRelink(
                currentStatusStorageKey = "completed",
                currentFulfilledRecordClientUuid = "rec-a",
                currentFulfilledAt = 10,
                resolution = resolution,
            ),
        ).isTrue()
    }

    @Test
    fun adoptionStatusPatchesOnlyChangedRows() {
        val resolution = FulfillmentAuthority.resolve(
            listOf(
                evidence("win", "rw", confirmedAt = 1, role = "owner"),
                evidence("lose", "rl", confirmedAt = 2, role = "member"),
            ),
        )!!
        val patches = FulfillmentAuthority.adoptionStatusPatches(
            liveClientUuidToStatus = mapOf(
                "win" to FulfillmentAdoptionStatus.ADOPTED,
                "lose" to "",
                "ghost" to FulfillmentAdoptionStatus.ADOPTED,
            ),
            resolution = resolution,
        )
        assertThat(patches).containsExactly(
            "lose",
            FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED,
        )
    }

    private fun evidence(
        clientUuid: String,
        recordClientUuid: String,
        confirmedAt: Long,
        role: String,
    ) = FulfillmentCandidateEvidence(
        clientUuid = clientUuid,
        recordClientUuid = recordClientUuid,
        confirmedAt = confirmedAt,
        submitterRole = role,
    )
}
