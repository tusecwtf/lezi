package com.lezi.babylog

import androidx.compose.ui.unit.dp
import com.lezi.babylog.feature.log.dock.quickDockSnackbarBottomInset
import com.lezi.babylog.sync.session.FamilyRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RootRoutingPolicyTest {
    @Test
    fun timelineCommitAndExternalSelectionShareOneDeduplicatedRootOwner() {
        val today = LocalDate.of(2026, 8, 8)
        val persisted = mutableListOf<LocalDate>()
        val owner = RootSelectedDateOwner(today, today, persisted::add)

        val timelineDay = today.minusDays(2)
        assertTrue(owner.select(timelineDay, today))
        assertEquals(timelineDay, owner.selectedDate.value)
        assertFalse(owner.select(timelineDay, today))

        val topBarDay = today.minusDays(1)
        assertTrue(owner.select(topBarDay, today))
        assertEquals(topBarDay, owner.selectedDate.value)
        assertEquals(listOf(today, timelineDay, topBarDay), persisted)
    }

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
