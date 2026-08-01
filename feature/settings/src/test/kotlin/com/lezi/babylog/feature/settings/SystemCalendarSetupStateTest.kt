package com.lezi.babylog.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class SystemCalendarSetupStateTest {
    @Test
    fun selectionIsDraftedUntilTargetAndDisclosureAreConfirmedTogether() {
        val initial = SystemCalendarSetupDraft.from(
            currentCalendarId = "calendar-old",
            currentDisclosureLevel = 1,
        )

        val edited = initial
            .selectDisclosureLevel(3)
            .selectCalendar("calendar-new")

        assertEquals("calendar-old", initial.confirmedOrNull()?.calendarId)
        assertEquals(1, initial.confirmedOrNull()?.disclosureLevel)
        assertEquals(
            SystemCalendarSetupSelection(
                calendarId = "calendar-new",
                disclosureLevel = 3,
            ),
            edited.confirmedOrNull(),
        )
    }

    @Test
    fun missingOrVanishedTargetCannotBeConfirmed() {
        val withoutTarget = SystemCalendarSetupDraft.from(
            currentCalendarId = null,
            currentDisclosureLevel = 2,
        )
        assertNull(withoutTarget.confirmedOrNull())

        val vanished = SystemCalendarSetupDraft.from(
            currentCalendarId = "calendar-removed",
            currentDisclosureLevel = 1,
        ).reconcileWritableCalendars(setOf("calendar-live"))

        assertNull(vanished.confirmedOrNull())
        assertEquals(1, vanished.disclosureLevel)
    }

    @Test
    fun disclosureLevelIsAlwaysNormalizedWithoutChangingTheTarget() {
        val draft = SystemCalendarSetupDraft.from("calendar-a", 99)

        assertEquals("calendar-a", draft.selectedCalendarId)
        assertEquals(3, draft.disclosureLevel)
        assertEquals(1, draft.selectDisclosureLevel(0).disclosureLevel)
    }

    @Test
    fun targetSummaryUsesCalendarNameAndNeverExposesProviderId() {
        val targets = listOf(
            com.lezi.babylog.domain.calendar.SystemCalendarTarget(
                calendarId = "37",
                displayName = "家庭日历",
                accountName = "care@example.com",
            ),
        )

        val summary = systemCalendarTargetSummary(
            calendarId = "37",
            hasPermission = true,
            targets = targets,
        )

        assertEquals("家庭日历 · care@example.com", summary)
        assertFalse(summary.contains("37"))
        assertEquals(
            "原日历不可用，请重新选择",
            systemCalendarTargetSummary("99", hasPermission = true, targets = targets),
        )
        assertEquals(
            "需要日历权限以确认目标",
            systemCalendarTargetSummary("37", hasPermission = false, targets = emptyList()),
        )
    }
}
