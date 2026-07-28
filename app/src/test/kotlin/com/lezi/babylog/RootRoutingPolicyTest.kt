package com.lezi.babylog

import com.lezi.babylog.sync.FamilyRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootRoutingPolicyTest {
    @Test
    fun joinedMemberWithoutAuthorityBabyUsesFamilyShellInsteadOfOnboarding() {
        assertFalse(shouldShowOnboarding(hasBaby = false, familyRole = FamilyRole.Member))
    }

    @Test
    fun unjoinedFirstRunStillUsesOnboarding() {
        assertTrue(shouldShowOnboarding(hasBaby = false, familyRole = FamilyRole.None))
        assertFalse(shouldShowOnboarding(hasBaby = true, familyRole = FamilyRole.None))
    }
}
