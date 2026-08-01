package com.lezi.babylog.feature.settings.calendar

import com.lezi.babylog.domain.calendar.SystemCalendarEventState
import com.lezi.babylog.domain.calendar.SystemCalendarOwnedEventLookup
import com.lezi.babylog.domain.calendar.SystemCalendarUpsertOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemCalendarProviderLookupTest {
    @Test
    fun extraProviderReminderIsNotAnExactBeginAlertSet() {
        assertEquals(
            SystemCalendarReminderSetState.STALE,
            strictSystemCalendarReminderSetState(
                eventId = "41",
                alertMethod = 1,
            ) {
                listOf(
                    SystemCalendarReminderRow(minutes = 0, method = 1),
                    SystemCalendarReminderRow(minutes = 15, method = 1),
                )
            },
        )
    }

    @Test
    fun oneBeginAlertIsTheOnlyExactReminderSet() {
        assertEquals(
            SystemCalendarReminderSetState.EXACT_BEGIN_ALERT,
            strictSystemCalendarReminderSetState(
                eventId = "41",
                alertMethod = 1,
            ) {
                listOf(SystemCalendarReminderRow(minutes = 0, method = 1))
            },
        )
        assertEquals(
            SystemCalendarReminderSetState.STALE,
            strictSystemCalendarReminderSetState(
                eventId = "41",
                alertMethod = 1,
            ) {
                listOf(SystemCalendarReminderRow(minutes = 0, method = 2))
            },
        )
        assertEquals(
            SystemCalendarReminderSetState.STALE,
            strictSystemCalendarReminderSetState(
                eventId = "41",
                alertMethod = 1,
            ) {
                listOf(
                    SystemCalendarReminderRow(minutes = 0, method = 1),
                    SystemCalendarReminderRow(minutes = 0, method = 1),
                )
            },
        )
    }

    @Test
    fun reminderSetAbsenceRequiresASuccessfulEmptyQuery() {
        assertEquals(
            SystemCalendarReminderSetState.ABSENT,
            strictSystemCalendarReminderSetState("41", alertMethod = 1) { emptyList() },
        )
        assertEquals(
            SystemCalendarReminderSetState.UNAVAILABLE,
            strictSystemCalendarReminderSetState("41", alertMethod = 1) { null },
        )
        assertEquals(
            SystemCalendarReminderSetState.UNAVAILABLE,
            strictSystemCalendarReminderSetState("41", alertMethod = 1) {
                error("provider failed")
            },
        )
        assertEquals(
            SystemCalendarReminderSetState.UNAVAILABLE,
            strictSystemCalendarReminderSetState("not-an-id", alertMethod = 1) { emptyList() },
        )
    }

    @Test
    fun matchingEventWithAStaleReminderSetRemainsPending() {
        assertEquals(
            SystemCalendarUpsertOutcome.ProviderStillOwnsStale,
            resolveSystemCalendarUpsertOutcome(
                requestedEventState = SystemCalendarRequestedEventState.MATCH,
                reminderSetState = SystemCalendarReminderSetState.STALE,
            ),
        )
    }

    @Test
    fun onlyAnEmptySuccessfulQueryIsConfirmedAbsent() {
        assertEquals(
            SystemCalendarEventState.PRESENT,
            strictSystemCalendarEventState(true, "41") { true },
        )
        assertEquals(
            SystemCalendarEventState.ABSENT,
            strictSystemCalendarEventState(true, "41") { false },
        )
        assertEquals(
            SystemCalendarEventState.UNAVAILABLE,
            strictSystemCalendarEventState(true, "41") { null },
        )
        assertEquals(
            SystemCalendarEventState.UNAVAILABLE,
            strictSystemCalendarEventState(true, "41") { error("provider failed") },
        )
        assertEquals(
            SystemCalendarEventState.UNAVAILABLE,
            strictSystemCalendarEventState(false, "41") { true },
        )
        assertEquals(
            SystemCalendarEventState.UNAVAILABLE,
            strictSystemCalendarEventState(true, "not-an-id") { true },
        )
    }

    @Test
    fun ownedEventLookupRequiresASuccessfulUidAndPackageQuery() {
        var queriedUid: String? = null
        var queriedPackage: String? = null

        assertEquals(
            SystemCalendarOwnedEventLookup.Found(setOf("41", "52", "63")),
            strictOwnedSystemCalendarEventLookup(
                hasPermission = true,
                carePlanClientUuid = "plan-uuid",
                appPackage = "com.lezi.babylog",
            ) { uid, appPackage ->
                queriedUid = uid
                queriedPackage = appPackage
                listOf("63", "41", "52")
            },
        )
        assertEquals("lezi-care-plan-plan-uuid", queriedUid)
        assertEquals("com.lezi.babylog", queriedPackage)
        assertEquals(
            SystemCalendarOwnedEventLookup.Absent,
            strictOwnedSystemCalendarEventLookup(true, "plan-uuid", "com.lezi.babylog") { _, _ ->
                emptyList()
            },
        )
        assertEquals(
            SystemCalendarOwnedEventLookup.Unavailable,
            strictOwnedSystemCalendarEventLookup(true, "plan-uuid", "com.lezi.babylog") { _, _ ->
                null
            },
        )
        assertEquals(
            SystemCalendarOwnedEventLookup.Unavailable,
            strictOwnedSystemCalendarEventLookup(true, "plan-uuid", "com.lezi.babylog") { _, _ ->
                error("provider failed")
            },
        )
        assertEquals(
            SystemCalendarOwnedEventLookup.Unavailable,
            strictOwnedSystemCalendarEventLookup(false, "plan-uuid", "com.lezi.babylog") { _, _ ->
                listOf("41")
            },
        )
    }

    @Test
    fun unavailableLookupNeverBecomesABlindInsert() {
        assertEquals(
            SystemCalendarUpsertTarget.RetryKnown("41"),
            selectSystemCalendarUpsertTarget(
                existingEventId = "41",
                existingState = SystemCalendarEventState.UNAVAILABLE,
                ownedLookup = SystemCalendarOwnedEventLookup.Unavailable,
            ),
        )
        assertEquals(
            SystemCalendarUpsertTarget.Unavailable,
            selectSystemCalendarUpsertTarget(
                existingEventId = null,
                existingState = null,
                ownedLookup = SystemCalendarOwnedEventLookup.Unavailable,
            ),
        )
        assertEquals(
            SystemCalendarUpsertTarget.Insert,
            selectSystemCalendarUpsertTarget(
                existingEventId = null,
                existingState = null,
                ownedLookup = SystemCalendarOwnedEventLookup.Absent,
            ),
        )
    }

    @Test
    fun stableUidReidentifiesAnEventOnlyAfterMappedIdIsConfirmedAbsent() {
        assertEquals(
            SystemCalendarUpsertTarget.Update("41"),
            selectSystemCalendarUpsertTarget(
                existingEventId = "99",
                existingState = SystemCalendarEventState.ABSENT,
                ownedLookup = SystemCalendarOwnedEventLookup.Found(
                    setOf("52", "41", "63"),
                ),
            ),
        )
        assertEquals(
            SystemCalendarUpsertTarget.Update("41"),
            selectSystemCalendarUpsertTarget(
                existingEventId = "41",
                existingState = SystemCalendarEventState.PRESENT,
                ownedLookup = SystemCalendarOwnedEventLookup.Unavailable,
            ),
        )
    }

    @Test
    fun updateZeroWithAnOldGenerationReminderIsNotCurrentReady() {
        val oldGeneration = resolveSystemCalendarUpsertOutcome(
            requestedEventState = SystemCalendarRequestedEventState.STALE,
            reminderSetState = SystemCalendarReminderSetState.EXACT_BEGIN_ALERT,
        )
        assertEquals(
            SystemCalendarUpsertOutcome.ProviderStillOwnsStale,
            oldGeneration,
        )
        assertEquals(
            SystemCalendarUpsertOutcome.ReleasedOrAbsent,
            resolveReleasedProviderReminders(listOf(SystemCalendarEventState.ABSENT)),
        )
        assertEquals(
            SystemCalendarUpsertOutcome.ProviderStillOwnsStale,
            resolveReleasedProviderReminders(listOf(SystemCalendarEventState.UNAVAILABLE)),
        )
        assertEquals(
            SystemCalendarUpsertOutcome.CurrentReady,
            resolveSystemCalendarUpsertOutcome(
                requestedEventState = SystemCalendarRequestedEventState.MATCH,
                reminderSetState = SystemCalendarReminderSetState.EXACT_BEGIN_ALERT,
            ),
        )
    }

    @Test
    fun releaseRequiresAllProviderRemindersAbsentNotOnlyBeginReminder() {
        assertEquals(
            SystemCalendarUpsertOutcome.ProviderStillOwnsStale,
            resolveSystemCalendarUpsertOutcome(
                requestedEventState = SystemCalendarRequestedEventState.MATCH,
                reminderSetState = SystemCalendarReminderSetState.STALE,
            ),
        )
        assertEquals(
            SystemCalendarUpsertOutcome.ProviderStillOwnsStale,
            resolveSystemCalendarUpsertOutcome(
                requestedEventState = SystemCalendarRequestedEventState.MATCH,
                reminderSetState = SystemCalendarReminderSetState.UNAVAILABLE,
            ),
        )
        assertEquals(
            SystemCalendarUpsertOutcome.ReleasedOrAbsent,
            resolveSystemCalendarUpsertOutcome(
                requestedEventState = SystemCalendarRequestedEventState.MATCH,
                reminderSetState = SystemCalendarReminderSetState.ABSENT,
            ),
        )
    }

    @Test
    fun providerReleaseRequiresEveryOwnedReminderToBeConfirmedAbsent() {
        assertEquals(
            SystemCalendarUpsertOutcome.ReleasedOrAbsent,
            resolveReleasedProviderReminders(
                listOf(SystemCalendarEventState.ABSENT, SystemCalendarEventState.ABSENT),
            ),
        )
        assertEquals(
            SystemCalendarUpsertOutcome.ProviderStillOwnsStale,
            resolveReleasedProviderReminders(
                listOf(SystemCalendarEventState.ABSENT, SystemCalendarEventState.PRESENT),
            ),
        )
        assertEquals(
            SystemCalendarUpsertOutcome.ProviderStillOwnsStale,
            resolveReleasedProviderReminders(
                listOf(SystemCalendarEventState.UNAVAILABLE),
            ),
        )
    }

    @Test
    fun ownedDeleteFailsClosedWhenAnOrphanReminderMayRemain() {
        assertEquals(
            true,
            strictOwnedEventDeleteSucceeded(
                eventState = SystemCalendarEventState.ABSENT,
                reminderState = SystemCalendarEventState.ABSENT,
            ),
        )
        assertEquals(
            false,
            strictOwnedEventDeleteSucceeded(
                eventState = SystemCalendarEventState.ABSENT,
                reminderState = SystemCalendarEventState.PRESENT,
            ),
        )
        assertEquals(
            false,
            strictOwnedEventDeleteSucceeded(
                eventState = SystemCalendarEventState.ABSENT,
                reminderState = SystemCalendarEventState.UNAVAILABLE,
            ),
        )
    }

    @Test
    fun duplicateConvergenceRejectsADeletedEventWithARemainingReminder() {
        val canonicalOnly = SystemCalendarOwnedEventLookup.Found(setOf("41"))
        assertEquals(
            true,
            strictOwnedDuplicatesConverged(
                ownedLookup = canonicalOnly,
                canonicalEventId = "41",
                duplicateReminderStates = listOf(
                    SystemCalendarEventState.ABSENT,
                    SystemCalendarEventState.ABSENT,
                ),
            ),
        )
        assertEquals(
            false,
            strictOwnedDuplicatesConverged(
                ownedLookup = canonicalOnly,
                canonicalEventId = "41",
                duplicateReminderStates = listOf(
                    SystemCalendarEventState.ABSENT,
                    SystemCalendarEventState.PRESENT,
                ),
            ),
        )
        assertEquals(
            false,
            strictOwnedDuplicatesConverged(
                ownedLookup = SystemCalendarOwnedEventLookup.Unavailable,
                canonicalEventId = "41",
                duplicateReminderStates = emptyList(),
            ),
        )
    }
}
