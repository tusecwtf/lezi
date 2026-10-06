package com.lezi.babylog

import androidx.compose.ui.unit.dp
import com.lezi.babylog.feature.log.dock.quickDockSnackbarBottomInset
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.sync.session.FamilyRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

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
    fun parkedOnOldTodayRollsSelectedDayToLiveToday() {
        val previous = LocalDate.of(2026, 8, 8)
        val live = LocalDate.of(2026, 8, 9)
        val snapshot = applyCalendarToday(live, previous, previous)
        assertEquals(live, snapshot.today)
        assertEquals(live, snapshot.selectedDate)
    }

    @Test
    fun browsingEarlierDayKeepsSelectedDayAcrossCalendarTodayRoll() {
        val previous = LocalDate.of(2026, 8, 8)
        val live = LocalDate.of(2026, 8, 9)
        val browsing = LocalDate.of(2026, 8, 7)
        val snapshot = applyCalendarToday(live, previous, browsing)
        assertEquals(live, snapshot.today)
        assertEquals(browsing, snapshot.selectedDate)
    }

    @Test
    fun futureSelectedDayStillClampsToLiveToday() {
        val previous = LocalDate.of(2026, 8, 8)
        val live = LocalDate.of(2026, 8, 9)
        val snapshot = applyCalendarToday(live, previous, live.plusDays(3))
        assertEquals(live, snapshot.today)
        assertEquals(live, snapshot.selectedDate)
    }

    @Test
    fun restoreWhenSelectedEqualsPersistedTodaySnapsToLiveToday() {
        val persisted = LocalDate.of(2026, 8, 8)
        val live = LocalDate.of(2026, 8, 9)
        assertEquals(
            live,
            restoreSelectedDate(
                restoredSelected = persisted,
                restoredToday = persisted,
                liveToday = live,
            ),
        )
    }

    @Test
    fun restoreHistoricalDayKeepsClampedSelectedDate() {
        val live = LocalDate.of(2026, 8, 9)
        val historical = LocalDate.of(2026, 8, 6)
        assertEquals(
            historical,
            restoreSelectedDate(
                restoredSelected = historical,
                restoredToday = LocalDate.of(2026, 8, 8),
                liveToday = live,
            ),
        )
    }

    @Test
    fun restoreWithoutPersistedTodayDoesNotTreatYesterdayAsParkedToday() {
        val live = LocalDate.of(2026, 8, 9)
        val yesterday = LocalDate.of(2026, 8, 8)
        assertEquals(
            yesterday,
            restoreSelectedDate(
                restoredSelected = yesterday,
                restoredToday = null,
                liveToday = live,
            ),
        )
    }

    @Test
    fun refreshThenSelectAcceptsLiveTodayThatWasClampedWhileStale() {
        val staleToday = LocalDate.of(2026, 8, 8)
        val liveToday = LocalDate.of(2026, 8, 9)
        val owner = RootSelectedDateOwner(staleToday, staleToday)
        assertEquals(staleToday, clampSelectedDate(liveToday, staleToday))
        val snapshot = applyCalendarToday(liveToday, staleToday, owner.selectedDate.value)
        assertTrue(owner.select(snapshot.selectedDate, snapshot.today))
        assertEquals(liveToday, owner.selectedDate.value)
    }

    @Test
    fun millisUntilNextLocalMidnightUsesFollowingStartOfDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        val noon = ZonedDateTime.of(2026, 8, 8, 12, 0, 0, 0, zone)
        assertEquals(
            Duration.between(noon, LocalDate.of(2026, 8, 9).atStartOfDay(zone)).toMillis(),
            millisUntilNextLocalMidnight(noon),
        )
        val justBefore = ZonedDateTime.of(2026, 8, 8, 23, 59, 59, 400_000_000, zone)
        assertTrue(millisUntilNextLocalMidnight(justBefore) >= 1_000L)
    }

    @Test
    fun joinedMemberWithoutAuthorityBabyUsesFamilyShellInsteadOfOnboarding() {
        assertFalse(shouldShowOnboarding(hasBaby = false, familyRole = FamilyRole.Member))
        assertEquals(
            "family",
            rootStartDestination(hasBaby = false, familyRole = FamilyRole.Member),
        )
    }

    @Test
    fun joinedOwnerWithoutBabyUsesFamilyShellInsteadOfOnboarding() {
        assertFalse(shouldShowOnboarding(hasBaby = false, familyRole = FamilyRole.Owner))
        assertEquals(
            "family",
            rootStartDestination(hasBaby = false, familyRole = FamilyRole.Owner),
        )
    }

    @Test
    fun pendingCreateBabyKeepsOnboardingUntilABabyExists() {
        assertTrue(
            shouldShowOnboarding(
                hasBaby = false,
                familyRole = FamilyRole.Owner,
                pendingCreateBaby = true,
            ),
        )
        assertFalse(
            shouldShowOnboarding(
                hasBaby = true,
                familyRole = FamilyRole.Owner,
                pendingCreateBaby = true,
            ),
        )
        assertEquals(
            "log",
            rootStartDestination(hasBaby = true, familyRole = FamilyRole.Owner),
        )
    }

    @Test
    fun unjoinedFirstRunStillUsesOnboarding() {
        assertTrue(shouldShowOnboarding(hasBaby = false, familyRole = FamilyRole.None))
        assertFalse(shouldShowOnboarding(hasBaby = true, familyRole = FamilyRole.None))
    }

    @Test
    fun unresolvedRootUiHoldsBlankFrameInsteadOfFlashingOnboarding() {
        assertNull(onboardingGateTarget(ui = null))
    }

    @Test
    fun resolvedRootUiRoutesGateThroughOnboardingPolicy() {
        assertEquals(
            java.lang.Boolean.TRUE,
            onboardingGateTarget(RootUi(hasBaby = false, familyRole = FamilyRole.None)),
        )
        assertEquals(
            java.lang.Boolean.FALSE,
            onboardingGateTarget(RootUi(hasBaby = true, familyRole = FamilyRole.None)),
        )
        assertEquals(
            java.lang.Boolean.FALSE,
            onboardingGateTarget(RootUi(hasBaby = false, familyRole = FamilyRole.Member)),
        )
    }

    @Test
    fun darkThemeFollowsStoredSettingWithSystemFallback() {
        assertTrue(leziDarkTheme(darkMode = "dark", systemDark = false))
        assertFalse(leziDarkTheme(darkMode = "light", systemDark = true))
        assertTrue(leziDarkTheme(darkMode = "system", systemDark = true))
        assertFalse(leziDarkTheme(darkMode = "system", systemDark = false))
        // Unknown values degrade to the system resolution, never force dark.
        assertFalse(leziDarkTheme(darkMode = "garbage", systemDark = false))
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
            108.dp,
            rootSnackbarBottomInset(route = "log", logLayoutEditActive = false, elder = true),
        )
        assertEquals(
            0.dp,
            rootSnackbarBottomInset(route = "log", logLayoutEditActive = true),
        )

        listOf("summary", "growth", "family", "settings", "timer", "search", "export", "calendar")
            .forEach { route ->
                assertEquals(
                    0.dp,
                    rootSnackbarBottomInset(route = route, logLayoutEditActive = false),
                )
            }
    }


    @Test
    fun localBaseUiCarriesElderModeWithoutRewritingStyleOrDarkMode() {
        val ui = rootUiFromLocalSettings(
            hasBaby = true,
            current = null,
            babies = emptyList(),
            settings = SettingsLocal(
                darkMode = "dark",
                visualStyle = "journal",
                elderMode = "l2",
            ),
            day = LocalDate.of(2026, 8, 19),
        )

        assertEquals("l2", ui.elderMode)
        assertEquals("journal", ui.visualStyle)
        assertEquals("dark", ui.darkMode)
        assertEquals(LocalDate.of(2026, 8, 19), ui.selectedDate)
        assertTrue(ui.hasBaby)
    }
}
