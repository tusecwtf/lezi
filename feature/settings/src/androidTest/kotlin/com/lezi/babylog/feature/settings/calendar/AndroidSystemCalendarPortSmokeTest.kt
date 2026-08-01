package com.lezi.babylog.feature.settings.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.domain.calendar.SystemCalendarDisclosureLevel
import com.lezi.babylog.domain.calendar.SystemCalendarDisclosurePolicy
import com.lezi.babylog.domain.calendar.SystemCalendarEventState
import com.lezi.babylog.domain.calendar.SystemCalendarOwnedEventLookup
import com.lezi.babylog.domain.calendar.SystemCalendarUpsert
import com.lezi.babylog.domain.calendar.SystemCalendarUpsertOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator / device smoke for [AndroidSystemCalendarPort] against real
 * CalendarContract (ticket 28 residual). Covers grant path, writable target,
 * L1–L3 disclosure content, create/edit/delete, deep link field, and the
 * no-photo-bytes/URI invariant. Interactive OEM calendar UI is not driven.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSystemCalendarPortSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val port = AndroidSystemCalendarPort(context)
    private val createdCalendarIds = mutableListOf<Long>()
    private val createdEventIds = mutableListOf<Long>()

    @Before
    fun grantCalendarPermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        // Library instrumented APK package (targetContext) needs the grants.
        val packages = linkedSetOf(
            context.packageName,
            instrumentation.context.packageName,
        )
        for (pkg in packages) {
            runCatching {
                automation.grantRuntimePermission(pkg, Manifest.permission.READ_CALENDAR)
            }
            runCatching {
                automation.grantRuntimePermission(pkg, Manifest.permission.WRITE_CALENDAR)
            }
            runCatching {
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant $pkg ${Manifest.permission.READ_CALENDAR}",
                ).close()
            }
            runCatching {
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant $pkg ${Manifest.permission.WRITE_CALENDAR}",
                ).close()
            }
        }
    }

    @After
    fun cleanup() {
        val resolver = context.contentResolver
        createdEventIds.forEach { id ->
            runCatching {
                resolver.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                    null,
                    null,
                )
            }
        }
        createdCalendarIds.forEach { id ->
            runCatching {
                val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id)
                    .buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                    .appendQueryParameter(
                        CalendarContract.Calendars.ACCOUNT_TYPE,
                        CalendarContract.ACCOUNT_TYPE_LOCAL,
                    )
                    .build()
                resolver.delete(uri, null, null)
            }
        }
    }

    @Test
    fun writableTargetL1L2L3CrudDeepLinkAndNoPhotoLeak() = runBlocking<Unit> {
        assumeTrue("Calendar permission not granted", port.hasCalendarPermission())

        val calendarId = ensureWritableLocalCalendar()
        assumeTrue("No writable calendar available", calendarId != null)
        val calId = calendarId!!

        assertThat(port.isWritableCalendar(calId)).isTrue()
        assertThat(port.listWritableCalendars().map { it.calendarId }).contains(calId)

        val begin = System.currentTimeMillis() + 3_600_000L
        val planUuid = "smoke-plan-${System.nanoTime()}"

        // L1
        val l1 = SystemCalendarDisclosurePolicy.build(
            level = SystemCalendarDisclosureLevel.EVENT_ONLY,
            babyNickname = "乐乐",
            recordTypeLabel = "配方奶",
            note = "不应出现在 L1",
            photoCount = 3,
            carePlanClientUuid = planUuid,
        )
        val inserted = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = calId,
                carePlanClientUuid = planUuid,
                beginAtMillis = begin,
                title = l1.title,
                description = l1.description,
                customAppUri = l1.deepLinkUri,
            ),
        )
        assertThat(inserted.outcome).isEqualTo(SystemCalendarUpsertOutcome.CurrentReady)
        val eventId = checkNotNull(inserted.eventId)
        createdEventIds += eventId.toLong()
        assertThat(readBeginReminder(eventId.toLong())).isTrue()
        assertThat(port.findOwnedEvent(planUuid))
            .isEqualTo(SystemCalendarOwnedEventLookup.Found(setOf(eventId)))
        val l1Row = readEvent(eventId.toLong())
        assertThat(l1Row.title).isEqualTo(SystemCalendarDisclosurePolicy.L1_TITLE)
        assertThat(l1Row.description.orEmpty()).doesNotContain("不应出现")
        assertThat(l1Row.description.orEmpty()).doesNotContain("照片")
        assertThat(l1Row.description.orEmpty()).doesNotContain("content://")
        assertThat(l1Row.description.orEmpty()).doesNotContain("file://")
        assertThat(l1Row.customAppUri).isNull()

        // L2 edit in place
        val l2 = SystemCalendarDisclosurePolicy.build(
            level = SystemCalendarDisclosureLevel.BABY_AND_TYPE,
            babyNickname = "乐乐",
            recordTypeLabel = "配方奶",
            note = null,
            photoCount = 0,
            carePlanClientUuid = planUuid,
        )
        val sameId = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = calId,
                carePlanClientUuid = planUuid,
                beginAtMillis = begin + 60_000L,
                title = l2.title,
                description = l2.description,
                existingEventId = eventId,
                customAppUri = l2.deepLinkUri,
            ),
        )
        assertThat(sameId.eventId).isEqualTo(eventId)
        assertThat(sameId.outcome).isEqualTo(SystemCalendarUpsertOutcome.CurrentReady)
        val l2Row = readEvent(eventId.toLong())
        assertThat(l2Row.title).isEqualTo("乐乐 · 配方奶")

        // L3 with note + photo count line + deep link; still no photo bytes/URIs.
        val l3 = SystemCalendarDisclosurePolicy.build(
            level = SystemCalendarDisclosureLevel.DETAILS,
            babyNickname = "乐乐",
            recordTypeLabel = "配方奶",
            note = "备注含本地路径 photos/secret.jpg 也不应被拆成 URI 字段",
            photoCount = 2,
            carePlanClientUuid = planUuid,
        )
        assertThat(l3.deepLinkUri).isEqualTo("lezi://care-plan/$planUuid")
        assertThat(l3.description).contains("照片 2 张，打开乐记查看")
        assertThat(l3.description).doesNotContain("content://")
        assertThat(l3.description).doesNotContain("file://")
        val l3Result = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = calId,
                carePlanClientUuid = planUuid,
                beginAtMillis = begin + 120_000L,
                title = l3.title,
                description = l3.description,
                existingEventId = null,
                customAppUri = l3.deepLinkUri,
            ),
        )
        assertThat(l3Result.eventId).isEqualTo(eventId)
        assertThat(l3Result.outcome).isEqualTo(SystemCalendarUpsertOutcome.CurrentReady)
        val l3Row = readEvent(eventId.toLong())
        assertThat(l3Row.title).isEqualTo("乐乐 · 配方奶")
        assertThat(l3Row.description).contains("照片 2 张，打开乐记查看")
        assertThat(l3Row.description).contains("lezi://care-plan/$planUuid")
        // Note text may be present; provider must not gain attachment/location photo paths.
        assertThat(l3Row.eventLocation).isNull()
        assertThat(l3Row.customAppUri).isEqualTo("lezi://care-plan/$planUuid")
        assertThat(l3Row.description).doesNotContain("content://")
        assertThat(l3Row.description).doesNotContain("file://")

        assertThat(port.eventExists(eventId)).isTrue()
        assertThat(port.deleteEvent(eventId)).isTrue()
        assertThat(port.eventExists(eventId)).isFalse()
        assertThat(port.findOwnedEvent(planUuid)).isEqualTo(SystemCalendarOwnedEventLookup.Absent)
        createdEventIds.remove(eventId.toLong())
    }

    @Test
    fun providerFailureDoesNotThrowFromPort() = runBlocking<Unit> {
        assumeTrue("Calendar permission not granted", port.hasCalendarPermission())
        // Non-numeric calendar id → null, not exception (CareLog must keep plan save).
        // Numeric-but-missing ids are OEM-dependent (some AOSP builds still insert).
        val result = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = "not-a-calendar-id",
                carePlanClientUuid = "fail-plan",
                beginAtMillis = System.currentTimeMillis() + 60_000L,
                title = "乐记 · 护理计划",
                description = null,
            ),
        )
        assertThat(result.eventId).isNull()
        assertThat(result.outcome).isEqualTo(SystemCalendarUpsertOutcome.ReleasedOrAbsent)
        assertThat(port.deleteEvent("not-a-number")).isFalse()
        assertThat(port.eventExists("not-a-number")).isFalse()
        // Confirmed missing id/UID still cannot insert into an invalid target.
        val updateMissing = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = "not-a-calendar-id",
                carePlanClientUuid = "fail-plan-2",
                beginAtMillis = System.currentTimeMillis() + 60_000L,
                title = "乐记 · 护理计划",
                description = null,
                existingEventId = "999999999999",
            ),
        )
        assertThat(updateMissing.eventId).isNull()
        assertThat(updateMissing.outcome)
            .isEqualTo(SystemCalendarUpsertOutcome.ReleasedOrAbsent)
    }

    @Test
    fun duplicateOwnedEventsConvergeToOneCanonicalProjection() = runBlocking<Unit> {
        assumeTrue("Calendar permission not granted", port.hasCalendarPermission())
        val calendarId = createWritableLocalCalendar()
        assumeTrue("Cannot create writable local calendar", calendarId != null)
        val calId = calendarId!!
        val planUuid = "duplicate-plan-${System.nanoTime()}"
        val begin = System.currentTimeMillis() + 7_200_000L

        val first = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = calId,
                carePlanClientUuid = planUuid,
                beginAtMillis = begin,
                title = "first",
            ),
        )
        val firstEventId = checkNotNull(first.eventId)
        createdEventIds += firstEventId.toLong()
        val duplicate2 = rawInsertOwnedEvent(calId, planUuid, begin + 60_000L)
        val duplicate3 = rawInsertOwnedEvent(calId, planUuid, begin + 120_000L)
        createdEventIds += duplicate2
        createdEventIds += duplicate3
        rawInsertReminder(duplicate2, minutes = 15)
        rawInsertReminder(duplicate3, minutes = 30)
        assertThat(readAnyReminder(duplicate2)).isTrue()
        assertThat(readAnyReminder(duplicate3)).isTrue()

        assertThat(port.findOwnedEvent(planUuid)).isEqualTo(
            SystemCalendarOwnedEventLookup.Found(
                setOf(firstEventId, duplicate2.toString(), duplicate3.toString()),
            ),
        )

        val converged = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = calId,
                carePlanClientUuid = planUuid,
                beginAtMillis = begin + 180_000L,
                title = "canonical",
            ),
        )
        assertThat(converged.eventId).isEqualTo(firstEventId)
        assertThat(converged.outcome).isEqualTo(SystemCalendarUpsertOutcome.CurrentReady)
        assertThat(port.findOwnedEvent(planUuid))
            .isEqualTo(SystemCalendarOwnedEventLookup.Found(setOf(firstEventId)))
        assertThat(port.eventState(duplicate2.toString())).isEqualTo(SystemCalendarEventState.ABSENT)
        assertThat(port.eventState(duplicate3.toString())).isEqualTo(SystemCalendarEventState.ABSENT)
        assertThat(readAnyReminder(duplicate2)).isFalse()
        assertThat(readAnyReminder(duplicate3)).isFalse()
        createdEventIds.remove(duplicate2)
        createdEventIds.remove(duplicate3)
    }

    @Test
    fun deletedProviderTombstoneIsStrictlyAbsent() = runBlocking<Unit> {
        assumeTrue("Calendar permission not granted", port.hasCalendarPermission())
        val calendarId = createWritableLocalCalendar()
        assumeTrue("Cannot create writable local calendar", calendarId != null)
        val calId = calendarId!!
        val planUuid = "tombstone-plan-${System.nanoTime()}"
        val inserted = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = calId,
                carePlanClientUuid = planUuid,
                beginAtMillis = System.currentTimeMillis() + 3_600_000L,
                title = "tombstone",
            ),
        )
        val eventId = checkNotNull(inserted.eventId).toLong()
        createdEventIds += eventId
        val syncAdapterUri = ContentUris.withAppendedId(
            CalendarContract.Events.CONTENT_URI,
            eventId,
        ).buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL,
            )
            .build()
        val updated = context.contentResolver.update(
            syncAdapterUri,
            ContentValues().apply { put(CalendarContract.Events.DELETED, 1) },
            null,
            null,
        )
        assumeTrue("Calendar provider cannot create a tombstone", updated > 0)

        assertThat(port.eventState(eventId.toString())).isEqualTo(SystemCalendarEventState.ABSENT)
        assertThat(port.findOwnedEvent(planUuid)).isEqualTo(SystemCalendarOwnedEventLookup.Absent)
    }

    @Test
    fun staleIdsNeverMutateEventsWithTheWrongUidOrPackage() = runBlocking<Unit> {
        assumeTrue("Calendar permission not granted", port.hasCalendarPermission())
        val calendarId = createWritableLocalCalendar()
        assumeTrue("Cannot create writable local calendar", calendarId != null)
        val calId = calendarId!!
        val begin = System.currentTimeMillis() + 10_800_000L

        val packagePlan = "package-owner-${System.nanoTime()}"
        val wrongPackageId = rawInsertOwnedEvent(
            calendarId = calId,
            carePlanClientUuid = packagePlan,
            beginAtMillis = begin,
            appPackage = "com.example.not.lezi",
        )
        createdEventIds += wrongPackageId
        assertThat(port.eventState(wrongPackageId.toString()))
            .isEqualTo(SystemCalendarEventState.ABSENT)
        assertThat(port.eventState(wrongPackageId.toString(), packagePlan))
            .isEqualTo(SystemCalendarEventState.ABSENT)
        val packageResult = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = calId,
                carePlanClientUuid = packagePlan,
                beginAtMillis = begin + 60_000L,
                title = "owned package",
                existingEventId = wrongPackageId.toString(),
            ),
        )
        createdEventIds += checkNotNull(packageResult.eventId).toLong()
        assertThat(packageResult.eventId).isNotEqualTo(wrongPackageId.toString())
        assertThat(readEvent(wrongPackageId).title).isEqualTo("duplicate")
        assertThat(port.deleteEvent(wrongPackageId.toString())).isFalse()
        assertThat(port.deleteEvent(wrongPackageId.toString(), packagePlan)).isFalse()

        val uidPlan = "uid-owner-${System.nanoTime()}"
        val wrongUidId = rawInsertOwnedEvent(
            calendarId = calId,
            carePlanClientUuid = "$uidPlan-other",
            beginAtMillis = begin,
        )
        createdEventIds += wrongUidId
        assertThat(port.eventState(wrongUidId.toString(), uidPlan))
            .isEqualTo(SystemCalendarEventState.ABSENT)
        val uidResult = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = calId,
                carePlanClientUuid = uidPlan,
                beginAtMillis = begin + 120_000L,
                title = "owned uid",
                existingEventId = wrongUidId.toString(),
            ),
        )
        createdEventIds += checkNotNull(uidResult.eventId).toLong()
        assertThat(uidResult.eventId).isNotEqualTo(wrongUidId.toString())
        assertThat(readEvent(wrongUidId).title).isEqualTo("duplicate")
        assertThat(port.deleteEvent(wrongUidId.toString(), uidPlan)).isFalse()
    }

    @Test
    fun unwritableTargetReleasesTheOldProviderReminderBeforeFallback() = runBlocking<Unit> {
        assumeTrue("Calendar permission not granted", port.hasCalendarPermission())
        val calendarId = createWritableLocalCalendar()
        assumeTrue("Cannot create writable local calendar", calendarId != null)
        val calId = calendarId!!
        val planUuid = "unwritable-target-${System.nanoTime()}"
        val begin = System.currentTimeMillis() + 14_400_000L
        val inserted = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = calId,
                carePlanClientUuid = planUuid,
                beginAtMillis = begin,
                title = "before target loss",
            ),
        )
        val eventId = checkNotNull(inserted.eventId).toLong()
        createdEventIds += eventId
        assertThat(readBeginReminder(eventId)).isTrue()

        val calendarUri = ContentUris.withAppendedId(
            CalendarContract.Calendars.CONTENT_URI,
            calId.toLong(),
        ).buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL,
            )
            .build()
        val downgraded = context.contentResolver.update(
            calendarUri,
            ContentValues().apply {
                put(
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                    CalendarContract.Calendars.CAL_ACCESS_READ,
                )
            },
            null,
            null,
        )
        assumeTrue("Calendar provider cannot downgrade target", downgraded > 0)
        assertThat(port.isWritableCalendar(calId)).isFalse()

        val result = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = calId,
                carePlanClientUuid = planUuid,
                beginAtMillis = begin + 60_000L,
                title = "after target loss",
                existingEventId = eventId.toString(),
                providerHandoffMayExist = true,
            ),
        )
        assertThat(result.outcome).isEqualTo(SystemCalendarUpsertOutcome.ReleasedOrAbsent)
        assertThat(result.eventId).isEqualTo(eventId.toString())
        assertThat(readAnyReminder(eventId)).isFalse()
    }

    @Test
    fun extraProviderReminderIsRemovedBeforeProjectionBecomesReady() = runBlocking<Unit> {
        assumeTrue("Calendar permission not granted", port.hasCalendarPermission())
        val calendarId = createWritableLocalCalendar()
        assumeTrue("Cannot create writable local calendar", calendarId != null)
        val calId = calendarId!!
        val planUuid = "extra-reminder-${System.nanoTime()}"
        val begin = System.currentTimeMillis() + 18_000_000L

        val inserted = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = calId,
                carePlanClientUuid = planUuid,
                beginAtMillis = begin,
                title = "before reminder repair",
            ),
        )
        val eventId = checkNotNull(inserted.eventId).toLong()
        createdEventIds += eventId
        rawInsertReminder(eventId, minutes = 15)
        assertThat(readReminderRows(eventId)).containsExactly(
            0 to CalendarContract.Reminders.METHOD_ALERT,
            15 to CalendarContract.Reminders.METHOD_ALERT,
        )

        val repaired = port.upsertEvent(
            SystemCalendarUpsert(
                calendarId = calId,
                carePlanClientUuid = planUuid,
                beginAtMillis = begin + 60_000L,
                title = "after reminder repair",
                existingEventId = eventId.toString(),
            ),
        )

        assertThat(repaired.eventId).isEqualTo(eventId.toString())
        assertThat(repaired.outcome).isEqualTo(SystemCalendarUpsertOutcome.CurrentReady)
        assertThat(readReminderRows(eventId)).containsExactly(
            0 to CalendarContract.Reminders.METHOD_ALERT,
        )
    }

    private fun readBeginReminder(eventId: Long): Boolean {
        context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders._ID),
            "${CalendarContract.Reminders.EVENT_ID}=? AND " +
                "${CalendarContract.Reminders.MINUTES}=? AND " +
                "${CalendarContract.Reminders.METHOD}=?",
            arrayOf(
                eventId.toString(),
                "0",
                CalendarContract.Reminders.METHOD_ALERT.toString(),
            ),
            null,
        )?.use { cursor -> return cursor.moveToFirst() }
        return false
    }

    private fun readAnyReminder(eventId: Long): Boolean {
        context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders._ID),
            "${CalendarContract.Reminders.EVENT_ID}=?",
            arrayOf(eventId.toString()),
            null,
        )?.use { cursor -> return cursor.moveToFirst() }
        return false
    }

    private fun readReminderRows(eventId: Long): List<Pair<Int, Int>> {
        context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(
                CalendarContract.Reminders.MINUTES,
                CalendarContract.Reminders.METHOD,
            ),
            "${CalendarContract.Reminders.EVENT_ID}=?",
            arrayOf(eventId.toString()),
            null,
        )?.use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(cursor.getInt(0) to cursor.getInt(1))
            }
        }
        return emptyList()
    }

    private fun ensureWritableLocalCalendar(): String? {
        val existing = runBlocking { port.listWritableCalendars() }.firstOrNull()
        if (existing != null) return existing.calendarId

        return createWritableLocalCalendar()
    }

    private fun createWritableLocalCalendar(): String? {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "lezi-smoke")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "乐记测试日历")
            put(
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_OWNER,
            )
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL,
            )
            .build()
        val inserted = context.contentResolver.insert(uri, values) ?: return null
        val id = ContentUris.parseId(inserted)
        createdCalendarIds += id
        return id.toString()
    }

    private fun rawInsertOwnedEvent(
        calendarId: String,
        carePlanClientUuid: String,
        beginAtMillis: Long,
        appPackage: String = context.packageName,
    ): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId.toLong())
            put(CalendarContract.Events.TITLE, "duplicate")
            put(CalendarContract.Events.DTSTART, beginAtMillis)
            put(CalendarContract.Events.DTEND, beginAtMillis + 30L * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            put(CalendarContract.Events.HAS_ALARM, 1)
            put(CalendarContract.Events.CUSTOM_APP_PACKAGE, appPackage)
            put(CalendarContract.Events.UID_2445, "lezi-care-plan-$carePlanClientUuid")
        }
        val uri = checkNotNull(
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values),
        )
        return ContentUris.parseId(uri)
    }

    private fun rawInsertReminder(eventId: Long, minutes: Int) {
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        checkNotNull(context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values))
    }

    private data class EventRow(
        val title: String?,
        val description: String?,
        val eventLocation: String?,
        val customAppUri: String?,
    )

    private fun readEvent(eventId: Long): EventRow {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.CUSTOM_APP_URI,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            check(cursor.moveToFirst()) { "event $eventId missing" }
            return EventRow(
                title = cursor.getString(0),
                description = cursor.getString(1),
                eventLocation = cursor.getString(2),
                customAppUri = cursor.getString(3),
            )
        } ?: error("query failed for event $eventId")
    }

    companion object {
        private const val ACCOUNT_NAME = "lezi-smoke@local"
    }
}
