package com.lezi.babylog

import androidx.compose.ui.unit.dp
import com.lezi.babylog.feature.log.dock.quickDockSnackbarBottomInset
import com.lezi.babylog.sync.FamilyRole
import org.junit.Assert.assertEquals
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

    @Test
    fun snackbarClearsQuickDockOnlyWhileEverydayLogDockIsVisible() {
        assertEquals(
            quickDockSnackbarBottomInset,
            rootSnackbarBottomInset(route = "log", logLayoutEditActive = false),
        )
        assertEquals(
            0.dp,
            rootSnackbarBottomInset(route = "log", logLayoutEditActive = true),
        )

        listOf("summary", "growth", "family", "settings", "timer", "search", "export", "calendar")
            .forEach { route ->
                assertEquals(
                    "$route must not reserve quick-dock space",
                    0.dp,
                    rootSnackbarBottomInset(route = route, logLayoutEditActive = false),
                )
            }
    }
}
