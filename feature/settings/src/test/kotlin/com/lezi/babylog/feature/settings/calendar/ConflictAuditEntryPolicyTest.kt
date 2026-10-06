package com.lezi.babylog.feature.settings.calendar

import com.lezi.babylog.core.model.CarePlanStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure entry-policy for calendar conflict audit (ticket 27).
 * Domain still re-checks admin on every list/detail/convert call.
 */
class ConflictAuditEntryPolicyTest {

    @Test
    fun onlyAdminCompletedWithConflictsOpensAudit() {
        assertTrue(
            shouldOpenConflictAudit(
                isFamilyAdmin = true,
                effective = CarePlanStatus.COMPLETED,
                conflictCount = 2,
            ),
        )
        assertFalse(
            shouldOpenConflictAudit(
                isFamilyAdmin = false,
                effective = CarePlanStatus.COMPLETED,
                conflictCount = 2,
            ),
        )
        assertFalse(
            shouldOpenConflictAudit(
                isFamilyAdmin = true,
                effective = CarePlanStatus.PENDING,
                conflictCount = 2,
            ),
        )
        assertFalse(
            shouldOpenConflictAudit(
                isFamilyAdmin = true,
                effective = CarePlanStatus.COMPLETED,
                conflictCount = 0,
            ),
        )
    }
}
