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

    @Test
    fun layoutEditorReplacesDateAndPrimaryNavigationChrome() {
        val normal = rootChromeVisibility(route = "log", logLayoutEditActive = false)
        assertTrue(normal.showTopBar)
        assertTrue(normal.showBottomBar)
        assertFalse(normal.preserveBottomBarExtent)

        val editing = rootChromeVisibility(route = "log", logLayoutEditActive = true)
        assertFalse(editing.showTopBar)
        assertFalse(editing.showBottomBar)
        assertTrue(editing.preserveBottomBarExtent)
    }

    @Test
    fun staleEditorSignalCannotHideChromeOutsideLogRoute() {
        val settings = rootChromeVisibility(
            route = "settings",
            logLayoutEditActive = true,
        )

        assertTrue(settings.showTopBar)
        assertTrue(settings.showBottomBar)
        assertFalse(settings.preserveBottomBarExtent)
    }
}
