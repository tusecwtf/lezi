package com.lezi.babylog.feature.settings.calendar

import com.lezi.babylog.core.model.ConflictNotAdoptedAudit
import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Test

class ConflictCountGroupingTest {
    @Test
    fun oneAuditListBecomesPerPlanCounts() {
        val audits = listOf(
            audit(plan = "plan-a", candidate = "c1"),
            audit(plan = "plan-a", candidate = "c2"),
            audit(plan = "plan-b", candidate = "c3"),
            audit(plan = "plan-other", candidate = "c4"),
        )

        assertEquals(
            mapOf("plan-a" to 2, "plan-b" to 1),
            conflictCountsByPlanUuid(audits, setOf("plan-a", "plan-b", "plan-empty")),
        )
    }

    @Test
    fun emptyInputsStayEmpty() {
        assertEquals(emptyMap<String, Int>(), conflictCountsByPlanUuid(emptyList(), setOf("plan-a")))
        assertEquals(
            emptyMap<String, Int>(),
            conflictCountsByPlanUuid(listOf(audit(plan = "plan-a", candidate = "c1")), emptySet()),
        )
    }

    private fun audit(plan: String, candidate: String) = ConflictNotAdoptedAudit(
        candidateClientUuid = candidate,
        carePlanClientUuid = plan,
        carePlanId = 1L,
        babyId = 1L,
        type = RecordType.NURSING,
        typeLabel = "亲喂",
        note = null,
        actualTimestamp = null,
        confirmedAt = 1L,
        submitterMembershipId = "m1",
        submitterRole = "member",
        submitterDisplayName = "妈妈",
        notAdoptedReason = "未采纳",
        sourceRecordClientUuid = "src",
        sourceRecordId = null,
    )
}
